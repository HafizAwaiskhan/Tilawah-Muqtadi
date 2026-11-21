package com.qurancompanion.asr

import java.io.File
import java.io.InputStream
import kotlin.math.exp

/**
 * Model vocabulary, loaded at runtime from the model's `tokens.txt` ("symbol id" per line).
 * Nothing about the alphabet is hardcoded except the two special symbols.
 */
class Vocabulary(val symbols: List<String>) {
    val blankId: Int = symbols.indexOf(BLANK).also { require(it >= 0) { "tokens: no $BLANK" } }
    val wordSeparatorId: Int = symbols.indexOf(WORD_SEPARATOR).also { require(it >= 0) { "tokens: no word separator" } }
    val size: Int get() = symbols.size

    /** Ids of letter symbols (everything except blank and the word separator). */
    val letterIds: List<Int> = symbols.indices.filter { it != blankId && it != wordSeparatorId }

    operator fun get(id: Int): String = symbols[id]

    companion object {
        const val BLANK = "<blk>"
        const val WORD_SEPARATOR = "▁"

        fun parse(lines: Sequence<String>): Vocabulary {
            val map = HashMap<Int, String>()
            for (raw in lines) {
                val line = raw.trimEnd('\n', '\r')
                if (line.isBlank()) continue
                val sp = line.lastIndexOf(' ')
                require(sp > 0) { "bad tokens line: $line" }
                map[line.substring(sp + 1).trim().toInt()] = line.substring(0, sp)
            }
            require(map.keys == (0 until map.size).toSet()) { "token ids must be 0..n-1" }
            return Vocabulary(List(map.size) { map.getValue(it) })
        }

        fun load(stream: InputStream): Vocabulary = stream.bufferedReader(Charsets.UTF_8).useLines { parse(it) }
        fun load(file: File): Vocabulary = file.inputStream().use { load(it) }
    }
}

/** CTC log-posteriors `[numFrames, vocabSize]`, row-major (log-softmax, as the model outputs). */
class LogProbs(val numFrames: Int, val vocabSize: Int, val data: FloatArray) {
    init { require(data.size >= numFrames * vocabSize) }
    operator fun get(frame: Int, id: Int): Float = data[frame * vocabSize + id]
}

/**
 * One decoded word. Frames are output frames (40 ms each) relative to the decoded window;
 * [endFrame] is exclusive (last letter frame + 1), matching `asr_core.greedy`.
 * [confidence] = min over the word's letters of the letter's peak posterior.
 */
data class DecodedWord(val text: String, val startFrame: Int, val endFrame: Int, val confidence: Float)

/**
 * Greedy CTC: argmax per frame, collapse repeats, drop blank; the word-separator symbol splits words.
 * Bit-for-bit the same word segmentation and times as `asr_core.QuranAsr.greedy`.
 */
class CtcGreedyDecoder(private val vocab: Vocabulary) {

    fun decode(lp: LogProbs): List<DecodedWord> {
        require(lp.vocabSize == vocab.size) { "model vocab ${lp.vocabSize} != tokens ${vocab.size}" }
        val blank = vocab.blankId
        val sep = vocab.wordSeparatorId
        val words = ArrayList<DecodedWord>()
        val cur = StringBuilder()
        var curStart = -1
        var curEnd = -1
        var curMinConf = 1f
        var letterPeak = 0f   // peak posterior of the letter currently being emitted (its run of frames)
        var prev = blank
        fun closeLetter() { if (letterPeak > 0f) { curMinConf = minOf(curMinConf, letterPeak); letterPeak = 0f } }
        fun closeWord() {
            closeLetter()
            if (cur.isNotEmpty()) words += DecodedWord(cur.toString(), curStart, curEnd, curMinConf)
            cur.setLength(0); curStart = -1; curEnd = -1; curMinConf = 1f
        }
        for (t in 0 until lp.numFrames) {
            val row = t * lp.vocabSize
            var best = 0
            var bestV = lp.data[row]
            for (v in 1 until lp.vocabSize) {
                val x = lp.data[row + v]
                if (x > bestV) { bestV = x; best = v }
            }
            val p = exp(bestV.toDouble()).toFloat()
            if (best != prev && best != blank) {
                if (best == sep) {
                    closeWord()
                } else {
                    closeLetter()
                    if (curStart < 0) curStart = t
                    cur.append(vocab[best])
                }
            }
            if (best != blank && best != sep && cur.isNotEmpty()) {
                curEnd = t + 1
                if (best == prev || letterPeak == 0f) letterPeak = maxOf(letterPeak, p)
            }
            prev = best
        }
        closeWord()
        return words
    }

    companion object {
        /** Output frame duration: 4x subsampling of 10 ms feature frames. */
        const val FRAME_MS = 40L
    }
}
