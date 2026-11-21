package com.qurancompanion.data.reference

import kotlinx.coroutines.flow.StateFlow

/**
 * Read-only access to the prebuilt Quran reference database (built offline by tools/, never
 * typed by hand). Implementations must not modify the text.
 */
interface QuranReferenceRepository {
    companion object {
        /** CC BY 3.0 attribution notice that must be displayed (Settings → About). */
        const val META_ATTRIBUTION_TANZIL = "attribution.tanzil"
    }

    val status: StateFlow<ReferenceStatus>

    /** Opens (copying from assets on first run if needed). Safe to call repeatedly. */
    suspend fun ensureReady(): ReferenceStatus

    suspend fun surahs(): List<SurahInfo>
    suspend fun surah(id: Int): SurahInfo?
    suspend fun ayahs(surah: Int, script: Script = Script.INDOPAK): List<AyahText>
    suspend fun ayah(surah: Int, ayah: Int, script: Script = Script.INDOPAK): AyahText?

    /** Value from the `meta` table (e.g. [META_ATTRIBUTION_TANZIL]), or null. */
    suspend fun meta(key: String): String?

    /** Returns the position after [position]'s ayah (next ayah / next surah), or null at the end. */
    suspend fun nextAyah(position: QuranPosition): QuranPosition? {
        val s = surah(position.surah) ?: return null
        return when {
            position.ayah < s.ayahCount -> QuranPosition(position.surah, position.ayah + 1)
            position.surah < 114 -> QuranPosition(position.surah + 1, 1)
            else -> null
        }
    }
}
