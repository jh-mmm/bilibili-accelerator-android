package io.github.jh_mmm.biliaccelerator.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.ui.graphics.vector.ImageVector

enum class TopLevelDestination(
    val title: String,
    val unselectedIcon: ImageVector,
    val selectedIcon: ImageVector
) {
    DASHBOARD(
        title = "看板",
        unselectedIcon = Icons.Outlined.Dashboard,
        selectedIcon = Icons.Filled.Dashboard
    ),
    SETTINGS(
        title = "设置",
        unselectedIcon = Icons.Outlined.Tune,
        selectedIcon = Icons.Filled.Tune
    ),
    ABOUT(
        title = "关于",
        unselectedIcon = Icons.Outlined.Info,
        selectedIcon = Icons.Filled.Info
    )
}
