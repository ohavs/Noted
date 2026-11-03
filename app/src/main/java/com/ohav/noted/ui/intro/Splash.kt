package com.ohav.noted.ui.intro

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.ohav.noted.design.Motion
import com.ohav.noted.design.Noted
import com.ohav.noted.design.NoteColor
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The launch splash: the launcher icon's three stacked notes put themselves together (the front one
 * pops in, the two behind rise out of it, its lines are written from the start side, the accent dot
 * lands), then the front note grows into the whole screen and fades — the app opens out of it.
 * The system splash (Android 12+) is only the paper colour, so this picks up seamlessly.
 * A tap anywhere jumps straight to the opening; everything rides the two motion springs.
 */
@Stable
class SplashState(show: Boolean) {
    var visible by mutableStateOf(show)
        private set

    internal val front = Animatable(0f)
    internal val peach = Animatable(0f)
    internal val mint = Animatable(0f)
    internal val line1 = Animatable(0f)
    internal val line2 = Animatable(0f)
    internal val dot = Animatable(0f)
    internal val open = Animatable(0f)

    /** How far what's under the splash has come forward, 0..1 — read in draw only. */
    fun reveal(): Float = if (visible) open.value.coerceIn(0f, 1f) else 1f

    internal suspend fun finish() = coroutineScope {
        // whatever hasn't landed yet catches up on the way out
        listOf(front, peach, mint, line1, line2, dot).forEach { a -> if (a.targetValue < 1f) launch { a.animateTo(1f, Motion.touch()) } }
        open.animateTo(1f, Motion.glide(visibilityThreshold = .004f))
        visible = false
    }
}

@Composable
fun SplashOverlay(state: SplashState) {
    if (!state.visible) return
    val c = Noted.colors
    val haptics = Noted.haptics
    val scope = rememberCoroutineScope()
    val bg = c.bg
    val accent = c.accent

    LaunchedEffect(Unit) {
        coroutineScope {
            launch { state.front.animateTo(1f, Motion.touch()) }
            delay(90); launch { state.peach.animateTo(1f, Motion.glide()) }
            delay(80); launch { state.mint.animateTo(1f, Motion.glide()) }
            delay(90); launch { state.line1.animateTo(1f, Motion.glide()) }
            delay(130); launch { state.line2.animateTo(1f, Motion.glide()) }
            delay(110)
            haptics.tick()
            launch { state.dot.animateTo(1f, Motion.touch()) }
            delay(330)
            state.finish()
        }
    }

    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { scope.launch { state.finish() } } },
    ) {
        val p = state.open.value.coerceIn(0f, 1f)
        drawRect(bg.copy(alpha = 1f - smooth(0f, .42f, p)))

        val u = 2.25.dp.toPx() // one unit of the icon's 108-unit grid
        val cx = size.width / 2f
        val cy = size.height / 2f
        fun x(v: Float) = cx + (v - 54f) * u
        fun y(v: Float) = cy + (v - 58f) * u

        val f = state.front.value
        val group = .72f + .28f * f
        val groupAlpha = (f * 1.6f).coerceIn(0f, 1f)
        val restAlpha = groupAlpha * (1f - smooth(0f, .28f, p))
        // as the front note opens, the rest of the mark zooms with it and fades
        val zoom = 1f + 2.2f * p

        // the front note: from its place in the mark to the whole screen
        val card0 = Rect(x(25f), y(46f), x(83f), y(82f))
        val screen = Rect(-2f, -2f, size.width + 2f, size.height + 2f)
        val card = lerpRect(card0.scaled(group, Offset(cx, cy)), screen, p)
        val cardAlpha = groupAlpha * (1f - smooth(.3f, 1f, p))

        withTransform({ scale(group * zoom, group * zoom, Offset(cx, cy)) }) {
            // the two behind rise out from under the front note
            val mintDy = (1f - state.mint.value) * 15f * u
            val peachDy = (1f - state.peach.value) * 9f * u
            topCard(NoteColor.Mint.light, Rect(x(31f), y(34f) + mintDy, x(77f), y(49f) + mintDy), 9f * u, restAlpha * state.mint.value.coerceIn(0f, 1f))
            topCard(NoteColor.Peach.light, Rect(x(28f), y(40f) + peachDy, x(80f), y(55f) + peachDy), 9f * u, restAlpha * state.peach.value.coerceIn(0f, 1f))
        }
        drawRoundRect(
            NoteColor.Butter.light.copy(alpha = cardAlpha),
            topLeft = card.topLeft, size = card.size,
            cornerRadius = CornerRadius(lerpF(10f * u * group, 0f, p)),
        )
        withTransform({ scale(group * zoom, group * zoom, Offset(cx, cy)) }) {
            // lines written from the start side (right, in Hebrew)
            val ink = Color(0xFF1D1A15)
            val l1 = state.line1.value.coerceIn(0f, 1f)
            val l2 = state.line2.value.coerceIn(0f, 1f)
            if (l1 > 0f) bar(ink.copy(alpha = .3f * restAlpha), x(72f) - 24f * u * l1, y(56f), x(72f), y(62f), 3f * u)
            if (l2 > 0f) bar(ink.copy(alpha = .15f * restAlpha), x(72f) - 14f * u * l2, y(67f), x(72f), y(73f), 3f * u)
            val d = state.dot.value
            if (d > 0f) drawCircle(accent.copy(alpha = restAlpha), radius = 6f * u * d, center = Offset(x(36f), y(70f)))
        }
    }
}

/** A note seen only by its top edge — rounded on top, square where the next one covers it. */
private fun DrawScope.topCard(color: Color, r: Rect, radius: Float, alpha: Float) {
    if (alpha <= 0f) return
    val col = color.copy(alpha = alpha)
    drawRoundRect(col, topLeft = r.topLeft, size = r.size, cornerRadius = CornerRadius(radius))
    drawRect(col, topLeft = Offset(r.left, r.top + r.height / 2f), size = Size(r.width, r.height / 2f))
}

private fun DrawScope.bar(color: Color, l: Float, t: Float, r: Float, b: Float, radius: Float) {
    if (r - l < .5f) return
    drawRoundRect(color, topLeft = Offset(l, t), size = Size(r - l, b - t), cornerRadius = CornerRadius(minOf(radius, (r - l) / 2f)))
}

private fun Rect.scaled(k: Float, pivot: Offset) = Rect(
    pivot.x + (left - pivot.x) * k, pivot.y + (top - pivot.y) * k,
    pivot.x + (right - pivot.x) * k, pivot.y + (bottom - pivot.y) * k,
)

private fun lerpF(a: Float, b: Float, t: Float) = a + (b - a) * t
private fun lerpRect(a: Rect, b: Rect, t: Float) = Rect(lerpF(a.left, b.left, t), lerpF(a.top, b.top, t), lerpF(a.right, b.right, t), lerpF(a.bottom, b.bottom, t))

/** 0 before [a], 1 after [b], eased in between. */
internal fun smooth(a: Float, b: Float, v: Float): Float {
    val t = ((v - a) / (b - a)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
