package com.ohav.noted.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ohav.noted.design.Accent
import com.ohav.noted.design.FontPair
import com.ohav.noted.design.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class AppSettings(
    val theme: ThemeMode = ThemeMode.System,
    val accent: Accent = Accent.Fire,
    val fontPair: FontPair = FontPair.Soft,
    val haptics: Boolean = true,
)

private val Context.store by preferencesDataStore("settings")

class SettingsRepository(private val context: Context) {
    private object K {
        val theme = stringPreferencesKey("theme")
        val accent = stringPreferencesKey("accent")
        val font = stringPreferencesKey("font")
        val haptics = booleanPreferencesKey("haptics")
    }

    private inline fun <reified E : Enum<E>> parse(v: String?, d: E): E =
        v?.let { s -> enumValues<E>().firstOrNull { it.name == s } } ?: d

    val settings: Flow<AppSettings> = context.store.data.map { p ->
        AppSettings(
            theme = parse(p[K.theme], ThemeMode.System),
            accent = parse(p[K.accent], Accent.Fire),
            fontPair = parse(p[K.font], FontPair.Soft),
            haptics = p[K.haptics] ?: true,
        )
    }

    suspend fun setTheme(v: ThemeMode) = context.store.edit { it[K.theme] = v.name }
    suspend fun setAccent(v: Accent) = context.store.edit { it[K.accent] = v.name }
    suspend fun setFont(v: FontPair) = context.store.edit { it[K.font] = v.name }
    suspend fun setHaptics(v: Boolean) = context.store.edit { it[K.haptics] = v }
}
