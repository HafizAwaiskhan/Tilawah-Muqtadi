package com.qurancompanion.data.settings

enum class RecitationMode { RECITATION, MEMORIZATION }

data class UserSettings(
    val onboardingCompleted: Boolean = false,
    val smartAssistance: Boolean = true,
    val mode: RecitationMode = RecitationMode.RECITATION,
    val vibration: Boolean = true,
    val playbackSpeed: Float = 1.0f,
    val playbackVolume: Float = 1.0f,
    /** Identifier of the preferred Qari audio set; null until a Qari resource is installed. */
    val preferredQariId: String? = null,
    val keepHistory: Boolean = true,
) {
    companion object {
        val SPEED_RANGE = 0.5f..1.5f
        val VOLUME_RANGE = 0f..1f
    }
}
