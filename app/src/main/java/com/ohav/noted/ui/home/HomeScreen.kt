package com.ohav.noted.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ohav.noted.NotedApp
import com.ohav.noted.data.Blocks
import com.ohav.noted.data.NoteEntity
import com.ohav.noted.design.Btn
import com.ohav.noted.design.IconButton
import com.ohav.noted.design.Motion
import com.ohav.noted.design.NoteColor
import com.ohav.noted.design.Noted
import com.ohav.noted.design.Ph
import com.ohav.noted.design.Shapes
import com.ohav.noted.design.Squircle
import com.ohav.noted.design.ToastState
import com.ohav.noted.design.press
import com.ohav.noted.design.softShadow
import com.ohav.noted.design.SoftShadow
import com.ohav.noted.ui.Chrome
import com.ohav.noted.ui.common.EmptyArt
import com.ohav.noted.ui.common.EmptyState
import com.ohav.noted.update.Updater
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sign

private data class Cat(val id: String, val name: String)

@Composable
fun HomeScreen(app: NotedApp, toasts: ToastState, chrome: Chrome, onSettings: () -> Unit) {
    val c = Noted.colors
    val haptics = Noted.haptics
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val notesOrNull by app.notes.notes.collectAsStateWithLifecycle(initialValue = null)
    val dbCats by app.notes.categories.collectAsStateWithLifecycle(initialValue = emptyList())
    val update by app.updater.state.collectAsState()
    val notes = notesOrNull.orEmpty()
    val cats = remember(dbCats) { listOf(Cat("all", "כל הפתקים")) + dbCats.map { Cat(it.id, it.name) } }

    var cat by rememberSaveable { mutableStateOf("all") }
    var shownCat by rememberSaveable { mutableStateOf("all") }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val stack = remember { StackState() }
    val scroll = rememberScrollState()
    val catIndex = cats.indexOfFirst { it.id == cat }.coerceAtLeast(0)

    val visible = remember(notes, shownCat, searching, query) {
        when {
            searching -> query.trim().let { q ->
                if (q.isEmpty()) emptyList()
                else notes.filter { n -> n.title.contains(q, true) || Blocks.parse(n.blocks).any { it.text.contains(q, true) } }
            }
            shownCat == "all" -> notes
            else -> notes.filter { it.categoryId == shownCat }
        }
    }
    val blocksOf = remember(notes) { notes.associate { it.id to Blocks.parse(it.blocks) } }
    if (expanded != null && visible.none { it.id == expanded }) expanded = null

    fun goCat(i: Int) {
        cat = cats[i.coerceIn(0, cats.lastIndex)].id
    }

    // category change: old cards slide away, then the new set rises in, staggered
    LaunchedEffect(cat) {
        if (cat == shownCat) {
            stack.exit.animateTo(0f, Motion.glide())
            return@LaunchedEffect
        }
        haptics.tick()
        expanded = null
        val from = cats.indexOfFirst { it.id == shownCat }
        stack.exitDir = if (catIndex > from) 1f else -1f
        val leaving = launch { stack.exit.animateTo(1f, Motion.glide()) }
        delay(200)
        leaving.cancel()
        stack.hideAll()
        stack.enterArmed = true
        shownCat = cat
        stack.enterGen++
        stack.exit.snapTo(0f)
    }

    // open card: keep it in view, leaving room above for the strips of the cards folded behind it
    LaunchedEffect(expanded, expanded?.let { stack.fullHeights[it] }) {
        val id = expanded ?: return@LaunchedEffect
        val i = visible.indexOfFirst { it.id == id }
        if (i < 0 || i >= stack.tops.size) return@LaunchedEffect
        with(density) {
            val top = stack.stackTop + stack.tops[i]
            val bottom = top + stack.heights[i]
            val viewB = scroll.value + scroll.viewportSize - 120.dp.toPx()
            var target = scroll.value.toFloat()
            if (bottom > viewB) target += bottom - viewB
            val room = StackDims.PilePad.toPx() + minOf(StackDims.PEEK_N, i) * StackDims.Peek.toPx()
            target = minOf(target, top - room)
            if (top < scroll.value + room) target = top - room
            if (abs(target - scroll.value) > 2f) scroll.animateScrollTo(target.coerceAtLeast(0f).toInt(), Motion.glide())
        }
    }

    // nav + FAB step aside while scrolling down, return on the way up
    LaunchedEffect(scroll) {
        var last = 0
        snapshotFlow { scroll.value }.collect { st ->
            val d = st - last
            with(density) {
                if (d > 6 && st > 60.dp.toPx()) chrome.navAway = true
                else if (d < -6 || st < 20.dp.toPx()) chrome.navAway = false
            }
            last = st
        }
    }
    LaunchedEffect(chrome.scrollTopTick) {
        if (chrome.scrollTopTick > 0) scroll.animateScrollTo(0, Motion.glide())
    }

    fun closeSearch() {
        searching = false
        query = ""
        stack.enterArmed = true
        stack.enterGen++
    }
    BackHandler(enabled = searching || expanded != null) {
        if (expanded != null) expanded = null else closeSearch()
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            Column(Modifier.fillMaxWidth().onSizeChanged { stack.stackTop = it.height }) {
                AnimatedContent(
                    targetState = searching,
                    transitionSpec = {
                        (slideInVertically(Motion.glide()) { -it / 2 } + fadeIn(Motion.glide()))
                            .togetherWith(slideOutVertically(Motion.glide()) { -it / 2 } + fadeOut(Motion.glide()))
                    },
                    label = "top",
                ) { s ->
                    if (s) SearchBar(query, onQuery = { query = it; expanded = null }, onCancel = ::closeSearch)
                    else TopBar(
                        onProfile = onSettings,
                        onSearch = {
                            haptics.light()
                            searching = true
                            query = ""
                            expanded = null
                        },
                    )
                }
                if (!searching) {
                    CategoryTrack(cats, catIndex, notes, onSelect = ::goCat)
                    CatSub(visible, blocksOf)
                } else {
                    Spacer(Modifier.height(10.dp))
                }
                (update as? Updater.State.Available)?.let { a ->
                    if (!searching) UpdateBanner(a.versionName, onSettings)
                }
            }

            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                NoteStack(
                    notes = visible,
                    expanded = expanded,
                    state = stack,
                    scroll = scroll,
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) { detectTapGestures { expanded = null } }
                        .pointerInput(searching, catIndex, cats.size) {
                            if (searching) return@pointerInput
                            val max = 90.dp.toPx()
                            var total = 0f
                            detectHorizontalDragGestures(
                                onDragStart = { total = 0f },
                                onDragEnd = {
                                    scope.launch { stack.swipe.animateTo(0f, Motion.touch()) }
                                    // RTL: the next category sits to the left, so dragging right brings it in
                                    if (total > 60.dp.toPx()) goCat(catIndex + 1)
                                    else if (total < -60.dp.toPx()) goCat(catIndex - 1)
                                },
                                onDragCancel = { scope.launch { stack.swipe.animateTo(0f, Motion.touch()) } },
                            ) { ch, dx ->
                                ch.consume()
                                total += dx
                                val band = sign(total) * max * (1f - exp(-abs(total) / max))
                                scope.launch { stack.swipe.snapTo(band) }
                            }
                        },
                ) { n, _ ->
                    NoteCard(
                        n = n,
                        blocks = blocksOf[n.id].orEmpty(),
                        expanded = n.id == expanded,
                        onFullHeight = { h -> if (stack.fullHeights[n.id] != h) stack.fullHeights[n.id] = h },
                        onClick = { if (expanded == n.id) toasts.show("העורך מגיע בשלב הבא", Ph.NotePencil) else expanded = n.id },
                        onLongClick = {
                            scope.launch { app.notes.trash(listOf(n.id)) }
                            toasts.show("הפתק הועבר לסל המחזור", Ph.Trash, action = "ביטול", millis = 5000) {
                                scope.launch { app.notes.restore(listOf(n.id)) }
                            }
                        },
                        onToggleCheck = { blockId ->
                            scope.launch {
                                val r = app.notes.toggleCheck(n.id, blockId) ?: return@launch
                                if (r.first == r.second && r.second > 0) toasts.show("כל הרשימה סומנה. יפה!", Ph.CheckCircleFill)
                            }
                        },
                        onOpenEditor = { toasts.show("העורך מגיע בשלב הבא", Ph.NotePencil) },
                    )
                }
                if (notesOrNull != null && visible.isEmpty() && stack.exit.value == 0f) {
                    val q = query.trim()
                    when {
                        searching && q.isEmpty() -> EmptyState(EmptyArt.Search, "מה מחפשים?", "חיפוש בכותרות ובתוכן של כל הפתקים.")
                        searching -> EmptyState(EmptyArt.Search, "לא מצאנו", "אין פתק שמכיל את המילים האלה. אולי בכתיב אחר?")
                        else -> EmptyState(
                            EmptyArt.Stack, "ריק כאן, בינתיים",
                            "פתק חדש ב״${cats.getOrNull(catIndex)?.name ?: ""}״ יופיע בראש הערימה.",
                            Modifier.padding(top = 10.dp),
                        ) { Btn("פתק חדש", icon = Ph.Plus) { scope.launch { newNote(app, cat, notes.size) } } }
                    }
                }
            }
        }

        // new note — squircle FAB above the nav, at the end side (left in RTL)
        val low by animateFloatAsState(if (chrome.navAway) 1f else 0f, Motion.glide(), label = "fabLow")
        val fabShadow = SoftShadow(c.accent.copy(alpha = .55f).compositeOverBlack(.3f), 30.dp, 14.dp, (-10).dp)
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 30.dp, bottom = 18.dp + 90.dp)
                .graphicsLayer {
                    translationY = low * 92.dp.toPx()
                    alpha = if (searching) 0f else 1f
                }
                .size(66.dp)
                .press(enabled = !searching, scale = .9f) {
                    scope.launch {
                        newNote(app, cat, notes.size)
                        scroll.animateScrollTo(0, Motion.glide())
                    }
                }
                .softShadow(fabShadow, 24.dp, c.accent)
                .clip(Squircle(24.dp))
                .background(c.accent),
            contentAlignment = Alignment.Center,
        ) { Icon(Ph.Plus, "פתק חדש", Modifier.size(28.dp), tint = c.accentInk) }
    }
}

