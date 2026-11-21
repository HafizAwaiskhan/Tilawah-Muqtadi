package com.qurancompanion.engine.tracking

import com.qurancompanion.engine.align.OpType
import com.qurancompanion.engine.recognition.Token
import com.qurancompanion.engine.recognition.TokenUnit
import com.qurancompanion.engine.testutil.Synthetic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuranPositionTrackerTest {
    private val ref = Synthetic.reference
    private fun t(id: Int) = Token(ref.word(id).matchKey)
    private fun tracker() = QuranPositionTracker(ref, TokenUnit.WORD)

    /** A tracker located at the end of [ayah] of surah 2. */
    private fun locatedAt(ayah: Int): QuranPositionTracker {
        val tr = tracker()
        Synthetic.ayah(2, ayah).forEach { tr.onStable(listOf(t(it))) }
        tr.onStable(listOf(t(Synthetic.ayah(2, ayah + 1).first)))
        tr.onStable(listOf(t(Synthetic.ayah(2, ayah + 1).first + 1)))
        assertEquals(Synthetic.ayah(2, ayah + 1).first + 1, tr.lastConfirmed!!.wordId)
        return tr
    }

    @Test fun locatesThenAdvancesWordByWord() {
        val tr = tracker()
        val ids = Synthetic.ayah(2, 60)
        val kinds = ids.flatMap { tr.onStable(listOf(t(it))) }.map { it.kind }
        assertEquals(UpdateKind.LOCATED, kinds.first())
        assertTrue(kinds.drop(1).all { it == UpdateKind.ADVANCE })
        assertTrue(tr.lastConfirmed!!.wordId >= ids.last - 1)
        assertEquals(ids.last, tr.candidate!!.wordId)
        assertTrue(tr.confidence > 0.8)
    }

    @Test fun provisionalTokensNeverMoveConfirmedPosition() {
        val tr = locatedAt(30)
        val before = tr.lastConfirmed
        val next = before!!.wordId + 1
        tr.onProvisional(listOf(t(next), t(next + 1)))
        assertEquals(before, tr.lastConfirmed)
        assertEquals(next + 1, tr.candidate!!.wordId)
        tr.onProvisional(emptyList()) // hypothesis revised away
        assertEquals(before.wordId, tr.candidate!!.wordId)
    }

    @Test fun substitutionAndDeletionAppearAsOps() {
        val tr = locatedAt(30)
        val p = tr.lastConfirmed!!.wordId
        // p+1 ok, p+2 replaced by junk, p+3 ok, p+4 skipped, p+5, p+6, p+7 ok
        val ups = listOf(t(p + 1), Token("qcqcq"), t(p + 3), t(p + 5), t(p + 6), t(p + 7)).flatMap { tr.onStable(listOf(it)) }
        val ops = ups.filter { it.kind == UpdateKind.ADVANCE }.flatMap { it.ops }
        assertEquals(OpType.SUBSTITUTION, ops.first { it.refIndex == p + 2 }.type)
        assertEquals(OpType.DELETION, ops.first { it.refIndex == p + 4 }.type)
        assertTrue(tr.lastConfirmed!!.wordId >= p + 6)
    }

    @Test fun repeatOfCurrentAyahIsRepeatNotMistake() {
        val tr = locatedAt(30)
        val a31 = Synthetic.ayah(2, 31)
        val ups = a31.flatMap { tr.onStable(listOf(t(it))) }
        assertTrue(ups.any { it.kind == UpdateKind.REPEAT }, "$ups")
        assertTrue(ups.filter { it.kind == UpdateKind.ADVANCE }.flatMap { it.ops }.all { it.type == OpType.MATCH || it.type == OpType.INSERTION })
        assertTrue(tr.lastConfirmed!!.wordId >= a31.last - 1)
    }

    @Test fun skipForwardIsSkip() {
        val tr = locatedAt(30)
        val ups = Synthetic.ayah(2, 33).flatMap { tr.onStable(listOf(t(it))) }
        assertTrue(ups.any { it.kind == UpdateKind.SKIP }, "$ups")
        assertTrue(tr.lastConfirmed!!.wordId >= Synthetic.ayah(2, 33).last - 1)
    }

    @Test fun jumpToOtherSurahGoesLostThenJump() {
        val tr = locatedAt(30)
        val target = Synthetic.ayah(3, 20)
        val ups = target.flatMap { tr.onStable(listOf(t(it))) }
        val kinds = ups.map { it.kind }
        assertTrue(UpdateKind.LOST in kinds && UpdateKind.JUMP in kinds, "$kinds")
        assertEquals(3, tr.lastConfirmed!!.surah)
    }

    @Test fun noiseKeepsLastConfirmedPosition() {
        val tr = locatedAt(30)
        val before = tr.lastConfirmed
        repeat(40) { tr.onStable(listOf(Token(Synthetic.junk(kotlin.random.Random(it))))) }
        assertEquals(before, tr.lastConfirmed)
        assertFalse(tr.isTracking)
        assertNull(tr.candidate)
    }

    @Test fun restoreContinuesFromPersistedWord() {
        val tr = tracker()
        val a = Synthetic.ayah(2, 90)
        tr.restore(a.first + 1)
        tr.onStable(listOf(t(a.first + 2))); tr.onStable(listOf(t(a.first + 3)))
        assertEquals(a.first + 3, tr.lastConfirmed!!.wordId)
    }

    @Test fun phonemeTrackingLocatesAndAdvances() {
        val tr = QuranPositionTracker(ref, TokenUnit.PHONEME)
        val words = Synthetic.ayah(2, 140).toList() + Synthetic.ayah(2, 141).toList()
        for (w in words) for (p in ref.word(w).phonetic) tr.onStable(listOf(Token(p)))
        assertTrue(tr.lastConfirmed!!.wordId >= words.last() - 1, "${tr.lastConfirmed}")
    }
}
