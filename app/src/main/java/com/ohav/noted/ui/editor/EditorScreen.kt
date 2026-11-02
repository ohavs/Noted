package com.ohav.noted.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.ohav.noted.NotedApp
import com.ohav.noted.data.BlockType
import com.ohav.noted.data.Blocks
import com.ohav.noted.data.NoteEntity
import com.ohav.noted.design.LocalOverlays
import com.ohav.noted.design.Motion
import com.ohav.noted.design.NoteColor
import com.ohav.noted.design.Noted
import com.ohav.noted.design.Ph
import com.ohav.noted.design.SheetRow
import com.ohav.noted.design.Shadows
import com.ohav.noted.design.Squircle
import com.ohav.noted.design.ToastState
import com.ohav.noted.design.press
import com.ohav.noted.design.softShadow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Which note the editor shows and where it grew from. The editor is a container transform:
 * it opens out of the card's bounds and folds back into wherever that card is now.
 */
@Stable
class EditorNav {
    var note by mutableStateOf<NoteEntity?>(null)
        private set
    var from by mutableStateOf(Rect.Zero)
    var fresh = false
        private set
    var closing by mutableStateOf(false)
    val p = Animatable(0f)

    /** Where the note's card is on screen right now (home provides it), for the way back. */
    var cardRect: ((String) -> Rect?)? = null

    val openId: String? get() = note?.id

    fun open(n: NoteEntity, rect: Rect?, fresh: Boolean = false) {
        if (note != null) return
        note = n
        from = rect ?: Rect.Zero
        this.fresh = fresh
        closing = false
    }

    internal fun finish() {
        note = null
        closing = false
    }
}

@Composable
fun EditorHost(app: NotedApp, nav: EditorNav, toasts: ToastState) {
    val n = nav.note ?: return
    val c = Noted.colors
    val t = Noted.type
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val overlays = LocalOverlays.current
    val state = remember(n.id) { EditorState(n.id, n.title, Blocks.parse(n.blocks)) }
    val live by app.notes.notes.collectAsState(initial = null)
    val color = NoteColor.of(live?.firstOrNull { it.id == n.id }?.color ?: n.color)
    val bg by animateColorAsState(c.note(color), Motion.glide(), label = "edBg")

    LaunchedEffect(n.id) {
        nav.p.snapTo(0f)
        nav.p.animateTo(1f, Motion.glide())
    }
    LaunchedEffect(n.id) {
        delay(260)
        if (nav.fresh || state.isEmpty) runCatching { state.titleFocus.requestFocus() }
    }
    // autosave on every pause in typing
    LaunchedEffect(state) {
        snapshotFlow { state.version }.drop(1).collectLatest {
            delay(400)
            val (title, blocks) = state.content()
            app.notes.save(n.id, title, blocks)
        }
    }
    DisposableEffect(state) {
        onDispose {
            if (state.version > 0) {
                val (title, blocks) = state.content()
                app.scope.launch { app.notes.save(n.id, title, blocks) }
            }
        }
    }

    fun close() {
        if (nav.closing) return
        nav.closing = true
        keyboard?.hide()
        focus.clearFocus(force = true)
        val changed = state.changedSinceOpen
        if (state.version > 0) {
            val (title, blocks) = state.content()
            app.scope.launch { app.notes.save(n.id, title, blocks) }
        }
        nav.cardRect?.invoke(n.id)?.let { nav.from = it }
        scope.launch {
            nav.p.animateTo(0f, Motion.glide(0.002f))
            nav.finish()
            if (changed) toasts.show("נשמר", Ph.CheckCircleFill, millis = 1400)
        }
    }

    fun menu() {
        keyboard?.hide()
        overlays.sheet.show("הפתק") {
            Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NoteColor.entries.forEach { col ->
                    val on = col == color
                    Box(
                        Modifier
                            .weight(1f)
                            .height(44.dp)
                            .press(scale = .88f) {
                                scope.launch { app.notes.setColor(listOf(n.id), col) }
                            }
                            .clip(Squircle(14.dp))
                            .background(c.note(col))
                            .drawBehind {
                                if (on) {
                                    val o = Squircle(14.dp).createOutline(size, layoutDirection, this)
                                    drawOutline(o, c.ink, style = androidx.compose.ui.graphics.drawscope.Stroke(2.5.dp.toPx()))
                                }
                            },
                    )
                }
            }
            SheetRow(Ph.ArrowCounterClockwise, "ביטול כל השינויים מהפתיחה") {
                overlays.sheet.close()
                if (!state.changedSinceOpen) { toasts.show("אין שינויים לבטל", Ph.Info); return@SheetRow }
                scope.launch {
                    val ok = overlays.dialog.confirm(
                        title = "לחזור לגרסה מהפתיחה?",
                        text = "כל מה שנכתב מאז שנפתח הפתק יימחק. אפשר לבטל עם ״ברירה״.",
                        icon = Ph.ArrowCounterClockwise, danger = true,
                        confirm = "חזרה לגרסה", cancel = "השאר",
                    )
                    if (ok) state.revertAll()
                }
            }
            SheetRow(Ph.Trash, "העברה לסל", danger = true, last = true) {
                overlays.sheet.close()
                app.scope.launch { app.notes.trash(listOf(n.id)) }
                nav.cardRect = null
                close()
                toasts.show("הפתק הועבר לסל המחזור", Ph.Trash, action = "ביטול", millis = 5000) {
                    app.scope.launch { app.notes.restore(listOf(n.id)) }
                }
            }
        }
    }

    BackHandler { close() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val fullW = constraints.maxWidth.toFloat()
        val fullH = constraints.maxHeight.toFloat()
        val p = nav.p.value
        val r = nav.from.takeIf { it.width > 1f } ?: with(density) {
            // no card to grow from: rise from the + button's corner
            Rect(Offset(30.dp.toPx(), fullH - 190.dp.toPx()), androidx.compose.ui.geometry.Size(66.dp.toPx(), 66.dp.toPx()))
        }
        fun lerp(a: Float, b: Float) = a + (b - a) * p
        val x = lerp(r.left, 0f)
        val y = lerp(r.top, 0f)
        val w = lerp(r.width, fullW)
        val h = lerp(r.height, fullH)
        val radius = 30.dp * (1f - p).coerceIn(0f, 1f)
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = p.coerceIn(0f, 1f) * .5f }
                .background(c.scrim),
        )
        with(density) {
            Box(
                Modifier
                    .absoluteOffset { IntOffset(x.roundToInt(), y.roundToInt()) }
                    .size(w.toDp(), h.toDp())
                    .softShadow(Shadows.card(c.isDark), radius, bg)
                    .clip(Squircle(radius))
                    .background(bg)
                    // the page owns every touch inside it, gaps included
                    .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
            ) {
                // the card's own title fades out as the page grows from it
                Text(
                    n.title.ifBlank { "ללא כותרת" }, style = t.cardTitle, color = c.noteInk, maxLines = 1,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 22.dp).graphicsLayer { alpha = (1f - p * 3f).coerceIn(0f, 1f) },
                )
                Box(
                    Modifier
                        .wrapContentSize(Alignment.TopStart, unbounded = true)
                        .requiredSize(fullW.toDp(), fullH.toDp())
                        .graphicsLayer { alpha = ((p - .25f) / .75f).coerceIn(0f, 1f) },
                ) {
                    EditorPage(state, onBack = ::close, onMenu = ::menu, toasts = toasts)
                }
            }
        }
    }
}

