package com.qurancompanion.asr

import com.qurancompanion.engine.reference.InMemoryQuranReference
import com.qurancompanion.engine.reference.WordRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.DriverManager
import kotlin.math.abs

/** Tiny test-only adapter: reference DB -> engine reference (matchKey = norm_skeleton). */
object DbReference {
    val reference: InMemoryQuranReference by lazy {
        val words = ArrayList<WordRef>(77429)
        DriverManager.getConnection("jdbc:sqlite:${TestData.dbFile.path}").use { c ->
            c.createStatement().executeQuery("SELECT id, surah, ayah, word_index, norm_skeleton, stop_marks FROM word ORDER BY id").use { r ->
                while (r.next()) words += WordRef(r.getInt(1), r.getInt(2), r.getInt(3), r.getInt(4), r.getString(5),
                    waqfAfter = !r.getString(6).isNullOrEmpty())
            }
        }
        InMemoryQuranReference(words)
    }
    fun ayahKeys(surah: Int, ayah: Int): List<String> =
        reference.ayahWords(surah, ayah)!!.map { reference.word(it).matchKey }
    fun alphabet(): Set<Char> {
        val s = HashSet<Char>()
        for (i in 0 until reference.wordCount) s.addAll(reference.word(i).matchKey.toList())
        return s
    }
}

class RealAudioTest {

    /** Task 3: Kotlin fbank + ORT + greedy CTC == Python reference, word for word and frame for frame. */
    @Test fun offlineTranscriptsIdenticalToPythonReference() {
        TestData.requireModel()
        val clips = TestData.requireClips()
        val tr = OfflineTranscriber(TestData.model, TestData.vocab)
        var audio = 0.0; var ns = 0L; var identical = 0; var nWords = 0; var exactTimes = 0
        for (c in clips) {
            val pcm = c.pcm()
            val t0 = System.nanoTime()
            val words = tr.transcribe(pcm)
            ns += System.nanoTime() - t0; audio += c.seconds
            assertEquals(c.id, c.words.map { it.text }, words.map { it.text })
            // ORT JVM 1.26 vs the Python reference's ORT 1.32: int8 kernels differ slightly, which flips
            // near-tie blank/letter frames (p ~ 0.5) -> a word edge may move by one 40 ms frame; text never.
            words.zip(c.words).forEach { (k, p) ->
                val ds = abs(k.startFrame * 0.04 - p.startS); val de = abs(k.endFrame * 0.04 - p.endS)
                assertTrue("${c.id} ${k.text} start ${k.startFrame * 0.04} vs ${p.startS}", ds < 0.041)
                assertTrue("${c.id} ${k.text} end ${k.endFrame * 0.04} vs ${p.endS}", de < 0.041)
                nWords++; if (ds < 1e-3 && de < 1e-3) exactTimes++
            }
            identical++
        }
        println("offline: $identical/${clips.size} clips identical text to Python, $exactTimes/$nWords words with identical frame times; %.0f s audio, RTF %.4f (incl. fbank), model load %d ms"
            .format(audio, ns / 1e9 / audio, TestData.model.loadMs))
    }

    /** Task 5: alphabet compatibility and key-match rate of normalized output vs DB norm_skeleton. */
    @Test fun normalizedWordsMatchDbNormSkeleton() {
        TestData.requireModel(); TestData.requireDb()
        val clips = TestData.requireClips()
        val dbAlpha = DbReference.alphabet()
        val norm = SkeletonNormalizer()
        val vocabLetters = TestData.vocab.letterIds.map { TestData.vocab[it].single() }.toSet()
        // every DB key letter is a model letter; every model letter survives normalization or is a documented drop
        assertEquals(norm.alphabet, dbAlpha)
        assertTrue((dbAlpha - vocabLetters).isEmpty())
        assertEquals(setOf(SkeletonNormalizer.HAMZA), vocabLetters - dbAlpha)

        val tr = OfflineTranscriber(TestData.model, TestData.vocab)
        var total = 0; var exact = 0; var rawExact = 0
        val misses = LinkedHashMap<String, Int>()
        for (c in clips) {
            val raw = tr.transcribe(c.pcm())
            val hyp = norm.normalize(raw).map { it.text }
            val ref = DbReference.ayahKeys(c.surah, c.ayah)
            val pairs = align(hyp, ref)
            total += hyp.size
            exact += pairs.count { (h, r) -> h != null && r != null && hyp[h] == ref[r] }
            rawExact += align(raw.map { it.text }, ref).count { (h, r) -> h != null && r != null && raw[h].text == ref[r] }
            for ((h, r) in pairs) if (h != null && (r == null || hyp[h] != ref[r])) misses.merge("${c.surah}:${c.ayah}:${r?.plus(1)}", 1, Int::plus)
        }
        val rate = 100.0 * exact / total
        println("key match: %d/%d = %.2f%% normalized (raw model output: %.2f%%); misses by surah:ayah:word = %s"
            .format(exact, total, rate, 100.0 * rawExact / total, misses))
        assertTrue("key-match rate $rate", rate >= 99.0)
    }

    companion object {
        /** Levenshtein word alignment; returns (hypIndex?, refIndex?) pairs. */
        fun align(h: List<String>, r: List<String>): List<Pair<Int?, Int?>> {
            val d = Array(h.size + 1) { IntArray(r.size + 1) }
            for (i in 0..h.size) d[i][0] = i
            for (j in 0..r.size) d[0][j] = j
            for (i in 1..h.size) for (j in 1..r.size)
                d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (h[i - 1] == r[j - 1]) 0 else 1)
            val out = ArrayList<Pair<Int?, Int?>>()
            var i = h.size; var j = r.size
            while (i > 0 || j > 0) {
                when {
                    i > 0 && j > 0 && d[i][j] == d[i - 1][j - 1] + if (h[i - 1] == r[j - 1]) 0 else 1 -> { out += (i - 1) to (j - 1); i--; j-- }
                    i > 0 && d[i][j] == d[i - 1][j] + 1 -> { out += (i - 1) to null; i-- }
                    else -> { out += null to (j - 1); j-- }
                }
            }
            return out.reversed()
        }
    }
}
