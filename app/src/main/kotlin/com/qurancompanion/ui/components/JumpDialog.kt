package com.qurancompanion.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import com.qurancompanion.data.reference.SurahInfo

/** Jump to surah:ayah. Validates against the reference surah table when available. */
@Composable
fun JumpDialog(
    surahs: List<SurahInfo>,
    onDismiss: () -> Unit,
    onJump: (surah: Int, ayah: Int) -> Unit,
) {
    var surahText by rememberSaveable { mutableStateOf("") }
    var ayahText by rememberSaveable { mutableStateOf("1") }
    val surah = surahText.toIntOrNull()
    val ayah = ayahText.toIntOrNull()
    val info = surahs.firstOrNull { it.id == surah }
    val valid = surah != null && ayah != null && surah in 1..114 && ayah >= 1 &&
        (info == null || ayah <= info.ayahCount)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Go to") },
        text = {
            Column {
                OutlinedTextField(
                    surahText, { surahText = it.filter(Char::isDigit).take(3) },
                    label = { Text("Surah (1–114)") },
                    supportingText = { info?.let { Text("${it.nameTransliterated} · ${it.ayahCount} ayahs") } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
                )
                OutlinedTextField(
                    ayahText, { ayahText = it.filter(Char::isDigit).take(3) },
                    label = { Text("Ayah") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onJump(surah!!, ayah!!) }) { Text("Go") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
