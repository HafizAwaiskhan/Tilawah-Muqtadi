package com.qurancompanion.engine.align

import com.qurancompanion.engine.recognition.TokenUnit

enum class OpType { MATCH, SUBSTITUTION, INSERTION, DELETION }

/**
 * One alignment step. [queryIndex] is -1 for a deletion. For an insertion, [refIndex] is the index
 * of the next reference element (the inserted token sits before it).
 */
data class AlignOp(val type: OpType, val queryIndex: Int, val refIndex: Int, val similarity: Double)

data class Alignment(val ops: List<AlignOp>, val score: Double) {
    val matches: Int get() = ops.count { it.type == OpType.MATCH }
    val errors: Int get() = ops.size - matches
    /** First / last reference index covered by a non-insertion op, or -1. */
    val refStart: Int get() = ops.firstOrNull { it.type != OpType.INSERTION }?.refIndex ?: -1
    val refEnd: Int get() = ops.lastOrNull { it.type != OpType.INSERTION }?.refIndex ?: -1
    val queryEnd: Int get() = ops.lastOrNull { it.queryIndex >= 0 }?.queryIndex ?: -1

    fun shifted(queryOffset: Int, refOffset: Int) = Alignment(
        ops.map { it.copy(queryIndex = if (it.queryIndex < 0) -1 else it.queryIndex + queryOffset, refIndex = it.refIndex + refOffset) },
        score,
    )

    /** Ops up to and including the last MATCH (trailing unmatched tokens are left for later). */
    fun trimmedToLastMatch(): Alignment {
        val last = ops.indexOfLast { it.type == OpType.MATCH }
        return Alignment(if (last < 0) emptyList() else ops.subList(0, last + 1).toList(), score)
    }
}

/** Result of a local alignment: best alignment plus the best score of a clearly different end position. */
data class LocalAlignment(val best: Alignment?, val bestEnd: Int, val secondScore: Double) {
    val margin: Double get() = (best?.score ?: 0.0) - secondScore
}

/**
 * Weighted edit-distance aligner. Pairs whose similarity is >= [matchThreshold] are matches and
 * score +similarity; other pairs are substitutions costing (1 - similarity); insertions and
 * deletions cost [gapPenalty]. So a near-miss substitution is cheaper than a gap pair.
 */
