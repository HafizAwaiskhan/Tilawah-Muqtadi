package com.qurancompanion.ui.history

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qurancompanion.data.db.SessionStatus
import com.qurancompanion.data.reference.QuranPosition
import com.qurancompanion.ui.components.formatDuration
import com.qurancompanion.ui.components.formatTimestamp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(vm: HistoryViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var confirmDeleteAll by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("History") },
                actions = {
                    if (state.sessions.isNotEmpty()) {
                        IconButton(onClick = { confirmDeleteAll = true }) {
                            Icon(Icons.Filled.DeleteSweep, contentDescription = "Delete all history")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.sessions.isEmpty() -> Text(
                    "No sessions yet. Only positions and durations are kept — never audio.",
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
                else -> LazyColumn {
                    items(state.sessions, key = { it.id }) { s ->
                        val live = s.id == state.liveSessionId
                        ListItem(
                            headlineContent = {
                                Text(
                                    "${QuranPosition(s.startSurah, s.startAyah).label()} → " +
                                        QuranPosition(s.lastSurah, s.lastAyah, s.lastWord).label()
                                )
                            },
                            supportingContent = {
                                Text(
                                    "${formatTimestamp(s.startedAt)} · ${formatDuration(s.durationMs)}" +
                                        when {
                                            live -> " · in progress"
                                            s.status != SessionStatus.COMPLETED -> " · unfinished"
                                            else -> ""
                                        }
                                )
                            },
                            trailingContent = {
                                if (!live) {
                                    IconButton(onClick = { vm.delete(s.id) }) {
                                        Icon(Icons.Filled.Delete, contentDescription = "Delete session")
                                    }
                                }
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("Delete all history?") },
            text = { Text("All saved sessions will be removed from this device.", style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = { confirmDeleteAll = false; vm.deleteAll() }) { Text("Delete all") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text("Cancel") } },
        )
    }
}
