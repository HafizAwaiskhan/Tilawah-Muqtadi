package com.qurancompanion.engine.recognition

/** What one recognizer token represents. */
enum class TokenUnit { WORD, PHONEME }

/**
 * One recognizer token: a normalized word (same normalization as WordRef.matchKey) or a single
 * phoneme (same inventory as WordRef.phonetic). Times are in the session clock's milliseconds.
 */
data class Token(
    val text: String,
    val startMs: Long? = null,
    val endMs: Long? = null,
    val confidence: Float? = null,
)

/**
 * Output of a recognizer.
 *
 * - Stable events carry tokens that will not change; each stable token is delivered exactly once,
 *   in order (stable events are appended).
 * - Provisional events carry the recognizer's current hypothesis of the not-yet-stable tail; each
 *   provisional event replaces the previous one. They are used only for display, never to commit.
 * - [suppressedWindow]: a time range during which the recognizer knows its input was suppressed or
 *   contaminated (e.g. Qari playback); tokens inside it are dropped.
 */
data class RecognitionEvent(
    val tokens: List<Token>,
    val isStable: Boolean,
    val timestampMs: Long,
    val unit: TokenUnit = TokenUnit.WORD,
    val suppressedWindow: LongRange? = null,
)

sealed interface RecognizerStatus {
    data object Idle : RecognizerStatus
    data class Loading(val progress: Float? = null) : RecognizerStatus
    data object Ready : RecognizerStatus
    data object Listening : RecognizerStatus
    data class Error(val message: String) : RecognizerStatus
}
