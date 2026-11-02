package com.ohav.noted.design

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Springy press: scales down under the finger on the touch spring, light haptic on click. No ripple. */
@Composable
fun Modifier.press(
    enabled: Boolean = true,
    scale: Float = 0.94f,
    haptic: Boolean = true,
    onClick: () -> Unit,
): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) scale else 1f, Motion.touch(), label = "press")
    val haptics = Noted.haptics
    return this
        .graphicsLayer { scaleX = s; scaleY = s }
        .clickable(interactionSource = source, indication = null, enabled = enabled) {
            if (haptic) haptics.light()
            onClick()
        }
}

@Composable
fun IconButton(icon: ImageVector, contentDescription: String, modifier: Modifier = Modifier, tint: androidx.compose.ui.graphics.Color = Noted.colors.ink, onClick: () -> Unit) {
    Box(
        modifier.size(44.dp).clip(CircleShape).press(scale = .86f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription, Modifier.size(24.dp), tint = tint) }
}

enum class BtnKind { Accent, Dark, Quiet, Danger }

/** 56dp squircle button — the prototype's `.btn`. */
@Composable
fun Btn(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, kind: BtnKind = BtnKind.Accent, onClick: () -> Unit) {
    val c = Noted.colors
    val (bg, fg) = when (kind) {
        BtnKind.Accent -> c.accent to c.accentInk
        BtnKind.Dark -> c.inverse to c.inverseInk
        BtnKind.Quiet -> androidx.compose.ui.graphics.Color.Transparent to c.ink2
        BtnKind.Danger -> c.danger to androidx.compose.ui.graphics.Color.White
    }
    Row(
        modifier.height(56.dp).press(scale = .95f, onClick = onClick).clip(Shapes.md).background(bg).padding(horizontal = 26.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(20.dp), tint = fg)
        Text(text, style = Noted.type.button, color = fg, maxLines = 1)
    }
}

/** Custom toggle — squircle track, thumb rides the touch spring. */
@Composable
fun NotedSwitch(checked: Boolean, modifier: Modifier = Modifier) {
    val c = Noted.colors
    val x by animateDpAsState(if (checked) 22.dp else 0.dp, Motion.touch(), label = "thumb")
    val track = if (checked) c.accent else c.ink3.copy(alpha = .45f)
    Box(modifier.size(54.dp, 32.dp).clip(Squircle(16.dp)).background(track).padding(3.dp)) {
        // offset() flips in RTL, so "on" moves toward end like every other control
        Box(Modifier.offset(x = x).size(26.dp).clip(CircleShape).background(if (checked) c.accentInk else c.surface))
    }
}

// ------------------------------------------------------------------ toasts

@Stable
class ToastState {
    data class Toast(val id: Long, val message: String, val icon: ImageVector?, val action: String?, val onAction: (() -> Unit)?, val millis: Long)

    var current by mutableStateOf<Toast?>(null)
        private set

    fun show(message: String, icon: ImageVector? = null, action: String? = null, millis: Long = 2600, onAction: (() -> Unit)? = null) {
        current = Toast(System.nanoTime(), message, icon, action, onAction, millis)
    }

    fun dismiss(id: Long) {
        if (current?.id == id) current = null
    }
}

