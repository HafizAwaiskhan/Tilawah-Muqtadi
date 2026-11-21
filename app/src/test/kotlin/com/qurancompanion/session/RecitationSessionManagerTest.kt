package com.qurancompanion.session

import android.net.Uri
import com.qurancompanion.FakePlayback
import com.qurancompanion.FakeQariAudio
import com.qurancompanion.FakeRecognizer
import com.qurancompanion.FakeSessionRepository
import com.qurancompanion.FakeSettingsRepository
import com.qurancompanion.FixedEngineReference
import com.qurancompanion.SchedulerClock
import com.qurancompanion.core.audio.InterruptionSignal
import com.qurancompanion.core.playback.AyahAudio
import com.qurancompanion.data.db.SessionStatus
import com.qurancompanion.data.reference.QuranPosition
import com.qurancompanion.data.settings.UserSettings
import com.qurancompanion.engine.recognition.RecognitionEvent
import com.qurancompanion.engine.recognition.Token
import com.qurancompanion.engine.reference.QuranReference
import com.qurancompanion.engine.session.SessionState as EngineState
import com.qurancompanion.silentFrames
import com.qurancompanion.syntheticReference
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The manager runs in backgroundScope (never idle: 250 ms ticks), so tests use runCurrent()/advanceTimeBy().
@OptIn(ExperimentalCoroutinesApi::class)
class RecitationSessionManagerTest {
    private val repo = FakeSessionRepository()
    private val settings = FakeSettingsRepository()
    private val playback = FakePlayback()
    private val qari = FakeQariAudio()
    private val recognizer = FakeRecognizer()
    private val ref: QuranReference = syntheticReference()

    private fun TestScope.manager(bind: Boolean = true): RecitationSessionManager {
        val m = RecitationSessionManager(
            repo, settings, playback, qari, SchedulerClock(testScheduler), FixedEngineReference(ref),
            recognizer, backgroundScope, StandardTestDispatcher(testScheduler),
        )
        if (bind) backgroundScope.launch { m.runRecognizer(recognizer, { silentFrames() }) }
        runCurrent()
        return m
    }

    private fun TestScope.say(m: RecitationSessionManager, surah: Int, ayah: Int, words: IntRange) {
        for (w in words) {
            val start = testScheduler.currentTime
            advanceTimeBy(500)
            m.onRecognitionEvent(RecognitionEvent(listOf(Token("s${surah}a${ayah}w$w", start, testScheduler.currentTime, 0.95f)), true, testScheduler.currentTime))
            advanceTimeBy(150)
        }
        runCurrent()
    }

    @Test fun readinessAndLocatingFromRecitation() = runTest {
        val m = manager()
        assertEquals(TrackingReadiness.READY, m.state.value.readiness)
        m.start()
        runCurrent()
        assertEquals(EngineState.LOCATING_POSITION, m.state.value.state)
        assertEquals(ListeningStatus.LISTENING, m.state.value.status)
        assertEquals(1L, m.state.value.sessionId)
        say(m, 2, 3, 1..4)
        val s = m.state.value
        assertEquals(EngineState.RECITING, s.state)
        assertEquals(2, s.engine.confirmed!!.surah)
        assertEquals(3, s.engine.confirmed!!.ayah)
        val row = repo.rows.value.single()
        assertEquals(Triple(2, 3, s.engine.confirmed!!.wordIndex), Triple(row.lastSurah, row.lastAyah, row.lastWord))
        assertEquals(2 to 3, row.startSurah to row.startAyah) // start updated to the located ayah
    }

    @Test fun unavailableReferenceReportsReadiness() = runTest {
        val m = RecitationSessionManager(
            repo, settings, playback, qari, SchedulerClock(testScheduler), FixedEngineReference(null),
            recognizer, backgroundScope, StandardTestDispatcher(testScheduler),
        )
        runCurrent()
        assertEquals(TrackingReadiness.UNAVAILABLE, m.state.value.readiness)
        m.start(); runCurrent()
        assertTrue(repo.rows.value.isEmpty())
    }

    @Test fun pauseExcludesPausedTimeFromDuration() = runTest {
        val m = manager()
        m.start(); runCurrent()
        advanceTimeBy(10_000)
        m.pause(); runCurrent()
        assertEquals(ListeningStatus.PAUSED, m.state.value.status)
        assertFalse(m.state.value.audioGateOpen)
        assertEquals(SessionStatus.PAUSED, repo.rows.value.single().status)
        advanceTimeBy(60_000)
        m.resume(); runCurrent()
        advanceTimeBy(5_000)
        m.stop(); runCurrent()
        assertEquals(ListeningStatus.STOPPED, m.state.value.status)
        val row = repo.rows.value.single()
        assertEquals(SessionStatus.COMPLETED, row.status)
        assertEquals(15_000.0, row.durationMs.toDouble(), 300.0)
        assertNotNull(row.endedAt)
        m.reset()
        assertEquals(ListeningStatus.IDLE, m.state.value.status)
    }

    @Test fun stopWithoutKeepHistoryDeletesSession() = runTest {
        settings.state.value = UserSettings(keepHistory = false)
        val m = manager()
        m.start(); runCurrent()
        m.stop(); runCurrent()
        assertTrue(repo.rows.value.isEmpty())
    }

