package com.qurancompanion.engine.testutil

import com.qurancompanion.engine.align.Similarity
import com.qurancompanion.engine.reference.InMemoryQuranReference
import com.qurancompanion.engine.reference.WordRef
import kotlin.random.Random

/**
 * Synthetic reference: random letter strings (no real text), 3 "surahs", Zipf-like word frequencies,
 * plus deliberate repetitions:
 *  - [PHRASE] opens surah 1 ayah 3 and surah 3 ayah 10,
 *  - surah 2 ayahs 20, 22, 24, 26 are exactly the [REFRAIN],
 *  - surah 3 ayah 5 is identical to surah 2 ayah 50.
 * Ayahs with >= 10 words have a waqf hint after word len/2. Phonetic = one phoneme per letter.
 */
object Synthetic {
    const val LETTERS = "bdfghjklmnprstvwxz" // 'q' and 'c' never used: strings of them never match anything
    val vocab: List<String> = buildVocab(300, Random(7))
    val REFRAIN: List<String> = listOf(vocab[3], vocab[40], vocab[11], vocab[77], vocab[5])
    val PHRASE: List<String> = listOf(vocab[2], vocab[90], vocab[17], vocab[150], vocab[8], vocab[61])

    /** ayah lists per surah; ayahs[surah-1][ayah-1] = words */
    val ayahs: List<List<List<String>>> = buildAyahs(Random(11))
    val reference: InMemoryQuranReference = toReference(ayahs)

    private fun buildVocab(n: Int, r: Random): List<String> {
        val out = ArrayList<String>()
        while (out.size < n) {
            val len = r.nextInt(4, 8)
            val w = (1..len).map { LETTERS[r.nextInt(LETTERS.length)] }.joinToString("")
            if (out.none { Similarity.normalized(it, w) >= 0.5 }) out += w
        }
        return out
    }

    private fun zipfWord(r: Random): String {
        // weights 1/(k+1)
        val h = vocab.indices.sumOf { 1.0 / (it + 1) }
        var x = r.nextDouble() * h
        for (k in vocab.indices) { x -= 1.0 / (k + 1); if (x <= 0) return vocab[k] }
        return vocab.last()
    }

    private fun buildAyahs(r: Random): List<List<List<String>>> {
        fun randomAyah() = List(r.nextInt(5, 15)) { zipfWord(r) }
        val s1 = MutableList(7) { randomAyah() }
        s1[2] = PHRASE + List(4) { zipfWord(r) }
        val s2 = MutableList(200) { randomAyah() }
        for (a in listOf(20, 22, 24, 26)) s2[a - 1] = REFRAIN
        val s3 = MutableList(30) { randomAyah() }
        s3[9] = PHRASE + List(5) { zipfWord(r) }
        s3[4] = s2[49]
        return listOf(s1, s2, s3)
    }

    private fun toReference(ayahs: List<List<List<String>>>): InMemoryQuranReference {
        val words = ArrayList<WordRef>()
        ayahs.forEachIndexed { s, surah ->
            surah.forEachIndexed { a, ayah ->
                ayah.forEachIndexed { i, w ->
                    words += WordRef(
                        globalId = words.size, surah = s + 1, ayah = a + 1, wordIndex = i + 1, matchKey = w,
                        phonetic = w.map { it.toString() }, waqfAfter = ayah.size >= 10 && i == ayah.size / 2 - 1,
                    )
                }
            }
        }
        return InMemoryQuranReference(words)
    }

    fun ayah(surah: Int, ayah: Int): IntRange = reference.ayahWords(surah, ayah)!!

    /** A random string that never matches any vocabulary word. */
    fun junk(r: Random, len: Int = 5) = (1..len).map { "qc"[r.nextInt(2)] }.joinToString("")
}

class ManualClock(var now: Long = 1_000_000L) : com.qurancompanion.engine.session.Clock {
    override fun nowMs() = now
    fun advance(ms: Long) { now += ms }
}