private fun Color.compositeOverBlack(amount: Float) = Color(red * (1 - amount), green * (1 - amount), blue * (1 - amount), alpha)

private suspend fun newNote(app: NotedApp, cat: String, count: Int) {
    val colors = listOf(NoteColor.Butter, NoteColor.Peach, NoteColor.Rose, NoteColor.Lilac, NoteColor.Sky, NoteColor.Mint, NoteColor.Sage, NoteColor.Sand)
    app.notes.create(if (cat == "all") "home" else cat, colors[(count * 3) % colors.size], title = "פתק חדש")
}

@Composable
private fun TopBar(onProfile: () -> Unit, onSearch: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(top = 14.dp).height(64.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar("א", onProfile)
        Spacer(Modifier.weight(1f))
        IconButton(Ph.MagnifyingGlass, "חיפוש", onClick = onSearch)
    }
}

/** Account avatar (settings live behind it until sign-in arrives) — conic gradient, ringed by the page. */
@Composable
private fun Avatar(letter: String, onClick: () -> Unit) {
    val c = Noted.colors
    val brush = remember { Brush.sweepGradient(listOf(Color(0xFFF4B98F), Color(0xFFE58F6F), Color(0xFFC9A2E8), Color(0xFFF4B98F))) }
    Box(
        Modifier
            .size(51.dp)
            .press(scale = .9f, onClick = onClick)
            .drawBehind {
                val r = size.minDimension / 2
                drawCircle(c.line, r - .75.dp.toPx(), style = Stroke(1.5.dp.toPx()))
                // CSS conic gradients start at 12 o'clock, sweep gradients at 3 — turn by (200° − 90°)
                rotate(110f) { drawCircle(brush, 21.dp.toPx()) }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(letter, style = Noted.type.button.copy(fontFamily = Noted.type.pair.display, fontSize = 17.sp), color = Color(0xFF2A1A10))
    }
}

@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit, onCancel: () -> Unit) {
    val c = Noted.colors
    val t = Noted.type
    val focus = remember { FocusRequester() }
    val fm = LocalFocusManager.current
    LaunchedEffect(Unit) { delay(60); focus.requestFocus() }
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 0.dp).height(64.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier
                .weight(1f)
                .height(54.dp)
                .clip(Shapes.md)
                .background(c.surface)
                .drawBehind {
                    val o = Shapes.md.createOutline(size, layoutDirection, this)
                    drawOutline(o, c.line, style = Stroke(3.dp.toPx()))
                }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Ph.MagnifyingGlass, null, Modifier.size(20.dp), tint = c.ink2)
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("חיפוש בכל הפתקים", style = t.field, color = c.ink3)
                BasicTextField(
                    value = query, onValueChange = onQuery, singleLine = true,
                    textStyle = t.field.copy(color = c.ink),
                    cursorBrush = SolidColor(c.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
        }
        Text(
            "ביטול", style = t.button, color = c.ink2,
            modifier = Modifier.clip(Shapes.sm).press { fm.clearFocus(); onCancel() }.padding(horizontal = 6.dp, vertical = 10.dp),
        )
    }
}

