package com.ohav.noted.design

import android.app.Activity
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.view.WindowCompat
import com.ohav.noted.settings.AppSettings

val LocalNotedColors = staticCompositionLocalOf { notedColors(false, Accent.Fire) }
val LocalNotedType = staticCompositionLocalOf { NotedType() }
val LocalHaptics = staticCompositionLocalOf<Haptics> { error("Haptics not provided") }

object Noted {
    val colors: NotedColors @Composable get() = LocalNotedColors.current
    val type: NotedType @Composable get() = LocalNotedType.current
    val haptics: Haptics @Composable get() = LocalHaptics.current
}

@Composable
private fun animate(c: Color): Color = animateColorAsState(c, Motion.glide(), label = "color").value

/** Every token glides to its new value when theme/accent change. */
@Composable
private fun NotedColors.animated() = NotedColors(
    isDark = isDark,
    bg = animate(bg), bg2 = animate(bg2), surface = animate(surface),
    ink = animate(ink), ink2 = animate(ink2), ink3 = animate(ink3), line = animate(line), scrim = animate(scrim),
    inverse = animate(inverse), inverse2 = animate(inverse2), inverseInk = animate(inverseInk), inverseInk2 = animate(inverseInk2),
    danger = animate(danger), dangerSoft = animate(dangerSoft), noteInk = animate(noteInk), noteInk2 = animate(noteInk2),
    accent = animate(accent), accentInk = animate(accentInk), accentSoft = animate(accentSoft),
)

@Composable
fun NotedTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val dark = when (settings.theme) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val colors = notedColors(dark, settings.accent).animated()
    val type = remember { NotedType() }
    val view = LocalView.current
    val haptics = remember(view) { Haptics(view) }
    haptics.enabled = settings.haptics

    if (!view.isInEditMode) SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    // Material is only a base for text fields/selection; every visible component is custom.
    val scheme = if (dark) darkColorScheme(
        primary = colors.accent, onPrimary = colors.accentInk, background = colors.bg, onBackground = colors.ink,
        surface = colors.surface, onSurface = colors.ink, error = colors.danger,
    ) else lightColorScheme(
        primary = colors.accent, onPrimary = colors.accentInk, background = colors.bg, onBackground = colors.ink,
        surface = colors.surface, onSurface = colors.ink, error = colors.danger,
    )
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(
            LocalNotedColors provides colors,
            LocalNotedType provides type,
            LocalHaptics provides haptics,
            LocalLayoutDirection provides LayoutDirection.Rtl,
            LocalTextSelectionColors provides TextSelectionColors(colors.accent, colors.accent.copy(alpha = .3f)),
            content = content,
        )
    }
}
