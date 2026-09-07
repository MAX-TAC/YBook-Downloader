package com.maxim.ybookdownloader.ui

import android.content.Context

enum class ThemeMode(val label: String) {
    SYSTEM("Как в системе"),
    LIGHT("Светлая"),
    DARK("Тёмная")
}

enum class StartTab(val label: String) {
    HOME("Главная"),
    SEARCH("Поиск"),
    FAVORITES("Избранное"),
    HISTORY("История")
}

class ThemeStore(context: Context) {
    private val prefs = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)

    fun get(): ThemeMode {
        val raw = prefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name)
        return ThemeMode.entries.firstOrNull { it.name == raw } ?: ThemeMode.SYSTEM
    }

    fun set(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
    }

    fun getStartTab(): StartTab {
        val raw = prefs.getString(KEY_START_TAB, StartTab.HOME.name)
        return StartTab.entries.firstOrNull { it.name == raw } ?: StartTab.HOME
    }

    fun setStartTab(tab: StartTab) {
        prefs.edit().putString(KEY_START_TAB, tab.name).apply()
    }

    private companion object {
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_START_TAB = "start_tab"
    }
}
