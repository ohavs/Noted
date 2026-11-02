package com.ohav.noted.ui.places

import android.content.Intent
import android.location.Location
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ohav.noted.NotedApp
import com.ohav.noted.data.Blocks
import com.ohav.noted.data.NoteEntity
import com.ohav.noted.data.PlaceEntity
import com.ohav.noted.data.PlaceKind
import com.ohav.noted.data.PlaceReminderEntity
import com.ohav.noted.design.Btn
import com.ohav.noted.design.BtnKind
import com.ohav.noted.design.LocalOverlays
import com.ohav.noted.design.Motion
import com.ohav.noted.design.Noted
import com.ohav.noted.design.Overlays
import com.ohav.noted.design.Ph
import com.ohav.noted.design.SheetRow
import com.ohav.noted.design.Shapes
import com.ohav.noted.design.ToastState
import com.ohav.noted.design.VSpace
import com.ohav.noted.design.press
import com.ohav.noted.places.Found
import com.ohav.noted.places.Geo
import com.ohav.noted.ui.common.EmptyArt
import com.ohav.noted.ui.common.EmptyState
import com.ohav.noted.ui.reminders.PlaceWords
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The places tab: a radar of saved places round where you stand (north up), under a catalog
 * sheet that drags between half and full height. Tap a place for its card: the notes waiting
 * there, edit, open in a maps app, delete with undo.
 */
