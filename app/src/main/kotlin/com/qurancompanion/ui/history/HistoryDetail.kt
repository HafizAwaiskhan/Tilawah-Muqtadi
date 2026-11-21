package com.qurancompanion.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.qurancompanion.data.db.FlaggedWordEntity
import com.qurancompanion.data.db.SessionEntity
import com.qurancompanion.data.reference.QuranPosition
import com.qurancompanion.data.session.SessionRepository
import com.qurancompanion.ui.components.FlaggedWordList
import com.qurancompanion.ui.components.formatDuration
import com.qurancompanion.ui.components.formatTimestamp
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class HistoryDetailUiState(
    val loading: Boolean = true,
    val session: SessionEntity? = null,
    val flags: List<FlaggedWordEntity> = emptyList(),
)

@HiltViewModel
class HistoryDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    sessions: SessionRepository,
) : ViewModel() {
    val sessionId: Long = savedState.get<Long>("id") ?: -1L

    val state: StateFlow<HistoryDetailUiState> = combine(
        sessions.observeSessions().map { l -> l.firstOrNull { it.id == sessionId } },
        sessions.observeFlags(sessionId),
    ) { s, flags -> HistoryDetailUiState(loading = false, session = s, flags = flags) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryDetailUiState())
}

/** History → session details: summary + flagged words (tap one to open its ayah in the Quran screen). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryDetailScreen(
    onBack: () -> Unit,
    onOpenAyah: (surah: Int, ayah: Int) -> Unit,
    vm: HistoryDetailViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Session") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val s = state.session
            when {
                state.loading -> Text("Loading…")
                s == null -> Text("This session was deleted.")
                else -> {
                    Text(
                        "${QuranPosition(s.startSurah, s.startAyah).label()} → " +
                            QuranPosition(s.lastSurah, s.lastAyah, s.lastWord).label(),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text("${formatTimestamp(s.startedAt)} · ${formatDuration(s.durationMs)}")
                    FlaggedWordList(
                        state.flags.map { Triple(it.surah, it.ayah, it.wordIndex to it.mistakeType) },
                        onOpenAyah = onOpenAyah,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Only positions and mistake types are kept — never audio or what was heard.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
