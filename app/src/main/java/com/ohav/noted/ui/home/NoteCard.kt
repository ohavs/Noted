package com.ohav.noted.ui.home

import androidx.compose.animation.animateColorAsState
import com.ohav.noted.ui.media.CardMedia
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import kotlin.math.abs
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.AbsoluteRoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ohav.noted.data.Block
import com.ohav.noted.ui.editor.Rich
import com.ohav.noted.data.BlockType
import com.ohav.noted.data.NoteEntity
import com.ohav.noted.design.Motion
import com.ohav.noted.design.NoteColor
import com.ohav.noted.design.Noted
import com.ohav.noted.design.Ph
import com.ohav.noted.design.Shadows
import com.ohav.noted.design.Shapes
import com.ohav.noted.design.softShadow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.hypot
import kotlin.math.min

/** Long-press-then-drag hooks (reordering a lifted card). */
class LiftGestures(val onStart: (Offset) -> Unit, val onDrag: (Float) -> Unit, val onEnd: () -> Unit)

/**
 * Pulling the open card up into the editor, under the finger: [start] hands the card to the
 * editor (false = not now), [move] sets the progress 0..1, [end] lets go (open?, velocity in p/s).
 */
class CardPull(val start: () -> Boolean, val move: (Float) -> Unit, val end: (Boolean, Float) -> Unit)

private val TEXTY = setOf(BlockType.P, BlockType.H1, BlockType.H2, BlockType.H3, BlockType.Quote, BlockType.Bullet, BlockType.Num, BlockType.Check)

fun snippetOf(blocks: List<Block>): String = blocks.firstOrNull { it.type in TEXTY && it.text.isNotBlank() }?.text.orEmpty()

fun progressOf(blocks: List<Block>): Pair<Int, Int>? {
    val c = blocks.filter { it.type == BlockType.Check }
    return if (c.isEmpty()) null else c.count { it.done } to c.size
}

private val DAYS = listOf("א׳", "ב׳", "ג׳", "ד׳", "ה׳", "ו׳", "ש׳")

/** Relative, local-time reminder label — same wording as the prototype. */
fun fmtRel(millis: Long): String {
    val zone = ZoneId.systemDefault()
    val d = Instant.ofEpochMilli(millis).atZone(zone)
    val hm = "%02d:%02d".format(d.hour, d.minute)
    val dd = ChronoUnit.DAYS.between(LocalDate.now(zone), d.toLocalDate())
    val day = DAYS[d.dayOfWeek.value % 7]
    return when {
        dd == 0L -> "היום $hm"
        dd == 1L -> "מחר $hm"
        dd in 2..6 -> "יום $day $hm"
        else -> "$day ${d.dayOfMonth}/${d.monthValue} $hm"
    }
}