/** Replaces the system selection menu with floating circles over the selection. */
@Stable
private class Bubble : TextToolbar {
    var rect by mutableStateOf<Rect?>(null)
    var onCopy: (() -> Unit)? = null
    var onPaste: (() -> Unit)? = null
    var onCut: (() -> Unit)? = null
    var onSelectAll: (() -> Unit)? = null
    override var status: TextToolbarStatus by mutableStateOf(TextToolbarStatus.Hidden)
        private set

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        this.rect = rect
        onCopy = onCopyRequested
        onPaste = onPasteRequested
        onCut = onCutRequested
        onSelectAll = onSelectAllRequested
        status = TextToolbarStatus.Shown
    }

    override fun hide() {
        status = TextToolbarStatus.Hidden
    }
}

@Composable
private fun EditorPage(state: EditorState, onBack: () -> Unit, onMenu: () -> Unit, toasts: ToastState) {
    val c = Noted.colors
    val t = Noted.type
    val haptics = Noted.haptics
    val bubble = remember { Bubble() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val scroll = rememberScrollState()
    val overlays = LocalOverlays.current

    fun blockMenu(id: String) {
        haptics.light()
        overlays.sheet.show("בלוק") {
            SheetRow(Ph.ArrowUp, "הזזה למעלה") { state.moveBy(id, -1); overlays.sheet.close() }
            SheetRow(Ph.ArrowDown, "הזזה למטה") { state.moveBy(id, 1); overlays.sheet.close() }
            SheetRow(Ph.CopySimple, "שכפול") { state.duplicate(id); overlays.sheet.close() }
            SheetRow(Ph.Trash, "מחיקה", danger = true, last = true) {
                state.delete(id)
                overlays.sheet.close()
                toasts.show("הבלוק נמחק", Ph.Trash, action = "ביטול", millis = 4000) { state.undo() }
            }
        }
    }

    CompositionLocalProvider(LocalTextToolbar provides bubble) {
        Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
            Column(Modifier.fillMaxSize().imePadding()) {
                // top bar: back · undo · redo · more
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp).height(60.dp).padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BarIcon(Ph.CaretStart, "חזרה", c.noteInk, onClick = onBack)
                    Spacer(Modifier.weight(1f))
                    // RTL: "back in time" points right
                    BarIcon(Ph.ArrowUUpRight, "ברירה", c.noteInk, enabled = state.canUndo) { state.undo(); haptics.tick() }
                    BarIcon(Ph.ArrowUUpLeft, "חזרה על פעולה", c.noteInk, enabled = state.canRedo) { state.redo(); haptics.tick() }
                    BarIcon(Ph.DotsThreeVertical, "עוד", c.noteInk, onClick = onMenu)
                }
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(scroll)
                        .padding(start = 22.dp, end = 22.dp, top = 6.dp),
                ) {
                    TitleField(state)
                    Spacer(Modifier.height(10.dp))
                    BlockList(state, onBlockMenu = ::blockMenu, toasts = toasts)
                    // tap under the last block → keep writing at the end
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 220.dp)
                            .clickable(remember { MutableInteractionSource() }, indication = null) {
                                val last = state.blocks.lastOrNull()
                                if (last != null && last.type in TEXT_TYPES && last.text.isEmpty()) state.pendingFocus = last.id to 0
                                else {
                                    val b = EdBlock.new()
                                    state.blocks.add(b)
                                    state.pendingFocus = b.id to 0
                                }
                            },
                    )
                }
                SmartBar(state, toasts)
            }
            SelectionBubble(bubble, origin, state)
        }
    }
}

