package io.github.jh_mmm.biliaccelerator.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.jh_mmm.biliaccelerator.R

enum class TopLevelDestination(
    val title: String,
    @StringRes val titleRes: Int,
    val unselectedIcon: ImageVector,
    val selectedIcon: ImageVector
) {
    DASHBOARD(
        title = "看板",
        titleRes = R.string.title_dashboard,
        unselectedIcon = Icons.Outlined.Dashboard,
        selectedIcon = Icons.Filled.Dashboard
    ),
    SETTINGS(
        title = "设置",
        titleRes = R.string.title_settings,
        unselectedIcon = Icons.Outlined.Tune,
        selectedIcon = Icons.Filled.Tune
    ),
    ABOUT(
        title = "关于",
        titleRes = R.string.title_about,
        unselectedIcon = Icons.Outlined.Info,
        selectedIcon = Icons.Filled.Info
    )
}
