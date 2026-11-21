package com.qurancompanion.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Gentle wording for an engine MistakeType name. */
fun mistakeLabel(type: String): String = when (type) {
    "DELETION" -> "A word may have been missed"
    "ORDER" -> "The word order may have changed"
    "INSERTION" -> "An extra word may have slipped in"
    else -> "A word may have been different"
}

/** One row per flagged word; tapping opens that ayah. */
@Composable
fun FlaggedWordList(
    items: List<Triple<Int, Int, Pair<Int, String>>>,
    onOpenAyah: (surah: Int, ayah: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            if (items.isEmpty()) "No flagged words in this session." else "Flagged words (${items.size}) — tap to open the ayah",
            style = MaterialTheme.typography.titleSmall,
        )
        items.forEach { (surah, ayah, wt) ->
            val (word, type) = wt
            Text(
                "$surah:$ayah · word $word — ${mistakeLabel(type)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().clickable { onOpenAyah(surah, ayah) }.padding(vertical = 8.dp),
            )
        }
    }
}
