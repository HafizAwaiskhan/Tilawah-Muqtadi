package com.qurancompanion.engine.tracking

import com.qurancompanion.engine.align.AlignOp
import com.qurancompanion.engine.align.Aligner
import com.qurancompanion.engine.align.OpType
import com.qurancompanion.engine.recognition.Token
import com.qurancompanion.engine.recognition.TokenUnit
import com.qurancompanion.engine.reference.QuranReference
import com.qurancompanion.engine.reference.WordPosition

enum class UpdateKind {
    /** First location found by global search. */
    LOCATED,
    /** In-sequence progress; ops may contain candidate mistakes. */
    ADVANCE,
    /** Re-recitation from an earlier point (current or previous ayah). Never a mistake. */
    REPEAT,
    /** Continued further ahead inside the local window. */
    SKIP,
    /** Re-located far away (other surah) after tracking was lost. */
    JUMP,
    /** Local alignment failed for too long: global search runs, last confirmed position is kept. */
    LOST,
}

data class TrackUpdate(
    val kind: UpdateKind,
    val from: WordPosition?,
    val to: WordPosition?,
    /** Alignment ops (absolute unit indices; queryIndex indexes [tokens]). Only ADVANCE ops are meant for mistake detection. */
    val ops: List<AlignOp> = emptyList(),
    val tokens: List<Token> = emptyList(),
    /** Word ids matched by this update (used to discard held mistakes that were re-recited). */
    val matchedWords: Set<Int> = emptySet(),
    /** Matched words immediately before this update (0..2), context for mistake confidence. */
    val contextBefore: Int = 0,
    /** Last tokens consumed before this update (timing context for mistake classification). */
    val previousTokens: List<Token> = emptyList(),
)

/**
 * Combines global search and local tracking.
 *
 * Only stable tokens can move [lastConfirmed]. Provisional tokens only move [candidate] (display).
 * The engine is not thread-safe; call it from one thread/dispatcher.
 */
