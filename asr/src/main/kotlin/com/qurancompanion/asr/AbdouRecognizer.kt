package com.qurancompanion.asr

import com.qurancompanion.engine.recognition.RecognitionEvent
import com.qurancompanion.engine.recognition.Token
import com.qurancompanion.engine.recognition.TokenUnit
import kotlin.math.abs

/** Sliding-window parameters (all in ms of audio). See docs/ASR.md for the measured trade-offs. */
data class AbdouConfig(
    /** Decoded window length (the model is bidirectional; ~6-8 s gives it context on both sides). */
    val windowMs: Long = 6000,
    /** Re-decode every [hopMs] of new audio. */
    val hopMs: Long = 1000,
    /** A word becomes eligible for stable only when it ends at least this long before the window end. */
    val stableAgeMs: Long = 1500,
    /** Words starting within this distance of a window's left edge (not the stream start) are ignored. */
    val leftGuardMs: Long = 500,
    /** Two windows agree on a word when the text is equal and start/end differ by at most this. */
    val agreeToleranceMs: Long = 160,
    /** No decode before this much audio has arrived. */
    val minDecodeMs: Long = 1000,
    /** A jump of more than this between the expected and the given capture time is a discontinuity. */
    val gapResetMs: Long = 1000,
) {
    init {
        require(hopMs in 40..windowMs && stableAgeMs + leftGuardMs + hopMs <= windowMs) { "inconsistent window params" }
    }
}

/** Cumulative cost counters (wall time on the calling thread). */
data class RecognizerStats(
    var audioMs: Long = 0,
    var windows: Int = 0,
    var decodedAudioMs: Long = 0,
    var featureNs: Long = 0,
    var modelNs: Long = 0,
    var decodeNs: Long = 0,
    var stableWords: Int = 0,
    var droppedWords: Int = 0,
) {
    val computeMs: Double get() = (featureNs + modelNs + decodeNs) / 1e6
    /** CPU (calling-thread wall) ms per second of input audio. */
    val msPerAudioSecond: Double get() = if (audioMs == 0L) 0.0 else computeMs * 1000.0 / audioMs
}

/**
 * Quasi-streaming recognizer for Abdou/quran-asr-small. Feed 16 kHz mono PCM16 with [accept];
 * every [AbdouConfig.hopMs] of new audio the last [AbdouConfig.windowMs] are decoded offline and
 * engine [RecognitionEvent]s ([TokenUnit.WORD], text in norm_skeleton space) are returned:
 *
 * - **stable** (appended, each word once, in order): a word that (1) ends >= stableAgeMs before the
 *   window end, (2) starts >= leftGuardMs after the window start (unless that is the stream start),
 *   and (3) appears with the same text and times (±agreeToleranceMs) in the previous window.
 *   De-duplicated by time: a word is new only if its midpoint is after the end of the last
 *   committed word. A word that never agrees before it leaves the reliable zone is dropped (counted
 *   in [stats]); later words wait for it, so order is kept.
 * - **provisional** (replaces the previous one): all newer words of the current window.
 *
 * [flush] ends the utterance (stop/pause): the final window's right edge is the real end of the
 * audio, so its remaining words are committed without waiting for agreement.
 *
 * Times: token times are `captureTimeMs` of the chunk that holds the sample (piecewise, so gated /
 * paused audio keeps the session clock) or audio ms since the first sample when no time is given.
 * Event `timestampMs` is the clock time of the window end. Not thread-safe; call from one thread.
 */
