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
        runCurrent()
        val s = settings.state.value
        assertEquals(UserSettings.SPEED_RANGE.endInclusive, s.playbackSpeed)
        assertEquals(0f, s.playbackVolume)
        assertEquals(RecitationMode.MEMORIZATION, s.mode)
        assertEquals(null, s.preferredQariId)
        assertTrue(!s.keepHistory)
        assertEquals("ATTRIBUTION", vm.attribution.value)
    }
}
