package com.qurancompanion.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.qurancompanion.ui.components.hasMicPermission
import com.qurancompanion.ui.components.hasNotificationPermission

private enum class Step { WELCOME, MICROPHONE, BACKGROUND, PRIVACY, DOWNLOADS }

/**
 * First-run flow. Permissions are requested in context (on the step that explains why), and
 * every step can be skipped: the app re-requests RECORD_AUDIO when the user starts reciting.
 */
@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val context = LocalContext.current
    var stepIndex by rememberSaveable { mutableIntStateOf(0) }
    val step = Step.entries[stepIndex]
    var micGranted by rememberSaveable { mutableStateOf(context.hasMicPermission()) }
    var notifGranted by rememberSaveable { mutableStateOf(context.hasNotificationPermission()) }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { micGranted = it }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifGranted = it }

    val next: () -> Unit = { if (stepIndex < Step.entries.lastIndex) stepIndex++ else onFinished() }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().padding(24.dp).fillMaxSize()) {
            LinearProgressIndicator(
                progress = { (stepIndex + 1f) / Step.entries.size },
                modifier = Modifier.fillMaxWidth(),
            )
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (step) {
                    Step.WELCOME -> StepContent(
                        Icons.AutoMirrored.Filled.MenuBook, "Assalamu alaikum",
                        "Quran Companion listens while you recite from memory, follows your position " +
                            "word by word, and stays quiet unless you ask for help (Lukma) or it is " +
                            "confident something was missed.",
                    )
                    Step.MICROPHONE -> {
                        StepContent(
                            Icons.Filled.Mic, "Microphone",
                            "To follow your recitation the app needs the microphone while a session is " +
                                "running. It is only used after you press Start, and you can pause at any time.",
                        )
                        PermissionButton(
                            granted = micGranted, grantedText = "Microphone allowed", requestText = "Allow microphone",
                        ) { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                    }
                    Step.BACKGROUND -> {
                        StepContent(
                            Icons.Filled.Notifications, "Listening with the screen off",
                            "Once you start a session from the app, it keeps listening with the screen " +
                                "locked or the phone in your pocket. A notification shows that the " +
                                "microphone is in use and lets you pause, resume or stop. A session must " +
                                "always be started from the open app.",
                        )
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            PermissionButton(
                                granted = notifGranted, grantedText = "Notifications allowed",
                                requestText = "Allow notifications",
                            ) { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                        }
                    }
                    Step.PRIVACY -> StepContent(
                        Icons.Filled.Lock, "Private by design",
                        "Recognition runs entirely on this device. Your voice is never uploaded and " +
                            "no audio recordings are stored. History keeps only positions and " +
                            "durations, and you can delete it or turn it off in Settings.",
                    )
                    Step.DOWNLOADS -> StepContent(
                        Icons.Filled.CloudDownload, "Downloads",
                        "The recognition model and Qari audio for hints are downloaded once and then " +
                            "work offline. Until they are installed you can still read the Quran, set " +
                            "your position manually and keep session history.",
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (stepIndex > 0) {
                    TextButton(onClick = { stepIndex-- }) { Text("Back") }
                } else {
                    Spacer(Modifier.size(1.dp))
                }
                Button(onClick = next) { Text(if (step == Step.DOWNLOADS) "Get started" else "Next") }
            }
        }
    }
}

@Composable
private fun StepContent(icon: ImageVector, title: String, body: String) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
    Text(title, style = MaterialTheme.typography.headlineSmall)
    Text(body, style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun PermissionButton(granted: Boolean, grantedText: String, requestText: String, onRequest: () -> Unit) {
    Spacer(Modifier.height(8.dp))
    if (granted) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text("✓ $grantedText", color = MaterialTheme.colorScheme.primary) }
    } else {
        OutlinedButton(onClick = onRequest) { Text(requestText) }
        Text(
            "You can skip this; we'll ask again when it's needed.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
