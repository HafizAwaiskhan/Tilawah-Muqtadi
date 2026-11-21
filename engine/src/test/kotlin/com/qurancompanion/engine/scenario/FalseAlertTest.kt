package com.qurancompanion.engine.scenario

import com.qurancompanion.engine.session.PracticeMode
import com.qurancompanion.engine.session.SessionState
import com.qurancompanion.engine.testutil.Driver
import com.qurancompanion.engine.testutil.Synthetic
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Recites all 200 ayahs of synthetic surah 2 CORRECTLY, with simulated recognition noise.
 * Any feedback is a false alert.
 *
 * Realistic noise (per word): 8% one-letter misrecognition, 3% whole-word misrecognition with low
 * recognizer confidence (0.3-0.7), 2% word dropped by the recognizer (its time passes silently),
 * 2% stutter (word recognized twice), 2% short low-confidence junk token; breaths at waqf and ayah ends.
 *
 * Harsh noise: the same, but misrecognitions come with high confidence and dropped words leave no
 * timing gap. These are acoustically indistinguishable from real mistakes at word level, so the rate
 * is reported (printed), not asserted to be zero.
 */
class FalseAlertTest {
    private val ref = Synthetic.reference

    private data class Stats(val ayahs: Int, val words: Int, val alerts: Int, val relocations: Int, val reachedEnd: Boolean, val lastAyah: Int?)

    private fun run(mode: PracticeMode, harsh: Boolean, seed: Int): Stats {
        val r = Random(seed)
        val d = Driver(mode = mode)
        var words = 0
        for (a in 1..200) {
            val ayah = Synthetic.ayah(2, a)
            for (id in ayah) {
                words++
                val key = ref.word(id).matchKey
                val p = r.nextDouble()
                when {
                    p < 0.08 -> {
                        val i = r.nextInt(key.length)
                        val c = Synthetic.LETTERS.filter { it != key[i] }.random(r)
                        d.sayText(key.substring(0, i) + c + key.substring(i + 1), confidence = 0.8f)
                    }
                    p < 0.11 -> d.sayText(Synthetic.vocab.random(r), confidence = if (harsh) 0.95f else 0.3f + 0.4f * r.nextFloat())
                    p < 0.13 -> if (harsh) Unit else d.silence(d.wordMs + d.gapMs)
                    p < 0.15 -> { d.sayText(key); d.sayText(key) }
                    p < 0.17 -> { d.sayText(Synthetic.junk(r, 2), confidence = 0.4f, durationMs = 150, gap = 50); d.sayText(key) }
                    else -> d.sayText(key, confidence = 0.85f + 0.15f * r.nextFloat())
                }
                if (ref.hasWaqfAfter(id)) d.silence(r.nextLong(1000, 2000))
            }
            d.silence(r.nextLong(1500, 3000))
        }
        d.silence(10_000)
        val relocations = d.c.history.drop(8).count { it == SessionState.LOCATING_POSITION }
        val end = Synthetic.ayah(2, 200).last
        return Stats(200, words, d.ui.feedback.size, relocations, (d.ui.confirmed?.wordId ?: 0) >= end - 1, d.ui.confirmed?.ayah)
    }

    @Test fun zeroFalseAlertsOnCorrectRecitationWithRealisticNoise() {
        for (mode in PracticeMode.entries) for (seed in 1..10) {
            val s = run(mode, harsh = false, seed = seed)
            println("realistic noise mode=$mode seed=$seed: $s")
            assertEquals(0, s.alerts, "false alerts in $mode seed $seed: $s")
            assertEquals(200, s.lastAyah, "tracking did not follow to the last ayah: $s")
        }
    }

    @Test fun reportFalseAlertRateWithHarshNoise() {
        for (mode in PracticeMode.entries) {
            val s = run(mode, harsh = true, seed = 5)
            val per100 = 100.0 * s.alerts / s.ayahs
            println("HARSH noise mode=$mode: $s -> ${"%.1f".format(per100)} false alerts per 100 ayahs")
            assertEquals(200, s.lastAyah, "tracking did not follow to the last ayah: $s")
        }
    }
}
