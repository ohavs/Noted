@file:OptIn(ExperimentalTextApi::class)

package com.ohav.noted.wear.ui

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ohav.noted.wear.Palette
import com.ohav.noted.wear.R
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The phone's two springs, nothing else moves the watch UI. */
object Motion {
    fun <T> touch(): FiniteAnimationSpec<T> = spring(dampingRatio = .55f, stiffness = 520f)
    fun <T> glide(): FiniteAnimationSpec<T> = spring(dampingRatio = .99f, stiffness = 230f)
}

object C {
    val bg = Color(Palette.BG)
    val surface = Color(Palette.SURFACE)
    val surface2 = Color(Palette.SURFACE2)
    val ink = Color(Palette.INK)
    val ink2 = Color(Palette.INK2)
    val ink3 = Color(Palette.INK3)
    val noteInk = Color(Palette.NOTE_INK)
    val noteInk2 = Color(Palette.NOTE_INK2)
    fun note(name: String) = Color(Palette.note(name))
}

object Fonts {
    val display = FontFamily(Font(R.font.karantina_bold, FontWeight.Bold))
    val body = FontFamily(
        listOf(400, 500, 600, 700).map { w ->
            Font(R.font.heebo, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
        },
    )
}

fun body(size: TextUnit = 14.sp, color: Color = C.ink, weight: Int = 400, align: TextAlign = TextAlign.Start) =
    TextStyle(fontFamily = Fonts.body, fontSize = size, fontWeight = FontWeight(weight), color = color, textAlign = align, lineHeight = size * 1.3f)

fun display(size: TextUnit = 26.sp, color: Color = C.ink, align: TextAlign = TextAlign.Center) =
    TextStyle(fontFamily = Fonts.display, fontSize = size, fontWeight = FontWeight.Bold, color = color, textAlign = align, lineHeight = size * 1.02f)

/** A light tick on every touch, a firmer one when something is done. */
class Haptics(private val view: View) {
    fun tick() { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
    fun confirm() { view.performHapticFeedback(HapticFeedbackConstants.CONFIRM) }
    fun reject() { view.performHapticFeedback(HapticFeedbackConstants.REJECT) }
}

@Composable
fun rememberHaptics(): Haptics { val v = LocalView.current; return remember(v) { Haptics(v) } }

/** Springy press + tick: every tappable thing on the watch. */
@Composable
fun Modifier.press(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val src = remember { MutableInteractionSource() }
    val down by src.collectIsPressedAsState()
    val k by animateFloatAsState(if (down) .93f else 1f, Motion.touch(), label = "press")
    val h = rememberHaptics()
    return this
        .graphicsLayer { scaleX = k; scaleY = k }
        .clickable(src, indication = null, enabled = enabled) { h.tick(); onClick() }
}

@Composable
fun Icon(v: ImageVector, tint: Color, size: Dp = 20.dp, modifier: Modifier = Modifier) =
    Image(rememberVectorPainter(v), null, modifier.size(size), colorFilter = ColorFilter.tint(tint))

/** "noted" with the accent dot, like the phone's logo. */
@Composable
fun Wordmark(accent: Color, size: TextUnit = 28.sp) {
    Box(contentAlignment = Alignment.Center) {
        BasicText("noted", style = display(size, C.ink))
        Box(
            Modifier.align(Alignment.BottomEnd).padding(bottom = (size.value * .2f).dp)
                .size((size.value * .2f).dp).clip(CircleShape).background(accent),
        )
    }
}

/** A round action with an optional caption under it. */
@Composable
fun RoundAction(icon: ImageVector, bg: Color, tint: Color, size: Dp, label: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(size).press(enabled, onClick).clip(CircleShape).background(bg),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, tint, size * .44f) }
        if (label != null) {
            Spacer(Modifier.height(4.dp))
            BasicText(label, style = body(11.sp, C.ink3, 500))
        }
    }
}

/** Small section title inside a list. */
@Composable
fun Section(text: String) = BasicText(text, Modifier.padding(top = 6.dp, bottom = 2.dp), style = body(12.sp, C.ink3, 600, TextAlign.Center))

/**
 * The confirmation after an action: an accent disc pops in, its tick draws itself, the line
 * under it says what happened. Leaves on its own (or on a tap).
 */
