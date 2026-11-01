package com.ohav.noted.design

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

enum class ThemeMode(val label: String) { System("מערכת"), Light("בהיר"), Dark("כהה") }

/** Note pastels — same names as the prototype; dark values are muted versions of the same hue. */
enum class NoteColor(val label: String, val light: Color, val dark: Color) {
    Butter("חמאה", Color(0xFFFAE5A0), Color(0xFF4A4024)),
    Peach("אפרסק", Color(0xFFF9CFB6), Color(0xFF4C3326)),
    Rose("ורד", Color(0xFFF5C6D2), Color(0xFF482C35)),
    Lilac("לילך", Color(0xFFDACDF6), Color(0xFF383150)),
    Sky("שמיים", Color(0xFFC4DDF5), Color(0xFF27384A)),
    Mint("מנטה", Color(0xFFC6EAD5), Color(0xFF243D31)),
    Sage("מרווה", Color(0xFFDBE4C0), Color(0xFF343D28)),
    Sand("חול", Color(0xFFECE1D2), Color(0xFF3A342D));

    companion object {
        fun of(id: String): NoteColor = entries.firstOrNull { it.name.equals(id, true) } ?: Sand
    }
}

enum class Accent(
    val label: String,
    val light: Color, val lightSoft: Color,
    val dark: Color, val darkInk: Color, val darkSoft: Color,
) {
    Fire("אש", Color(0xFFE0542C), Color(0xFFF9DDD2), Color(0xFFFF7A4F), Color(0xFF1B0E08), Color(0xFF4A271B)),
    Forest("יער", Color(0xFF1E7552), Color(0xFFD3EADF), Color(0xFF45C891), Color(0xFF06170F), Color(0xFF173A2B)),
    Ink("דיו", Color(0xFF3442CF), Color(0xFFDDE0FA), Color(0xFF8A95FF), Color(0xFF0B0E2B), Color(0xFF23284F)),
    Plum("שזיף", Color(0xFF8E3AB3), Color(0xFFEBDAF3), Color(0xFFCF93F0), Color(0xFF1E0B29), Color(0xFF3A2348)),
}

@Immutable
data class NotedColors(
    val isDark: Boolean,
    val bg: Color,
    val bg2: Color,
    val surface: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val line: Color,
    val scrim: Color,
    val inverse: Color,
    val inverse2: Color,
    val inverseInk: Color,
    val inverseInk2: Color,
    val danger: Color,
    val dangerSoft: Color,
    val noteInk: Color,
    val noteInk2: Color,
    val accent: Color,
    val accentInk: Color,
    val accentSoft: Color,
) {
    fun note(c: NoteColor): Color = if (isDark) c.dark else c.light
}

fun notedColors(dark: Boolean, accent: Accent): NotedColors = if (!dark) NotedColors(
    isDark = false,
    bg = Color(0xFFF5F1EA), bg2 = Color(0xFFEDE7DD), surface = Color(0xFFFFFDF9),
    ink = Color(0xFF1B1915), ink2 = Color(0xFF6B655C), ink3 = Color(0xFFA69F94),
    line = Color(0x171B1915), scrim = Color(0x471E1A14),
    inverse = Color(0xFF1C1A17), inverse2 = Color(0xFF2A2723),
    inverseInk = Color(0xFFF6F2EA), inverseInk2 = Color(0xFF9C958A),
    danger = Color(0xFFD8402F), dangerSoft = Color(0xFFFBE1DC),
    noteInk = Color(0xFF1D1A15), noteInk2 = Color(0x9E1D1A15),
    accent = accent.light, accentInk = Color.White, accentSoft = accent.lightSoft,
) else NotedColors(
    isDark = true,
    bg = Color(0xFF12110F), bg2 = Color(0xFF1A1916), surface = Color(0xFF1F1D1A),
    ink = Color(0xFFF3EEE6), ink2 = Color(0xFFA59E93), ink3 = Color(0xFF6A645B),
    line = Color(0x17F3EEE6), scrim = Color(0x80000000),
    inverse = Color(0xFF2A2824), inverse2 = Color(0xFF37342F),
    inverseInk = Color(0xFFF6F2EA), inverseInk2 = Color(0xFFA39C90),
    danger = Color(0xFFFF6A55), dangerSoft = Color(0xFF3D1F1A),
    noteInk = Color(0xFFF4EFE7), noteInk2 = Color(0x99F4EFE7),
    accent = accent.dark, accentInk = accent.darkInk, accentSoft = accent.darkSoft,
)
