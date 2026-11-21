package com.qurancompanion.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.qurancompanion.data.settings.SettingsRepository
import com.qurancompanion.ui.history.HistoryDetailScreen
import com.qurancompanion.ui.history.HistoryScreen
import com.qurancompanion.ui.home.HomeScreen
import com.qurancompanion.ui.onboarding.OnboardingScreen
import com.qurancompanion.ui.quran.AyahListScreen
import com.qurancompanion.ui.quran.SurahListScreen
import com.qurancompanion.ui.recitation.RecitationScreen
import com.qurancompanion.ui.settings.SettingsScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

object Routes {
    const val HOME = "home"
    const val QURAN = "quran"
    const val AYAHS = "quran/{surah}?ayah={ayah}"
    const val RECITATION = "recitation?surah={surah}&ayah={ayah}"
    const val HISTORY = "history"
    const val HISTORY_DETAIL = "history/{id}"
    const val SETTINGS = "settings"

    fun ayahs(surah: Int, ayah: Int = -1) = "quran/$surah?ayah=$ayah"
    fun recitation(surah: Int = -1, ayah: Int = -1) = "recitation?surah=$surah&ayah=$ayah"
    fun historyDetail(id: Long) = "history/$id"
}

@HiltViewModel
class RootViewModel @Inject constructor(private val settings: SettingsRepository) : ViewModel() {
    /** null while loading, then whether onboarding is complete. */
    val onboardingCompleted: StateFlow<Boolean?> = settings.settings
        .map { it.onboardingCompleted }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun completeOnboarding() {
        viewModelScope.launch { settings.update { it.copy(onboardingCompleted = true) } }
    }
}

@Composable
fun AppRoot(
    openRecitationRequest: Boolean,
    onOpenRecitationHandled: () -> Unit,
    vm: RootViewModel = hiltViewModel(),
) {
    val onboarded by vm.onboardingCompleted.collectAsStateWithLifecycle()
    when (onboarded) {
        null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        false -> OnboardingScreen(onFinished = vm::completeOnboarding)
        true -> MainScaffold(openRecitationRequest, onOpenRecitationHandled)
    }
}

private data class TopLevel(val route: String, val label: String, val icon: ImageVector)

private val topLevel = listOf(
    TopLevel(Routes.HOME, "Home", Icons.Filled.Home),
    TopLevel(Routes.QURAN, "Quran", Icons.AutoMirrored.Filled.MenuBook),
    TopLevel(Routes.HISTORY, "History", Icons.Filled.History),
    TopLevel(Routes.SETTINGS, "Settings", Icons.Filled.Settings),
)

@Composable
private fun MainScaffold(openRecitationRequest: Boolean, onOpenRecitationHandled: () -> Unit) {
    val nav = rememberNavController()
    LaunchedEffect(openRecitationRequest) {
        if (openRecitationRequest) {
            nav.navigate(Routes.recitation()) { launchSingleTop = true }
            onOpenRecitationHandled()
        }
    }
    val backStack by nav.currentBackStackEntryAsState()
    Scaffold(
        bottomBar = {
            NavigationBar {
                topLevel.forEach { item ->
                    val selected = backStack?.destination?.hierarchy?.any { d ->
                        d.route == item.route || (item.route == Routes.QURAN && d.route == Routes.AYAHS) ||
                            (item.route == Routes.HISTORY && d.route == Routes.HISTORY_DETAIL)
                    } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = { nav.navigateTopLevel(item.route) },
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Routes.HOME, modifier = Modifier.padding(padding)) {
            composable(Routes.HOME) {
                HomeScreen(
                    onOpenRecitation = { nav.navigate(Routes.recitation()) { launchSingleTop = true } },
                    onOpenQuran = { nav.navigateTopLevel(Routes.QURAN) },
                )
            }
            composable(Routes.QURAN) {
                SurahListScreen(onOpenSurah = { s, a -> nav.navigate(Routes.ayahs(s, a)) })
            }
            composable(
                Routes.AYAHS,
                arguments = listOf(
                    navArgument("surah") { type = NavType.IntType },
                    navArgument("ayah") { type = NavType.IntType; defaultValue = -1 },
                ),
            ) {
                AyahListScreen(
                    onBack = { nav.popBackStack() },
                    onOpenRecitation = { s, a -> nav.navigate(Routes.recitation(s, a)) { launchSingleTop = true } },
                    onJump = { s, a ->
                        nav.navigate(Routes.ayahs(s, a)) { popUpTo(Routes.QURAN) }
                    },
                )
            }
            composable(
                Routes.RECITATION,
                arguments = listOf(
                    navArgument("surah") { type = NavType.IntType; defaultValue = -1 },
                    navArgument("ayah") { type = NavType.IntType; defaultValue = -1 },
                ),
            ) {
                RecitationScreen(
                    onBack = { nav.popBackStack() },
                    onOpenAyah = { s, a -> nav.navigate(Routes.ayahs(s, a)) },
                )
            }
            composable(Routes.HISTORY) { HistoryScreen(onOpenSession = { nav.navigate(Routes.historyDetail(it)) }) }
            composable(
                Routes.HISTORY_DETAIL,
                arguments = listOf(navArgument("id") { type = NavType.LongType }),
            ) {
                HistoryDetailScreen(
                    onBack = { nav.popBackStack() },
                    onOpenAyah = { s, a -> nav.navigate(Routes.ayahs(s, a)) },
                )
            }
            composable(Routes.SETTINGS) { SettingsScreen() }
        }
    }
}

private fun NavHostController.navigateTopLevel(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
