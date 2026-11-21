package com.qurancompanion.debug

import com.qurancompanion.FakePlayback
import com.qurancompanion.FakeQariAudio
import com.qurancompanion.FakeSessionRepository
import com.qurancompanion.FakeSettingsRepository
import com.qurancompanion.FixedEngineReference
import com.qurancompanion.RealReferenceDb
import com.qurancompanion.SchedulerClock
import com.qurancompanion.core.playback.AyahAudio
import com.qurancompanion.data.db.SessionStatus
import com.qurancompanion.data.settings.DeveloperSettings
import com.qurancompanion.data.settings.UserSettings
import com.qurancompanion.engine.recognition.RecognitionEvent
import com.qurancompanion.engine.recognition.Token
import com.qurancompanion.engine.session.HintLevel
import com.qurancompanion.engine.session.SessionState as EngineState
import com.qurancompanion.session.RecitationSessionManager
import com.qurancompanion.silentFrames
import kotlinx.coroutines.CoroutineStart
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
import kotlin.time.Duration.Companion.minutes

/**
 * Full pipeline on the real reference DB: simulated recognizer → gated frames → manager →
 * RecitationSessionController → Room fakes, with a virtual clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SimulatedPipelineTest {
    private val dev = DeveloperSettings(simulatedRecognizer = true, surah = 67, fromAyah = 1, toAyah = 6, wordMs = 600)

    private fun TestScope.until(maxMs: Long, what: String, cond: () -> Boolean) {
        var waited = 0L
        while (!cond()) {
            check(waited < maxMs) { "timed out waiting for $what" }
            advanceTimeBy(250); runCurrent(); waited += 250
        }
    }

    @Test fun simulatedSessionEndToEnd() = runTest(timeout = 5.minutes) {
        RealReferenceDb.assumeAvailable()
        val ref = RealReferenceDb.reference
        val refSource = FixedEngineReference(ref)
        val settings = FakeSettingsRepository(UserSettings(developer = dev))
        val repo = FakeSessionRepository()
        val playback = FakePlayback()
        val qari = FakeQariAudio(AyahAudio("file://qari.mp3", wordTimings = (0 until 60).map { it * 800L to it * 800L + 700 }))
        val clock = SchedulerClock(testScheduler)
        val sim = SimulatedRecognitionEngine(refSource, settings.settings, clock, backgroundScope)
        val m = RecitationSessionManager(
            repo, settings, playback, qari, clock, refSource, sim, backgroundScope, StandardTestDispatcher(testScheduler),
        )
        val script = SimulatorScript.build(ref, dev)
        val range = SimulatorScript.wordRange(ref, dev)!!
        val forgetIdx = (1 until script.size).first { script[it].startMs - (script[it - 1].startMs + script[it - 1].durationMs) >= SimulatorScript.FORGET_MS }
        val stableTimes = ArrayList<Long>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            sim.observeRecognition().collect { if (it.isStable) stableTimes += testScheduler.currentTime }
        }
        backgroundScope.launch { m.runRecognizer(sim, { silentFrames() }) }
        runCurrent()

        m.start(); runCurrent()
        assertEquals(EngineState.LOCATING_POSITION, m.state.value.state)

        // 1. Position found in the right ayah.
        until(15_000, "located") { m.state.value.engine.confirmed != null }
        val located = m.state.value.engine.confirmed!!
        assertEquals(67 to 1, located.surah to located.ayah)
        assertTrue(located.wordId in range)

        // 2. Words advance (highlight follows the candidate).
        val trail = ArrayList<Int>()
        until(60_000, "reach forget pause") {
            m.state.value.engine.confirmed?.wordId?.let { if (trail.lastOrNull() != it) trail += it }
            stableTimes.size >= forgetIdx
        }
        assertTrue("advanced: $trail", trail.size >= 5)
        assertNull(m.state.value.engine.pendingOffer)

        // 3. Pause → offer only after the threshold.
        val pauseStart = stableTimes[forgetIdx - 1]
        until(3_000, "3 s of silence") { testScheduler.currentTime >= pauseStart + 3_000 }
        assertNull("no offer after 3 s", m.state.value.engine.pendingOffer)
        until(SimulatorScript.FORGET_MS - 1_000, "offer") { m.state.value.engine.pendingOffer != null }
        val offerAfter = testScheduler.currentTime - pauseStart
        assertTrue("offer after $offerAfter ms", offerAfter >= 5_000)
        assertEquals(EngineState.WAITING_FOR_CONFIRMATION, m.state.value.state)
        val beforeHint = m.state.value.engine.confirmed!!

        // 4. Hint playback window is ignored: the gate closes, the simulator stalls, injected tokens are dropped.
        m.acceptOffer(HintLevel.NEXT_WORD); runCurrent()
        assertEquals(EngineState.PLAYING_LUKMA, m.state.value.state)
        assertEquals(1, playback.played.size)
        assertFalse(m.state.value.audioGateOpen)
        val stableDuringHint = stableTimes.size
        val next = (beforeHint.wordId + 1)..(beforeHint.wordId + 3)
        val now = testScheduler.currentTime
        m.onRecognitionEvent(RecognitionEvent(next.map { Token(ref.word(it).matchKey, now - 300, now, 0.95f) }, true, now))
        advanceTimeBy(2_000); runCurrent()
        assertEquals(beforeHint, m.state.value.engine.confirmed)
        assertEquals(stableDuringHint, stableTimes.size)
        playback.finish(playback.played.last().first); runCurrent()
        assertEquals(EngineState.WAITING_FOR_RETRY, m.state.value.state)

        // 5. Recitation resumes and runs to the end of the range.
        until(120_000, "end of script") { stableTimes.size >= script.size }
        advanceTimeBy(1_000); runCurrent()
        val end = m.state.value.engine.confirmed!!
        assertTrue("resumed past the hint: $end", end.wordId > beforeHint.wordId)
        assertTrue("reached the end: ${end.wordId} vs ${range.last}", end.wordId >= range.last - 1)
        // the self-corrected wrong word is never surfaced
        val wrongWord = script.indexOfFirst { it.note == "wrong word" }
        assertTrue(wrongWord > 0)
        val corrected = range.first + (range.last - range.first + 1).let { maxOf(3, it / 4) }
        assertTrue(m.state.value.engine.feedback.none { it.wordId == corrected && it.type.name == "SUBSTITUTION" })

        // 6. Snapshot persisted.
        val row = repo.rows.value.single()
        assertEquals(67 to 1, row.startSurah to row.startAyah)
        assertEquals(Triple(end.surah, end.ayah, end.wordIndex), Triple(row.lastSurah, row.lastAyah, row.lastWord))
        assertEquals(SessionStatus.ACTIVE, row.status)
        m.stop(); runCurrent()
        val done = repo.rows.value.single()
        assertEquals(SessionStatus.COMPLETED, done.status)
        assertNotNull(done.endedAt)
        assertTrue(done.durationMs > 30_000)
    }

    @Test fun scriptReadsReferenceAndInjectsConfiguredEvents() {
        RealReferenceDb.assumeAvailable()
        val ref = RealReferenceDb.reference
        val range = SimulatorScript.wordRange(ref, dev)!!
        val plain = SimulatorScript.build(ref, dev.copy(forgetPause = false, injectErrors = false))
        assertEquals(range.map { ref.word(it).matchKey }, plain.map { it.text })
        val full = SimulatorScript.build(ref, dev)
        assertEquals(1, full.count { it.note == "wrong word" })
        assertEquals(2, full.count { it.note == "self-correction" })
        assertEquals(plain.size - 1 + 1 + 2, full.size) // one skipped, one wrong, two re-said
        assertTrue(SimulatorScript.build(ref, dev.copy(surah = 999)).isEmpty())
    }
}
