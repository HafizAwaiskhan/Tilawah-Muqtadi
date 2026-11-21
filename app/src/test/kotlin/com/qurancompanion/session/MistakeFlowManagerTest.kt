package com.qurancompanion.session

import com.qurancompanion.FakeCues
import com.qurancompanion.FakePlayback
import com.qurancompanion.FakeQariAudio
import com.qurancompanion.FakeRecognizer
import com.qurancompanion.FakeSessionRepository
import com.qurancompanion.FakeSettingsRepository
import com.qurancompanion.FixedEngineReference
import com.qurancompanion.SchedulerClock
import com.qurancompanion.core.playback.AyahAudio
import com.qurancompanion.data.db.SessionStatus
import com.qurancompanion.data.settings.MistakeHint
import com.qurancompanion.data.settings.UserSettings
import com.qurancompanion.engine.recognition.RecognitionEvent
import com.qurancompanion.engine.recognition.Token
import com.qurancompanion.engine.reference.QuranReference
import com.qurancompanion.engine.session.OfferReason
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
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mistake → flag (Room) + cues + Lukma offer (full ayah by default), New session, flagged-word replay. */
@OptIn(ExperimentalCoroutinesApi::class)
class MistakeFlowManagerTest {
    private val repo = FakeSessionRepository()
    private val settings = FakeSettingsRepository()
    private val playback = FakePlayback()
    private val qari = FakeQariAudio()
    private val recognizer = FakeRecognizer()
    private val cues = FakeCues()
    private val ref: QuranReference = syntheticReference(surahs = 3, ayahs = 6, words = 12)

    private fun TestScope.manager(): RecitationSessionManager {
        val m = RecitationSessionManager(
            repo, settings, playback, qari, SchedulerClock(testScheduler), FixedEngineReference(ref),
            recognizer, backgroundScope, StandardTestDispatcher(testScheduler), cues,
        )
        backgroundScope.launch { m.runRecognizer(recognizer, { silentFrames() }) }
        runCurrent()
        return m
    }

    private fun TestScope.sayText(m: RecitationSessionManager, text: String) {
        val start = testScheduler.currentTime
        advanceTimeBy(500)
        m.onRecognitionEvent(RecognitionEvent(listOf(Token(text, start, testScheduler.currentTime, 0.95f)), true, testScheduler.currentTime))
        advanceTimeBy(150)
    }

    private fun TestScope.say(m: RecitationSessionManager, surah: Int, ayah: Int, words: IntRange) {
        val first = ref.ayahWords(surah, ayah)!!.first
        for (w in words) sayText(m, ref.word(first + w - 1).matchKey)
        runCurrent()
    }

    /** 2:3 with word 6 replaced by a wrong token, then 2:4 — the mistake surfaces. */
    private fun TestScope.reciteWithMistake(m: RecitationSessionManager) {
        m.start(); runCurrent()
        say(m, 2, 3, 1..5)
        sayText(m, "qqqqqq")
        say(m, 2, 3, 7..12)
        say(m, 2, 4, 1..4)
        advanceTimeBy(9_000); runCurrent()
    }

    @Test fun mistakeIsFlaggedPersistedCuedAndOffered() = runTest {
        val m = manager()
        reciteWithMistake(m)
        val s = m.state.value
        val flag = s.flagged.single()
        assertEquals(Triple(2, 3, 6), Triple(flag.surah, flag.ayah, flag.wordIndex))
        assertEquals("SUBSTITUTION", flag.type)
        val row = repo.flags.value.single()
        assertEquals(1L, row.sessionId)
        assertEquals(Triple(2, 3, 6), Triple(row.surah, row.ayah, row.wordIndex))
        assertEquals(OfferReason.MISTAKE, s.engine.pendingOffer?.reason)
        assertEquals(EngineState.WAITING_FOR_CONFIRMATION, s.state)
        assertEquals(1, cues.vibrations) // one cue for mistake + offer
        assertEquals(0, cues.tones)      // soft tone is off by default
        assertTrue(playback.played.isEmpty()) // nothing plays without confirmation

        // Deleting the session deletes its flags.
        m.stop(); runCurrent()
        repo.delete(1L)
        assertTrue(repo.flags.value.isEmpty())
    }

