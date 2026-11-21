package com.qurancompanion.core.cue

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gentle, non-visual cues for a surfaced mistake / Lukma offer. Triggered by the session manager
 * (not the UI), so they also work with the screen locked while the microphone service runs.
 */
interface SessionCues {
    /** Short, soft vibration; respects the system ringer/vibration settings. */
    fun vibrate()
    /** Short quiet tone on the current media output (earbuds if connected). Takes ≤ [TONE_MS]. */
    fun tone()

    companion object {
        const val TONE_MS = 120L
    }
}

object NoSessionCues : SessionCues {
    override fun vibrate() = Unit
    override fun tone() = Unit
}

@Singleton
class AndroidSessionCues @Inject constructor(@ApplicationContext private val context: Context) : SessionCues {
    private val main = Handler(Looper.getMainLooper())

    private fun vibrator(): Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        context.getSystemService(Vibrator::class.java)
    }

    override fun vibrate() {
        val v = vibrator() ?: return
        if (!v.hasVibrator()) return
        // Silent mode = no vibration either.
        val audio = context.getSystemService(AudioManager::class.java)
        if (audio?.ringerMode == AudioManager.RINGER_MODE_SILENT) return
        val effect = if (v.hasAmplitudeControl()) {
            VibrationEffect.createWaveform(longArrayOf(0, 35, 90, 35), intArrayOf(0, 70, 0, 70), -1)
        } else {
            VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_NOTIFICATION))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build())
            }
        }
    }

    override fun tone() {
        // A generated beep (no audio asset) at low volume on the media stream → follows the active output.
        val tg = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, TONE_VOLUME) }.getOrNull() ?: return
        runCatching { tg.startTone(ToneGenerator.TONE_PROP_BEEP, SessionCues.TONE_MS.toInt()) }
        main.postDelayed({ runCatching { tg.release() } }, SessionCues.TONE_MS + 200)
    }

    private companion object {
        /** 0–100 relative to the stream volume: quiet. */
        const val TONE_VOLUME = 25
    }
}
