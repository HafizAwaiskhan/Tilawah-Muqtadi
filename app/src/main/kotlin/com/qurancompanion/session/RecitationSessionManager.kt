package com.qurancompanion.session

import com.qurancompanion.core.audio.InterruptionSignal
import com.qurancompanion.core.playback.PlaybackEvent
import com.qurancompanion.core.playback.QariAudioResolver
import com.qurancompanion.core.playback.QuranAudioPlayback
import com.qurancompanion.core.recognition.RecognitionEvent
import com.qurancompanion.core.time.Clock
import com.qurancompanion.data.db.SessionEntity
import com.qurancompanion.data.db.SessionStatus
import com.qurancompanion.data.reference.QuranPosition
import com.qurancompanion.data.session.SessionRepository
import com.qurancompanion.data.settings.SettingsRepository
import com.qurancompanion.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-side owner of the live recitation session: status, last confirmed position, timing,
 * Lukma assistance and playback isolation. Shared by the UI (ViewModels) and RecitationService.
 *
 * All state changes are synchronous on [state]; persistence runs in order on a single queue so
 * the last confirmed position always reaches Room (process-death recovery, PHASE0_PLAN §5).
 *
 * INTEGRATION TODO(engine): when `:engine`'s RecitationSessionController lands, delegate the
 * session state machine / position tracking to it and keep this class as the Android adapter
 * (persistence, playback isolation, service wiring). See [onRecognitionEvent].
 */