class AbdouRecognizer(
    private val model: CtcAcousticModel,
    vocab: Vocabulary,
    val config: AbdouConfig = AbdouConfig(),
    private val normalizer: SkeletonNormalizer = SkeletonNormalizer(),
    private val fbank: FbankExtractor = FbankExtractor(),
) {
    private val decoder = CtcGreedyDecoder(vocab)
    private val sr = FbankExtractor.SAMPLE_RATE
    private val windowSamples = (config.windowMs * sr / 1000).toInt()
    private val hopSamples = (config.hopMs * sr / 1000).toInt()
    private val minSamples = (config.minDecodeMs * sr / 1000).toInt()

    // Ring buffer of the latest windowSamples (+ one hop) samples; absolute sample indices.
    private val ring = FloatArray(windowSamples + hopSamples)
    private var total = 0L               // samples received since the last reset (stream sample index)
    private var lastDecodeEnd = 0L
    // (stream sample index, clock ms) anchors for sample -> clock conversion
    private val anchorIdx = ArrayList<Long>()
    private val anchorMs = ArrayList<Long>()

    private var prevWindow: List<TimedWord> = emptyList()
    /** End (stream sample) of the last committed or dropped word. */
    private var frontier = Long.MIN_VALUE
    private var lastProvisional: List<String>? = null

    val stats = RecognizerStats()

    /** A decoded word in stream sample coordinates. */
    private data class TimedWord(val text: String, val start: Long, val end: Long, val confidence: Float) {
        val mid get() = (start + end) / 2
    }

    /**
     * Feeds samples; returns the events produced (possibly none). [captureTimeMs]: session-clock time
     * of `pcm[offset]` (e.g. elapsedRealtime at capture); a jump > gapResetMs from the expected time
     * flushes the current utterance first (audio was gated, e.g. during Qari playback).
     */
    fun accept(pcm: ShortArray, offset: Int = 0, length: Int = pcm.size - offset, captureTimeMs: Long? = null): List<RecognitionEvent> {
        val events = ArrayList<RecognitionEvent>()
        if (captureTimeMs != null) {
            if (total > 0) {
                val expected = clockMs(total)
                if (abs(captureTimeMs - expected) > config.gapResetMs) { events += flush(); clearAudio() }
            }
            if (anchorIdx.isEmpty() || clockMs(total) != captureTimeMs) { anchorIdx += total; anchorMs += captureTimeMs }
        } else if (anchorIdx.isEmpty()) { anchorIdx += 0L; anchorMs += 0L }
        var i = 0
        while (i < length) {
            // feed up to the next decode boundary so windows end exactly on hop multiples
            val untilHop = (lastDecodeEnd + hopSamples - total).coerceAtLeast(1L)
            val n = minOf((length - i).toLong(), untilHop).toInt()
            for (k in 0 until n) ring[((total + k) % ring.size).toInt()] = pcm[offset + i + k] / 32768f
            total += n; i += n
            stats.audioMs = stats.audioMs + n * 1000L / sr
            if (total - lastDecodeEnd >= hopSamples && total >= minSamples) events += decodeWindow(final = false)
        }
        return events
    }

    /** Ends the utterance: decodes the tail and commits every remaining word. Keeps the clock mapping. */
    fun flush(): List<RecognitionEvent> {
        if (flushedAt == total) return clearProvisional()
        flushedAt = total
        if (total - segmentStart < ms(MIN_FLUSH_MS)) return clearProvisional()
        val ev = decodeWindow(final = true)
        prevWindow = emptyList()
        return ev
    }

    /** Forgets all audio and state (new session). */
    fun reset() {
        total = 0; lastDecodeEnd = 0; segmentStart = 0; flushedAt = -1
        prevWindow = emptyList()
        anchorIdx.clear(); anchorMs.clear()
        frontier = Long.MIN_VALUE
        lastProvisional = null
    }

    private fun clearAudio() {
        // A new stream segment starts at the current index; keep indices monotonic so the frontier stays valid.
        lastDecodeEnd = total
        segmentStart = total
        prevWindow = emptyList()
    }

    /** First sample of the current contiguous segment (stream start for the left-guard rule). */
    private var segmentStart = 0L
    private var flushedAt = -1L

    private companion object { const val MIN_FLUSH_MS = 300L }

    private fun clearProvisional(): List<RecognitionEvent> {
        if (lastProvisional.isNullOrEmpty()) return emptyList()
        lastProvisional = emptyList()
        return listOf(RecognitionEvent(emptyList(), false, clockMs(total), TokenUnit.WORD))
    }

    private fun decodeWindow(final: Boolean): List<RecognitionEvent> {
        val end = total
        val start = maxOf(segmentStart, end - windowSamples)
        val len = (end - start).toInt()
        lastDecodeEnd = end
        if (len < minSamples && !final) return emptyList()
        val wave = FloatArray(len) { ring[((start + it) % ring.size).toInt()] }
        val t0 = System.nanoTime()
        val feats = fbank.compute(wave)
        val t1 = System.nanoTime()
        val lp = model.logProbs(feats)
        val t2 = System.nanoTime()
        val frameSamples = CtcGreedyDecoder.FRAME_MS * sr / 1000
        val words = normalizer.normalize(decoder.decode(lp)).map {
            TimedWord(it.text, start + it.startFrame * frameSamples, minOf(end, start + it.endFrame * frameSamples), it.confidence)
        }
        val t3 = System.nanoTime()
        stats.windows++; stats.decodedAudioMs += len * 1000L / sr
        stats.featureNs += t1 - t0; stats.modelNs += t2 - t1; stats.decodeNs += t3 - t2

        val atStreamStart = start == segmentStart
        val leftLimit = if (atStreamStart) Long.MIN_VALUE else start + ms(config.leftGuardMs)
        val nextLeftLimit = start + hopSamples + ms(config.leftGuardMs)
        val eligibleEnd = end - ms(config.stableAgeMs)
        val tol = ms(config.agreeToleranceMs)
        val committed = ArrayList<TimedWord>()
        var blocked = false
        val tail = ArrayList<TimedWord>()
        for (w in words) {
            if (w.mid <= frontier) continue
            if (blocked) { tail += w; continue }
            if (w.start < leftLimit) { stats.droppedWords++; frontier = w.end; continue }
            if (final) { committed += w; frontier = w.end; continue }
            if (w.end > eligibleEnd) { blocked = true; tail += w; continue }
            val agreed = prevWindow.any { p -> p.text == w.text && abs(p.start - w.start) <= tol && abs(p.end - w.end) <= tol }
            when {
                agreed -> { committed += w; frontier = w.end }
                w.start < nextLeftLimit && end - start >= windowSamples -> { stats.droppedWords++; frontier = w.end }
                else -> { blocked = true; tail += w }
            }
        }
        prevWindow = words
        val events = ArrayList<RecognitionEvent>(2)
        val ts = clockMs(end)
        if (committed.isNotEmpty()) {
            stats.stableWords += committed.size
            events += RecognitionEvent(committed.map { it.toToken() }, true, ts, TokenUnit.WORD)
        }
        val texts = tail.map { it.text }
        if (texts != lastProvisional && !(texts.isEmpty() && lastProvisional == null)) {
            lastProvisional = texts
            events += RecognitionEvent(tail.map { it.toToken() }, false, ts, TokenUnit.WORD)
        }
        return events
    }

    private fun TimedWord.toToken() = Token(text, clockMs(start), clockMs(end), confidence)

    private fun ms(v: Long): Long = v * sr / 1000

    /** Session-clock ms of stream sample [idx]. */
    fun clockMs(idx: Long): Long {
        if (anchorIdx.isEmpty()) return idx * 1000 / sr
        var k = anchorIdx.size - 1
        while (k > 0 && anchorIdx[k] > idx) k--
        // prune anchors that can no longer be referenced (older than the ring)
        val oldest = total - ring.size
        while (anchorIdx.size > 1 && anchorIdx[1] <= oldest && k > 0) { anchorIdx.removeAt(0); anchorMs.removeAt(0); k-- }
        return anchorMs[k] + (idx - anchorIdx[k]) * 1000 / sr
    }
}