@Composable
private fun BarIcon(icon: ImageVector, desc: String, tint: Color, enabled: Boolean = true, onClick: () -> Unit) {
    val a by animateFloatAsState(if (enabled) 1f else .3f, Motion.glide(), label = "barIcon")
    Box(
        Modifier.size(46.dp).graphicsLayer { alpha = a }.clip(CircleShape).press(enabled = enabled, scale = .86f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, Modifier.size(24.dp), tint = tint) }
}

@Composable
private fun TitleField(state: EditorState) {
    val c = Noted.colors
    val t = Noted.type
    Box {
        if (state.title.text.isEmpty()) Text("כותרת", style = t.title, color = c.noteInk2.copy(alpha = .45f))
        BasicTextField(
            value = state.title,
            onValueChange = state::onTitle,
            textStyle = t.title.copy(color = c.noteInk, textDirection = TextDirection.Content),
            cursorBrush = SolidColor(c.noteInk),
            keyboardOptions = KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(state.titleFocus)
                .onFocusChanged { if (it.isFocused) state.focused = null },
        )
    }
}

/** Blocks with drag-to-reorder from their handle; neighbours glide out of the way. */
@Composable
private fun BlockList(state: EditorState, onBlockMenu: (String) -> Unit, toasts: ToastState) {
    val haptics = Noted.haptics
    val scope = rememberCoroutineScope()
    val heights = remember { mutableStateMapOf<String, Int>() }
    val shifts = remember { HashMap<String, Animatable<Float, AnimationVector1D>>() }
    var dragId by remember { mutableStateOf<String?>(null) }
    var dragDy by remember { mutableStateOf(0f) }

    fun shiftOf(id: String) = shifts.getOrPut(id) { Animatable(0f) }

    Column(Modifier.fillMaxWidth()) {
        // numbering restarts after anything that isn't a numbered row, and per indent level
        val counters = IntArray(4)
        state.blocks.forEachIndexed { i, b ->
            val num = if (b.type == BlockType.Num) {
                val lvl = b.indent.coerceIn(0, 3)
                for (k in lvl + 1..3) counters[k] = 0
                counters[lvl] += 1
                counters[lvl]
            } else { counters.fill(0); 0 }
            val dragging = dragId == b.id
            androidx.compose.runtime.key(b.id) {
                val sh = shiftOf(b.id)
                Box(
                    Modifier
                        .zIndex(if (dragging) 1f else 0f)
                        .onSizeChanged { heights[b.id] = it.height }
                        .graphicsLayer {
                            translationY = if (dragging) dragDy else sh.value
                            val k = if (dragging) 1.02f else 1f
                            scaleX = k; scaleY = k
                            shadowElevation = if (dragging) 10.dp.toPx() else 0f
                            shape = RoundedCornerShape(14.dp)
                        },
                ) {
                    BlockRow(
                        state, b, num,
                        onHandleTap = { onBlockMenu(b.id) },
                        onDragStart = { dragId = b.id; dragDy = 0f; haptics.medium() },
                        onDrag = { dy ->
                            dragDy += dy
                            val idx = state.indexOf(b.id)
                            val next = state.blocks.getOrNull(idx + 1)
                            val prev = state.blocks.getOrNull(idx - 1)
                            val nh = next?.let { heights[it.id] }?.toFloat()
                            val ph = prev?.let { heights[it.id] }?.toFloat()
                            if (next != null && nh != null && dragDy > nh / 2) {
                                state.move(idx, idx + 1)
                                dragDy -= nh
                                val a = shiftOf(next.id)
                                scope.launch { a.snapTo(nh); a.animateTo(0f, Motion.glide()) }
                                haptics.tick()
                            } else if (prev != null && ph != null && dragDy < -ph / 2) {
                                state.move(idx, idx - 1)
                                dragDy += ph
                                val a = shiftOf(prev.id)
                                scope.launch { a.snapTo(-ph); a.animateTo(0f, Motion.glide()) }
                                haptics.tick()
                            }
                        },
                        onDragEnd = {
                            val a = shiftOf(b.id)
                            val from = dragDy
                            dragId = null
                            dragDy = 0f
                            scope.launch { a.snapTo(from); a.animateTo(0f, Motion.glide()) }
                            haptics.light()
                        },
                        toasts = toasts,
                    )
                }
            }
        }
    }
}

