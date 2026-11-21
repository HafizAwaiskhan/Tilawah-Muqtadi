package com.qurancompanion.engine.tracking

import com.qurancompanion.engine.recognition.TokenUnit

/** Tunable parameters of position tracking. All counts are in recognizer units (words or phonemes). */
data class TrackerConfig(
    /** Minimum similarity for a token to match a reference unit. */
    val matchThreshold: Double,
    /** n-gram length of the global inverted index. */
    val ngram: Int,
    /** Matches needed to commit an in-sequence advance; each error in the committed span adds one more. */
    val minSequentialMatches: Int,
    /** Max reference units an in-sequence advance may skip (as deletions) before it counts as a reposition. */
    val maxSequentialSkip: Int,
    /** Matched units needed to accept a reposition or a global location. */
    val minRepositionMatches: Int,
    /** Minimum alignment score for a reposition/location (rejects scattered chance matches). */
    val minScore: Double,
    /** Score margin the best candidate needs over the best clearly different candidate. */
    val minMargin: Double,
    /** Uncommitted units after which tracking is declared lost and global search starts. */
    val lostAfter: Int,
    /** Most recent units used as the search query. */
    val maxQuery: Int,
    /** Local window behind / ahead of the last confirmed position (repeats / skips). */
    val backWindow: Int,
    val forwardWindow: Int,
    /** Query tokens at the end that a candidate alignment may leave unexplained. */
    val tailSlack: Int,
    val topCandidates: Int = 16,
    /** n-grams with more occurrences than this are ignored for voting (too common to be informative). */
    val maxPostings: Int = 2000,
) {
    companion object {
        fun forWords() = TrackerConfig(
            matchThreshold = 0.7, ngram = 2, minSequentialMatches = 2, maxSequentialSkip = 2,
            minRepositionMatches = 3, minScore = 2.8, minMargin = 1.5, lostAfter = 6, maxQuery = 12,
            backWindow = 80, forwardWindow = 120, tailSlack = 1,
        )

        fun forPhonemes() = TrackerConfig(
            matchThreshold = 1.0, ngram = 4, minSequentialMatches = 4, maxSequentialSkip = 8,
            minRepositionMatches = 10, minScore = 8.0, minMargin = 4.0, lostAfter = 25, maxQuery = 40,
            backWindow = 400, forwardWindow = 600, tailSlack = 2,
        )

        fun forUnit(unit: TokenUnit) = if (unit == TokenUnit.WORD) forWords() else forPhonemes()
    }
}
