package com.qurancompanion.engine.tracking

import com.qurancompanion.engine.align.Aligner
import com.qurancompanion.engine.align.Alignment

/**
 * Tracking around the last confirmed position.
 *
 * - [sequential]: the user continues from the last confirmed unit. Commits only a prefix ending in a
 *   match, and only with enough evidence (minSequentialMatches + one per error in that prefix).
 * - [reposition]: the recent tokens are found elsewhere in a window [confirmed - back, confirmed + forward]
 *   (repeat of the current or a previous ayah, skip forward), with the same acceptance rule as global search.
 */
class LocalTracker(
    private val sequence: UnitSequence,
    private val aligner: Aligner,
    private val config: TrackerConfig,
    private val search: GlobalPositionSearch,
) {
    /** Committed in-sequence span: [alignment] has absolute ref indices and query indices into pending. */
    data class Advance(val alignment: Alignment, val consumed: Int) {
        val endUnit: Int get() = alignment.refEnd
    }

    fun sequential(confirmed: Int, pending: List<String>, minMatches: Int = config.minSequentialMatches): Advance? {
        val start = confirmed + 1
        if (pending.isEmpty() || start >= sequence.size) return null
        val end = minOf(sequence.size, start + pending.size + config.maxSequentialSkip)
        val a = aligner.prefix(pending, sequence.units.subList(start, end)).trimmedToLastMatch()
        if (a.ops.isEmpty()) return null
        if (a.matches < minMatches + a.errors) return null
        val consumed = a.queryEnd + 1
        return Advance(a.shifted(0, start), consumed)
    }

    fun reposition(confirmed: Int, pending: List<String>): SearchResult {
        val lo = maxOf(0, confirmed - config.backWindow)
        val hi = minOf(sequence.size - 1, confirmed + config.forwardWindow)
        return search.bestOf(pending.takeLast(config.maxQuery), listOf(lo..hi))
    }
}
