package io.github.jh_mmm.biliaccelerator.ui.theme

enum class UiThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色模式"),
    DARK("深色模式");

    fun resolveDark(isSystemInDarkTheme: Boolean): Boolean = when (this) {
        SYSTEM -> isSystemInDarkTheme
        LIGHT -> false
        DARK -> true
    }
}

enum class UiStyle(val label: String) {
    MIUIX("MIUIX"),
    MATERIAL3("Material 3"),
    MATERIAL3_EXPRESSIVE("Material 3 Expressive")
}

enum class BottomBarStyle(val label: String) {
    FOLLOW_STYLE("跟随界面风格"),
    FLOATING("悬浮底栏"),
    DOCKED("固定底栏")
}

data class UiPreferences(
    val themeMode: UiThemeMode = UiThemeMode.SYSTEM,
    val style: UiStyle = UiStyle.MIUIX,
    val bottomBarStyle: BottomBarStyle = BottomBarStyle.FOLLOW_STYLE
)
