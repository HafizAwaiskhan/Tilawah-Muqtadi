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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qurancompanion.core.model.AsrModelSpec
import com.qurancompanion.data.settings.RecitationMode
import com.qurancompanion.core.qari.AlafasyTestQari
import com.qurancompanion.data.settings.MistakeHint
import com.qurancompanion.ui.components.ModelDownloadCard
import com.qurancompanion.ui.components.QariDownloadCard
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
        Text("On mistake, play", style = MaterialTheme.typography.titleSmall)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            MistakeHint.entries.forEachIndexed { i, h ->
                SegmentedButton(
                    selected = s.mistakeHint == h,
                    onClick = { vm.setMistakeHint(h) },
                    shape = SegmentedButtonDefaults.itemShape(i, MistakeHint.entries.size),
                ) { Text(if (h == MistakeHint.FULL_AYAH) "Full ayah" else "Correct word only") }
            }
        }
        SwitchRow("Mark flagged words in text", "Underline words that may have been recited differently; tap one to see the expected word.",
            s.markFlaggedWords, vm::setMarkFlaggedWords)
        SwitchRow("Gentle vibration on mistake / Lukma offer", "A short, soft pattern. Follows your phone's silent mode.",
            s.vibrateOnCue, vm::setVibrateOnCue)
        SwitchRow("Soft tone on mistake / Lukma offer", "A short quiet beep through your earbuds or speaker. Listening pauses for it.",
            s.softTone, vm::setSoftTone)

        Section("Offline downloads")
        ModelDownloadCard()
        QariDownloadCard()

        Section("Qari audio")
        SliderRow("Playback speed", s.playbackSpeed, UserSettings.SPEED_RANGE, steps = 9,
            label = { String.format(Locale.ROOT, "%.1f×", it) }, onChange = vm::setPlaybackSpeed)
        SliderRow("Playback volume", s.playbackVolume, UserSettings.VOLUME_RANGE, steps = 9,
            label = { "${(it * 100).toInt()}%" }, onChange = vm::setPlaybackVolume)
        Text("Qari: ${AlafasyTestQari.TITLE}", style = MaterialTheme.typography.bodySmall)

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
        Text("Speech recognition", style = MaterialTheme.typography.titleSmall)
        Text(AsrModelSpec.ABDOU_V1.attribution + " Full licence: Offline downloads → View licence.",
            style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = vm::replayOnboarding) { Text("Show introduction again") }

        if (vm.developerAvailable) {
            Section("Developer (debug build)")
            val d = s.developer
            SwitchRow("Simulated recognizer",
                "Replays the reference words of the range below instead of listening — exercises tracking, highlighting, pauses and Lukma without a model. Applies to the next session.",
                d.simulatedRecognizer) { v -> vm.updateDeveloper { it.copy(simulatedRecognizer = v) } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Surah", d.surah, Modifier.weight(1f)) { v -> vm.updateDeveloper { it.copy(surah = v) } }
                NumberField("From ayah", d.fromAyah, Modifier.weight(1f)) { v -> vm.updateDeveloper { it.copy(fromAyah = v) } }
                NumberField("To ayah", d.toAyah, Modifier.weight(1f)) { v -> vm.updateDeveloper { it.copy(toAyah = v) } }
            }
            SliderRow("Pace (ms per word)", d.wordMs.toFloat(), 200f..2000f, steps = 17,
                label = { "${it.toInt()} ms" }) { v -> vm.updateDeveloper { it.copy(wordMs = v.toInt()) } }
            SwitchRow("Long pause mid-range", "One ~9 s pause before a word (triggers a Lukma offer).",
                d.forgetPause) { v -> vm.updateDeveloper { it.copy(forgetPause = v) } }
            SwitchRow("Inject errors", "One wrong word followed by a self-correction, and one skipped word.",
                d.injectErrors) { v -> vm.updateDeveloper { it.copy(injectErrors = v) } }
        }
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
private fun NumberField(label: String, value: Int, modifier: Modifier, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { t -> text = t.filter(Char::isDigit).take(3); text.toIntOrNull()?.let(onChange) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
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
