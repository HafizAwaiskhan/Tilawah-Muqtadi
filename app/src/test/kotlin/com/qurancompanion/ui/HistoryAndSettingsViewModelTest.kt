package com.qurancompanion.ui

import com.qurancompanion.FakeRecognizer
import com.qurancompanion.FixedEngineReference
import com.qurancompanion.SchedulerClock
import com.qurancompanion.syntheticReference
import com.qurancompanion.FakePlayback
import com.qurancompanion.FakeQariAudio
import com.qurancompanion.FakeSessionRepository
import com.qurancompanion.FakeSettingsRepository
import com.qurancompanion.data.reference.AyahText
import com.qurancompanion.data.reference.QuranPosition
import com.qurancompanion.data.reference.QuranReferenceRepository
import com.qurancompanion.data.reference.ReferenceStatus
import com.qurancompanion.data.reference.Script
import com.qurancompanion.data.reference.SurahInfo
import com.qurancompanion.data.settings.RecitationMode
import com.qurancompanion.data.settings.UserSettings
import com.qurancompanion.session.RecitationSessionManager
import com.qurancompanion.ui.history.HistoryViewModel
import com.qurancompanion.ui.settings.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import androidx.lifecycle.SavedStateHandle
import com.qurancompanion.data.db.FlaggedWordEntity
import com.qurancompanion.data.db.SessionEntity
import com.qurancompanion.data.db.SessionStatus
import com.qurancompanion.data.settings.MistakeHint
import com.qurancompanion.ui.history.HistoryDetailViewModel

private class FakeReference : QuranReferenceRepository {
    override val status: StateFlow<ReferenceStatus> = MutableStateFlow(ReferenceStatus.Missing)
    override suspend fun ensureReady(): ReferenceStatus = status.value
    override suspend fun surahs(): List<SurahInfo> = emptyList()
    override suspend fun surah(id: Int): SurahInfo? = null
    override suspend fun ayahs(surah: Int, script: Script): List<AyahText> = emptyList()
    override suspend fun ayah(surah: Int, ayah: Int, script: Script): AyahText? = null
    override suspend fun meta(key: String): String? =
        if (key == QuranReferenceRepository.META_ATTRIBUTION_TANZIL) "ATTRIBUTION" else null
}

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryAndSettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun historyDeletesButKeepsLiveSession() = runTest(dispatcher) {
        val repo = FakeSessionRepository()
        val manager = RecitationSessionManager(
            repo, FakeSettingsRepository(), FakePlayback(), FakeQariAudio(), SchedulerClock(testScheduler),
            FixedEngineReference(syntheticReference()), FakeRecognizer(), backgroundScope, dispatcher,
        )
        runCurrent()
        val vm = HistoryViewModel(repo, manager)
        backgroundScope.launch { vm.state.collect {} }

        manager.start(QuranPosition.START); runCurrent(); manager.stop(); runCurrent()
        manager.reset()
        manager.start(QuranPosition(2, 1)); runCurrent(); manager.stop(); runCurrent()
        manager.reset()
        manager.start(QuranPosition(3, 1)); runCurrent()
        assertEquals(3, vm.state.value.sessions.size)
        assertEquals(3L, vm.state.value.liveSessionId)

        vm.delete(1L); runCurrent()
        assertEquals(listOf(2L, 3L), repo.rows.value.map { it.id })
        vm.delete(3L); runCurrent() // live: ignored
        assertEquals(2, repo.rows.value.size)
        vm.deleteAll(); runCurrent()
        assertEquals(listOf(3L), repo.rows.value.map { it.id })
    }

    @Test fun settingsUpdatesAreClampedAndPersisted() = runTest(dispatcher) {
        val settings = FakeSettingsRepository()
        val vm = SettingsViewModel(settings, FakeSessionRepository(), FakeReference())
        vm.setPlaybackSpeed(9f)
        vm.setPlaybackVolume(-1f)
        vm.setMode(RecitationMode.MEMORIZATION)
        vm.setPreferredQari("  ")
        vm.setKeepHistory(false)
        vm.setMistakeHint(MistakeHint.CORRECT_WORD)
        vm.setMarkFlaggedWords(false)
        vm.setVibrateOnCue(false)
        vm.setSoftTone(true)
        runCurrent()
        val s = settings.state.value
        assertEquals(UserSettings.SPEED_RANGE.endInclusive, s.playbackSpeed)
        assertEquals(0f, s.playbackVolume)
        assertEquals(RecitationMode.MEMORIZATION, s.mode)
        assertEquals(null, s.preferredQariId)
        assertTrue(!s.keepHistory)
        assertEquals(MistakeHint.CORRECT_WORD, s.mistakeHint)
        assertEquals(Triple(false, false, true), Triple(s.markFlaggedWords, s.vibrateOnCue, s.softTone))
        assertEquals("ATTRIBUTION", vm.attribution.value)
    }

    @Test fun newSettingDefaults() {
        val d = UserSettings()
        assertEquals(MistakeHint.FULL_AYAH, d.mistakeHint)
        assertTrue(d.markFlaggedWords && d.vibrateOnCue && !d.softTone)
    }

    @Test fun historyDetailShowsFlagsAndDeleteRemovesThem() = runTest(dispatcher) {
        val repo = FakeSessionRepository()
        val id = repo.create(SessionEntity(startedAt = 1, startSurah = 2, startAyah = 1, lastSurah = 2, lastAyah = 5, status = SessionStatus.COMPLETED))
        val other = repo.create(SessionEntity(startedAt = 2, startSurah = 3, startAyah = 1, lastSurah = 3, lastAyah = 2, status = SessionStatus.COMPLETED))
        repo.addFlag(FlaggedWordEntity(sessionId = id, surah = 2, ayah = 3, wordIndex = 6, mistakeType = "SUBSTITUTION", flaggedAt = 10))
        repo.addFlag(FlaggedWordEntity(sessionId = other, surah = 3, ayah = 1, wordIndex = 1, mistakeType = "DELETION", flaggedAt = 11))
        val vm = HistoryDetailViewModel(SavedStateHandle(mapOf("id" to id)), repo)
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        assertEquals(id, vm.state.value.session?.id)
        assertEquals(listOf(Triple(2, 3, 6)), vm.state.value.flags.map { Triple(it.surah, it.ayah, it.wordIndex) })
        repo.delete(id); runCurrent()
        assertEquals(null, vm.state.value.session)
        assertTrue(vm.state.value.flags.isEmpty())
        assertEquals(1, repo.flags.value.size)
    }
}
