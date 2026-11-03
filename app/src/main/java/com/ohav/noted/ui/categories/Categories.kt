package com.ohav.noted.ui.categories

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ohav.noted.NotedApp
import com.ohav.noted.data.CatTree
import com.ohav.noted.data.CategoryEntity
import com.ohav.noted.data.NotesRepository
import com.ohav.noted.design.Btn
import com.ohav.noted.design.BtnKind
import com.ohav.noted.design.Haptics
import com.ohav.noted.design.IconButton
import com.ohav.noted.design.Motion
import com.ohav.noted.design.Noted
import com.ohav.noted.design.Overlays
import com.ohav.noted.design.Ph
import com.ohav.noted.design.ShadowFrame
import com.ohav.noted.design.Shadows
import com.ohav.noted.design.SheetRow
import com.ohav.noted.design.Shapes
import com.ohav.noted.design.Squircle
import com.ohav.noted.design.ToastState
import com.ohav.noted.design.VSpace
import com.ohav.noted.design.fade
import com.ohav.noted.design.press
import com.ohav.noted.design.softShadow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** The icons a category can wear. Keys are stored in the database; never rename one. */
object CatIcons {
    val all: List<Pair<String, ImageVector>> by lazy {
        listOf(
            "folder" to Ph.FolderSimple, "home" to Ph.House, "work" to Ph.Briefcase, "ideas" to Ph.Lightbulb,
            "heart" to Ph.Heart, "trips" to Ph.MapTrifold, "cart" to Ph.ShoppingCart,
            "book" to Ph.BookOpen, "plane" to Ph.Airplane, "sport" to Ph.Barbell, "pets" to Ph.PawPrint,
            "kids" to Ph.Baby, "study" to Ph.GraduationCap, "code" to Ph.Code,
            "art" to Ph.Palette, "plant" to Ph.Plant, "coffee" to Ph.Coffee, "music" to Ph.MusicNotes,
            "film" to Ph.FilmSlate, "games" to Ph.GameController, "health" to Ph.FirstAid,
            "money" to Ph.Wallet, "gift" to Ph.Gift, "people" to Ph.Users, "car" to Ph.Car,
            "star" to Ph.Star, "tag" to Ph.Tag, "leaf" to Ph.Leaf,
        )
    }
    private val map by lazy { all.toMap() }
    fun of(key: String): ImageVector = map[key] ?: Ph.FolderSimple
}

/**
 * Create / edit / delete, as sheets and dialogs over whatever screen asked. Deleting is undoable
 * from its toast: the category comes back with every note it had, moved or trashed.
 */
