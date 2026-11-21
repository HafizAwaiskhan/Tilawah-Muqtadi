package com.qurancompanion.engine.align

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AlignerTest {
    private val a = WordAligner()
    private fun types(al: Alignment) = al.ops.map { it.type }

    @Test fun similarity() {
        assertEquals(0, Similarity.levenshtein("abc", "abc"))
        assertEquals(1, Similarity.levenshtein("abc", "abd"))
        assertEquals(3, Similarity.levenshtein("", "abc"))
        assertEquals(0.75, Similarity.normalized("abcd", "abxd"), 1e-9)
        assertEquals(1.0, Similarity.exact("p", "p")); assertEquals(0.0, Similarity.exact("p", "q"))
        assertEquals(0.5, Similarity.sequence(listOf("a", "b"), listOf("a", "c")), 1e-9)
    }

    @Test fun globalAlignmentFindsSubInsDel() {
        val ref = listOf("kalam", "bisat", "durov", "fenik", "gorat")
        // durov -> zzzzz (substitution), extra "qqqq" (insertion), gorat missing (deletion)
        val q = listOf("kalam", "qqqq", "bisat", "zzzzz", "fenik")
        val al = a.global(q, ref)
        assertEquals(listOf(OpType.MATCH, OpType.INSERTION, OpType.MATCH, OpType.SUBSTITUTION, OpType.MATCH, OpType.DELETION), types(al))
        assertEquals(3, al.matches)
    }

    @Test fun fuzzyMatchAcceptsSmallRecognitionNoise() {
        val al = a.global(listOf("kalxm", "bisat"), listOf("kalam", "bisat"))
        assertEquals(listOf(OpType.MATCH, OpType.MATCH), types(al))
        assertTrue(al.ops[0].similarity < 1.0)
    }

    @Test fun nearMissSubstitutionIsCheaperThanGapPair() {
        val al = a.global(listOf("kxlxm"), listOf("kalam"))
        assertEquals(listOf(OpType.SUBSTITUTION), types(al))
    }

    @Test fun prefixHasFreeReferenceEnd() {
        val al = a.prefix(listOf("aaaa", "bbbb"), listOf("aaaa", "bbbb", "cccc", "dddd"))
        assertEquals(listOf(OpType.MATCH, OpType.MATCH), types(al))
        val skip = a.prefix(listOf("bbbb", "cccc"), listOf("aaaa", "bbbb", "cccc", "dddd"))
        assertEquals(listOf(OpType.DELETION, OpType.MATCH, OpType.MATCH), types(skip))
    }

    @Test fun localFindsEmbeddedSpanAndReportsAmbiguity() {
        val ref = listOf("xxxx", "aaaa", "bbbb", "cccc", "yyyy", "zzzz", "wwww", "aaaa", "bbbb", "dddd")
        val unique = a.local(listOf("qqqq", "aaaa", "bbbb", "cccc"), ref)
        assertEquals(3, unique.bestEnd); assertEquals(3, unique.best!!.matches)
        assertEquals(listOf(1, 3), listOf(unique.best!!.refStart, unique.best!!.refEnd))
        assertTrue(unique.margin >= 1.0)
        val ambiguous = a.local(listOf("aaaa", "bbbb"), ref)
        assertEquals(0.0, ambiguous.margin, 1e-9)
    }

    @Test fun localEndConstraintForcesRecentTokens() {
        // Old tokens match early; the last tokens match elsewhere: the alignment must explain the end.
        val ref = listOf("aaaa", "bbbb", "cccc", "dddd", "eeee", "ffff", "gggg", "hhhh")
        val r = a.local(listOf("aaaa", "bbbb", "cccc", "gggg", "hhhh"), ref, minQueryEnd = 4)
        assertEquals(7, r.bestEnd)
    }

    @Test fun phonemeAlignerIsExact() {
        val p = PhonemeAligner()
        val al = p.global(listOf("k", "t", "b"), listOf("k", "d", "b"))
        assertEquals(listOf(OpType.MATCH, OpType.SUBSTITUTION, OpType.MATCH), types(al))
    }
}
