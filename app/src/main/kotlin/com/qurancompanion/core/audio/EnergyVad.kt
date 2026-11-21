package com.qurancompanion.core.audio

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Small RMS energy voice-activity detector for 16 kHz PCM16 frames. It only provides speech
 * on/off edges for the pause detector (SessionInput.SpeechActivity); recognition itself does
 * not depend on it.
 *
 * - Threshold adapts to the noise floor: speech when RMS > max([minRms], noiseFloor × [noiseRatio]).
 *   The noise floor follows quiet frames quickly and loud frames very slowly.
 * - Speech starts after [attackMs] of consecutive loud audio and ends after [hangoverMs] of quiet
 *   audio (so breaths between syllables do not toggle it).
 */
class EnergyVad(
    private val sampleRate: Int = AudioCapture.SAMPLE_RATE,
    private val minRms: Double = 0.006,
    private val noiseRatio: Double = 3.0,
    private val attackMs: Long = 60,
    private val hangoverMs: Long = 400,
) {
    var speaking = false
        private set
    var noiseFloor = minRms / noiseRatio
        private set
    private var loudMs = 0L
    private var quietMs = 0L

    /** Feeds one frame; returns true/false on a speech start/end edge, null otherwise. */
    fun process(frame: ShortArray): Boolean? {
        if (frame.isEmpty()) return null
        val frameMs = frame.size * 1000L / sampleRate
        val r = rms(frame)
        val threshold = max(minRms, noiseFloor * noiseRatio)
        val loud = r > threshold
        noiseFloor = if (r < noiseFloor) 0.8 * noiseFloor + 0.2 * r
        else if (!loud) 0.95 * noiseFloor + 0.05 * r
        else 0.999 * noiseFloor + 0.001 * r
        if (loud) { loudMs += frameMs; quietMs = 0 } else { quietMs += frameMs; loudMs = 0 }
        return when {
            !speaking && loudMs >= attackMs -> { speaking = true; true }
            speaking && quietMs >= hangoverMs -> { speaking = false; false }
            else -> null
        }
    }

    fun reset() {
        speaking = false; loudMs = 0; quietMs = 0; noiseFloor = minRms / noiseRatio
    }

    companion object {
        /** RMS normalised to full scale (0..1). */
        fun rms(frame: ShortArray): Double {
            var sum = 0.0
            for (s in frame) { val v = s / 32768.0; sum += v * v }
            return sqrt(sum / frame.size)
        }
    }
}
