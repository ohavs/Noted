package com.ohav.noted.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.ohav.noted.data.Block
import com.ohav.noted.data.NoteEntity
import com.ohav.noted.design.Motion
import com.ohav.noted.design.NoteColor
import com.ohav.noted.design.Noted
import com.ohav.noted.design.Ph
import com.ohav.noted.design.Shadows
import com.ohav.noted.design.Shapes
import com.ohav.noted.design.Squircle
import com.ohav.noted.design.press
import com.ohav.noted.design.softShadow
import com.ohav.noted.design.ShadowFrame
import com.ohav.noted.design.fade
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Long press lifts a card out of the stack: a clone floats above a blurred scrim with the action
 * menu beside it. Dragging past a few dp closes the menu and turns it into a reorder; letting go
 * flies the clone back into its (new) slot. Dragged up onto a category name (the giant title or
 * the subcategory track), the clone shrinks around the finger and the name lights up; letting go
 * there drops the note into that category — the clone flies into the name.
 */
@Stable
class LiftState {
    var id by mutableStateOf<String?>(null)
        internal set
    var from by mutableStateOf(Rect.Zero)
        internal set
    var touchX by mutableFloatStateOf(0f)
    var touchY by mutableFloatStateOf(0f)
    var dx by mutableFloatStateOf(0f)
    var dy by mutableFloatStateOf(0f)
    var dragging by mutableStateOf(false)
    var menu by mutableStateOf(false)
    var settling by mutableStateOf(false)
    var dragged by mutableStateOf(false)

    /** 0→1: scale 1→1.045, scrim and blur in. */
    val pop = Animatable(0f)

    /** 0→1: the clone travels from where it was dropped to its slot. */
    val settle = Animatable(0f)

    /** Clone height (px) — an open card shrinks to stack height once dragging starts. */
    val height = Animatable(0f)

    /** Visible order while dragging / until the database catches up. */
    var order by mutableStateOf<List<String>?>(null)
    var idx by mutableIntStateOf(0)
    var startTop = 0f
    var dyAtDrag = 0f

    /** Last known coordinates of every card in the stack. */
    val coords = HashMap<String, LayoutCoordinates>()

    /** Category names a card can be dropped on: key → (category id, coordinates). */
    val targets = HashMap<String, Pair<String, LayoutCoordinates>>()
    /** The category name under the finger, if it can take the card. */
    var hover by mutableStateOf<String?>(null)
    /** 0→1: the clone flies from the finger into [dropAt] (root px) and fades. */
    val drop = Animatable(0f)
    var dropAt by mutableStateOf(Offset.Zero)
    /** Top of the stack's viewport (root px): above it the finger is over the header, not a slot. */
    var stackTop = 0f

    /** Root position of the finger while dragging. */
    fun finger() = Offset(from.left + touchX + dx, from.top + touchY + dy)

    /** The droppable category under [p], by its name's bounds (a little forgiving). */
    fun targetAt(p: Offset, slop: Float, ok: (String) -> Boolean): Pair<String, Rect>? {
        var best: Pair<String, Rect>? = null
        for ((cat, lc) in targets.values) {
            if (!lc.isAttached || !ok(cat)) continue
            val r = lc.boundsInRoot()
            if (r.width < 1f) continue
            if (p.x in r.left - slop..r.right + slop && p.y in r.top - slop..r.bottom + slop) {
                if (best == null || abs(r.center.x - p.x) < abs(best.second.center.x - p.x)) best = cat to r
            }
        }
        return best
    }

    fun slotRect(): Rect? = id?.let { coords[it] }?.takeIf { it.isAttached }?.boundsInRoot()

    internal fun begin(noteId: String, rect: Rect) {
        id = noteId
        from = rect
        dx = 0f
        dy = 0f
        hover = null
        dragging = false
        dragged = false
        settling = false
        menu = true
    }

    internal fun clear() {
        id = null
        hover = null
        menu = false
        dragging = false
        settling = false
    }
}