private fun styleFor(type: BlockType, base: TextStyle, display: TextStyle, h2: TextStyle): TextStyle = when (type) {
    BlockType.H1 -> display
    BlockType.H2 -> h2
    BlockType.H3 -> base.copy(fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, lineHeight = 25.sp)
    BlockType.Quote -> base.copy(fontStyle = FontStyle.Italic)
    else -> base
}

@Composable
private fun BlockRow(
    state: EditorState,
    b: EdBlock,
    num: Int,
    onHandleTap: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    toasts: ToastState,
) {
    val c = Noted.colors
    val t = Noted.type
    val haptics = Noted.haptics
    val focused = state.focused == b.id
    val handle by animateFloatAsState(if (focused) 1f else 0f, Motion.glide(), label = "handle")

    LaunchedEffect(state.pendingFocus) {
        val pf = state.pendingFocus ?: return@LaunchedEffect
        if (pf.first != b.id) return@LaunchedEffect
        state.pendingFocus = null
        b.value = b.value.copy(selection = TextRange((pf.second + 1).coerceIn(1, b.value.text.length)))
        runCatching { b.focus.requestFocus() }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 34.dp)
            .padding(start = (b.indent * 22).dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (b.type == BlockType.Divider) {
            Box(
                Modifier
                    .weight(1f)
                    .height(30.dp)
                    .clickable(remember { MutableInteractionSource() }, indication = null) { onHandleTap() },
                contentAlignment = Alignment.Center,
            ) { Box(Modifier.fillMaxWidth().height(1.5.dp).background(c.noteInk.copy(alpha = .18f))) }
        } else {
            Lead(state, b, num, focused, toasts)
            val base = t.note.copy(fontSize = 17.sp, lineHeight = 26.sp, textDirection = TextDirection.Content)
            val display = t.noteTitle.copy(textDirection = TextDirection.Content)
            val h2 = t.cardTitle.copy(fontSize = t.cardTitle.fontSize * .82f, lineHeight = t.cardTitle.lineHeight * .82f, textDirection = TextDirection.Content)
            var style = styleFor(b.type, base, display, h2).copy(color = c.noteInk)
            if (b.type == BlockType.Check && b.done) style = style.copy(color = c.noteInk2, textDecoration = TextDecoration.LineThrough)
            val quote = b.type == BlockType.Quote
            Box(
                Modifier
                    .weight(1f)
                    .padding(top = if (b.type == BlockType.H1 || b.type == BlockType.H2) 10.dp else 4.dp, bottom = 4.dp)
                    .then(if (quote) Modifier.height(IntrinsicSize.Min) else Modifier),
            ) {
                if (b.text.isEmpty() && focused) {
                    Text(
                        when (b.type) {
                            BlockType.P -> if (state.blocks.size == 1) "כותבים כאן…" else "כתיבה, או + להוספה"
                            BlockType.H1, BlockType.H2, BlockType.H3 -> "כותרת"
                            BlockType.Check -> "משימה"
                            BlockType.Quote -> "ציטוט"
                            else -> "פריט"
                        },
                        style = style.copy(color = c.noteInk2.copy(alpha = .5f), textDecoration = null),
                        modifier = Modifier.padding(start = if (quote) 14.dp else 0.dp),
                    )
                } else if (b.text.isEmpty() && state.blocks.size == 1 && state.focused == null) {
                    Text("כותבים כאן…", style = style.copy(color = c.noteInk2.copy(alpha = .5f)))
                }
                if (quote) Box(Modifier.width(3.dp).fillMaxHeight().background(c.noteInk, RoundedCornerShape(2.dp)))
                BasicTextField(
                    value = b.value,
                    onValueChange = { state.onValue(b, it) },
                    textStyle = style,
                    cursorBrush = SolidColor(c.noteInk),
                    visualTransformation = RichTransformation(b.spans, c.isDark, 1),
                    keyboardOptions = KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = if (quote) 14.dp else 0.dp)
                        .focusRequester(b.focus)
                        .onFocusChanged {
                            if (it.isFocused) state.focused = b.id
                            else if (state.focused == b.id) state.focused = null
                        },
                )
            }
        }
        // drag handle: tap → block menu, drag → reorder
        Box(
            Modifier
                .padding(top = 4.dp)
                .size(30.dp, 30.dp)
                .graphicsLayer { alpha = handle * .6f + if (b.type == BlockType.Divider) .4f else 0f }
                .clip(RoundedCornerShape(9.dp))
                .clickable(remember { MutableInteractionSource() }, indication = null) { onHandleTap() }
                .pointerInput(b.id) {
                    detectDragGestures(
                        onDragStart = { onDragStart() },
                        onDragEnd = onDragEnd,
                        onDragCancel = onDragEnd,
                    ) { ch, d -> ch.consume(); onDrag(d.y) }
                },
            contentAlignment = Alignment.Center,
        ) { Icon(Ph.DotsSixVertical, "גרירה", Modifier.size(18.dp), tint = c.noteInk2) }
    }
}

