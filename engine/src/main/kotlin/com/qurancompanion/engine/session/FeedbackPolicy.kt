package com.qurancompanion.engine.session

import com.qurancompanion.engine.align.CandidateMistake
import com.qurancompanion.engine.align.MistakeType
import com.qurancompanion.engine.reference.QuranReference

enum class PracticeMode { RECITATION, MEMORIZATION }

data class ModePolicy(
    val threshold: Double,
    val types: Set<MistakeType>,
    val cooldownMs: Long,
    val maxPerAyah: Int,
)

data class FeedbackConfig(
    val recitation: ModePolicy = ModePolicy(
        threshold = 0.85,
        types = setOf(MistakeType.SUBSTITUTION, MistakeType.DELETION, MistakeType.ORDER),
        cooldownMs = 15_000, maxPerAyah = 1,
    ),
    val memorization: ModePolicy = ModePolicy(
        threshold = 0.75,
        types = setOf(MistakeType.SUBSTITUTION, MistakeType.DELETION, MistakeType.ORDER, MistakeType.INSERTION),
        cooldownMs = 6_000, maxPerAyah = 2,
    ),
    /** Extra threshold for mistakes next to an ayah end / waqf or right after a breath gap. */
    val pausePointPenalty: Double = 0.1,
    /** Tracking must be at least this confident for any feedback. */
    val minTrackingConfidence: Double = 0.6,
)

/** Decides whether a promoted mistake is surfaced. Silence is the default. */
class FeedbackPolicy(
    private val reference: QuranReference,
    private val config: FeedbackConfig = FeedbackConfig(),
) {
    private var lastFeedbackMs: Long? = null
    private val perAyah = HashMap<Pair<Int, Int>, Int>()

    fun policy(mode: PracticeMode) = if (mode == PracticeMode.RECITATION) config.recitation else config.memorization

    fun shouldEmit(m: CandidateMistake, mode: PracticeMode, nowMs: Long, trackingConfidence: Double): Boolean {
        val p = policy(mode)
        if (!m.isError || m.type !in p.types) return false
        if (trackingConfidence < config.minTrackingConfidence) return false
        val nearBoundary = m.nearPause || m.relatedWordIds.any { w ->
            reference.isPausePoint(w) || (w > 0 && reference.isPausePoint(w - 1))
        }
        if (nearBoundary && (m.type == MistakeType.DELETION || m.type == MistakeType.INSERTION)) return false
        val threshold = p.threshold + if (nearBoundary) config.pausePointPenalty else 0.0
        if (m.confidence < threshold) return false
        lastFeedbackMs?.let { if (nowMs - it < p.cooldownMs) return false }
        val w = reference.word(m.wordId.coerceIn(0, reference.wordCount - 1))
        val key = w.surah to w.ayah
        if ((perAyah[key] ?: 0) >= p.maxPerAyah) return false
        perAyah[key] = (perAyah[key] ?: 0) + 1
        lastFeedbackMs = nowMs
        return true
    }

    fun reset() { lastFeedbackMs = null; perAyah.clear() }
}