@Singleton
class RecitationSessionManager @Inject constructor(
    private val sessions: SessionRepository,
    private val settings: SettingsRepository,
    private val playback: QuranAudioPlayback,
    private val qariAudio: QariAudioResolver,
    private val clock: Clock,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val ops = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private var tailJob: Job? = null

    init {
        scope.launch { for (op in ops) runCatching { op() } }
        scope.launch { playback.events.collect(::onPlaybackEvent) }
    }

    private fun persist(op: suspend () -> Unit) {
        ops.trySend(op)
    }

    private fun currentId(): Long? = _state.value.sessionId

    // ---- lifecycle -------------------------------------------------------------------------

    /** Starts a new session at [from]. Ignored if a session is already running. */
    fun start(from: QuranPosition) {
        if (_state.value.status.isRunning) return
        val now = clock.elapsedRealtime()
        _state.value = SessionState(
            status = ListeningStatus.LISTENING,
            startPosition = from,
            position = from,
            activeSinceElapsed = now,
        )
        val startedAt = clock.nowMillis()
        persist {
            val id = sessions.create(
                SessionEntity(
                    startedAt = startedAt,
                    status = SessionStatus.ACTIVE,
                    startSurah = from.surah, startAyah = from.ayah,
                    lastSurah = from.surah, lastAyah = from.ayah, lastWord = from.word,
                )
            )
            _state.update { it.copy(sessionId = id) }
        }
    }

    /** Restores an unfinished persisted session (e.g. after process death) and starts listening. */
    fun resumeSession(id: Long) {
        if (_state.value.status.isRunning) return
        persist {
            val s = sessions.get(id) ?: return@persist
            if (!s.status.isResumable) return@persist
            _state.value = SessionState(
                sessionId = s.id,
                status = ListeningStatus.LISTENING,
                startPosition = QuranPosition(s.startSurah, s.startAyah),
                position = QuranPosition(s.lastSurah, s.lastAyah, s.lastWord),
                accumulatedMs = s.durationMs,
                activeSinceElapsed = clock.elapsedRealtime(),
            )
            sessions.updateStatus(s.id, SessionStatus.ACTIVE, s.durationMs, null)
        }
    }

    fun pause() = transitionToHalted(ListeningStatus.PAUSED, null, SessionStatus.PAUSED)

    fun interrupt(reason: InterruptionReason) {
        if (_state.value.status != ListeningStatus.LISTENING) return
        transitionToHalted(ListeningStatus.INTERRUPTED, reason, SessionStatus.INTERRUPTED)
    }

    private fun transitionToHalted(status: ListeningStatus, reason: InterruptionReason?, persisted: SessionStatus) {
        val cur = _state.value
        if (!cur.status.isRunning) return
        if (playback.isPlaying.value) playback.stop()
        val now = clock.elapsedRealtime()
        val next = cur.copy(
            status = status,
            accumulatedMs = cur.durationMs(now),
            activeSinceElapsed = null,
            interruption = reason,
            assistance = AssistanceState.None,
        )
        _state.value = next
        persist { currentId()?.let { sessions.updateStatus(it, persisted, next.accumulatedMs, null) } }
    }

    fun resume() {
        val cur = _state.value
        if (cur.status != ListeningStatus.PAUSED && cur.status != ListeningStatus.INTERRUPTED) return
        _state.value = cur.copy(
            status = ListeningStatus.LISTENING,
            activeSinceElapsed = clock.elapsedRealtime(),
            interruption = null,
        )
        persist { currentId()?.let { sessions.updateStatus(it, SessionStatus.ACTIVE, cur.accumulatedMs, null) } }
    }

    fun stop() {
        val cur = _state.value
        if (!cur.status.isRunning) return
        if (playback.isPlaying.value) playback.stop()
        val duration = cur.durationMs(clock.elapsedRealtime())
        _state.value = cur.copy(
            status = ListeningStatus.STOPPED,
            accumulatedMs = duration,
            activeSinceElapsed = null,
            assistance = AssistanceState.None,
            interruption = null,
            recognitionSuppressed = false,
        )
        val endedAt = clock.nowMillis()
        persist {
            val id = currentId() ?: return@persist
            val p = _state.value.position
            sessions.updatePosition(id, p.surah, p.ayah, p.word, duration)
            sessions.updateStatus(id, SessionStatus.COMPLETED, duration, endedAt)
            if (!settings.settings.first().keepHistory) sessions.delete(id)
        }
    }

    /** Clears a STOPPED summary so the screen shows the start state again. */
    fun reset() {
        if (_state.value.status == ListeningStatus.STOPPED) _state.value = SessionState()
    }

    // ---- position --------------------------------------------------------------------------

    /** Engine-confirmed position (stable tokens only). */
    fun onConfirmedPosition(position: QuranPosition) = updatePosition(position)

    /** User picked a position manually (Quran screen / jump). */
    fun setManualPosition(position: QuranPosition) = updatePosition(position)

    private fun updatePosition(position: QuranPosition) {
        val cur = _state.value
        if (!cur.status.isRunning || cur.position == position) return
        _state.value = cur.copy(position = position)
        val duration = cur.durationMs(clock.elapsedRealtime())
        persist { currentId()?.let { sessions.updatePosition(it, position.surah, position.ayah, position.word, duration) } }
    }

    /**
     * Recognition events from the engine. INTEGRATION TODO(engine): feed stable tokens into the
     * engine's position tracker and call [onConfirmedPosition] with its confirmed positions.
     * Tokens must be ignored while [SessionState.recognitionSuppressed] (hint playback).
     */
    fun onRecognitionEvent(event: RecognitionEvent) {
        when (event) {
            is RecognitionEvent.Error -> if (!event.recoverable) interrupt(InterruptionReason.CAPTURE_ERROR)
            is RecognitionEvent.Token, is RecognitionEvent.VoiceActivity -> Unit
        }
    }

    // ---- interruptions ---------------------------------------------------------------------

    fun onInterruptionSignal(signal: InterruptionSignal) {
        val cur = _state.value
        when (signal) {
            InterruptionSignal.BecomingNoisy -> interrupt(InterruptionReason.HEADSET_UNPLUGGED)
            InterruptionSignal.CallStarted -> interrupt(InterruptionReason.CALL)
            is InterruptionSignal.FocusLost -> interrupt(InterruptionReason.AUDIO_FOCUS)
            // Auto-resume only after transient system interruptions; an unplugged headset
            // or capture error waits for the user.
            InterruptionSignal.CallEnded, InterruptionSignal.FocusGained ->
                if (cur.status == ListeningStatus.INTERRUPTED &&
                    (cur.interruption == InterruptionReason.CALL || cur.interruption == InterruptionReason.AUDIO_FOCUS)
                ) resume()
        }
    }

    // ---- Lukma assistance ------------------------------------------------------------------

    fun requestLukma() {
        val cur = _state.value
        if (!cur.status.isRunning) return
        _state.value = cur.copy(assistance = AssistanceState.Offered(cur.position))
    }

    /** Plays a hint. Never changes the confirmed position. */
    fun playHint(kind: HintKind) {
        val cur = _state.value
        if (!cur.status.isRunning) return
        val pos = cur.position
        scope.launch {
            val prefs = settings.settings.first()
            val audio = qariAudio.ayahAudio(prefs.preferredQariId, pos.surah, pos.ayah)
            if (audio == null) {
                _state.update { it.copy(assistance = AssistanceState.Unavailable("Qari audio is not downloaded yet.")) }
                return@launch
            }
            var start = audio.startMs
            var end = audio.endMs
            if (kind == HintKind.NEXT_WORD) {
                val timing = audio.wordTimings.getOrNull(pos.word) // word index is 1-based → next word
                if (timing == null) {
                    _state.update { it.copy(assistance = AssistanceState.Unavailable("Word timings are not available for this Qari.")) }
                    return@launch
                }
                start = timing.first
                end = timing.second
            }
            // Suppress before audio starts so not a single frame of the hint reaches the recognizer.
            _state.update { it.copy(recognitionSuppressed = true, assistance = AssistanceState.Playing(kind, pos)) }
            playback.play(audio.uri, tag = "hint-$kind", startMs = start, endMs = end,
                speed = prefs.playbackSpeed, volume = prefs.playbackVolume)
        }
    }

    fun continueReciting() {
        if (playback.isPlaying.value) playback.stop()
        _state.update { it.copy(assistance = AssistanceState.None) }
    }

    private fun onPlaybackEvent(event: PlaybackEvent) {
        when (event) {
            is PlaybackEvent.Started -> {
                tailJob?.cancel()
                _state.update { it.copy(recognitionSuppressed = true) }
            }
            is PlaybackEvent.Ended -> {
                tailJob?.cancel()
                tailJob = scope.launch {
                    delay(PLAYBACK_TAIL_MS) // room echo / decay must not be recognised
                    _state.update {
                        it.copy(
                            recognitionSuppressed = false,
                            assistance = if (it.assistance is AssistanceState.Playing) AssistanceState.None else it.assistance,
                        )
                    }
                }
            }
        }
    }

    companion object {
        const val PLAYBACK_TAIL_MS = 300L
    }
}
