package com.ohav.noted.ui.home

import com.ohav.noted.data.shareInfo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.ui.draw.clipToBounds
import kotlinx.coroutines.CoroutineStart
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import com.ohav.noted.design.LocalOverlays
import com.ohav.noted.ui.reminders.openReminderPicker
import com.ohav.noted.design.SheetRow
import kotlin.math.roundToInt
import androidx.compose.animation.AnimatedContent
import com.ohav.noted.data.CatTree
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.animation.SizeTransform
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.drawOutline
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
import com.ohav.noted.ui.categories.CatIcons
import com.ohav.noted.ui.categories.rememberCategoryActions
import com.ohav.noted.ui.common.EmptyArt
import com.ohav.noted.ui.common.EmptyState
import com.ohav.noted.update.Updater
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sign

private data class Cat(val id: String, val name: String)

@Composable
fun HomeScreen(app: NotedApp, toasts: ToastState, chrome: Chrome, onSettings: () -> Unit, onCategories: () -> Unit) {
    val c = Noted.colors
    val haptics = Noted.haptics
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val scope = rememberCoroutineScope()
    val notesOrNull by app.notes.notes.collectAsStateWithLifecycle(initialValue = null)
    val dbCats by app.notes.categories.collectAsStateWithLifecycle(initialValue = emptyList())
    val update by app.updater.state.collectAsState()
    // a note shared with me shows here only once I've placed it in one of my categories
    val notes = remember(notesOrNull) { notesOrNull.orEmpty().filter { it.categoryId.isNotEmpty() } }
    val tree = remember(dbCats) { CatTree(dbCats) }
    // the giant title shows top-level categories; subcategories ride a smaller track under it
    val cats = remember(tree) { listOf(Cat("all", "כל הפתקים")) + tree.tops.map { Cat(it.id, it.name) } }
    // the stack swipe walks this: each category, then its subcategories, then the next one
    val flat = remember(tree) { listOf("all") + tree.flat.map { it.id } }
    val names = remember(dbCats) { dbCats.associate { it.id to it.name } + ("all" to "כל הפתקים") }

    var cat by rememberSaveable { mutableStateOf("all") }
    var shownCat by rememberSaveable { mutableStateOf("all") }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val stack = remember { StackState() }
    val topId = if (cat == "all") "all" else tree.topOf(cat)
    val catIndex = cats.indexOfFirst { it.id == topId }.coerceAtLeast(0)
    val flatIndex = flat.indexOf(cat).coerceAtLeast(0)

    val overlays = LocalOverlays.current
    val catActions = rememberCategoryActions(app, overlays, toasts)
    // the category on screen was deleted (here or in settings): fall back to all notes
    LaunchedEffect(dbCats) {
        if (dbCats.isNotEmpty() && cat != "all" && dbCats.none { it.id == cat }) cat = "all"
    }
    val lift = remember { LiftState() }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    val baseVisible = remember(notes, shownCat, searching, query, tree) {
        when {
            searching -> query.trim().let { q ->
                if (q.isEmpty()) emptyList()
                else notes.filter { n -> n.title.contains(q, true) || Blocks.parse(n.blocks).any { it.text.contains(q, true) } }
            }
            shownCat == "all" -> notes
            else -> tree.family(shownCat).let { fam -> notes.filter { it.categoryId in fam } }
        }
    }
    val visible = lift.order?.let { ord ->
        val pos = ord.withIndex().associate { it.value to it.index }
        baseVisible.sortedBy { pos[it.id] ?: Int.MAX_VALUE }
    } ?: baseVisible
    // drop the local order once the database reflects it
    LaunchedEffect(baseVisible) {
        val o = lift.order ?: return@LaunchedEffect
        if (!lift.dragging && !lift.settling && baseVisible.map { it.id } == o) lift.order = null
    }
    // parse only what changed: an autosave of one note must not re-parse (and recompose) the rest
    val parsed = remember { HashMap<String, Pair<String, List<com.ohav.noted.data.Block>>>() }
    val blocksOf = remember(notes) {
        notes.associate { n ->
            val hit = parsed[n.id]
            n.id to if (hit != null && hit.first == n.blocks) hit.second
            else Blocks.parse(n.blocks).also { parsed[n.id] = n.blocks to it }
        }
    }
    if (expanded != null && visible.none { it.id == expanded }) expanded = null

    /** Stack swipe: the next / previous stop along categories and their subcategories. */
    fun goFlat(i: Int) {
        cat = flat[i.coerceIn(0, flat.lastIndex)]
    }

    /** Long press on a title: this category's actions, or new / manage for all notes. */
    fun catMenu(id: String) {
        haptics.medium()
        val k = dbCats.firstOrNull { it.id == id }
        val count = if (k == null) notes.size else notes.count { it.categoryId == k.id }
        overlays.sheet.show(k?.name ?: "כל הפתקים") {
            if (k != null) SheetRow(Ph.PencilSimple, "שם ואייקון") { catActions.edit(k, dbCats, count) }
            if (k != null && tree.isTop(k.id)) SheetRow(Ph.Plus, "תת-קטגוריה חדשה") {
                catActions.create(dbCats, parent = k.id) { made -> cat = made.id }
            }
            SheetRow(Ph.FolderSimplePlus, "קטגוריה חדשה") {
                catActions.create(dbCats) { made -> cat = made.id }
            }
            SheetRow(Ph.ArrowsDownUp, "סידור וניהול", last = k == null) { overlays.sheet.close(); onCategories() }
            if (k != null) SheetRow(Ph.Trash, "מחיקת הקטגוריה", danger = true, last = true) { catActions.delete(k, dbCats, count) }
        }
    }

    fun startSelecting(first: String?) {
        selecting = true
        expanded = null
        selected = setOfNotNull(first)
        chrome.navHidden = true
        haptics.medium()
    }

    fun stopSelecting() {
        selecting = false
        selected = emptySet()
        chrome.navHidden = false
    }

    fun startLift(n: NoteEntity) {
        if (selecting || searching || lift.id != null) return
        val rect = lift.coords[n.id]?.takeIf { it.isAttached }?.boundsInRoot() ?: return
        lift.begin(n.id, rect)
        scope.launch {
            lift.settle.snapTo(0f)
            lift.height.snapTo(rect.height)
            lift.pop.animateTo(1f, Motion.touch())
        }
    }

    fun settleLift(after: (() -> Unit)? = null) {
        if (lift.id == null || lift.settling) { after?.invoke(); return }
        val order = if (lift.dragging) lift.order else null
        lift.menu = false
        lift.dragging = false
        lift.settling = true
        scope.launch {
            if (order != null) launch { app.notes.reorder(order, notes) }
            launch { lift.pop.animateTo(0f, Motion.glide()) }
            lift.settle.animateTo(1f, Motion.glide())
            lift.clear()
            haptics.light()
            delay(1200)
            if (lift.id == null) lift.order = null
        }
        after?.invoke()
    }

    fun openMove(ids: List<String>) {
        overlays.sheet.show(if (ids.size > 1) "להעביר ${ids.size} פתקים ל…" else "להעביר ל…") {
            tree.flat.forEach { k ->
                SheetRow(CatIcons.of(k.icon), k.name, end = "${notes.count { it.categoryId == k.id }}", inset = k.id in tree.parentOf) {
                    scope.launch { app.notes.move(ids, k.id) }
                    overlays.sheet.close()
                    if (selecting) stopSelecting()
                    toasts.show(if (ids.size > 1) "${ids.size} פתקים עברו ל״${k.name}״" else "עבר ל״${k.name}״", Ph.FolderSimple)
                }
            }
            SheetRow(Ph.FolderSimplePlus, "קטגוריה חדשה", last = true) {
                catActions.create(dbCats) { k ->
                    scope.launch { app.notes.move(ids, k.id) }
                    if (selecting) stopSelecting()
                    toasts.show(if (ids.size > 1) "${ids.size} פתקים עברו ל״${k.name}״ החדשה" else "עבר ל״${k.name}״ החדשה", CatIcons.of(k.icon))
                }
            }
        }
    }

    fun openColor(ids: List<String>) {
        overlays.sheet.show("צבע לכל הנבחרים") {
            ColorGrid { col ->
                scope.launch { app.notes.setColor(ids, col) }
                haptics.light()
                overlays.sheet.close()
                stopSelecting()
            }
        }
    }

    fun confirmDelete(ids: List<String>) {
        scope.launch {
            val one = ids.singleOrNull()?.let { id -> notes.firstOrNull { it.id == id } }
            val sharedOut = ids.count { id -> notes.firstOrNull { it.id == id }?.shareInfo?.let { s -> s.shared && !s.withMe } == true }
            val ok = overlays.dialog.confirm(
                title = if (one != null) "להעביר לסל המחזור?" else "להעביר ${ids.size} פתקים לסל?",
                text = when {
                    sharedOut > 0 && one != null -> "הפתק משותף — הוא ייעלם גם אצל המשתתפים. שחזור מהסל מחזיר אותו לכולם."
                    sharedOut > 0 -> "$sharedOut מהם משותפים — הם ייעלמו גם אצל המשתתפים. שחזור מהסל מחזיר אותם לכולם."
                    else -> "אפשר לשחזר אותם מסל המחזור."
                },
                icon = Ph.Trash, danger = true,
                note = one?.let { it.title.ifBlank { "ללא כותרת" } to c.note(NoteColor.of(it.color)) },
                confirm = "העבר לסל", cancel = "השאר",
            )
            if (!ok) return@launch
            if (selecting) stopSelecting()
            app.notes.trash(ids)
            toasts.show(if (ids.size > 1) "${ids.size} פתקים הועברו לסל" else "הפתק הועבר לסל המחזור", Ph.Trash, action = "ביטול", millis = 5000) {
                scope.launch { app.notes.restore(ids) }
            }
        }
    }

    fun archive(ids: List<String>) {
        scope.launch { app.notes.archive(ids) }
        toasts.show(if (ids.size > 1) "${ids.size} פתקים הועברו לארכיון" else "הועבר לארכיון", Ph.Archive, action = "ביטול", millis = 5000) {
            scope.launch { app.notes.archive(ids, false) }
        }
    }

    fun onMenu(a: String) {
        val n = lift.id?.let { id -> notes.firstOrNull { it.id == id } } ?: return
        val ids = listOf(n.id)
        when (a) {
            "pin" -> settleLift {
                scope.launch { app.notes.setPinned(ids, !n.pinned) }
                toasts.show(if (!n.pinned) "ננעץ בראש הערימה" else "השחרור בוצע", Ph.PushPinFill)
            }
            "fav" -> settleLift {
                scope.launch { app.notes.setFavorite(ids, !n.favorite) }
                toasts.show(if (!n.favorite) "נוסף למועדפים" else "הוסר מהמועדפים", Ph.StarFill)
            }
            "move" -> settleLift { openMove(ids) }
            "archive" -> settleLift { archive(ids) }
            "delete" -> settleLift { confirmDelete(ids) }
            "select" -> settleLift { startSelecting(n.id) }
            "remind" -> settleLift { openReminderPicker(app, overlays, toasts, scope, n.id) }
            "share" -> settleLift { com.ohav.noted.ui.share.openShareSheet(app, overlays, toasts, scope, n.id) }
            "lock" -> settleLift { toasts.show("נעילת פתקים מגיעה בהמשך", Ph.Lock) }
        }
    }

    fun onSelAction(a: String) {
        val ids = selected.toList()
        if (ids.isEmpty()) { toasts.show("קודם בוחרים פתקים", Ph.HandTap); return }
        when (a) {
            "color" -> openColor(ids)
            "move" -> openMove(ids)
            "delete" -> confirmDelete(ids)
            "pin" -> {
                val all = ids.all { id -> notes.firstOrNull { it.id == id }?.pinned == true }
                scope.launch { app.notes.setPinned(ids, !all) }
                stopSelecting()
                toasts.show(if (all) "השחרור בוצע" else "${ids.size} נעוצים למעלה", Ph.PushPinFill)
            }
        }
    }

    // the lifted card + menu live in the app-wide overlay, above the nav
    val liftNote = lift.id?.let { id -> notes.firstOrNull { it.id == id } }
    val liftUi = rememberUpdatedState(
        LiftUi(
            note = liftNote,
            blocks = liftNote?.let { blocksOf[it.id] }.orEmpty(),
            onAction = ::onMenu,
            onColor = { col ->
                haptics.tick()
                liftNote?.let { scope.launch { app.notes.setColor(listOf(it.id), col) } }
            },
            onDismiss = { settleLift() },
        ),
    )
    DisposableEffect(overlays) {
        overlays.layer = { LiftLayer(lift, liftUi.value) }
        onDispose {
            overlays.layer = null
            overlays.blur = 0f
            chrome.navHidden = false
        }
    }
    LaunchedEffect(lift) {
        snapshotFlow { if (lift.menu || (lift.settling && !lift.dragged)) lift.pop.value.coerceIn(0f, 1f) else 0f }
            .collect { overlays.blur = it }
    }
    // while dragging near an edge, scroll the stack under the finger
    LaunchedEffect(lift.dragging) {
        if (!lift.dragging) return@LaunchedEffect
        with(density) {
            val screenH = configuration.screenHeightDp.dp.toPx()
            val speed = 8.dp.toPx() / 2.5f
            while (lift.dragging) {
                withFrameNanos { }
                val y = lift.from.top + lift.touchY + lift.dy
                val v = when {
                    y > screenH - 110.dp.toPx() -> speed
                    y < 90.dp.toPx() -> -speed
                    else -> 0f
                }
                if (v != 0f) lift.startTop += stack.scrollBy(v)
            }
        }
    }

    // category change: old cards slide away, then the new set rises in, staggered
    LaunchedEffect(cat) {
        if (cat == shownCat) {
            stack.exit.animateTo(0f, Motion.glide())
            return@LaunchedEffect
        }
        haptics.tick()
        expanded = null
        val from = flat.indexOf(shownCat)
        stack.exitDir = if (flatIndex > from) 1f else -1f
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
            val top = stack.tops[i]
            val bottom = top + stack.heights[i]
            val viewB = stack.value + stack.viewport - 120.dp.toPx()
            var target = stack.value
            if (bottom > viewB) target += bottom - viewB
            val room = StackDims.PilePad.toPx() + minOf(StackDims.PEEK_N, i) * StackDims.Peek.toPx()
            target = minOf(target, top - room)
            if (top < stack.value + room) target = top - room
            if (abs(target - stack.value) > 2f) stack.animateTo(target)
        }
    }

    // nav + FAB step aside while scrolling down, return on the way up
    LaunchedEffect(stack) {
        var last = 0f
        snapshotFlow { stack.value }.collect { st ->
            val d = st - last
            with(density) {
                if (d > 6 && st > 60.dp.toPx()) chrome.navAway = true
                else if (d < -6 || st < 20.dp.toPx()) chrome.navAway = false
            }
            last = st
        }
    }
    LaunchedEffect(chrome.scrollTopTick) {
        if (chrome.scrollTopTick > 0) stack.animateTo(0f)
    }

    // scrolling is how you move on: a drag away from the open card closes it, keeping it in place
    val stepPx = with(density) { StackDims.Step.toPx() }
    val closeAfter = with(density) { 26.dp.toPx() }
    val expandedNow by rememberUpdatedState(expanded)
    val visibleNow by rememberUpdatedState(visible)
    var dragged by remember { mutableStateOf(0f) }
    LaunchedEffect(expanded) { dragged = 0f }
    DisposableEffect(stack) {
        stack.onUserScroll = { d ->
            val id = expandedNow
            if (id != null) {
                dragged += abs(d)
                if (dragged > closeAfter) {
                    dragged = 0f
                    val i = visibleNow.indexOfFirst { it.id == id }
                    val old = stack.tops.getOrNull(i)
                    expanded = null
                    haptics.tick()
                    if (old != null) scope.launch(start = CoroutineStart.UNDISPATCHED) { stack.shift(i * stepPx - old) }
                }
            }
        }
        onDispose { stack.onUserScroll = null }
    }
    // after a drag or fling leaves the stack past its end, glide back
    LaunchedEffect(stack) {
        snapshotFlow { stack.scrollable.isScrollInProgress }.collect { busy ->
            if (!busy && (stack.value > stack.maxValue + 1f || stack.value < 0f)) stack.animateTo(stack.value)
        }
    }

    var fabRect by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    // new note: opens right away where you can see it. An untouched new note blocks the next one —
    // tapping + again just brings that one back into focus.
    var focusNew by remember { mutableStateOf<String?>(null) }
    fun cardRect(id: String) = lift.coords[id]?.takeIf { it.isAttached }?.boundsInRoot()?.takeIf { it.width > 1f && it.height > 1f }
    fun openEditor(n: NoteEntity, fresh: Boolean = false) {
        if (selecting || lift.id != null) return
        chrome.editor.open(n, cardRect(n.id) ?: fabRect.takeIf { fresh || it.width > 0f }, fresh)
    }
    fun pullFor(n: NoteEntity) = CardPull(
        start = {
            !selecting && lift.id == null && cardRect(n.id) != null &&
                chrome.editor.open(n, cardRect(n.id), held = true).also { if (it) haptics.light() }
        },
        move = { f -> chrome.editor.drag(scope, f) },
        end = { open, v -> chrome.editor.letGo(open, v) },
    )
    fun addNote() {
        val draft = notes.firstOrNull { it.isBlankDraft(blocksOf[it.id].orEmpty()) }
        if (draft != null) {
            if (shownCat != "all" && draft.categoryId !in tree.family(shownCat)) cat = "all"
            focusNew = draft.id
            haptics.light()
            toasts.show("הפתק החדש עוד מחכה לך", Ph.NotePencil)
            chrome.editor.open(draft, cardRect(draft.id) ?: fabRect, fresh = true)
            return
        }
        scope.launch {
            val n = newNote(app, if (cat == "all") tree.tops.firstOrNull()?.id ?: "home" else cat, notes.size)
            focusNew = n.id
            haptics.confirm()
            chrome.editor.open(n, fabRect, fresh = true)
        }
    }
    DisposableEffect(chrome.editor) {
        chrome.editor.cardRect = { id -> cardRect(id) }
        onDispose { chrome.editor.cardRect = null }
    }
    // once the new note shows up in the stack (and any category change has settled), open it
    LaunchedEffect(focusNew, visible, shownCat == cat) {
        val id = focusNew ?: return@LaunchedEffect
        if (cat != shownCat || visible.none { it.id == id }) return@LaunchedEffect
        focusNew = null
        if (expanded == id) {
            val i = visible.indexOfFirst { it.id == id }
            stack.tops.getOrNull(i)?.let { stack.animateTo(it - with(density) { StackDims.PilePad.toPx() + minOf(StackDims.PEEK_N, i) * StackDims.Peek.toPx() }) }
        } else expanded = id
    }

    fun closeSearch() {
        searching = false
        query = ""
        stack.enterArmed = true
        stack.enterGen++
    }
    BackHandler(enabled = searching || expanded != null || selecting || lift.id != null) {
        when {
            lift.id != null -> settleLift()
            selecting -> stopSelecting()
            expanded != null -> expanded = null
            else -> closeSearch()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth()) {
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
                        app,
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
                    val counts = remember(notes, tree) {
                        val direct = notes.groupingBy { it.categoryId }.eachCount()
                        direct + tree.children.mapValues { (top, kids) -> (direct[top] ?: 0) + kids.sumOf { direct[it.id] ?: 0 } } + ("all" to notes.size)
                    }
                    CategoryTrack(
                        cats, catIndex, { counts[it] ?: 0 },
                        onSelect = { cat = cats[it].id }, onLongPress = { catMenu(cats[it].id) },
                    )
                    SubTrack(tree, topId, cat, { counts[it] ?: 0 }, onSelect = { cat = it }, onLongPress = ::catMenu)
                    CatSub(visible, blocksOf)
                } else {
                    Spacer(Modifier.height(10.dp))
                }
                (update as? Updater.State.Available)?.let { a ->
                    if (!searching) UpdateBanner(a.versionName, onSettings)
                }
            }

            // only the stack scrolls; the title and the page around it stay put
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clipToBounds()
                    .onSizeChanged { stack.viewport = it.height }
                    .scrollable(stack.scrollable, Orientation.Vertical, reverseDirection = true)
                    .padding(horizontal = 16.dp),
            ) {
                NoteStack(
                    notes = visible,
                    expanded = expanded,
                    state = stack,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) { detectTapGestures { expanded = null } }
                        .pointerInput(searching, selecting, flatIndex, flat.size) {
                            if (searching || selecting) return@pointerInput
                            val max = 90.dp.toPx()
                            var total = 0f
                            detectHorizontalDragGestures(
                                onDragStart = { total = 0f },
                                onDragEnd = {
                                    scope.launch { stack.swipe.animateTo(0f, Motion.touch()) }
                                    // RTL: the next category sits to the left, so dragging right brings it in
                                    if (total > 60.dp.toPx()) goFlat(flatIndex + 1)
                                    else if (total < -60.dp.toPx()) goFlat(flatIndex - 1)
                                },
                                onDragCancel = { scope.launch { stack.swipe.animateTo(0f, Motion.touch()) } },
                            ) { ch, dx ->
                                ch.consume()
                                total += dx
                                val band = sign(total) * max * (1f - exp(-abs(total) / max))
                                scope.launch { stack.swipe.snapTo(band) }
                            }
                        },
                ) { n, _, clipped ->
                    Box(
                        Modifier
                            .graphicsLayer { alpha = if (lift.id == n.id || chrome.editor.openId == n.id) 0f else 1f }
                            .onGloballyPositioned { lift.coords[n.id] = it },
                    ) {
                    NoteCard(
                        n = n,
                        blocks = blocksOf[n.id].orEmpty(),
                        expanded = n.id == expanded,
                        onFullHeight = { h -> if (stack.fullHeights[n.id] != h) stack.fullHeights[n.id] = h },
                        onClick = {
                            when {
                                selecting -> { selected = if (n.id in selected) selected - n.id else selected + n.id; haptics.tick() }
                                expanded == n.id -> openEditor(n)
                                else -> expanded = n.id
                            }
                        },
                        onLongClick = { startLift(n) },
                        onToggleCheck = { blockId ->
                            if (n.shareInfo.readOnly) { haptics.reject(); toasts.show("יש לך הרשאת צפייה בפתק הזה", Ph.Eye); return@NoteCard }
                            scope.launch {
                                val r = app.notes.toggleCheck(n.id, blockId) ?: return@launch
                                if (r.first == r.second && r.second > 0) toasts.show("כל הרשימה סומנה. יפה!", Ph.CheckCircleFill)
                            }
                        },
                        onOpenEditor = { openEditor(n) },
                        pull = remember(n) { pullFor(n) },
                        selecting = selecting,
                        selected = n.id in selected,
                        clipped = clipped,
                        lift = LiftGestures(
                            onStart = { lift.touchY = it.y },
                            onDrag = { d -> liftDrag(lift, n.id, d, visible, density, scope, haptics) { expanded = null } },
                            onEnd = { if (lift.id == n.id && lift.dragging) settleLift() },
                        ),
                    )
                    }
                }
                if (notesOrNull != null && visible.isEmpty() && stack.exit.value == 0f) {
                    val q = query.trim()
                    when {
                        searching && q.isEmpty() -> EmptyState(EmptyArt.Search, "מה מחפשים?", "חיפוש בכותרות ובתוכן של כל הפתקים.")
                        searching -> EmptyState(EmptyArt.Search, "לא מצאנו", "אין פתק שמכיל את המילים האלה. אולי בכתיב אחר?")
                        else -> EmptyState(
                            EmptyArt.Stack, "ריק כאן, בינתיים",
                            "פתק חדש ב״${names[cat] ?: ""}״ יופיע בראש הערימה.",
                            Modifier.padding(top = 10.dp),
                        ) { Btn("פתק חדש", icon = Ph.Plus) { addNote() } }
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
                    alpha = if (searching || selecting) 0f else 1f
                }
                .size(66.dp)
                .onGloballyPositioned { fabRect = it.boundsInRoot() }
                .press(enabled = !searching && !selecting, scale = .9f) { addNote() }
                .softShadow(fabShadow, 24.dp, c.accent)
                .clip(Squircle(24.dp))
                .background(c.accent),
            contentAlignment = Alignment.Center,
        ) { Icon(Ph.Plus, "פתק חדש", Modifier.size(28.dp), tint = c.accentInk) }

        val selVis by animateFloatAsState(if (selecting) 1f else 0f, Motion.glide(), label = "sel")
        if (selVis > .001f) {
            SelectionHeader(
                selected.size, selVis,
                onClose = ::stopSelecting,
                onAll = { selected = visible.mapTo(HashSet()) { it.id }; haptics.light() },
                modifier = Modifier.align(Alignment.TopCenter),
            )
            SelectionBar(selVis, Modifier.align(Alignment.BottomCenter), onAction = ::onSelAction)
        }
    }
}