class QuranPositionTracker(
    val reference: QuranReference,
    val unit: TokenUnit,
    val config: TrackerConfig = TrackerConfig.forUnit(unit),
) {
    val sequence = UnitSequence(reference, unit)
    val aligner: Aligner = Aligner.forUnit(unit, config.matchThreshold)
    private val global = GlobalPositionSearch(sequence, aligner, config)
    private val local = LocalTracker(sequence, aligner, config, global)

    private val pending = ArrayList<Token>()
    private var provisional: List<Token> = emptyList()
    private var confirmedUnit: Int? = null
    private var lost = false
    private var inFlow = false
    private val recentTokens = ArrayDeque<Token>()

    /** Last position committed from stable recognition (kept while lost). */
    val lastConfirmed: WordPosition? get() = confirmedUnit?.let { reference.position(sequence.wordOf(it)) }

    /** Best guess of the word being recited now, including uncommitted and provisional tokens. Display only. */
    var candidate: WordPosition? = null
        private set

    /** 0..1, recent match ratio of committed alignments; 0 while not tracking. */
    var confidence: Double = 0.0
        private set

    val isTracking: Boolean get() = confirmedUnit != null && !lost

    /** Last unit index committed; exposed for diagnostics. */
    val confirmedUnitIndex: Int? get() = confirmedUnit

    fun reset() {
        pending.clear(); provisional = emptyList(); confirmedUnit = null; lost = false; inFlow = false
        candidate = null; confidence = 0.0; recentTokens.clear()
    }

    /** Restores a persisted position (e.g. after process death). The user is assumed to continue after it. */
    fun restore(wordId: Int) {
        reset()
        confirmedUnit = sequence.lastUnitOf(wordId.coerceIn(0, reference.wordCount - 1))
        confidence = 0.5
        candidate = lastConfirmed
    }

    fun onProvisional(tokens: List<Token>) {
        provisional = tokens
        updateCandidate()
    }

    fun onStable(tokens: List<Token>): List<TrackUpdate> {
        provisional = emptyList()
        pending.addAll(tokens.filter { it.text.isNotBlank() })
        val updates = ArrayList<TrackUpdate>()
        while (true) {
            val u = step() ?: break
            updates += u
            if (u.kind == UpdateKind.LOST) continue
            if (pending.isEmpty()) break
        }
        while (pending.size > config.maxQuery) pending.removeAt(0)
        updateCandidate()
        return updates
    }

    private fun step(): TrackUpdate? {
        val texts = pending.map { it.text }
        val conf = confirmedUnit
        if (conf == null || lost) {
            val r = global.search(texts)
            if (!r.accepted) return null
            val kind = when {
                conf == null -> UpdateKind.LOCATED
                r.endUnit in (conf - config.backWindow)..(conf + config.forwardWindow) ->
                    if (r.startUnit <= conf) UpdateKind.REPEAT else UpdateKind.SKIP
                else -> UpdateKind.JUMP
            }
            return commitReposition(kind, r, texts.size)
        }
        local.sequential(conf, texts)?.let { adv ->
            val tokens = pending.subList(0, adv.consumed).toList()
            val update = TrackUpdate(
                UpdateKind.ADVANCE, lastConfirmed, reference.position(sequence.wordOf(adv.endUnit)),
                adv.alignment.ops, tokens, matchedWords(adv.alignment.ops), if (inFlow) 2 else 0, recentTokens.toList(),
            )
            repeat(adv.consumed) { pending.removeAt(0) }
            confirmedUnit = adv.endUnit
            remember(tokens)
            inFlow = true
            blendConfidence(adv.alignment.matches, adv.alignment.ops.size)
            return update
        }
        if (pending.size >= config.minRepositionMatches) {
            val r = local.reposition(conf, texts)
            if (r.accepted) {
                val kind = when {
                    r.startUnit <= conf -> UpdateKind.REPEAT
                    r.startUnit > conf + 1 + config.maxSequentialSkip -> UpdateKind.SKIP
                    else -> UpdateKind.ADVANCE // resynchronised just after the current position; no mistake ops
                }
                return commitReposition(kind, r, texts.size)
            }
        }
        if (pending.size >= config.lostAfter) {
            lost = true; inFlow = false; confidence = 0.0
            return TrackUpdate(UpdateKind.LOST, lastConfirmed, lastConfirmed)
        }
        return null
    }

    private fun commitReposition(kind: UpdateKind, r: SearchResult, querySize: Int): TrackUpdate {
        val from = lastConfirmed
        val offset = pending.size - minOf(querySize, config.maxQuery)
        val ops = r.alignment!!.ops
        val consumed = offset + (r.alignment.queryEnd + 1)
        val tokens = pending.subList(0, consumed).toList()
        repeat(consumed) { pending.removeAt(0) }
        confirmedUnit = r.endUnit
        lost = false
        inFlow = true
        remember(tokens)
        val ratio = r.matches.toDouble() / ops.size
        confidence = if (kind == UpdateKind.LOCATED || kind == UpdateKind.JUMP) ratio else 0.7 * confidence + 0.3 * ratio
        // Ops are not exposed for repositions: mistakes are only judged on in-sequence recitation.
        return TrackUpdate(kind, from, lastConfirmed, tokens = tokens, matchedWords = matchedWords(ops))
    }

    private fun remember(tokens: List<Token>) {
        tokens.forEach { recentTokens.addLast(it) }
        while (recentTokens.size > 3) recentTokens.removeFirst()
    }

    private fun matchedWords(ops: List<AlignOp>): Set<Int> =
        ops.filter { it.type == OpType.MATCH }.mapTo(LinkedHashSet()) { sequence.wordOf(it.refIndex) }

    private fun blendConfidence(matches: Int, total: Int) {
        confidence = 0.7 * confidence + 0.3 * (matches.toDouble() / maxOf(1, total))
    }

    private fun updateCandidate() {
        val conf = confirmedUnit
        if (conf == null || lost) { candidate = null; return }
        val texts = (pending + provisional).map { it.text }
        val adv = local.sequential(conf, texts, minMatches = 1)
        candidate = reference.position(sequence.wordOf(adv?.endUnit ?: conf))
    }

    /** Word after the best current guess of where the user is (used for hints and pause context). */
    fun effectiveWord(): Int? = (candidate ?: lastConfirmed)?.wordId
}
