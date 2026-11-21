package com.qurancompanion.engine.session

import com.qurancompanion.engine.align.CandidateMistake
import com.qurancompanion.engine.align.MistakeType
import com.qurancompanion.engine.recognition.RecognitionEvent
import com.qurancompanion.engine.recognition.Token
import com.qurancompanion.engine.testutil.Synthetic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SelfCorrectionHandlerTest {
    private fun m(word: Int) = CandidateMistake(MistakeType.SUBSTITUTION, word, "x", 0.95)

    @Test fun discardedWhenWordIsReRecited() {
        val h = SelfCorrectionHandler()
        h.hold(listOf(m(10)), 0)
        assertEquals(listOf(m(10)), h.onWordsMatched(listOf(9, 10, 11), 1000))
        assertTrue(h.held.isEmpty())
        assertTrue(h.promoteDue(10_000).isEmpty())
    }

    @Test fun promotedAfterTimeAndWords() {
        val h = SelfCorrectionHandler(SelfCorrectionConfig(windowMs = 3000, windowWords = 3, maxHoldMs = 8000))
        h.hold(listOf(m(10)), 0)
        h.onWordsMatched(listOf(11, 12), 1000)
        assertTrue(h.promoteDue(4000).isEmpty()) // only 2 words after
        h.onWordsMatched(listOf(13), 4100)
        assertEquals(listOf(m(10)), h.promoteDue(4200))
    }

    @Test fun promotedAfterMaxHoldEvenWithoutWords() {
        val h = SelfCorrectionHandler()
        h.hold(listOf(m(10)), 0)
        assertTrue(h.promoteDue(7999).isEmpty())
        assertEquals(1, h.promoteDue(8000).size)
    }

    @Test fun repetitionIsNeverHeld() {
        val h = SelfCorrectionHandler()
        h.hold(listOf(CandidateMistake(MistakeType.REPETITION, 3, "x", 0.0)), 0)
        assertTrue(h.held.isEmpty())
    }
}

class PauseDetectorTest {
    private val cfg = PauseConfig(adaptToPace = false)

    @Test fun classesFollowThresholds() {
        val p = PauseDetector(cfg)
        p.onSpeech(0)
        assertEquals(PauseClass.NORMAL, p.classify(2999, false))
        assertEquals(PauseClass.LISTENING, p.classify(3000, false))
        assertEquals(PauseClass.EVALUATE, p.classify(6000, false))
        assertEquals(PauseClass.LONG, p.classify(10_000, false))
    }

    @Test fun pausePointsAllowLongerSilence() {
        val p = PauseDetector(cfg)
        p.onSpeech(0)
        assertEquals(PauseClass.LISTENING, p.classify(7000, true))
        assertEquals(PauseClass.EVALUATE, p.classify(12_000, true))
    }

    @Test fun speakingIsNone() {
        val p = PauseDetector(cfg)
        p.onSpeechStart(0)
        assertEquals(PauseClass.NONE, p.classify(20_000, false))
        p.onSpeechEnd(20_000)
        assertEquals(PauseClass.NORMAL, p.classify(21_000, false))
    }

    @Test fun slowReciterGetsLongerThresholds() {
        val slow = PauseDetector(PauseConfig())
        var t = 0L
        repeat(10) { slow.onWord(t); t += 1100 } // 1.1 s per word
        assertTrue(slow.paceFactor > 1.4)
        val fast = PauseDetector(PauseConfig())
        t = 0
        repeat(10) { fast.onWord(t); t += 400 }
        assertEquals(0.8, fast.paceFactor, 1e-9)
        val last = t - 400
        assertEquals(PauseClass.EVALUATE, fast.classify(last + 5000, false))
        assertEquals(PauseClass.LISTENING, slow.classify(9900 + 5000, false))
    }
}

class FeedbackPolicyTest {
    private val ref = Synthetic.reference
    /** A word in the middle of a long ayah, far from any pause point. */
    private val mid: Int = (0 until ref.wordCount).first { w ->
        (w - 2..w + 2).all { it in 1 until ref.wordCount && !ref.isPausePoint(it) } && ref.word(w).surah == 2
    }

    private fun m(type: MistakeType, conf: Double, w: Int = mid) = CandidateMistake(type, w, "x", conf)

    @Test fun thresholdsAndModes() {
        val p = FeedbackPolicy(ref)
        assertFalse(p.shouldEmit(m(MistakeType.SUBSTITUTION, 0.8), PracticeMode.RECITATION, 0, 1.0))
        assertTrue(p.shouldEmit(m(MistakeType.SUBSTITUTION, 0.8), PracticeMode.MEMORIZATION, 0, 1.0))
        val q = FeedbackPolicy(ref)
        assertFalse(q.shouldEmit(m(MistakeType.INSERTION, 0.99), PracticeMode.RECITATION, 0, 1.0))
        assertFalse(q.shouldEmit(m(MistakeType.REPETITION, 1.0), PracticeMode.MEMORIZATION, 0, 1.0))
    }

