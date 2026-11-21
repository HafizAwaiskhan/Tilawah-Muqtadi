package com.qurancompanion.data.reference

data class SurahInfo(
    val id: Int,
    val nameArabic: String,
    val nameTransliterated: String,
    val nameEnglish: String,
    val ayahCount: Int,
)

/** One displayable word. [index] is 1-based within its ayah (the shared (surah, ayah, word) key). */
data class WordText(val index: Int, val text: String)

data class AyahText(
    val surah: Int,
    val ayah: Int,
    val globalIndex: Int,
    val words: List<WordText>,
)

/** A position in the Quran. word = 0 means "at the start of the ayah, no word confirmed yet". */
data class QuranPosition(val surah: Int, val ayah: Int, val word: Int = 0) {
    fun label(): String = if (word > 0) "$surah:$ayah · word $word" else "$surah:$ayah"

    companion object {
        val START = QuranPosition(1, 1, 0)
    }
}

sealed interface ReferenceStatus {
    data object Loading : ReferenceStatus
    /** The reference DB asset is not bundled in this build (data pipeline not run yet). */
    data object Missing : ReferenceStatus
    data class Ready(val version: String?, val surahCount: Int) : ReferenceStatus
    data class Failed(val message: String) : ReferenceStatus
}

/** Which text layer to show. Indo-Pak is the default display script (PHASE0_PLAN §4). */
enum class Script { INDOPAK, UTHMANI }
