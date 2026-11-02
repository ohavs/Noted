package com.ohav.noted.ui.media

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ohav.noted.data.Media
import com.ohav.noted.data.Voice
import com.ohav.noted.design.Motion
import com.ohav.noted.design.Noted
import com.ohav.noted.design.Ph
import com.ohav.noted.design.Shadows
import com.ohav.noted.design.Squircle
import com.ohav.noted.design.press
import com.ohav.noted.design.softShadow
import kotlin.math.max

/**
 * Bars of a recording. Time runs with the reading direction (right → left in Hebrew);
 * [progress] (read only while drawing) fills the played part.
 */
@Composable
fun WaveBars(bars: List<Int>, played: Color, rest: Color, progress: () -> Float, modifier: Modifier) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(
        modifier.drawBehind {
            if (bars.isEmpty()) return@drawBehind
            val n = bars.size
            val gap = 2.dp.toPx()
            val w = max(1.5f, (size.width - gap * (n - 1)) / n)
            val p = progress().coerceIn(0f, 1f)
            bars.forEachIndexed { i, v ->
                val h = max(3.dp.toPx(), size.height * (v / 100f))
                val x = i * (w + gap)
                val left = if (rtl) size.width - x - w else x
                val done = (i + .5f) / n <= p
                drawRoundRect(
                    if (done) played else rest,
                    Offset(left, (size.height - h) / 2), Size(w, h), CornerRadius(w / 2, w / 2),
                )
            }
        },
    )
}

/** A recording inside a note: play / pause, the waveform (tap or drag to seek), length, transcript. */
@Composable
fun VoiceTile(v: Voice, compact: Boolean = false, modifier: Modifier = Modifier) {
    val c = Noted.colors
    val t = Noted.type
    val ctx = LocalContext.current
    val haptics = Noted.haptics
    val mine = VoicePlayer.current == v.f
    val playing = mine && VoicePlayer.playing
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var open by remember { mutableStateOf(false) }
    fun frac(x: Float, w: Int) = ((if (rtl) w - x else x) / w).coerceIn(0f, 1f)

    Column(
        modifier
            .fillMaxWidth()
            .clip(Squircle(18.dp))
            .background(c.noteInk.copy(alpha = .07f))
            .padding(horizontal = 10.dp, vertical = if (compact) 8.dp else 10.dp)
            .animateContentSize(Motion.glide()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val k by animateFloatAsState(if (playing) 1f else 0f, Motion.touch(), label = "play")
            Box(
                Modifier
                    .size(if (compact) 36.dp else 42.dp)
                    .press(scale = .85f) { VoicePlayer.toggle(ctx, v.f) }
                    .clip(CircleShape)
                    .background(c.noteInk),
                contentAlignment = Alignment.Center,
            ) {
                val ink = if (c.isDark) Color.Black else Color.White
                Icon(Ph.PlayFill, "ניגון", Modifier.size(18.dp).graphicsLayer { alpha = 1f - k; scaleX = 1f - .4f * k; scaleY = 1f - .4f * k }, tint = ink)
                Icon(Ph.PauseFill, "השהיה", Modifier.size(18.dp).graphicsLayer { alpha = k; scaleX = .6f + .4f * k; scaleY = .6f + .4f * k }, tint = ink)
            }
            Spacer(Modifier.width(10.dp))
            WaveBars(
                v.wave, c.noteInk, c.noteInk.copy(alpha = .25f),
                progress = { if (VoicePlayer.current == v.f) VoicePlayer.progress else 0f },
                modifier = Modifier
                    .weight(1f)
                    .height(if (compact) 28.dp else 34.dp)
                    .then(
                        if (compact) Modifier else Modifier
                            .pointerInput(v.f) {
                                detectTapGestures { o -> haptics.tick(); VoicePlayer.playAt(ctx, v.f, frac(o.x, size.width)) }
                            }
                            .pointerInput(v.f) {
                                detectHorizontalDragGestures { ch, _ ->
                                    ch.consume()
                                    VoicePlayer.playAt(ctx, v.f, frac(ch.position.x, size.width))
                                }
                            },
                    ),
            )
            Spacer(Modifier.width(10.dp))
            val shown = if (mine) (VoicePlayer.progress * v.ms).toLong() else v.ms
            Text(Media.fmtMs(shown), style = t.meta, color = c.noteInk2)
        }
        if (v.text.isNotBlank()) {
            Text(
                v.text,
                style = t.note.copy(fontSize = 15.sp, lineHeight = 22.sp, textDirection = TextDirection.Content),
                color = c.noteInk2,
                maxLines = if (open && !compact) Int.MAX_VALUE else 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = 8.dp, start = 4.dp, end = 4.dp)
                    .then(if (compact) Modifier else Modifier.pointerInput(Unit) { detectTapGestures { open = !open } }),
            )
        }
    }
}

/**
 * The recording panel: rises over the smart bar while recording. Live waveform, time,
 * the transcript as it's heard; cancel or finish.
 */
@Composable
fun RecorderPanel(rec: Recorder, onCancel: () -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val c = Noted.colors
    val t = Noted.type
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, Motion.glide()) }
    val pulse = rememberInfiniteTransition(label = "rec")
    val dot by pulse.animateFloat(1f, .35f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "dot")
    Column(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
            .graphicsLayer { alpha = enter.value; translationY = (1f - enter.value) * 40.dp.toPx() }
            .softShadow(Shadows.float(c.isDark), 26.dp, c.inverse)
            .clip(Squircle(26.dp))
            .background(c.inverse)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).graphicsLayer { alpha = dot }.clip(CircleShape).background(c.danger))
            Spacer(Modifier.width(8.dp))
            Text(Media.fmtMs(rec.elapsed), style = t.cardTitle.copy(fontSize = 20.sp, lineHeight = 24.sp), color = c.inverseInk)
            Spacer(Modifier.weight(1f))
            Text(
                when (rec.listening) {
                    null -> "הקלטה"
                    true -> "מקליט ומתמלל"
                    false -> "מקליט"
                },
                style = t.meta, color = c.inverseInk2,
            )
        }
        // the newest 60 levels, sliding in from the start side
        val tail = rec.levels.takeLast(60)
        WaveBars(
            List(60 - tail.size) { 0 } + tail.map { (it * 100).toInt() },
            c.inverseInk, c.inverseInk, progress = { 1f },
            modifier = Modifier.fillMaxWidth().height(44.dp),
        )
        val text = (rec.transcript + " " + rec.partial).trim()
        if (rec.listening != null) {
            val scroll = rememberScrollState()
            LaunchedEffect(text) { scroll.animateScrollTo(scroll.maxValue) }
            Box(Modifier.fillMaxWidth().heightIn(min = 22.dp, max = 110.dp).verticalScroll(scroll)) {
                Text(
                    text.ifEmpty { "מדברים, והטקסט יופיע כאן…" },
                    style = t.note.copy(fontSize = 16.sp, lineHeight = 23.sp, textDirection = TextDirection.Content),
                    color = if (text.isEmpty()) c.inverseInk2 else c.inverseInk,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(c.inverseInk2.copy(alpha = .18f)).press(scale = .85f, onClick = onCancel),
                contentAlignment = Alignment.Center,
            ) { Icon(Ph.Trash, "ביטול ההקלטה", Modifier.size(20.dp), tint = c.inverseInk) }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(60.dp).clip(CircleShape).background(c.danger).press(scale = .88f, onClick = onDone),
                contentAlignment = Alignment.Center,
            ) { Icon(Ph.StopFill, "סיום", Modifier.size(24.dp), tint = Color.White) }
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.size(48.dp))
        }
    }
}
