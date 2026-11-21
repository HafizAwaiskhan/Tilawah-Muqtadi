package com.qurancompanion.data

import com.qurancompanion.RealReferenceDb
import com.qurancompanion.data.reference.EngineReferenceBuilder
import com.qurancompanion.data.reference.EngineWordRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class EngineReferenceTest {
    @Test fun realDbCountsAndContiguousIds() {
        val ref = RealReferenceDb.reference
        assertEquals(77_429, ref.wordCount)
        val surahs = HashSet<Int>()
        val ayahs = HashSet<Long>()
        for (id in 0 until ref.wordCount) {
            val w = ref.word(id)
            assertEquals(id, w.globalId)
            assertTrue("empty matchKey at $id", w.matchKey.isNotEmpty())
            surahs += w.surah
            ayahs += w.surah * 1000L + w.ayah
        }
        assertEquals(114, surahs.size)
        assertEquals(6_236, ayahs.size)
        // ayah boundaries agree with the ayah table
        RealReferenceDb.connect { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT surah, ayah, first_word_id, word_count FROM ayah").use { rs ->
                    var n = 0
                    while (rs.next()) {
                        val r = ref.ayahWords(rs.getInt(1), rs.getInt(2))!!
                        assertEquals(rs.getInt(3), r.first)
                        assertEquals(rs.getInt(4), r.last - r.first + 1)
                        n++
                    }
                    assertEquals(6_236, n)
                }
            }
        }
        assertTrue(ref.isAyahEnd(ref.wordCount - 1))
        assertTrue(ref.word(0).phonetic.isNotEmpty())
    }

    @Test fun waqfHintsComeFromStopMarks() {
        val ref = RealReferenceDb.reference
        val waqf = (0 until ref.wordCount).count { ref.hasWaqfAfter(it) }
        val withMarks = RealReferenceDb.connect { c ->
            c.createStatement().use { it.executeQuery("SELECT COUNT(*) FROM word WHERE stop_marks != ''").use { rs -> rs.next(); rs.getInt(1) } }
        }
        assertTrue("waqf=$waqf marks=$withMarks", waqf in 1..withMarks)
    }

    @Test fun pauseSignRuleExcludesDoNotStop() {
        assertFalse(EngineReferenceBuilder.isPauseSign(""))
        assertFalse(EngineReferenceBuilder.isPauseSign(null))
        assertFalse(EngineReferenceBuilder.isPauseSign("ۙ"))
        assertTrue(EngineReferenceBuilder.isPauseSign("ۖ"))
        assertTrue(EngineReferenceBuilder.isPauseSign("ۚ"))
    }

    @Test fun builderRejectsNonContiguousIds() {
        val b = EngineReferenceBuilder()
        b.add(EngineWordRow(0, 1, 1, 1, "aa", "a", ""))
        try {
            b.add(EngineWordRow(2, 1, 1, 2, "bb", "b", ""))
            fail("expected failure")
        } catch (_: IllegalArgumentException) {
        }
    }

    /** Measures load time and retained heap of the full reference (printed; loose upper bounds asserted). */
    @Test fun loadTimeAndMemory() {
        RealReferenceDb.assumeAvailable()
        val rt = Runtime.getRuntime()
        fun used(): Long { repeat(3) { System.gc(); Thread.sleep(50) }; return rt.totalMemory() - rt.freeMemory() }
        RealReferenceDb.build() // warm up JDBC/JIT
        val before = used()
        val t0 = System.nanoTime()
        val ref = RealReferenceDb.build()
        val ms = (System.nanoTime() - t0) / 1_000_000
        val after = used()
        val mb = (after - before) / (1024.0 * 1024.0)
        println("EngineReference: ${ref.wordCount} words loaded in $ms ms, retained ≈ %.1f MB".format(mb))
        assertEquals(77_429, ref.wordCount)
        assertTrue("load took $ms ms", ms < 10_000)
        assertTrue("retained $mb MB", mb < 120)
    }
}
