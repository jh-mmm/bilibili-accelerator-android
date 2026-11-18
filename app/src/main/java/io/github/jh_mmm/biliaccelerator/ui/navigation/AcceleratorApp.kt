package io.github.jh_mmm.biliaccelerator.ui.navigation

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.jh_mmm.biliaccelerator.core.AcceleratorConfig
import io.github.jh_mmm.biliaccelerator.core.RewriteLogEntry
import io.github.jh_mmm.biliaccelerator.core.StatsSnapshot
import io.github.jh_mmm.biliaccelerator.ui.MainActivity.ActivationState
import io.github.jh_mmm.biliaccelerator.ui.about.AboutScreen
import io.github.jh_mmm.biliaccelerator.ui.components.AcceleratorBottomBar
import io.github.jh_mmm.biliaccelerator.ui.dashboard.DashboardScreen
import io.github.jh_mmm.biliaccelerator.ui.settings.SettingsScreen
import io.github.jh_mmm.biliaccelerator.ui.theme.UiPreferences
import io.github.jh_mmm.biliaccelerator.ui.theme.UiStyle
import io.github.jh_mmm.biliaccelerator.ui.theme.resolveUseFloatingBottomBar

@Composable
fun AcceleratorApp(
    state: ActivationState,
    snapshot: StatsSnapshot,
    config: AcceleratorConfig,
    onConfigChange: (AcceleratorConfig) -> Unit,
    uiPreferences: UiPreferences,
    onUiPreferencesChange: (UiPreferences) -> Unit,
    onClearStats: () -> Unit,
    onExportDiagnostic: () -> Unit,
    onRefresh: () -> Unit,
    onLogClick: (RewriteLogEntry) -> Unit,
    uposEntries: List<String>,
    uposValues: List<String>,
    versionName: String,
    versionCode: Long
) {
    var currentDestination by rememberSaveable { mutableStateOf(TopLevelDestination.DASHBOARD) }
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val useFloating = resolveUseFloatingBottomBar(uiPreferences.style, uiPreferences.bottomBarStyle)
    val bottomContentPadding = navBarBottom + if (useFloating) 98.dp else 88.dp

    Box(modifier = Modifier.fillMaxSize()) {
        Crossfade(
            targetState = currentDestination,
            animationSpec = if (uiPreferences.style == UiStyle.MATERIAL3_EXPRESSIVE) {
                MaterialTheme.motionScheme.defaultEffectsSpec()
            } else {
                tween()
            },
            label = "tab_crossfade",
            modifier = Modifier.fillMaxSize()
        ) { destination ->
            when (destination) {
                TopLevelDestination.DASHBOARD -> {
                    DashboardScreen(
                        state = state,
                        snapshot = snapshot,
                        config = config,
                        onRefresh = onRefresh,
                        onLogClick = onLogClick,
                        uposEntries = uposEntries,
                        uposValues = uposValues,
                        bottomContentPadding = bottomContentPadding
                    )
                }
                TopLevelDestination.SETTINGS -> {
                    SettingsScreen(
                        config = config,
                        onConfigChange = onConfigChange,
                        uiPreferences = uiPreferences,
                        onUiPreferencesChange = onUiPreferencesChange,
                        onClearStats = onClearStats,
                        onExportDiagnostic = onExportDiagnostic,
                        uposEntries = uposEntries,
                        uposValues = uposValues,
                        bottomContentPadding = bottomContentPadding
                    )
                }
                TopLevelDestination.ABOUT -> {
                    AboutScreen(
                        versionName = versionName,
                        versionCode = versionCode,
                        bottomContentPadding = bottomContentPadding
                    )
                }
            }
        }

        AcceleratorBottomBar(
            destinations = TopLevelDestination.values().toList(),
            currentDestination = currentDestination,
            onNavigateToDestination = { currentDestination = it },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}
