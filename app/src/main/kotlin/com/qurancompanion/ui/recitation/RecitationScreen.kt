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
    LaunchedEffect(state.showOffer) {
        if (state.showOffer && state.vibration) {
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
                    val ayah = state.ayah
                    Text(
                        (state.surah?.let { "${it.nameTransliterated} · " } ?: "") +
                            (ayah?.let { "${it.surah}:${it.ayah}" } ?: session.position.label()),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        state.stateLabel + (state.pauseLabel?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    session.engine.confirmed?.let {
                        Text(
                            "Confirmed: ${it.surah}:${it.ayah} · word ${it.wordIndex}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
                Text(formatDuration(session.durationMs), style = MaterialTheme.typography.headlineSmall)
            }

            // Current ayah, word by word (tap a word to correct the position)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    val ayah = state.ayah
                    if (ayah == null) {
                        Text("Quran text not available (reference data not installed).")
                    } else {
                        AyahWords(
                            words = ayah.words,
                            highlightWord = state.highlightWord,
                            ayahNumber = ayah.ayah,
                            onWordClick = if (status.isRunning) { w -> vm.tapWord(ayah.surah, ayah.ayah, w.index) } else null,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (state.manualTracking) {
                        // No recognition model: the user moves the position (tap a word, or step).
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
            state.nextAyah?.takeIf { status.isRunning }?.let { next ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(12.dp)) {
                        AyahWords(
                            words = next.words,
                            ayahNumber = next.ayah,
                            onWordClick = { w -> vm.tapWord(next.surah, next.ayah, w.index) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            AssistanceCard(state, vm)

            // Controls
            when (status) {
                ListeningStatus.IDLE, ListeningStatus.STOPPED -> {
                    if (status == ListeningStatus.STOPPED) {
                        val start = session.startPosition?.let { "${it.surah}:${it.ayah}" } ?: "—"
                        val end = session.engine.confirmed?.let { "${it.surah}:${it.ayah} · word ${it.wordIndex}" } ?: "—"
                        Text("Session saved: $start → $end, " + formatDuration(session.durationMs))
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
                        enabled = state.canRequestLukma,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) { Text("Lukma — help me continue") }
                }
            }
        }
    }
}

@Composable
private fun AssistanceCard(state: RecitationUiState, vm: RecitationViewModel) {
    val session = state.session
    val notice = session.notice
    val feedback = state.feedback?.takeIf { session.status.isRunning }
    if (!state.showOffer && !session.isPlayingHint && notice == null && feedback == null) return
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                state.showOffer -> {
                    Text("Would you like a hint?", style = MaterialTheme.typography.titleMedium)
                    Text("Your position stays where it is. Recognition pauses while the hint plays.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = vm::playHint) { Text("Play hint") }
                        OutlinedButton(onClick = vm::playFullAyah) { Text("Play full ayah") }
                    }
                    TextButton(onClick = vm::continueReciting) { Text("Continue") }
                }
                session.isPlayingHint -> {
                    Text(if (session.state == com.qurancompanion.engine.session.SessionState.PLAYING_FULL_AYAH) "Playing the ayah…" else "Playing hint…")
                    TextButton(onClick = vm::stopHint) { Text("Stop and continue") }
                }
                notice != null -> {
                    Text(notice)
                    TextButton(onClick = vm::dismissNotice) { Text("OK") }
                }
                feedback != null -> {
                    Text(feedback.text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
