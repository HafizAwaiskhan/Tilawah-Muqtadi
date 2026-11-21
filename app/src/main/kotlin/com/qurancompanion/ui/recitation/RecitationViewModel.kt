package com.qurancompanion.ui.recitation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qurancompanion.core.recognition.ModelReadiness
import com.qurancompanion.core.recognition.QuranRecognitionEngine
import com.qurancompanion.data.reference.AyahText
import com.qurancompanion.data.reference.EngineReferenceSource
import com.qurancompanion.data.reference.QuranPosition
import com.qurancompanion.data.reference.QuranReferenceRepository
import com.qurancompanion.data.reference.SurahInfo
import com.qurancompanion.data.settings.SettingsRepository
import com.qurancompanion.engine.align.CandidateMistake
import com.qurancompanion.engine.align.MistakeType
import com.qurancompanion.engine.reference.WordPosition
import com.qurancompanion.engine.session.HintLevel
import com.qurancompanion.engine.session.PauseClass
import com.qurancompanion.engine.session.SessionState as EngineState
import com.qurancompanion.session.ListeningStatus
import com.qurancompanion.session.LiveSession
import com.qurancompanion.session.RecitationSessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A surfaced mistake, phrased gently for display. */
data class FeedbackNote(val text: String, val position: WordPosition?)