/** What the layer needs from the home screen each frame. */
class LiftUi(
    val note: NoteEntity?,
    val blocks: List<Block>,
    val onAction: (String) -> Unit,
    val onColor: (NoteColor) -> Unit,
    val onDismiss: () -> Unit,
)

@Composable
fun LiftLayer(lift: LiftState, ui: LiftUi) {
    val n = ui.note ?: return
    if (lift.id == null) return
    val c = Noted.colors
    val density = LocalDensity.current
    val screenH = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    var menuH by androidx.compose.runtime.remember { mutableFloatStateOf(with(density) { 330.dp.toPx() }) }

    // absolute top-left: the clone and the menu are placed by left edges in root coordinates
    // (a plain Box would park them against the right edge in RTL and then add the offset)
    Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.AbsoluteAlignment.TopLeft) {
        // scrim — taps dismiss while the menu is up; it lifts while dragging, so the category names
        // the card can be dropped on read clearly
        val dim by androidx.compose.animation.core.animateFloatAsState(if (lift.dragging) .3f else 1f, Motion.glide(), label = "dim")
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = (lift.pop.value * dim).coerceIn(0f, 1f) }
                .background(c.scrim)
                .pointerInput(Unit) { detectTapGestures { ui.onDismiss() } },
        )

        // the clone: over a category name it shrinks around the finger, as if about to go in
        val shrink = androidx.compose.runtime.remember { Animatable(0f) }
        androidx.compose.runtime.LaunchedEffect(lift.hover != null) { shrink.animateTo(if (lift.hover != null) 1f else 0f, Motion.touch()) }
        val floatShadow = Shadows.float(c.isDark)
        val noteFill = c.note(NoteColor.of(n.color))
        Box(
            Modifier
                .absoluteOffset {
                    val r = currentRect(lift)
                    IntOffset(r.left.roundToInt(), r.top.roundToInt())
                }
                .graphicsLayer {
                    val r = currentRect(lift)
                    transformOrigin = TransformOrigin(.5f, .3f)
                    val k = 1f + .045f * lift.pop.value
                    val p = lift.drop.value
                    val sh = (1f - .58f * shrink.value) * (1f - .7f * p)
                    scaleX = k * sh; scaleY = k * sh
                    // keep the finger's point of the card under the finger while it shrinks, then
                    // carry that point into the name
                    val fx = lift.touchX.coerceIn(0f, size.width) - size.width * .5f
                    val fy = lift.touchY.coerceIn(0f, size.height) - size.height * .3f
                    val f = lift.finger()
                    translationX = fx * k * (1f - sh) + (lift.dropAt.x - f.x) * p
                    translationY = fy * k * (1f - sh) + (lift.dropAt.y - f.y) * p
                    alpha = if (r.width > 0f) (1f - p * p).coerceIn(0f, 1f) else 0f
                }
                .then(with(density) { Modifier.size(lift.from.width.toDp(), lift.height.value.toDp()) })
                // the float shadow leaves with the pop; the card's own shadow (inside) stays, so the
                // drop into the slot is the stack's shadow already
                .softShadow { ShadowFrame(floatShadow.fade(lift.pop.value), 30.dp, noteFill) },
        ) {
            NoteCard(
                n = n, blocks = ui.blocks, expanded = false,
                onFullHeight = {}, onClick = {}, onLongClick = {}, onToggleCheck = {}, onOpenEditor = {},
            )
        }

        // the menu, below the card when it fits, otherwise above
        if (lift.menu || lift.pop.value > .01f && !lift.dragging && !lift.settling) {
            val r = lift.from
            val gap = with(density) { 14.dp.toPx() }
            val (top, up) = when {
                r.bottom + gap + menuH < screenH - 10f -> r.bottom + gap to false
                r.top - gap - menuH > 10f -> r.top - gap - menuH to true
                else -> screenH - menuH - with(density) { 20.dp.toPx() } to true
            }
            ActionMenu(
                n,
                visible = lift.menu,
                up = up,
                modifier = Modifier
                    .absoluteOffset { IntOffset(0, top.roundToInt()) }
                    .padding(horizontal = 20.dp)
                    .onSizeChanged { menuH = it.height.toFloat() },
                onColor = ui.onColor,
                onAction = ui.onAction,
            )
        }
    }
}

