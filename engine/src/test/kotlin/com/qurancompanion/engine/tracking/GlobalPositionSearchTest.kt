package com.qurancompanion.engine.tracking

import com.qurancompanion.engine.align.Aligner
import com.qurancompanion.engine.recognition.TokenUnit
import com.qurancompanion.engine.testutil.Synthetic
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GlobalPositionSearchTest {
    private val ref = Synthetic.reference
    private fun search(unit: TokenUnit): Pair<GlobalPositionSearch, UnitSequence> {
        val cfg = TrackerConfig.forUnit(unit)
        val seq = UnitSequence(ref, unit)
        return GlobalPositionSearch(seq, Aligner.forUnit(unit, cfg.matchThreshold), cfg) to seq
    }
    private fun keys(ids: IntRange) = ids.map { ref.word(it).matchKey }

    @Test fun locatesStartMidAyah() {
        val (s, _) = search(TokenUnit.WORD)
        val ayah = Synthetic.ayah(2, 77)
        val ids = (ayah.first + 2)..minOf(ayah.last, ayah.first + 7)
        val r = s.search(keys(ids))
        assertTrue(r.accepted, "$r")
        assertEquals(ids.last, r.endUnit)
        assertEquals(ids.first, r.startUnit)
    }

    @Test fun locatesWithLeadingNoiseAndOneMisrecognizedWord() {
        val (s, _) = search(TokenUnit.WORD)
        val ayah = Synthetic.ayah(2, 120)
        val words = keys(ayah).toMutableList()
        words[2] = "qcqcq"
        val r = s.search(listOf("qqqq", "cccc") + words)
        assertTrue(r.accepted, "$r")
        assertEquals(ayah.last, r.endUnit)
    }

    @Test fun repeatedRefrainStaysAmbiguousUntilContextSeparates() {
        val (s, _) = search(TokenUnit.WORD)
        val refrain = keys(Synthetic.ayah(2, 22))
        assertEquals(SearchStatus.AMBIGUOUS, s.search(refrain).status)
        val next = keys(Synthetic.ayah(2, 23)).take(4)
        val r = s.search(refrain + next)
        assertTrue(r.accepted, "$r")
        assertEquals(Synthetic.ayah(2, 23).first + 3, r.endUnit)
    }

    @Test fun identicalAyahInTwoSurahsIsAmbiguous() {
        val (s, _) = search(TokenUnit.WORD)
        assertNotEquals(SearchStatus.ACCEPTED, s.search(keys(Synthetic.ayah(3, 5)).takeLast(12)).status)
    }

    @Test fun tooFewOrRandomTokensAreRejected() {
        val (s, _) = search(TokenUnit.WORD)
        assertEquals(SearchStatus.INSUFFICIENT, s.search(keys(Synthetic.ayah(2, 5)).take(2)).status)
        val r = Random(3)
        var accepted = 0
        repeat(300) {
            val q = List(12) { Synthetic.vocab[r.nextInt(Synthetic.vocab.size)] }
            if (s.search(q).accepted) accepted++
        }
        assertEquals(0, accepted)
    }

    @Test fun phonemeSearchLocates() {
        val (s, seq) = search(TokenUnit.PHONEME)
        val ayah = Synthetic.ayah(2, 140)
        val phon = ayah.drop(1).flatMap { ref.word(it).phonetic }.take(30)
        val r = s.search(phon)
        assertTrue(r.accepted, "$r")
        assertEquals(seq.firstUnitOf(ayah.first + 1) + phon.size - 1, r.endUnit)
    }
}
