package com.qurancompanion.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FbankExtractorTest {
    private val fb = FbankExtractor()

    /** Mirror of prepare_test_data.synthetic_signal (integer-only, so both sides are exact). */
    private fun syntheticSignal(n: Int = 24000): ShortArray {
        val out = ShortArray(n)
        var seed = 12345L
        for (i in 0 until n) {
            seed = (seed * 1103515245L + 12345L) and 0x7FFFFFFFL
            val noise = ((seed shr 16) % 2001 - 1000).toInt()
            val saw = ((i * 37) % 400) * 40 - 8000
            val square = if ((i / 23) % 2 == 0) 3000 else -3000
            val v = when {
                i in 8000 until 9600 -> 0
                i >= 20000 -> Math.floorDiv(noise, 10) + 200
                else -> saw + square + noise
            }
            out[i] = v.coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    private fun maxDiff(a: FloatArray, b: FloatArray): Double {
        assertEquals(a.size, b.size)
        var m = 0.0
        for (i in a.indices) m = maxOf(m, abs(a[i].toDouble() - b[i]))
        return m
    }

    @Test fun frameCountMatchesReference() {
        assertEquals(0, fb.numFrames(0))
        assertEquals(1, fb.numFrames(80))
        assertEquals(0, fb.numFrames(79))
        assertEquals(100, fb.numFrames(16000))
        assertEquals(150, fb.numFrames(24000))
    }

    @Test fun syntheticGoldenFromPythonReference() {
        val golden = TestData.floats(javaClass.getResourceAsStream("/fbank_synthetic.f32")!!.readBytes())
        val f = fb.compute(syntheticSignal())
        assertEquals(150, f.numFrames)
        val d = maxDiff(f.data, golden)
        println("fbank synthetic: max|diff| = %.2e".format(d))
        assertTrue("max diff $d", d < 1e-3)
    }

    @Test fun realClipsGoldenFromPythonReference() {
        val clips = TestData.requireClips().filter { it.golden }
        for (c in clips) {
            val pcm = c.pcm()
            val f = fb.compute(pcm)
            val g = c.goldenFbank()
            assertEquals(g.size / 80, f.numFrames)
            val d = maxDiff(f.data, g)
            println("fbank ${c.id}: ${f.numFrames} frames, max|diff| = %.2e".format(d))
            assertTrue("${c.id}: max diff $d", d < 1e-3)
        }
    }

    @Test fun speed() {
        val sig = FloatArray(16000 * 30) { ((it * 7919) % 2000 - 1000) / 32768f }
        repeat(3) { fb.compute(sig) } // warm-up
        val n = 5
        val t0 = System.nanoTime()
        repeat(n) { fb.compute(sig) }
        val msPerSec = (System.nanoTime() - t0) / 1e6 / n / 30.0
        println("fbank speed: %.2f ms per second of audio (JVM, 1 thread)".format(msPerSec))
        assertTrue(msPerSec < 20.0)
    }
}
