package com.qurancompanion.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

interface SettingsRepository {
    val settings: Flow<UserSettings>
    suspend fun update(transform: (UserSettings) -> UserSettings)
}

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "user_settings")

@Singleton
class DataStoreSettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
) : SettingsRepository {

    private val store = context.settingsDataStore

    private object Keys {
        val onboarding = booleanPreferencesKey("onboarding_completed")
        val smartAssistance = booleanPreferencesKey("smart_assistance")
        val mode = stringPreferencesKey("mode")
        val vibration = booleanPreferencesKey("vibration")
        val speed = floatPreferencesKey("playback_speed")
        val volume = floatPreferencesKey("playback_volume")
        val qari = stringPreferencesKey("preferred_qari_id")
        val keepHistory = booleanPreferencesKey("keep_history")
        val devSim = booleanPreferencesKey("dev_sim_recognizer")
        val devSurah = intPreferencesKey("dev_sim_surah")
        val devFrom = intPreferencesKey("dev_sim_from_ayah")
        val devTo = intPreferencesKey("dev_sim_to_ayah")
        val devWordMs = intPreferencesKey("dev_sim_word_ms")
        val devForget = booleanPreferencesKey("dev_sim_forget_pause")
        val devErrors = booleanPreferencesKey("dev_sim_errors")
    }

    override val settings: Flow<UserSettings> = store.data.map { it.toSettings() }

    override suspend fun update(transform: (UserSettings) -> UserSettings) {
        store.edit { prefs ->
            val updated = transform(prefs.toSettings())
            prefs[Keys.onboarding] = updated.onboardingCompleted
            prefs[Keys.smartAssistance] = updated.smartAssistance
            prefs[Keys.mode] = updated.mode.name
            prefs[Keys.vibration] = updated.vibration
            prefs[Keys.speed] = updated.playbackSpeed.coerceIn(UserSettings.SPEED_RANGE)
            prefs[Keys.volume] = updated.playbackVolume.coerceIn(UserSettings.VOLUME_RANGE)
            if (updated.preferredQariId.isNullOrBlank()) prefs.remove(Keys.qari) else prefs[Keys.qari] = updated.preferredQariId
            prefs[Keys.keepHistory] = updated.keepHistory
            val dev = updated.developer
            prefs[Keys.devSim] = dev.simulatedRecognizer
            prefs[Keys.devSurah] = dev.surah
            prefs[Keys.devFrom] = dev.fromAyah
            prefs[Keys.devTo] = dev.toAyah
            prefs[Keys.devWordMs] = dev.wordMs
            prefs[Keys.devForget] = dev.forgetPause
            prefs[Keys.devErrors] = dev.injectErrors
        }
    }

    private fun Preferences.toSettings(): UserSettings {
        val d = UserSettings()
        return UserSettings(
            onboardingCompleted = this[Keys.onboarding] ?: d.onboardingCompleted,
            smartAssistance = this[Keys.smartAssistance] ?: d.smartAssistance,
            mode = this[Keys.mode]?.let { runCatching { RecitationMode.valueOf(it) }.getOrNull() } ?: d.mode,
            vibration = this[Keys.vibration] ?: d.vibration,
            playbackSpeed = this[Keys.speed] ?: d.playbackSpeed,
            playbackVolume = this[Keys.volume] ?: d.playbackVolume,
            preferredQariId = this[Keys.qari],
            keepHistory = this[Keys.keepHistory] ?: d.keepHistory,
            developer = d.developer.let { dd ->
                DeveloperSettings(
                    simulatedRecognizer = this[Keys.devSim] ?: dd.simulatedRecognizer,
                    surah = this[Keys.devSurah] ?: dd.surah,
                    fromAyah = this[Keys.devFrom] ?: dd.fromAyah,
                    toAyah = this[Keys.devTo] ?: dd.toAyah,
                    wordMs = this[Keys.devWordMs] ?: dd.wordMs,
                    forgetPause = this[Keys.devForget] ?: dd.forgetPause,
                    injectErrors = this[Keys.devErrors] ?: dd.injectErrors,
                )
            },
        )
    }
}