/** What sits before the text: bullet, number, checkbox — or the `+` fan on an empty paragraph. */
@Composable
private fun Lead(state: EditorState, b: EdBlock, num: Int, focused: Boolean, toasts: ToastState) {
    val c = Noted.colors
    val t = Noted.type
    val haptics = Noted.haptics
    when (b.type) {
        BlockType.Bullet -> Box(Modifier.padding(top = 4.dp).size(26.dp, 26.dp), contentAlignment = Alignment.Center) {
            val ring = b.indent % 2 == 1
            Box(
                Modifier.size(7.dp).clip(CircleShape)
                    .background(if (ring) Color.Transparent else c.noteInk)
                    .drawBehind { if (ring) drawCircle(c.noteInk, size.minDimension / 2 - 0.75.dp.toPx(), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx())) },
            )
        }
        BlockType.Num -> Box(Modifier.padding(top = 4.dp).widthIn(min = 26.dp), contentAlignment = Alignment.TopStart) {
            Text("$num.", style = t.note.copy(fontSize = 17.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold), color = c.noteInk)
        }
        BlockType.Check -> {
            val p by animateFloatAsState(if (b.done) 1f else 0f, Motion.touch(), label = "edCheck")
            Box(
                Modifier
                    .padding(top = 5.dp, end = 10.dp)
                    .size(24.dp)
                    .press(scale = .82f) { state.toggleCheck(b); if (b.done) haptics.confirm() else haptics.light() }
                    .clip(Squircle(8.dp))
                    .background(c.noteInk.copy(alpha = p))
                    .drawBehind {
                        val o = Squircle(8.dp).createOutline(size, layoutDirection, this)
                        drawOutline(o, c.noteInk.copy(alpha = .55f + .45f * p), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (p > .05f) Icon(Ph.Check, null, Modifier.size(15.dp).graphicsLayer { scaleX = p; scaleY = p }, tint = if (c.isDark) Color.Black else Color.White)
            }
        }
        BlockType.P -> {
            val show = focused && b.text.isEmpty()
            val vis by animateFloatAsState(if (show) 1f else 0f, Motion.touch(), label = "plus")
            if (vis > .01f) PlusFan(state, vis, toasts)
        }
        else -> Unit
    }
}

/** Round `+` at the start of an empty line; opens a fan of round insert buttons in place. */
@Composable
private fun PlusFan(state: EditorState, vis: Float, toasts: ToastState) {
    val c = Noted.colors
    val haptics = Noted.haptics
    var open by remember { mutableStateOf(false) }
    val o by animateFloatAsState(if (open) 1f else 0f, Motion.touch(), label = "fan")
    LaunchedEffect(state.focused) { if (state.focused == null) open = false }
    val items = listOf(
        Act(Ph.CheckSquare, "צ׳קבוקס") { state.insert(BlockType.Check) },
        Act(Ph.ListBullets, "רשימה") { state.insert(BlockType.Bullet) },
        Act(Ph.Image, "תמונה") { toasts.show("תמונות מגיעות בשלב 5", Ph.Image) },
        Act(Ph.ScribbleLoop, "ציור") { toasts.show("ציור מגיע בשלב 5", Ph.ScribbleLoop) },
        Act(Ph.Waveform, "הקלטה") { toasts.show("הקלטות מגיעות בשלב 5", Ph.Waveform) },
    )
    Row(
        Modifier.padding(top = 1.dp, end = 8.dp).graphicsLayer { alpha = vis; scaleX = .6f + .4f * vis; scaleY = .6f + .4f * vis },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(32.dp)
                .press(scale = .85f) { open = !open; haptics.light() }
                .graphicsLayer { rotationZ = 45f * o }
                .clip(CircleShape)
                .background(c.noteInk),
            contentAlignment = Alignment.Center,
        ) { Icon(Ph.Plus, "הוספה", Modifier.size(17.dp), tint = if (c.isDark) Color.Black else Color.White) }
        if (o > .01f) {
            items.forEachIndexed { i, (icon, label, act) ->
                val k = ((o * (items.size + 2) - i) / 2f).coerceIn(0f, 1f)
                Box(
                    Modifier
                        .size(32.dp)
                        .graphicsLayer { alpha = k; scaleX = .4f + .6f * k; scaleY = .4f + .6f * k; translationX = 0f }
                        .press(scale = .85f) { open = false; act() }
                        .clip(CircleShape)
                        .background(c.noteInk.copy(alpha = .1f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(icon, label, Modifier.size(17.dp), tint = c.noteInk) }
            }
        }
    }
}

/** The floating circles over a selection: bold, marker, list, copy, cut, paste. */
@Composable
private fun SelectionBubble(bubble: Bubble, origin: Offset, state: EditorState) {
    val c = Noted.colors
    val haptics = Noted.haptics
    val clipboard = LocalClipboardManager.current
    val shown = bubble.status == TextToolbarStatus.Shown && bubble.rect != null
    val p by animateFloatAsState(if (shown) 1f else 0f, Motion.touch(), label = "bubble")
    val r = bubble.rect ?: return
    if (p < .01f) return
    val density = LocalDensity.current
    val screenWDp = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    var w by remember { mutableStateOf(0) }
    val inBlock = state.current != null
    val items = buildList<Act> {
        if (inBlock) {
            add(Act(Ph.TextB, "מודגש") { state.style(Rich.BOLD); haptics.tick() })
            add(Act(Ph.Highlighter, "מרקר") {
                val on = state.current?.let { Rich.inkOf(it.spans, it.sel.min, it.sel.max, "h:") } != null
                state.ink("h:", if (on) null else Ink.Orange); haptics.tick()
            })
            add(Act(Ph.ListBullets, "לרשימה") { state.setType(BlockType.Bullet); bubble.hide(); haptics.tick() })
        }
        bubble.onCopy?.let { f -> add(Act(Ph.Copy, "העתקה") { f(); bubble.hide() }) }
        bubble.onCut?.let { f -> add(Act(Ph.Scissors, "גזירה") { f(); bubble.hide() }) }
        bubble.onPaste?.let { f ->
            add(Act(Ph.ClipboardText, "הדבקה") {
                // multi-line pastes become blocks
                val txt = clipboard.getText()?.text
                if (txt != null && txt.contains('\n') && inBlock) state.paste(txt) else f()
                bubble.hide()
            })
        }
    }
    if (items.isEmpty()) return
    with(density) {
        val screenW = screenWDp.dp.toPx()
        val above = r.top - origin.y - 64.dp.toPx()
        val y = if (above > 80.dp.toPx()) above else r.bottom - origin.y + 14.dp.toPx()
        val cx = (r.left + r.right) / 2 - origin.x
        val x = (cx - w / 2f).coerceIn(12.dp.toPx(), max(12.dp.toPx(), screenW - w - 12.dp.toPx()))
        Row(
            Modifier
                .absoluteOffset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .onSizeChanged { w = it.width }
                .graphicsLayer { alpha = p.coerceIn(0f, 1f); scaleX = .7f + .3f * p; scaleY = .7f + .3f * p; translationY = (1f - p) * 8.dp.toPx() },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items.forEach { (icon, label, act) ->
                val on = when (icon) {
                    Ph.TextB -> state.isOn(Rich.BOLD)
                    else -> false
                }
                Box(
                    Modifier
                        .size(46.dp)
                        .softShadow(Shadows.float(c.isDark), 23.dp, c.inverse)
                        .clip(CircleShape)
                        .background(if (on) c.accent else c.inverse)
                        .press(scale = .86f, onClick = act),
                    contentAlignment = Alignment.Center,
                ) { Icon(icon, label, Modifier.size(20.dp), tint = if (on) c.accentInk else c.inverseInk) }
            }
        }
    }
}

private data class Act(val icon: ImageVector, val label: String, val run: () -> Unit)

private enum class BarMode { Insert, Text, List, Format }
private enum class SubBar { None, Heading, Color, Mark }

/**
 * Dark floating bar above the keyboard; its row changes with the context:
 * empty line → insert, selection → format, inside a list → indent / convert, otherwise text types.
 * A button can open a sub-bar above it (headings, ink, marker).
 */
@Composable
private fun SmartBar(state: EditorState, toasts: ToastState) {
    val c = Noted.colors
    val haptics = Noted.haptics
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val b = state.current
    val vis by animateFloatAsState(if (b != null) 1f else 0f, Motion.glide(), label = "bar")
    var sub by remember { mutableStateOf(SubBar.None) }
    LaunchedEffect(b?.id) { sub = SubBar.None }
    if (vis < .01f) return
    val mode = when {
        b == null -> BarMode.Text
        !b.sel.collapsed -> BarMode.Format
        b.type in LIST_TYPES -> BarMode.List
        b.type == BlockType.P && b.text.isEmpty() -> BarMode.Insert
        else -> BarMode.Text
    }
    LaunchedEffect(mode) { if (mode != BarMode.Format && mode != BarMode.Text) sub = SubBar.None }

    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
            .graphicsLayer { alpha = vis; translationY = (1f - vis) * 30.dp.toPx() },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AnimatedContent(
            targetState = sub,
            transitionSpec = { (fadeIn(Motion.glide()) + scaleIn(Motion.touch(), .9f)).togetherWith(fadeOut(Motion.glide()) + scaleOut(Motion.glide(), .9f)) },
            label = "sub",
        ) { s ->
            when (s) {
                SubBar.None -> Spacer(Modifier.height(0.dp))
                SubBar.Heading -> BarShell {
                    BarBtn(Ph.TextHOne, "כותרת גדולה", b?.type == BlockType.H1) { state.setType(BlockType.H1); sub = SubBar.None }
                    BarBtn(Ph.TextHTwo, "כותרת בינונית", b?.type == BlockType.H2) { state.setType(BlockType.H2); sub = SubBar.None }
                    BarBtn(Ph.TextHThree, "כותרת קטנה", b?.type == BlockType.H3) { state.setType(BlockType.H3); sub = SubBar.None }
                    BarBtn(Ph.Quotes, "ציטוט", b?.type == BlockType.Quote) { state.setType(BlockType.Quote); sub = SubBar.None }
                }
                SubBar.Color, SubBar.Mark -> BarShell {
                    val prefix = if (s == SubBar.Color) "c:" else "h:"
                    val now = b?.let { Rich.inkOf(it.spans, it.sel.min, it.sel.max, prefix) }
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).press(scale = .85f) { state.ink(prefix, null); haptics.tick() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(26.dp).clip(CircleShape).drawBehind {
                            drawCircle(c.inverseInk2, size.minDimension / 2 - 1.dp.toPx(), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                            drawLine(c.inverseInk2, Offset(size.width * .2f, size.height * .8f), Offset(size.width * .8f, size.height * .2f), 2.dp.toPx())
                        })
                    }
                    Ink.entries.forEach { ink ->
                        val on = now == ink.name
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).press(scale = .85f) { state.ink(prefix, ink); haptics.tick() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier.size(if (on) 30.dp else 26.dp).clip(CircleShape)
                                    .background(if (s == SubBar.Color) ink.ink(true) else ink.mark(false).copy(alpha = .9f))
                                    .drawBehind { if (on) drawCircle(c.inverseInk, size.minDimension / 2 - 1.dp.toPx(), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx())) },
                            )
                        }
                    }
                }
            }
        }
        AnimatedContent(
            targetState = mode,
            transitionSpec = { (fadeIn(Motion.glide()) + scaleIn(Motion.touch(), .96f)).togetherWith(fadeOut(Motion.glide())) },
            label = "bar",
        ) { m ->
            BarShell {
                when (m) {
                    BarMode.Insert -> {
                        BarBtn(Ph.CheckSquare, "צ׳קבוקס") { state.insert(BlockType.Check) }
                        BarBtn(Ph.ListBullets, "תבליטים") { state.insert(BlockType.Bullet) }
                        BarBtn(Ph.ListNumbers, "מספור") { state.insert(BlockType.Num) }
                        BarBtn(Ph.TextHOne, "כותרת", sub == SubBar.Heading) { sub = if (sub == SubBar.Heading) SubBar.None else SubBar.Heading }
                        BarBtn(Ph.Minus, "קו מפריד") { state.insert(BlockType.Divider) }
                        BarBtn(Ph.Image, "תמונה") { toasts.show("תמונות מגיעות בשלב 5", Ph.Image) }
                        BarBtn(Ph.Waveform, "הקלטה") { toasts.show("הקלטות מגיעות בשלב 5", Ph.Waveform) }
                        BarBtn(Ph.ScribbleLoop, "ציור") { toasts.show("ציור מגיע בשלב 5", Ph.ScribbleLoop) }
                    }
                    BarMode.Format -> {
                        BarBtn(Ph.TextB, "מודגש", state.isOn(Rich.BOLD)) { state.style(Rich.BOLD) }
                        BarBtn(Ph.TextItalic, "נטוי", state.isOn(Rich.ITALIC)) { state.style(Rich.ITALIC) }
                        BarBtn(Ph.TextUnderline, "קו תחתי", state.isOn(Rich.UNDER)) { state.style(Rich.UNDER) }
                        BarBtn(Ph.TextStrikethrough, "קו חוצה", state.isOn(Rich.STRIKE)) { state.style(Rich.STRIKE) }
                        BarBtn(Ph.Palette, "צבע טקסט", sub == SubBar.Color) { sub = if (sub == SubBar.Color) SubBar.None else SubBar.Color }
                        BarBtn(Ph.Highlighter, "מרקר", sub == SubBar.Mark) { sub = if (sub == SubBar.Mark) SubBar.None else SubBar.Mark }
                        BarBtn(Ph.X, "ניקוי עיצוב") { state.clearStyles() }
                    }
                    BarMode.List -> {
                        BarBtn(Ph.TextIndent, "הזחה") { state.indent(1) }
                        BarBtn(Ph.TextOutdent, "ביטול הזחה") { state.indent(-1) }
                        BarBtn(Ph.CheckSquare, "צ׳קבוקס", b?.type == BlockType.Check) { state.setType(BlockType.Check) }
                        BarBtn(Ph.ListBullets, "תבליטים", b?.type == BlockType.Bullet) { state.setType(BlockType.Bullet) }
                        BarBtn(Ph.ListNumbers, "מספור", b?.type == BlockType.Num) { state.setType(BlockType.Num) }
                        BarBtn(Ph.TextB, "מודגש", state.isOn(Rich.BOLD)) { state.style(Rich.BOLD) }
                    }
                    BarMode.Text -> {
                        BarBtn(Ph.TextHOne, "כותרת", sub == SubBar.Heading || b?.type in setOf(BlockType.H1, BlockType.H2, BlockType.H3, BlockType.Quote)) {
                            sub = if (sub == SubBar.Heading) SubBar.None else SubBar.Heading
                        }
                        BarBtn(Ph.CheckSquare, "צ׳קבוקס") { state.setType(BlockType.Check) }
                        BarBtn(Ph.ListBullets, "תבליטים") { state.setType(BlockType.Bullet) }
                        BarBtn(Ph.ListNumbers, "מספור") { state.setType(BlockType.Num) }
                        BarBtn(Ph.TextB, "מודגש", state.isOn(Rich.BOLD)) { state.style(Rich.BOLD) }
                        BarBtn(Ph.TextItalic, "נטוי", state.isOn(Rich.ITALIC)) { state.style(Rich.ITALIC) }
                        BarBtn(Ph.Palette, "צבע טקסט", sub == SubBar.Color) { sub = if (sub == SubBar.Color) SubBar.None else SubBar.Color }
                    }
                }
                Spacer(Modifier.width(4.dp))
                Box(Modifier.width(1.5.dp).height(26.dp).background(c.inverseInk2.copy(alpha = .3f)))
                BarBtn(Ph.Check, "סיום") { keyboard?.hide(); focusManager.clearFocus(); haptics.light() }
            }
        }
    }
}

@Composable
private fun BarShell(content: @Composable () -> Unit) {
    val c = Noted.colors
    Row(
        Modifier
            .fillMaxWidth()
            .softShadow(Shadows.float(c.isDark), 22.dp, c.inverse)
            .clip(Squircle(22.dp))
            .background(c.inverse)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) { content() }
}

@Composable
private fun BarBtn(icon: ImageVector, desc: String, on: Boolean = false, onClick: () -> Unit) {
    val c = Noted.colors
    val haptics = Noted.haptics
    val bg by animateColorAsState(if (on) c.accent else Color.Transparent, Motion.glide(), label = "barBg")
    val fg by animateColorAsState(if (on) c.accentInk else c.inverseInk, Motion.glide(), label = "barFg")
    Box(
        Modifier
            .size(44.dp)
            .press(scale = .84f, haptic = false) { haptics.tick(); onClick() }
            .clip(Squircle(14.dp))
            .background(bg),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, Modifier.size(22.dp), tint = fg) }
}