/**
 * The giant category title: a snapping horizontal track. The item nearest the start edge is
 * "current" (ink, with its count); the category switches once the track settles there.
 */
@Composable
private fun CategoryTrack(cats: List<Cat>, index: Int, notes: List<NoteEntity>, onSelect: (Int) -> Unit) {
    val c = Noted.colors
    val t = Noted.type
    val state = rememberLazyListState(initialFirstVisibleItemIndex = index)
    val fling = rememberSnapFlingBehavior(state, SnapPosition.Start)
    val nearest by remember { derivedStateOf { nearestItem(state) } }
    val tail = (LocalConfiguration.current.screenWidthDp * .6f).dp
    val currentIndex by rememberUpdatedState(index)
    val select by rememberUpdatedState(onSelect)

    LaunchedEffect(state) {
        snapshotFlow { if (state.isScrollInProgress) null else nearestItem(state) }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { if (it != currentIndex) select(it) }
    }
    LaunchedEffect(index) {
        if (nearestItem(state) == index && !state.isScrollInProgress) return@LaunchedEffect
        val item = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
        if (item != null) state.animateScrollBy(item.offset.toFloat(), Motion.glide()) else state.scrollToItem(index)
    }

    LazyRow(
        state = state,
        flingBehavior = fling,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(26.dp),
    ) {
        itemsIndexed(cats, key = { _, it -> it.id }) { i, cat ->
            val on = i == nearest
            val col by animateColorAsState(if (on) c.ink else c.ink3, Motion.glide(), label = "cat")
            val show by animateFloatAsState(if (on) 1f else 0f, Motion.glide(), label = "count")
            val count = if (cat.id == "all") notes.size else notes.count { it.categoryId == cat.id }
            Row(
                Modifier
                    .then(if (i == cats.lastIndex) Modifier.padding(end = tail) else Modifier)
                    .pointerInput(i) { detectTapGestures { select(i) } },
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(cat.name, style = t.cat, color = col, maxLines = 1, softWrap = false)
                Text(
                    "$count", style = t.label.copy(fontSize = 14.sp), color = c.accent,
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .widthIn(min = 22.dp)
                        .graphicsLayer { alpha = show; translationY = (1f - show) * 4.dp.toPx() },
                )
            }
        }
    }
}

