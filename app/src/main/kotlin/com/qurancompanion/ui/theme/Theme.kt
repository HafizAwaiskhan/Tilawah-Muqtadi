package com.qurancompanion.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

private val Green = Color(0xFF1B5E20)
private val LightColors = lightColorScheme(primary = Green, secondary = Color(0xFF4E6352))
private val DarkColors = darkColorScheme(primary = Color(0xFF8BD18F), secondary = Color(0xFFB5CCB8))

/**
 * Style for Quran words. Uses the system Arabic font for now.
 * TODO(font): bundle a licensed Indo-Pak Nastaleeq/Naskh font once the data pipeline documents
 * one whose license permits redistribution (see docs/ANDROID.md).
 */
val QuranTextStyle = TextStyle(fontFamily = FontFamily.Default, fontSize = 28.sp, lineHeight = 52.sp)

@Composable
fun QuranCompanionTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
