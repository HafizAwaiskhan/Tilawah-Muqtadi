package com.qurancompanion.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qurancompanion.data.reference.QuranReferenceRepository
import com.qurancompanion.data.session.SessionRepository
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
    fun setVibration(v: Boolean) = update { it.copy(vibration = v) }
    fun setPlaybackSpeed(v: Float) = update { it.copy(playbackSpeed = v.coerceIn(UserSettings.SPEED_RANGE)) }
    fun setPlaybackVolume(v: Float) = update { it.copy(playbackVolume = v.coerceIn(UserSettings.VOLUME_RANGE)) }
    fun setPreferredQari(id: String?) = update { it.copy(preferredQariId = id?.trim()?.ifEmpty { null }) }
    fun setKeepHistory(v: Boolean) = update { it.copy(keepHistory = v) }

    fun clearHistory() {
        viewModelScope.launch { sessions.deleteAll() }
    }

    fun replayOnboarding() = update { it.copy(onboardingCompleted = false) }
}
