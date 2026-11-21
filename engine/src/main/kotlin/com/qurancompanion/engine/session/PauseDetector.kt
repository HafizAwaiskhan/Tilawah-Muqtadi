package com.qurancompanion.engine.session

enum class PauseClass {
    /** Currently speaking. */
    NONE,
    /** Short silence: breathing, normal rhythm. */
    NORMAL,
    /** Longer silence: the user may be thinking; listen, do nothing. */
    LISTENING,
    /** Long enough to consider offering help. */
    EVALUATE,
    /** Very long silence. */
    LONG,
}

data class PauseConfig(
    val listeningMs: Long = 3000,
    val evaluateMs: Long = 6000,
    val longMs: Long = 10000,
    /** Thresholds are multiplied by this at the end of an ayah or at a waqf hint. */
    val pausePointMultiplier: Double = 2.0,
    val adaptToPace: Boolean = true,
    /** Inter-word interval considered "average"; slower reciters get proportionally longer thresholds. */
    val referenceWordIntervalMs: Long = 700,
    val minPaceFactor: Double = 0.8,
    val maxPaceFactor: Double = 1.6,
    /** Intervals longer than this are pauses, not pace samples. */
    val maxPaceSampleMs: Long = 2000,
)

/** Classifies the current silence from speech timestamps plus context (end of ayah / waqf). */
class PauseDetector(private val config: PauseConfig = PauseConfig()) {
    private var lastSpeechMs: Long? = null
    private var speaking = false
    private var lastWordMs: Long? = null
    private var meanInterval: Double? = null

    val paceFactor: Double
        get() {
            val m = meanInterval ?: return 1.0
            if (!config.adaptToPace) return 1.0
            return (m / config.referenceWordIntervalMs).coerceIn(config.minPaceFactor, config.maxPaceFactor)
        }

    /** Speech was observed at [atMs] (a token, or VAD activity). */
    fun onSpeech(atMs: Long) {
        lastSpeechMs = maxOf(lastSpeechMs ?: atMs, atMs)
    }

    /** Voice-activity edges, if a VAD is available. */
    fun onSpeechStart(atMs: Long) { speaking = true; onSpeech(atMs) }
    fun onSpeechEnd(atMs: Long) { speaking = false; onSpeech(atMs) }

    /** [words] recited words were completed, the last one ending at [atMs]; used to measure pace. */
    fun onWord(atMs: Long, words: Int = 1) {
        val prev = lastWordMs
        lastWordMs = atMs
        onSpeech(atMs)
        if (prev == null || words < 1) return
        val d = (atMs - prev).toDouble() / words
        if (d <= 0 || d > config.maxPaceSampleMs) return
        meanInterval = meanInterval?.let { 0.8 * it + 0.2 * d } ?: d
    }

    /** Restarts the silence timer (after resume, after playback). */
    fun restart(atMs: Long) { lastSpeechMs = atMs; speaking = false }

    fun silenceMs(nowMs: Long): Long = if (speaking) 0 else nowMs - (lastSpeechMs ?: nowMs)

    fun classify(nowMs: Long, atPausePoint: Boolean): PauseClass {
        if (speaking) return PauseClass.NONE
        val s = silenceMs(nowMs)
        val f = paceFactor * (if (atPausePoint) config.pausePointMultiplier else 1.0)
        return when {
            s >= config.longMs * f -> PauseClass.LONG
            s >= config.evaluateMs * f -> PauseClass.EVALUATE
            s >= config.listeningMs * f -> PauseClass.LISTENING
            else -> PauseClass.NORMAL
        }
    }

    fun reset() { lastSpeechMs = null; speaking = false; lastWordMs = null; meanInterval = null }
}
