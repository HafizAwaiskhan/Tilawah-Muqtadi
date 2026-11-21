package com.qurancompanion.ui.quran

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qurancompanion.core.time.Clock
import com.qurancompanion.data.db.BookmarkEntity
import com.qurancompanion.data.reference.AyahText
import com.qurancompanion.data.reference.QuranPosition
import com.qurancompanion.data.reference.QuranReferenceRepository
import com.qurancompanion.data.reference.ReferenceStatus
import com.qurancompanion.data.reference.SurahInfo
import com.qurancompanion.data.session.BookmarkRepository
import com.qurancompanion.session.RecitationSessionManager
import com.qurancompanion.session.SessionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SurahListUiState(
    val status: ReferenceStatus = ReferenceStatus.Loading,
    val surahs: List<SurahInfo> = emptyList(),
    val bookmarks: List<BookmarkEntity> = emptyList(),
)

@HiltViewModel
class SurahListViewModel @Inject constructor(
    private val reference: QuranReferenceRepository,
    bookmarks: BookmarkRepository,
) : ViewModel() {
    private val surahs = MutableStateFlow<List<SurahInfo>>(emptyList())

    val state: StateFlow<SurahListUiState> =
        combine(reference.status, surahs, bookmarks.observeBookmarks()) { st, list, bm ->
            SurahListUiState(st, list, bm)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SurahListUiState())

    init {
        viewModelScope.launch {
            reference.ensureReady()
            surahs.value = reference.surahs()
        }
    }
}

data class AyahListUiState(
    val loading: Boolean = true,
    val surah: SurahInfo? = null,
    val ayahs: List<AyahText> = emptyList(),
    val allSurahs: List<SurahInfo> = emptyList(),
    val bookmarkedAyahs: Set<Int> = emptySet(),
    val session: SessionState = SessionState(),
    val scrollToAyah: Int? = null,
) {
    /** Word to highlight in [ayah] when the live session is there. */
    fun highlightFor(ayah: AyahText): Int? {
        val p = session.position
        return if (session.status.isRunning && p.surah == ayah.surah && p.ayah == ayah.ayah) p.word.coerceAtLeast(1) else null
    }
}

@HiltViewModel
class AyahListViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val reference: QuranReferenceRepository,
    private val bookmarks: BookmarkRepository,
    private val manager: RecitationSessionManager,
    private val clock: Clock,
) : ViewModel() {
    val surahId: Int = savedState.get<Int>("surah") ?: 1
    private val initialAyah: Int? = savedState.get<Int>("ayah")?.takeIf { it > 0 }

    private val content = MutableStateFlow(AyahListUiState())

    val state: StateFlow<AyahListUiState> = combine(
        content, bookmarks.observeBookmarks(), manager.state,
    ) { c, bm, live ->
        c.copy(bookmarkedAyahs = bm.filter { it.surah == surahId }.map { it.ayah }.toSet(), session = live)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AyahListUiState())

    init {
        viewModelScope.launch {
            val all = reference.surahs()
            content.value = AyahListUiState(
                loading = false,
                surah = all.firstOrNull { it.id == surahId },
                ayahs = reference.ayahs(surahId),
                allSurahs = all,
                scrollToAyah = initialAyah,
            )
        }
    }

    fun toggleBookmark(ayah: Int) {
        viewModelScope.launch {
            if (ayah in state.value.bookmarkedAyahs) bookmarks.remove(surahId, ayah)
            else bookmarks.add(surahId, ayah, clock.nowMillis())
        }
    }

    /**
     * Sets the live session's position manually. Returns false when no session is running
     * (the caller then opens the Recitation screen to start one there).
     */
    fun setPosition(ayah: Int, word: Int = 0): Boolean {
        if (!manager.state.value.status.isRunning) return false
        manager.setManualPosition(QuranPosition(surahId, ayah, word))
        return true
    }
}