/** One step of a lifted card's drag: after a few dp it becomes a reorder; slots follow the finger. */
private fun liftDrag(
    lift: LiftState, id: String, d: Float, visible: List<NoteEntity>,
    density: androidx.compose.ui.unit.Density, scope: kotlinx.coroutines.CoroutineScope,
    haptics: com.ohav.noted.design.Haptics, collapse: () -> Unit,
) {
    if (lift.id != id || lift.settling) return
    lift.dy += d
    val step = with(density) { StackDims.Step.toPx() }
    if (!lift.dragging && abs(lift.dy) > with(density) { 10.dp.toPx() }) {
        lift.dragging = true
        lift.dragged = true
        lift.menu = false
        collapse()
        val ids = visible.map { it.id }
        lift.order = ids
        lift.idx = ids.indexOf(id).coerceAtLeast(0)
        lift.startTop = lift.idx * step
        lift.dyAtDrag = lift.dy
        val hc = with(density) { StackDims.Card.toPx() }
        scope.launch { lift.height.animateTo(hc, Motion.glide()) }
    }
    if (!lift.dragging) return
    val ord = lift.order ?: return
    val pos = lift.startTop + lift.dy - lift.dyAtDrag
    val to = (pos / step).roundToInt().coerceIn(0, ord.lastIndex)
    if (to != lift.idx && lift.idx in ord.indices) {
        lift.order = ord.toMutableList().apply { removeAt(lift.idx); add(to, id) }
        lift.idx = to
        haptics.tick()
    }
}