class CategoryActions(
    private val repo: NotesRepository,
    private val overlays: Overlays,
    private val toasts: ToastState,
    private val haptics: Haptics,
    private val scope: CoroutineScope,
) {
    /** Names already used next to a category under [parent] (siblings may not repeat; cousins may). */
    private fun taken(all: List<CategoryEntity>, parent: String, except: String? = null): Set<String> {
        val tree = CatTree(all)
        return all.filter { it.id != except && (tree.parentOf[it.id] ?: "") == parent }.map { it.name.trim() }.toSet()
    }

    /** New category; with [parent], a subcategory under it. */
    fun create(all: List<CategoryEntity>, parent: String = "", onCreated: (CategoryEntity) -> Unit = {}) {
        val under = all.firstOrNull { it.id == parent }
        overlays.sheet.show(if (under == null) "קטגוריה חדשה" else "תת-קטגוריה ב״${under.name}״") {
            CategoryForm(
                name = "", icon = under?.icon ?: "folder", fresh = true,
                taken = taken(all, under?.id ?: ""), confirm = "יצירה",
            ) { name, icon ->
                overlays.sheet.close()
                haptics.confirm()
                scope.launch {
                    val k = repo.createCategory(name, icon, under?.id ?: "")
                    toasts.show(if (under == null) "״${k.name}״ נוספה" else "״${k.name}״ נוספה ל״${under.name}״", CatIcons.of(icon))
                    onCreated(k)
                }
            }
        }
    }

    fun edit(k: CategoryEntity, all: List<CategoryEntity>, count: Int) {
        val tree = CatTree(all)
        val parent = tree.parentOf[k.id]
        val homes = tree.tops.filter { it.id != k.id && it.id != parent }
        overlays.sheet.show("עריכת קטגוריה") {
            CategoryForm(
                name = k.name, icon = k.icon, fresh = false,
                taken = taken(all, parent ?: "", except = k.id), confirm = "שמירה",
            ) { name, icon ->
                overlays.sheet.close()
                haptics.light()
                scope.launch { repo.editCategory(k.id, name, icon) }
            }
            VSpace(6.dp)
            // a category with subcategories stays on top (one level only)
            if (!tree.hasChildren(k.id) && homes.isNotEmpty()) {
                SheetRow(Ph.FolderSimple, if (parent == null) "להכניס לתוך קטגוריה" else "להעביר לקטגוריה אחרת") { place(k, all) }
            }
            if (parent != null) SheetRow(Ph.ArrowUp, "להוציא לרמה הראשית") { overlays.sheet.close(); moveTo(k, null) }
            SheetRow(Ph.Trash, "מחיקת הקטגוריה", danger = true, last = true) { delete(k, all, count) }
        }
    }

    /** Pick the top-level category [k] goes under. */
    fun place(k: CategoryEntity, all: List<CategoryEntity>) {
        val tree = CatTree(all)
        val parent = tree.parentOf[k.id]
        val homes = tree.tops.filter { it.id != k.id && it.id != parent }
        overlays.sheet.show("להכניס את ״${k.name}״ לתוך…") {
            homes.forEachIndexed { i, h ->
                SheetRow(CatIcons.of(h.icon), h.name, last = i == homes.lastIndex) { overlays.sheet.close(); moveTo(k, h) }
            }
        }
    }

    private fun moveTo(k: CategoryEntity, home: CategoryEntity?) {
        haptics.confirm()
        scope.launch {
            repo.moveCategory(k.id, home?.id ?: "")
            toasts.show(
                if (home == null) "״${k.name}״ בקטגוריות הראשיות" else "״${k.name}״ עברה לתוך ״${home.name}״",
                CatIcons.of(k.icon), action = "ביטול", millis = 5000,
            ) { scope.launch { repo.placeCategory(k.id, k.parent, k.position) } }
        }
    }

    fun delete(k: CategoryEntity, all: List<CategoryEntity>, count: Int) {
        val tree = CatTree(all)
        val others = tree.flat.filter { it.id != k.id }
        if (others.isEmpty()) {
            overlays.sheet.close()
            haptics.reject()
            toasts.show("צריך להשאיר לפחות קטגוריה אחת", Ph.Info)
            return
        }
        if (count == 0) {
            overlays.sheet.close()
            scope.launch {
                val ok = overlays.dialog.confirm(
                    title = "למחוק את ״${k.name}״?", text = "הקטגוריה ריקה, אז שום פתק לא זז.",
                    icon = Ph.Trash, danger = true, confirm = "מחיקה", cancel = "השאר",
                )
                if (ok) remove(k, null)
            }
            return
        }
        val many = if (count == 1) "הפתק שבה" else "$count הפתקים שבה"
        overlays.sheet.show("מחיקת ״${k.name}״") {
            Text(
                "לאן ילך $many?", style = Noted.type.body, color = Noted.colors.ink2,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            others.forEach { o ->
                val inset = o.id in tree.parentOf
                SheetRow(CatIcons.of(o.icon), if (inset) o.name else "להעביר ל״${o.name}״", inset = inset) { overlays.sheet.close(); remove(k, o) }
            }
            SheetRow(Ph.Trash, if (count == 1) "לסל, יחד עם הקטגוריה" else "כולם לסל, יחד עם הקטגוריה", danger = true, last = true) {
                overlays.sheet.close()
                scope.launch {
                    val ok = overlays.dialog.confirm(
                        title = if (count == 1) "להעביר את הפתק לסל?" else "להעביר $count פתקים לסל?",
                        text = "אפשר לשחזר מהסל, או מיד עם ״ביטול״.",
                        icon = Ph.Trash, danger = true, confirm = "מחיקה", cancel = "השאר",
                    )
                    if (ok) remove(k, null)
                }
            }
        }
    }

    private fun remove(k: CategoryEntity, moveTo: CategoryEntity?) {
        haptics.confirm()
        scope.launch {
            val r = repo.deleteCategory(k, moveTo?.id)
            val msg = when {
                moveTo != null && r.noteIds.isNotEmpty() -> "״${k.name}״ נמחקה, הפתקים ב״${moveTo.name}״"
                r.children.isNotEmpty() -> "״${k.name}״ נמחקה, תתי-הקטגוריות עלו לרמה הראשית"
                else -> "״${k.name}״ נמחקה"
            }
            toasts.show(msg, Ph.Trash, action = "ביטול", millis = 5000) { scope.launch { repo.undoDeleteCategory(r) } }
        }
    }
}

@Composable
fun rememberCategoryActions(app: NotedApp, overlays: Overlays, toasts: ToastState): CategoryActions {
    val haptics = Noted.haptics
    // app.scope: a deletion or its undo must finish even if the screen that asked goes away
    return remember(overlays, toasts, haptics) { CategoryActions(app.notes, overlays, toasts, haptics, app.scope) }
}

/** Name + icon. The preview tile wears the chosen icon; a duplicate name is pointed out, not refused silently. */
@Composable
private fun ColumnScope.CategoryForm(
    name: String,
    icon: String,
    fresh: Boolean,
    taken: Set<String>,
    confirm: String,
    onSave: (String, String) -> Unit,
) {
    val c = Noted.colors
    val t = Noted.type
    val haptics = Noted.haptics
    var text by remember { mutableStateOf(name) }
    var pick by remember { mutableStateOf(icon) }
    val focus = remember { FocusRequester() }
    val fm = LocalFocusManager.current
    val clean = text.trim()
    val dup = clean.isNotEmpty() && clean in taken
    val ok = clean.isNotEmpty() && !dup
    fun save() {
        if (!ok) { haptics.reject(); return }
        fm.clearFocus()
        onSave(clean, pick)
    }
    LaunchedEffect(Unit) {
        if (fresh) { delay(280); runCatching { focus.requestFocus() } }
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.size(58.dp).clip(Shapes.md).background(c.accentSoft),
            contentAlignment = Alignment.Center,
        ) { Icon(CatIcons.of(pick), null, Modifier.size(28.dp), tint = c.accent) }
        val ring by animateColorAsState(if (dup) c.danger else c.line, Motion.glide(), label = "catRing")
        Box(
            Modifier
                .weight(1f)
                .height(58.dp)
                .clip(Shapes.md)
                .background(c.bg)
                .drawBehind {
                    val o = Shapes.md.createOutline(size, layoutDirection, this)
                    drawOutline(o, ring, style = Stroke(3.dp.toPx()))
                }
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (text.isEmpty()) Text("שם הקטגוריה", style = t.field.copy(fontWeight = FontWeight.SemiBold), color = c.ink3)
            BasicTextField(
                value = text, onValueChange = { if (it.length <= 24) text = it }, singleLine = true,
                textStyle = t.field.copy(color = c.ink, fontWeight = FontWeight.SemiBold),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
    }
    val warn by animateFloatAsState(if (dup) 1f else 0f, Motion.glide(), label = "dup")
    Box(Modifier.fillMaxWidth().height(26.dp * warn)) {
        Text(
            "כבר יש קטגוריה בשם הזה", style = t.caption, color = c.danger,
            modifier = Modifier.padding(start = 72.dp, top = 6.dp).graphicsLayer { alpha = warn },
        )
    }
    VSpace(14.dp)
    Text("אייקון", style = t.section, color = c.ink2, modifier = Modifier.padding(bottom = 10.dp))
    IconGrid(pick) { pick = it; haptics.tick() }
    VSpace(18.dp)
    val a by animateFloatAsState(if (ok) 1f else .4f, Motion.glide(), label = "catOk")
    Btn(confirm, Modifier.fillMaxWidth().graphicsLayer { alpha = a }, icon = Ph.Check, kind = BtnKind.Dark) { save() }
    VSpace(8.dp)
}

@Composable
private fun IconGrid(selected: String, onPick: (String) -> Unit) {
    val c = Noted.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CatIcons.all.chunked(7).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (key, icon) ->
                    val on = key == selected
                    val bg by animateColorAsState(if (on) c.ink else c.bg, Motion.glide(), label = "iconBg")
                    val fg by animateColorAsState(if (on) c.bg else c.ink2, Motion.glide(), label = "iconFg")
                    val pop by animateFloatAsState(if (on) 1f else 0f, Motion.touch(), label = "iconPop")
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .press(scale = .86f, haptic = false) { onPick(key) }
                            .clip(Squircle(14.dp))
                            .background(bg),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            icon, null,
                            Modifier.size(22.dp).graphicsLayer { val k = 1f + .12f * pop; scaleX = k; scaleY = k },
                            tint = fg,
                        )
                    }
                }
                repeat(7 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * Settings → categories: the order here is the order of the giant title on home, subcategories
 * inset under their category. Drag a row by its handle to reorder (rows make room on the glide
 * spring); hold it over another category to drop it inside. Tap a row to rename / change icon;
 * + on a top-level row adds a subcategory.
 */
@Composable
fun CategoriesScreen(app: NotedApp, toasts: ToastState, overlays: Overlays, onBack: () -> Unit) {
    val c = Noted.colors
    val t = Noted.type
    val haptics = Noted.haptics
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val cats by app.notes.categories.collectAsStateWithLifecycle(initialValue = emptyList())
    val notes by app.notes.notes.collectAsStateWithLifecycle(initialValue = emptyList())
    val actions = rememberCategoryActions(app, overlays, toasts)
    val counts = remember(notes) { notes.groupingBy { it.categoryId }.eachCount() }
    val tree = remember(cats) { CatTree(cats) }
    val rows = remember(tree) { tree.flat.map { it.id to (tree.parentOf[it.id] ?: "") } }
    val parents = remember(rows) { rows.toMap() }

    // while dragging: local order (the dragged parent's children folded into it) and the top it
    // hovers over; after the drop: the arrangement, until the database has caught up
    var order by remember { mutableStateOf<List<String>?>(null) }
    var pending by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var dragId by remember { mutableStateOf<String?>(null) }
    var into by remember { mutableStateOf<String?>(null) }
    var hidden by remember { mutableStateOf<List<String>>(emptyList()) }
    val dragDy = remember { mutableFloatStateOf(0f) }
    val settle = remember { Animatable(0f) }
    val rowH = with(density) { (RowHeight + RowGap).toPx() }
    val byId = cats.associateBy { it.id }
    val shownIds = order ?: pending?.map { it.first } ?: rows.map { it.first }
    val shown = shownIds.mapNotNull { byId[it] }
    fun nested(id: String): Boolean {
        val o = order
        if (id == dragId && o != null) return into != null || CatTree.predict(o, id, parents, hidden.isNotEmpty()).isNotEmpty()
        return (pending?.firstOrNull { it.first == id }?.second ?: parents[id]).orEmpty().isNotEmpty()
    }
    // the drag handlers outlive compositions: read the lists through these
    val shownNow by rememberUpdatedState(shownIds)
    val catsNow by rememberUpdatedState(cats)
    val rowsNow by rememberUpdatedState(rows)
    val parentsNow by rememberUpdatedState(parents)
    val treeNow by rememberUpdatedState(tree)
    LaunchedEffect(rows) {
        if (pending == rows) pending = null
    }
    // a write that never shows up (or was merged differently by sync) must not pin the list
    LaunchedEffect(pending) {
        if (pending != null) { delay(2500); pending = null }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 140.dp),
        verticalArrangement = Arrangement.spacedBy(RowGap),
    ) {
        item(key = "head") {
            Column(Modifier.statusBarsPadding()) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(Ph.CaretStart, "חזרה", onClick = onBack)
                }
                Text("קטגוריות", style = t.hero, color = c.ink, modifier = Modifier.padding(horizontal = 20.dp))
                Text(
                    "הסדר כאן הוא הסדר בכותרת של הבית. גוררים בידית כדי לסדר, או משהים מעל קטגוריה כדי להכניס לתוכה. + מוסיף תת-קטגוריה.",
                    style = t.sub, color = c.ink2,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 14.dp),
                )
            }
        }
        items(shown, key = { it.id }) { k ->
            val dragging = dragId == k.id
            val up = animateFloatAsState(if (dragging) 1f else 0f, Motion.glide(), label = "catLift")
            // over a drop target the dragged row tucks in a little
            val tuck by animateFloatAsState(if (dragging && into != null) 1f else 0f, Motion.touch(), label = "catTuck")
            val sub = nested(k.id)
            val isTop = !sub && !dragging
            val kids = if (dragging) hidden.size else treeNow.children[k.id]?.size ?: 0
            val total = (counts[k.id] ?: 0) + (treeNow.children[k.id]?.sumOf { counts[it.id] ?: 0 } ?: 0)
            CategoryRow(
                k, total, kids, sub = sub, target = into == k.id,
                modifier = Modifier
                    .then(if (dragging) Modifier else Modifier.animateItem(fadeInSpec = Motion.glide(), placementSpec = Motion.glide(IntOffset(1, 1)), fadeOutSpec = Motion.glide()))
                    .zIndex(if (dragging || up.value > .01f) 1f else 0f)
                    .graphicsLayer {
                        translationY = if (dragging) dragDy.floatValue else 0f
                        val s = 1f + .03f * up.value.coerceIn(0f, 1f) - .06f * tuck
                        scaleX = s; scaleY = s
                    }
                    .softShadow { ShadowFrame(Shadows.float(c.isDark).fade(up.value * .8f), 18.dp, c.surface) },
                onClick = { actions.edit(k, cats, counts[k.id] ?: 0) },
                onAdd = if (isTop && tree.isTop(k.id)) ({ actions.create(cats, parent = k.id) }) else null,
                handle = Modifier.pointerInput(k.id) {
                    detectDragGestures(
                        onDragStart = {
                            val folded = treeNow.children[k.id].orEmpty().map { it.id }
                            hidden = folded
                            order = shownNow.filter { it !in folded }
                            pending = null
                            into = null
                            dragId = k.id
                            dragDy.floatValue = 0f
                            haptics.medium()
                        },
                        onDragEnd = {
                            val o = order
                            val target = into
                            val result = o?.let { CatTree.arrange(it, k.id, target, parentsNow, hidden) }
                            scope.launch {
                                settle.snapTo(dragDy.floatValue)
                                settle.animateTo(0f, Motion.glide()) { dragDy.floatValue = value }
                                if (result != null && result != rowsNow) {
                                    pending = result
                                    app.notes.arrangeCategories(result, catsNow)
                                }
                                if (target != null) haptics.confirm() else haptics.light()
                                order = null
                                into = null
                                hidden = emptyList()
                                dragId = null
                            }
                        },
                        onDragCancel = { dragId = null; dragDy.floatValue = 0f; order = null; into = null; hidden = emptyList() },
                    ) { ch, d ->
                        ch.consume()
                        val o = order?.toMutableList() ?: return@detectDragGestures
                        dragDy.floatValue += d.y
                        val i = o.indexOf(k.id)
                        val f = dragDy.floatValue / rowH
                        val next = when {
                            f > 0f -> o.getOrNull(i + 1)
                            f < 0f -> o.getOrNull(i - 1)
                            else -> null
                        }
                        // a top-level neighbour can take the row in: hovering over its middle aims there,
                        // so the swap waits a bit longer; a category with children never nests
                        val nestable = next != null && hidden.isEmpty() && parentsNow[next].isNullOrEmpty()
                        val aim = if (nestable && abs(f) >= .35f && abs(f) < .7f) next else null
                        if (aim != into) { into = aim; if (aim != null) haptics.tick() }
                        val swapAt = if (nestable) .7f else .5f
                        if (f > swapAt && i < o.lastIndex) {
                            o.add(i + 1, o.removeAt(i)); order = o; dragDy.floatValue -= rowH; into = null; haptics.tick()
                        } else if (f < -swapAt && i > 0) {
                            o.add(i - 1, o.removeAt(i)); order = o; dragDy.floatValue += rowH; into = null; haptics.tick()
                        }
                    }
                },
            )
        }
        item(key = "add") {
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 10.dp)) {
                Btn("קטגוריה חדשה", Modifier.fillMaxWidth(), icon = Ph.FolderSimplePlus, kind = BtnKind.Dark) { actions.create(cats) }
            }
        }
        item(key = "foot") { Spacer(Modifier.navigationBarsPadding()) }
    }
}

