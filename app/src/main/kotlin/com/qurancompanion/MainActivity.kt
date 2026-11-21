package com.qurancompanion

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.qurancompanion.ui.navigation.AppRoot
import com.qurancompanion.ui.theme.QuranCompanionTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** Set when the user taps the recitation notification. */
    private val openRecitation = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openRecitation.value = intent?.getBooleanExtra(EXTRA_OPEN_RECITATION, false) == true
        setContent {
            QuranCompanionTheme {
                AppRoot(
                    openRecitationRequest = openRecitation.value,
                    onOpenRecitationHandled = { openRecitation.value = false },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_RECITATION, false)) openRecitation.value = true
    }

    companion object {
        const val EXTRA_OPEN_RECITATION = "open_recitation"
    }
}