/** 4-up grid of note colours (sheet content). */
@Composable
private fun ColorGrid(onPick: (NoteColor) -> Unit) {
    val c = Noted.colors
    NoteColor.entries.chunked(4).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { col ->
                Column(
                    Modifier.weight(1f).press(scale = .92f) { onPick(col) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.fillMaxWidth().aspectRatio(1.2f).clip(Shapes.md).background(c.note(col)))
                    Text(col.label, style = Noted.type.caption, color = c.ink2)
                }
            }
        }
    }
}

private fun Color.compositeOverBlack(amount: Float) = Color(red * (1 - amount), green * (1 - amount), blue * (1 - amount), alpha)

private const val NEW_TITLE = "פתק חדש"

private suspend fun newNote(app: NotedApp, cat: String, count: Int): NoteEntity {
    val colors = listOf(NoteColor.Butter, NoteColor.Peach, NoteColor.Rose, NoteColor.Lilac, NoteColor.Sky, NoteColor.Mint, NoteColor.Sage, NoteColor.Sand)
    return app.notes.create(cat, colors[(count * 3) % colors.size])
}

/** A note created with + and never written in. */
private fun NoteEntity.isBlankDraft(blocks: List<com.ohav.noted.data.Block>) =
    (title.isBlank() || title == NEW_TITLE) && blocks.all { it.text.isBlank() } && reminderAt == null

