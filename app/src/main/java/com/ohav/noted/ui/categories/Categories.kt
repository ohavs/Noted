package com.ohav.noted.ui.categories

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
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
    fun create(all: List<CategoryEntity>, onCreated: (CategoryEntity) -> Unit = {}) {
        overlays.sheet.show("קטגוריה חדשה") {
            CategoryForm(
                name = "", icon = "folder", fresh = true,
                taken = all.map { it.name.trim() }.toSet(), confirm = "יצירה",
            ) { name, icon ->
                overlays.sheet.close()
                haptics.confirm()
                scope.launch {
                    val k = repo.createCategory(name, icon)
                    toasts.show("״${k.name}״ נוספה", CatIcons.of(icon))
                    onCreated(k)
                }
            }
        }
    }

    fun edit(k: CategoryEntity, all: List<CategoryEntity>, count: Int) {
        overlays.sheet.show("עריכת קטגוריה") {
            CategoryForm(
                name = k.name, icon = k.icon, fresh = false,
                taken = all.filter { it.id != k.id }.map { it.name.trim() }.toSet(), confirm = "שמירה",
            ) { name, icon ->
                overlays.sheet.close()
                haptics.light()
                scope.launch { repo.editCategory(k.id, name, icon) }
            }
            VSpace(6.dp)
            SheetRow(Ph.Trash, "מחיקת הקטגוריה", danger = true, last = true) { delete(k, all, count) }
        }
    }

    fun delete(k: CategoryEntity, all: List<CategoryEntity>, count: Int) {
        val others = all.filter { it.id != k.id }
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
                SheetRow(CatIcons.of(o.icon), "להעביר ל״${o.name}״") { overlays.sheet.close(); remove(k, o) }
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
            val msg = if (moveTo != null && r.noteIds.isNotEmpty()) "״${k.name}״ נמחקה, הפתקים ב״${moveTo.name}״" else "״${k.name}״ נמחקה"
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
 * Settings → categories: the order here is the order of the giant title on home. Drag a row by
 * its handle to reorder (rows make room on the glide spring), tap it to rename or change icon.
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

    // local order while dragging, dropped once the database has caught up
    var order by remember { mutableStateOf<List<String>?>(null) }
    var dragId by remember { mutableStateOf<String?>(null) }
    val dragDy = remember { mutableFloatStateOf(0f) }
    val settle = remember { Animatable(0f) }
    val rowH = with(density) { (RowHeight + RowGap).toPx() }
    val byId = cats.associateBy { it.id }
    val shown = order?.mapNotNull { byId[it] } ?: cats
    // the drag handlers outlive compositions: read the lists through these
    val shownNow by rememberUpdatedState(shown)
    val catsNow by rememberUpdatedState(cats)
    LaunchedEffect(cats) {
        val o = order ?: return@LaunchedEffect
        if (dragId == null && cats.map { it.id } == o) order = null
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
                    "הסדר כאן הוא הסדר בכותרת של הבית. גוררים בידית כדי לסדר, נוגעים כדי לשנות שם או אייקון.",
                    style = t.sub, color = c.ink2,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 14.dp),
                )
            }
        }
        items(shown, key = { it.id }) { k ->
            val dragging = dragId == k.id
            val up = animateFloatAsState(if (dragging) 1f else 0f, Motion.glide(), label = "catLift")
            CategoryRow(
                k, counts[k.id] ?: 0,
                modifier = Modifier
                    .then(if (dragging) Modifier else Modifier.animateItem(fadeInSpec = Motion.glide(), placementSpec = Motion.glide(IntOffset(1, 1)), fadeOutSpec = Motion.glide()))
                    .zIndex(if (dragging || up.value > .01f) 1f else 0f)
                    .graphicsLayer {
                        translationY = if (dragging) dragDy.floatValue else 0f
                        val s = 1f + .03f * up.value.coerceIn(0f, 1f)
                        scaleX = s; scaleY = s
                    }
                    .softShadow { ShadowFrame(Shadows.float(c.isDark).fade(up.value * .8f), 18.dp, c.surface) },
                onClick = { actions.edit(k, cats, counts[k.id] ?: 0) },
                handle = Modifier.pointerInput(k.id) {
                    detectDragGestures(
                        onDragStart = {
                            order = shownNow.map { it.id }
                            dragId = k.id
                            dragDy.floatValue = 0f
                            haptics.medium()
                        },
                        onDragEnd = {
                            val o = order
                            scope.launch {
                                settle.snapTo(dragDy.floatValue)
                                settle.animateTo(0f, Motion.glide()) { dragDy.floatValue = value }
                                dragId = null
                                haptics.light()
                                val now = catsNow
                                if (o != null && o != now.map { it.id }) app.notes.reorderCategories(o, now) else order = null
                            }
                        },
                        onDragCancel = { dragId = null; dragDy.floatValue = 0f; order = null },
                    ) { ch, d ->
                        ch.consume()
                        val o = order?.toMutableList() ?: return@detectDragGestures
                        dragDy.floatValue += d.y
                        val i = o.indexOf(k.id)
                        // swap once the row passes half of its neighbour; the finger keeps it in place
                        if (dragDy.floatValue > rowH / 2 && i < o.lastIndex) {
                            o.add(i + 1, o.removeAt(i)); order = o; dragDy.floatValue -= rowH; haptics.tick()
                        } else if (dragDy.floatValue < -rowH / 2 && i > 0) {
                            o.add(i - 1, o.removeAt(i)); order = o; dragDy.floatValue += rowH; haptics.tick()
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
private fun CategoryRow(k: CategoryEntity, count: Int, modifier: Modifier, onClick: () -> Unit, handle: Modifier) {
    val c = Noted.colors
    val t = Noted.type
    Row(
        Modifier
            .padding(horizontal = 16.dp)
            .then(modifier)
            .fillMaxWidth()
            .height(RowHeight)
            .clip(Shapes.md)
            .background(c.surface)
            .press(scale = .98f, onClick = onClick)
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).clip(Squircle(15.dp)).background(c.accentSoft), contentAlignment = Alignment.Center) {
            Icon(CatIcons.of(k.icon), null, Modifier.size(24.dp), tint = c.accent)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(k.name, style = t.body.copy(fontWeight = FontWeight.Bold), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when (count) { 0 -> "ריקה"; 1 -> "פתק אחד"; else -> "$count פתקים" },
                style = t.caption, color = c.ink2,
            )
        }
        // the handle owns its drag; the rest of the row is a tap target
        Box(Modifier.width(56.dp).fillMaxHeight().then(handle), contentAlignment = Alignment.Center) {
            Icon(Ph.DotsSixVertical, "גרירה לסידור", Modifier.size(22.dp), tint = c.ink3)
        }
    }
}
