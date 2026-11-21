package com.qurancompanion.ui.quran

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qurancompanion.data.reference.ReferenceStatus
import com.qurancompanion.ui.components.AyahWords
import com.qurancompanion.ui.components.JumpDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SurahListScreen(
    onOpenSurah: (surah: Int, ayah: Int) -> Unit,
    vm: SurahListViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showJump by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Quran") },
                actions = {
                    IconButton(onClick = { showJump = true }) { Icon(Icons.Filled.Search, contentDescription = "Go to surah and ayah") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val st = state.status) {
                ReferenceStatus.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                ReferenceStatus.Missing -> Message(
                    "The Quran text database is not included in this build yet. It is produced by the " +
                        "data pipeline (data/build/quran_ref_v1.db) and bundled automatically when present."
                )
                is ReferenceStatus.Failed -> Message("Could not open the Quran text: ${st.message}")
                is ReferenceStatus.Ready -> LazyColumn {
                    if (state.bookmarks.isNotEmpty()) {
                        item { SectionHeader("Bookmarks") }
                        items(state.bookmarks, key = { "b${it.id}" }) { b ->
                            val name = state.surahs.firstOrNull { it.id == b.surah }?.nameTransliterated.orEmpty()
                            ListItem(
                                headlineContent = { Text("$name ${b.surah}:${b.ayah}") },
                                leadingContent = { Icon(Icons.Filled.Bookmark, contentDescription = null) },
                                modifier = Modifier.clickable { onOpenSurah(b.surah, b.ayah) },
                            )
                        }
                        item { SectionHeader("Surahs") }
                    }
                    items(state.surahs, key = { it.id }) { s ->
                        ListItem(
                            leadingContent = { Text("${s.id}", style = MaterialTheme.typography.titleMedium) },
                            headlineContent = { Text(s.nameTransliterated) },
                            supportingContent = { Text("${s.nameEnglish} · ${s.ayahCount} ayahs") },
                            trailingContent = { Text(s.nameArabic, style = MaterialTheme.typography.titleLarge) },
                            modifier = Modifier.clickable { onOpenSurah(s.id, -1) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
    if (showJump) {
        JumpDialog(state.surahs, onDismiss = { showJump = false }) { s, a ->
            showJump = false
            onOpenSurah(s, a)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AyahListScreen(
    onBack: () -> Unit,
    onOpenRecitation: (surah: Int, ayah: Int) -> Unit,
    onJump: (surah: Int, ayah: Int) -> Unit,
    vm: AyahListViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showJump by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    LaunchedEffect(state.loading, state.scrollToAyah) {
        val target = state.scrollToAyah
        if (!state.loading && target != null) {
            val idx = state.ayahs.indexOfFirst { it.ayah == target }
            if (idx >= 0) listState.scrollToItem(idx)
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.surah?.let { "${it.id}. ${it.nameTransliterated}" } ?: "Surah ${vm.surahId}")
                        state.surah?.let { Text(it.nameEnglish, style = MaterialTheme.typography.bodySmall) }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = { showJump = true }) { Icon(Icons.Filled.Search, contentDescription = "Go to surah and ayah") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.ayahs.isEmpty() -> Message("No text available for this surah (reference data not installed).")
                else -> LazyColumn(state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
                    items(state.ayahs, key = { it.ayah }) { ayah ->
                        val bookmarked = ayah.ayah in state.bookmarkedAyahs
                        Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Column(Modifier.padding(12.dp)) {
                                AyahWords(
                                    words = ayah.words,
                                    highlightWord = state.highlightFor(ayah),
                                    ayahNumber = ayah.ayah,
                                    onWordClick = { w -> vm.setPosition(ayah.ayah, w.index) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("${ayah.surah}:${ayah.ayah}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                                    IconButton(onClick = {
                                        if (!vm.setPosition(ayah.ayah)) onOpenRecitation(ayah.surah, ayah.ayah)
                                        else onOpenRecitation(-1, -1)
                                    }) {
                                        Icon(Icons.Filled.MyLocation, contentDescription = "Recite from here")
                                    }
                                    IconButton(onClick = { vm.toggleBookmark(ayah.ayah) }) {
                                        Icon(
                                            if (bookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                                            contentDescription = if (bookmarked) "Remove bookmark" else "Bookmark",
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (showJump) {
        JumpDialog(state.allSurahs, onDismiss = { showJump = false }) { s, a ->
            showJump = false
            onJump(s, a)
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
}

@Composable
internal fun Message(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
