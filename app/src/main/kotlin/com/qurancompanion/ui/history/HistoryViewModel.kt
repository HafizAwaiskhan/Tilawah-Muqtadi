package com.qurancompanion.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qurancompanion.data.db.SessionEntity
import com.qurancompanion.data.session.SessionRepository
import com.qurancompanion.session.RecitationSessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HistoryUiState(
    val loading: Boolean = true,
    val sessions: List<SessionEntity> = emptyList(),
    /** Id of the live session, which cannot be deleted while running. */
    val liveSessionId: Long? = null,
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val sessions: SessionRepository,
    manager: RecitationSessionManager,
) : ViewModel() {

    val state: StateFlow<HistoryUiState> = combine(sessions.observeSessions(), manager.state) { list, live ->
        HistoryUiState(
            loading = false,
            sessions = list,
            liveSessionId = live.sessionId.takeIf { live.status.isRunning },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    fun delete(id: Long) {
        if (id == state.value.liveSessionId) return
        viewModelScope.launch { sessions.delete(id) }
    }

    fun deleteAll() {
        val keep = state.value.liveSessionId
        viewModelScope.launch {
            if (keep == null) sessions.deleteAll()
            else state.value.sessions.filter { it.id != keep }.forEach { sessions.delete(it.id) }
        }
    }
}
