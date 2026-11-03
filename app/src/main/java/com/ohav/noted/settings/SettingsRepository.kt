package com.ohav.noted.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ohav.noted.design.Accent
import com.ohav.noted.design.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class AppSettings(
    val theme: ThemeMode = ThemeMode.System,
    val accent: Accent = Accent.Fire,
    val haptics: Boolean = true,
    /** The first-run intro was seen (or skipped); false shows it over whatever is on screen. */
    val onboarded: Boolean = true,
)

private val Context.store by preferencesDataStore("settings")

class SettingsRepository(private val context: Context) {
    private object K {
        val theme = stringPreferencesKey("theme")
        val accent = stringPreferencesKey("accent")
        val haptics = booleanPreferencesKey("haptics")
        val onboarded = booleanPreferencesKey("onboarded")
    }

    /**
     * Installed now, never updated: only then does a missing "onboarded" mean a new user. Anyone who
     * came here through an update already knows the app and skips the intro.
     */
    private val freshInstall: Boolean = runCatching {
        val i = context.packageManager.getPackageInfo(context.packageName, 0)
        i.firstInstallTime == i.lastUpdateTime
    }.getOrDefault(false)

    private inline fun <reified E : Enum<E>> parse(v: String?, d: E): E =
        v?.let { s -> enumValues<E>().firstOrNull { it.name == s } } ?: d

    val settings: Flow<AppSettings> = context.store.data.map { p ->
        AppSettings(
            theme = parse(p[K.theme], ThemeMode.System),
            accent = parse(p[K.accent], Accent.Fire),
            haptics = p[K.haptics] ?: true,
            onboarded = p[K.onboarded] ?: !freshInstall,
        )
    }

    suspend fun setTheme(v: ThemeMode) = context.store.edit { it[K.theme] = v.name }
    suspend fun setAccent(v: Accent) = context.store.edit { it[K.accent] = v.name }
    suspend fun setHaptics(v: Boolean) = context.store.edit { it[K.haptics] = v }
    suspend fun setOnboarded(v: Boolean) = context.store.edit { it[K.onboarded] = v }
}
