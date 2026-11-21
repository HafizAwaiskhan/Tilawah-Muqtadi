package com.qurancompanion.engine.reference

import com.qurancompanion.engine.recognition.TokenUnit
import com.qurancompanion.engine.testutil.Synthetic
import com.qurancompanion.engine.tracking.UnitSequence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReferenceTest {
    private fun w(id: Int, s: Int, a: Int, i: Int, key: String, ph: List<String> = listOf(key), waqf: Boolean = false) =
        WordRef(id, s, a, i, key, ph, waqf)

    private val small = InMemoryQuranReference(listOf(
        w(0, 1, 1, 1, "aa"), w(1, 1, 1, 2, "bb", waqf = true), w(2, 1, 1, 3, "cc"),
        w(3, 1, 2, 1, "dd", listOf("d", "x")), w(4, 2, 1, 1, "ee"),
    ))

    @Test fun ayahBoundaries() {
        assertEquals(0..2, small.ayahWords(1, 1))
        assertEquals(3..3, small.ayahWords(1, 2))
        assertEquals(4..4, small.ayahWords(2, 1))
        assertNull(small.ayahWords(3, 1))
        assertFalse(small.isAyahEnd(0)); assertFalse(small.isAyahEnd(1)); assertTrue(small.isAyahEnd(2))
        assertTrue(small.isAyahEnd(3)); assertTrue(small.isAyahEnd(4))
        assertTrue(small.hasWaqfAfter(1)); assertTrue(small.isPausePoint(1)); assertFalse(small.isPausePoint(0))
        assertEquals(WordPosition(3, 1, 2, 1), small.position(3))
    }

    @Test fun rejectsNonContiguousIds() {
        assertFailsWith<IllegalArgumentException> { InMemoryQuranReference(listOf(w(1, 1, 1, 1, "aa"))) }
        assertFailsWith<IllegalArgumentException> { InMemoryQuranReference(emptyList()) }
    }

    @Test fun unitSequenceMapsPhonemesToWords() {
        val seq = UnitSequence(small, TokenUnit.PHONEME)
        assertEquals(listOf("aa", "bb", "cc", "d", "x", "ee"), seq.units)
        assertEquals(3, seq.wordOf(4)); assertEquals(3, seq.firstUnitOf(3)); assertEquals(4, seq.lastUnitOf(3))
        assertEquals(listOf("d", "x"), seq.unitsOf(3))
        val words = UnitSequence(small, TokenUnit.WORD)
        assertEquals(5, words.size); assertEquals(2, words.wordOf(2))
    }

    @Test fun syntheticReferenceHasIntendedStructure() {
        val ref = Synthetic.reference
        assertEquals(7, (1..7).count { ref.ayahWords(1, it) != null })
        assertEquals(Synthetic.REFRAIN, Synthetic.ayah(2, 20).map { ref.word(it).matchKey })
        assertEquals(Synthetic.ayah(2, 50).map { ref.word(it).matchKey }, Synthetic.ayah(3, 5).map { ref.word(it).matchKey })
        assertTrue((0 until ref.wordCount).any { ref.hasWaqfAfter(it) })
    }
}
