package com.ohav.noted.ui.media

import android.content.Context
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ohav.noted.data.Drawing
import com.ohav.noted.data.Media
import com.ohav.noted.design.LocalOverlays
import com.ohav.noted.design.Motion
import com.ohav.noted.design.Noted
import com.ohav.noted.design.Ph
import com.ohav.noted.design.Shadows
import com.ohav.noted.design.Squircle
import com.ohav.noted.design.press
import com.ohav.noted.design.softShadow
import com.ohav.noted.ui.editor.Ink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

enum class Tool { Pen, Marker, Eraser }

/** One stroke; points and width are in pad widths, so it scales with wherever it's drawn. */
class PenStroke(val tool: Tool, val color: String, val w: Float, val pts: FloatArray)

/** Strokes files (`*.strokes.json` in the media folder), cached once read. */
object StrokeFile {
    private val cache = LruCache<String, List<PenStroke>>(48)

    fun cached(name: String): List<PenStroke>? = cache.get(name)

    suspend fun load(ctx: Context, name: String): List<PenStroke> = cache.get(name) ?: withContext(Dispatchers.IO) {
        if (!Media.file(ctx, name).exists()) return@withContext emptyList()
        runCatching {
            val a = JSONObject(Media.file(ctx, name).readText()).getJSONArray("s")
            List(a.length()) { i ->
                val o = a.getJSONObject(i)
                val p = o.getJSONArray("p")
                PenStroke(
                    runCatching { Tool.valueOf(o.optString("t")) }.getOrDefault(Tool.Pen),
                    o.optString("c", INK), o.optDouble("w", .008).toFloat(),
                    FloatArray(p.length()) { p.optDouble(it).toFloat() },
                )
            }
        }.getOrDefault(emptyList()).also { cache.put(name, it) }
    }

    /** Always a new file, so undo in the note can still go back to the old drawing. */
    suspend fun save(ctx: Context, list: List<PenStroke>): String = withContext(Dispatchers.IO) {
        val a = JSONArray()
        list.forEach { s ->
            val p = JSONArray()
            s.pts.forEach { p.put(Math.round(it * 10000.0) / 10000.0) }
            a.put(JSONObject().put("t", s.tool.name).put("c", s.color).put("w", s.w.toDouble()).put("p", p))
        }
        val name = Media.newName("strokes.json")
        Media.file(ctx, name).writeText(JSONObject().put("v", 1).put("s", a).toString())
        cache.put(name, list)
        name
    }

    /** Crop (top, bottom) around the strokes, in pad widths, never flatter than ~3.3 : 1. */
    fun crop(list: List<PenStroke>): Pair<Float, Float> {
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        list.forEach { s ->
            var i = 1
            while (i < s.pts.size) { lo = min(lo, s.pts[i] - s.w); hi = max(hi, s.pts[i] + s.w); i += 2 }
        }
        if (lo > hi) return 0f to .5f
        var top = max(0f, lo - .05f)
        var bottom = hi + .05f
        if (bottom - top < .3f) { val mid = (top + bottom) / 2; top = max(0f, mid - .15f); bottom = top + .3f }
        return top to bottom
    }

    const val INK = "ink"
}

/** Smoothed path through the points (midpoint quadratics), scaled by [k] and moved up by [dy]. */
private fun pathOf(pts: FloatArray, k: Float, dy: Float): Path {
    val p = Path()
    val n = pts.size / 2
    if (n == 0) return p
    p.moveTo(pts[0] * k, pts[1] * k - dy)
    if (n == 1) { p.lineTo(pts[0] * k + .01f, pts[1] * k - dy); return p }
    for (i in 1 until n - 1) {
        val x = pts[2 * i] * k; val y = pts[2 * i + 1] * k - dy
        val nx = pts[2 * i + 2] * k; val ny = pts[2 * i + 3] * k - dy
        p.quadraticTo(x, y, (x + nx) / 2, (y + ny) / 2)
    }
    p.lineTo(pts[2 * n - 2] * k, pts[2 * n - 1] * k - dy)
    return p
}

private fun colorOf(tool: Tool, color: String, ink: Color, dark: Boolean): Color {
    val i = Ink.of(color)
    return if (tool == Tool.Marker) i?.mark(dark) ?: ink.copy(alpha = .26f) else i?.ink(dark) ?: ink
}

private fun DrawScope.drawStroke(path: Path, s: PenStroke, k: Float, ink: Color, dark: Boolean) =
    drawPath(path, colorOf(s.tool, s.color, ink, dark), style = Stroke(s.w * k, cap = StrokeCap.Round, join = StrokeJoin.Round))