    @Test fun cooldownAndPerAyahLimit() {
        val p = FeedbackPolicy(ref)
        assertTrue(p.shouldEmit(m(MistakeType.SUBSTITUTION, 0.95), PracticeMode.RECITATION, 0, 1.0))
        val other = (mid + 30 until ref.wordCount).first { w -> ref.word(w).ayah != ref.word(mid).ayah && (w - 1..w + 1).none { ref.isPausePoint(it) } }
        assertFalse(p.shouldEmit(m(MistakeType.SUBSTITUTION, 0.95, other), PracticeMode.RECITATION, 5_000, 1.0)) // cooldown
        assertFalse(p.shouldEmit(m(MistakeType.DELETION, 0.95, mid + 1), PracticeMode.RECITATION, 60_000, 1.0)) // same ayah, max 1
        assertTrue(p.shouldEmit(m(MistakeType.SUBSTITUTION, 0.95, other), PracticeMode.RECITATION, 60_000, 1.0))
    }

    @Test fun suppressedNearPausePointsAndWhenTrackingUnsure() {
        val p = FeedbackPolicy(ref)
        val end = ref.ayahWords(2, 40)!!.last
        assertFalse(p.shouldEmit(m(MistakeType.DELETION, 0.99, end), PracticeMode.MEMORIZATION, 0, 1.0))
        assertFalse(p.shouldEmit(m(MistakeType.SUBSTITUTION, 0.8, end), PracticeMode.MEMORIZATION, 0, 1.0))
        assertFalse(p.shouldEmit(m(MistakeType.SUBSTITUTION, 0.99), PracticeMode.MEMORIZATION, 0, 0.3))
        assertFalse(p.shouldEmit(m(MistakeType.SUBSTITUTION, 0.9).copy(nearPause = true), PracticeMode.RECITATION, 0, 1.0))
    }
}

class LukmaAssistanceControllerTest {
    private val ref = Synthetic.reference
    private val waqfWord = (0 until ref.wordCount).first { ref.hasWaqfAfter(it) }

    @Test fun offerThenAcceptGivesSmallestHint() {
        val l = LukmaAssistanceController(ref)
        assertNull(l.accept()) // nothing offered: nothing to play
        val o = l.maybeOffer(100, 0)
        assertNotNull(o)
        val h = l.accept()!!
        assertEquals(HintLevel.NEXT_WORD, h.level); assertEquals(100..100, h.wordIds)
        assertNull(l.offer)
    }

    @Test fun noRepeatedOfferForSameWordAndCooldown() {
        val l = LukmaAssistanceController(ref, LukmaConfig(offerCooldownMs = 20_000))
        l.maybeOffer(100, 0); l.decline(1000)
        assertNull(l.maybeOffer(100, 60_000))
        assertNull(l.maybeOffer(101, 5_000))
        assertNotNull(l.maybeOffer(101, 30_000))
    }

    @Test fun levelsAndEscalation() {
        val l = LukmaAssistanceController(ref)
        val start = waqfWord - 1
        assertEquals(HintLevel.NEXT_WORD, l.request(start)!!.level)
        val phrase = l.request(start)!!
        assertEquals(HintLevel.PHRASE, phrase.level)
        assertEquals(start..waqfWord, phrase.wordIds) // phrase stops at the waqf
        val full = l.request(start)!!
        assertEquals(HintLevel.FULL_AYAH, full.level)
        assertEquals(ref.ayahWords(ref.word(start).surah, ref.word(start).ayah), full.wordIds)
        l.onResumed()
        assertEquals(HintLevel.NEXT_WORD, l.request(start)!!.level)
        assertEquals(HintLevel.FULL_AYAH, l.request(start + 1, HintLevel.FULL_AYAH)!!.level)
    }
}

class PlaybackGuardTest {
    private fun ev(vararg t: Token, at: Long = 0, suppressed: LongRange? = null) = RecognitionEvent(t.toList(), true, at, suppressedWindow = suppressed)

    @Test fun dropsDuringPlaybackAndTail() {
        val g = PlaybackGuard(tailMs = 300)
        g.onPlaybackStart(1000)
        assertNull(g.filter(ev(Token("aaaa"), at = 1500)))
        g.onPlaybackEnd(2000)
        assertNull(g.filter(ev(Token("aaaa", 1900, 2100), at = 2400)))
        assertNull(g.filter(ev(Token("aaaa"), at = 2250))) // no timestamps: event time inside the tail
        assertNotNull(g.filter(ev(Token("aaaa", 2400, 2800), at = 2900)))
    }

    @Test fun keepsOnlyTokensOutsideWindows() {
        val g = PlaybackGuard(tailMs = 0)
        g.onPlaybackStart(1000); g.onPlaybackEnd(2000)
        val out = g.filter(ev(Token("in", 1500, 1800), Token("out", 2100, 2400), at = 2500))!!
        assertEquals(listOf("out"), out.tokens.map { it.text })
    }

    @Test fun honoursRecognizerSuppressedWindow() {
        val g = PlaybackGuard()
        assertNull(g.filter(ev(Token("aaaa", 100, 200), at = 300, suppressed = 0L..500L)))
    }
}

class SelfCorrectionRepeatTest {
    @Test fun repeatStartingJustAfterMistakeDiscardsIt() {
        val h = SelfCorrectionHandler()
        h.hold(listOf(CandidateMistake(MistakeType.DELETION, 100, null, 0.9), CandidateMistake(MistakeType.SUBSTITUTION, 90, "x", 0.9)), 0)
        assertEquals(listOf(100), h.onRepeat(101).map { it.wordId })
        assertEquals(listOf(90), h.held.map { it.wordId })
    }
}
