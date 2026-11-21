package com.qurancompanion.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.qurancompanion.core.model.AsrModelSpec
import com.qurancompanion.core.model.ModelDownloadManager
import com.qurancompanion.core.model.ModelInstallState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ModelDownloadViewModel @Inject constructor(
    private val downloads: ModelDownloadManager,
) : ViewModel() {
    val spec: AsrModelSpec get() = downloads.spec
    val state: StateFlow<ModelInstallState> = downloads.state

    /** Explicit user action only; the app never downloads in the background on its own. */
    fun download() = downloads.start()
    fun cancel() = downloads.cancel()
    fun delete() { viewModelScope.launch { downloads.delete() } }
}

/** "Recognition model" download card (Onboarding download step, Settings → Offline downloads). */
@Composable
fun ModelDownloadCard(modifier: Modifier = Modifier, vm: ModelDownloadViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showLicense by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val spec = vm.spec
    val sizeMb = spec.totalBytes / 1_000_000

    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Recognition model", style = MaterialTheme.typography.titleMedium)
            Text(
                "quran-asr-small · $sizeMb MB · ${spec.licenseName} licence. Downloaded once from " +
                    "Hugging Face, verified, then works offline. Your voice never leaves the device.",
                style = MaterialTheme.typography.bodyMedium,
            )
            when (val s = state) {
                ModelInstallState.NotInstalled -> Button(onClick = vm::download) { Text("Download model ($sizeMb MB)") }
                is ModelInstallState.Downloading -> {
                    LinearProgressIndicator(progress = { s.fraction }, modifier = Modifier.fillMaxWidth())
                    Text(
                        "Downloading ${s.downloadedBytes / 1_000_000} / ${s.totalBytes / 1_000_000} MB",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = vm::cancel) { Text("Pause download") }
                }
                ModelInstallState.Verifying -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("Verifying…", style = MaterialTheme.typography.bodySmall)
                }
                is ModelInstallState.Installed -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("✓ Installed (${spec.version})", color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 12.dp))
                    TextButton(onClick = { confirmDelete = true }) { Text("Remove") }
                }
                is ModelInstallState.Failed -> {
                    Text(s.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = vm::download) { Text(if (s.resumable) "Resume download" else "Try again") }
                }
            }
            Text(spec.attribution, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { showLicense = true }) { Text("View licence") }
        }
    }

    if (showLicense) {
        AlertDialog(
            onDismissRequest = { showLicense = false },
            title = { Text("quran-asr-small — ${spec.licenseName}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(spec.licenseText, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { showLicense = false }) { Text("Close") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Remove the recognition model?") },
            text = { Text("Sessions will use manual mode (tap the word you are on) until it is downloaded again.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete() }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
