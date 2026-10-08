package io.github.jh_mmm.biliaccelerator.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

val BiliBlue = Color(0xFF00AEEC)
val BiliPink = Color(0xFFFB7299)
val TagPcdn = Color(0xFFE53935)
val TagMcdn = Color(0xFFFB8C00)
val TagUpos = Color(0xFF43A047)
val TagSched = Color(0xFF00AEEC)

// Miuix / HyperOS Colors
val MiuixLightBackground = Color(0xFFF4F5F7)
val MiuixLightCard = Color(0xFFFFFFFF)
val MiuixLightTextPrimary = Color(0xFF191919)
val MiuixLightTextSecondary = Color(0xFF888888)
val MiuixLightDivider = Color(0xFFEBEBEB)

val MiuixDarkBackground = Color(0xFF101010)
val MiuixDarkCard = Color(0xFF1E1E20)
val MiuixDarkTextPrimary = Color(0xFFF2F2F2)
val MiuixDarkTextSecondary = Color(0xFF8E8E93)
val MiuixDarkDivider = Color(0xFF2C2C2E)

private val MiuixLightColorScheme = lightColorScheme(
    primary = BiliBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0F4FD),
    onPrimaryContainer = Color(0xFF00354B),
    secondary = BiliPink,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFD9E2),
    onSecondaryContainer = Color(0xFF3E001D),
    background = MiuixLightBackground,
    onBackground = MiuixLightTextPrimary,
    surface = MiuixLightCard,
    onSurface = MiuixLightTextPrimary,
    surfaceVariant = Color(0xFFEFEFF2),
    onSurfaceVariant = MiuixLightTextSecondary,
    outline = MiuixLightDivider,
)

private val MiuixDarkColorScheme = darkColorScheme(
    primary = BiliBlue,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF004D6B),
    onPrimaryContainer = Color(0xFFBCE9FF),
    secondary = BiliPink,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF650033),
    onSecondaryContainer = Color(0xFFFFD9E2),
    background = MiuixDarkBackground,
    onBackground = MiuixDarkTextPrimary,
    surface = MiuixDarkCard,
    onSurface = MiuixDarkTextPrimary,
    surfaceVariant = Color(0xFF28282B),
    onSurfaceVariant = MiuixDarkTextSecondary,
    outline = MiuixDarkDivider,
)

private val Material3LightColorScheme = lightColorScheme(
    primary = BiliBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFBBE9FF),
    onPrimaryContainer = Color(0xFF001F2A),
    secondary = BiliPink,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFD9E2),
    onSecondaryContainer = Color(0xFF3B071F),
    background = Color(0xFFFDFCFF),
    onBackground = Color(0xFF191C1E),
    surface = Color(0xFFFDFCFF),
    onSurface = Color(0xFF191C1E),
    surfaceVariant = Color(0xFFDFE2E8),
    onSurfaceVariant = Color(0xFF43474E),
    outline = Color(0xFF73777F),
)

private val Material3DarkColorScheme = darkColorScheme(
    primary = Color(0xFF64D2FF),
    onPrimary = Color(0xFF003547),
    primaryContainer = Color(0xFF004D66),
    onPrimaryContainer = Color(0xFFBBE9FF),
    secondary = Color(0xFFFFB0C7),
    onSecondary = Color(0xFF5E1132),
    secondaryContainer = Color(0xFF7B2948),
    onSecondaryContainer = Color(0xFFFFD9E2),
    background = Color(0xFF191C1E),
    onBackground = Color(0xFFE1E2E5),
    surface = Color(0xFF191C1E),
    onSurface = Color(0xFFE1E2E5),
    surfaceVariant = Color(0xFF43474E),
    onSurfaceVariant = Color(0xFFC3C7CF),
    outline = Color(0xFF8D9199),
)

val LocalResolvedDarkTheme = staticCompositionLocalOf { false }
val LocalUiStyle = staticCompositionLocalOf { UiStyle.MIUIX }

@Composable
fun AcceleratorTheme(
    preferences: UiPreferences,
    content: @Composable () -> Unit
) {
    val darkTheme = preferences.themeMode.resolveDark(isSystemInDarkTheme())
    val context = LocalContext.current

    val colors: ColorScheme = when (preferences.style) {
        UiStyle.MATERIAL3 -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } else {
                if (darkTheme) Material3DarkColorScheme else Material3LightColorScheme
            }
        }
        UiStyle.MIUIX -> {
            if (darkTheme) MiuixDarkColorScheme else MiuixLightColorScheme
        }
    }

    CompositionLocalProvider(
        LocalResolvedDarkTheme provides darkTheme,
        LocalUiStyle provides preferences.style
    ) {
        MaterialTheme(
            colorScheme = colors,
            content = content
        )
    }
}

@Composable
@ReadOnlyComposable
fun isAcceleratorDarkTheme(): Boolean = LocalResolvedDarkTheme.current
