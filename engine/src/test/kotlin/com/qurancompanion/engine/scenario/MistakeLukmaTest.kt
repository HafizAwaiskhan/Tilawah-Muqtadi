package com.qurancompanion.engine.scenario

import com.qurancompanion.engine.recognition.Token
import com.qurancompanion.engine.session.HintLevel
import com.qurancompanion.engine.session.LukmaConfig
import com.qurancompanion.engine.session.OfferReason
import com.qurancompanion.engine.session.PracticeMode
import com.qurancompanion.engine.session.SessionConfig
import com.qurancompanion.engine.session.SessionInput
import com.qurancompanion.engine.session.SessionState.*
import com.qurancompanion.engine.testutil.Driver
import com.qurancompanion.engine.testutil.Synthetic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Lukma offered on a confirmed (surfaced) mistake: offer → confirm → full ayah → re-recite from the ayah start. */
class MistakeLukmaTest {
    private val ref = Synthetic.reference

    private fun longAyah(minAyah: Int = 60): Pair<IntRange, Int> {
        for (a in minAyah..190) {
            val r = Synthetic.ayah(2, a)
            if (r.count() < 12) continue
            val w = (r.first + 5..r.last - 3).firstOrNull { x -> (x - 2..x + 2).none { ref.isPausePoint(it) } } ?: continue
            return r to w
        }
        error("no long ayah")
    }

    /** Recites [ayah] with word [w] replaced by a wrong token and lets the window pass. */
    private fun mistakeDriver(config: SessionConfig = SessionConfig()): Triple<Driver, IntRange, Int> {
        val (ayah, w) = longAyah()
        val d = Driver(mode = PracticeMode.RECITATION, config = config)
        d.say(ayah.first until w)
        d.sayText("qqqqqq")
        d.say(w + 1..ayah.last)
        d.silence(9000)
        return Triple(d, ayah, w)
    }

    @Test fun mistakeOfferAcceptPlaysFullAyahThenResumesAtAyahStart() {
        val (d, ayah, w) = mistakeDriver()
        assertEquals(w, d.ui.feedback.single().wordId)
        assertEquals(1, d.ui.feedbackCount)
        val offer = assertNotNull(d.ui.pendingOffer)
        assertEquals(OfferReason.MISTAKE, offer.reason)
        assertEquals(w, offer.mistakeWordId)
        assertEquals(WAITING_FOR_CONFIRMATION, d.c.currentState)
        assertNull(d.ui.activeHint) // nothing plays without confirmation

        d.handle(SessionInput.AcceptOffer()) // default for a mistake: the full ayah
        val hint = assertNotNull(d.ui.activeHint)
        assertEquals(HintLevel.FULL_AYAH, hint.level)
        assertEquals(ayah, hint.wordIds)
        assertEquals(OfferReason.MISTAKE, hint.reason)
        assertEquals(PLAYING_FULL_AYAH, d.c.currentState)

        // Qari audio picked up by the mic never moves the position.
        val before = d.ui.confirmed
        d.handle(SessionInput.PlaybackStarted)
        d.say(ayah.first..ayah.first + 4)
        assertEquals(before, d.ui.confirmed)
        d.handle(SessionInput.PlaybackEnded)
        assertEquals(WAITING_FOR_RETRY, d.c.currentState)
        // Tracking resumes at the start of the ayah: the next expected word is its first word.
        assertEquals(ayah.first - 1, d.ui.confirmed!!.wordId)
        assertEquals(ayah.first, d.ui.nextWordId)
        assertTrue(d.ui.heldMistakes.isEmpty())

        d.clock.advance(600) // after the playback tail the user re-recites the ayah correctly
        d.say(ayah)
        assertTrue(RESUMING in d.c.history)
        assertEquals(RECITING, d.c.currentState)
        assertTrue(d.ui.confirmed!!.wordId >= ayah.last - 1)
        assertEquals(1, d.ui.feedbackCount) // no new feedback
    }

