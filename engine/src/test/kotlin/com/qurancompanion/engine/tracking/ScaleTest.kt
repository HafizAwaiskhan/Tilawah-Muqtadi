package com.qurancompanion.engine.tracking

import com.qurancompanion.engine.recognition.Token
import com.qurancompanion.engine.recognition.TokenUnit
import com.qurancompanion.engine.reference.InMemoryQuranReference
import com.qurancompanion.engine.reference.WordRef
import com.qurancompanion.engine.testutil.Synthetic
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Full-Quran-sized synthetic reference (~78k words, ~6236 ayahs, ~400k phonemes): index build and search cost. */
class ScaleTest {
    private val ref: InMemoryQuranReference by lazy {
        val r = Random(99)
        val vocab = List(15_000) { i -> (1..r.nextInt(3, 8)).map { Synthetic.LETTERS[r.nextInt(Synthetic.LETTERS.length)] }.joinToString("") + i.toString(36) }
        val words = ArrayList<WordRef>()
        var ayahs = 0
        for (s in 1..114) for (a in 1..55) {
            if (ayahs++ >= 6236) break
            repeat(12) { i ->
                val k = (vocab.size * Math.pow(r.nextDouble(), 3.0)).toInt() // heavy head, like function words
                val w = vocab[k]
                words += WordRef(words.size, s, a, i + 1, w, w.map { it.toString() })
            }
        }
        InMemoryQuranReference(words)
    }

    private fun timeMs(block: () -> Unit): Long { val t = System.nanoTime(); block(); return (System.nanoTime() - t) / 1_000_000 }

    @Test fun wordAndPhonemeSearchAtFullScale() {
        for (unit in TokenUnit.entries) {
            lateinit var tr: QuranPositionTracker
            val build = timeMs { tr = QuranPositionTracker(ref, unit) }
            val r = Random(5)
            var located = 0
            val search = timeMs {
                repeat(20) {
                    tr.reset()
                    val start = r.nextInt(ref.wordCount - 20)
                    for (w in start until start + 10) for (u in tr.sequence.unitsOf(w)) tr.onStable(listOf(Token(u)))
                    if (tr.lastConfirmed?.wordId in (start + 8)..(start + 9)) located++
                }
            }
            println("scale unit=$unit words=${ref.wordCount} units=${tr.sequence.size} build=${build}ms 20 locates=${search}ms located=$located/20")
            assertTrue(located >= 18, "$unit located $located/20")
            assertTrue(search < 30_000, "too slow: $search ms")
        }
        assertEquals(74_832, ref.wordCount)
    }
}
