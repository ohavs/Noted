package com.ohav.noted.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.ohav.noted.data.NoteEntity
import com.ohav.noted.design.Drift
import com.ohav.noted.design.Motion
import com.ohav.noted.design.Paced
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Geometry of the stack — the prototype's STEP / HC / GAP / PEEK / PEEK_N. */
object StackDims {
    val Step = 118.dp      // distance between collapsed cards (what stays visible of each)
    val Card = 196.dp      // collapsed card height; the next card covers the rest
    val Gap = 14.dp        // after the open card
    val Peek = 56.dp       // title strip a piled card keeps visible
    const val PEEK_N = 3   // how many strips the pile shows
    val PilePad = 18.dp    // pile line distance from the top of the viewport
    val Tail = 150.dp      // room under the last card (nav + FAB)
}

/**
 * Per-card motion. The layout itself never animates: every change (a card unfolding, the scroll
 * jumping to it, a card leaving the stack) lands at once, and these screen-space offsets take up
 * the jump and glide it out (FLIP) — so each card travels in a straight line from exactly where it
 * was shown, whatever the pile did in between. The drifts are plain fields stepped by the stack's
 * frame loop; [StackState.tick] invalidates the layout that reads them.
 */
@Stable
class CardAnim(enter: Float) {
    val y = Drift()
    val h = Drift()
    val scale = Drift(.0005f)
    val alpha = Drift(.002f)
    val enter = Animatable(enter)
    val moving: Boolean get() = y.active || h.active || scale.active || alpha.active
    fun step(dt: Float) { y.step(dt); h.step(dt); scale.step(dt); alpha.step(dt) }
}

/** Where every card belongs: content tops and heights, and which one is open. */
@Immutable
data class StackTargets(val ids: List<String>, val open: Int, val tops: List<Float>, val heights: List<Float>)

internal class StackGeo(val step: Float, val peek: Float, val pad: Float, val pn: Int)

/**
 * Screen pose (y, scale, alpha per card) of [t] at scroll [s]. Cards that scrolled past the pile
 * line fold into [StackGeo.pn] title strips; `t` counts (fractionally) how many crossed, so every
 * position is continuous in the scroll. The line eases down from the viewport top over the first
 * stretch of scroll — at the top of the list nothing is piled and the first card sits where it is.
 */
internal fun StackTargets.pose(g: StackGeo, s: Float): FloatArray {
    val n = ids.size
    val out = FloatArray(n * 3)
    if (n == 0) return out
    val r = g.pad + g.pn * g.peek
    val v = s.coerceIn(0f, 2f * r)
    val base = s + v - v * v / (4f * r)
    val non = IntArray(if (open in 0 until n) n - 1 else n)
    val pileIndex = IntArray(n) { -1 }
    var c = 0
    for (i in 0 until n) if (i != open) { pileIndex[i] = c; non[c++] = i }
    var t = -1f
    for (j in non.indices) {
        val ai = non[j]
        val top = tops[ai]
        if (top > base) break
        val next = if (j + 1 < non.size) tops[non[j + 1]] else Float.POSITIVE_INFINITY
        if (next > base) {
            val gp = min(g.step, (if (ai + 1 < n) tops[ai + 1] else Float.POSITIVE_INFINITY) - top)
            t = j + if (gp <= 0f) 1f else min(1f, (base - top) / gp)
            break
        }
        t = (j + 1).toFloat()
    }
    val pn = g.pn.toFloat()
    for (i in 0 until n) {
        var d = tops[i]
        val depth = if (open >= 0 && i < open) (open - i).toFloat() else 0f
        var sc = if (depth > 0f) max(.88f, 1f - depth * .03f) else 1f
        var op = (1f - (depth - pn)).coerceIn(0f, 1f)
        val j = pileIndex[i]
        val k = if (j < 0) 0f else t - j
        if (k > 0f) {
            d = base - min(k, pn) * g.peek
            sc = min(sc, 1f - min(k, pn + 1f) * .03f)
            if (k > pn) op = max(0f, 1f - (k - pn))
        }
        out[i * 3] = d - s
        out[i * 3 + 1] = sc
        out[i * 3 + 2] = op
    }
    return out
}

@Stable
class StackState {
    val holders = HashMap<String, CardAnim>()
    val fullHeights = mutableStateMapOf<String, Int>()

    /** Bumped every frame while a card drifts — the layout reads it. */
    internal var tick by mutableIntStateOf(0)
    internal val wake = Channel<Unit>(Channel.CONFLATED)
    internal var wokeAt = 0L
    /** Programmatic scroll jumps since the last layout — taken up by the drifts, not shown as a jump. */
    internal var jumped = 0f
    internal var shownTargets: StackTargets? = null

    /** 0→1 while the old category's cards leave sideways. */
    val exit = Animatable(0f)
    var exitDir by mutableFloatStateOf(1f)

