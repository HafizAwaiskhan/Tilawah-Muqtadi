package com.qurancompanion.engine.session

import com.qurancompanion.engine.reference.QuranReference

enum class HintLevel { NEXT_WORD, PHRASE, FULL_AYAH }

/** Why help is offered: the reciter stopped (forgot the next word) or a mistake was confirmed. */
enum class OfferReason { PAUSE, MISTAKE }

/**
 * A pending offer. For [OfferReason.PAUSE] [nextWordId] is the word the user seems to be stuck on;
 * for [OfferReason.MISTAKE] it is the expected word of the confirmed mistake ([mistakeWordId]).
 */
data class LukmaOffer(
    val nextWordId: Int,
    val offeredAtMs: Long,
    val reason: OfferReason = OfferReason.PAUSE,
    val mistakeWordId: Int? = null,
)

/**
 * What the app should play: words [wordIds] (global ids) of one ayah. A [OfferReason.MISTAKE] hint
 * makes the controller resume tracking at the start of that ayah after playback (the user re-recites it).
 */
data class HintRequest(
    val level: HintLevel,
    val wordIds: IntRange,
    val surah: Int,
    val ayah: Int,
    val reason: OfferReason = OfferReason.PAUSE,
)

data class LukmaConfig(
    val phraseWords: Int = 3,
    /** No new offer within this time after an offer was declined, withdrawn or served. */
    val offerCooldownMs: Long = 20_000,
    /** Default hint when the user accepts a MISTAKE offer without choosing a level (owner: whole ayah). */
    val mistakeHintLevel: HintLevel = HintLevel.FULL_AYAH,
    /** A MISTAKE offer that is neither accepted nor declined disappears after this time. */
    val mistakeOfferTimeoutMs: Long = 30_000,
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

    /**
     * Offers help for a mistake the feedback policy has just surfaced. Same cooldown as pause offers;
     * never replaces a pending offer. Nothing plays until [accept].
     */
    fun offerForMistake(mistakeWordId: Int, nowMs: Long): LukmaOffer? {
        if (offer != null) return null
        if (mistakeWordId !in 0 until reference.wordCount) return null
        lastOfferEndMs?.let { if (nowMs - it < config.offerCooldownMs) return null }
        return LukmaOffer(mistakeWordId, nowMs, OfferReason.MISTAKE, mistakeWordId).also { offer = it }
    }

    /**
     * Accepts the pending offer. Default level: PAUSE → the smallest hint (escalating on repeats);
     * MISTAKE → [LukmaConfig.mistakeHintLevel] (full ayah) for the mistake's ayah.
     */
    fun accept(level: HintLevel? = null): HintRequest? {
        val o = offer ?: return null
        offer = null
        if (o.reason == OfferReason.MISTAKE) return serveMistake(o.mistakeWordId ?: o.nextWordId, level)
        return serve(o.nextWordId, level)
    }

    /** User asked to hear the ayah of a flagged word (or just that word); resumes at the ayah start. */
    fun requestForMistake(wordId: Int, level: HintLevel? = null): HintRequest? {
        if (wordId !in 0 until reference.wordCount) return null
        offer = null
        return serveMistake(wordId, level)
    }

    private fun serveMistake(wordId: Int, level: HintLevel?): HintRequest =
        hintFor(level ?: config.mistakeHintLevel, wordId).copy(reason = OfferReason.MISTAKE)
            .also { activeHint = it; lastHint = it }

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
