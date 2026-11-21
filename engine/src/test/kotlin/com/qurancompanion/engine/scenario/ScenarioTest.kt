package com.qurancompanion.engine.scenario

import com.qurancompanion.engine.align.MistakeType
import com.qurancompanion.engine.recognition.RecognizerStatus
import com.qurancompanion.engine.recognition.Token
import com.qurancompanion.engine.recognition.TokenUnit
import com.qurancompanion.engine.session.HintLevel
import com.qurancompanion.engine.session.PauseClass
import com.qurancompanion.engine.session.PracticeMode
import com.qurancompanion.engine.session.RecitationSessionController
import com.qurancompanion.engine.session.SessionInput
import com.qurancompanion.engine.session.SessionState
import com.qurancompanion.engine.session.SessionState.*
import com.qurancompanion.engine.session.SessionStatus
import com.qurancompanion.engine.testutil.Driver
import com.qurancompanion.engine.testutil.ManualClock
import com.qurancompanion.engine.testutil.Synthetic
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScenarioTest {
    private val ref = Synthetic.reference

    /** Surah-2 ayah with >= 12 words; returns (ayah range, index of a word far from pause points). */
    private fun longAyah(minAyah: Int = 60): Pair<IntRange, Int> {
        for (a in minAyah..190) {
            val r = Synthetic.ayah(2, a)
            if (r.count() < 12) continue
            val w = (r.first + 5..r.last - 3).firstOrNull { x -> (x - 2..x + 2).none { ref.isPausePoint(it) } } ?: continue
            return r to w
        }
        error("no long ayah")
    }

    private fun Driver.locateIn(ayah: IntRange) {
        say(ayah.first..ayah.first + 4)
        assertEquals(RECITING, c.currentState)
    }

    private fun Driver.assertNoFeedback() = assertTrue(ui.feedback.isEmpty(), "unexpected feedback ${ui.feedback}")

    @Test fun startMidAyahLocatesPosition() {
        val d = Driver()
        val a = Synthetic.ayah(2, 101)
        val ids = (a.first + 3)..a.last
        d.say(ids)
        assertEquals(RECITING, d.c.currentState)
        assertTrue(d.ui.confirmed!!.wordId >= ids.last - 1)
        assertEquals(ids.last, d.ui.candidate!!.wordId)
        assertEquals(2, d.ui.confirmed!!.surah); assertEquals(101, d.ui.confirmed!!.ayah)
    }

    @Test fun breathingPauseGivesNoLukma() {
        val d = Driver()
        val (ayah, w) = longAyah()
        d.say(ayah.first..w)
        d.silence(2000)
        assertEquals(PauseClass.NORMAL, d.ui.pauseClass)
        assertNull(d.ui.pendingOffer)
        d.say(w + 1..ayah.last)
        assertTrue(ASSISTANCE_OFFERED !in d.c.history && PAUSE_DETECTED !in d.c.history)
    }

    @Test fun waqfPauseGivesNoAssistanceButSamePauseMidAyahDoes() {
        val waqf = (0 until ref.wordCount).first { ref.hasWaqfAfter(it) && ref.word(it).surah == 2 && ref.word(it).ayah > 40 }
        val ayah = ref.ayahWords(2, ref.word(waqf).ayah)!!
        val d = Driver()
        d.say(ayah.first..waqf)
        d.silence(8000)
        assertNull(d.ui.pendingOffer)
        assertTrue(ASSISTANCE_OFFERED !in d.c.history)

        val (ayah2, mid) = longAyah()
        val e = Driver()
        e.say(ayah2.first..mid)
        e.silence(8000)
        assertNotNull(e.ui.pendingOffer)
    }

    @Test fun forgottenWordGetsOfferThenHintThenResume() {
        val d = Driver(mode = PracticeMode.MEMORIZATION)
        val (ayah, w) = longAyah()
        d.say(ayah.first..w)
        d.silence(4000)
        assertNull(d.ui.pendingOffer) // still only listening
        d.silence(2500)
        val offer = assertNotNull(d.ui.pendingOffer)
        assertEquals(w + 1, offer.nextWordId)
        assertEquals(WAITING_FOR_CONFIRMATION, d.c.currentState)
        assertNull(d.ui.activeHint) // nothing plays without confirmation

        d.handle(SessionInput.AcceptOffer())
        val hint = assertNotNull(d.ui.activeHint)
        assertEquals(HintLevel.NEXT_WORD, hint.level); assertEquals(w + 1..w + 1, hint.wordIds)
        assertEquals(PLAYING_LUKMA, d.c.currentState)

        // The Qari's voice is picked up by the mic: must not move anything.
        val before = d.ui.confirmed to d.ui.candidate
        d.handle(SessionInput.PlaybackStarted)
        d.sayText(ref.word(w + 1).matchKey); d.sayText(ref.word(w + 2).matchKey)
        assertEquals(before, d.ui.confirmed to d.ui.candidate)
        val playEnd = d.clock.now
        d.handle(SessionInput.PlaybackEnded)
        assertEquals(WAITING_FOR_RETRY, d.c.currentState)
        // late token from playback audio, inside the tail
        d.stable(listOf(Token(ref.word(w + 1).matchKey, playEnd - 200, playEnd + 100)))
        assertEquals(before.first, d.ui.confirmed)

        d.clock.advance(600) // user resumes after the playback tail
        d.say(w + 1..ayah.last)
        assertTrue(RESUMING in d.c.history)
        assertEquals(RECITING, d.c.currentState)
        assertTrue(d.ui.confirmed!!.wordId >= ayah.last - 1)
        d.assertNoFeedback()
    }

    @Test fun repeatingCurrentAyahIsNotAMistake() {
        val d = Driver(mode = PracticeMode.MEMORIZATION)
        val a = Synthetic.ayah(2, 70); val b = Synthetic.ayah(2, 71)
        d.say(a); d.say(a.first..a.first + 3) // restart the same ayah
        d.say(a.first + 4..a.last); d.say(b)
        d.silence(9000) // let any held mistake expire
        d.assertNoFeedback()
        assertTrue(d.ui.confirmed!!.wordId >= b.last - 1)
        assertTrue(d.confirmedTrail.zipWithNext().any { (x, y) -> x != null && y != null && y < x }, "position should move back")
    }

    @Test fun recitingPreviousAyahRepositions() {
        val d = Driver(mode = PracticeMode.MEMORIZATION)
        d.say(Synthetic.ayah(2, 80)); d.say(Synthetic.ayah(2, 81))
        val prev = Synthetic.ayah(2, 80)
        d.say(prev)
        assertEquals(80, d.ui.confirmed!!.ayah)
        assertTrue(d.ui.confirmed!!.wordId >= prev.last - 1)
        d.silence(9000)
        d.assertNoFeedback()
    }

    @Test fun skippingAnAyahRepositionsWithoutDeletions() {
        val d = Driver(mode = PracticeMode.MEMORIZATION)
        d.say(Synthetic.ayah(2, 90))
        d.say(Synthetic.ayah(2, 92))
        assertEquals(92, d.ui.confirmed!!.ayah)
        d.silence(9000)
        d.assertNoFeedback()
    }

    @Test fun jumpToAnotherSurah() {
        val d = Driver(mode = PracticeMode.MEMORIZATION)
        d.say(Synthetic.ayah(2, 90))
        val target = Synthetic.ayah(3, 20)
        d.say(target)
        assertTrue(LOCATING_POSITION in d.c.history.drop(8))
        assertEquals(3, d.ui.confirmed!!.surah); assertEquals(20, d.ui.confirmed!!.ayah)
        d.say(Synthetic.ayah(3, 21))
        assertEquals(RECITING, d.c.currentState)
        d.silence(9000)
        d.assertNoFeedback()
    }

    @Test fun ambiguousRepeatedPhraseStaysSilentUntilResolved() {
        val d = Driver()
        d.say(Synthetic.ayah(2, 24)) // the refrain: appears 4 times
        assertNull(d.ui.confirmed)
        assertEquals(LOCATING_POSITION, d.c.currentState)
        d.say(Synthetic.ayah(2, 25))
        assertEquals(25, d.ui.confirmed!!.ayah)

        val e = Driver()
        e.say(Synthetic.ayah(3, 5)) // identical to surah 2 ayah 50
        assertNull(e.ui.confirmed)
        e.say(Synthetic.ayah(3, 6))
        assertEquals(3, e.ui.confirmed!!.surah)
    }

    @Test fun selfCorrectionRestartGivesNoFeedback() {
        // Spec pattern: user says a wrong word X, then restarts and says the expected words correctly.
        val (ayah, w) = longAyah()
        val d = Driver(mode = PracticeMode.MEMORIZATION)
        d.say(ayah.first until w)
        d.sayText("qqqqqq")                 // X instead of word w
        d.say(w + 1..w + 2)                 // continues a little...
        d.say(w - 2..w + 4)                 // ...then goes back and recites correctly
        d.say(w + 5..ayah.last)
        d.silence(9000)
        d.assertNoFeedback()

        // Immediate restart: X, then the expected word.
        val e = Driver(mode = PracticeMode.MEMORIZATION)
        e.say(ayah.first until w); e.sayText("qqqqqq"); e.say(w..ayah.last)
        e.silence(9000)
        e.assertNoFeedback()

        // Restart from earlier before anything was committed.
        val f = Driver(mode = PracticeMode.MEMORIZATION)
        f.say(ayah.first until w); f.sayText("qqqqqq"); f.say(w - 3..ayah.last)
        f.silence(9000)
        f.assertNoFeedback()
    }

    @Test fun uncorrectedSubstitutionIsReportedAfterWindow() {
        val (ayah, w) = longAyah()
        val d = Driver(mode = PracticeMode.RECITATION)
        d.say(ayah.first until w)
        d.sayText("qqqqqq")
        d.say(w + 1..w + 2)
        assertTrue(d.ui.feedback.isEmpty()) // still inside the self-correction window
        d.say(w + 3..ayah.last)
        d.silence(9000)
        val m = d.ui.feedback.single()
        assertEquals(MistakeType.SUBSTITUTION, m.type); assertEquals(w, m.wordId)
        assertTrue(POSSIBLE_MISTAKE in d.c.history && SELF_CORRECTION_WINDOW in d.c.history)
    }

    @Test fun deletionAndOrderAreReportedInMemorization() {
        val (ayah, w) = longAyah()
        val d = Driver(mode = PracticeMode.MEMORIZATION)
        d.say(ayah.first until w); d.say(w + 1..ayah.last) // word w missing
        d.silence(9000)
        assertEquals(listOf(MistakeType.DELETION to w), d.ui.feedback.map { it.type to it.wordId })

        val (ayah2, v) = longAyah(ayah.last.let { ref.word(it).ayah + 1 })
        val e = Driver(mode = PracticeMode.MEMORIZATION)
        e.say(ayah2.first until v); e.say(listOf(v + 1, v)); e.say(v + 2..ayah2.last)
        e.silence(9000)
        assertEquals(MistakeType.ORDER, e.ui.feedback.single().type)
    }

    @Test fun noisyRandomTokensNeverAdvanceOrAlert() {
        val r = Random(42)
        fun noise() = if (r.nextBoolean()) Synthetic.vocab[r.nextInt(Synthetic.vocab.size)] else Synthetic.junk(r, r.nextInt(3, 7))
        val d = Driver(mode = PracticeMode.MEMORIZATION)
        repeat(300) { d.sayText(noise(), confidence = r.nextFloat()) }
        assertNull(d.ui.confirmed); assertEquals(LOCATING_POSITION, d.c.currentState)
        d.assertNoFeedback()

        val e = Driver(mode = PracticeMode.MEMORIZATION)
        e.say(Synthetic.ayah(2, 140))
        val pos = e.ui.confirmed
        repeat(300) { e.sayText(noise(), confidence = r.nextFloat()) }
        assertEquals(pos, e.ui.confirmed)
        e.silence(9000)
        e.assertNoFeedback()
        assertNull(e.ui.pendingOffer) // lost tracking: no position to offer help for
    }

    @Test fun userRequestedLukmaEscalates() {
        val d = Driver()
        val (ayah, w) = longAyah()
        d.say(ayah.first..w)
        d.handle(SessionInput.RequestLukma())
        assertEquals(PLAYING_LUKMA, d.c.currentState)
        assertEquals(HintLevel.NEXT_WORD, d.ui.activeHint!!.level)
        d.handle(SessionInput.PlaybackEnded)
        d.handle(SessionInput.RequestLukma())
        assertEquals(HintLevel.PHRASE, d.ui.activeHint!!.level)
        d.handle(SessionInput.PlaybackEnded)
        d.handle(SessionInput.RequestLukma())
        assertEquals(PLAYING_FULL_AYAH, d.c.currentState)
        assertEquals(ayah, d.ui.activeHint!!.wordIds)
        d.handle(SessionInput.PlaybackEnded)
        d.clock.advance(600)
        d.say(w + 1..ayah.last)
        assertEquals(RECITING, d.c.currentState)
    }

    @Test fun longSilenceNeverAutoPlays() {
        val d = Driver()
        val (ayah, w) = longAyah()
        d.say(ayah.first..w)
        d.silence(60_000)
        assertNotNull(d.ui.pendingOffer)
        assertNull(d.ui.activeHint)
        assertEquals(WAITING_FOR_CONFIRMATION, d.c.currentState)
        d.handle(SessionInput.DeclineOffer)
        d.silence(60_000)
        assertNull(d.ui.pendingOffer) // no second offer for the same word
        assertTrue(d.c.history.none { it == PLAYING_LUKMA || it == PLAYING_FULL_AYAH })
    }

    @Test fun resumingRecitationWithdrawsOffer() {
        val d = Driver()
        val (ayah, w) = longAyah()
        d.say(ayah.first..w); d.silence(8000)
        assertNotNull(d.ui.pendingOffer)
        d.say(w + 1..w + 3)
        assertNull(d.ui.pendingOffer)
        assertEquals(RECITING, d.c.currentState)
    }

    @Test fun interruptionAndPauseKeepPositionAndDuration() {
        val d = Driver()
        d.say(Synthetic.ayah(2, 110))
        val pos = d.ui.confirmed
        val dur = d.c.snapshot()!!.durationMs
        d.handle(SessionInput.Interrupted("call"))
        assertEquals(INTERRUPTED, d.c.currentState)
        d.say(Synthetic.ayah(2, 111)) // other party's voice etc. ignored
        d.clock.advance(60_000)
        assertEquals(pos, d.ui.confirmed)
        assertEquals(SessionStatus.INTERRUPTED, d.c.snapshot()!!.status)
        assertEquals(dur, d.c.snapshot()!!.durationMs)
        d.handle(SessionInput.InterruptionEnded)
        assertEquals(RECITING, d.c.currentState)
        d.handle(SessionInput.Pause); assertEquals(PAUSED, d.c.currentState)
        d.handle(SessionInput.Resume); assertEquals(RECITING, d.c.currentState)
        d.say(Synthetic.ayah(2, 111))
        assertEquals(111, d.ui.confirmed!!.ayah)
    }

    @Test fun snapshotAndRecovery() {
        val d = Driver()
        val a = Synthetic.ayah(2, 150)
        d.say(a)
        val snap = d.c.snapshot()!!
        assertEquals("session-1", snap.sessionId)
        assertEquals(d.ui.confirmed!!.wordId, snap.lastWordId)
        assertEquals(2, snap.lastSurah); assertEquals(150, snap.lastAyah)
        assertEquals(SessionStatus.ACTIVE, snap.status)
        assertTrue(snap.durationMs > 0)

        // process death: new controller restores and the user continues
        val clock = ManualClock(d.clock.now + 100_000)
        val c = RecitationSessionController(ref, TokenUnit.WORD, clock)
        c.handle(SessionInput.Initialize); c.handle(SessionInput.PermissionsResult(true)); c.handle(SessionInput.ModelReady); c.handle(SessionInput.ReferenceReady)
        c.handle(SessionInput.RecoverSession(snap))
        assertEquals(RECOVERING_SESSION, c.currentState)
        c.handle(SessionInput.RecognizerStatusChanged(RecognizerStatus.Listening))
        assertEquals(RECITING, c.currentState)
        assertEquals(snap.lastWordId, c.uiState.value.confirmed!!.wordId)
        assertEquals(snap.startTimeMs, c.snapshot()!!.startTimeMs)
        val next = (snap.lastWordId!! + 1)..(snap.lastWordId!! + 4)
        for (id in next) {
            clock.advance(500)
            c.handle(SessionInput.Recognition(com.qurancompanion.engine.recognition.RecognitionEvent(listOf(Token(ref.word(id).matchKey)), true, clock.now)))
        }
        assertTrue(c.uiState.value.confirmed!!.wordId >= next.last - 1)
    }

    @Test fun phonemeRecognizerScenario() {
        val d = Driver(unit = TokenUnit.PHONEME, mode = PracticeMode.MEMORIZATION)
        val a = Synthetic.ayah(2, 130)
        d.say(a.first + 2..a.last)
        assertEquals(RECITING, d.c.currentState)
        assertEquals(130, d.ui.confirmed!!.ayah)
        val b = Synthetic.ayah(2, 131)
        d.say(b)
        assertTrue(d.ui.confirmed!!.wordId >= b.last - 1)
        d.silence(9000)
        d.assertNoFeedback()
    }
}
