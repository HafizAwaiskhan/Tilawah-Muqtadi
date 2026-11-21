package com.qurancompanion.engine.tracking

import com.qurancompanion.engine.align.Aligner
import com.qurancompanion.engine.align.Alignment

enum class SearchStatus { ACCEPTED, AMBIGUOUS, INSUFFICIENT }

/** A located span: [startUnit]..[endUnit] of the reference explains the end of the query. */
data class SearchResult(
    val status: SearchStatus,
    val startUnit: Int = -1,
    val endUnit: Int = -1,
    val score: Double = 0.0,
    val matches: Int = 0,
    val margin: Double = 0.0,
    /** Alignment with absolute reference unit indices and query indices into the query. */
    val alignment: Alignment? = null,
) {
    val accepted: Boolean get() = status == SearchStatus.ACCEPTED
}

/**
 * Finds where in the whole reference the most recent tokens were recited.
 *
 * 1. Inverted index of unit n-grams (sorted array of hash|position, compact enough for ~77k words
 *    or ~350k phonemes).
 * 2. Each query n-gram votes for a diagonal (refPos - queryPos); top-K diagonal buckets are kept.
 * 3. Banded Smith-Waterman around each candidate, constrained to end at the last query tokens.
 * 4. Accept only with >= minRepositionMatches matches AND a margin of minMargin over the best
 *    candidate ending elsewhere. Repeated phrases therefore stay AMBIGUOUS until context separates them.
 */
class GlobalPositionSearch(
    private val sequence: UnitSequence,
    private val aligner: Aligner,
    private val config: TrackerConfig,
) {
    private val vocab = HashMap<String, Int>()
    private val ids: IntArray = IntArray(sequence.size) { vocab.getOrPut(sequence.units[it]) { vocab.size } }
    /** (hash shl 32) | position, sorted. */
    private val index: LongArray

    init {
        val n = config.ngram
        val count = maxOf(0, ids.size - n + 1)
        index = LongArray(count) { p -> (hash(ids, p, n).toLong() shl 32) or p.toLong() }
        index.sort()
    }

    private fun hash(arr: IntArray, from: Int, n: Int): Int {
        var h = 17
        for (k in 0 until n) h = h * 1_000_003 + arr[from + k]
        return h
    }

    private fun postings(h: Int): IntRange {
        val lo = lowerBound(h.toLong() shl 32)
        val hi = lowerBound((h.toLong() + 1) shl 32)
        return lo until hi
    }

    private fun lowerBound(key: Long): Int {
        var lo = 0; var hi = index.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (index[mid] < key) lo = mid + 1 else hi = mid }
        return lo
    }

    fun search(query: List<String>): SearchResult = searchIn(query, 0 until sequence.size)

    /** Same as [search] but candidates are restricted to [range] of reference units. */
    fun searchIn(query: List<String>, range: IntRange): SearchResult {
        val q = query.takeLast(config.maxQuery)
        if (q.size < config.minRepositionMatches) return SearchResult(SearchStatus.INSUFFICIENT)
        val n = config.ngram
        val qIds = IntArray(q.size) { vocab[q[it]] ?: -1 }
        val bucketSize = maxOf(4, q.size / 2)
        val votes = HashMap<Int, Int>()
        for (qp in 0..q.size - n) {
            if ((0 until n).any { qIds[qp + it] < 0 }) continue
            val r = postings(hash(qIds, qp, n))
            if (r.isEmpty() || r.last - r.first + 1 > config.maxPostings) continue
            for (k in r) {
                val p = (index[k] and 0xffffffffL).toInt()
                if (p !in range) continue
                val b = Math.floorDiv(p - qp, bucketSize)
                votes[b] = (votes[b] ?: 0) + 1
            }
        }
        val buckets = votes.entries.sortedByDescending { it.value }.take(config.topCandidates).map { it.key }
        val windows = buckets.map { b ->
            val start = (b * bucketSize - config.maxSequentialSkip - 2).coerceAtLeast(range.first)
            val end = (b * bucketSize + bucketSize + q.size + config.maxSequentialSkip + 2).coerceAtMost(range.last)
            start..end
        }
        return bestOf(q, windows)
    }

    /** Aligns [q] in each window and applies the acceptance rule. Also used for the local window. */
    internal fun bestOf(q: List<String>, windows: List<IntRange>): SearchResult {
        data class Cand(val score: Double, val end: Int, val second: Double, val alignment: Alignment)
        val minEnd = q.size - config.tailSlack
        val cands = ArrayList<Cand>()
        for (win in windows) {
            if (win.isEmpty()) continue
            val la = aligner.local(q, sequence.units.subList(win.first, win.last + 1), minEnd, q.size)
            val best = la.best ?: continue
            cands += Cand(best.score, win.first + la.bestEnd, la.secondScore, best.shifted(0, win.first))
        }
        if (cands.isEmpty()) return SearchResult(SearchStatus.INSUFFICIENT)
        cands.sortByDescending { it.score }
        val top = cands[0]
        var second = top.second
        for (c in cands.drop(1)) if (kotlin.math.abs(c.end - top.end) > q.size) second = maxOf(second, c.score)
        val matches = top.alignment.matches
        val margin = top.score - second
        val status = when {
            matches < config.minRepositionMatches || top.score < config.minScore -> SearchStatus.INSUFFICIENT
            margin < config.minMargin -> SearchStatus.AMBIGUOUS
            else -> SearchStatus.ACCEPTED
        }
        return SearchResult(status, top.alignment.refStart, top.alignment.refEnd, top.score, matches, margin, top.alignment)
    }
}