data class RecitationUiState(
    val session: LiveSession = LiveSession(),
    val surah: SurahInfo? = null,
    /** Ayah containing the current word (or the requested start while locating). */
    val ayah: AyahText? = null,
    /** The following ayah, shown dimmed below. */
    val nextAyah: AyahText? = null,
    val model: ModelReadiness = ModelReadiness.NotInstalled,
    val smartAssistance: Boolean = true,
    val vibration: Boolean = true,
    val requestedStart: QuranPosition? = null,
    val feedback: FeedbackNote? = null,
) {
    private val engineState: EngineState get() = session.state

    /** 1-based word to highlight in [ayah], or null. */
    val highlightWord: Int?
        get() {
            val c = session.current ?: return null
            val a = ayah ?: return null
            return if (session.status.isRunning && c.surah == a.surah && c.ayah == a.ayah) c.wordIndex else null
        }

    val stateLabel: String
        get() = when (engineState) {
            EngineState.IDLE, EngineState.INITIALIZING, EngineState.CHECKING_PERMISSIONS,
            EngineState.DOWNLOADING_MODEL, EngineState.FETCHING_REFERENCE -> "Preparing…"
            EngineState.READY -> "Ready"
            EngineState.STARTING_SESSION -> "Starting…"
            EngineState.RECOVERING_SESSION -> "Restoring your session…"
            EngineState.LOCATING_POSITION ->
                if (model.isReady) "Listening — finding your position" else "Tap the word you are on to follow along"
            EngineState.RECITING, EngineState.PROCESSING, EngineState.SELF_CORRECTION_WINDOW,
            EngineState.POSSIBLE_MISTAKE, EngineState.RESUMING -> "Following your recitation"
            EngineState.PAUSE_DETECTED -> "Take your time…"
            EngineState.ASSISTANCE_OFFERED, EngineState.WAITING_FOR_CONFIRMATION -> "Would you like a hint?"
            EngineState.PLAYING_LUKMA -> "Playing hint — not listening"
            EngineState.PLAYING_FULL_AYAH -> "Playing the ayah — not listening"
            EngineState.WAITING_FOR_RETRY -> "Your turn — continue when ready"
            EngineState.PAUSED -> "Paused"
            EngineState.INTERRUPTED ->
                "Interrupted" + (session.interruption?.let { " (${it.name.lowercase().replace('_', ' ')})" } ?: "")
            EngineState.STOPPED -> if (session.summaryDismissed) "Ready" else "Session ended"
            EngineState.ERROR -> "Something went wrong" + (session.engine.error?.let { ": $it" } ?: "")
        }

    val pauseLabel: String?
        get() = when (session.engine.pauseClass) {
            PauseClass.LISTENING -> "Pause"
            PauseClass.EVALUATE, PauseClass.LONG -> "Long pause"
            else -> null
        }

    val showOffer: Boolean get() = session.engine.pendingOffer != null && engineState == EngineState.WAITING_FOR_CONFIRMATION
    val canRequestLukma: Boolean
        get() = engineState in EngineState.LISTENING && engineState != EngineState.LOCATING_POSITION
    /** Without a recognition model the position is moved by hand. */
    val manualTracking: Boolean get() = session.status.isRunning && !model.isReady
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RecitationViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val manager: RecitationSessionManager,
    private val reference: QuranReferenceRepository,
    private val engineReference: EngineReferenceSource,
    engine: QuranRecognitionEngine,
    settings: SettingsRepository,
) : ViewModel() {

    private val requestedStart: QuranPosition? = run {
        val s = savedState.get<Int>("surah") ?: -1
        val a = savedState.get<Int>("ayah") ?: -1
        if (s > 0) QuranPosition(s, a.coerceAtLeast(1)) else null
    }

    private fun displayAyah(s: LiveSession): Pair<Int, Int> {
        val c = s.current
        if (c != null && s.status != ListeningStatus.IDLE) return c.surah to c.ayah
        val p = if (s.status == ListeningStatus.IDLE) requestedStart ?: s.position
        else s.requestedStart ?: s.startPosition ?: requestedStart ?: s.position
        return p.surah to p.ayah
    }

    private val ayahFlow: Flow<Triple<SurahInfo?, AyahText?, AyahText?>> = manager.state
        .map(::displayAyah)
        .distinctUntilChanged()
        .mapLatest { (s, a) ->
            val next = reference.nextAyah(QuranPosition(s, a))?.let { reference.ayah(it.surah, it.ayah) }
            Triple(reference.surah(s), reference.ayah(s, a), next)
        }

    private val feedbackFlow: Flow<FeedbackNote?> = manager.state
        .map { it.engine.feedback.lastOrNull() }
        .distinctUntilChanged()
        .mapLatest { m -> m?.let { describe(it) } }

    private suspend fun describe(m: CandidateMistake): FeedbackNote {
        val pos = engineReference.get()?.takeIf { m.wordId in 0 until it.wordCount }?.position(m.wordId)
        val where = pos?.let { "word ${it.wordIndex} of ${it.surah}:${it.ayah}" } ?: "the last words"
        val what = when (m.type) {
            MistakeType.DELETION -> "A word may have been missed"
            MistakeType.ORDER -> "The word order may have changed"
            MistakeType.INSERTION -> "An extra word may have slipped in"
            else -> "A word may have been different"
        }
        return FeedbackNote("$what — $where. You may want to check it.", pos)
    }

    val state: StateFlow<RecitationUiState> = combine(
        manager.state, ayahFlow, feedbackFlow, engine.readiness, settings.settings,
    ) { s, (surah, ayah, next), feedback, model, prefs ->
        RecitationUiState(
            session = s,
            surah = surah,
            ayah = ayah,
            nextAyah = next,
            model = model,
            smartAssistance = prefs.smartAssistance,
            vibration = prefs.vibration,
            requestedStart = requestedStart,
            feedback = feedback,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecitationUiState(requestedStart = requestedStart))

    init {
        viewModelScope.launch { reference.ensureReady() }
    }

    /** Called after the mic permission is granted; the screen then starts the service. */
    fun start() {
        manager.reset()
        manager.start(requestedStart)
    }

    fun pause() = manager.pause()
    fun resume() = manager.resume()
    fun stop() = manager.stop()
    fun lukma() = manager.requestLukma()
    fun playHint() = manager.acceptOffer(HintLevel.NEXT_WORD)
    fun playFullAyah() = manager.acceptOffer(HintLevel.FULL_AYAH)
    fun continueReciting() = manager.declineOffer()
    fun stopHint() = manager.stopHint()
    fun dismissNotice() = manager.dismissNotice()

    /** Manual recovery: the user taps the word they are on. */
    fun tapWord(surah: Int, ayah: Int, word: Int) = manager.setManualPosition(QuranPosition(surah, ayah, word))

    fun nextWord() = manager.stepManualPosition(+1)
    fun previousWord() = manager.stepManualPosition(-1)
}
