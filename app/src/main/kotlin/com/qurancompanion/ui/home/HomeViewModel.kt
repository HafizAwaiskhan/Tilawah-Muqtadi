package com.qurancompanion.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qurancompanion.core.recognition.ModelReadiness
import com.qurancompanion.core.qari.QariDownloadManager
import com.qurancompanion.core.recognition.QuranRecognitionEngine
import com.qurancompanion.data.db.SessionEntity
import com.qurancompanion.data.db.SessionStatus
import com.qurancompanion.data.reference.QuranPosition
import com.qurancompanion.data.reference.QuranReferenceRepository
import com.qurancompanion.data.reference.ReferenceStatus
import com.qurancompanion.data.session.SessionRepository
import com.qurancompanion.session.RecitationSessionManager
import com.qurancompanion.session.LiveSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val live: LiveSession = LiveSession(),
    /** Persisted session that was not stopped (process death, interruption): offer resume. */
    val resumable: SessionEntity? = null,
    val lastCompleted: SessionEntity? = null,
    val reference: ReferenceStatus = ReferenceStatus.Loading,
    val model: ModelReadiness = ModelReadiness.NotInstalled,
    /** Surahs of the Alafasy test audio on the device (hints stream when online otherwise). */
    val qariSurahs: Int = 0,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val manager: RecitationSessionManager,
    private val sessions: SessionRepository,
    private val reference: QuranReferenceRepository,
    engine: QuranRecognitionEngine,
    qari: QariDownloadManager,
) : ViewModel() {

    val state: StateFlow<HomeUiState> = combine(
        manager.state,
        sessions.observeSessions(),
        reference.status,
        engine.readiness,
        qari.state,
    ) { live, all, ref, model, q ->
        HomeUiState(
            live = live,
            resumable = all.firstOrNull { it.status.isResumable && it.id != live.sessionId }
                ?.takeIf { !live.status.isRunning },
            lastCompleted = all.firstOrNull { it.status == SessionStatus.COMPLETED },
            reference = ref,
            model = model,
            qariSurahs = q.downloaded.size,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    init {
        viewModelScope.launch { reference.ensureReady() }
    }

    /** The engine finds the position from the recitation itself. */
    fun startNew() {
        manager.reset()
        manager.start(null)
    }

    /** Ends the running session (saved) and starts a fresh one with automatic position detection. */
    fun newSession() = manager.startNewSession()

    fun resume(sessionId: Long) {
        manager.reset()
        manager.resumeSession(sessionId)
    }

    fun discard(sessionId: Long) {
        viewModelScope.launch {
            sessions.get(sessionId)?.let {
                sessions.updateStatus(it.id, SessionStatus.COMPLETED, it.durationMs, it.endedAt ?: it.startedAt + it.durationMs)
            }
        }
    }
}
