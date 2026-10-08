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
    MIUIX("HyperOS (Miuix)"),
    MATERIAL3("Material 3")
}

data class UiPreferences(
    val themeMode: UiThemeMode = UiThemeMode.SYSTEM,
    val style: UiStyle = UiStyle.MIUIX
)
