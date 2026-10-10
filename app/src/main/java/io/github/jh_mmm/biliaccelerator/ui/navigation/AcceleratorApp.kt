package io.github.jh_mmm.biliaccelerator.ui.navigation

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Scaffold
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

    Box(modifier = Modifier.fillMaxSize()) {
        Crossfade(
            targetState = currentDestination,
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
                        bottomContentPadding = 88.dp
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
                        bottomContentPadding = 88.dp
                    )
                }
                TopLevelDestination.ABOUT -> {
                    AboutScreen(
                        versionName = versionName,
                        versionCode = versionCode,
                        bottomContentPadding = 88.dp
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
