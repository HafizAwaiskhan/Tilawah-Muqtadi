package com.qurancompanion.core.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Platform signals that must interrupt (or may resume) a recitation session. */
sealed interface InterruptionSignal {
    /** Headset unplugged / BT disconnected (ACTION_AUDIO_BECOMING_NOISY). */
    data object BecomingNoisy : InterruptionSignal
    data class FocusLost(val transient: Boolean) : InterruptionSignal
    data object FocusGained : InterruptionSignal
    /** Phone/VoIP call started (audio mode IN_CALL / IN_COMMUNICATION / RINGTONE). */
    data object CallStarted : InterruptionSignal
    data object CallEnded : InterruptionSignal
}

/**
 * Holds audio focus for the session and reports interruption signals while collected.
 * Collecting requests focus; cancelling abandons it. No phone-state permission is needed:
 * calls are detected via audio-mode changes (API 31+) and focus loss / silenced recording
 * ([AudioCapture.silenced]) on older versions.
 */
@Singleton
class AudioInterruptionMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    fun signals(): Flow<InterruptionSignal> = callbackFlow {
        val noisyReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                    trySend(InterruptionSignal.BecomingNoisy)
                }
            }
        }
        ContextCompat.registerReceiver(
            context, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> trySend(InterruptionSignal.FocusGained)
                AudioManager.AUDIOFOCUS_LOSS -> trySend(InterruptionSignal.FocusLost(transient = false))
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> trySend(InterruptionSignal.FocusLost(transient = true))
            }
        }
        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        audioManager.requestAudioFocus(focusRequest)

        var modeListener: Any? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            var inCall = isCallMode(audioManager.mode)
            val l = AudioManager.OnModeChangedListener { mode ->
                val nowInCall = isCallMode(mode)
                if (nowInCall != inCall) {
                    inCall = nowInCall
                    trySend(if (nowInCall) InterruptionSignal.CallStarted else InterruptionSignal.CallEnded)
                }
            }
            audioManager.addOnModeChangedListener(ContextCompat.getMainExecutor(context), l)
            modeListener = l
        }

        awaitClose {
            runCatching { context.unregisterReceiver(noisyReceiver) }
            audioManager.abandonAudioFocusRequest(focusRequest)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && modeListener != null) {
                audioManager.removeOnModeChangedListener(modeListener as AudioManager.OnModeChangedListener)
            }
        }
    }

    private fun isCallMode(mode: Int) = mode == AudioManager.MODE_IN_CALL ||
        mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_RINGTONE
}