@Composable
fun PlacesScreen(app: NotedApp, toasts: ToastState, notes: List<NoteEntity>, onOpenNote: (NoteEntity) -> Unit) {
    val ctx = LocalContext.current
    val c = Noted.colors
    val t = Noted.type
    val overlays = LocalOverlays.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val places by app.places.places.collectAsStateWithLifecycle(initialValue = null)
    val reminders by app.places.reminders.collectAsStateWithLifecycle(initialValue = emptyList())
    var me by remember { mutableStateOf<Location?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    LaunchedEffect(tick) {
        me = Geo.last(ctx) ?: me
        if (Geo.hasFine(ctx) || Geo.hasCoarse(ctx)) Geo.current(ctx)?.let { me = it }
    }
    val list = places ?: return
    val open = reminders.filter { !it.fired }
    val actions = remember(app, overlays, toasts, scope) { PlaceActions(app, overlays, toasts, scope) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val h = with(density) { maxHeight.toPx() }
        val statusTop = WindowInsets.statusBars.getTop(density).toFloat()
        val fullTop = statusTop + with(density) { 12.dp.toPx() }
        val peekTop = h * .52f
        val top = remember { Animatable(peekTop) }
        // 0 at peek → 1 fully open; the radar recedes as the sheet rises
        fun openness() = ((peekTop - top.value) / (peekTop - fullTop)).coerceIn(0f, 1f)

        // ---- radar + title
        Column(
            Modifier.fillMaxSize().graphicsLayer {
                val o = openness()
                alpha = 1f - o * .85f
                translationY = -o * 40.dp.toPx()
            },
        ) {
            Box(Modifier.statusBarsPadding().padding(top = 14.dp).height(64.dp))
            Text("מקומות", style = t.cat, color = c.ink, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 4.dp))
            Radar(
                list, open, me,
                Modifier.fillMaxWidth().height(with(density) { (peekTop - statusTop).toDp() } - 150.dp),
            ) { p -> actions.card(p, notes, reminders, me, onOpenNote) }
        }

        // ---- catalog sheet
        fun settle(v: Float) = scope.launch {
            val mid = (fullTop + peekTop) / 2
            val target = when {
                v < -900f -> fullTop
                v > 900f -> peekTop
                top.value < mid -> fullTop
                else -> peekTop
            }
            top.animateTo(target, Motion.glide(), initialVelocity = v)
        }
        val listState = rememberLazyListState()
        val nested = remember(peekTop, fullTop) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    // pushing up: the sheet rises before the list scrolls
                    if (available.y < 0 && top.value > fullTop) {
                        val next = (top.value + available.y).coerceAtLeast(fullTop)
                        val used = next - top.value
                        scope.launch { top.snapTo(next) }
                        return Offset(0f, used)
                    }
                    return Offset.Zero
                }

                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    // pulling down past the list's top: the sheet follows
                    if (available.y > 0 && source == NestedScrollSource.UserInput) {
                        val next = (top.value + available.y).coerceAtMost(peekTop)
                        val used = next - top.value
                        scope.launch { top.snapTo(next) }
                        return Offset(0f, used)
                    }
                    return Offset.Zero
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (top.value > fullTop + 1f && top.value < peekTop - 1f) {
                        settle(available.y)
                        return available
                    }
                    return Velocity.Zero
                }
            }
        }
        val drag = rememberDraggableState { dy -> scope.launch { top.snapTo((top.value + dy).coerceIn(fullTop, peekTop)) } }

        Column(
            Modifier
                .offset { IntOffset(0, top.value.roundToInt()) }
                .fillMaxWidth()
                .height(with(density) { (h - fullTop).toDp() })
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(c.surface),
        ) {
            Column(
                Modifier.fillMaxWidth().draggable(drag, Orientation.Vertical, onDragStopped = { v -> settle(v) }),
            ) {
                Box(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(42.dp, 5.dp).clip(RoundedCornerShape(3.dp)).background(c.ink.copy(alpha = .18f)))
                }
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 6.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("הקטלוג", style = t.barTitle, color = c.ink)
                    if (list.isNotEmpty()) Text("  ${list.size}", style = t.barTitle, color = c.ink3)
                    Spacer(Modifier.weight(1f))
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f).nestedScroll(nested),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 150.dp),
            ) {
                item(key = "add") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AddTile(Ph.Crosshair, "המיקום הנוכחי", "שמירה בלחיצה", dark = true, Modifier.weight(1f)) { actions.add(list, autoLocate = true) }
                        AddTile(Ph.MagnifyingGlass, "חיפוש", "כתובת או קישור", dark = false, Modifier.weight(1f)) { actions.add(list, autoLocate = false) }
                    }
                    VSpace(14.dp)
                }
                item(key = "status") { StatusLine(app, open.size, tick) { actions.permissions() } }
                if (list.isEmpty()) {
                    item(key = "empty") {
                        EmptyState(EmptyArt.Places, "המקומות שלך", "שמרו בית, עבודה, סופר — ופתקים יקפצו כשתגיעו.", Modifier.padding(top = 10.dp))
                    }
                }
                val groups = list.groupBy { PlaceKind.of(it.kind) }.toSortedMap(compareBy { it.ordinal })
                groups.forEach { (kind, ps) ->
                    item(key = "h:${kind.key}") {
                        Text(kind.label, style = t.section, color = c.ink2, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp, start = 2.dp))
                    }
                    items(ps, key = { it.id }) { p ->
                        PlaceRow(p, me, open.count { it.placeId == p.id }) { actions.card(p, notes, reminders, me, onOpenNote) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddTile(icon: androidx.compose.ui.graphics.vector.ImageVector, name: String, sub: String, dark: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Noted.colors
    val t = Noted.type
    Row(
        modifier.press(scale = .95f, onClick = onClick).clip(Shapes.md).background(if (dark) c.inverse else c.bg).padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = if (dark) c.inverseInk else c.ink)
        Column {
            Text(name, style = t.label, color = if (dark) c.inverseInk else c.ink, maxLines = 1)
            Text(sub, style = t.caption, color = if (dark) c.inverseInk2 else c.ink2, maxLines = 1)
        }
    }
}

/** Says plainly when place reminders can't ring, and what the fences last did when they can. */
@Composable
private fun StatusLine(app: NotedApp, open: Int, tick: Int, onFix: () -> Unit) {
    val ctx = LocalContext.current
    val c = Noted.colors
    val t = Noted.type
    if (open == 0) return
    val ready = remember(tick) { placesReady(ctx) }
    val st = app.places.status
    val result = remember(tick, open) { st.result }
    if (!ready || result.startsWith("err")) {
        val why = when {
            !Geo.hasFine(ctx) -> "אין הרשאת מיקום מדויק"
            !Geo.hasBackground(ctx) -> "חסר ״לאפשר כל הזמן״ בהרשאת המיקום"
            !Geo.locationOn(ctx) -> "המיקום בטלפון כבוי"
            result == "err:1000" -> "״דיוק המיקום של Google״ כבוי בהגדרות המיקום"
            result.startsWith("err") -> "הטלפון סירב לרשום את הגדרות (${result.removePrefix("err:")})"
            else -> "אין הרשאת התראות"
        }
        Row(
            Modifier.fillMaxWidth().padding(bottom = 6.dp).press(scale = .97f, onClick = onFix).clip(Shapes.md).background(c.dangerSoft).padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Ph.WarningCircle, null, Modifier.size(24.dp), tint = c.danger)
            Column(Modifier.weight(1f)) {
                Text("תזכורות מקום לא יקפצו", style = t.label, color = c.ink)
                Text(why, style = t.caption, color = c.ink2)
            }
            Text("לתקן", style = t.label, color = c.danger)
        }
    } else {
        val cross = remember(tick, open) { st.lastCross }
        val n = remember(tick, open) { st.count }
        Text(
            listOf(if (n > 0) "$n גדרות פעילות" else "הגדרות נרשמות…", cross.ifEmpty { null }?.let { "אחרון: $it" }).filterNotNull().joinToString(" · "),
            style = t.caption, color = c.ink3, maxLines = 2, modifier = Modifier.padding(start = 2.dp, bottom = 6.dp),
        )
    }
}

