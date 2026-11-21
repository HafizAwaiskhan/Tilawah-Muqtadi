package com.qurancompanion.session

import com.qurancompanion.core.audio.EnergyVad
import com.qurancompanion.core.audio.InterruptionSignal
import com.qurancompanion.core.playback.AyahAudio
import com.qurancompanion.core.playback.PlaybackEvent
import com.qurancompanion.core.playback.QariAudioResolver
import com.qurancompanion.core.playback.QuranAudioPlayback
import com.qurancompanion.core.recognition.ModelReadiness
import com.qurancompanion.core.recognition.QuranRecognitionEngine
import com.qurancompanion.core.time.Clock
import com.qurancompanion.data.db.SessionEntity
import com.qurancompanion.data.db.SessionStatus
import com.qurancompanion.data.reference.EngineReferenceSource
import com.qurancompanion.data.reference.EngineReferenceStatus
import com.qurancompanion.data.reference.QuranPosition
import com.qurancompanion.data.session.SessionRepository
import com.qurancompanion.data.settings.RecitationMode
import com.qurancompanion.data.settings.SettingsRepository
import com.qurancompanion.data.settings.UserSettings
import com.qurancompanion.di.ApplicationScope
import com.qurancompanion.di.EngineDispatcher
import com.qurancompanion.engine.recognition.RecognitionEvent
import com.qurancompanion.engine.recognition.RecognizerStatus
import com.qurancompanion.engine.reference.QuranReference
import com.qurancompanion.engine.session.HintLevel
import com.qurancompanion.engine.session.HintRequest
import com.qurancompanion.engine.session.PracticeMode
import com.qurancompanion.engine.session.RecitationSessionController
import com.qurancompanion.engine.session.SessionConfig
import com.qurancompanion.engine.session.SessionInput
import com.qurancompanion.engine.session.SessionSnapshot
import com.qurancompanion.engine.session.SessionUiState
import com.qurancompanion.engine.session.SessionState as EngineState
import com.qurancompanion.engine.session.SessionStatus as EngineStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android adapter around the engine's [RecitationSessionController] (docs/ENGINE.md).
 *
 * - The controller is created once the engine reference is loaded and is only touched from one
 *   coroutine (an input channel consumed on [engineDispatcher]), so `handle()` is single-threaded.
 * - Inputs: lifecycle (Initialize, permission/model/reference readiness, Start/Recover), recognizer
 *   events + VAD edges ([runRecognizer]), a 250 ms Tick while a session exists, user actions,
 *   playback start/end ([QuranAudioPlayback]) and interruptions.
 * - Persistence: the controller snapshot is written to Room, in order on a single queue, whenever
 *   the confirmed position or the session status changes, and on stop (process-death recovery).
 * - Hints: when the controller exposes an [HintRequest] the clip is resolved and played; with no
 *   Qari audio a gentle notice is shown and the session resumes immediately.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class RecitationSessionManager @Inject constructor(
    private val sessions: SessionRepository,
    private val settings: SettingsRepository,
    private val playback: QuranAudioPlayback,
    private val qariAudio: QariAudioResolver,
    private val clock: Clock,
    private val reference: EngineReferenceSource,
    private val recognizer: QuranRecognitionEngine,
    @ApplicationScope private val scope: CoroutineScope,
    @EngineDispatcher private val engineDispatcher: CoroutineDispatcher,
) {
    private val _state = MutableStateFlow(LiveSession())
    val state: StateFlow<LiveSession> = _state.asStateFlow()

    private sealed interface Op {
        data class Input(val input: SessionInput) : Op
        data class Run(val block: (RecitationSessionController, QuranReference) -> Unit) : Op
    }

    private val ops = Channel<Op>(Channel.UNLIMITED)
    private val persistQueue = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val prefs: StateFlow<UserSettings> = settings.settings.stateIn(scope, SharingStarted.Eagerly, UserSettings())

    // Engine-loop confined.
    private var controller: RecitationSessionController? = null
    @Volatile private var ref: QuranReference? = null
    @Volatile private var recognizerBound = false

    // Persistence-queue confined.
    private var roomId: Long? = null
    private var startLocated = true

    private var tailJob: Job? = null

    init {
        scope.launch { for (op in persistQueue) runCatching { op() } }
        scope.launch(engineDispatcher) { engineLoop() }
        scope.launch {
            recognizer.readiness.collect { m ->
                send(if (m is ModelReadiness.Downloading) SessionInput.ModelDownloadProgress(m.progress) else SessionInput.ModelReady)
            }
        }
        scope.launch { playback.events.collect(::onPlaybackEvent) }
        scope.launch(engineDispatcher) {
            state.map { it.state in EngineState.IN_SESSION }.distinctUntilChanged().collectLatest { active ->
                if (active) while (true) { delay(TICK_MS); send(SessionInput.Tick) }
            }
        }
    }

    private suspend fun engineLoop() {
        val r = reference.get()
        if (r == null) {
            _state.update { it.copy(readiness = TrackingReadiness.UNAVAILABLE) }
            for (op in ops) Unit // drain: nothing can run without the reference
            return
        }
        ref = r
        val c = RecitationSessionController(
            r, recognizer.unit, { clock.elapsedRealtime() }, SessionConfig(),
            newSessionId = { UUID.randomUUID().toString() },
        )
        controller = c
        c.handle(SessionInput.Initialize)
        val loadMs = (reference.status.value as? EngineReferenceStatus.Ready)?.loadMs
        _state.update { it.copy(readiness = TrackingReadiness.READY, referenceLoadMs = loadMs, engine = c.uiState.value) }
        for (op in ops) {
            runCatching {
                when (op) {
                    is Op.Input -> process(c, op.input)
                    is Op.Run -> op.block(c, r)
                }
            }.onFailure { e -> runCatching { process(c, SessionInput.Failure(e.message ?: e.javaClass.simpleName)) } }
        }
    }

    private fun send(input: SessionInput) { ops.trySend(Op.Input(input)) }
    private fun onEngine(block: (RecitationSessionController, QuranReference) -> Unit) { ops.trySend(Op.Run(block)) }

    /** Engine loop only. */
    private fun process(c: RecitationSessionController, input: SessionInput) {
        val before = c.uiState.value
        c.handle(input)
        afterHandle(c, before, c.uiState.value)
    }

    private fun afterHandle(c: RecitationSessionController, b: SessionUiState, a: SessionUiState) {
        val snap = c.snapshot()
        _state.update { s ->
            s.copy(
                engine = a,
                durationMs = (snap?.durationMs ?: s.durationMs) / 1000 * 1000,
                interruption = if (a.state == EngineState.INTERRUPTED) s.interruption else null,
                notice = if (a.confirmed != b.confirmed && b.confirmed != null) null else s.notice,
            )
        }
        // New session row.
        if (a.sessionId != b.sessionId && a.state == EngineState.STARTING_SESSION) {
            val requested = _state.value.requestedStart
            val start = requested ?: QuranPosition.START
            val startedAt = clock.nowMillis()
            persistQueue.trySend {
                val id = sessions.create(
                    SessionEntity(
                        startedAt = startedAt, status = SessionStatus.ACTIVE,
                        startSurah = start.surah, startAyah = start.ayah,
                        lastSurah = start.surah, lastAyah = start.ayah, lastWord = 0,
                    )
                )
                roomId = id
                startLocated = requested != null
                _state.update { it.copy(sessionId = id, startPosition = start) }
            }
        }
        if (snap != null) {
            val statusChanged = snapshotStatus(b.state) != snapshotStatus(a.state)
            val stopped = a.state == EngineState.STOPPED && b.state != EngineState.STOPPED
            if (a.confirmed != b.confirmed || statusChanged || stopped) persist(snap, stopped)
        }
        if (recognizerBound && a.state != b.state &&
            (a.state == EngineState.STARTING_SESSION || a.state == EngineState.RECOVERING_SESSION)
        ) process(c, SessionInput.RecognizerStatusChanged(RecognizerStatus.Listening))
        // Hint requested by an explicit user action.
        val hint = a.activeHint
        if (hint != null && hint != b.activeHint && a.state in EngineState.PLAYING) scope.launch { playHint(hint) }
        // Playback cut short by pause/stop/interruption.
        if (b.state in EngineState.PLAYING && a.state !in EngineState.PLAYING && a.state != EngineState.WAITING_FOR_RETRY) {
            scope.launch { if (playback.isPlaying.value) playback.stop() }
        }
        // Smart assistance off: never show unsolicited offers (the Lukma button still works).
        if (a.pendingOffer != null && b.pendingOffer == null && !prefs.value.smartAssistance) {
            process(c, SessionInput.DeclineOffer)
        }
    }

    private fun snapshotStatus(s: EngineState) = when (s) {
        EngineState.PAUSED -> EngineStatus.PAUSED
        EngineState.INTERRUPTED -> EngineStatus.INTERRUPTED
        in EngineState.IN_SESSION -> EngineStatus.ACTIVE
        else -> EngineStatus.STOPPED
    }

    private fun persist(snap: SessionSnapshot, ended: Boolean) {
        val endedAt = clock.nowMillis()
        persistQueue.trySend {
            val id = roomId ?: return@trySend
            val s = snap.lastSurah
            val a = snap.lastAyah
            if (s != null && a != null) {
                sessions.updatePosition(id, s, a, snap.lastWordIndex ?: 0, snap.durationMs)
                if (!startLocated) {
                    startLocated = true
                    sessions.updateStart(id, s, a)
                    _state.update { it.copy(startPosition = QuranPosition(s, a)) }
                }
            }
            val status = when (snap.status) {
                EngineStatus.ACTIVE -> SessionStatus.ACTIVE
                EngineStatus.PAUSED -> SessionStatus.PAUSED
                EngineStatus.INTERRUPTED -> SessionStatus.INTERRUPTED
                EngineStatus.STOPPED -> SessionStatus.COMPLETED
            }
            sessions.updateStatus(id, status, snap.durationMs, if (ended) endedAt else null)
            if (ended && !prefs.value.keepHistory) sessions.delete(id)
        }
    }

    // ---- lifecycle -------------------------------------------------------------------------

    private fun readyChain() {
        send(SessionInput.PermissionsResult(true)) // the UI only starts after RECORD_AUDIO is granted
        send(SessionInput.ModelReady)              // no/failed model → manual-position mode
        send(SessionInput.ReferenceReady)          // the controller exists only once the reference is loaded
    }

    /**
     * Starts a new session. The engine locates the position from the recitation; [requestedStart]
     * (Quran screen "Recite from here") is only shown until then.
     */
    fun start(requestedStart: QuranPosition? = null) {
        if (_state.value.status.isRunning) return
        _state.update { it.copy(requestedStart = requestedStart, summaryDismissed = false, notice = null, sessionId = null, startPosition = null) }
        recoverFromError()
        readyChain()
        send(SessionInput.StartSession(mode()))
    }

    private fun mode() = if (prefs.value.mode == RecitationMode.MEMORIZATION) PracticeMode.MEMORIZATION else PracticeMode.RECITATION

    private fun recoverFromError() = onEngine { c, _ ->
        if (c.currentState == EngineState.ERROR) {
            process(c, SessionInput.Reset)
            process(c, SessionInput.Initialize)
        }
    }

    /** Restores an unfinished persisted session (e.g. after process death) and starts listening. */
    fun resumeSession(id: Long) {
        if (_state.value.status.isRunning) return
        persistQueue.trySend {
            val row = sessions.get(id) ?: return@trySend
            if (!row.status.isResumable) return@trySend
            roomId = id
            startLocated = true
            _state.update {
                it.copy(sessionId = id, startPosition = QuranPosition(row.startSurah, row.startAyah),
                    requestedStart = null, summaryDismissed = false, notice = null)
            }
            sessions.updateStatus(id, SessionStatus.ACTIVE, row.durationMs, null)
            recoverFromError()
            readyChain()
            onEngine { c, r ->
                val wordId = if (row.lastWord <= 0) null
                else r.ayahWords(row.lastSurah, row.lastAyah)?.let { (it.first + row.lastWord - 1).coerceAtMost(it.last) }
                process(
                    c,
                    SessionInput.RecoverSession(
                        SessionSnapshot(
                            sessionId = "room-$id", startTimeMs = row.startedAt, mode = mode(),
                            lastWordId = wordId, lastSurah = row.lastSurah, lastAyah = row.lastAyah,
                            lastWordIndex = row.lastWord, status = EngineStatus.ACTIVE, durationMs = row.durationMs,
                        )
                    ),
                )
            }
        }
    }

    fun pause() = send(SessionInput.Pause)

    fun resume() = onEngine { c, _ ->
        when (c.currentState) {
            EngineState.PAUSED -> process(c, SessionInput.Resume)
            EngineState.INTERRUPTED -> process(c, SessionInput.InterruptionEnded)
            else -> Unit
        }
    }

    fun stop() = send(SessionInput.Stop)

    /** Dismisses a stopped session's summary (the controller stays STOPPED until the next start). */
    fun reset() {
        if (_state.value.state == EngineState.STOPPED) _state.update { it.copy(summaryDismissed = true) }
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    // ---- position recovery -----------------------------------------------------------------

    /**
     * Manual position (word tap / "Recite from here"): the user is at [position]. word = 0 means
     * the start of the ayah. Maps to SessionInput.SetManualPosition.
     */
    fun setManualPosition(position: QuranPosition) = onEngine { c, r ->
        val ayah = r.ayahWords(position.surah, position.ayah) ?: return@onEngine
        val wordId = (ayah.first + (position.word - 1).coerceAtLeast(0)).coerceAtMost(ayah.last)
        process(c, SessionInput.SetManualPosition(wordId))
    }

    /** Moves the confirmed position by [delta] words (manual tracking without a model). */
    fun stepManualPosition(delta: Int) = onEngine { c, _ ->
        val cur = (c.uiState.value.candidate ?: c.uiState.value.confirmed)?.wordId ?: return@onEngine
        process(c, SessionInput.SetManualPosition(cur + delta))
    }

    // ---- recognition -----------------------------------------------------------------------

    fun onRecognitionEvent(event: RecognitionEvent) = send(SessionInput.Recognition(event))

    /**
     * Runs [engine] for the current session until cancelled: feeds it gated microphone frames
     * (open only while listening and no hint plays), runs the energy VAD on the same frames,
     * forwards events/status to the controller and reports Listening.
     */
    suspend fun runRecognizer(
        engine: QuranRecognitionEngine,
        frames: () -> Flow<ShortArray>,
        onCaptureError: (Throwable) -> Unit = {},
    ): Unit = coroutineScope {
        val vad = EnergyVad()
        launch(start = CoroutineStart.UNDISPATCHED) { engine.observeRecognition().collect { send(SessionInput.Recognition(it)) } }
        launch(start = CoroutineStart.UNDISPATCHED) {
            engine.observeStatus().collect { if (it is RecognizerStatus.Error) send(SessionInput.RecognizerStatusChanged(it)) }
        }
        val gated = state.map { it.audioGateOpen }.distinctUntilChanged().flatMapLatest { open ->
            if (!open) {
                if (vad.speaking) send(SessionInput.SpeechActivity(false))
                vad.reset()
                emptyFlow()
            } else frames()
                .onEach { f -> vad.process(f)?.let { send(SessionInput.SpeechActivity(it)) } }
                .catch { e -> onCaptureError(e) }
        }
        engine.initialize()
        engine.startListening(gated)
        recognizerBound = true
        onEngine { c, _ ->
            if (c.currentState == EngineState.STARTING_SESSION || c.currentState == EngineState.RECOVERING_SESSION) {
                process(c, SessionInput.RecognizerStatusChanged(RecognizerStatus.Listening))
            }
        }
        try {
            awaitCancellation()
        } finally {
            recognizerBound = false
            engine.stopListening()
        }
    }

    // ---- interruptions ---------------------------------------------------------------------

    fun interrupt(reason: InterruptionReason) = onEngine { c, _ ->
        val wasInterrupted = c.currentState == EngineState.INTERRUPTED
        process(c, SessionInput.Interrupted(reason.name))
        if (c.currentState == EngineState.INTERRUPTED && !wasInterrupted) _state.update { it.copy(interruption = reason) }
    }

    fun onInterruptionSignal(signal: InterruptionSignal) {
        when (signal) {
            InterruptionSignal.BecomingNoisy -> interrupt(InterruptionReason.HEADSET_UNPLUGGED)
            InterruptionSignal.CallStarted -> interrupt(InterruptionReason.CALL)
            is InterruptionSignal.FocusLost -> interrupt(InterruptionReason.AUDIO_FOCUS)
            // Auto-resume only after transient system interruptions; an unplugged headset or a
            // capture error waits for the user.
            InterruptionSignal.CallEnded, InterruptionSignal.FocusGained -> onEngine { c, _ ->
                val reason = _state.value.interruption
                if (c.currentState == EngineState.INTERRUPTED &&
                    (reason == InterruptionReason.CALL || reason == InterruptionReason.AUDIO_FOCUS)
                ) process(c, SessionInput.InterruptionEnded)
            }
        }
    }

    // ---- Lukma assistance ------------------------------------------------------------------

    /** User pressed Lukma: hint for the next word (repeated presses escalate word → phrase → ayah). */
    fun requestLukma(level: HintLevel? = null) = send(SessionInput.RequestLukma(level))
    fun acceptOffer(level: HintLevel? = null) = send(SessionInput.AcceptOffer(level))
    fun declineOffer() = send(SessionInput.DeclineOffer)

    /** Stops a playing hint and returns to listening. */
    fun stopHint() {
        if (playback.isPlaying.value) playback.stop()
        send(SessionInput.PlaybackEnded)
        clearSuppressionAfterTail()
    }

    private suspend fun playHint(h: HintRequest) {
        val p = prefs.value
        val audio = runCatching { qariAudio.ayahAudio(p.preferredQariId, h.surah, h.ayah) }.getOrNull()
        val clip = audio?.let { clipFor(it, h) }
        if (audio == null || clip == null) {
            _state.update {
                it.copy(notice = if (audio == null) "Qari audio is not downloaded yet — carry on, I'm still listening."
                else "Word timings are not available for this Qari — carry on, I'm still listening.")
            }
            send(SessionInput.PlaybackEnded)
            return
        }
        // Suppress before audio starts so not a single frame of the hint reaches the recognizer.
        tailJob?.cancel()
        _state.update { it.copy(recognitionSuppressed = true) }
        playback.play(audio.uri, tag = "hint-${h.level}", startMs = clip.first, endMs = clip.second,
            speed = p.playbackSpeed, volume = p.playbackVolume)
    }

    private fun clipFor(audio: AyahAudio, h: HintRequest): Pair<Long, Long?>? {
        if (h.level == HintLevel.FULL_AYAH) return audio.startMs to audio.endMs
        val r = ref ?: return null
        val first = audio.wordTimings.getOrNull(r.word(h.wordIds.first).wordIndex - 1) ?: return null
        val last = audio.wordTimings.getOrNull(r.word(h.wordIds.last).wordIndex - 1) ?: return null
        return first.first to last.second
    }

    private fun onPlaybackEvent(event: PlaybackEvent) {
        when (event) {
            is PlaybackEvent.Started -> {
                tailJob?.cancel()
                _state.update { it.copy(recognitionSuppressed = true) }
                send(SessionInput.PlaybackStarted)
            }
            is PlaybackEvent.Ended -> {
                send(SessionInput.PlaybackEnded)
                clearSuppressionAfterTail()
            }
        }
    }

    private fun clearSuppressionAfterTail() {
        tailJob?.cancel()
        tailJob = scope.launch {
            delay(PLAYBACK_TAIL_MS) // room echo / decay must not be recognised
            _state.update { it.copy(recognitionSuppressed = false) }
        }
    }

    companion object {
        const val PLAYBACK_TAIL_MS = 300L
        const val TICK_MS = 250L
    }
}
