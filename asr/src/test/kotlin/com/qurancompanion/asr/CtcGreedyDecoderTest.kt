package com.qurancompanion.asr

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.ln

class CtcGreedyDecoderTest {
    // Synthetic vocabulary (Latin letters); real tokens come from the model's tokens.txt.
    private val vocab = Vocabulary.parse(sequenceOf("<blk> 0", "a 1", "b 2", "c 3", "▁ 4"))
    private val dec = CtcGreedyDecoder(vocab)

    private fun lp(path: List<Int>, p: Float = 0.9f): LogProbs {
        val v = vocab.size
        val d = FloatArray(path.size * v) { ln((1 - p) / (v - 1)) }
        path.forEachIndexed { t, id -> d[t * v + id] = ln(p) }
        return LogProbs(path.size, v, d)
    }

    @Test fun parsesTokens() {
        assertEquals(0, vocab.blankId); assertEquals(4, vocab.wordSeparatorId); assertEquals(listOf(1, 2, 3), vocab.letterIds)
    }

    @Test fun collapsesRepeatsAndSplitsWords() {
        // frames: _ a a _ a b 4 4 c c _ 4 b
        val w = dec.decode(lp(listOf(0, 1, 1, 0, 1, 2, 4, 4, 3, 3, 0, 4, 2)))
        assertEquals(listOf("aab", "c", "b"), w.map { it.text })
        assertEquals(DecodedWord("aab", 1, 6, 0.9f).copy(confidence = w[0].confidence), w[0])
        assertEquals(8 to 10, w[1].startFrame to w[1].endFrame)
        assertEquals(12 to 13, w[2].startFrame to w[2].endFrame)
        assertEquals(0.9f, w[0].confidence, 1e-4f)
    }

    @Test fun confidenceIsWeakestLetterPeak() {
        val v = vocab.size
        val l = lp(listOf(1, 2, 2))
        // make the "b" run weak: peak 0.5
        for (t in 1..2) { for (k in 0 until v) l.data[t * v + k] = ln(0.5f / (v - 1)); l.data[t * v + 2] = ln(0.5f + 0.01f * t) }
        val w = dec.decode(l)
        assertEquals("ab", w.single().text)
        assertEquals(0.52f, w.single().confidence, 1e-4f)
    }

    @Test fun emptyInput() { assertEquals(emptyList<DecodedWord>(), dec.decode(LogProbs(0, vocab.size, FloatArray(0)))) }
}
