package io.github.jh_mmm.biliaccelerator.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val BiliBlue = Color(0xFF00AEEC)
val BiliPink = Color(0xFFFB7299)
val TagPcdn = Color(0xFFE53935)
val TagMcdn = Color(0xFFFB8C00)
val TagUpos = Color(0xFF43A047)
val TagSched = Color(0xFF00AEEC)
val TagMoss = Color(0xFF9C27B0)
val TagBtr = Color(0xFF8E44AD)
val TagBtrFailover = Color(0xFFE67E22)

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

private val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
    largeIncreased = RoundedCornerShape(26.dp),
    extraLargeIncreased = RoundedCornerShape(32.dp),
    extraExtraLarge = RoundedCornerShape(36.dp)
)

val LocalResolvedDarkTheme = staticCompositionLocalOf { false }
val LocalUiStyle = staticCompositionLocalOf { UiStyle.MIUIX }
val LocalBottomBarStyle = staticCompositionLocalOf { BottomBarStyle.FOLLOW_STYLE }

fun resolveUseFloatingBottomBar(
    style: UiStyle,
    bottomBarStyle: BottomBarStyle
): Boolean = when (bottomBarStyle) {
    BottomBarStyle.FLOATING -> true
    BottomBarStyle.DOCKED -> false
    BottomBarStyle.FOLLOW_STYLE -> style == UiStyle.MIUIX || style == UiStyle.MATERIAL3_EXPRESSIVE
}

fun UiStyle.groupCornerRadius(): Dp = when (this) {
    UiStyle.MIUIX -> 18.dp
    UiStyle.MATERIAL3 -> 12.dp
    UiStyle.MATERIAL3_EXPRESSIVE -> 24.dp
}

fun UiStyle.cardCornerRadius(): Dp = when (this) {
    UiStyle.MIUIX -> 16.dp
    UiStyle.MATERIAL3 -> 12.dp
    UiStyle.MATERIAL3_EXPRESSIVE -> 22.dp
}

fun UiStyle.itemCornerRadius(): Dp = when (this) {
    UiStyle.MIUIX -> 14.dp
    UiStyle.MATERIAL3 -> 10.dp
    UiStyle.MATERIAL3_EXPRESSIVE -> 18.dp
}

fun UiStyle.heroCornerRadius(): Dp = when (this) {
    UiStyle.MIUIX -> 20.dp
    UiStyle.MATERIAL3 -> 16.dp
    UiStyle.MATERIAL3_EXPRESSIVE -> 28.dp
}

fun UiStyle.dialogCornerRadius(): Dp = when (this) {
    UiStyle.MIUIX -> 20.dp
    UiStyle.MATERIAL3 -> 20.dp
    UiStyle.MATERIAL3_EXPRESSIVE -> 28.dp
}

@Composable
fun AcceleratorTheme(
    preferences: UiPreferences,
    content: @Composable () -> Unit
) {
    val darkTheme = preferences.themeMode.resolveDark(isSystemInDarkTheme())
    val context = LocalContext.current

    val colors: ColorScheme = when (preferences.style) {
        UiStyle.MATERIAL3, UiStyle.MATERIAL3_EXPRESSIVE -> {
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
        LocalUiStyle provides preferences.style,
        LocalBottomBarStyle provides preferences.bottomBarStyle
    ) {
        if (preferences.style == UiStyle.MATERIAL3_EXPRESSIVE) {
            MaterialExpressiveTheme(
                colorScheme = colors,
                motionScheme = MotionScheme.expressive(),
                shapes = ExpressiveShapes,
                content = content
            )
        } else {
            MaterialTheme(
                colorScheme = colors,
                content = content
            )
        }
    }
}

@Composable
@ReadOnlyComposable
fun isAcceleratorDarkTheme(): Boolean = LocalResolvedDarkTheme.current