private val RowHeight = 72.dp
private val RowGap = 8.dp

@Composable
private fun CategoryRow(
    k: CategoryEntity,
    count: Int,
    kids: Int,
    sub: Boolean,
    target: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    onAdd: (() -> Unit)?,
    handle: Modifier,
) {
    val c = Noted.colors
    val t = Noted.type
    val inset by animateDpAsState(if (sub) 30.dp else 0.dp, Motion.glide(), label = "catInset")
    val hot by animateFloatAsState(if (target) 1f else 0f, Motion.glide(), label = "catHot")
    val bg by animateColorAsState(if (target) c.accentSoft else c.surface, Motion.glide(), label = "catBg")
    Row(
        Modifier
            .padding(horizontal = 16.dp)
            .padding(start = inset)
            .then(modifier)
            .fillMaxWidth()
            .height(RowHeight)
            .clip(Shapes.md)
            .background(bg)
            .drawBehind {
                if (hot > .01f) drawOutline(Shapes.md.createOutline(size, layoutDirection, this), c.accent.copy(alpha = hot), style = Stroke(2.5.dp.toPx()))
            }
            .press(scale = .98f, onClick = onClick)
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tile = if (sub) 40.dp else 48.dp
        Box(Modifier.size(tile).clip(Squircle(if (sub) 13.dp else 15.dp)).background(if (sub) c.bg else c.accentSoft), contentAlignment = Alignment.Center) {
            Icon(CatIcons.of(k.icon), null, Modifier.size(if (sub) 20.dp else 24.dp), tint = c.accent)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(k.name, style = t.body.copy(fontWeight = if (sub) FontWeight.SemiBold else FontWeight.Bold), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val notes = when (count) { 0 -> "ריקה"; 1 -> "פתק אחד"; else -> "$count פתקים" }
            val subs = when (kids) { 0 -> null; 1 -> "תת-קטגוריה אחת"; else -> "$kids תתי-קטגוריות" }
            Text(listOfNotNull(notes, subs).joinToString(" · "), style = t.caption, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onAdd != null) {
            Box(
                Modifier.size(44.dp).clip(Squircle(14.dp)).press(scale = .86f, onClick = onAdd),
                contentAlignment = Alignment.Center,
            ) { Icon(Ph.Plus, "תת-קטגוריה חדשה", Modifier.size(20.dp), tint = c.ink2) }
        }
        // the handle owns its drag; the rest of the row is a tap target
        Box(Modifier.width(52.dp).fillMaxHeight().then(handle), contentAlignment = Alignment.Center) {
            Icon(Ph.DotsSixVertical, "גרירה לסידור", Modifier.size(22.dp), tint = c.ink3)
        }
    }
}