/** Inverse squircle toast with optional undo + draining timer; flick sideways to dismiss. */
@Composable
fun ToastHost(state: ToastState, modifier: Modifier = Modifier, lift: Dp = 0.dp) {
    val c = Noted.colors
    val t = Noted.type
    val haptics = Noted.haptics
    val toast = state.current
    var shown by remember { mutableStateOf(toast) }
    if (toast != null) shown = toast
    AnimatedVisibility(
        visible = toast != null,
        modifier = modifier.navigationBarsPadding().padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 18.dp + lift),
        enter = slideInVertically(Motion.touch()) { it } + fadeIn(Motion.glide()) + scaleIn(Motion.touch(), initialScale = .9f),
        exit = slideOutVertically(Motion.glide()) { it / 2 } + fadeOut(Motion.glide()) + scaleOut(Motion.glide(), targetScale = .94f),
    ) {
        val cur = shown ?: return@AnimatedVisibility
        val drag = remember(cur.id) { Animatable(0f) }
        val timer = remember(cur.id) { Animatable(1f) }
        val scope = rememberCoroutineScope()
        LaunchedEffect(cur.id) {
            haptics.tick()
            launch { timer.animateTo(0f, tween(cur.millis.toInt(), easing = LinearEasing)) }
            delay(cur.millis)
            state.dismiss(cur.id)
        }
        Row(
            Modifier
                .fillMaxWidth()
                .offset { IntOffset(drag.value.roundToInt(), 0) }
                .graphicsLayer { alpha = 1f - (abs(drag.value) / 600f).coerceIn(0f, .8f) }
                .pointerInput(cur.id) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (abs(drag.value) > 160f) { state.dismiss(cur.id) } else drag.animateTo(0f, Motion.touch())
                            }
                        },
                    ) { _, d -> scope.launch { drag.snapTo(drag.value + d) } }
                }
                .clip(Shapes.lg)
                .background(c.inverse)
                .drawBehind {
                    if (cur.action != null) drawRect(c.inverseInk2.copy(alpha = .35f), topLeft = androidx.compose.ui.geometry.Offset(if (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl) size.width * (1f - timer.value) else 0f, size.height - 3.dp.toPx()), size = androidx.compose.ui.geometry.Size(size.width * timer.value, 3.dp.toPx()))
                }
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (cur.icon != null) {
                Box(Modifier.size(32.dp).clip(CircleShape).background(c.inverse2), contentAlignment = Alignment.Center) {
                    Icon(cur.icon, null, Modifier.size(18.dp), tint = c.accent)
                }
                Spacer(Modifier.width(12.dp))
            }
            Text(cur.message, style = t.label, color = c.inverseInk, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
            if (cur.action != null) {
                Text(
                    cur.action, style = t.label, color = c.accent,
                    modifier = Modifier.clip(Shapes.sm).press { cur.onAction?.invoke(); state.dismiss(cur.id) }.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
fun VSpace(h: Dp) = Spacer(Modifier.height(h))

// ------------------------------------------------------------------ shadows

/** CSS-style box shadow (blur, y offset, spread) drawn under a squircle; same tokens as the prototype. */
data class SoftShadow(val color: androidx.compose.ui.graphics.Color, val blur: Dp, val dy: Dp, val spread: Dp)

object Shadows {
    /** Cards cast upward onto the card behind them in the stack. */
    fun card(dark: Boolean) = if (dark) SoftShadow(androidx.compose.ui.graphics.Color(0xCC000000), 28.dp, (-10).dp, (-16).dp)
    else SoftShadow(androidx.compose.ui.graphics.Color(0x61281E0A), 28.dp, (-10).dp, (-18).dp)

    fun float(dark: Boolean) = if (dark) SoftShadow(androidx.compose.ui.graphics.Color(0xCC000000), 50.dp, 18.dp, (-12).dp)
    else SoftShadow(androidx.compose.ui.graphics.Color(0x731E1405), 50.dp, 18.dp, (-18).dp)
}

fun Modifier.softShadow(shadow: SoftShadow, radius: Dp, fill: androidx.compose.ui.graphics.Color): Modifier =
    softShadow { ShadowFrame(shadow, radius, fill) }

/** One frame of a shadow that changes while something moves (read in draw, never in composition). */
class ShadowFrame(val shadow: SoftShadow, val radius: Dp, val fill: androidx.compose.ui.graphics.Color)

/** Blend two shadows; at 0 it is [a], at 1 it is [b]. */
fun lerpShadow(a: SoftShadow, b: SoftShadow, f: Float): SoftShadow {
    val t = f.coerceIn(0f, 1f)
    return SoftShadow(
        androidx.compose.ui.graphics.lerp(a.color, b.color, t),
        androidx.compose.ui.unit.lerp(a.blur, b.blur, t),
        androidx.compose.ui.unit.lerp(a.dy, b.dy, t),
        androidx.compose.ui.unit.lerp(a.spread, b.spread, t),
    )
}

/** Same shadow with its colour faded by [k] — a shadow that leaves fades, it never pops. */
fun SoftShadow.fade(k: Float) = copy(color = color.copy(alpha = color.alpha * k.coerceIn(0f, 1f)))

/** Animated shadow: [frame] is evaluated every draw; null or a transparent colour draws nothing. */
fun Modifier.softShadow(frame: () -> ShadowFrame?): Modifier = drawBehind {
    val fr = frame() ?: return@drawBehind
    val shadow = fr.shadow
    if (shadow.color.alpha <= .004f) return@drawBehind
    val fill = fr.fill
    val sp = shadow.spread.toPx()
    val w = size.width + 2 * sp
    val h = size.height + 2 * sp
    if (w <= 0f || h <= 0f) return@drawBehind
    // A rounded rect, not the squircle path: Skia blurs rounded rects analytically on the GPU,
    // while a path that changes every frame (transitions) is re-rasterised and blurred on the
    // render thread — enough to drop a 120 Hz panel to 60. With the radius at the squircle's
    // extent the rect sits inside the squircle, so its fill never peeks out; under the blur the
    // corner difference is invisible.
    val rad = min(fr.radius.toPx() * 1.5f, min(w, h) / 2f)
    val paint = androidx.compose.ui.graphics.Paint()
    paint.asFrameworkPaint().apply {
        isAntiAlias = true
        color = android.graphics.Color.argb(255, (fill.red * 255).toInt(), (fill.green * 255).toInt(), (fill.blue * 255).toInt())
        setShadowLayer(max(.5f, shadow.blur.toPx() / 2f), 0f, shadow.dy.toPx(), android.graphics.Color.argb(
            (shadow.color.alpha * 255).toInt(), (shadow.color.red * 255).toInt(), (shadow.color.green * 255).toInt(), (shadow.color.blue * 255).toInt(),
        ))
    }
    drawIntoCanvas { canvas ->
        canvas.save()
        canvas.translate(-sp, -sp)
        canvas.drawRoundRect(.5f, .5f, w - .5f, h - .5f, rad, rad, paint)
        canvas.restore()
    }
}