@Composable
private fun PlaceRow(p: PlaceEntity, me: Location?, count: Int, onClick: () -> Unit) {
    val c = Noted.colors
    val t = Noted.type
    val dist = me?.let { Geo.fmtDistance(Geo.distance(it.latitude, it.longitude, p.lat, p.lng)) }
    Row(
        Modifier.fillMaxWidth().press(scale = .97f, haptic = false, onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PlaceBadge(PlaceKind.of(p.kind), 46.dp)
        Column(Modifier.weight(1f)) {
            Text(p.name, style = t.body.copy(fontWeight = FontWeight.SemiBold), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(dist, p.address.ifEmpty { null }).joinToString(" · "),
                style = t.caption, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (count > 0) {
            Row(
                Modifier.clip(Shapes.sm).background(c.accentSoft).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(Ph.BellRinging, null, Modifier.size(15.dp), tint = c.accent)
                Text("$count", style = t.label, color = c.accent)
            }
        }
    }
}

// ------------------------------------------------------------------ radar

/**
 * Saved places round you, north up, on a log scale so the corner shop and the office both fit.
 * Without a fix the centre is the places' own middle. Drawn in absolute coordinates — east is
 * right whatever the layout direction.
 */
@Composable
private fun Radar(places: List<PlaceEntity>, open: List<PlaceReminderEntity>, me: Location?, modifier: Modifier, onPlace: (PlaceEntity) -> Unit) {
    val c = Noted.colors
    val t = Noted.type
    val pulse = rememberInfiniteTransition(label = "radar")
    val pv by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "pulse")
    val center = me?.let { it.latitude to it.longitude }
        ?: places.takeIf { it.isNotEmpty() }?.let { ps -> ps.map { it.lat }.average() to ps.map { it.lng }.average() }
    val far = remember(places, center) {
        if (center == null) 1000f else (places.maxOfOrNull { Geo.distance(center.first, center.second, it.lat, it.lng) } ?: 1000f).coerceAtLeast(800f)
    }
    val k = 250f
    fun scale(d: Float) = (ln(1f + d / k) / ln(1f + far / k)).coerceIn(0f, 1f)
    val withNotes = remember(open) { open.map { it.placeId }.toSet() }

    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2
            val cy = size.height / 2
            val r = minOf(cx, cy) - 26.dp.toPx()
            listOf(.34f, .67f, 1f).forEach { f ->
                drawCircle(c.line, radius = r * f, center = Offset(cx, cy), style = Stroke(1.5.dp.toPx()))
            }
            if (me != null) {
                // the pulse: one ring leaving the dot, fading as it grows (draw-phase only)
                drawCircle(c.accent.copy(alpha = (1f - pv) * .35f), radius = 8.dp.toPx() + pv * 46.dp.toPx(), center = Offset(cx, cy))
                drawCircle(c.surface, radius = 9.dp.toPx(), center = Offset(cx, cy))
                drawCircle(c.accent, radius = 6.5.dp.toPx(), center = Offset(cx, cy))
            }
        }
        // ring labels at the top of each ring (distance it stands for)
        Layout(
            content = {
                listOf(.34f, .67f, 1f).forEach { f ->
                    val d = k * (Math.exp((f * ln(1f + far / k)).toDouble()).toFloat() - 1f)
                    Text(Geo.fmtDistance(d), style = t.tiny, color = c.ink3)
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) { ms, cs ->
            val ps = ms.map { it.measure(cs.copy(minWidth = 0, minHeight = 0)) }
            layout(cs.maxWidth, cs.maxHeight) {
                val cx = cs.maxWidth / 2
                val cy = cs.maxHeight / 2
                val r = minOf(cx, cy) - 26.dp.roundToPx()
                listOf(.34f, .67f, 1f).forEachIndexed { i, f ->
                    ps[i].place(cx - ps[i].width / 2, cy - (r * f).roundToInt() - ps[i].height - 2)
                }
            }
        }
        if (center != null) {
            Layout(
                content = { places.forEach { p -> Pin(p, p.id in withNotes) { onPlace(p) } } },
                modifier = Modifier.fillMaxSize(),
            ) { ms, cs ->
                val ps = ms.map { it.measure(cs.copy(minWidth = 0, minHeight = 0)) }
                layout(cs.maxWidth, cs.maxHeight) {
                    val cx = cs.maxWidth / 2f
                    val cy = cs.maxHeight / 2f
                    val r = minOf(cx, cy) - 26.dp.toPx()
                    places.forEachIndexed { i, p ->
                        val d = Geo.distance(center.first, center.second, p.lat, p.lng)
                        val b = Math.toRadians(Geo.bearing(center.first, center.second, p.lat, p.lng).toDouble())
                        val rr = r * scale(d)
                        val x = cx + (sin(b) * rr).toFloat()
                        val y = cy - (cos(b) * rr).toFloat()
                        // absolute place(): north-up east-right, never mirrored
                        ps[i].place((x - ps[i].width / 2f).roundToInt(), (y - 18.dp.toPx()).roundToInt())
                    }
                }
            }
        }
        if (me == null && places.isNotEmpty()) {
            Text(
                "בלי מיקום — המקומות סביב המרכז שלהם", style = t.caption, color = c.ink3, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp),
            )
        }
    }
}

@Composable
private fun Pin(p: PlaceEntity, hasNotes: Boolean, onClick: () -> Unit) {
    val c = Noted.colors
    Column(Modifier.width(76.dp).press(scale = .9f, onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            if (hasNotes) Box(Modifier.size(44.dp).clip(CircleShape).background(c.accent.copy(alpha = .22f)))
            PlaceBadge(PlaceKind.of(p.kind), 36.dp, on = hasNotes)
        }
        Text(p.name, style = Noted.type.tiny, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
    }
}

// ------------------------------------------------------------------ sheets

/** Adding, the place card, editing and deleting — as sheets over the places tab. */
private class PlaceActions(
    private val app: NotedApp,
    private val overlays: Overlays,
    private val toasts: ToastState,
    private val scope: CoroutineScope,
) {
    fun permissions() {
        overlays.sheet.show("") { PlacePermissionCard(saved = false) { overlays.sheet.close() } }
    }

    fun add(all: List<PlaceEntity>, autoLocate: Boolean) {
        val found = mutableStateOf<Found?>(null)
        overlays.sheet.show(if (autoLocate) "המיקום הנוכחי" else "מקום חדש") {
            val f = found.value
            AnimatedContent(
                f,
                transitionSpec = { (fadeIn(Motion.glide()) togetherWith fadeOut(Motion.glide())).using(SizeTransform { _, _ -> Motion.glide() }) },
                label = "addStep",
            ) { ff ->
                Column {
                    if (ff == null) {
                        PlaceFinder(autoLocate = autoLocate) { hit ->
                            val same = existingAt(all, hit.lat, hit.lng)
                            if (same != null) {
                                overlays.sheet.close()
                                toasts.show("כבר שמור כ״${same.name}״", PlaceKind.of(same.kind).icon())
                            } else {
                                overlays.sheet.setTitle("מקום חדש")
                                found.value = hit
                            }
                        }
                    } else {
                        val kind = remember(ff) {
                            guessKind(ff.name, all).let { k ->
                                if (k == PlaceKind.Other && autoLocate && all.none { it.kind == PlaceKind.Home.key }) PlaceKind.Home else k
                            }
                        }
                        PlaceForm(
                            name = if (kind == PlaceKind.Home) kind.label else ff.name,
                            kind = kind, radius = null, address = ff.address, confirm = "שמירת המקום",
                        ) { name, k, r ->
                            overlays.sheet.close()
                            scope.launch {
                                app.places.create(name, k.key, ff.lat, ff.lng, r, ff.address)
                                toasts.show("״$name״ נשמר", k.icon())
                            }
                        }
                    }
                }
            }
        }
    }

    fun card(p: PlaceEntity, notes: List<NoteEntity>, all: List<PlaceReminderEntity>, me: Location?, onOpenNote: (NoteEntity) -> Unit) {
        val ctx = app
        val here = all.filter { it.placeId == p.id }
        val byId = notes.associateBy { it.id }
        overlays.sheet.show(p.name) {
            val c = Noted.colors
            val t = Noted.type
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                PlaceBadge(PlaceKind.of(p.kind), 54.dp)
                Column(Modifier.weight(1f)) {
                    if (p.address.isNotEmpty()) Text(p.address, style = t.body, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(
                            PlaceKind.of(p.kind).label,
                            "רדיוס ${Geo.fmtRadius(p.radius)}",
                            me?.let { Geo.fmtDistance(Geo.distance(it.latitude, it.longitude, p.lat, p.lng)) + " ממך" },
                        ).joinToString(" · "),
                        style = t.caption, color = c.ink3,
                    )
                }
            }
            VSpace(20.dp)
            Text("פתקים כאן", style = t.section, color = c.ink2, modifier = Modifier.padding(bottom = 6.dp))
            val rows = here.mapNotNull { r -> byId[r.noteId]?.takeIf { it.trashedAt == null }?.let { r to it } }
            if (rows.isEmpty()) {
                Text("עוד אין פתקים שמחכים כאן. בתוך פתק: פעמון ← מקום.", style = t.caption, color = c.ink3, modifier = Modifier.padding(vertical = 8.dp))
            } else rows.forEachIndexed { i, (r, n) -> NoteAtPlace(r, n, last = i == rows.lastIndex) { overlays.sheet.close(); onOpenNote(n) } }
            VSpace(16.dp)
            SheetRow(Ph.PencilSimple, "עריכה") { edit(p) }
            SheetRow(Ph.MapTrifold, "פתיחה במפות") {
                runCatching {
                    val q = Uri.encode(p.name)
                    ctx.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("geo:${p.lat},${p.lng}?q=${p.lat},${p.lng}($q)")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
            SheetRow(Ph.Trash, "מחיקה", danger = true, last = true) { delete(p, rows.size) }
        }
    }

    private fun edit(p: PlaceEntity) {
        overlays.sheet.show("עריכת מקום") {
            PlaceForm(name = p.name, kind = PlaceKind.of(p.kind), radius = p.radius, address = p.address, confirm = "שמירה") { name, k, r ->
                overlays.sheet.close()
                scope.launch { app.places.update(p.copy(name = name, kind = k.key, radius = r)) }
            }
        }
    }

    private fun delete(p: PlaceEntity, notes: Int) {
        scope.launch {
            if (notes > 0) {
                val ok = overlays.dialog.confirm(
                    "למחוק את ״${p.name}״?",
                    if (notes == 1) "תזכורת המקום של פתק אחד תימחק איתו." else "תזכורות המקום של $notes פתקים יימחקו איתו.",
                    Ph.Trash, danger = true, confirm = "מחיקה",
                )
                if (!ok) return@launch
            }
            overlays.sheet.close()
            val snap = app.places.delete(p.id) ?: return@launch
            toasts.show("״${p.name}״ נמחק", Ph.Trash, action = "ביטול") {
                app.scope.launch { app.places.restore(snap.first, snap.second) }
            }
        }
    }
}

@Composable
private fun NoteAtPlace(r: PlaceReminderEntity, n: NoteEntity, last: Boolean, onClick: () -> Unit) {
    val c = Noted.colors
    val t = Noted.type
    val line = remember(n.blocks, r.blockId) { if (r.blockId.isEmpty()) null else Blocks.parse(n.blocks).firstOrNull { it.id == r.blockId }?.text }
    Column(Modifier.fillMaxWidth().press(scale = .97f, onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(if (r.leave) Ph.SignOut else Ph.SignIn, null, Modifier.size(22.dp), tint = if (r.fired) c.ink3 else c.ink2)
            Column(Modifier.weight(1f)) {
                Text(
                    line?.trim()?.ifEmpty { null } ?: n.title.trim().ifEmpty { "פתק בלי כותרת" },
                    style = t.body.copy(fontWeight = FontWeight.SemiBold), color = if (r.fired) c.ink3 else c.ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOf(
                        PlaceWords.trigger(r.leave),
                        if (r.always) "בכל פעם" else if (r.fired) "כבר הזכיר" else "פעם אחת",
                        PlaceWords.window(r),
                        if (line != null) n.title.trim() else "",
                    ).filter { it.isNotEmpty() }.joinToString(" · "),
                    style = t.caption, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Ph.CaretEnd, null, Modifier.size(18.dp), tint = c.ink3)
        }
        if (!last) Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
    }
}