/**
 * One note in the stack. The surface fills whatever height the stack gives it; the content is
 * laid out at its natural height (reported through [onFullHeight]) and clipped by the surface,
 * so a collapsed card shows its head and the expanded card grows to fit everything.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NoteCard(
    n: NoteEntity,
    blocks: List<Block>,
    expanded: Boolean,
    onFullHeight: (Int) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleCheck: (String) -> Unit,
    onOpenEditor: () -> Unit,
    selecting: Boolean = false,
    selected: Boolean = false,
    lift: LiftGestures? = null,
    clipped: Boolean = false,
    pull: CardPull? = null,
) {
    val c = Noted.colors
    val t = Noted.type
    val haptics = Noted.haptics
    val note by animateColorAsState(c.note(NoteColor.of(n.color)), Motion.glide(), label = "note")
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) .975f else 1f, Motion.touch(), label = "press")
    // Animated values are read in layout/draw only; composition sees just the derived on/off
    // switches, so a card never recomposes per frame while it opens, closes or gets selected.
    val open = animateFloatAsState(if (expanded) 1f else 0f, Motion.glide(), label = "open")
    val showSummary by remember { derivedStateOf { open.value < .99f } }
    val showFull by remember { derivedStateOf { open.value > .01f } }
    val progress = remember(blocks) { progressOf(blocks) }
    val liftNow by androidx.compose.runtime.rememberUpdatedState(lift)
    val sel by animateFloatAsState(if (selecting) 1f else 0f, Motion.touch(), label = "selecting")
    val selFade = animateFloatAsState(if (selecting) 1f else 0f, Motion.glide(), label = "selFade")
    val selShown by remember { derivedStateOf { selFade.value > .005f } }
    val picked by animateFloatAsState(if (selected) 1f else 0f, Motion.touch(), label = "selected")

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { val k = s * (1f - .04f * picked); scaleX = k; scaleY = k }
            .then(
                if (lift == null) Modifier else Modifier.pointerInput(n.id) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { liftNow?.onStart?.invoke(it) },
                        onDragEnd = { liftNow?.onEnd?.invoke() },
                        onDragCancel = { liftNow?.onEnd?.invoke() },
                    ) { ch, d -> ch.consume(); liftNow?.onDrag?.invoke(d.y) }
                },
            )
            .softShadow(Shadows.card(c.isDark), 30.dp, note)
            .clip(Shapes.card)
            .background(note)
            .combinedClickable(
                interactionSource = source, indication = null,
                onClick = { haptics.light(); onClick() },
                onLongClick = { haptics.medium(); onLongClick() },
            ),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .wrapContentHeight(Alignment.Top, unbounded = true)
                .onSizeChanged { onFullHeight(it.height) }
                .padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    n.title.ifBlank { "ללא כותרת" }, style = t.cardTitle, color = c.noteInk,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (n.pinned || n.favorite) {
                    Row(Modifier.padding(top = 6.dp).graphicsLayer { alpha = 1f - sel }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (n.pinned) Icon(Ph.PushPinFill, "נעוץ", Modifier.size(16.dp), tint = c.noteInk2)
                        if (n.favorite) Icon(Ph.StarFill, "מועדף", Modifier.size(16.dp), tint = c.noteInk2)
                    }
                }
                if (progress != null) ProgressRing(progress.first, progress.second, Modifier.offset(y = (-4).dp).graphicsLayer { alpha = 1f - sel })
            }
            Box(Modifier.padding(top = 6.dp)) {
                if (showSummary) Summary(n, blocks, Modifier.graphicsLayer { alpha = 1f - open.value })
                if (expanded || showFull) {
                    FullContent(n, blocks, onToggleCheck, onOpenEditor, pull, Modifier.graphicsLayer { alpha = open.value })
                }
            }
        }
        // a long note is cut at the viewport: fade the cut and keep the way into the editor in reach
        val cut = animateFloatAsState(if (clipped && expanded) 1f else 0f, Motion.glide(), label = "cut")
        val cutShown by remember { derivedStateOf { cut.value > .01f } }
        if (cutShown) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .graphicsLayer { alpha = cut.value }
                    .background(Brush.verticalGradient(0f to note.copy(alpha = 0f), .45f to note, 1f to note))
                    .padding(start = 24.dp, end = 24.dp, top = 44.dp, bottom = 16.dp),
            ) { ExpandHint(onOpenEditor, pull, more = true) }
        }
        if (selecting || selShown) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 20.dp, end = 20.dp)
                    .size(28.dp)
                    .graphicsLayer { val k = (.4f + .6f * sel).coerceAtLeast(0f); scaleX = k; scaleY = k; alpha = selFade.value.coerceIn(0f, 1f) }
                    .clip(CircleShape)
                    .drawBehind {
                        drawCircle(c.noteInk.copy(alpha = picked.coerceIn(0f, 1f)))
                        drawCircle(c.noteInk, size.minDimension / 2 - 1.25.dp.toPx(), style = Stroke(2.5.dp.toPx()))
                    },
                contentAlignment = Alignment.Center,
            ) { if (selected) Icon(Ph.Check, null, Modifier.size(15.dp), tint = note) }
        }
    }
}

@Composable
private fun Summary(n: NoteEntity, blocks: List<Block>, modifier: Modifier) {
    val c = Noted.colors
    val t = Noted.type
    Column(modifier.fillMaxWidth()) {
        Text(snippetOf(blocks).ifBlank { " " }, style = t.snippet, color = c.noteInk2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(
            Modifier.padding(top = 8.dp).heightIn(min = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            n.reminderAt?.let { MetaItem(Ph.Bell, fmtRel(it)) }
        }
    }
}

@Composable
private fun MetaItem(icon: ImageVector, text: String?) {
    val c = Noted.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Icon(icon, null, Modifier.size(16.dp), tint = c.noteInk2)
        if (text != null) Text(text, style = Noted.type.meta, color = c.noteInk2)
    }
}

@Composable
private fun FullContent(n: NoteEntity, blocks: List<Block>, onToggleCheck: (String) -> Unit, onOpenEditor: () -> Unit, pull: CardPull?, modifier: Modifier) {
    val c = Noted.colors
    val t = Noted.type
    // rich text is built once per block content, not on every pass
    val rich = remember(blocks, c.isDark) { blocks.associate { it.id to Rich.annotate(it.text, it.spans, c.isDark, links = true) } }
    Column(modifier.fillMaxWidth()) {
        var num = 0
        blocks.forEach { b ->
            if (b.type != BlockType.Num) num = 0
            val ann = rich[b.id] ?: androidx.compose.ui.text.AnnotatedString(b.text)
            when (b.type) {
                BlockType.P -> if (b.text.isNotBlank()) Text(ann, style = t.note, color = c.noteInk, modifier = Modifier.padding(bottom = 12.dp))
                BlockType.H1, BlockType.H2, BlockType.H3 ->
                    Text(ann, style = t.note.copy(fontWeight = FontWeight.ExtraBold, fontSize = 16.sp), color = c.noteInk, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                BlockType.Quote -> Row(Modifier.padding(top = 8.dp, bottom = 12.dp).height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(c.noteInk, RoundedCornerShape(2.dp)))
                    Spacer(Modifier.width(12.dp))
                    Text(ann, style = t.note.copy(fontStyle = FontStyle.Italic), color = c.noteInk)
                }
                BlockType.Bullet -> ListRow(null, ann, b.indent)
                BlockType.Num -> { num++; ListRow("$num.", ann, b.indent) }
                BlockType.Check -> CheckRow(b.text, b.done, c.note(NoteColor.of(n.color))) { onToggleCheck(b.id) }
                BlockType.Divider -> Box(Modifier.padding(vertical = 10.dp).fillMaxWidth().height(1.5.dp).background(c.noteInk.copy(alpha = .14f)))
                else -> CardMedia(b, Modifier.padding(top = 4.dp, bottom = 12.dp))
            }
        }
        n.reminderAt?.let { at ->
            Row(
                Modifier
                    .padding(top = 8.dp)
                    .clip(AbsoluteRoundedCornerShape(12.dp, 12.dp, 12.dp, 4.dp))
                    .background(c.noteInk)
                    .padding(start = 12.dp, end = 14.dp, top = 9.dp, bottom = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Ph.BellRinging, null, Modifier.size(17.dp), tint = c.note(NoteColor.of(n.color)))
                Text(fmtRel(at), style = t.label, color = c.note(NoteColor.of(n.color)))
            }
        }
        ExpandHint(onOpenEditor, pull)
    }
}

@Composable
private fun ListRow(marker: String?, text: androidx.compose.ui.text.AnnotatedString, indent: Int = 0) {
    val c = Noted.colors
    val t = Noted.type
    Row(Modifier.padding(start = (indent * 18).dp, top = 3.dp, bottom = 3.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (marker == null) Box(Modifier.padding(top = 10.dp).size(6.dp).clip(CircleShape).background(c.noteInk))
        else Text(marker, style = t.note.copy(fontWeight = FontWeight.Bold), color = c.noteInk)
        Text(text, style = t.note, color = c.noteInk)
    }
}

/**
 * Grab handle under the open card: tap → editor; pull up → the editor grows out of the card
 * under the finger and settles open or back depending on how far and how fast it was thrown.
 */