    @Test fun acceptingMistakeOfferPlaysFullAyahThenResumesAtAyahStart() = runTest {
        qari.audio = AyahAudio("file://a.mp3", startMs = 0, endMs = 9_000, wordTimings = (0 until 12).map { it * 700L to it * 700L + 650 })
        val m = manager()
        reciteWithMistake(m)
        m.acceptOffer(); runCurrent()
        assertEquals(EngineState.PLAYING_FULL_AYAH, m.state.value.state)
        assertEquals(Triple("hint-FULL_AYAH", 0L, 9_000L as Long?), playback.played.single())
        assertEquals(2 to 3, qari.requests.last())
        assertFalse(m.state.value.audioGateOpen)
        playback.finish("hint-FULL_AYAH"); runCurrent()
        assertEquals(EngineState.WAITING_FOR_RETRY, m.state.value.state)
        assertEquals(ref.ayahWords(2, 3)!!.first, m.state.value.engine.nextWordId)
        advanceTimeBy(RecitationSessionManager.PLAYBACK_TAIL_MS + 1)
        say(m, 2, 3, 1..12)
        assertEquals(EngineState.RECITING, m.state.value.state)
        assertEquals(1, m.state.value.flagged.size)
    }

    @Test fun correctWordOnlySettingPlaysTheWordClip() = runTest {
        settings.state.value = UserSettings(mistakeHint = MistakeHint.CORRECT_WORD)
        qari.audio = AyahAudio("file://a.mp3", wordTimings = (0 until 12).map { it * 700L to it * 700L + 650 })
        val m = manager()
        reciteWithMistake(m)
        m.acceptOffer(); runCurrent()
        assertEquals(EngineState.PLAYING_LUKMA, m.state.value.state)
        assertEquals(Triple("hint-NEXT_WORD", 3_500L, 4_150L as Long?), playback.played.single()) // word 6
    }

    @Test fun offlineWithoutDownloadShowsGentleNotice() = runTest {
        qari.offline = true
        val m = manager()
        reciteWithMistake(m)
        m.acceptOffer(); runCurrent()
        assertTrue(m.state.value.notice!!.contains("offline"))
        assertEquals(EngineState.WAITING_FOR_RETRY, m.state.value.state)
        assertTrue(playback.played.isEmpty())
    }

    @Test fun cuesFollowSettings_toneSuppressesRecognition() = runTest {
        settings.state.value = UserSettings(vibrateOnCue = false, softTone = true)
        val m = manager()
        m.start(); runCurrent()
        say(m, 2, 3, 1..5)
        sayText(m, "qqqqqq")
        say(m, 2, 3, 7..12)
        say(m, 2, 4, 1..2)
        // run in small steps until the mistake surfaces, then check the suppression window
        var waited = 0L
        while (m.state.value.flagged.isEmpty() && waited < 10_000) { advanceTimeBy(50); runCurrent(); waited += 50 }
        assertEquals(1, m.state.value.flagged.size)
        assertEquals(0, cues.vibrations)
        assertTrue(m.state.value.recognitionSuppressed)
        assertFalse(m.state.value.audioGateOpen)
        advanceTimeBy(RecitationSessionManager.TONE_LEAD_MS + 1); runCurrent()
        assertEquals(1, cues.tones)
        advanceTimeBy(1_000); runCurrent()
        assertFalse(m.state.value.recognitionSuppressed)
    }

    @Test fun newSessionEndsCurrentAndStartsFreshLocating() = runTest {
        val m = manager()
        m.start(); runCurrent()
        say(m, 2, 3, 1..5)
        m.pause(); runCurrent()
        m.startNewSession(); runCurrent()
        val s = m.state.value
        assertEquals(EngineState.LOCATING_POSITION, s.state)
        assertEquals(ListeningStatus.LISTENING, s.status)
        assertFalse(s.restarting)
        assertEquals(2L, s.sessionId)
        assertEquals(listOf(SessionStatus.COMPLETED, SessionStatus.ACTIVE), repo.rows.value.sortedBy { it.id }.map { it.status })
        assertEquals(3, repo.rows.value.first { it.id == 1L }.lastAyah)
        assertTrue(s.flagged.isEmpty())
        // still works as a plain start when nothing runs
        m.stop(); runCurrent()
        m.startNewSession(); runCurrent()
        assertEquals(3L, m.state.value.sessionId)
    }

    @Test fun tappedFlagReplaysItsAyahWhileListening() = runTest {
        qari.audio = AyahAudio("file://a.mp3", endMs = 9_000)
        val m = manager()
        reciteWithMistake(m)
        m.declineOffer(); runCurrent()
        m.playFlaggedAyah(2, 3, 6); runCurrent()
        assertEquals(EngineState.PLAYING_FULL_AYAH, m.state.value.state)
        assertNotNull(playback.played.singleOrNull())
        playback.finish("hint-FULL_AYAH"); runCurrent()
        assertEquals(ref.ayahWords(2, 3)!!.first, m.state.value.engine.nextWordId)
    }

    @Test fun smartAssistanceOffStillFlagsButDeclinesTheOffer() = runTest {
        settings.state.value = UserSettings(smartAssistance = false)
        val m = manager()
        reciteWithMistake(m)
        assertEquals(1, m.state.value.flagged.size)
        assertEquals(null, m.state.value.engine.pendingOffer)
        assertEquals(1, cues.vibrations)
    }
}