    @Test fun manualPositionIsRecoveryInput() = runTest {
        val m = manager()
        m.setManualPosition(QuranPosition(3, 1, 2)); runCurrent()
        assertNull(m.state.value.engine.confirmed) // no session: ignored
        m.start(); runCurrent()
        m.setManualPosition(QuranPosition(3, 2, 4)); runCurrent()
        assertEquals(EngineState.RECITING, m.state.value.state)
        assertEquals(QuranPosition(3, 2, 4), m.state.value.position)
        m.stepManualPosition(+1); runCurrent()
        assertEquals(QuranPosition(3, 2, 5), m.state.value.position)
        assertEquals(5, repo.rows.value.single().lastWord)
        // word 0 = start of the ayah
        m.setManualPosition(QuranPosition(1, 4, 0)); runCurrent()
        assertEquals(QuranPosition(1, 4, 1), m.state.value.position)
    }

    @Test fun callInterruptsAndAutoResumes_unplugDoesNot() = runTest {
        val m = manager()
        m.start(); runCurrent()
        m.onInterruptionSignal(InterruptionSignal.CallStarted); runCurrent()
        assertEquals(ListeningStatus.INTERRUPTED, m.state.value.status)
        assertEquals(InterruptionReason.CALL, m.state.value.interruption)
        assertFalse(m.state.value.audioGateOpen)
        m.onInterruptionSignal(InterruptionSignal.CallEnded); runCurrent()
        assertEquals(ListeningStatus.LISTENING, m.state.value.status)
        assertNull(m.state.value.interruption)

        m.onInterruptionSignal(InterruptionSignal.BecomingNoisy); runCurrent()
        assertEquals(ListeningStatus.INTERRUPTED, m.state.value.status)
        m.onInterruptionSignal(InterruptionSignal.FocusGained); runCurrent()
        assertEquals(ListeningStatus.INTERRUPTED, m.state.value.status)
        assertEquals(SessionStatus.INTERRUPTED, repo.rows.value.single().status)
        m.resume(); runCurrent() // the user resumes after unplugging
        assertEquals(ListeningStatus.LISTENING, m.state.value.status)
    }

    @Test fun resumeAfterProcessDeathRestoresPosition() = runTest {
        val first = manager()
        first.start(); runCurrent()
        say(first, 1, 5, 1..4)
        val confirmed = first.state.value.engine.confirmed!!
        advanceTimeBy(2_000)

        // New process: fresh manager, same persisted rows.
        val second = manager()
        assertEquals(ListeningStatus.IDLE, second.state.value.status)
        second.resumeSession(1L); runCurrent()
        val s = second.state.value
        assertEquals(EngineState.RECITING, s.state)
        assertEquals(confirmed.wordId, s.engine.confirmed!!.wordId)
        assertEquals(1L, s.sessionId)
        // continues tracking from there
        say(second, 1, 5, (confirmed.wordIndex + 1)..(confirmed.wordIndex + 2))
        assertTrue(second.state.value.engine.confirmed!!.wordId > confirmed.wordId)
    }

    @Test fun lukmaWithoutAudioShowsNoticeAndResumes() = runTest {
        val m = manager()
        m.start(); runCurrent()
        m.setManualPosition(QuranPosition(1, 2, 1)); runCurrent()
        m.requestLukma(); runCurrent()
        val s = m.state.value
        assertEquals(EngineState.WAITING_FOR_RETRY, s.state)
        assertNotNull(s.notice)
        assertEquals(QuranPosition(1, 2, 1), s.position)
        assertTrue(playback.played.isEmpty())
        m.dismissNotice()
        assertNull(m.state.value.notice)
    }

    @Test fun hintPlaysWordClipAndSuppressesRecognitionWithTail() = runTest {
        qari.audio = AyahAudio(Uri.EMPTY, wordTimings = (0 until 6).map { it * 1000L to it * 1000L + 900 })
        val m = manager()
        m.start(); runCurrent()
        m.setManualPosition(QuranPosition(1, 2, 2)); runCurrent()
        m.requestLukma(); runCurrent()
        assertEquals(EngineState.PLAYING_LUKMA, m.state.value.state)
        assertEquals(Triple("hint-NEXT_WORD", 2000L, 2900L), playback.played.single()) // word 3
        assertTrue(m.state.value.recognitionSuppressed)
        assertFalse(m.state.value.audioGateOpen)
        // tokens arriving during playback never move the position
        say(m, 1, 2, 3..5)
        assertEquals(QuranPosition(1, 2, 2), m.state.value.position)
        playback.finish("hint-NEXT_WORD"); runCurrent()
        assertEquals(EngineState.WAITING_FOR_RETRY, m.state.value.state)
        assertTrue("tail still suppressed", m.state.value.recognitionSuppressed)
        advanceTimeBy(RecitationSessionManager.PLAYBACK_TAIL_MS + 1)
        assertFalse(m.state.value.recognitionSuppressed)
        assertTrue(m.state.value.audioGateOpen)
    }

    @Test fun smartAssistanceOffSuppressesOffers() = runTest {
        settings.state.value = UserSettings(smartAssistance = false)
        val m = manager()
        m.start(); runCurrent()
        m.setManualPosition(QuranPosition(1, 2, 2)); runCurrent()
        advanceTimeBy(20_000); runCurrent()
        assertNull(m.state.value.engine.pendingOffer)
        assertTrue(m.state.value.state != EngineState.WAITING_FOR_CONFIRMATION)
    }
}
