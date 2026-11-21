package com.qurancompanion.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qurancompanion.core.recognition.ModelReadiness
import com.qurancompanion.data.reference.QuranPosition
import com.qurancompanion.data.reference.ReferenceStatus
import com.qurancompanion.ui.components.formatDuration
import com.qurancompanion.ui.components.formatTimestamp
import com.qurancompanion.ui.components.rememberRecitationStarter

@Composable
fun HomeScreen(
    onOpenRecitation: () -> Unit,
    onOpenQuran: () -> Unit,
    vm: HomeViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val starter = rememberRecitationStarter()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Quran Companion", style = MaterialTheme.typography.headlineMedium)

        if (state.live.status.isRunning) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Session in progress", style = MaterialTheme.typography.titleMedium)
                    Text("At ${state.live.position.label()} · ${state.live.status.name.lowercase()}")
                    Button(onClick = onOpenRecitation, Modifier.padding(top = 8.dp)) { Text("Open session") }
                }
            }
        } else {
            Button(
                onClick = { starter.start { vm.startNew(state.suggestedStart); onOpenRecitation() } },
                modifier = Modifier.fillMaxWidth().height(64.dp),
            ) {
                Icon(Icons.Filled.Mic, contentDescription = null)
                Text("  Start recitation", style = MaterialTheme.typography.titleMedium)
            }
            if (starter.lastDenied) {
                Text(
                    "Microphone permission is needed to follow your recitation. You can allow it in system settings.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        state.resumable?.let { s ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Unfinished session", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Started ${formatTimestamp(s.startedAt)} · last at ${QuranPosition(s.lastSurah, s.lastAyah, s.lastWord).label()} · " +
                            formatDuration(s.durationMs)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        Button(onClick = { starter.start { vm.resume(s.id); onOpenRecitation() } }) { Text("Resume session") }
                        TextButton(onClick = { vm.discard(s.id) }) { Text("Discard") }
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Resources", style = MaterialTheme.typography.titleMedium)
                ReadinessRow(
                    "Quran text",
                    when (val r = state.reference) {
                        is ReferenceStatus.Ready -> "Ready (${r.surahCount} surahs${r.version?.let { ", v$it" } ?: ""})"
                        ReferenceStatus.Loading -> "Loading…"
                        ReferenceStatus.Missing -> "Not included in this build"
                        is ReferenceStatus.Failed -> "Error: ${r.message}"
                    },
                    state.reference is ReferenceStatus.Ready,
                )
                ReadinessRow(
                    "Recognition model",
                    when (val m = state.model) {
                        ModelReadiness.NotInstalled -> "Not installed — follow along manually for now"
                        is ModelReadiness.Downloading -> "Downloading ${(m.progress * 100).toInt()}%"
                        ModelReadiness.Loading -> "Loading…"
                        is ModelReadiness.Ready -> "Ready (${m.modelId} ${m.version})"
                        is ModelReadiness.Failed -> "Error: ${m.message}"
                    },
                    state.model.isReady,
                )
                ReadinessRow("Qari audio (hints)", if (state.qariInstalled) "Installed" else "Not downloaded yet", state.qariInstalled)
                OutlinedButton(onClick = onOpenQuran) { Text("Open the Quran") }
            }
        }

        state.lastCompleted?.let { s ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Last session", style = MaterialTheme.typography.titleMedium)
                    Text(formatTimestamp(s.startedAt))
                    Text(
                        "${QuranPosition(s.startSurah, s.startAyah).label()} → " +
                            "${QuranPosition(s.lastSurah, s.lastAyah).label()} · ${formatDuration(s.durationMs)}"
                    )
                }
            }
        }
    }
}

@Composable
private fun ReadinessRow(label: String, value: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ok) Icons.Filled.CheckCircle else Icons.Filled.Info, contentDescription = null,
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        )
        Column(Modifier.padding(start = 12.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
