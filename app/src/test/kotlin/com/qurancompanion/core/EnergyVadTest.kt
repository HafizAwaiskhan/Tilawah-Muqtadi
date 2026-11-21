package com.qurancompanion.core

import com.qurancompanion.core.audio.EnergyVad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class EnergyVadTest {
    private val rnd = Random(1)
    private fun tone(amp: Double) = ShortArray(320) { i -> (amp * 32767 * sin(2 * PI * 220 * i / 16000.0)).toInt().toShort() }
    private fun noise(amp: Double) = ShortArray(320) { ((rnd.nextDouble() * 2 - 1) * amp * 32767).toInt().toShort() }

    /** Feeds [frames] frames and returns the edges observed. */
    private fun EnergyVad.feed(frames: Int, gen: () -> ShortArray) = (1..frames).mapNotNull { process(gen()) }

    @Test fun rms() {
        assertEquals(0.0, EnergyVad.rms(ShortArray(320)), 1e-9)
        assertEquals(0.5 / Math.sqrt(2.0), EnergyVad.rms(tone(0.5)), 0.01)
    }

    @Test fun silenceAndLowNoiseNeverSpeak() {
        val vad = EnergyVad()
        assertTrue(vad.feed(200) { ShortArray(320) }.isEmpty())
        assertTrue(vad.feed(200) { noise(0.002) }.isEmpty())
        assertFalse(vad.speaking)
    }

    @Test fun speechStartsAfterAttackAndEndsAfterHangover() {
        val vad = EnergyVad(attackMs = 60, hangoverMs = 400)
        vad.feed(50) { noise(0.001) }
        assertNull(vad.process(tone(0.2))) // 20 ms
        assertNull(vad.process(tone(0.2))) // 40 ms
        assertEquals(true, vad.process(tone(0.2))) // 60 ms → start
        assertTrue(vad.feed(100) { tone(0.2) }.isEmpty())
        // a short breath (200 ms) inside speech does not end it
        assertTrue(vad.feed(10) { noise(0.001) }.isEmpty())
        assertTrue(vad.speaking)
        vad.feed(5) { tone(0.2) }
        val edges = vad.feed(25) { noise(0.001) } // 500 ms of quiet
        assertEquals(listOf(false), edges)
        assertFalse(vad.speaking)
    }

    @Test fun clickShorterThanAttackIgnored() {
        val vad = EnergyVad(attackMs = 60)
        vad.feed(20) { ShortArray(320) }
        assertNull(vad.process(tone(0.5)))
        assertNull(vad.process(ShortArray(320)))
        assertFalse(vad.speaking)
    }

    @Test fun adaptsToSteadyBackgroundNoise() {
        val vad = EnergyVad()
        // loud fan-like noise: may trigger at first, but the floor adapts and it stops
        vad.feed(500) { noise(0.05) }
        assertFalse(vad.speaking)
        // speech clearly above the noise floor is still detected
        assertTrue(vad.feed(20) { tone(0.6) }.contains(true))
    }
}
