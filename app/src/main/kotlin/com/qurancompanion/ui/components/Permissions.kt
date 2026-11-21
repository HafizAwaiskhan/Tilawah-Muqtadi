package com.qurancompanion.ui.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.qurancompanion.service.RecitationService

fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

fun Context.hasMicPermission() = hasPermission(Manifest.permission.RECORD_AUDIO)

fun Context.hasNotificationPermission() =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || hasPermission(Manifest.permission.POST_NOTIFICATIONS)

/**
 * Starting a session is a two-step, user-initiated action: make sure RECORD_AUDIO is granted
 * (requested in context, right when the user taps Start), then update the session state and
 * start the microphone foreground service while the app is visible.
 */
class RecitationStarter internal constructor(
    private val deniedState: State<Boolean>,
    private val launch: (onGranted: () -> Unit) -> Unit,
) {
    /** True when the user denied the microphone permission on the last attempt. */
    val lastDenied: Boolean get() = deniedState.value

    fun start(beginSession: () -> Unit) = launch(beginSession)
}

@Composable
fun rememberRecitationStarter(): RecitationStarter {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val denied = remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pending
        pending = null
        denied.value = !granted
        if (granted && action != null) {
            action()
            RecitationService.start(context)
        }
    }
    return remember(launcher) {
        RecitationStarter(denied) { onGranted ->
            if (context.hasMicPermission()) {
                onGranted()
                RecitationService.start(context)
            } else {
                pending = onGranted
                launcher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
}
