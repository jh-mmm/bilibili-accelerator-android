package io.github.jh_mmm.biliaccelerator.ui.theme

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class UiPreferencesStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _preferences = MutableStateFlow(loadPreferences())
    val preferences: StateFlow<UiPreferences> = _preferences.asStateFlow()

    private fun loadPreferences(): UiPreferences {
        val themeModeStr = prefs.getString(KEY_THEME_MODE, UiThemeMode.SYSTEM.name)
        val styleStr = prefs.getString(KEY_STYLE, UiStyle.MIUIX.name)

        val themeMode = runCatching { UiThemeMode.valueOf(themeModeStr ?: "") }
            .getOrDefault(UiThemeMode.SYSTEM)
        val style = runCatching { UiStyle.valueOf(styleStr ?: "") }
            .getOrDefault(UiStyle.MIUIX)

        return UiPreferences(themeMode = themeMode, style = style)
    }

    fun updateThemeMode(mode: UiThemeMode) {
        val updated = _preferences.value.copy(themeMode = mode)
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
        _preferences.value = updated
    }

    fun updateStyle(style: UiStyle) {
        val updated = _preferences.value.copy(style = style)
        prefs.edit().putString(KEY_STYLE, style.name).apply()
        _preferences.value = updated
    }

    companion object {
        private const val PREFS_NAME = "bili_accelerator_ui_prefs"
        private const val KEY_THEME_MODE = "ui_theme_mode"
        private const val KEY_STYLE = "ui_style"
    }
}
