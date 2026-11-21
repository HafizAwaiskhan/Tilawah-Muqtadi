package com.qurancompanion.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.qurancompanion.data.reference.WordText
import com.qurancompanion.ui.theme.QuranTextStyle

/**
 * Renders an ayah word by word, right-to-left. Each word is its own element so the current
 * word can be highlighted (and tapped to set the position manually). Words before
 * [highlightWord] are shown as "recited" (slightly dimmed). Words in [flaggedWords] (surfaced
 * mistakes) are underlined in the error colour; tapping one calls [onFlaggedClick] when given.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AyahWords(
    words: List<WordText>,
    modifier: Modifier = Modifier,
    highlightWord: Int? = null,
    ayahNumber: Int? = null,
    onWordClick: ((WordText) -> Unit)? = null,
    flaggedWords: Set<Int> = emptySet(),
    onFlaggedClick: ((WordText) -> Unit)? = null,
) {
    val flaggedFg = MaterialTheme.colorScheme.error
    val highlightBg = MaterialTheme.colorScheme.primaryContainer
    val highlightFg = MaterialTheme.colorScheme.onPrimaryContainer
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            words.forEach { w ->
                val isCurrent = highlightWord != null && w.index == highlightWord
                val isRecited = highlightWord != null && w.index < highlightWord
                val isFlagged = w.index in flaggedWords
                val click = if (isFlagged && onFlaggedClick != null) onFlaggedClick else onWordClick
                Text(
                    text = w.text,
                    style = if (isFlagged) QuranTextStyle.copy(textDecoration = TextDecoration.Underline) else QuranTextStyle,
                    color = when {
                        isCurrent -> highlightFg
                        isFlagged -> flaggedFg
                        isRecited -> dim
                        else -> Color.Unspecified
                    },
                    modifier = Modifier
                        .then(if (isCurrent) Modifier.background(highlightBg, RoundedCornerShape(6.dp)) else Modifier)
                        .then(if (click != null) Modifier.clickable { click(w) } else Modifier)
                        .semantics {
                            when {
                                isCurrent -> contentDescription = "Current word ${w.index}"
                                isFlagged -> contentDescription = "Flagged word ${w.index}, tap to check"
                            }
                        }
                        .padding(horizontal = 4.dp),
                )
            }
            if (ayahNumber != null) {
                Text(
                    "($ayahNumber)",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
                )
            }
        }
    }
}