@Composable
private fun ExpandHint(onOpenEditor: () -> Unit, pull: CardPull?, more: Boolean = false) {
    val c = Noted.colors
    val openNow by androidx.compose.runtime.rememberUpdatedState(onOpenEditor)
    val pullNow by androidx.compose.runtime.rememberUpdatedState(pull)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .offset(y = 10.dp)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { openNow() }
            .pointerInput(Unit) {
                // Claimed in the Initial pass: once the finger heads up past the slop, the stack's
                // scroll (and the card's tap) never see this gesture.
                val dist = 440.dp.toPx()
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    var dx = 0f
                    var dy = 0f
                    var dragging = false
                    var held: CardPull? = null
                    var lifted = false
                    try {
                        while (true) {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) { lifted = true; if (dragging) ch.consume(); break }
                            val d = ch.position - ch.previousPosition
                            dx += d.x; dy += d.y
                            tracker.addPosition(ch.uptimeMillis, ch.position)
                            if (!dragging) {
                                // down or sideways belongs to the stack (scroll / category swipe)
                                if (dy > slop || abs(dx) > slop && abs(dx) > abs(dy)) return@awaitEachGesture
                                if (dy < -slop) dragging = true else continue
                            }
                            ch.consume()
                            val p = pullNow
                            if (held == null && p != null && p.start()) held = p
                            held?.move(((-dy - slop) / dist).coerceIn(0f, 1f))
                        }
                    } finally {
                        val h = held
                        if (dragging) {
                            val v = tracker.calculateVelocity().y
                            val f = ((-dy - slop) / dist).coerceIn(0f, 1f)
                            val open = lifted && (v < -700f || (f > .16f && v < 500f))
                            if (h != null) h.end(open, -v / dist)
                            else if (lifted && -dy > 40.dp.toPx()) openNow()
                        }
                    }
                }
            }
            .padding(top = 12.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(Modifier.size(38.dp, 5.dp).clip(RoundedCornerShape(3.dp)).background(c.noteInk.copy(alpha = .25f)))
        Text(if (more) "יש עוד — משיכה למעלה לפתק המלא" else "משיכה למעלה לעריכה", style = Noted.type.caption, color = c.noteInk2)
    }
}

