package com.qurancompanion.engine.align

import com.qurancompanion.engine.recognition.Token
import com.qurancompanion.engine.recognition.TokenUnit
import com.qurancompanion.engine.tracking.UnitSequence

enum class MistakeType { SUBSTITUTION, DELETION, INSERTION, REPETITION, ORDER }

/**
 * A possible mistake. [wordId] is the expected reference word (for an insertion: the word it was
 * inserted before; for ORDER: the first of the swapped words, the other in [relatedWordIds]).
 * REPETITION is informational: re-reciting correct words is never an error.
 */
data class CandidateMistake(
    val type: MistakeType,
    val wordId: Int,
    val recognized: String?,
    val confidence: Double,
    val timeMs: Long? = null,
    val relatedWordIds: Set<Int> = setOf(wordId),
    /** A breath-length gap was observed right before this word (recognition near breaths is unreliable). */
    val nearPause: Boolean = false,
) {
    val isError: Boolean get() = type != MistakeType.REPETITION
}

data class ClassifierConfig(
    val deletionBase: Double = 0.9,
    val orderBase: Double = 0.85,
    /** Insertions are indistinguishable from false starts at word level, so they stay below alert thresholds by default. */
    val insertionBase: Double = 0.5,
    /** Substitution confidence = 1 - substitutionSimilarityWeight * similarity (near-misses are less certain). */
    val substitutionSimilarityWeight: Double = 0.6,
    /** Phoneme-mode: boundary insertions shorter than this are ignored as noise. */
    val minInsertedPhonemes: Int = 3,
    /** A gap of this fraction of a typical word duration where a word is "missing" means the recognizer probably dropped it. */
    val missedWordGapFraction: Double = 0.6,
    val missedWordFactor: Double = 0.3,
    val defaultWordDurationMs: Long = 400,
    val breathGapMs: Long = 800,
)

/**
 * Turns alignment ops of an in-sequence advance into word-level candidate mistakes with a
 * confidence. Confidence = type base x context factor (matched neighbours) x token confidence
 * (x timing factor for deletions).
 */
