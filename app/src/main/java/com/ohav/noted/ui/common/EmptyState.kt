package com.ohav.noted.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ohav.noted.design.Motion
import com.ohav.noted.design.NoteColor
import com.ohav.noted.design.Noted
import com.ohav.noted.design.NotedColors

/** Illustrations from the prototype's empty states, drawn on a 168×140 grid. */
enum class EmptyArt { Stack, Search, Shared, Places, Agenda }

private fun DrawScope.rr(x: Float, y: Float, w: Float, h: Float, r: Float, color: Color) =
    drawRoundRect(color, Offset(x, y), Size(w, h), CornerRadius(r, r))

private fun path(d: String) = PathParser().parsePathString(d).toPath()

private fun DrawScope.drawArt(art: EmptyArt, c: NotedColors) {
    val ink = c.noteInk
    when (art) {
        EmptyArt.Stack -> {
            rotate(-6f, Offset(84f, 91f)) { rr(30f, 58f, 108f, 66f, 24f, c.note(NoteColor.Sand)) }
            rotate(4f, Offset(84f, 75f)) { rr(26f, 40f, 116f, 70f, 26f, c.note(NoteColor.Mint)) }
            rr(22f, 22f, 124f, 74f, 28f, c.note(NoteColor.Butter))
            rr(42f, 42f, 58f, 9f, 4.5f, ink.copy(alpha = .28f))
            rr(42f, 60f, 36f, 9f, 4.5f, ink.copy(alpha = .14f))
            plusBadge(122f, 96f, c)
        }
        EmptyArt.Search -> {
            drawCircle(c.ink3, 36f, Offset(74f, 62f), style = Stroke(9f))
            drawLine(c.ink3, Offset(101f, 89f), Offset(127f, 115f), 11f, StrokeCap.Round)
            drawLine(c.accent, Offset(60f, 62f), Offset(88f, 62f), 7f, StrokeCap.Round)
        }
        EmptyArt.Shared -> {
            rotate(-8f, Offset(64f, 76f)) { rr(18f, 34f, 92f, 84f, 26f, c.note(NoteColor.Sky)) }
            rotate(6f, Offset(104f, 64f)) {
                rr(58f, 22f, 92f, 84f, 26f, c.note(NoteColor.Peach))
                rr(76f, 44f, 46f, 7f, 3.5f, ink.copy(alpha = .35f))
                rr(76f, 58f, 30f, 7f, 3.5f, ink.copy(alpha = .2f))
            }
            listOf(44f to Color(0xFFE9A87F), 68f to Color(0xFFB99BE6)).forEach { (x, col) ->
                drawCircle(c.bg, 18.5f, Offset(x, 118f)); drawCircle(col, 16f, Offset(x, 118f))
            }
            drawCircle(c.bg, 18.5f, Offset(92f, 118f))
            plusBadge(92f, 118f, c, r = 16f, arm = 6f, w = 3f)
        }
        EmptyArt.Places -> {
            drawPath(path("M14 40l46-16 48 16 46-16v84l-46 16-48-16-46 16z"), c.note(NoteColor.Mint))
            drawLine(ink.copy(alpha = .12f), Offset(60f, 24f), Offset(60f, 108f), 3f)
            drawLine(ink.copy(alpha = .12f), Offset(108f, 40f), Offset(108f, 124f), 3f)
            drawPath(
                path("M28 92c20-8 30 10 52-2s34-6 50 4"), ink.copy(alpha = .25f),
                style = Stroke(4f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 9f))),
            )
            drawCircle(c.accent.copy(alpha = .14f), 30f, Offset(98f, 58f))
            drawPath(path("M98 30c-12 0-21 9-21 21 0 16 21 33 21 33s21-17 21-33c0-12-9-21-21-21z"), c.accent)
            drawCircle(c.accentInk, 7.5f, Offset(98f, 51f))
        }
        EmptyArt.Agenda -> {
            rotate(-5f, Offset(80f, 80f)) { rr(22f, 38f, 116f, 86f, 26f, c.note(NoteColor.Lilac)) }
            rotate(3f, Offset(92f, 70f)) {
                rr(34f, 26f, 116f, 86f, 26f, c.note(NoteColor.Butter))
                rr(54f, 50f, 50f, 9f, 4.5f, ink.copy(alpha = .28f))
                rr(54f, 68f, 30f, 9f, 4.5f, ink.copy(alpha = .14f))
            }
            drawCircle(c.accent, 20f, Offset(124f, 102f))
            drawLine(c.accentInk, Offset(124f, 102f), Offset(124f, 92f), 3.5f, StrokeCap.Round)
            drawLine(c.accentInk, Offset(124f, 102f), Offset(131f, 106f), 3.5f, StrokeCap.Round)
        }
    }
}

private fun DrawScope.plusBadge(cx: Float, cy: Float, c: NotedColors, r: Float = 20f, arm: Float = 8f, w: Float = 3.5f) {
    drawCircle(c.accent, r, Offset(cx, cy))
    drawLine(c.accentInk, Offset(cx - arm, cy), Offset(cx + arm, cy), w, StrokeCap.Round)
    drawLine(c.accentInk, Offset(cx, cy - arm), Offset(cx, cy + arm), w, StrokeCap.Round)
}

@Composable
fun EmptyArtwork(art: EmptyArt, modifier: Modifier = Modifier) {
    val c = Noted.colors
    Canvas(modifier.size(168.dp, 140.dp)) {
        scale(size.width / 168f, size.height / 140f, pivot = Offset.Zero) { drawArt(art, c) }
    }
}

/** Illustration, display title, one line of help, optional action. Rises in on the glide spring. */
@Composable
fun EmptyState(
    art: EmptyArt,
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val c = Noted.colors
    val t = Noted.type
    val rise = remember { Animatable(0f) }
    LaunchedEffect(Unit) { rise.animateTo(1f, Motion.glide()) }
    Column(
        modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = rise.value; translationY = (1f - rise.value) * 20.dp.toPx() }
            .padding(horizontal = 40.dp, vertical = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        EmptyArtwork(art)
        Spacer(Modifier.height(6.dp))
        Text(title, style = t.emptyTitle, color = c.ink, textAlign = TextAlign.Center)
        Text(text, style = t.body, color = c.ink2, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 260.dp))
        if (action != null) {
            Spacer(Modifier.height(8.dp))
            action()
        }
    }
}

/** Side gutter shared by every page. */
val Gutter = 16.dp
