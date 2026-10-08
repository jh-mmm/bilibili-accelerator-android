package io.github.jh_mmm.biliaccelerator.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jh_mmm.biliaccelerator.ui.navigation.TopLevelDestination
import io.github.jh_mmm.biliaccelerator.ui.theme.LocalUiStyle
import io.github.jh_mmm.biliaccelerator.ui.theme.UiStyle

@Composable
fun AcceleratorBottomBar(
    destinations: List<TopLevelDestination>,
    currentDestination: TopLevelDestination,
    onNavigateToDestination: (TopLevelDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiStyle = LocalUiStyle.current

    when (uiStyle) {
        UiStyle.MIUIX -> {
            MiuixFloatingBottomBar(
                destinations = destinations,
                currentDestination = currentDestination,
                onNavigateToDestination = onNavigateToDestination,
                modifier = modifier
            )
        }
        UiStyle.MATERIAL3 -> {
            Material3BottomBar(
                destinations = destinations,
                currentDestination = currentDestination,
                onNavigateToDestination = onNavigateToDestination,
                modifier = modifier
            )
        }
    }
}

@Composable
private fun MiuixFloatingBottomBar(
    destinations: List<TopLevelDestination>,
    currentDestination: TopLevelDestination,
    onNavigateToDestination: (TopLevelDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(32.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            modifier = Modifier.height(64.dp)
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                destinations.forEach { dest ->
                    val selected = dest == currentDestination
                    val pillBgColor by animateColorAsState(
                        targetValue = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            Color.Transparent
                        },
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                        label = "pill_bg"
                    )
                    val contentColor by animateColorAsState(
                        targetValue = if (selected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        label = "content_color"
                    )

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(24.dp))
                            .background(pillBgColor)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onNavigateToDestination(dest) }
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                contentDescription = dest.title,
                                tint = contentColor,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = dest.title,
                                color = contentColor,
                                fontSize = 13.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Material3BottomBar(
    destinations: List<TopLevelDestination>,
    currentDestination: TopLevelDestination,
    onNavigateToDestination: (TopLevelDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationBar(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp
    ) {
        destinations.forEach { dest ->
            val selected = dest == currentDestination
            NavigationBarItem(
                selected = selected,
                onClick = { onNavigateToDestination(dest) },
                icon = {
                    Icon(
                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                        contentDescription = dest.title
                    )
                },
                label = { Text(dest.title) },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    }
}