class MistakeClassifier(
    private val sequence: UnitSequence,
    private val similarity: (String, String) -> Double,
    private val matchThreshold: Double,
    private val config: ClassifierConfig = ClassifierConfig(),
) {
    private data class WordOp(
        val type: OpType, val wordId: Int, val tokens: List<Token>, val similarity: Double, val queries: List<Int>,
    ) {
        val text: String? get() = if (tokens.isEmpty()) null else tokens.joinToString(" ") { it.text }
    }

    /**
     * [previousTokens]: the last tokens committed before [tokens] (timing context across updates).
     */
    fun classify(ops: List<AlignOp>, tokens: List<Token>, contextBefore: Int = 2, previousTokens: List<Token> = emptyList()): List<CandidateMistake> {
        val w = wordOps(ops, tokens)
        val timeline = previousTokens + tokens
        val out = ArrayList<CandidateMistake>()
        val used = BooleanArray(w.size)
        val typicalDuration = typicalDuration(tokens)
        for (i in w.indices) {
            if (used[i]) continue
            val op = w[i]
            if (op.type == OpType.MATCH) continue
            val ctx = 0.6 + 0.1 * (contextBefore(w, i, contextBefore) + matchesAround(w, i + 1, i + 2))
            val tokConf = op.tokens.map { (it.confidence ?: 1f).toDouble() }.minOrNull() ?: 1.0
            val time = op.tokens.firstOrNull()?.startMs
            val nearPause = op.queries.firstOrNull()?.let { q ->
                val prev = timeline.getOrNull(q + previousTokens.size - 1); val t = tokens[q]
                if (prev?.endMs != null && t.startMs != null) t.startMs - prev.endMs >= config.breathGapMs else false
            } ?: false
            when (op.type) {
                OpType.INSERTION -> {
                    // Saying a neighbouring correct word twice (or a false start of the next word) is a repetition.
                    val repeated = ((i - 1 downTo maxOf(0, i - 2)) + listOf(i + 1)).firstOrNull {
                        w.getOrNull(it)?.type == OpType.MATCH && resembles(op.tokens, w[it].wordId)
                    }
                    val order = (maxOf(0, i - 2)..minOf(w.lastIndex, i + 2)).firstOrNull {
                        !used[it] && w[it].type == OpType.DELETION && resembles(op.tokens, w[it].wordId)
                    }
                    when {
                        repeated != null ->
                            out += CandidateMistake(MistakeType.REPETITION, w[repeated].wordId, op.text, 0.0, time)
                        order != null -> {
                            used[order] = true
                            val related = swapRange(w, insertion = i, deletion = order)
                            out += CandidateMistake(MistakeType.ORDER, related.first(), op.text, config.orderBase * ctx * tokConf, time, related, nearPause)
                        }
                        else -> out += CandidateMistake(MistakeType.INSERTION, op.wordId, op.text, config.insertionBase * ctx * tokConf, time, nearPause = nearPause)
                    }
                }
                OpType.DELETION -> {
                    val order = (maxOf(0, i - 2)..minOf(w.lastIndex, i + 2)).firstOrNull {
                        !used[it] && w[it].type == OpType.INSERTION && resembles(w[it].tokens, op.wordId)
                    }
                    if (order != null) {
                        used[order] = true
                        val related = swapRange(w, insertion = order, deletion = i)
                        out += CandidateMistake(MistakeType.ORDER, related.first(), w[order].text, config.orderBase * ctx, w[order].tokens.firstOrNull()?.startMs, related)
                    } else {
                        out += deletion(w, i, ctx, typicalDuration, timeline, previousTokens.size)
                    }
                }
                OpType.SUBSTITUTION -> {
                    val next = w.getOrNull(i + 1)
                    if (next != null && next.type == OpType.SUBSTITUTION && resembles(op.tokens, next.wordId) && resembles(next.tokens, op.wordId)) {
                        used[i + 1] = true
                        out += CandidateMistake(MistakeType.ORDER, op.wordId, "${op.text} ${next.text}", config.orderBase * ctx * tokConf, time, setOf(op.wordId, next.wordId), nearPause)
                    } else if (adjacentMatchResembles(w, i)) {
                        // The "wrong" token is really a neighbouring correct word said twice: the expected word is missing
                        // (often a recognizer drop plus a stutter), so judge it as a deletion.
                        out += deletion(w, i, ctx, typicalDuration, timeline, previousTokens.size)
                    } else {
                        val base = 1.0 - config.substitutionSimilarityWeight * op.similarity
                        out += CandidateMistake(MistakeType.SUBSTITUTION, op.wordId, op.text, base * ctx * tokConf, time, nearPause = nearPause)
                    }
                }
                OpType.MATCH -> {}
            }
        }
        return out
    }

    /** The token of a substitution is really the previous or next reference word (a repeat or a jump ahead). */
    private fun adjacentMatchResembles(w: List<WordOp>, i: Int): Boolean =
        listOf(w[i].wordId - 1, w[i].wordId + 1).any { it in 0 until sequence.reference.wordCount && resembles(w[i].tokens, it) }

    /** Deletion of w[i].wordId; a word-sized silent gap where the word should be means the recognizer probably missed it. */
    private fun deletion(w: List<WordOp>, i: Int, ctx: Double, typicalDuration: Double, timeline: List<Token>, offset: Int): CandidateMistake {
        val word = w[i].wordId
        // Timeline index of the first token after the missing word.
        val k = (i + 1..w.lastIndex).firstNotNullOfOrNull { w[it].queries.firstOrNull() }?.let { it + offset } ?: timeline.size
        fun gapAt(x: Int): Long? {
            val b = timeline.getOrNull(x - 1); val a = timeline.getOrNull(x)
            return if (b?.endMs != null && a?.startMs != null) a.startMs - b.endMs else null
        }
        // With an identical word next to the missing one, the silent hole may be on the far side of it.
        // If the "missing" word has a token of its own (a substitution that is really a repeated neighbour), the hole can be
        // just before that token too.
        val own = w[i].queries.firstOrNull()?.let { it + offset }
        val holes = listOfNotNull(
            k, own, (k - 1).takeIf { identical(word - 1, word) }, (k + 1).takeIf { identical(word + 1, word) },
            own?.minus(1)?.takeIf { identical(word - 1, word) },
        )
        val gap = holes.mapNotNull { gapAt(it) }.maxOrNull()
        val timing = if (gap != null && gap >= config.missedWordGapFraction * typicalDuration) config.missedWordFactor else 1.0
        val nConf = listOfNotNull(timeline.getOrNull(k - 1), timeline.getOrNull(k)).map { (it.confidence ?: 1f).toDouble() }.minOrNull() ?: 1.0
        return CandidateMistake(MistakeType.DELETION, word, null, config.deletionBase * ctx * timing * nConf, timeline.getOrNull(k)?.startMs,
            nearPause = gap != null && gap >= config.breathGapMs)
    }

    private fun identical(a: Int, b: Int): Boolean =
        a in 0 until sequence.reference.wordCount && sequence.unitsOf(a) == sequence.unitsOf(b)

    /** Words involved when a word was deleted at one place and inserted at another. */
    private fun swapRange(w: List<WordOp>, insertion: Int, deletion: Int): Set<Int> {
        val del = w[deletion].wordId
        val ins = w[insertion].wordId // word the insertion sits before
        return if (deletion < insertion) (del until maxOf(del + 1, ins)).toSet() else (ins..del).toSet()
    }

    private fun contextBefore(w: List<WordOp>, i: Int, outside: Int): Int {
        var c = 0
        for (k in 1..2) {
            val idx = i - k
            if (idx < 0) return minOf(2, c + outside.coerceIn(0, 2 - c))
            if (w[idx].type == OpType.MATCH) c++ else break
        }
        return c
    }

    private fun matchesAround(w: List<WordOp>, from: Int, to: Int): Int {
        var c = 0
        for (k in from..to) if (w.getOrNull(k)?.type == OpType.MATCH) c++ else break
        return c
    }

    private fun resembles(tokens: List<Token>, wordId: Int): Boolean {
        if (tokens.isEmpty()) return false
        val ref = sequence.unitsOf(wordId)
        return if (sequence.unit == TokenUnit.WORD) {
            tokens.size == 1 && similarity(tokens[0].text, ref[0]) >= matchThreshold
        } else Similarity.sequence(tokens.map { it.text }, ref) >= 0.75
    }

    private fun typicalDuration(tokens: List<Token>): Double {
        val d = tokens.mapNotNull { t -> if (t.startMs != null && t.endMs != null) (t.endMs - t.startMs).toDouble() else null }.sorted()
        val perUnit = if (d.isEmpty()) return config.defaultWordDurationMs.toDouble() else d[d.size / 2]
        return if (sequence.unit == TokenUnit.WORD) perUnit else perUnit * 4
    }

    /** Groups unit ops into word ops (identity for WORD units). */
    private fun wordOps(ops: List<AlignOp>, tokens: List<Token>): List<WordOp> {
        val maxWord = sequence.reference.wordCount - 1
        fun wordOfRef(refIndex: Int) = if (refIndex >= sequence.size) maxWord else sequence.wordOf(refIndex)
        // Word key per op: insertions inside a word belong to it; boundary insertions get key -1.
        val keys = IntArray(ops.size) { k ->
            val op = ops[k]
            if (op.type != OpType.INSERTION) return@IntArray sequence.wordOf(op.refIndex)
            val prev = (k - 1 downTo 0).firstOrNull { ops[it].type != OpType.INSERTION }?.let { sequence.wordOf(ops[it].refIndex) }
            val next = (k + 1 until ops.size).firstOrNull { ops[it].type != OpType.INSERTION }?.let { sequence.wordOf(ops[it].refIndex) }
            if (sequence.unit == TokenUnit.PHONEME && prev != null && prev == next) prev else -1
        }
        val out = ArrayList<WordOp>()
        var k = 0
        while (k < ops.size) {
            val key = keys[k]
            var e = k
            if (key == -1 && sequence.unit == TokenUnit.WORD) e = k // one inserted word per token
            else while (e + 1 < ops.size && keys[e + 1] == key) e++
            val group = ops.subList(k, e + 1)
            val qs = group.filter { it.queryIndex >= 0 }.map { it.queryIndex }
            val toks = qs.map { tokens[it] }
            if (key == -1) {
                if (sequence.unit == TokenUnit.WORD || group.size >= config.minInsertedPhonemes) {
                    out += WordOp(OpType.INSERTION, wordOfRef(group.first().refIndex), toks, 0.0, qs)
                }
            } else {
                val refOps = group.filter { it.type != OpType.INSERTION }
                val inserted = group.size - refOps.size
                val type = when {
                    refOps.all { it.type == OpType.MATCH } && inserted == 0 -> OpType.MATCH
                    refOps.all { it.type == OpType.DELETION } && inserted == 0 -> OpType.DELETION
                    sequence.unit == TokenUnit.WORD -> group.single().type
                    else -> OpType.SUBSTITUTION
                }
                val sim = if (sequence.unit == TokenUnit.WORD) group.single().similarity
                else refOps.count { it.type == OpType.MATCH }.toDouble() / (refOps.size + inserted)
                out += WordOp(type, key, toks, sim, qs)
            }
            k = e + 1
        }
        return out
    }
}
