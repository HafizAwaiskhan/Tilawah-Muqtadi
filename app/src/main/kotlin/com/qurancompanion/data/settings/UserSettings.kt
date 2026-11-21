package com.qurancompanion.data.settings

enum class RecitationMode { RECITATION, MEMORIZATION }

/** "On mistake, play": what an accepted mistake offer plays. */
enum class MistakeHint { FULL_AYAH, CORRECT_WORD }

data class UserSettings(
    val onboardingCompleted: Boolean = false,
    val smartAssistance: Boolean = true,
    val mode: RecitationMode = RecitationMode.RECITATION,
    /** Gentle vibration on a surfaced mistake / Lukma offer (replaces the former generic "Vibration"). */
    val vibrateOnCue: Boolean = true,
    /** Short quiet tone on a surfaced mistake / Lukma offer; recognition is suppressed meanwhile. */
    val softTone: Boolean = false,
    /** Underline flagged (surfaced) words in the ayah text; tap shows the expected word. */
    val markFlaggedWords: Boolean = true,
    val mistakeHint: MistakeHint = MistakeHint.FULL_AYAH,
    val playbackSpeed: Float = 1.0f,
    val playbackVolume: Float = 1.0f,
    /** Identifier of the preferred Qari audio set; null = the default (Alafasy test download). */
    val preferredQariId: String? = null,
    val keepHistory: Boolean = true,
    /** Debug builds only (Settings → Developer). Ignored in release builds. */
    val developer: DeveloperSettings = DeveloperSettings(),
) {
    companion object {
        val SPEED_RANGE = 0.5f..1.5f
        val VOLUME_RANGE = 0f..1f
    }
}

/**
 * Simulated recognizer (debug builds): replays the reference's match keys for
 * [surah]:[fromAyah]..[toAyah] at [wordMs] per word, so the pipeline can be exercised without a model.
 */
data class DeveloperSettings(
    val simulatedRecognizer: Boolean = false,
    val surah: Int = 67,
    val fromAyah: Int = 1,
    val toAyah: Int = 6,
    val wordMs: Int = 650,
    /** Insert one long "forgot the next word" pause in the middle of the range (→ Lukma offer). */
    val forgetPause: Boolean = true,
    /** Inject one self-corrected wrong word and one skipped word. */
    val injectErrors: Boolean = true,
)
