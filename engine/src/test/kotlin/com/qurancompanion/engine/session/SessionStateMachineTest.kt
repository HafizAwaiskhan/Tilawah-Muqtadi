package com.qurancompanion.engine.session

import com.qurancompanion.engine.recognition.RecognizerStatus
import com.qurancompanion.engine.recognition.TokenUnit
import com.qurancompanion.engine.session.SessionState.*
import com.qurancompanion.engine.testutil.Driver
import com.qurancompanion.engine.testutil.ManualClock
import com.qurancompanion.engine.testutil.Synthetic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SessionStateMachineTest {
    @Test fun everyStateHasExplicitTransitionsAndCanFail() {
        for (s in SessionState.entries) {
            assertTrue(SessionTransitions.allowed(s).isNotEmpty(), "$s")
            if (s != ERROR) assertTrue(SessionTransitions.canTransition(s, ERROR), "$s -> ERROR")
        }
        // every state is reachable from some other state
        for (s in SessionState.entries - IDLE) assertTrue(SessionState.entries.any { SessionTransitions.canTransition(it, s) }, "$s unreachable")
    }

    @Test fun forbiddenTransitions() {
        assertFalse(SessionTransitions.canTransition(IDLE, RECITING))
        assertFalse(SessionTransitions.canTransition(READY, PLAYING_LUKMA))
        assertFalse(SessionTransitions.canTransition(LOCATING_POSITION, PLAYING_LUKMA)) // no hint without a position
        assertFalse(SessionTransitions.canTransition(LOCATING_POSITION, ASSISTANCE_OFFERED))
        assertFalse(SessionTransitions.canTransition(PAUSE_DETECTED, WAITING_FOR_CONFIRMATION)) // offer first
        assertFalse(SessionTransitions.canTransition(ASSISTANCE_OFFERED, PLAYING_LUKMA)) // must wait for confirmation
        assertFalse(SessionTransitions.canTransition(PLAYING_LUKMA, RECITING))
        assertFalse(SessionTransitions.canTransition(IDLE, STOPPED))
        assertTrue(SessionTransitions.canTransition(RECITING, STOPPED))
    }

    @Test fun lifecycleHappyPath() {
        val clock = ManualClock()
        val c = RecitationSessionController(Synthetic.reference, TokenUnit.WORD, clock, newSessionId = { "id" })
        assertEquals(IDLE, c.uiState.value.state)
        c.handle(SessionInput.Initialize)
        assertEquals(CHECKING_PERMISSIONS, c.currentState)
        c.handle(SessionInput.PermissionsResult(true))
        assertEquals(DOWNLOADING_MODEL, c.currentState)
        c.handle(SessionInput.ModelDownloadProgress(0.5f))
        assertEquals(0.5f, c.uiState.value.downloadProgress)
        c.handle(SessionInput.ModelReady)
        assertEquals(FETCHING_REFERENCE, c.currentState)
        c.handle(SessionInput.ReferenceReady)
        assertEquals(READY, c.currentState)
        c.handle(SessionInput.StartSession(PracticeMode.MEMORIZATION))
        assertEquals(STARTING_SESSION, c.currentState)
        c.handle(SessionInput.RecognizerStatusChanged(RecognizerStatus.Listening))
        assertEquals(LOCATING_POSITION, c.currentState)
        assertEquals("id", c.uiState.value.sessionId)
        assertEquals(PracticeMode.MEMORIZATION, c.uiState.value.mode)
        c.handle(SessionInput.Stop)
        assertEquals(STOPPED, c.currentState)
        assertEquals(SessionStatus.STOPPED, c.snapshot()!!.status)
        c.handle(SessionInput.Reset)
        assertEquals(IDLE, c.currentState)
    }

    @Test fun permissionDeniedIsError() {
        val c = RecitationSessionController(Synthetic.reference, TokenUnit.WORD, ManualClock())
        c.handle(SessionInput.Initialize)
        c.handle(SessionInput.PermissionsResult(false))
        assertEquals(ERROR, c.currentState)
        assertNotNull(c.uiState.value.error)
        c.handle(SessionInput.Initialize)
        assertEquals(CHECKING_PERMISSIONS, c.currentState)
    }

    @Test fun inputsInWrongStateAreIgnored() {
        val c = RecitationSessionController(Synthetic.reference, TokenUnit.WORD, ManualClock())
        for (i in listOf(SessionInput.PlaybackEnded, SessionInput.AcceptOffer(), SessionInput.RequestLukma(), SessionInput.Resume,
            SessionInput.Stop, SessionInput.Tick, SessionInput.StartSession(), SessionInput.ModelReady)) {
            c.handle(i)
            assertEquals(IDLE, c.currentState, "$i")
        }
        val d = Driver()
        d.handle(SessionInput.RequestLukma()) // no position yet: nothing to hint
        d.handle(SessionInput.AcceptOffer())
        assertEquals(LOCATING_POSITION, d.c.currentState)
    }

    @Test fun recognizerErrorGoesToError() {
        val d = Driver()
        d.handle(SessionInput.RecognizerStatusChanged(RecognizerStatus.Error("model crashed")))
        assertEquals(ERROR, d.c.currentState)
        assertEquals("model crashed", d.ui.error)
    }

    @Test fun historyOnlyContainsAllowedTransitions() {
        val d = Driver(mode = PracticeMode.MEMORIZATION)
        d.say(Synthetic.ayah(2, 60)); d.silence(12_000); d.handle(SessionInput.AcceptOffer())
        d.handle(SessionInput.PlaybackStarted); d.handle(SessionInput.PlaybackEnded)
        d.clock.advance(600)
        d.say(Synthetic.ayah(2, 61))
        d.c.history.zipWithNext().forEach { (a, b) -> assertTrue(SessionTransitions.canTransition(a, b), "$a -> $b") }
        assertTrue(PROCESSING in d.c.history && RESUMING in d.c.history && ASSISTANCE_OFFERED in d.c.history)
    }
}