class Flash {
    var msg by mutableStateOf<Pair<String, ImageVector?>?>(null)
        private set
    private var n by mutableStateOf(0)
    val key get() = n
    fun show(text: String, icon: ImageVector? = null) { msg = text to icon; n++ }
    fun hide() { msg = null }
}

@Composable
fun FlashHost(flash: Flash, accent: Color, onAccent: Color) {
    val m = flash.msg ?: return
    val h = rememberHaptics()
    val pop = remember(flash.key) { Animatable(0f) }
    val draw = remember(flash.key) { Animatable(0f) }
    LaunchedEffect(flash.key) {
        h.confirm()
        pop.animateTo(1f, Motion.touch())
    }
    LaunchedEffect(flash.key) {
        delay(120)
        draw.animateTo(1f, Motion.glide())
    }
    LaunchedEffect(flash.key) {
        delay(1700)
        flash.hide()
    }
    Box(
        Modifier.fillMaxSize().background(C.bg.copy(alpha = .92f)).clickable(remember { MutableInteractionSource() }, null) { flash.hide() },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(64.dp).scale(pop.value).clip(CircleShape).background(accent), contentAlignment = Alignment.Center) {
                val icon = m.second
                if (icon != null) Icon(icon, onAccent, 28.dp, Modifier.graphicsLayer { alpha = draw.value })
                else Tick(onAccent, draw.value, Modifier.size(30.dp))
            }
            Spacer(Modifier.height(12.dp))
            BasicText(m.first, Modifier.padding(horizontal = 28.dp).graphicsLayer { alpha = pop.value.coerceIn(0f, 1f) }, style = body(15.sp, C.ink, 500, TextAlign.Center))
        }
    }
}

/** A tick drawn up to [t] (0..1) of its length. */
@Composable
fun Tick(color: Color, t: Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val p = Path().apply {
            moveTo(size.width * .18f, size.height * .54f)
            lineTo(size.width * .42f, size.height * .76f)
            lineTo(size.width * .84f, size.height * .28f)
        }
        val pm = PathMeasure().apply { setPath(p, false) }
        val part = Path()
        pm.getSegment(0f, pm.length * t.coerceIn(0f, 1f), part, true)
        drawPath(part, color, style = Stroke(width = size.width * .13f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** A thin progress line for checklists. */
@Composable
fun Progress(done: Int, total: Int, track: Color, fill: Color, modifier: Modifier = Modifier) {
    val t by animateFloatAsState(if (total == 0) 0f else done.toFloat() / total, Motion.glide(), label = "progress")
    Canvas(modifier.height(4.dp)) {
        val y = size.height / 2
        drawLine(track, Offset(0f, y), Offset(size.width, y), size.height, StrokeCap.Round)
        if (t > 0f) {
            // RTL: fills from the right
            drawLine(fill, Offset(size.width, y), Offset(size.width * (1 - t), y), size.height, StrokeCap.Round)
        }
    }
}

// ---- time, local and in Hebrew ----

private val hm = DateTimeFormatter.ofPattern("HH:mm")
private val days = listOf("ב׳", "ג׳", "ד׳", "ה׳", "ו׳", "ש׳", "א׳") // ISO Monday first

fun whenText(at: Long, due: Boolean): String {
    if (due) return "עכשיו"
    val z = ZoneId.systemDefault()
    val t = Instant.ofEpochMilli(at).atZone(z)
    val d = t.toLocalDate()
    val today = LocalDate.now(z)
    val time = t.format(hm)
    return when {
        d == today -> time
        d == today.plusDays(1) -> "מחר $time"
        d.isBefore(today.plusDays(7)) -> "יום ${days[d.dayOfWeek.value - 1]} $time"
        else -> "${d.dayOfMonth}/${d.monthValue} $time"
    }
}

fun agoText(ms: Long): String {
    val m = ((System.currentTimeMillis() - ms) / 60_000).coerceAtLeast(0)
    return when {
        m < 1 -> "עכשיו"
        m < 60 -> "לפני $m דק׳"
        m < 60 * 24 -> "לפני ${m / 60} שע׳"
        else -> "לפני ${m / 1440} ימים"
    }
}

fun mmss(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }
