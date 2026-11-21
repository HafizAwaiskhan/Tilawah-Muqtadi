package com.qurancompanion.ui.recitation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qurancompanion.core.recognition.ModelReadiness
import com.qurancompanion.core.recognition.QuranRecognitionEngine
import com.qurancompanion.core.time.Clock
import com.qurancompanion.data.reference.AyahText
import com.qurancompanion.data.reference.QuranPosition
import com.qurancompanion.data.reference.QuranReferenceRepository
import com.qurancompanion.data.reference.SurahInfo
import com.qurancompanion.data.settings.SettingsRepository
import com.qurancompanion.session.HintKind
import com.qurancompanion.session.ListeningStatus
import com.qurancompanion.session.RecitationSessionManager
import com.qurancompanion.session.SessionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RecitationUiState(
    val session: SessionState = SessionState(),
    val durationMs: Long = 0,
    val surah: SurahInfo? = null,
    val ayah: AyahText? = null,
    val model: ModelReadiness = ModelReadiness.NotInstalled,
    val smartAssistance: Boolean = true,
    val vibration: Boolean = true,
    /** Position the user asked to start from (Quran screen), or null for the default. */
    val requestedStart: QuranPosition? = null,
) {
    val listeningLabel: String
        get() = when (session.status) {
            ListeningStatus.IDLE -> "Ready"
            ListeningStatus.LISTENING ->
                if (session.recognitionSuppressed) "Playing hint — not listening"
                else if (!model.isReady) "Listening (recognition model not installed)"
                else "Listening"
            ListeningStatus.PAUSED -> "Paused"
            ListeningStatus.INTERRUPTED -> "Interrupted" + (session.interruption?.let { " (${it.name.lowercase().replace('_', ' ')})" } ?: "")
            ListeningStatus.STOPPED -> "Session ended"
        }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RecitationViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val manager: RecitationSessionManager,
    private val reference: QuranReferenceRepository,
    private val clock: Clock,
    engine: QuranRecognitionEngine,
    settings: SettingsRepository,
) : ViewModel() {

    private val requestedStart: QuranPosition? = run {
        val s = savedState.get<Int>("surah") ?: -1
        val a = savedState.get<Int>("ayah") ?: -1
        if (s > 0) QuranPosition(s, a.coerceAtLeast(1)) else null
    }

    /** Position shown: the live one when running, otherwise where a new session would start. */
    private fun displayPosition(s: SessionState): QuranPosition =
        if (s.status == ListeningStatus.IDLE) requestedStart ?: s.position else s.position

    private val ayahFlow: Flow<Pair<SurahInfo?, AyahText?>> = manager.state
        .map { displayPosition(it).let { p -> p.surah to p.ayah } }
        .distinctUntilChanged()
        .mapLatest { (s, a) -> reference.surah(s) to reference.ayah(s, a) }

    private val tick: Flow<Long> = manager.state
        .map { it.status == ListeningStatus.LISTENING }
        .distinctUntilChanged()
        .flatMapLatest { running ->
            if (!running) flowOf(clock.elapsedRealtime())
            else flow { while (true) { emit(clock.elapsedRealtime()); delay(1_000) } }
        }

    val state: StateFlow<RecitationUiState> = combine(
        manager.state, tick, ayahFlow, engine.readiness, settings.settings,
    ) { s, now, (surah, ayah), model, prefs ->
        RecitationUiState(
            session = s,
            durationMs = s.durationMs(now),
            surah = surah,
            ayah = ayah,
            model = model,
            smartAssistance = prefs.smartAssistance,
            vibration = prefs.vibration,
            requestedStart = requestedStart,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecitationUiState(requestedStart = requestedStart))

    init {
        viewModelScope.launch { reference.ensureReady() }
    }

    /** Called after the mic permission is granted; the screen then starts the service. */
    fun start() {
        manager.reset()
        manager.start(requestedStart ?: manager.state.value.position.copy(word = 0))
    }

    fun pause() = manager.pause()
    fun resume() = manager.resume()
    fun stop() = manager.stop()
    fun newSession() = manager.reset()
    fun lukma() = manager.requestLukma()
    fun playHint(kind: HintKind) = manager.playHint(kind)
    fun continueReciting() = manager.continueReciting()

    /** Manual word advance (used until the engine confirms positions automatically). */
    fun nextWord() {
        val s = manager.state.value
        val ayah = state.value.ayah ?: return
        val p = s.position
        if (p.word < ayah.words.size) {
            manager.setManualPosition(p.copy(word = p.word + 1))
        } else {
            viewModelScope.launch { reference.nextAyah(p)?.let { manager.setManualPosition(it) } }
        }
    }

    fun previousWord() {
        val p = manager.state.value.position
        if (p.word > 0) manager.setManualPosition(p.copy(word = p.word - 1))
    }
}
