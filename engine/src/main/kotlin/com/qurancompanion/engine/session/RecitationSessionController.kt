package com.qurancompanion.engine.session

import com.qurancompanion.engine.align.CandidateMistake
import com.qurancompanion.engine.align.ClassifierConfig
import com.qurancompanion.engine.align.MistakeClassifier
import com.qurancompanion.engine.align.MistakeType
import com.qurancompanion.engine.recognition.RecognitionEvent
import com.qurancompanion.engine.recognition.RecognizerStatus
import com.qurancompanion.engine.recognition.TokenUnit
import com.qurancompanion.engine.reference.QuranReference
import com.qurancompanion.engine.reference.WordPosition
import com.qurancompanion.engine.session.SessionState.*
import com.qurancompanion.engine.session.SessionState.Companion.ACTIVE
import com.qurancompanion.engine.session.SessionState.Companion.IN_SESSION
import com.qurancompanion.engine.session.SessionState.Companion.LISTENING
import com.qurancompanion.engine.session.SessionState.Companion.PLAYING
import com.qurancompanion.engine.tracking.QuranPositionTracker
import com.qurancompanion.engine.tracking.TrackerConfig
import com.qurancompanion.engine.tracking.UpdateKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Everything that drives the session. The app forwards these to [RecitationSessionController.handle]. */
sealed interface SessionInput {
    // Lifecycle (reported by the app)
    data object Initialize : SessionInput
    data class PermissionsResult(val granted: Boolean) : SessionInput
    data class ModelDownloadProgress(val fraction: Float) : SessionInput
    data object ModelReady : SessionInput
    data object ReferenceReady : SessionInput
    data class StartSession(val mode: PracticeMode = PracticeMode.RECITATION) : SessionInput
    data class RecoverSession(val snapshot: SessionSnapshot) : SessionInput
    data class RecognizerStatusChanged(val status: RecognizerStatus) : SessionInput
    // Audio / recognition
    data class Recognition(val event: RecognitionEvent) : SessionInput
    /** Optional VAD edges. */
    data class SpeechActivity(val speaking: Boolean) : SessionInput
    /** Periodic clock tick (e.g. every 250-500 ms) for pause detection and self-correction windows. */
    data object Tick : SessionInput
    // User actions
    data class RequestLukma(val level: HintLevel? = null) : SessionInput
    data class AcceptOffer(val level: HintLevel? = null) : SessionInput
    data object DeclineOffer : SessionInput
    data object Pause : SessionInput
    data object Resume : SessionInput
    data object Stop : SessionInput
    data object Reset : SessionInput
    /**
     * Manual recovery: the user says [wordId] is the word they have just recited (e.g. tapped it).
     * Accepted while listening (-> RECITING) and while PAUSED/INTERRUPTED/RECOVERING_SESSION (position
     * only). Ignored during hint playback. Clears held mistakes and withdraws a pending offer.
     */
    data class SetManualPosition(val wordId: Int) : SessionInput
    // Playback / system
    data object PlaybackStarted : SessionInput
    data object PlaybackEnded : SessionInput
    data class Interrupted(val reason: String) : SessionInput
    data object InterruptionEnded : SessionInput
    data class Failure(val message: String) : SessionInput
}

enum class SessionStatus { ACTIVE, PAUSED, INTERRUPTED, STOPPED }

/** Persistable summary; save it whenever [SessionUiState.confirmed] changes and on pause/stop. */
data class SessionSnapshot(
    val sessionId: String,
    val startTimeMs: Long,
    val mode: PracticeMode,
    val lastWordId: Int?,
    val lastSurah: Int?,
    val lastAyah: Int?,
    val lastWordIndex: Int?,
    val status: SessionStatus,
    val durationMs: Long,
)

data class SessionUiState(
    val state: SessionState = IDLE,
    val mode: PracticeMode = PracticeMode.RECITATION,
    val sessionId: String? = null,
    /** Last position committed from stable recognition. */
    val confirmed: WordPosition? = null,
    /** Best guess of the word being recited (includes provisional tokens) — highlight only. */
    val candidate: WordPosition? = null,
    /** Next expected word id (after the candidate/confirmed word). */
    val nextWordId: Int? = null,
    val trackingConfidence: Double = 0.0,
    val pauseClass: PauseClass = PauseClass.NONE,
    val pendingOffer: LukmaOffer? = null,
    val activeHint: HintRequest? = null,
    /** Mistakes still inside the self-correction window (not shown as errors). */
    val heldMistakes: List<CandidateMistake> = emptyList(),
    /** Mistakes surfaced by the feedback policy in this session (most recent last). */
    val feedback: List<CandidateMistake> = emptyList(),
    val repetitions: Int = 0,
    val downloadProgress: Float? = null,
    val error: String? = null,
)