/** Where the clone is drawn right now (root px). */
private fun currentRect(lift: LiftState): Rect {
    val f = lift.from
    val start = Rect(f.left + lift.dx, f.top + lift.dy, f.right + lift.dx, f.top + lift.dy + lift.height.value)
    val p = lift.settle.value
    if (p <= 0f) return start
    val to = lift.slotRect() ?: return start
    return Rect(
        lerp(start.left, to.left, p), lerp(start.top, to.top, p),
        lerp(start.right, to.right, p), lerp(start.bottom, to.bottom, p),
    )
}

private val MenuDanger = Color(0xFFFF8B7A)

@Composable
private fun ActionMenu(
    n: NoteEntity,
    visible: Boolean,
    up: Boolean,
    modifier: Modifier,
    onColor: (NoteColor) -> Unit,
    onAction: (String) -> Unit,
) {
    val c = Noted.colors
    val t = Noted.type
    val p = androidx.compose.runtime.remember { Animatable(0f) }
    val fade = androidx.compose.runtime.remember { Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(visible) {
        kotlinx.coroutines.coroutineScope {
            launch { fade.animateTo(if (visible) 1f else 0f, Motion.glide()) }
            p.animateTo(if (visible) 1f else 0f, Motion.touch())
        }
    }
    Column(
        modifier
            .fillMaxWidth()
            .graphicsLayer {
                transformOrigin = TransformOrigin(.5f, if (up) 1f else 0f)
                val k = .9f + .1f * p.value
                scaleX = k; scaleY = k
                translationY = (1f - p.value) * (if (up) 8.dp.toPx() else -8.dp.toPx())
                alpha = fade.value
            }
            .softShadow(Shadows.float(c.isDark), 26.dp, c.inverse)
            .clip(Shapes.lg)
            .background(c.inverse)
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 14.dp),
    ) {
        // swatches
        // each swatch takes an equal share of the width, so 8 never overflow on a narrow card
        Row(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            NoteColor.entries.forEach { col ->
                val on = NoteColor.of(n.color) == col
                Box(
                    Modifier
                        .weight(1f)
                        .height(42.dp)
                        .press(scale = .82f) { onColor(col) }
                        .drawBehind {
                            val ring = minOf(20.dp.toPx(), size.width / 2f - 1.dp.toPx())
                            if (on) drawCircle(c.inverseInk, ring, style = Stroke(2.dp.toPx()))
                            drawCircle(c.note(col), ring - 4.5.dp.toPx())
                        },
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.inverseInk.copy(alpha = .08f)))
        val pv = com.ohav.noted.ui.lock.LocalPrivacy.current.of(n.id)
        val privacyAct = when {
            pv and com.ohav.noted.data.Privacy.LOCK != 0 -> Act("lock", Ph.Lock, Ph.LockFill, "פרטיות", on = true)
            pv != 0 -> Act("lock", Ph.EyeSlash, Ph.EyeSlash, "פרטיות", on = true)
            else -> Act("lock", Ph.EyeSlash, Ph.EyeSlash, "פרטיות")
        }
        val shadeHeld by com.ohav.noted.reminders.Shade.held.collectAsState()
        val acts = listOf(
            Act("move", Ph.FolderSimple, Ph.FolderSimple, "קטגוריה"),
            Act("remind", Ph.Bell, Ph.Bell, "תזכורת"),
            Act("share", Ph.ShareNetwork, Ph.ShareNetwork, "שיתוף"),
            Act("copy", Ph.Copy, Ph.Copy, "העתקה"),
            Act("pin", Ph.PushPin, Ph.PushPinFill, if (n.pinned) "שחרור" else "נעיצה", n.pinned),
            Act("fav", Ph.Star, Ph.StarFill, "מועדף", n.favorite),
            Act("shade", Ph.Notification, Ph.NotificationFill, "בהתראות", com.ohav.noted.reminders.Shade.note(n.id) in shadeHeld),
            privacyAct,
            Act("archive", Ph.Archive, Ph.Archive, "ארכיון"),
            Act("delete", Ph.Trash, Ph.Trash, "מחיקה", danger = true),
        )
        acts.chunked((acts.size + 1) / 2).forEachIndexed { row, chunk ->
            Row(Modifier.fillMaxWidth().padding(top = if (row == 0) 14.dp else 4.dp)) {
                chunk.forEach { a -> MenuAct(a, Modifier.weight(1f)) { onAction(a.key) } }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.Center) {
            Row(
                Modifier.clip(Shapes.sm).press { onAction("select") }.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Ph.CheckCircle, null, Modifier.size(18.dp), tint = c.inverseInk2)
                Text("בחירת כמה פתקים", style = t.label.copy(fontSize = 14.sp), color = c.inverseInk2)
            }
        }
    }
}

private class Act(val key: String, val icon: ImageVector, val iconOn: ImageVector, val label: String, val on: Boolean = false, val danger: Boolean = false)

@Composable
private fun MenuAct(a: Act, modifier: Modifier, onClick: () -> Unit) {
    val c = Noted.colors
    Column(
        modifier.press(scale = .9f, onClick = onClick).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(50.dp).clip(Squircle(17.dp)).background(if (a.on) c.accent else c.inverse2),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (a.on) a.iconOn else a.icon, null, Modifier.size(20.dp),
                tint = when { a.on -> c.accentInk; a.danger -> MenuDanger; else -> c.inverseInk },
            )
        }
        Text(a.label, style = Noted.type.caption.copy(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold), color = c.inverseInk2, maxLines = 1)
    }
}

