package com.qurancompanion.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qurancompanion.data.settings.RecitationMode
import com.qurancompanion.data.settings.UserSettings
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    var confirmClear by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        Section("Recitation")
        SwitchRow("Smart assistance", "Offer gentle help when you seem stuck. Off: help only when you press Lukma.",
            s.smartAssistance, vm::setSmartAssistance)
        Text("Mode", style = MaterialTheme.typography.titleSmall)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            RecitationMode.entries.forEachIndexed { i, mode ->
                SegmentedButton(
                    selected = s.mode == mode,
                    onClick = { vm.setMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(i, RecitationMode.entries.size),
                ) { Text(if (mode == RecitationMode.RECITATION) "Recitation" else "Memorization") }
            }
        }
        SwitchRow("Vibration", "Vibrate softly when assistance is offered.", s.vibration, vm::setVibration)

        Section("Qari audio")
        SliderRow("Playback speed", s.playbackSpeed, UserSettings.SPEED_RANGE, steps = 9,
            label = { String.format(Locale.ROOT, "%.1f×", it) }, onChange = vm::setPlaybackSpeed)
        SliderRow("Playback volume", s.playbackVolume, UserSettings.VOLUME_RANGE, steps = 9,
            label = { "${(it * 100).toInt()}%" }, onChange = vm::setPlaybackVolume)
        var qari by remember(s.preferredQariId) { mutableStateOf(s.preferredQariId.orEmpty()) }
        OutlinedTextField(
            value = qari,
            onValueChange = { qari = it },
            label = { Text("Preferred Qari id") },
            supportingText = { Text("Qari audio downloads are not available yet.") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        LaunchedEffect(qari) { if (qari != s.preferredQariId.orEmpty()) vm.setPreferredQari(qari) }

        Section("Privacy")
        SwitchRow("Keep session history", "Positions and durations only — audio is never stored.",
            s.keepHistory, vm::setKeepHistory)
        OutlinedButton(onClick = { confirmClear = true }) { Text("Delete all history") }

        Section("About")
        val attribution by vm.attribution.collectAsStateWithLifecycle()
        val dataVersion by vm.dataVersion.collectAsStateWithLifecycle()
        Text("Quran text sources", style = MaterialTheme.typography.titleSmall)
        Text(
            attribution ?: "Quran text database not installed.",
            style = MaterialTheme.typography.bodySmall,
        )
        dataVersion?.let { Text("Reference data: $it", style = MaterialTheme.typography.bodySmall) }
        TextButton(onClick = vm::replayOnboarding) { Text("Show introduction again") }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Delete all history?") },
            text = { Text("All saved sessions will be removed from this device.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; vm.clearHistory() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 8.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderRow(
    title: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int,
    label: (Float) -> String, onChange: (Float) -> Unit,
) {
    var local by remember(value) { mutableFloatStateOf(value) }
    Column {
        Text("$title: ${label(local)}", style = MaterialTheme.typography.bodyLarge)
        Slider(value = local, onValueChange = { local = it }, onValueChangeFinished = { onChange(local) },
            valueRange = range, steps = steps)
    }
}
