package io.github.umislat.aicecho

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

internal object ThemeSettings {
    enum class Mode(val storedValue: String, val nightMode: Int) {
        SYSTEM("system", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
        LIGHT("light", AppCompatDelegate.MODE_NIGHT_NO),
        DARK("dark", AppCompatDelegate.MODE_NIGHT_YES);

        companion object {
            fun fromStoredValue(value: String?): Mode =
                entries.firstOrNull { it.storedValue == value } ?: SYSTEM
        }
    }

    fun mode(context: Context): Mode = Mode.fromStoredValue(
        preferences(context).getString(KEY_THEME_MODE, null)
    )

    fun setMode(context: Context, mode: Mode) {
        preferences(context).edit().putString(KEY_THEME_MODE, mode.storedValue).apply()
    }

    fun applyMode(context: Context) {
        AppCompatDelegate.setDefaultNightMode(mode(context).nightMode)
    }

    fun dynamicColorsEnabled(context: Context): Boolean =
        preferences(context).getBoolean(KEY_DYNAMIC_COLORS, true)

    fun setDynamicColorsEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_DYNAMIC_COLORS, enabled).apply()
    }

    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences(
        PREFERENCES,
        Context.MODE_PRIVATE
    )

    private const val PREFERENCES = "aicecho_appearance"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_DYNAMIC_COLORS = "dynamic_colors"
}