/** A drawing in a note / on a card: drawn live from its strokes, in the theme's inks. */
@Composable
fun DrawingView(d: Drawing, modifier: Modifier = Modifier) {
    val c = Noted.colors
    val ctx = LocalContext.current.applicationContext
    val strokes by produceState(StrokeFile.cached(d.strokes), d.strokes) {
        if (value == null) { Media.awaitFile(ctx, d.strokes); value = StrokeFile.load(ctx, d.strokes) }
    }
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(d.r.coerceIn(.6f, 3.4f))
            .clip(Squircle(18.dp))
            .background(c.noteInk.copy(alpha = .045f))
            .drawWithCache {
                val list = strokes.orEmpty()
                val k = size.width
                val paths = list.map { pathOf(it.pts, k, d.y0 * k) }
                onDrawBehind { list.forEachIndexed { i, s -> drawStroke(paths[i], s, k, c.noteInk, c.isDark) } }
            },
    )
}

/**
 * Full-screen drawing pad: pen, marker, stroke eraser; six inks; three sizes; undo / redo.
 * [onDone] gets the strokes (empty = nothing drawn).
 */
@Composable
fun DrawPad(initial: List<PenStroke>, paper: Color, onClose: () -> Unit, onDone: (List<PenStroke>) -> Unit) {
    val c = Noted.colors
    val haptics = Noted.haptics
    val overlays = LocalOverlays.current
    val scope = rememberCoroutineScope()
    val strokes = remember { mutableStateListOf<PenStroke>().apply { addAll(initial) } }
    val undo = remember { ArrayList<List<PenStroke>>() }
    val redo = remember { ArrayList<List<PenStroke>>() }
    var stamp by remember { mutableIntStateOf(0) } // bumps the undo buttons
    var tool by remember { mutableStateOf(Tool.Pen) }
    var color by remember { mutableStateOf(StrokeFile.INK) }
    var sizeIx by remember { mutableIntStateOf(1) }
    val live = remember { mutableStateListOf<Offset>() }
    val enter = remember { Animatable(0f) }
    var leaving by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { enter.animateTo(1f, Motion.glide()) }

    fun push() { undo += strokes.toList(); if (undo.size > 100) undo.removeAt(0); redo.clear(); stamp++ }
    fun leave(then: () -> Unit) {
        if (leaving) return
        leaving = true
        scope.launch { enter.animateTo(0f, Motion.glide()); then() }
    }
    val dirty = stamp > 0
    fun close() {
        if (!dirty) { leave(onClose); return }
        scope.launch {
            if (overlays.dialog.confirm("לצאת בלי לשמור את הציור?", icon = Ph.ScribbleLoop, danger = true, confirm = "יציאה בלי שמירה", cancel = "המשך ציור")) leave(onClose)
        }
    }
    BackHandler { close() }

    val penSizes = listOf(2f, 3.5f, 6.5f)
    val markSizes = listOf(14f, 20f, 30f)

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = enter.value
                val k = .96f + .04f * enter.value
                scaleX = k; scaleY = k
            }
            .background(paper),
    ) {
        // the pad
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        val w = size.width.toFloat()
                        if (tool == Tool.Eraser) {
                            val before = strokes.toList()
                            val r = 16.dp.toPx() / w
                            fun erase(at: Offset) {
                                val x = at.x / w; val y = at.y / w
                                val hit = strokes.filter { s ->
                                    val rr = r + s.w / 2
                                    var i = 0; var near = false
                                    while (i < s.pts.size && !near) {
                                        val dx = s.pts[i] - x; val dy = s.pts[i + 1] - y
                                        near = dx * dx + dy * dy < rr * rr
                                        i += 2
                                    }
                                    near
                                }
                                if (hit.isNotEmpty()) { strokes.removeAll(hit); haptics.tick() }
                            }
                            erase(down.position)
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) break
                                erase(ch.position); ch.consume()
                            }
                            if (strokes.size != before.size) { undo += before; redo.clear(); stamp++ }
                        } else {
                            live.clear()
                            live.add(down.position)
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                ch.historical.forEach { live.add(it.position) }
                                live.add(ch.position)
                                ch.consume()
                                if (!ch.pressed) break
                            }
                            val pts = FloatArray(live.size * 2)
                            live.forEachIndexed { i, o -> pts[2 * i] = o.x / w; pts[2 * i + 1] = o.y / w }
                            val dp = if (tool == Tool.Marker) markSizes[sizeIx] else penSizes[sizeIx]
                            push()
                            strokes.add(PenStroke(tool, color, dp.dp.toPx() / w, pts))
                            live.clear()
                        }
                    }
                }
                .drawWithCache {
                    val k = size.width
                    val list = strokes.toList()
                    val paths = list.map { pathOf(it.pts, k, 0f) }
                    onDrawBehind { list.forEachIndexed { i, s -> drawStroke(paths[i], s, k, c.noteInk, c.isDark) } }
                }
                .drawBehind {
                    if (live.isEmpty()) return@drawBehind
                    val k = size.width
                    val pts = FloatArray(live.size * 2)
                    live.forEachIndexed { i, o -> pts[2 * i] = o.x / k; pts[2 * i + 1] = o.y / k }
                    val dp = if (tool == Tool.Marker) markSizes[sizeIx] else penSizes[sizeIx]
                    drawStroke(pathOf(pts, k, 0f), PenStroke(tool, color, dp.dp.toPx() / k, pts), k, c.noteInk, c.isDark)
                },
        )

        // top: close · undo · redo · done
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PadBtn(Ph.X, "סגירה") { close() }
            Spacer(Modifier.weight(1f))
            val s = stamp // re-read on change
            PadBtn(Ph.ArrowUUpRight, "ברירה", enabled = s >= 0 && undo.isNotEmpty()) {
                undo.removeLastOrNull()?.let { redo += strokes.toList(); strokes.clear(); strokes.addAll(it); stamp++; haptics.tick() }
            }
            PadBtn(Ph.ArrowUUpLeft, "חזרה על פעולה", enabled = s >= 0 && redo.isNotEmpty()) {
                redo.removeLastOrNull()?.let { undo += strokes.toList(); strokes.clear(); strokes.addAll(it); stamp++; haptics.tick() }
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(c.accent).press(scale = .85f) {
                    haptics.confirm(); val list = strokes.toList(); leave { onDone(list) }
                },
                contentAlignment = Alignment.Center,
            ) { Icon(Ph.Check, "סיום", Modifier.size(20.dp), tint = c.accentInk) }
        }

        // bottom: inks, then tools and sizes
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
                .softShadow(Shadows.float(c.isDark), 22.dp, c.inverse)
                .clip(Squircle(22.dp))
                .background(c.inverse)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                val inks = listOf(StrokeFile.INK) + Ink.entries.map { it.name }
                inks.forEach { name ->
                    val on = color == name && tool != Tool.Eraser
                    val sz by animateFloatAsState(if (on) 30f else 24f, Motion.touch(), label = "ink")
                    val dot = if (name == StrokeFile.INK) c.inverseInk else Ink.of(name)!!.ink(!c.isDark)
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).press(scale = .85f) {
                            color = name; if (tool == Tool.Eraser) tool = Tool.Pen; haptics.tick()
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier.size(sz.dp).clip(CircleShape).background(dot)
                                .drawBehind { if (on) drawCircle(c.inverse, size.minDimension / 2 - 3.dp.toPx(), style = Stroke(2.dp.toPx())) },
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ToolBtn(Ph.PenNib, "עט", tool == Tool.Pen) { tool = Tool.Pen }
                ToolBtn(Ph.Highlighter, "מרקר", tool == Tool.Marker) { tool = Tool.Marker }
                ToolBtn(Ph.Eraser, "מחק", tool == Tool.Eraser) { tool = Tool.Eraser }
                Spacer(Modifier.weight(1f))
                Box(Modifier.width(1.5.dp).height(26.dp).background(c.inverseInk2.copy(alpha = .3f)))
                Spacer(Modifier.weight(1f))
                listOf(5f, 9f, 14f).forEachIndexed { i, d ->
                    val on = sizeIx == i
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).press(scale = .85f) { sizeIx = i; haptics.tick() },
                        contentAlignment = Alignment.Center,
                    ) {
                        val a by animateFloatAsState(if (on) 1f else .45f, Motion.glide(), label = "size")
                        Box(Modifier.size(d.dp).graphicsLayer { alpha = a }.clip(CircleShape).background(c.inverseInk))
                    }
                }
            }
        }
    }
}

@Composable
private fun PadBtn(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Noted.colors
    val a by animateFloatAsState(if (enabled) 1f else .3f, Motion.glide(), label = "padBtn")
    Box(
        Modifier.size(44.dp).graphicsLayer { alpha = a }.clip(CircleShape).background(c.noteInk.copy(alpha = .07f))
            .press(enabled = enabled, scale = .85f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, Modifier.size(20.dp), tint = c.noteInk) }
}

@Composable
private fun ToolBtn(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    val c = Noted.colors
    val bg by animateColorAsState(if (on) c.accent else Color.Transparent, Motion.glide(), label = "tool")
    Box(
        Modifier.size(44.dp).clip(Squircle(14.dp)).background(bg).press(scale = .85f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, Modifier.size(22.dp), tint = if (on) c.accentInk else c.inverseInk) }
}
