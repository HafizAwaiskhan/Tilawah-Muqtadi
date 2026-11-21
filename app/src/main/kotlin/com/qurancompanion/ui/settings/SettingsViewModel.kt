package com.qurancompanion.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qurancompanion.BuildConfig
import com.qurancompanion.data.reference.QuranReferenceRepository
import com.qurancompanion.data.settings.DeveloperSettings
import com.qurancompanion.data.session.SessionRepository
import com.qurancompanion.data.settings.MistakeHint
import com.qurancompanion.data.settings.RecitationMode
import com.qurancompanion.data.settings.SettingsRepository
import com.qurancompanion.data.settings.UserSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository,
    private val sessions: SessionRepository,
    private val reference: QuranReferenceRepository,
) : ViewModel() {

    /** Source attribution required by the Tanzil CC BY 3.0 licence, read from the reference DB. */
    val attribution = MutableStateFlow<String?>(null)
    val dataVersion = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            reference.ensureReady()
            attribution.value = reference.meta(QuranReferenceRepository.META_ATTRIBUTION_TANZIL)
            dataVersion.value = reference.meta("data_version")
        }
    }

    val settings: StateFlow<UserSettings> =
        repo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserSettings())

    private fun update(t: (UserSettings) -> UserSettings) {
        viewModelScope.launch { repo.update(t) }
    }

    fun setSmartAssistance(v: Boolean) = update { it.copy(smartAssistance = v) }
    fun setMode(v: RecitationMode) = update { it.copy(mode = v) }
    fun setVibrateOnCue(v: Boolean) = update { it.copy(vibrateOnCue = v) }
    fun setSoftTone(v: Boolean) = update { it.copy(softTone = v) }
    fun setMarkFlaggedWords(v: Boolean) = update { it.copy(markFlaggedWords = v) }
    fun setMistakeHint(v: MistakeHint) = update { it.copy(mistakeHint = v) }
    fun setPlaybackSpeed(v: Float) = update { it.copy(playbackSpeed = v.coerceIn(UserSettings.SPEED_RANGE)) }
    fun setPlaybackVolume(v: Float) = update { it.copy(playbackVolume = v.coerceIn(UserSettings.VOLUME_RANGE)) }
    fun setPreferredQari(id: String?) = update { it.copy(preferredQariId = id?.trim()?.ifEmpty { null }) }
    fun setKeepHistory(v: Boolean) = update { it.copy(keepHistory = v) }

    fun clearHistory() {
        viewModelScope.launch { sessions.deleteAll() }
    }

    /** Developer section (simulated recognizer) is only offered in debug builds. */
    val developerAvailable: Boolean = BuildConfig.DEBUG

    fun updateDeveloper(t: (DeveloperSettings) -> DeveloperSettings) = update {
        val d = t(it.developer)
        it.copy(
            developer = d.copy(
                surah = d.surah.coerceIn(1, 114),
                fromAyah = d.fromAyah.coerceAtLeast(1),
                toAyah = d.toAyah.coerceAtLeast(d.fromAyah.coerceAtLeast(1)),
                wordMs = d.wordMs.coerceIn(200, 2_000),
            )
        )
    }

    fun replayOnboarding() = update { it.copy(onboardingCompleted = false) }
}
