package io.github.jh_mmm.biliaccelerator.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.jh_mmm.biliaccelerator.ui.theme.LocalUiStyle
import io.github.jh_mmm.biliaccelerator.ui.theme.UiStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AcceleratorPage(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues, TopAppBarScrollBehavior) -> Unit
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val uiStyle = LocalUiStyle.current

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        text = title,
                        fontWeight = if (uiStyle == UiStyle.MIUIX) FontWeight.Bold else FontWeight.SemiBold,
                        letterSpacing = if (uiStyle == UiStyle.MIUIX) 0.5.sp else 0.sp
                    )
                },
                actions = actions,
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        content(padding, scrollBehavior)
    }
}