/** Checklist row: squircle box fills and draws its tick, text greys out under a growing strike. */
@Composable
fun CheckRow(text: String, done: Boolean, tick: Color, onToggle: () -> Unit) {
    val c = Noted.colors
    val t = Noted.type
    val haptics = Noted.haptics
    val p by animateFloatAsState(if (done) 1f else 0f, Motion.glide(), label = "check")
    val breathe = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val noteInk = c.noteInk
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                if (done) haptics.light() else haptics.confirm()
                scope.launch { breathe.animateTo(1f, Motion.touch(), initialVelocity = -9f) }
                onToggle()
            }
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .padding(top = 1.dp)
                .size(26.dp)
                .graphicsLayer { scaleX = breathe.value; scaleY = breathe.value }
                .drawBehind {
                    val r = CornerRadius(9.dp.toPx())
                    val bw = 2.2.dp.toPx()
                    drawRoundRect(noteInk.copy(alpha = p), cornerRadius = r)
                    drawRoundRect(
                        lerp(noteInk.copy(alpha = .45f), noteInk, p),
                        topLeft = Offset(bw / 2, bw / 2), size = Size(size.width - bw, size.height - bw),
                        cornerRadius = CornerRadius(r.x - bw / 2), style = Stroke(bw),
                    )
                    // tick "M3.5 8.5l3 3 6-7" on a 16-unit box, centred, drawn in the card colour
                    if (p > 0f) {
                        val u = 1.dp.toPx()
                        val o = (size.width - 16 * u) / 2
                        val a = Offset(o + 3.5f * u, o + 8.5f * u)
                        val b = Offset(o + 6.5f * u, o + 11.5f * u)
                        val e = Offset(o + 12.5f * u, o + 4.5f * u)
                        val l1 = hypot(3f, 3f)
                        val l2 = hypot(6f, 7f)
                        val drawn = (l1 + l2) * p
                        val sw = 3 * u
                        if (drawn <= l1) drawLine(tick, a, a + (b - a) * (drawn / l1), sw, StrokeCap.Round)
                        else {
                            drawLine(tick, a, b, sw, StrokeCap.Round)
                            drawLine(tick, b, b + (e - b) * min(1f, (drawn - l1) / l2), sw, StrokeCap.Round)
                        }
                    }
                },
        )
        Text(
            text, style = t.note, color = lerp(c.noteInk, c.noteInk2, p),
            onTextLayout = { layout = it },
            modifier = Modifier.weight(1f).drawWithContent {
                drawContent()
                val l = layout ?: return@drawWithContent
                if (p <= 0f) return@drawWithContent
                val rtl = layoutDirection == LayoutDirection.Rtl
                for (i in 0 until l.lineCount) {
                    val y = l.getLineTop(i) + (l.getLineBottom(i) - l.getLineTop(i)) * .52f
                    val left = l.getLineLeft(i)
                    val right = l.getLineRight(i)
                    val w = (right - left) * p
                    val (x0, x1) = if (rtl) right to right - w else left to left + w
                    drawLine(noteInk.copy(alpha = .55f), Offset(x0, y), Offset(x1, y), 2.dp.toPx(), StrokeCap.Round)
                }
            },
        )
    }
}

@Composable
fun ProgressRing(done: Int, total: Int, modifier: Modifier = Modifier) {
    val c = Noted.colors
    val frac by animateFloatAsState(if (total == 0) 0f else done / total.toFloat(), Motion.touch(), label = "ring")
    val pop = remember { Animatable(1f) }
    val first = remember { booleanArrayOf(true) }
    val full = done == total
    LaunchedEffect(full) {
        if (first[0]) { first[0] = false; return@LaunchedEffect }
        if (full) pop.animateTo(1f, Motion.touch(), initialVelocity = 7f)
    }
    val ink = c.noteInk
    Box(
        modifier
            .size(46.dp)
            .graphicsLayer { scaleX = pop.value; scaleY = pop.value }
            .drawBehind {
                val sw = 4.5.dp.toPx()
                val r = size.width * 18f / 46f
                drawCircle(ink.copy(alpha = .12f), r, style = Stroke(sw))
                drawArc(
                    ink, startAngle = -90f, sweepAngle = -360f * frac, useCenter = false,
                    topLeft = Offset(center.x - r, center.y - r), size = Size(2 * r, 2 * r),
                    style = Stroke(sw, cap = StrokeCap.Round),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "$done/$total", color = ink,
            style = Noted.type.caption.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
        )
    }
}