data class SessionConfig(
    val tracker: TrackerConfig? = null,
    val classifier: ClassifierConfig = ClassifierConfig(),
    val selfCorrection: SelfCorrectionConfig = SelfCorrectionConfig(),
    val pause: PauseConfig = PauseConfig(),
    val feedback: FeedbackConfig = FeedbackConfig(),
    val lukma: LukmaConfig = LukmaConfig(),
    val playbackTailMs: Long = 300,
    /** Pause class at which Lukma is offered. */
    val offerAt: PauseClass = PauseClass.EVALUATE,
    val maxFeedbackKept: Int = 50,
)

/**
 * The recitation session state machine. Single-threaded: call [handle] from one dispatcher.
 * Never plays audio itself: it exposes [SessionUiState.activeHint] after an explicit user action.
 */
class RecitationSessionController(
    private val reference: QuranReference,
    private val unit: TokenUnit,
    private val clock: Clock,
    private val config: SessionConfig = SessionConfig(),
    private val newSessionId: () -> String = { java.util.UUID.randomUUID().toString() },
) {
    val tracker = QuranPositionTracker(reference, unit, config.tracker ?: TrackerConfig.forUnit(unit))
    private val classifier = MistakeClassifier(tracker.sequence, tracker.aligner.similarity, tracker.config.matchThreshold, config.classifier)
    private val selfCorrection = SelfCorrectionHandler(config.selfCorrection)
    private val pause = PauseDetector(config.pause)
    private val policy = FeedbackPolicy(reference, config.feedback)
    private val lukma = LukmaAssistanceController(reference, config.lukma)
    private val guard = PlaybackGuard(config.playbackTailMs)

    private var state = IDLE
    private var mode = PracticeMode.RECITATION
    private var sessionId: String? = null
    private var startTimeMs = 0L
    private var durationMs = 0L
    private var lastAccountMs: Long? = null
    private var pauseClass = PauseClass.NONE
    private val feedback = ArrayList<CandidateMistake>()
    private var repetitions = 0
    private var downloadProgress: Float? = null
    private var error: String? = null

    private val _ui = MutableStateFlow(SessionUiState())
    val uiState: StateFlow<SessionUiState> = _ui.asStateFlow()

    /** Every state visited, including transient ones (PROCESSING, ASSISTANCE_OFFERED...). For diagnostics/tests. */
    val history = ArrayList<SessionState>().apply { add(IDLE) }

    val currentState: SessionState get() = state

    fun handle(input: SessionInput) {
        val now = clock.nowMs()
        accountDuration(now)
        when (input) {
            SessionInput.Initialize -> if (state == IDLE || state == ERROR) { error = null; go(INITIALIZING); go(CHECKING_PERMISSIONS) }
            is SessionInput.PermissionsResult -> if (state == CHECKING_PERMISSIONS) {
                if (input.granted) go(DOWNLOADING_MODEL) else fail("microphone permission denied")
            }
            is SessionInput.ModelDownloadProgress -> if (state == DOWNLOADING_MODEL) downloadProgress = input.fraction
            SessionInput.ModelReady -> if (state == DOWNLOADING_MODEL) { downloadProgress = null; go(FETCHING_REFERENCE) }
            SessionInput.ReferenceReady -> if (state == FETCHING_REFERENCE) go(READY)
            is SessionInput.StartSession -> if (state == READY || state == STOPPED) {
                newSession(input.mode, now, newSessionId(), now, 0L)
                tracker.reset()
                go(STARTING_SESSION)
            }
            is SessionInput.RecoverSession -> if (state == READY || state == STOPPED) {
                val s = input.snapshot
                newSession(s.mode, now, s.sessionId, s.startTimeMs, s.durationMs)
                tracker.reset()
                s.lastWordId?.let { tracker.restore(it) }
                go(RECOVERING_SESSION)
            }
            is SessionInput.RecognizerStatusChanged -> when (val st = input.status) {
                is RecognizerStatus.Error -> if (state != IDLE) fail(st.message)
                RecognizerStatus.Listening -> when (state) {
                    STARTING_SESSION -> { pause.restart(now); go(LOCATING_POSITION) }
                    RECOVERING_SESSION -> { pause.restart(now); go(if (tracker.isTracking) RECITING else LOCATING_POSITION) }
                    else -> {}
                }
                else -> {}
            }
            is SessionInput.Recognition -> onRecognition(input.event, now)
            is SessionInput.SpeechActivity -> if (state in LISTENING) {
                if (input.speaking) pause.onSpeechStart(now) else pause.onSpeechEnd(now)
                if (input.speaking && state == PAUSE_DETECTED) go(baseState())
            }
            SessionInput.Tick -> onTick(now)
            is SessionInput.RequestLukma -> if (state in LISTENING && state != LOCATING_POSITION) {
                nextWord()?.let { lukma.request(it, input.level) }?.let { startHint(it, now) }
            }
            is SessionInput.AcceptOffer -> if (state == WAITING_FOR_CONFIRMATION) {
                lukma.accept(input.level)?.let { startHint(it, now) }
            }
            SessionInput.DeclineOffer -> if (state == WAITING_FOR_CONFIRMATION) {
                lukma.decline(now); pause.restart(now); go(baseState())
            }
            SessionInput.PlaybackStarted -> if (state in PLAYING) guard.onPlaybackStart(now)
            SessionInput.PlaybackEnded -> if (state in PLAYING) {
                guard.onPlaybackEnd(now); lukma.onPlaybackFinished(now); pause.restart(now); pauseClass = PauseClass.NONE
                go(WAITING_FOR_RETRY)
            }
            SessionInput.Pause -> if (state in LISTENING || state in PLAYING) { stopPlayback(now); go(PAUSED) }
            SessionInput.Resume -> if (state == PAUSED) resume(now)
            is SessionInput.Interrupted -> if (state in LISTENING || state in PLAYING || state == PAUSED) { stopPlayback(now); go(INTERRUPTED) }
            SessionInput.InterruptionEnded -> if (state == INTERRUPTED) resume(now)
            SessionInput.Stop -> if (state in IN_SESSION) { stopPlayback(now); go(STOPPED) }
            SessionInput.Reset -> if (state == STOPPED || state == ERROR) { go(IDLE); sessionId = null }
            is SessionInput.SetManualPosition -> setManualPosition(input.wordId, now)
            is SessionInput.Failure -> fail(input.message)
        }
        publish()
    }

    fun snapshot(): SessionSnapshot? {
        val id = sessionId ?: return null
        accountDuration(clock.nowMs())
        val p = tracker.lastConfirmed
        val status = when (state) {
            PAUSED -> SessionStatus.PAUSED
            INTERRUPTED -> SessionStatus.INTERRUPTED
            STOPPED, ERROR, IDLE -> SessionStatus.STOPPED
            else -> SessionStatus.ACTIVE
        }
        return SessionSnapshot(id, startTimeMs, mode, p?.wordId, p?.surah, p?.ayah, p?.wordIndex, status, durationMs)
    }

    private fun newSession(m: PracticeMode, now: Long, id: String, start: Long, duration: Long) {
        mode = m; sessionId = id; startTimeMs = start; durationMs = duration; lastAccountMs = now
        feedback.clear(); repetitions = 0; pauseClass = PauseClass.NONE
        selfCorrection.clear(); policy.reset(); lukma.reset(); guard.reset(); pause.reset()
    }

    private fun onRecognition(event: RecognitionEvent, now: Long) {
        if (state !in LISTENING || event.unit != unit) return
        val ev = guard.filter(event) ?: return
        if (ev.tokens.isEmpty()) return
        pause.onSpeech(ev.tokens.mapNotNull { it.endMs }.maxOrNull() ?: now)
        pauseClass = PauseClass.NONE
        if (!ev.isStable) {
            tracker.onProvisional(ev.tokens)
            if (state == PAUSE_DETECTED) go(baseState())
            return
        }
        val before = state
        go(PROCESSING)
        val updates = tracker.onStable(ev.tokens)
        var advanced = false
        for (u in updates) {
            when (u.kind) {
                UpdateKind.ADVANCE -> {
                    selfCorrection.onWordsMatched(u.matchedWords, now)
                    val mistakes = classifier.classify(u.ops, u.tokens, u.contextBefore, u.previousTokens)
                    repetitions += mistakes.count { it.type == MistakeType.REPETITION }
                    selfCorrection.hold(mistakes, now)
                }
                UpdateKind.REPEAT -> {
                    selfCorrection.onWordsMatched(u.matchedWords, now)
                    u.matchedWords.minOrNull()?.let { selfCorrection.onRepeat(it) }
                }
                UpdateKind.SKIP -> selfCorrection.onWordsMatched(u.matchedWords, now)
                UpdateKind.LOCATED, UpdateKind.JUMP, UpdateKind.LOST -> selfCorrection.clear()
            }
            if (u.kind != UpdateKind.LOST) {
                advanced = true
                val words = if (u.kind == UpdateKind.ADVANCE && u.from != null && u.to != null) u.to.wordId - u.from.wordId else 0
                pause.onWord(u.tokens.lastOrNull()?.endMs ?: now, words)
            }
        }
        if (!tracker.isTracking) { go(LOCATING_POSITION); return }
        if (advanced) {
            if (before == WAITING_FOR_CONFIRMATION) lukma.withdraw(now)
            if (before == WAITING_FOR_RETRY) { lukma.onResumed(); go(RESUMING) }
        } else if (before == WAITING_FOR_CONFIRMATION || before == WAITING_FOR_RETRY || before == POSSIBLE_MISTAKE) {
            go(before); return
        }
        if (!promote(now)) go(baseState())
    }

    private fun onTick(now: Long) {
        if (state !in setOf(RECITING, SELF_CORRECTION_WINDOW, POSSIBLE_MISTAKE, PAUSE_DETECTED)) return
        if (promote(now)) return
        if (!tracker.isTracking) return
        val effective = tracker.effectiveWord() ?: return
        pauseClass = pause.classify(now, reference.isPausePoint(effective))
        if (pauseClass >= PauseClass.LISTENING && state != PAUSE_DETECTED) go(PAUSE_DETECTED)
        if (pauseClass >= config.offerAt) {
            val next = nextWord() ?: return
            if (lukma.maybeOffer(next, now) != null) { go(ASSISTANCE_OFFERED); go(WAITING_FOR_CONFIRMATION) }
        }
    }

    /** Promotes due mistakes through the policy. Returns true if feedback was emitted (state POSSIBLE_MISTAKE). */
    private fun promote(now: Long): Boolean {
        var emitted = false
        for (m in selfCorrection.promoteDue(now)) {
            if (policy.shouldEmit(m, mode, now, tracker.confidence)) {
                feedback += m; emitted = true
                while (feedback.size > config.maxFeedbackKept) feedback.removeAt(0)
            }
        }
        if (emitted) go(POSSIBLE_MISTAKE)
        return emitted
    }

    private fun baseState(): SessionState = when {
        !tracker.isTracking -> LOCATING_POSITION
        selfCorrection.held.isNotEmpty() -> SELF_CORRECTION_WINDOW
        else -> RECITING
    }

    private fun nextWord(): Int? {
        val w = tracker.effectiveWord() ?: return null
        return (w + 1).takeIf { it < reference.wordCount }
    }

    private fun startHint(h: HintRequest, now: Long) {
        guard.onPlaybackStart(now) // guard from the moment of the request, not only from PlaybackStarted
        go(if (h.level == HintLevel.FULL_AYAH) PLAYING_FULL_AYAH else PLAYING_LUKMA)
    }

    private fun stopPlayback(now: Long) {
        if (guard.isPlaying) { guard.onPlaybackEnd(now); lukma.onPlaybackFinished(now) }
    }

    private fun resume(now: Long) {
        pause.restart(now)
        pauseClass = PauseClass.NONE
        go(RESUMING)
        go(baseState())
    }

    private fun setManualPosition(wordId: Int, now: Long) {
        if (wordId !in 0 until reference.wordCount) return
        val listening = state in LISTENING
        if (!listening && state !in setOf(PAUSED, INTERRUPTED, RECOVERING_SESSION)) return
        lukma.withdraw(now)
        lukma.onResumed()
        selfCorrection.clear()
        tracker.restore(wordId)
        pause.restart(now)
        pauseClass = PauseClass.NONE
        if (listening) { go(PROCESSING); go(baseState()) }
    }

    private fun fail(message: String) { error = message; if (state != ERROR) go(ERROR) }

    private fun go(to: SessionState) {
        if (to == state) return
        check(SessionTransitions.canTransition(state, to)) { "illegal transition $state -> $to" }
        state = to
        history += to
        if (history.size > 1000) history.subList(0, 500).clear()
    }

    private fun accountDuration(now: Long) {
        val last = lastAccountMs
        if (last != null && state in ACTIVE) durationMs += now - last
        lastAccountMs = now
    }

    private fun publish() {
        _ui.value = SessionUiState(
            state = state, mode = mode, sessionId = sessionId,
            confirmed = tracker.lastConfirmed, candidate = tracker.candidate, nextWordId = nextWord(),
            trackingConfidence = tracker.confidence, pauseClass = pauseClass,
            pendingOffer = lukma.offer, activeHint = lukma.activeHint,
            heldMistakes = selfCorrection.held, feedback = feedback.toList(), repetitions = repetitions,
            downloadProgress = downloadProgress, error = error,
        )
    }
}