class Aligner(
    val similarity: (String, String) -> Double,
    val matchThreshold: Double,
    val gapPenalty: Double = 1.0,
) {
    fun isMatch(a: String, b: String) = similarity(a, b) >= matchThreshold

    private fun pairScore(sim: Double) = if (sim >= matchThreshold) sim else -(1.0 - sim)

    /** Needleman-Wunsch: both sequences fully consumed. */
    fun global(query: List<String>, ref: List<String>): Alignment = run(query, ref, Mode.GLOBAL, 0).best!!

    /** Query fully consumed, reference anchored at its start, free end on the reference. */
    fun prefix(query: List<String>, ref: List<String>): Alignment = run(query, ref, Mode.PREFIX, 0).best!!

    /**
     * Smith-Waterman local alignment whose end must consume at least [minQueryEnd] query tokens
     * (so the alignment explains the most recent tokens). The second score is the best alignment
     * ending more than [separation] reference elements away from the best one.
     */
    fun local(query: List<String>, ref: List<String>, minQueryEnd: Int = query.size, separation: Int = query.size): LocalAlignment =
        run(query, ref, Mode.LOCAL, minQueryEnd.coerceIn(0, query.size), separation)

    private enum class Mode { GLOBAL, PREFIX, LOCAL }

    private fun run(query: List<String>, ref: List<String>, mode: Mode, minQueryEnd: Int, separation: Int = 0): LocalAlignment {
        val n = query.size
        val m = ref.size
        val w = m + 1
        val h = DoubleArray((n + 1) * w)
        val trace = ByteArray((n + 1) * w) // 0 stop, 1 diag, 2 up (insertion), 3 left (deletion)
        val sims = DoubleArray(maxOf(1, n * m))
        val local = mode == Mode.LOCAL
        for (j in 1..m) { if (!local) { h[j] = -j * gapPenalty; trace[j] = 3 } }
        for (i in 1..n) { if (!local) { h[i * w] = -i * gapPenalty; trace[i * w] = 2 } }
        for (i in 1..n) {
            for (j in 1..m) {
                val sim = similarity(query[i - 1], ref[j - 1])
                sims[(i - 1) * m + (j - 1)] = sim
                val diag = h[(i - 1) * w + j - 1] + pairScore(sim)
                val up = h[(i - 1) * w + j] - gapPenalty
                val left = h[i * w + j - 1] - gapPenalty
                var best = diag; var t: Byte = 1
                if (up > best) { best = up; t = 2 }
                if (left > best) { best = left; t = 3 }
                if (local && best <= 0.0) { best = 0.0; t = 0 }
                h[i * w + j] = best; trace[i * w + j] = t
            }
        }
        var endI = n; var endJ = m
        var second = 0.0
        when (mode) {
            Mode.GLOBAL -> {}
            Mode.PREFIX -> { endJ = (0..m).maxByOrNull { h[n * w + it] } ?: 0 }
            Mode.LOCAL -> {
                val colBest = DoubleArray(m + 1)
                val colRow = IntArray(m + 1)
                // Only cells ending in a match count: an alignment ending in a gap is not "explaining" the end.
                for (j in 1..m) for (i in maxOf(minQueryEnd, 1)..n) {
                    val k = i * w + j
                    if (trace[k].toInt() == 1 && sims[(i - 1) * m + (j - 1)] >= matchThreshold && h[k] > colBest[j]) {
                        colBest[j] = h[k]; colRow[j] = i
                    }
                }
                endJ = (0..m).maxByOrNull { colBest[it] } ?: 0
                endI = colRow[endJ]
                if (colBest[endJ] <= 0.0) return LocalAlignment(null, -1, 0.0)
                for (j in 0..m) if (kotlin.math.abs(j - endJ) > separation) second = maxOf(second, colBest[j])
            }
        }
        val ops = ArrayList<AlignOp>()
        var i = endI; var j = endJ
        while (i > 0 || j > 0) {
            val t = trace[i * w + j]
            if (t.toInt() == 0) break
            when (t.toInt()) {
                1 -> {
                    val sim = sims[(i - 1) * m + (j - 1)]
                    ops += AlignOp(if (sim >= matchThreshold) OpType.MATCH else OpType.SUBSTITUTION, i - 1, j - 1, sim)
                    i--; j--
                }
                2 -> { ops += AlignOp(OpType.INSERTION, i - 1, j, 0.0); i-- }
                else -> { ops += AlignOp(OpType.DELETION, -1, j - 1, 0.0); j-- }
            }
        }
        ops.reverse()
        // A local alignment never starts or ends with a gap; drop any leading/trailing non-match.
        val result = if (local) {
            val a = ops.indexOfFirst { it.type == OpType.MATCH }
            val b = ops.indexOfLast { it.type == OpType.MATCH }
            if (a < 0) emptyList() else ops.subList(a, b + 1).toList()
        } else ops
        return LocalAlignment(Alignment(result, h[endI * w + endJ]), endJ - 1, second)
    }

    companion object {
        const val DEFAULT_WORD_MATCH_THRESHOLD = 0.7

        /** Word-level aligner: fuzzy similarity of normalized skeleton strings. */
        fun words(matchThreshold: Double = DEFAULT_WORD_MATCH_THRESHOLD) = Aligner(Similarity::normalized, matchThreshold)

        /** Phoneme-level aligner: exact phoneme identity by default, or a custom phoneme similarity. */
        fun phonemes(similarity: (String, String) -> Double = Similarity::exact, matchThreshold: Double = 1.0) =
            Aligner(similarity, matchThreshold)

        fun forUnit(unit: TokenUnit, matchThreshold: Double) =
            if (unit == TokenUnit.WORD) words(matchThreshold) else phonemes(matchThreshold = matchThreshold)
    }
}

/** Word-level aligner (normalized skeleton strings, fuzzy similarity). */
fun WordAligner(matchThreshold: Double = Aligner.DEFAULT_WORD_MATCH_THRESHOLD) = Aligner.words(matchThreshold)

/** Phoneme-level aligner. */
fun PhonemeAligner(similarity: (String, String) -> Double = Similarity::exact, matchThreshold: Double = 1.0) =
    Aligner.phonemes(similarity, matchThreshold)
