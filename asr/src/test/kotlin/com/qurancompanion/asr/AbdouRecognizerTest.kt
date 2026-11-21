package com.qurancompanion.asr

import com.qurancompanion.engine.recognition.RecognitionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Streaming logic with a fake acoustic model. The test audio encodes one CTC token per 40 ms block
 * as a tone amplitude; the fake "model" reads it back from the features, so it reproduces the real
 * frame geometry (window offsets, 4x subsampling). Synthetic Latin-letter vocabulary, no Quran text.
 */
class AbdouRecognizerTest {
    private val vocab = Vocabulary.parse(sequenceOf("<blk> 0", "a 1", "b 2", "c 3", "d 4", "▁ 5"))
    private val levels = FloatArray(vocab.size) { if (it == 0) 0f else 0.01f * Math.pow(1.8, it.toDouble()).toFloat() }
    private val fbank = FbankExtractor()

    private fun block(id: Int): FloatArray = FloatArray(640) { (levels[id] * sin(2 * PI * 1000 * it / 16000)).toFloat() }
    private val refEnergy = DoubleArray(vocab.size) { id -> if (id == 0) -99.0 else meanLog(fbank.compute(block(id) + block(id) + block(id)), 6) }

    private fun meanLog(f: Features, frame: Int) = (0 until f.numBins).sumOf { f[frame, it].toDouble() } / f.numBins

    /** Fake model; [edgeNoiseFrames] output frames at the right edge are corrupted (letters -> 'd'). */
    inner class FakeModel(private val edgeNoiseFrames: Int = 0) : CtcAcousticModel {
        override val vocabSize = vocab.size
        override fun outputFrames(featureFrames: Int) = if (featureFrames < 7) 0 else ((featureFrames - 1) / 2 - 1) / 2
        override fun logProbs(features: Features): LogProbs {
            val n = outputFrames(features.numFrames)
            val d = FloatArray(n * vocabSize) { -10f }
            for (k in 0 until n) {
                val e = meanLog(features, minOf(4 * k + 2, features.numFrames - 1))
                var id = if (e < refEnergy[1] - 1.0) 0 else (1 until vocabSize).minByOrNull { abs(refEnergy[it] - e) }!!
                if (k >= n - edgeNoiseFrames && id in 1..3) id = 4
                d[k * vocabSize + id] = -0.01f
            }
            return LogProbs(n, vocabSize, d)
        }
    }

    /** Script: words of letters; each letter 3 blocks, word separator block, 4 blank blocks of pause. */
    private fun audio(words: List<String>, leadBlocks: Int = 10, tailBlocks: Int = 100): Pair<ShortArray, List<Pair<Long, Long>>> {
        val ids = ArrayList<Int>()
        val times = ArrayList<Pair<Long, Long>>()
        repeat(leadBlocks) { ids += 0 }
        for (w in words) {
            val s = ids.size
            for (ch in w) { val id = vocab.symbols.indexOf(ch.toString()); repeat(3) { ids += id }; ids += 0 }
            times += s * 40L to (ids.size - 1) * 40L
            ids += 5; repeat(4) { ids += 0 }
        }
        repeat(tailBlocks) { ids += 0 }
        val pcm = ShortArray(ids.size * 640)
        ids.forEachIndexed { b, id -> val blk = block(id); for (i in 0 until 640) pcm[b * 640 + i] = (blk[i] * 32767).toInt().toShort() }
        return pcm to times
    }

    private fun run(rec: AbdouRecognizer, pcm: ShortArray, chunk: Int = 1600, startMs: Long? = null): List<Pair<Long, RecognitionEvent>> {
        val out = ArrayList<Pair<Long, RecognitionEvent>>()
        var i = 0
        while (i < pcm.size) {
            val n = minOf(chunk, pcm.size - i)
            val audioEnd = (i + n) * 1000L / 16000
            rec.accept(pcm, i, n, startMs?.let { it + i * 1000L / 16000 }).forEach { out += audioEnd to it }
            i += n
        }
        return out
    }

    private val script = List(14) { listOf("ab", "cab", "a", "bca", "cc", "ba")[it % 6] }

    @Test fun stableWordsOnceInOrderWithTimes() {
        val (pcm, times) = audio(script)
        val rec = AbdouRecognizer(FakeModel(), vocab)
        val ev = run(rec, pcm)
        val stable = ev.filter { it.second.isStable }.flatMap { it.second.tokens }
        assertEquals(script, stable.map { it.text })
        stable.zip(times).forEach { (t, exp) ->
            assertTrue("start ${t.startMs} vs ${exp.first}", abs(t.startMs!! - exp.first) <= 40)
            assertTrue("end ${t.endMs} vs ${exp.second}", abs(t.endMs!! - exp.second) <= 80)
        }
        // latency: word end -> stable emission is within [stableAge, stableAge + 2 hops]
        for ((at, e) in ev.filter { it.second.isStable }) for (t in e.tokens) {
            val lag = at - t.endMs!!
            assertTrue("lag $lag", lag in 1500..3600)
        }
        assertTrue(ev.any { !it.second.isStable && it.second.tokens.isNotEmpty() })
        assertEquals(0, rec.stats.droppedWords)
    }

    @Test fun unstableWindowEdgeNeverCommits() {
        val (pcm, _) = audio(script)
        // the newest 1.2 s of every window is garbage: stable output must be unaffected
        val rec = AbdouRecognizer(FakeModel(edgeNoiseFrames = 30), vocab)
        val ev = run(rec, pcm)
        val stable = ev.filter { it.second.isStable }.flatMap { it.second.tokens }.map { it.text }
        assertEquals(script, stable)
        // ... while provisional tails do show the garbage
        assertTrue(ev.any { e -> !e.second.isStable && e.second.tokens.any { 'd' in it.text } })
    }

    @Test fun flushCommitsTailAndClearsProvisional() {
        val (pcm, _) = audio(script.take(4), tailBlocks = 5)
        val rec = AbdouRecognizer(FakeModel(), vocab)
        val ev = run(rec, pcm).map { it.second } + rec.flush()
        assertEquals(script.take(4), ev.filter { it.isStable }.flatMap { it.tokens }.map { it.text })
        assertTrue(ev.last { !it.isStable }.tokens.isEmpty())
        assertTrue(rec.flush().isEmpty())
    }

    @Test fun captureTimesAndGapReset() {
        val (pcm1, _) = audio(script.take(3), tailBlocks = 5)
        val (pcm2, _) = audio(script.drop(3).take(3), tailBlocks = 60)
        val rec = AbdouRecognizer(FakeModel(), vocab)
        val ev1 = run(rec, pcm1, startMs = 1_000_000).map { it.second }
        // 20 s gap (e.g. Qari playback gated the mic): the first utterance is flushed, times follow the clock
        val ev2 = run(rec, pcm2, startMs = 1_000_000L + pcm1.size / 16 + 20_000).map { it.second }
        val stable = (ev1 + ev2).filter { it.isStable }.flatMap { it.tokens }
        assertEquals(script.take(6), stable.map { it.text })
        assertTrue(stable.take(3).all { it.startMs!! in 1_000_000L..1_000_000L + pcm1.size / 16 })
        assertTrue(stable.drop(3).all { it.startMs!! >= 1_000_000L + pcm1.size / 16 + 20_000 })
        // events are ordered by clock
        val ts = (ev1 + ev2).map { it.timestampMs }
        assertEquals(ts.sorted(), ts)
    }
}
