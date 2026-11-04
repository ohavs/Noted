package com.ohav.noted.ui.media

import android.content.Context

/** The language recordings are transcribed in (live while recording, and the watch's recordings). */
object SpeechLang {
    class Lang(val tag: String, val name: String, val native: String)

    val all = listOf(
        Lang("he-IL", "עברית", "עברית"),
        Lang("en-US", "אנגלית", "English"),
        Lang("ar-IL", "ערבית", "العربية"),
        Lang("ru-RU", "רוסית", "Русский"),
        Lang("fr-FR", "צרפתית", "Français"),
        Lang("es-ES", "ספרדית", "Español"),
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("speech", Context.MODE_PRIVATE)

    fun chosen(ctx: Context): Lang {
        val tag = prefs(ctx).getString("lang", null)
        return all.firstOrNull { it.tag == tag } ?: all[0]
    }

    fun tag(ctx: Context): String = chosen(ctx).tag

    fun choose(ctx: Context, l: Lang) = prefs(ctx).edit().putString("lang", l.tag).apply()
}
