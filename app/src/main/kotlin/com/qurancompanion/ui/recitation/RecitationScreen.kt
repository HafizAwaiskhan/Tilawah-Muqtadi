package com.qurancompanion.ui.recitation

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qurancompanion.session.AssistanceState
import com.qurancompanion.session.HintKind
import com.qurancompanion.session.ListeningStatus
import com.qurancompanion.ui.components.AyahWords
import com.qurancompanion.ui.components.formatDuration
import com.qurancompanion.ui.components.rememberRecitationStarter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecitationScreen(onBack: () -> Unit, vm: RecitationViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val starter = rememberRecitationStarter()
    val session = state.session
    val status = session.status
    val context = LocalContext.current
    val offered = session.assistance is AssistanceState.Offered
    LaunchedEffect(offered) {
        if (offered && state.vibration) {
            context.getSystemService(Vibrator::class.java)
                ?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recitation") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Position + status header
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val pos = if (status == ListeningStatus.IDLE) state.requestedStart ?: session.position else session.position
                    Text(
                        (state.surah?.let { "${it.nameTransliterated} · " } ?: "") + "${pos.surah}:${pos.ayah}",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        state.listeningLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                Text(formatDuration(state.durationMs), style = MaterialTheme.typography.headlineSmall)
            }

            // Current ayah, word by word
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    val ayah = state.ayah
                    if (ayah == null) {
                        Text("Quran text not available (reference data not installed).")
                    } else {
                        AyahWords(
                            words = ayah.words,
                            highlightWord = if (status.isRunning) session.position.word.coerceAtLeast(1) else null,
                            ayahNumber = ayah.ayah,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (status.isRunning && !state.model.isReady) {
                        // Manual tracking until the recognition engine confirms positions.
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = vm::previousWord) {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null); Text("Previous word")
                            }
                            TextButton(onClick = vm::nextWord) {
                                Text("Next word"); Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                            }
                        }
                    }
                }
            }

            AssistanceCard(state.session.assistance, vm)

            // Controls
            when (status) {
                ListeningStatus.IDLE, ListeningStatus.STOPPED -> {
                    if (status == ListeningStatus.STOPPED) {
                        Text(
                            "Session saved: ${session.startPosition.label()} → ${session.position.label()}, " +
                                formatDuration(session.accumulatedMs),
                        )
                    }
                    Button(
                        onClick = { starter.start { vm.start() } },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) { Text(if (status == ListeningStatus.STOPPED) "Start a new session" else "Start") }
                    if (starter.lastDenied) {
                        Text(
                            "Microphone permission was denied. Allow it in system settings to start a session.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                else -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (status == ListeningStatus.LISTENING) {
                            OutlinedButton(onClick = vm::pause, Modifier.weight(1f)) { Text("Pause") }
                        } else {
                            // Resuming re-opens the mic; keep it a foreground, user-initiated action.
                            Button(onClick = { starter.start { vm.resume() } }, Modifier.weight(1f)) { Text("Resume") }
                        }
                        OutlinedButton(onClick = vm::stop, Modifier.weight(1f)) { Text("Stop") }
                    }
                    FilledTonalButton(
                        onClick = vm::lukma,
                        enabled = session.assistance is AssistanceState.None,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) { Text("Lukma — help me continue") }
                }
            }
        }
    }
}

@Composable
private fun AssistanceCard(assistance: AssistanceState, vm: RecitationViewModel) {
    if (assistance is AssistanceState.None) return
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (assistance) {
                is AssistanceState.Offered -> {
                    Text("Would you like a hint?", style = MaterialTheme.typography.titleMedium)
                    Text("Your position stays at ${assistance.position.label()}. Recognition pauses while the hint plays.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.playHint(HintKind.NEXT_WORD) }) { Text("Play hint") }
                        OutlinedButton(onClick = { vm.playHint(HintKind.FULL_AYAH) }) { Text("Full ayah") }
                    }
                    TextButton(onClick = vm::continueReciting) { Text("Continue reciting") }
                }
                is AssistanceState.Playing -> {
                    Text("Playing ${if (assistance.kind == HintKind.NEXT_WORD) "hint" else "ayah"}…")
                    TextButton(onClick = vm::continueReciting) { Text("Stop and continue") }
                }
                is AssistanceState.Unavailable -> {
                    Text(assistance.message)
                    TextButton(onClick = vm::continueReciting) { Text("Continue reciting") }
                }
                AssistanceState.None -> Unit
            }
        }
    }
}
