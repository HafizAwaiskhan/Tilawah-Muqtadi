package com.qurancompanion.engine.session

import com.qurancompanion.engine.reference.QuranReference

enum class HintLevel { NEXT_WORD, PHRASE, FULL_AYAH }

data class LukmaOffer(val nextWordId: Int, val offeredAtMs: Long)

/** What the app should play: words [wordIds] (global ids) of one ayah. */
data class HintRequest(val level: HintLevel, val wordIds: IntRange, val surah: Int, val ayah: Int)

data class LukmaConfig(
    val phraseWords: Int = 3,
    /** No new offer within this time after an offer was declined, withdrawn or served. */
    val offerCooldownMs: Long = 20_000,
)

/**
 * Smart Lukma: offer -> wait for confirmation -> hint (smallest level by default) -> resume.
 * A [HintRequest] is only ever produced by [accept] or [request], i.e. by an explicit user action.
 */
class LukmaAssistanceController(
    private val reference: QuranReference,
    private val config: LukmaConfig = LukmaConfig(),
) {
    var offer: LukmaOffer? = null
        private set
    var activeHint: HintRequest? = null
        private set
    private var lastHint: HintRequest? = null
    private var lastOfferEndMs: Long? = null
    private var lastOfferedWord: Int? = null

    /** Offers help for [nextWordId] if allowed. Never offers twice for the same word in a row. */
    fun maybeOffer(nextWordId: Int, nowMs: Long): LukmaOffer? {
        if (offer != null) return offer
        if (nextWordId !in 0 until reference.wordCount) return null
        if (lastOfferedWord == nextWordId) return null
        lastOfferEndMs?.let { if (nowMs - it < config.offerCooldownMs) return null }
        lastOfferedWord = nextWordId
        return LukmaOffer(nextWordId, nowMs).also { offer = it }
    }

    fun accept(level: HintLevel? = null): HintRequest? {
        val o = offer ?: return null
        offer = null
        return serve(o.nextWordId, level)
    }

    fun decline(nowMs: Long) {
        if (offer != null) { offer = null; lastOfferEndMs = nowMs }
    }

    /** The user resumed reciting while an offer was pending. */
    fun withdraw(nowMs: Long) = decline(nowMs)

    /** User-initiated Lukma (button/notification). Repeated requests for the same word escalate. */
    fun request(nextWordId: Int, level: HintLevel? = null): HintRequest? {
        if (nextWordId !in 0 until reference.wordCount) return null
        offer = null
        return serve(nextWordId, level)
    }

    private fun serve(nextWordId: Int, level: HintLevel?): HintRequest {
        val prev = lastHint
        val chosen = level ?: if (prev != null && prev.wordIds.first == nextWordId) {
            HintLevel.entries[minOf(prev.level.ordinal + 1, HintLevel.entries.size - 1)]
        } else HintLevel.NEXT_WORD
        return hintFor(chosen, nextWordId).also { activeHint = it; lastHint = it }
    }

    fun hintFor(level: HintLevel, nextWordId: Int): HintRequest {
        val w = reference.word(nextWordId)
        val ayah = reference.ayahWords(w.surah, w.ayah) ?: (nextWordId..nextWordId)
        val range = when (level) {
            HintLevel.NEXT_WORD -> nextWordId..nextWordId
            HintLevel.PHRASE -> {
                var end = nextWordId
                while (end - nextWordId + 1 < config.phraseWords && end < ayah.last && !reference.hasWaqfAfter(end)) end++
                nextWordId..end
            }
            HintLevel.FULL_AYAH -> ayah
        }
        return HintRequest(level, range, w.surah, w.ayah)
    }

    fun onPlaybackFinished(nowMs: Long) { activeHint = null; lastOfferEndMs = nowMs }

    /** The user continued reciting after a hint: the escalation chain ends. */
    fun onResumed() { activeHint = null; lastHint = null; lastOfferedWord = null }

    fun reset() { offer = null; activeHint = null; lastHint = null; lastOfferEndMs = null; lastOfferedWord = null }
}