    @Test fun correctWordOnlyPlaysTheWordAndAlsoRestartsTheAyah() {
        val (d, ayah, w) = mistakeDriver()
        d.handle(SessionInput.AcceptOffer(HintLevel.NEXT_WORD))
        val hint = assertNotNull(d.ui.activeHint)
        assertEquals(w..w, hint.wordIds)
        assertEquals(PLAYING_LUKMA, d.c.currentState)
        d.handle(SessionInput.PlaybackStarted); d.clock.advance(800); d.handle(SessionInput.PlaybackEnded)
        assertEquals(ayah.first, d.ui.nextWordId)
    }

    @Test fun configurableDefaultForMistakeOffers() {
        val (d, _, w) = mistakeDriver(SessionConfig(lukma = LukmaConfig(mistakeHintLevel = HintLevel.NEXT_WORD)))
        d.handle(SessionInput.AcceptOffer())
        assertEquals(w..w, d.ui.activeHint!!.wordIds)
    }

    @Test fun declineContinuesTracking() {
        val (d, ayah, _) = mistakeDriver()
        assertNotNull(d.ui.pendingOffer)
        d.handle(SessionInput.DeclineOffer)
        assertNull(d.ui.pendingOffer)
        assertNull(d.ui.activeHint)
        assertEquals(RECITING, d.c.currentState)
        val next = ref.word(ayah.last + 1)
        d.say(Synthetic.ayah(next.surah, next.ayah))
        assertEquals(next.ayah, d.ui.confirmed!!.ayah)
        assertFalse(PLAYING_FULL_AYAH in d.c.history || PLAYING_LUKMA in d.c.history)
    }

    @Test fun selfCorrectedMistakeGivesNoOffer() {
        val (ayah, w) = longAyah()
        val d = Driver(mode = PracticeMode.RECITATION)
        d.say(ayah.first until w)
        d.sayText("qqqqqq")
        d.say(w - 2..ayah.last) // goes back and recites correctly
        d.silence(9000)
        assertTrue(d.ui.feedback.isEmpty())
        assertNull(d.ui.pendingOffer)
        assertFalse(WAITING_FOR_CONFIRMATION in d.c.history)
    }

    @Test fun mistakeOfferStaysWhileRecitingNearbyThenGoesWhenUserMovesOn() {
        val (ayah, w) = longAyah()
        val d = Driver(mode = PracticeMode.RECITATION)
        d.say(ayah.first until w)
        d.sayText("qqqqqq")
        val a1 = ref.word(ayah.last + 1).let { Synthetic.ayah(it.surah, it.ayah) }
        d.say(w + 1..ayah.last)
        d.say(a1.first..a1.first + 2)
        // Promoted while reciting (window passed): the offer appears and stays on the next words.
        assertEquals(OfferReason.MISTAKE, d.ui.pendingOffer?.reason, "feedback=${d.ui.feedback} held=${d.ui.heldMistakes}")
        d.say(a1.first + 3..a1.last)
        assertNotNull(d.ui.pendingOffer)
        val a2 = ref.word(a1.last + 1).let { Synthetic.ayah(it.surah, it.ayah) }
        d.say(a2)
        assertNull(d.ui.pendingOffer) // moved on beyond the following ayah
        assertTrue(d.c.currentState in setOf(RECITING, SELF_CORRECTION_WINDOW))
    }

    @Test fun unansweredMistakeOfferTimesOut() {
        val (d, _, _) = mistakeDriver()
        assertNotNull(d.ui.pendingOffer)
        d.silence(31_000)
        assertNull(d.ui.pendingOffer)
        assertNull(d.ui.activeHint)
    }

    @Test fun tappedFlagReplaysAyahAndRestartsThere() {
        val (d, ayah, w) = mistakeDriver()
        d.handle(SessionInput.DeclineOffer)
        d.handle(SessionInput.RequestLukma(wordId = w))
        val hint = assertNotNull(d.ui.activeHint)
        assertEquals(ayah, hint.wordIds)
        assertEquals(PLAYING_FULL_AYAH, d.c.currentState)
        d.handle(SessionInput.PlaybackEnded)
        assertEquals(ayah.first, d.ui.nextWordId)
        // late Qari token inside the tail is ignored
        d.stable(listOf(Token(ref.word(ayah.first).matchKey, d.clock.now - 100, d.clock.now + 50)))
        assertEquals(ayah.first - 1, d.ui.confirmed!!.wordId)
    }
}
