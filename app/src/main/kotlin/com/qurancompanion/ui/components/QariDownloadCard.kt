package com.qurancompanion.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.qurancompanion.core.qari.AlafasyTestQari
import com.qurancompanion.core.qari.QariDownloadManager
import com.qurancompanion.core.qari.QariDownloadState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class QariDownloadViewModel @Inject constructor(
    private val downloads: QariDownloadManager,
) : ViewModel() {
    val state: StateFlow<QariDownloadState> = downloads.state

    /** Explicit user action only. */
    fun downloadSurah(surah: Int) = downloads.downloadSurah(surah)
    fun downloadAll() = downloads.downloadAll()
    fun cancel() = downloads.cancel()
    fun deleteAll() { viewModelScope.launch { downloads.deleteAll() } }
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(Locale.ROOT, "%.2f GB", bytes / 1e9)
    else -> String.format(Locale.ROOT, "%.1f MB", bytes / 1e6)
}

/** Settings → Offline downloads: the Alafasy **test** audio (not licensed for distribution). */
@Composable
fun QariDownloadCard(modifier: Modifier = Modifier, vm: QariDownloadViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    var surahText by rememberSaveable { mutableStateOf("1") }
    var confirmAll by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val surah = surahText.toIntOrNull()?.takeIf { it in 1..114 }

    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(AlafasyTestQari.TITLE, style = MaterialTheme.typography.titleMedium)
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(
                    "TESTING ONLY. Per-ayah MP3 from everyayah.com, used for Lukma hints while we look for " +
                        "licensed recitations. Do not share builds that use it. Ayahs that are not downloaded " +
                        "are streamed from the same address when the phone is online.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(8.dp),
                )
            }
            Text(
                "On this phone: ${s.downloaded.size} of 114 surahs (${formatBytes(s.downloadedBytes)})",
                style = MaterialTheme.typography.bodyMedium,
            )
            val current = s.current
            if (current != null) {
                LinearProgressIndicator(
                    progress = { if (s.currentTotal == 0) 0f else s.currentDone.toFloat() / s.currentTotal },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Surah $current: ${s.currentDone} / ${s.currentTotal} ayahs" +
                        (if (s.queued.isNotEmpty()) " · ${s.queued.size} surahs waiting" else ""),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = vm::cancel) { Text("Stop download") }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = surahText,
                        onValueChange = { t -> surahText = t.filter(Char::isDigit).take(3) },
                        label = { Text("Surah") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = { surah?.let(vm::downloadSurah) }, enabled = surah != null && surah !in s.downloaded) {
                        Text(
                            when {
                                surah == null -> "Surah 1–114"
                                surah in s.downloaded -> "Downloaded"
                                else -> "Download (${formatBytes(AlafasyTestQari.surahBytes(surah))})"
                            }
                        )
                    }
                }
                OutlinedButton(onClick = { confirmAll = true }, enabled = s.downloaded.size < 114) {
                    Text("Download whole Quran (${formatBytes(AlafasyTestQari.TOTAL_BYTES)})")
                }
            }
            s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (s.downloaded.isNotEmpty() && current == null) {
                TextButton(onClick = { confirmDelete = true }) { Text("Remove downloaded audio") }
            }
        }
    }

    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text("Download the whole Quran?") },
            text = {
                Text(
                    "About ${formatBytes(AlafasyTestQari.TOTAL_BYTES - s.downloadedBytes)} more (6,236 files). " +
                        "Use Wi-Fi. The download stops if the app is closed and continues where it left off next time. " +
                        "For personal testing only."
                )
            },
            confirmButton = { TextButton(onClick = { confirmAll = false; vm.downloadAll() }) { Text("Download") } },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("Cancel") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Remove the Qari audio?") },
            text = { Text("Hints will stream when online, or show a notice when offline.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteAll() }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