    /** Rubber-band offset (px) while the stack is dragged sideways to change category. */
    val swipe = Animatable(0f)

    /** New holders start hidden while armed; bumping [enterGen] staggers everyone in. */
    var enterArmed = false
    var enterGen by mutableIntStateOf(0)

    /**
     * The stack scrolls inside its own viewport (the page above it stays put). [value] is our own
     * state rather than a ScrollState so it can be shifted mid-drag — closing the open card keeps
     * it where it is — without fighting the gesture's mutex.
     */
    var value by mutableFloatStateOf(0f)
    var viewport by mutableIntStateOf(0)
    /** Observed: when the content shrinks under the scroll (a category with one note), it comes back. */
    var content by mutableFloatStateOf(0f)
        internal set

    /** Called with every user-driven delta (drag + its fling), never for programmatic scrolls. */
    var onUserScroll: ((Float) -> Unit)? = null
    private var auto = false

    val maxValue: Float get() = max(0f, content - viewport)

    val scrollable = ScrollableState { d ->
        if (!auto) onUserScroll?.invoke(d)
        val mx = maxValue
        // never pull further out of range, but don't yank back either (that happens on release)
        val nv = when {
            d > 0f && value + d > mx -> max(value, mx)
            d < 0f && value + d < 0f -> min(value, 0f)
            else -> value + d
        }
        val used = nv - value
        value = nv
        used
    }

    private val glide = Paced(0f)

    /**
     * Programmatic scroll on the glide spring, paced like the cards so the two stay in step. A new
     * target while one is still running picks up the current velocity instead of restarting from
     * rest, so retargets (an open card that measures taller a frame later, a second tap) stay one
     * continuous motion.
     */
    suspend fun animateTo(target: Float) {
        try {
            scrollable.scroll {
                // set inside: the run this one interrupts clears the flag on its way out
                auto = true
                glide.follow(value)
                glide.glideTo(target.coerceIn(0f, maxValue)) { scrollBy(it - this@StackState.value) }
            }
        } finally {
            auto = false
        }
    }

    suspend fun scrollBy(d: Float): Float {
        auto = true
        try { return scrollable.scrollBy(d) } finally { auto = false }
    }

    /**
     * Moves the scroll to [target] in one go while every card stays where it is on screen and then
     * glides to its place from there — for layout changes (a card opening or closing), where a
     * scroll animation would run the stack through the pile on the way.
     */
    suspend fun jumpTo(target: Float) {
        try {
            scrollable.scroll(MutatePriority.PreventUserInput) {
                auto = true
                shift(target.coerceIn(0f, maxValue) - this@StackState.value)
            }
        } finally {
            auto = false
        }
    }

    /** Shifts the scroll by [d] (even mid-drag) while the cards keep their place on screen. */
    fun shift(d: Float) {
        if (d == 0f) return
        value += d
        jumped += d
    }

    /** Target slots of the last composition, px, aligned with the visible list. */
    var tops: List<Float> = emptyList()
        internal set
    var heights: List<Float> = emptyList()
        internal set

    suspend fun hideAll() = holders.values.forEach { it.enter.snapTo(0f) }
}

/**
 * The card pile. Cards are absolutely placed on [StackDims.Step] intervals so each covers the
 * lower part of the one before. Layout reads the stack's own scroll, so cards scrolling past
 * the top collect into a pile that keeps [StackDims.PEEK_N] title strips — only layout runs per
 * frame, never recomposition (a child whose size didn't change isn't re-measured). The open card is taken out of the pile; the cards above it fold
 * into the strips. An open card never grows past the viewport: [card] gets `clipped` and the
 * rest lives in the editor.
 */
