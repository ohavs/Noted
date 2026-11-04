package com.ohav.noted.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ohav.noted.design.LocalOverlays
import com.ohav.noted.design.Motion
import com.ohav.noted.design.Noted
import com.ohav.noted.design.Ph
import com.ohav.noted.design.Shapes
import com.ohav.noted.design.press
import com.ohav.noted.reminders.Sounds
import com.ohav.noted.ui.media.SpeechLang

/** One row per kind of notification; each opens a sheet of tones that play as you pick them. */
@Composable
fun SoundsCard() {
    val c = Noted.colors
    val t = Noted.type
    val ctx = LocalContext.current
    val overlays = LocalOverlays.current
    var version by remember { mutableIntStateOf(0) }
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(Shapes.lg).background(c.surface)) {
        Sounds.Kind.entries.forEachIndexed { i, kind ->
            val chosen = remember(version) { Sounds.chosen(ctx, kind) }
            Column(
                Modifier.fillMaxWidth().press(scale = .98f) {
                    overlays.sheet.show(kind.title) { TonePicker(kind) { version++ } }
                },
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        when (kind) { Sounds.Kind.Time -> Ph.BellRinging; Sounds.Kind.Place -> Ph.MapPin; Sounds.Kind.Shared -> Ph.UsersThree },
                        null, Modifier.size(22.dp), tint = c.ink2,
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(kind.title, style = t.body, color = c.ink, modifier = Modifier.weight(1f))
                    Text(chosen.name, style = t.caption, color = c.ink3)
                    Spacer(Modifier.width(8.dp))
                    Icon(Ph.CaretEnd, null, Modifier.size(18.dp), tint = c.ink3)
                }
                if (i < Sounds.Kind.entries.lastIndex) Box(Modifier.padding(start = 54.dp).fillMaxWidth().height(1.dp).background(c.line))
            }
        }
    }
}

@Composable
private fun TonePicker(kind: Sounds.Kind, onChosen: () -> Unit) {
    val c = Noted.colors
    val t = Noted.type
    val ctx = LocalContext.current
    val haptics = Noted.haptics
    var chosen by remember { mutableIntStateOf(Sounds.tones.indexOf(Sounds.chosen(ctx, kind))) }
    var muted by remember { mutableStateOf(Sounds.notificationsMuted(ctx)) }
    DisposableEffect(Unit) { onDispose { Sounds.stopPreview(ctx) } }
    Text("נגיעה משמיעה ובוחרת", style = t.label, color = c.ink3, modifier = Modifier.padding(bottom = 10.dp))
    if (muted) Row(
        Modifier.padding(bottom = 10.dp).fillMaxWidth().clip(Shapes.md).background(c.accent.copy(alpha = .08f)).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Ph.Vibrate, null, Modifier.size(20.dp), tint = c.accent)
        Text("הטלפון בשקט או שעוצמת ההתראות על 0 — כאן הצליל יישמע, אבל ההתראות עצמן יגיעו בלי צליל.", style = t.caption, color = c.ink2)
    }
    Sounds.tones.forEachIndexed { i, tone ->
        val on = i == chosen
        val bg by animateColorAsState(if (on) c.accent.copy(alpha = .12f) else c.bg.copy(alpha = 0f), Motion.glide(), label = "toneBg")
        val dot by animateFloatAsState(if (on) 1f else 0f, Motion.touch(), label = "toneDot")
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .clip(Shapes.md)
                .background(bg)
                .press(scale = .98f, haptic = false) {
                    haptics.tick()
                    chosen = i
                    Sounds.choose(ctx, kind, tone)
                    muted = Sounds.notificationsMuted(ctx)
                    if (tone.id == Sounds.SILENT) Sounds.stopPreview(ctx) else Sounds.preview(ctx, tone)
                    onChosen()
                }
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                when (tone.id) { Sounds.SILENT -> Ph.Vibrate; Sounds.SYSTEM -> Ph.DeviceMobile; else -> Ph.MusicNote },
                null, Modifier.size(20.dp), tint = if (on) c.accent else c.ink2,
            )
            Text(tone.name, style = t.body.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal), color = c.ink, modifier = Modifier.weight(1f))
            Box(Modifier.size(22.dp).clip(CircleShape).background(c.line), contentAlignment = Alignment.Center) {
                Box(Modifier.size(12.dp).graphicsLayer { scaleX = dot; scaleY = dot }.clip(CircleShape).background(c.accent))
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}

/** The language recordings are transcribed in: one row, a sheet of languages. */
@Composable
fun SpeechCard() {
    val c = Noted.colors
    val t = Noted.type
    val ctx = LocalContext.current
    val overlays = LocalOverlays.current
    var version by remember { mutableIntStateOf(0) }
    val chosen = remember(version) { SpeechLang.chosen(ctx) }
    Row(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(Shapes.lg).background(c.surface)
            .press(scale = .98f) { overlays.sheet.show("שפת התמלול") { LangPicker { version++ } } }
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Ph.Microphone, null, Modifier.size(22.dp), tint = c.ink2)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("שפת התמלול", style = t.body, color = c.ink)
            Text("הקלטות בפתק ומהשעון", style = t.caption, color = c.ink3)
        }
        Text(chosen.name, style = t.caption, color = c.ink3)
        Spacer(Modifier.width(8.dp))
        Icon(Ph.CaretEnd, null, Modifier.size(18.dp), tint = c.ink3)
    }
}

@Composable
private fun LangPicker(onChosen: () -> Unit) {
    val c = Noted.colors
    val t = Noted.type
    val ctx = LocalContext.current
    val haptics = Noted.haptics
    var chosen by remember { mutableIntStateOf(SpeechLang.all.indexOf(SpeechLang.chosen(ctx))) }
    Text("אם אין במכשיר מודל לשפה — התמלול עובר דרך שירות הזיהוי של Google", style = t.label, color = c.ink3, modifier = Modifier.padding(bottom = 10.dp))
    SpeechLang.all.forEachIndexed { i, lang ->
        val on = i == chosen
        val bg by animateColorAsState(if (on) c.accent.copy(alpha = .12f) else c.bg.copy(alpha = 0f), Motion.glide(), label = "langBg")
        val dot by animateFloatAsState(if (on) 1f else 0f, Motion.touch(), label = "langDot")
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .clip(Shapes.md)
                .background(bg)
                .press(scale = .98f, haptic = false) {
                    haptics.tick()
                    chosen = i
                    SpeechLang.choose(ctx, lang)
                    onChosen()
                }
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(lang.name, style = t.body.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal), color = c.ink, modifier = Modifier.weight(1f))
            if (lang.native != lang.name) Text(lang.native, style = t.caption, color = c.ink3)
            Box(Modifier.size(22.dp).clip(CircleShape).background(c.line), contentAlignment = Alignment.Center) {
                Box(Modifier.size(12.dp).graphicsLayer { scaleX = dot; scaleY = dot }.clip(CircleShape).background(c.accent))
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}