// ------------------------------------------------------------------ selection chrome

/** Header that slides over the top bar while selecting. */
@Composable
fun SelectionHeader(count: Int, visible: Float, onClose: () -> Unit, onAll: () -> Unit, modifier: Modifier = Modifier) {
    val c = Noted.colors
    Row(
        modifier
            .fillMaxWidth()
            .graphicsLayer { translationY = -(1f - visible) * size.height * 1.1f }
            .background(c.bg.copy(alpha = .94f))
            .statusBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 14.dp)
            .height(64.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        com.ohav.noted.design.IconButton(Ph.X, "סיום בחירה", onClick = onClose)
        Text(
            when (count) { 0 -> "בחירת פתקים"; 1 -> "פתק אחד נבחר"; else -> "$count פתקים נבחרו" },
            style = Noted.type.barTitle, color = c.ink, modifier = Modifier.weight(1f),
        )
        com.ohav.noted.design.IconButton(Ph.Checks, "בחר הכל", onClick = onAll)
    }
}

/** Replaces the nav while selecting: colour, move, pin, delete. */
@Composable
fun SelectionBar(visible: Float, modifier: Modifier = Modifier, onAction: (String) -> Unit) {
    val c = Noted.colors
    Row(
        modifier
            .navigationBarsPadding()
            .padding(start = 22.dp, end = 22.dp, bottom = 18.dp)
            .fillMaxWidth()
            .height(74.dp)
            .graphicsLayer { translationY = (1f - visible) * (size.height * 1.4f + 40.dp.toPx()) }
            .softShadow(Shadows.float(c.isDark), 30.dp, c.inverse)
            .clip(Squircle(30.dp))
            .background(c.inverse),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(
            Triple("color", Ph.Palette, "צבע"), Triple("move", Ph.FolderSimple, "העבר"),
            Triple("pin", Ph.PushPin, "נעץ"), Triple("delete", Ph.Trash, "מחק"),
        ).forEach { (key, icon, label) ->
            val ink = if (key == "delete") MenuDanger else c.inverseInk
            Column(
                Modifier.press(scale = .9f) { onAction(key) }.padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Icon(icon, label, Modifier.size(22.dp), tint = ink)
                Text(label, style = Noted.type.tiny, color = ink)
            }
        }
    }
}

