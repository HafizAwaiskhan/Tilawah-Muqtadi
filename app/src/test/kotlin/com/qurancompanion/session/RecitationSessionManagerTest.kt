package com.qurancompanion.session

import com.qurancompanion.FakeClock
import com.qurancompanion.FakePlayback
import com.qurancompanion.FakeQariAudio
import com.qurancompanion.FakeSessionRepository
import com.qurancompanion.FakeSettingsRepository
import com.qurancompanion.core.audio.InterruptionSignal
import com.qurancompanion.core.playback.PlaybackEvent
import com.qurancompanion.data.db.SessionStatus
import com.qurancompanion.data.reference.QuranPosition
import com.qurancompanion.data.settings.UserSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Note: the manager runs in backgroundScope, which advanceUntilIdle() does not wait for, so
// these tests drive it with runCurrent()/advanceTimeBy().
@OptIn(ExperimentalCoroutinesApi::class)
class RecitationSessionManagerTest {
    private val repo = FakeSessionRepository()
    private val settings = FakeSettingsRepository()
    private val playback = FakePlayback()
    private val qari = FakeQariAudio()
    private val clock = FakeClock()

    private fun TestScope.manager() =
        RecitationSessionManager(repo, settings, playback, qari, clock, backgroundScope).also { runCurrent() }

    @Test fun startPersistsSessionAndPosition() = runTest {
        val m = manager()
        m.start(QuranPosition(2, 255))
        runCurrent()
        val s = m.state.value
        assertEquals(ListeningStatus.LISTENING, s.status)
        assertEquals(1L, s.sessionId)
        assertEquals(SessionStatus.ACTIVE, repo.rows.value.single().status)

        clock.advance(5_000)
        m.setManualPosition(QuranPosition(2, 255, 3))
        runCurrent()
        val row = repo.rows.value.single()
        assertEquals(Triple(2, 255, 3), Triple(row.lastSurah, row.lastAyah, row.lastWord))
        assertEquals(5_000, row.durationMs)
    }

    @Test fun pauseExcludesPausedTimeFromDuration() = runTest {
        val m = manager()
        m.start(QuranPosition.START)
        clock.advance(10_000)
        m.pause()
        clock.advance(60_000)
        m.resume()
        clock.advance(5_000)
        m.stop()
        runCurrent()
        assertEquals(ListeningStatus.STOPPED, m.state.value.status)
        val row = repo.rows.value.single()
        assertEquals(SessionStatus.COMPLETED, row.status)
        assertEquals(15_000, row.durationMs)
        assertEquals(clock.now, row.endedAt)
    }

    @Test fun stopWithoutKeepHistoryDeletesSession() = runTest {
        settings.state.value = UserSettings(keepHistory = false)
        val m = manager()
        m.start(QuranPosition.START)
        m.stop()
        runCurrent()
        assertTrue(repo.rows.value.isEmpty())
    }

    @Test fun positionIgnoredWhenNotRunning() = runTest {
        val m = manager()
        m.setManualPosition(QuranPosition(3, 1))
        assertEquals(QuranPosition.START, m.state.value.position)
    }

    @Test fun callInterruptsAndAutoResumes_unplugDoesNot() = runTest {
        val m = manager()
        m.start(QuranPosition.START)
        runCurrent()
        m.onInterruptionSignal(InterruptionSignal.CallStarted)
        assertEquals(ListeningStatus.INTERRUPTED, m.state.value.status)
        assertEquals(InterruptionReason.CALL, m.state.value.interruption)
        assertFalse(m.state.value.audioGateOpen)
        m.onInterruptionSignal(InterruptionSignal.CallEnded)
        assertEquals(ListeningStatus.LISTENING, m.state.value.status)

        m.onInterruptionSignal(InterruptionSignal.BecomingNoisy)
        assertEquals(ListeningStatus.INTERRUPTED, m.state.value.status)
        m.onInterruptionSignal(InterruptionSignal.FocusGained)
        assertEquals(ListeningStatus.INTERRUPTED, m.state.value.status)
        runCurrent()
        assertEquals(SessionStatus.INTERRUPTED, repo.rows.value.single().status)
    }

    @Test fun resumeAfterProcessDeathRestoresPosition() = runTest {
        val first = manager()
        first.start(QuranPosition(18, 1))
        clock.advance(7_000)
        first.setManualPosition(QuranPosition(18, 10, 4))
        runCurrent()

        // New process: fresh manager, same persisted rows.
        val second = manager()
        assertEquals(ListeningStatus.IDLE, second.state.value.status)
        second.resumeSession(1L)
        runCurrent()
        val s = second.state.value
        assertEquals(ListeningStatus.LISTENING, s.status)
        assertEquals(QuranPosition(18, 10, 4), s.position)
        assertEquals(7_000, s.accumulatedMs)
    }

    @Test fun lukmaWithoutAudioReportsUnavailableAndKeepsPosition() = runTest {
        val m = manager()
        m.start(QuranPosition(1, 2, 1))
        m.requestLukma()
        assertTrue(m.state.value.assistance is AssistanceState.Offered)
        m.playHint(HintKind.NEXT_WORD)
        runCurrent()
        assertTrue(m.state.value.assistance is AssistanceState.Unavailable)
        assertEquals(QuranPosition(1, 2, 1), m.state.value.position)
        m.continueReciting()
        assertEquals(AssistanceState.None, m.state.value.assistance)
    }

    @Test fun playbackSuppressesRecognitionWithTail() = runTest {
        val m = manager()
        m.start(QuranPosition.START)
        runCurrent()
        assertTrue(m.state.value.audioGateOpen)
        playback.emitted.emit(PlaybackEvent.Started("hint"))
        runCurrent()
        assertTrue(m.state.value.recognitionSuppressed)
        assertFalse(m.state.value.audioGateOpen)
        playback.emitted.emit(PlaybackEvent.Ended("hint", 0))
        runCurrent()
        assertTrue("tail still suppressed", m.state.value.recognitionSuppressed)
        advanceTimeBy(RecitationSessionManager.PLAYBACK_TAIL_MS + 1)
        assertFalse(m.state.value.recognitionSuppressed)
        assertTrue(m.state.value.audioGateOpen)
    }

    @Test fun resetOnlyClearsStoppedSession() = runTest {
        val m = manager()
        m.start(QuranPosition.START)
        m.reset()
        assertEquals(ListeningStatus.LISTENING, m.state.value.status)
        m.stop()
        m.reset()
        assertEquals(ListeningStatus.IDLE, m.state.value.status)
        assertNull(m.state.value.sessionId)
    }
}