private fun nearestItem(state: LazyListState): Int =
    state.layoutInfo.visibleItemsInfo.minByOrNull { abs(it.offset) }?.index ?: state.firstVisibleItemIndex

/** "6 פתקים · 3 פריטים פתוחים · 1 תזכורות" under the title. */
@Composable
private fun CatSub(visible: List<NoteEntity>, blocksOf: Map<String, List<com.ohav.noted.data.Block>>) {
    val c = Noted.colors
    val now = System.currentTimeMillis()
    val pending = visible.sumOf { n -> progressOf(blocksOf[n.id].orEmpty())?.let { it.second - it.first } ?: 0 }
    val reminders = visible.count { (it.reminderAt ?: 0L) > now }
    val parts = buildList {
        add(if (visible.size == 1) "פתק אחד" else "${visible.size} פתקים")
        if (pending > 0) add("$pending פריטים פתוחים")
        if (reminders > 0) add(if (reminders == 1) "תזכורת אחת" else "$reminders תזכורות")
    }
    Row(
        Modifier.padding(start = 16.dp, end = 16.dp, bottom = 18.dp).heightIn(min = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        parts.forEachIndexed { i, p ->
            if (i > 0) Box(Modifier.size(4.dp).clip(CircleShape).background(c.ink3))
            Text(p, style = Noted.type.sub, color = c.ink2)
        }
    }
}

@Composable
private fun UpdateBanner(version: String, onClick: () -> Unit) {
    val c = Noted.colors
    val t = Noted.type
    Row(
        Modifier.padding(start = 16.dp, end = 16.dp, bottom = 18.dp).fillMaxWidth().press(scale = .97f, onClick = onClick)
            .clip(Shapes.lg).background(c.inverse).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Ph.Sparkle, null, Modifier.size(22.dp), tint = c.accent)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("גרסה חדשה מוכנה", style = t.label, color = c.inverseInk)
            Text("noted $version", style = t.caption, color = c.inverseInk2)
        }
        Icon(Ph.CaretEnd, null, Modifier.size(18.dp), tint = c.inverseInk2)
    }
}
