package com.ohav.noted.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.ohav.noted.data.NoteEntity
import com.ohav.noted.design.Motion
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

/** Per-card motion: layout slot, height, depth behind the open card, staggered entrance. */
@Stable
class CardAnim(top: Float, height: Float, enter: Float) {
    val top = Animatable(top)
    val height = Animatable(height)
    val depth = Animatable(0f)
    val enter = Animatable(enter)
}

@Stable
class StackState {
    val holders = HashMap<String, CardAnim>()
    val fullHeights = mutableStateMapOf<String, Int>()

    /** 0→1 while the old category's cards leave sideways. */
    val exit = Animatable(0f)
    var exitDir by mutableFloatStateOf(1f)

    /** Rubber-band offset (px) while the stack is dragged sideways to change category. */
    val swipe = Animatable(0f)

    /** New holders start hidden while armed; bumping [enterGen] staggers everyone in. */
    var enterArmed = false
    var enterGen by mutableIntStateOf(0)

    /** Offset (px) of the stack inside the scroll content. */
    var stackTop by mutableIntStateOf(0)

    /** Target slots of the last composition, px, aligned with the visible list. */
    var tops: List<Float> = emptyList()
        internal set
    var heights: List<Float> = emptyList()
        internal set

    suspend fun hideAll() = holders.values.forEach { it.enter.snapTo(0f) }
}

/**
 * The card pile. Cards are absolutely placed on [StackDims.Step] intervals so each covers the
 * lower part of the one before. Placement reads the scroll position, so cards scrolling past the
 * top collect into a pile that keeps [StackDims.PEEK_N] title strips — only re-placement runs per
 * frame, never recomposition. The open card is taken out of the pile and scrolls naturally;
 * the cards above it fold into the strips.
 */
@Composable
fun NoteStack(
    notes: List<NoteEntity>,
    expanded: String?,
    state: StackState,
    scroll: ScrollState,
    modifier: Modifier = Modifier,
    card: @Composable (NoteEntity, Int) -> Unit,
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
    val heights = notes.map { n -> if (n.id == expanded) max(hc, (state.fullHeights[n.id] ?: 0).toFloat()) else hc }
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
    val holders = notes.mapIndexed { i, n ->
        state.holders.getOrPut(n.id) { CardAnim(tops[i], heights[i], if (state.enterArmed) 0f else 1f) }
    }
    SideEffect {
        state.tops = tops
        state.heights = heights
        val ids = notes.mapTo(HashSet()) { it.id }
        state.holders.keys.retainAll(ids)
        state.fullHeights.keys.filter { it !in ids }.forEach { state.fullHeights.remove(it) }
    }

    // existing cards glide to their new slots; new ones were created in place
    LaunchedEffect(tops, heights, ei) {
        holders.forEachIndexed { i, h ->
            launch { h.top.animateTo(tops[i], Motion.glide()) }
            launch { h.height.animateTo(heights[i], Motion.glide()) }
            launch { h.depth.animateTo(if (ei >= 0 && i < ei) (ei - i).toFloat() else 0f, Motion.glide()) }
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
    val nonExp = notes.indices.filter { it != ei }
    val pileIndex = IntArray(notes.size) { -1 }.also { a -> nonExp.forEachIndexed { j, i -> a[i] = j } }

    Layout(
        content = { notes.forEachIndexed { i, n -> key(n.id) { card(n, i) } } },
        modifier = modifier,
    ) { measurables, constraints ->
        val w = constraints.maxWidth
        val placeables = measurables.mapIndexed { i, m ->
            val h = holders.getOrNull(i)?.height?.value ?: hc
            m.measure(Constraints.fixed(w, h.roundToInt().coerceAtLeast(1)))
        }
        layout(w, (total + tail).roundToInt()) {
            val s = scroll.value - state.stackTop
            val base = s + pad + pn * peek
            val cur = FloatArray(holders.size) { holders[it].top.value }

            // how many (fractional) cards have crossed the pile line — keeps every position continuous
            var t = -1f
            for (j in nonExp.indices) {
                val ai = nonExp[j]
                val top = cur[ai]
                if (top > base) break
                val next = if (j + 1 < nonExp.size) cur[nonExp[j + 1]] else Float.POSITIVE_INFINITY
                if (next > base) {
                    val g = min(step, (if (ai + 1 < cur.size) cur[ai + 1] else Float.POSITIVE_INFINITY) - top)
                    t = j + if (g <= 0f) 1f else min(1f, (base - top) / g)
                    break
                }
                t = (j + 1).toFloat()
            }

            val swipe = state.swipe.value
            val exit = state.exit.value
            val dir = state.exitDir
            placeables.forEachIndexed { i, p ->
                val h = holders.getOrNull(i) ?: return@forEachIndexed
                var d = cur[i]
                val depth = h.depth.value
                var sc = if (depth > 0f) max(.88f, 1f - depth * .03f) else 1f
                var op = (1f - (depth - pn)).coerceIn(0f, 1f)
                val j = pileIndex[i]
                val k = if (j < 0) 0f else t - j
                if (k > 0f) {
                    d = base - min(k, pn.toFloat()) * peek
                    sc = min(sc, 1f - min(k, pn + 1f) * .03f)
                    if (k > pn) op = max(0f, 1f - (k - pn))
                }
                val e = h.enter.value
                p.placeWithLayer(0, d.roundToInt(), zIndex = i.toFloat()) {
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
