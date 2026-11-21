package com.qurancompanion.engine.align

import com.qurancompanion.engine.recognition.Token
import com.qurancompanion.engine.recognition.TokenUnit
import com.qurancompanion.engine.reference.InMemoryQuranReference
import com.qurancompanion.engine.reference.WordRef
import com.qurancompanion.engine.tracking.UnitSequence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MistakeClassifierTest {
    private val keys = listOf("kalam", "bisat", "durov", "fenik", "gorat", "hivel", "jonap", "lumes")
    private val ref = InMemoryQuranReference(keys.mapIndexed { i, k -> WordRef(i, 1, 1, i + 1, k, k.map { it.toString() }) })
    private val words = UnitSequence(ref, TokenUnit.WORD)
    private val clf = MistakeClassifier(words, Similarity::normalized, 0.7)
    private val aligner = WordAligner()

    /** Aligns [said] against the reference words starting at 0 and classifies. */
    private fun classify(said: List<Token>, refRange: IntRange = keys.indices): List<CandidateMistake> {
        val al = aligner.global(said.map { it.text }, keys.slice(refRange)).shifted(0, refRange.first)
        return clf.classify(al.ops, said)
    }

    private fun toks(vararg w: String) = w.mapIndexed { i, s -> Token(s, i * 600L, i * 600L + 450, 0.95f) }

    @Test fun cleanRecitationHasNoMistakes() {
        assertTrue(classify(toks(*keys.toTypedArray())).isEmpty())
    }

    @Test fun clearSubstitutionIsConfident() {
        val m = classify(toks("kalam", "bisat", "qcqcq", "fenik", "gorat", "hivel", "jonap", "lumes")).single()
        assertEquals(MistakeType.SUBSTITUTION, m.type); assertEquals(2, m.wordId)
        assertTrue(m.confidence > 0.85, "${m.confidence}")
    }

    @Test fun nearMissSubstitutionIsLessConfident() {
        val m = classify(toks("kalam", "bisat", "duxox", "fenik", "gorat", "hivel", "jonap", "lumes")).single()
        assertEquals(MistakeType.SUBSTITUTION, m.type)
        assertTrue(m.confidence < 0.8, "${m.confidence}")
    }

    @Test fun lowTokenConfidenceLowersConfidence() {
        val t = toks("kalam", "bisat", "qcqcq", "fenik", "gorat", "hivel", "jonap", "lumes").toMutableList()
        t[2] = t[2].copy(confidence = 0.4f)
        assertTrue(classify(t).single().confidence < 0.5)
    }

    @Test fun deletionWithoutTimingGapIsConfident() {
        val m = classify(toks("kalam", "bisat", "fenik", "gorat", "hivel", "jonap", "lumes")).single()
        assertEquals(MistakeType.DELETION, m.type); assertEquals(2, m.wordId)
        assertTrue(m.confidence > 0.85)
    }

    @Test fun deletionWithWordSizedGapIsProbablyRecognizerMiss() {
        val t = listOf(Token("kalam", 0, 450), Token("bisat", 600, 1050), Token("fenik", 1800, 2250), Token("gorat", 2400, 2850), Token("hivel", 3000, 3450))
        val m = classify(t, 0..5).single()
        assertEquals(MistakeType.DELETION, m.type)
        assertTrue(m.confidence < 0.5, "${m.confidence}")
    }

    @Test fun repeatedWordIsRepetitionNotError() {
        val m = classify(toks("kalam", "bisat", "bisat", "durov", "fenik", "gorat", "hivel", "jonap", "lumes")).single()
        assertEquals(MistakeType.REPETITION, m.type); assertTrue(!m.isError)
    }

    @Test fun swappedWordsAreOrder() {
        val m = classify(toks("kalam", "bisat", "fenik", "durov", "gorat", "hivel", "jonap", "lumes")).single()
        assertEquals(MistakeType.ORDER, m.type)
        assertEquals(setOf(2, 3), m.relatedWordIds)
    }

    @Test fun extraWordIsLowConfidenceInsertion() {
        val m = classify(toks("kalam", "bisat", "durov", "qcqcq", "fenik", "gorat", "hivel", "jonap", "lumes")).single()
        assertEquals(MistakeType.INSERTION, m.type); assertEquals(3, m.wordId)
        assertTrue(m.confidence <= 0.5)
    }

    @Test fun phonemeLevelGroupsIntoWords() {
        val seq = UnitSequence(ref, TokenUnit.PHONEME)
        val pc = MistakeClassifier(seq, Similarity::exact, 1.0)
        val refUnits = seq.units.subList(0, seq.lastUnitOf(4) + 1)
        // word 2 ("durov") deleted entirely; word 3 ("fenik") with one wrong phoneme
        val said = (keys[0] + keys[1] + "fexik" + keys[4]).map { Token(it.toString()) }
        val al = PhonemeAligner().global(said.map { it.text }, refUnits)
        val ms = pc.classify(al.ops, said)
        assertEquals(setOf(MistakeType.DELETION to 2, MistakeType.SUBSTITUTION to 3), ms.map { it.type to it.wordId }.toSet())
        assertTrue(ms.first { it.type == MistakeType.SUBSTITUTION }.confidence < ms.first { it.type == MistakeType.DELETION }.confidence)
    }
}