@Composable
private fun TopBar(app: NotedApp, onProfile: () -> Unit, onSearch: () -> Unit) {
    val user by app.account.user.collectAsState()
    val photo by app.account.photo.collectAsState()
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(top = 14.dp).height(64.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(user?.letter ?: "א", photo, onProfile)
        Spacer(Modifier.weight(1f))
        IconButton(Ph.MagnifyingGlass, "חיפוש", onClick = onSearch)
    }
}

/** Account avatar (settings live behind it): the Google photo, or a letter on a conic gradient, ringed by the page. */
@Composable
private fun Avatar(letter: String, photo: androidx.compose.ui.graphics.ImageBitmap?, onClick: () -> Unit) {
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
        if (photo != null) {
            androidx.compose.foundation.Image(
                photo, null, Modifier.size(42.dp).clip(CircleShape),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
        } else {
            Text(letter, style = Noted.type.button.copy(fontFamily = Noted.type.pair.display, fontSize = 17.sp), color = Color(0xFF2A1A10))
        }
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
 * "current" (ink, with its count); the category switches once a drag of the track settles
 * there. With [small] it's the subcategory track under the title — same behaviour, smaller type.
 */
@Composable
private fun CategoryTrack(
    cats: List<Cat>,
    index: Int,
    count: (String) -> Int,
    small: Boolean = false,
    onSelect: (Int) -> Unit,
    onLongPress: (Int) -> Unit,
) {
    val c = Noted.colors
    val t = Noted.type
    val state = rememberLazyListState(initialFirstVisibleItemIndex = index)
    val fling = rememberSnapFlingBehavior(state, SnapPosition.Start)
    val nearest by remember { derivedStateOf { nearestItem(state) } }
    val tail = (LocalConfiguration.current.screenWidthDp * .6f).dp
    val currentIndex by rememberUpdatedState(index)
    val select by rememberUpdatedState(onSelect)
    val hold by rememberUpdatedState(onLongPress)

    // Only a drag of the track itself picks a category, and only once the whole gesture is over:
    // the drag hands over to its snap fling a frame later, and a drag the system takes away
    // (a mostly vertical swipe) stops without a fling — that one still lands where it shows.
    LaunchedEffect(state) {
        var touched = false
        launch { state.interactionSource.interactions.collect { if (it is DragInteraction.Start) touched = true } }
        snapshotFlow { state.isScrollInProgress }.collectLatest { busy ->
            if (busy || !touched) return@collectLatest
            withFrameNanos { }
            withFrameNanos { }
            if (state.isScrollInProgress) return@collectLatest
            touched = false
            val n = nearestItem(state)
            if (n != currentIndex) select(n) else state.alignTo(n)
        }
    }
    LaunchedEffect(index) { state.alignTo(index) }

    LazyRow(
        state = state,
        flingBehavior = fling,
        contentPadding = if (small) PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 4.dp)
        else PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(if (small) 20.dp else 26.dp),
    ) {
        itemsIndexed(cats, key = { _, it -> it.id }) { i, cat ->
            val on = i == nearest
            val col by animateColorAsState(if (on) c.ink else c.ink3, Motion.glide(), label = "cat")
            val show by animateFloatAsState(if (on) 1f else 0f, Motion.glide(), label = "count")
            Row(
                Modifier
                    .then(if (i == cats.lastIndex) Modifier.padding(end = tail) else Modifier)
                    .pointerInput(i) { detectTapGestures(onLongPress = { hold(i) }) { select(i) } },
                horizontalArrangement = Arrangement.spacedBy(if (small) 4.dp else 6.dp),
            ) {
                Text(cat.name, style = if (small) t.subCat else t.cat, color = col, maxLines = 1, softWrap = false)
                Text(
                    "${count(cat.id)}", style = t.label.copy(fontSize = if (small) 12.sp else 14.sp), color = c.accent,
                    modifier = Modifier
                        .padding(top = if (small) 4.dp else 10.dp)
                        .widthIn(min = if (small) 16.dp else 22.dp)
                        .graphicsLayer { alpha = show; translationY = (1f - show) * 4.dp.toPx() },
                )
            }
        }
    }
}

private fun nearestItem(state: LazyListState): Int =
    state.layoutInfo.visibleItemsInfo.minByOrNull { abs(it.offset) }?.index ?: state.firstVisibleItemIndex

/** Brings item [i] to the start edge (glides there if it's on screen). */
private suspend fun LazyListState.alignTo(i: Int) {
    if (isScrollInProgress) return
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == i }
    if (item == null) scrollToItem(i)
    else if (abs(item.offset) > 1) animateScrollBy(item.offset.toFloat(), Motion.glide())
}

/**
 * Subcategories of the current category, under the giant title: "הכל" (the category with all
 * of them), then each one. Absent — not even its height — for a category without any.
 */
@Composable
private fun SubTrack(tree: CatTree, top: String, current: String, count: (String) -> Int, onSelect: (String) -> Unit, onLongPress: (String) -> Unit) {
    AnimatedContent(
        targetState = top,
        transitionSpec = {
            (fadeIn(Motion.glide()) togetherWith fadeOut(Motion.glide()))
                .using(SizeTransform(clip = false) { _, _ -> Motion.glide() })
        },
        label = "subTrack",
    ) { t ->
        val kids = tree.children[t].orEmpty()
        if (kids.isEmpty()) Spacer(Modifier.fillMaxWidth().height(0.dp))
        else {
            val items = remember(kids, t) { listOf(Cat(t, "הכל")) + kids.map { Cat(it.id, it.name) } }
            val index = items.indexOfFirst { it.id == current }.coerceAtLeast(0)
            CategoryTrack(
                items, index, count, small = true,
                onSelect = { onSelect(items[it].id) }, onLongPress = { onLongPress(items[it].id) },
            )
        }
    }
}

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
