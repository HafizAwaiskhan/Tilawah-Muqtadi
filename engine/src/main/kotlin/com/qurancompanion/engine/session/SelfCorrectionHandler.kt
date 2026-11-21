package com.qurancompanion.engine.session

import com.qurancompanion.engine.align.CandidateMistake

data class SelfCorrectionConfig(
    /** A mistake is held at least this long... */
    val windowMs: Long = 3000,
    /** ...and until this many further words have been recited... */
    val windowWords: Int = 3,
    /** ...but is promoted after this long regardless of words. */
    val maxHoldMs: Long = 8000,
    /** A backward reposition starting within this many words after a held mistake discards it (restart or misalignment). */
    val repeatMarginWords: Int = 2,
)

/**
 * Holds candidate mistakes as provisional. A held mistake is discarded when any of its words is
 * matched again by a later update (the user went back and re-recited it correctly); otherwise it
 * is promoted once the window has passed.
 */
class SelfCorrectionHandler(private val config: SelfCorrectionConfig = SelfCorrectionConfig()) {
    private class Held(val mistake: CandidateMistake, val sinceMs: Long, var wordsAfter: Int = 0)

    private val items = ArrayList<Held>()

    val held: List<CandidateMistake> get() = items.map { it.mistake }

    fun hold(mistakes: List<CandidateMistake>, nowMs: Long) {
        mistakes.filter { it.isError }.forEach { items += Held(it, nowMs) }
    }

    /**
     * Call for every committed update BEFORE holding the mistakes of that same update.
     * Returns the mistakes that were discarded (self-corrected).
     */
    fun onWordsMatched(wordIds: Collection<Int>, nowMs: Long): List<CandidateMistake> {
        val corrected = items.filter { h -> h.mistake.relatedWordIds.any { it in wordIds } }
        items.removeAll(corrected.toSet())
        items.forEach { it.wordsAfter += wordIds.size }
        return corrected.map { it.mistake }
    }

    /**
     * The user went back and re-recited from [startWord] (a REPEAT). Held mistakes at or after
     * startWord - repeatMarginWords are discarded: either they were corrected, or our alignment
     * before the repeat was unreliable.
     */
    fun onRepeat(startWord: Int): List<CandidateMistake> {
        val corrected = items.filter { h -> h.mistake.relatedWordIds.any { it >= startWord - config.repeatMarginWords } }
        items.removeAll(corrected.toSet())
        return corrected.map { it.mistake }
    }

    /** Mistakes whose window has passed without a correction. */
    fun promoteDue(nowMs: Long): List<CandidateMistake> {
        val due = items.filter { h ->
            val age = nowMs - h.sinceMs
            (age >= config.windowMs && h.wordsAfter >= config.windowWords) || age >= config.maxHoldMs
        }
        items.removeAll(due.toSet())
        return due.map { it.mistake }
    }

    fun clear() = items.clear()
}