@Composable
fun NoteStack(
    notes: List<NoteEntity>,
    expanded: String?,
    state: StackState,
    modifier: Modifier = Modifier,
    room: androidx.compose.ui.unit.Dp = 120.dp,
    card: @Composable (NoteEntity, Int, Boolean) -> Unit,
) {
    val density = LocalDensity.current
    val step: Float
    val hc: Float
    val gap: Float
    val peek: Float
    val pad: Float
    val tail: Float
    val enterDy: Float
    val exitDx: Float
    with(density) {
        step = StackDims.Step.toPx(); hc = StackDims.Card.toPx(); gap = StackDims.Gap.toPx()
        peek = StackDims.Peek.toPx(); pad = StackDims.PilePad.toPx(); tail = StackDims.Tail.toPx()
        enterDy = 46.dp.toPx(); exitDx = 40.dp.toPx()
    }
    val pn = StackDims.PEEK_N

    val ei = notes.indexOfFirst { it.id == expanded }
    val navRoom = with(density) { room.toPx() }
    val cap = if (state.viewport > 0 && ei >= 0) max(hc, state.viewport - pad - min(pn, ei) * peek - navRoom) else Float.POSITIVE_INFINITY
    val full = if (ei >= 0) max(hc, (state.fullHeights[notes[ei].id] ?: 0).toFloat()) else 0f
    val clipped = full > cap + 1f
    val heights = notes.mapIndexed { i, _ -> if (i == ei) min(full, cap) else hc }
    val tops = ArrayList<Float>(notes.size)
    var y = 0f
    notes.indices.forEach { i ->
        tops += y
        y += when {
            ei >= 0 && i < ei -> if (ei - i <= pn) peek else 0f
            i == ei -> heights[i] + gap
            else -> step
        }
    }
    val targets = remember(notes, ei, tops, heights) { StackTargets(notes.map { it.id }, ei, tops, heights) }
    val geo = remember(step, peek, pad) { StackGeo(step, peek, pad, pn) }
    val holders = notes.map { n -> state.holders.getOrPut(n.id) { CardAnim(if (state.enterArmed) 0f else 1f) } }
    SideEffect {
        state.tops = tops
        state.heights = heights
        state.content = if (notes.isEmpty()) 0f else tops.last() + heights.last() + tail
        val ids = notes.mapTo(HashSet()) { it.id }
        state.holders.keys.retainAll(ids)
        state.fullHeights.keys.filter { it !in ids }.forEach { state.fullHeights.remove(it) }
    }

    // the drifts' frame loop: frame-paced like [Paced] (a heavy first frame doesn't eat the move)
    LaunchedEffect(state) {
        for (signal in state.wake) {
            // the jump was taken up during the last layout: the first frame already moves
            var last = state.wokeAt
            while (true) {
                val now = withFrameNanos { it }
                if (last in 1..now) {
                    val dt = (now - last) / 1e9f
                    state.holders.values.forEach { it.step(dt) }
                }
                last = now
                state.tick++
                if (state.holders.values.none { it.moving }) break
            }
        }
    }
    LaunchedEffect(state.enterGen) {
        if (state.enterGen == 0) return@LaunchedEffect
        state.enterArmed = false
        holders.forEachIndexed { i, h ->
            launch {
                delay(min(i, 10) * 45L)
                h.enter.animateTo(1f, Motion.glide())
            }
        }
    }

    val total = if (notes.isEmpty()) 0f else tops.last() + heights.last()

    Layout(
        content = { notes.forEachIndexed { i, n -> key(n.id) { card(n, i, i == ei && clipped) } } },
        modifier = modifier,
    ) { measurables, constraints ->
        state.tick
        val s = state.value
        val pose = targets.pose(geo, s)
        // the targets (or the scroll, programmatically) changed since the last frame: whatever moved
        // is taken up by the drifts, measured from the old pose at the scroll it was shown at
        val was = state.shownTargets
        if (was != null && (state.jumped != 0f || (was !== targets && was != targets))) {
            val old = was.pose(geo, s - state.jumped)
            val at = HashMap<String, Int>(was.ids.size * 2)
            was.ids.forEachIndexed { k, id -> at[id] = k }
            targets.ids.forEachIndexed { i, id ->
                val k = at[id] ?: return@forEachIndexed
                val h = holders.getOrNull(i) ?: return@forEachIndexed
                h.y.add(old[k * 3] - pose[i * 3])
                h.scale.add(old[k * 3 + 1] - pose[i * 3 + 1])
                h.alpha.add(old[k * 3 + 2] - pose[i * 3 + 2])
                h.h.add(was.heights[k] - heights[i])
            }
            state.wokeAt = System.nanoTime()
            state.wake.trySend(Unit)
        }
        state.shownTargets = targets
        state.jumped = 0f

        val w = constraints.maxWidth
        val placeables = measurables.mapIndexed { i, m ->
            val h = (heights.getOrNull(i) ?: hc) + (holders.getOrNull(i)?.h?.value ?: 0f)
            m.measure(Constraints.fixed(w, h.roundToInt().coerceAtLeast(1)))
        }
        val vh = if (constraints.hasBoundedHeight) constraints.maxHeight else (total + tail).roundToInt()
        layout(w, vh) {
            val swipe = state.swipe.value
            val exit = state.exit.value
            val dir = state.exitDir
            placeables.forEachIndexed { i, p ->
                val h = holders.getOrNull(i) ?: return@forEachIndexed
                val y = pose[i * 3] + h.y.value
                val sc = pose[i * 3 + 1] + h.scale.value
                val op = pose[i * 3 + 2] + h.alpha.value
                val e = h.enter.value
                p.placeWithLayer(0, y.roundToInt(), zIndex = i.toFloat()) {
                    transformOrigin = TransformOrigin(.5f, 0f)
                    val ss = sc * (.96f + .04f * e) * (1f - .04f * exit)
                    scaleX = ss
                    scaleY = ss
                    alpha = (op * e * (1f - exit)).coerceIn(0f, 1f)
                    translationY = (1f - e) * enterDy
                    translationX = swipe - dir * exitDx * exit
                }
            }
        }
    }
}
