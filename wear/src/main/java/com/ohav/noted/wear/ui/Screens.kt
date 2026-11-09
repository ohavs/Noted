package com.ohav.noted.wear.ui

import androidx.compose.runtime.remember
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.ohav.noted.wear.PlaceRem
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.AutoCenteringParams
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.TimeText
import com.ohav.noted.wear.BuildConfig
import com.ohav.noted.wear.Line
import com.ohav.noted.wear.Note
import com.ohav.noted.wear.Palette
import com.ohav.noted.wear.Rem
import com.ohav.noted.wear.Updater
import com.ohav.noted.wear.store
import com.ohav.noted.wear.Store
import com.ohav.noted.wear.WView
import com.ohav.noted.wear.WState
import kotlinx.coroutines.launch

/** What the screens can ask the activity for. */
class Actions(
    val dictate: (into: String?) -> Unit,
    val record: (into: String?) -> Unit,
    val openNote: (String) -> Unit,
    val openOnPhone: (String) -> Unit,
    val reminders: () -> Unit,
    val settings: () -> Unit,
    val flash: Flash,
    /** Any other screen by its route ("cats", "tile"). */
    val go: (String) -> Unit,
)

@Composable
fun ListScreen(content: ScalingLazyListScope.() -> Unit) {
    val state = rememberScalingLazyListState()
    Scaffold(
        modifier = Modifier.background(C.bg),
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = state) },
    ) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize().background(C.bg),
            state = state,
            autoCentering = AutoCenteringParams(itemIndex = 0),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 30.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
    }
}

// ---------------- home ----------------

@Composable
fun HomeScreen(a: Actions) {
    val ctx = LocalContext.current
    val s by ctx.store.state.collectAsState()
    val phone by ctx.store.phone.collectAsState()
    val accent = Color(Palette.accent(s?.accent))
    val onAccent = Color(Palette.onAccent(s?.accent))
    val st = s
    if (st == null) {
        Waiting(phone == false) { ctx.store.refresh() }
        return
    }
    val view by ctx.store.view.collectAsState()
    val notes = view.notes(st)
    val rems = view.rems(st)
    val pinned = notes.filter { it.pinned }
    val rest = notes.filterNot { it.pinned }
    ListScreen {
        item { Wordmark(accent) }
        item {
            Row(Modifier.padding(top = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundAction(Ph.Waveform, C.surface2, C.ink, 44.dp, "הקלטה") { a.record(null) }
                Spacer(Modifier.width(14.dp))
                RoundAction(Ph.MicrophoneFill, accent, onAccent, 56.dp, "הכתבה") { a.dictate(null) }
            }
        }
        if (phone == false && ctx.store.waiting > 0) item {
            BasicText("${ctx.store.waiting} פעולות מחכות לטלפון", style = body(11.sp, C.ink3, 500, TextAlign.Center))
        }
        if (rems.isNotEmpty()) {
            item { Section("בקרוב") }
            items(rems.take(2), key = { "r" + it.id }) { r -> ReminderRow(r, accent, compact = true) { a.reminders() } }
            if (rems.size > 2) item {
                BasicText("כל התזכורות (${rems.size})", Modifier.press { a.reminders() }.padding(6.dp), style = body(12.sp, accent, 600))
            }
        }
        if (pinned.isNotEmpty()) {
            item { Section("נעוצים") }
            items(pinned, key = { it.id }) { n -> NoteCard(n) { a.openNote(n.id) } }
        }
        if (rest.isNotEmpty()) {
            item { Section(if (pinned.isEmpty()) "פתקים" else "כל השאר") }
            items(rest, key = { it.id }) { n -> NoteCard(n) { a.openNote(n.id) } }
        }
        if (notes.isEmpty()) item {
            BasicText(if (st.notes.isEmpty()) "אין פתקים עדיין.\nהכתיבו את הראשון" else "אין פתקים בקטגוריות שנבחרו לשעון", Modifier.padding(12.dp), style = body(14.sp, C.ink2, 400, TextAlign.Center))
        }
        item {
            Column(Modifier.padding(top = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                RoundAction(Ph.Gear, C.surface, C.ink2, 36.dp) { a.settings() }
                Spacer(Modifier.height(6.dp))
                BasicText(
                    if (phone == false) "הטלפון לא זמין" else "מהטלפון · ${agoText(st.at)}",
                    style = body(11.sp, C.ink3, 400, TextAlign.Center),
                )
            }
        }
    }
}

private fun Note.shownTitle(): String = when {
    locked -> title.ifBlank { "פתק נעול" }
    blurTitle -> "••••••"
    else -> title.ifBlank { lines.firstOrNull { it.text.isNotBlank() && !it.isCheck }?.text ?: "פתק בלי כותרת" }
}

/** A note on the home list: its paper colour, title in the display face, a peek at what's inside. */
@Composable
fun NoteCard(n: Note, onClick: () -> Unit) {
    val (done, total) = n.progress
    Column(
        Modifier.fillMaxWidth().press(onClick = onClick).clip(RoundedCornerShape(22.dp)).background(C.note(n.color))
            .padding(start = 14.dp, end = 14.dp, top = 9.dp, bottom = 11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(n.shownTitle(), Modifier.weight(1f), style = display(22.sp, C.noteInk, TextAlign.Start), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (n.pinned) Icon(Ph.PushPinFill, C.noteInk2, 13.dp)
        }
        when {
            n.locked -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Ph.LockFill, C.noteInk2, 12.dp)
                Spacer(Modifier.width(4.dp))
                BasicText("נעול", style = body(12.sp, C.noteInk2, 500))
            }
            n.blurBody -> BasicText("••• ••••• •• ••••", style = body(13.sp, C.noteInk2))
            total > 0 -> {
                val open = n.checks.filter { !it.done && it.text.isNotBlank() }.map { it.text.trim() }
                BasicText(
                    if (open.isEmpty()) "הכל סומן" else open.joinToString(" · "),
                    style = body(13.sp, C.noteInk2), maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Progress(done, total, C.noteInk.copy(alpha = .12f), C.noteInk, Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    BasicText("$done/$total", style = body(11.sp, C.noteInk2, 600))
                }
            }
            else -> {
                val peek = n.lines.filter { it.text.isNotBlank() && it.kind !in media }.drop(if (n.title.isBlank()) 1 else 0)
                    .joinToString("\n") { it.text.trim() }
                if (peek.isNotEmpty()) BasicText(peek, style = body(13.sp, C.noteInk2), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private val media = setOf("Images", "Voice", "Link", "Draw", "Place")

/** No state yet: three paper strips breathing, and what to do. */
@Composable
private fun Waiting(failed: Boolean, retry: () -> Unit) {
    val t = rememberInfiniteTransition(label = "wait")
    val y by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "y")
    Box(Modifier.fillMaxSize().background(C.bg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(width = 90.dp, height = 62.dp)) {
                listOf("Sky" to 0, "Mint" to 1, "Butter" to 2).forEach { (c, i) ->
                    Box(
                        Modifier.align(Alignment.BottomCenter)
                            .graphicsLayer { translationY = -(i * 14f + y * i * 5f) * density; scaleX = 1f - (2 - i) * .08f }
                            .size(width = 90.dp, height = 30.dp).clip(RoundedCornerShape(10.dp)).background(C.note(c)),
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            BasicText(if (failed) "הטלפון לא זמין" else "מחכה לטלפון…", style = body(15.sp, C.ink, 600, TextAlign.Center))
            Spacer(Modifier.height(4.dp))
            BasicText("פתחו את noted בטלפון\nכשהשעון מחובר אליו", style = body(12.sp, C.ink3, 400, TextAlign.Center))
            Spacer(Modifier.height(10.dp))
            RoundAction(Ph.ArrowsClockwise, C.surface2, C.ink, 40.dp, onClick = retry)
        }
    }
}

// ---------------- a note ----------------

@Composable
fun NoteScreen(id: String, a: Actions) {
    val ctx = LocalContext.current
    val s by ctx.store.state.collectAsState()
    val n = s?.note(id)
    val accent = Color(Palette.accent(s?.accent))
    val h = rememberHaptics()
    var showTitle by rememberSaveable { mutableStateOf(false) }
    var showBody by rememberSaveable { mutableStateOf(false) }
    var showDone by rememberSaveable { mutableStateOf(false) }
    val ticked by ctx.store.ticked.collectAsState()
    if (n == null) {
        Box(Modifier.fillMaxSize().background(C.bg), contentAlignment = Alignment.Center) {
            BasicText("הפתק כבר לא כאן", style = body(14.sp, C.ink2, 500, TextAlign.Center))
        }
        return
    }
    val tint = C.note(n.color)
    val (done, total) = n.progress
    ListScreen {
        s?.cat(n.cat)?.let { c -> item { BasicText(c.name, style = body(11.sp, C.ink3, 600, TextAlign.Center)) } }
        item {
            BasicText(
                if (n.blurTitle && !showTitle) "••••••" else n.title.ifBlank { "פתק בלי כותרת" },
                Modifier.padding(horizontal = 8.dp).press(enabled = n.blurTitle && !showTitle) { showTitle = true },
                style = display(30.sp, tint), maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
        }
        if (total > 0 && !n.locked && !(n.blurBody && !showBody)) item {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 24.dp)) {
                Progress(done, total, C.surface2, tint, Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                BasicText(if (done == total) "הכל סומן" else "$done מתוך $total", style = body(11.sp, C.ink3, 500))
            }
        }
        when {
            n.locked -> item {
                Column(Modifier.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(48.dp).clip(CircleShape).background(C.surface2), contentAlignment = Alignment.Center) { Icon(Ph.LockFill, tint, 22.dp) }
                    Spacer(Modifier.height(8.dp))
                    BasicText("הפתק נעול.\nאפשר לפתוח אותו בטלפון", style = body(13.sp, C.ink2, 400, TextAlign.Center))
                }
            }
            n.blurBody && !showBody -> item {
                Column(
                    Modifier.fillMaxWidth().press { showBody = true }.clip(RoundedCornerShape(18.dp)).background(C.surface).padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    BasicText("••• ••••• ••\n•••• ••• •••••", style = body(14.sp, C.ink3, 400, TextAlign.Center))
                    Spacer(Modifier.height(4.dp))
                    BasicText("הקישו להצגה", style = body(11.sp, accent, 600))
                }
            }
            else -> {
                // a ticked row sinks to the bottom, stays a moment, then folds away (the toggle brings them back)
                val now = System.currentTimeMillis()
                val shown = n.lines.filter { l -> !l.isCheck || !l.done || showDone || ticked[l.id]?.let { now - it <= Store.SHOW_MS } == true }
                val hidden = n.lines.size - shown.size
                val rows = numbered(shown)
                items(rows, key = { it.first.id }) { (l, num) ->
                    LineView(l, num, tint, enabled = !n.readOnly) { d ->
                        if (d) h.confirm()
                        ctx.store.toggle(n.id, l.id, d)
                    }
                }
                if (hidden > 0 || (showDone && done > 0)) item {
                    Row(
                        Modifier.padding(top = 2.dp).press { showDone = !showDone }.clip(RoundedCornerShape(16.dp)).background(C.surface)
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Ph.Checks, C.ink2, 15.dp)
                        Spacer(Modifier.width(6.dp))
                        BasicText(if (showDone) "הסתרת מה שבוצע" else "$hidden בוצעו · הצגה", style = body(12.sp, C.ink2, 500))
                    }
                }
                if (rows.isEmpty() && hidden == 0) item { BasicText("פתק ריק", style = body(13.sp, C.ink3, 400, TextAlign.Center)) }
            }
        }
        if (n.readOnly) item { BasicText("צפייה בלבד", Modifier.padding(top = 4.dp), style = body(11.sp, C.ink3, 500)) }
        item {
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val canWrite = !n.locked && !n.readOnly
                if (canWrite) RoundAction(Ph.MicrophoneFill, C.surface2, C.ink, 42.dp, "שורה") { a.dictate(n.id) }
                if (canWrite) RoundAction(Ph.Waveform, C.surface2, C.ink, 42.dp, "הקלטה") { a.record(n.id) }
                RoundAction(Ph.DeviceMobile, C.surface2, C.ink, 42.dp, "בטלפון") { a.openOnPhone(n.id) }
            }
        }
    }
}

/** Lines worth drawing (runs of empty paragraphs dropped), with their number for numbered lists. */
private fun numbered(lines: List<Line>): List<Pair<Line, Int>> {
    val out = ArrayList<Pair<Line, Int>>()
    var count = 0
    var lastEmpty = true
    for (l in lines) {
        val empty = l.text.isBlank() && l.kind in setOf("P", "H1", "H2", "H3", "Quote", "Bullet", "Num")
        if (empty) { if (!lastEmpty) count = 0; lastEmpty = true; continue }
        lastEmpty = false
        count = if (l.kind == "Num") count + 1 else 0
        out += l to count
    }
    return out
}

@Composable
private fun LineView(l: Line, num: Int, tint: Color, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val start = (l.indent * 12).dp
    val m = Modifier.fillMaxWidth().padding(start = start + 6.dp, end = 6.dp)
    when (l.kind) {
        "Check" -> CheckRow(l, tint, enabled, Modifier.fillMaxWidth().padding(start = start), onToggle)
        "H1" -> BasicText(l.text, m.padding(top = 4.dp), style = body(18.sp, C.ink, 700))
        "H2" -> BasicText(l.text, m.padding(top = 3.dp), style = body(16.sp, C.ink, 700))
        "H3" -> BasicText(l.text, m, style = body(15.sp, C.ink, 600))
        "Quote" -> Row(m) {
            Box(Modifier.width(3.dp).height(20.dp).clip(CircleShape).background(tint))
            Spacer(Modifier.width(8.dp))
            BasicText(l.text, style = body(14.sp, C.ink2))
        }
        "Bullet", "Num" -> Row(m) {
            Box(Modifier.width(18.dp), contentAlignment = Alignment.TopCenter) {
                if (l.kind == "Num") BasicText("$num.", style = body(14.sp, tint, 600))
                else Box(Modifier.padding(top = 7.dp).size(5.dp).clip(CircleShape).background(tint))
            }
            BasicText(l.text, style = body(14.sp))
        }
        "Divider" -> Box(Modifier.padding(vertical = 4.dp).width(48.dp).height(1.dp).background(C.ink3))
        "Images" -> if (l.files.isEmpty()) MediaLine(Ph.Image, tint, if (l.text == "1") "תמונה" else "${l.text} תמונות", null) else PicsLine(l)
        "Place" -> MediaLine(Ph.MapPin, tint, l.text.ifBlank { "מקום" }, null)
        "Voice" -> MediaLine(Ph.Waveform, tint, "הקלטה · ${mmss(l.ms)}", l.text.ifBlank { null })
        "Link" -> MediaLine(Ph.Link, tint, l.text.ifBlank { "קישור" }, null)
        "Draw" -> MediaLine(Ph.ScribbleLoop, tint, "ציור", null)
        else -> BasicText(l.text, m, style = body(14.sp))
    }
}

/** Up to three pictures as squares; each thumbnail is asked from the phone the first time it's shown. */
@Composable
private fun PicsLine(l: Line) {
    val ctx = LocalContext.current
    val have by ctx.store.pics.collectAsState()
    val more = (l.text.toIntOrNull() ?: l.files.size) - l.files.size
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        l.files.forEachIndexed { i, f ->
            LaunchedEffect(f) { ctx.store.pic(f) }
            val bmp = remember(f, f in have) {
                if (f in have) runCatching { BitmapFactory.decodeFile(ctx.store.picFile(f).path)?.asImageBitmap() }.getOrNull() else null
            }
            val shown by animateFloatAsState(if (bmp != null) 1f else 0f, Motion.glide(), label = "pic")
            Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(C.surface), contentAlignment = Alignment.Center) {
                val b: ImageBitmap? = bmp
                if (b == null) Icon(Ph.Image, C.ink3, 18.dp)
                else Image(bitmap = b, contentDescription = null, modifier = Modifier.matchParentSize().graphicsLayer { alpha = shown }, contentScale = ContentScale.Crop)
                if (more > 0 && i == l.files.lastIndex) {
                    Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = .45f)), contentAlignment = Alignment.Center) {
                        BasicText("+$more", style = display(18.sp, Color.White))
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaLine(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, label: String, sub: String?) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.surface).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, tint, 18.dp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            BasicText(label, style = body(13.sp, C.ink, 500), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) BasicText(sub, style = body(12.sp, C.ink2), maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A checklist row: the box fills with the note's colour and its tick draws itself. */
@Composable
fun CheckRow(l: Line, tint: Color, enabled: Boolean, modifier: Modifier, onToggle: (Boolean) -> Unit) {
    val k by animateFloatAsState(if (l.done) 1f else 0f, Motion.touch(), label = "check")
    val box = RoundedCornerShape(7.dp)
    Row(
        modifier.press(enabled) { onToggle(!l.done) }.clip(RoundedCornerShape(18.dp)).background(C.surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(22.dp).graphicsLayer { val p = 1f + (k - k * k) * .5f; scaleX = p; scaleY = p }
                .clip(box).background(tint.copy(alpha = k.coerceIn(0f, 1f))).border(2.dp, tint, box),
            contentAlignment = Alignment.Center,
        ) { Tick(C.noteInk, k, Modifier.size(16.dp)) }
        Spacer(Modifier.width(10.dp))
        BasicText(
            l.text,
            Modifier.weight(1f),
            style = body(14.sp, if (l.done) C.ink3 else C.ink).copy(textDecoration = if (l.done) TextDecoration.LineThrough else null),
        )
    }
}

// ---------------- reminders ----------------

@Composable
fun RemindersScreen(a: Actions) {
    val ctx = LocalContext.current
    val s by ctx.store.state.collectAsState()
    val accent = Color(Palette.accent(s?.accent))
    val onAccent = Color(Palette.onAccent(s?.accent))
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val view by ctx.store.view.collectAsState()
    val rems = s?.let { view.rems(it) }.orEmpty()
    val places = s?.let { view.places(it) }.orEmpty()
    ListScreen {
        item { BasicText("תזכורות", style = display(28.sp)) }
        if (rems.isEmpty() && places.isEmpty()) item {
            Column(Modifier.padding(top = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(48.dp).clip(CircleShape).background(C.surface2), contentAlignment = Alignment.Center) { Icon(Ph.Bell, C.ink2, 22.dp) }
                Spacer(Modifier.height(8.dp))
                BasicText("אין תזכורות קרובות", style = body(13.sp, C.ink2, 500, TextAlign.Center))
            }
        }
        items(rems, key = { it.id }) { r ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ReminderRow(r, accent, compact = false) { open = if (open == r.id) null else r.id }
                AnimatedVisibility(open == r.id, enter = fadeIn(Motion.glide()) + expandVertically(Motion.glide()), exit = fadeOut(Motion.glide()) + shrinkVertically(Motion.glide())) {
                    Row(Modifier.padding(top = 8.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                        RoundAction(Ph.Check, accent, onAccent, 44.dp, "בוצע") {
                            ctx.store.reminderDone(r); open = null; a.flash.show("סומן כבוצע")
                        }
                        TextAction("10׳", "עוד 10 דק׳") { ctx.store.reminderLater(r, 10 * 60_000L); open = null; a.flash.show("נזכיר בעוד 10 דקות", Ph.Clock) }
                        TextAction("שעה", "עוד שעה") { ctx.store.reminderLater(r, 60 * 60_000L); open = null; a.flash.show("נזכיר בעוד שעה", Ph.Clock) }
                    }
                }
            }
        }
        if (places.isNotEmpty()) item {
            BasicText("במקומות", Modifier.padding(top = 10.dp, bottom = 2.dp), style = body(12.sp, C.ink3, 700, TextAlign.Center))
        }
        items(places, key = { "p:" + it.id }) { r ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                PlaceRow(r, accent) { open = if (open == "p:" + r.id) null else "p:" + r.id }
                AnimatedVisibility(open == "p:" + r.id, enter = fadeIn(Motion.glide()) + expandVertically(Motion.glide()), exit = fadeOut(Motion.glide()) + shrinkVertically(Motion.glide())) {
                    Row(Modifier.padding(top = 8.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                        RoundAction(Ph.Check, accent, onAccent, 44.dp, "בוצע") {
                            ctx.store.placeDone(r); open = null; a.flash.show("סומן כבוצע")
                        }
                        RoundAction(Ph.Notepad, C.surface2, C.ink, 44.dp, "לפתק") { open = null; a.openNote(r.noteId) }
                    }
                }
            }
        }
    }
}

/** A note waiting for a place: what, and where — arriving or leaving. */
@Composable
fun PlaceRow(r: PlaceRem, accent: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().press(onClick = onClick).clip(RoundedCornerShape(20.dp)).background(C.surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Ph.MapPinFill, accent, 16.dp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            BasicText(r.title, style = body(13.sp, C.ink, 500), maxLines = 2, overflow = TextOverflow.Ellipsis)
            BasicText(
                (if (r.leave) "ביציאה מ" else "בהגעה ל") + r.place + if (r.always) " · תמיד" else "",
                style = body(11.sp, C.ink3), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TextAction(big: String, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(44.dp).press(onClick = onClick).clip(CircleShape).background(C.surface2), contentAlignment = Alignment.Center) {
            BasicText(big, style = display(20.sp, C.ink))
        }
        Spacer(Modifier.height(4.dp))
        BasicText(label, style = body(11.sp, C.ink3, 500))
    }
}

@Composable
fun ReminderRow(r: Rem, accent: Color, compact: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().press(onClick = onClick).clip(RoundedCornerShape(20.dp))
            .background(if (r.due) accent.copy(alpha = .18f) else C.surface)
            .padding(horizontal = 12.dp, vertical = if (compact) 8.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (r.due) Ph.BellRinging else Ph.Bell, if (r.due) accent else C.ink2, 16.dp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            BasicText(r.title, style = body(13.sp, C.ink, 500), maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
            if (!compact && r.text.isNotBlank() && r.noteTitle.isNotBlank()) {
                BasicText(r.noteTitle, style = body(11.sp, C.ink3), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(6.dp))
        Column(horizontalAlignment = Alignment.End) {
            BasicText(whenText(r.at, r.due), style = display(if (compact) 17.sp else 19.sp, if (r.due) accent else C.ink, TextAlign.End))
            if (r.repeats && !compact) Icon(Ph.Repeat, C.ink3, 11.dp)
        }
    }
}

// ---------------- settings ----------------

@Composable
fun SettingsScreen(a: Actions) {
    val ctx = LocalContext.current
    val s by ctx.store.state.collectAsState()
    val phone by ctx.store.phone.collectAsState()
    val up by Updater.state.collectAsState()
    val accent = Color(Palette.accent(s?.accent))
    val onAccent = Color(Palette.onAccent(s?.accent))
    val scope = rememberCoroutineScope()
    ListScreen {
        item { Wordmark(accent, 24.sp) }
        item { BasicText("גרסה ${BuildConfig.VERSION_NAME}", style = body(12.sp, C.ink3, 500)) }
        item {
            SettingRow(Ph.DeviceMobile, if (phone == false) "הטלפון לא זמין" else "מחובר לטלפון", s?.let { "עודכן ${agoText(it.at)}" }) {
                ctx.store.refresh(); a.flash.show("מסנכרן עם הטלפון", Ph.ArrowsClockwise)
            }
        }
        if (BuildConfig.SELF_UPDATE) item {
            val (label, sub) = when (val u = up) {
                Updater.State.Idle -> "בדיקת עדכונים" to null
                Updater.State.Checking -> "בודק…" to null
                Updater.State.UpToDate -> "הגרסה עדכנית" to "בדיקה חוזרת"
                is Updater.State.Available -> "עדכון ל-${u.version}" to "הקישו להתקנה"
                is Updater.State.Downloading -> "מוריד ${(u.progress * 100).toInt()}%" to null
                Updater.State.Installing -> "מתקין…" to "אשרו בשעון"
                is Updater.State.Failed -> "לא הצליח" to u.why
            }
            SettingRow(Ph.DownloadSimple, label, sub, highlight = up is Updater.State.Available, accent = accent, onAccent = onAccent) {
                when (val u = up) {
                    is Updater.State.Available -> scope.launch { Updater.install(ctx, u) }
                    Updater.State.Checking, is Updater.State.Downloading, Updater.State.Installing -> {}
                    else -> scope.launch { Updater.check() }
                }
            }
        }
        item {
            val view by ctx.store.view.collectAsState()
            val live = s?.let { view.live(it) }.orEmpty()
            SettingRow(Ph.FolderSimple, "קטגוריות בשעון", if (live.isEmpty()) "הכל" else s?.let { st -> live.mapNotNull { st.cat(it)?.name }.joinToString(" · ") }) { a.go("cats") }
        }
        item {
            val view by ctx.store.view.collectAsState()
            SettingRow(Ph.Cards, "מה ב-Tile", s?.let { tileLabel(view.tile, it) }) { a.go("tile") }
        }
        item {
            BasicText(
                "הפתקים מגיעים מהטלפון. סימון, הכתבה והקלטות נשמרים בו, גם אם הוא רחוק — הם יישלחו כשיתחבר.",
                Modifier.padding(horizontal = 14.dp, vertical = 6.dp), style = body(11.sp, C.ink3, 400, TextAlign.Center),
            )
        }
    }
}

@Composable
private fun SettingRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, sub: String?,
    highlight: Boolean = false, accent: Color = C.ink, onAccent: Color = C.bg, onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().press(onClick = onClick).clip(RoundedCornerShape(20.dp)).background(if (highlight) accent else C.surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, if (highlight) onAccent else C.ink2, 18.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            BasicText(label, style = body(14.sp, if (highlight) onAccent else C.ink, 500))
            if (sub != null) BasicText(sub, style = body(11.sp, if (highlight) onAccent.copy(alpha = .7f) else C.ink3))
        }
    }
}

// ---------------- what the watch shows ----------------

private fun tileLabel(t: String, s: WState): String = when {
    t == WView.TILE_RECENT -> "אחרונים"
    t == WView.TILE_REMS -> "תזכורות קרובות"
    t.startsWith("cat:") -> s.cat(t.removePrefix("cat:"))?.name ?: "כל הפתקים"
    t.startsWith("note:") -> s.note(t.removePrefix("note:"))?.let { if (it.locked || it.blurTitle) "פתק נעול" else it.title.ifBlank { "פתק בלי כותרת" } } ?: "כל הפתקים"
    else -> "כל הפתקים"
}

/** Which categories reach the watch: none ticked = all of them. */
@Composable
fun CatsScreen() {
    val ctx = LocalContext.current
    val s by ctx.store.state.collectAsState()
    val view by ctx.store.view.collectAsState()
    val h = rememberHaptics()
    val st = s ?: return
    val accent = Color(Palette.accent(st.accent))
    val onAccent = Color(Palette.onAccent(st.accent))
    val live = view.live(st)
    ListScreen {
        item { BasicText("קטגוריות", style = display(26.sp)) }
        item { BasicText("מה יופיע בשעון וב-Tile", Modifier.padding(bottom = 4.dp), style = body(11.sp, C.ink3, 500, TextAlign.Center)) }
        item {
            PickRow("הכל", "${st.notes.size} פתקים", live.isEmpty(), accent, onAccent) { h.tick(); ctx.store.setCats(emptySet()) }
        }
        items(st.tops, key = { it.id }) { c ->
            val count = st.notes.count { st.top(it.cat) == c.id }
            PickRow(c.name, "$count פתקים", c.id in live, accent, onAccent) {
                h.tick()
                ctx.store.setCats(if (c.id in live) live - c.id else live + c.id)
            }
        }
    }
}

/** What the tile holds: pinned, newest, reminders, one category or one note. */
@Composable
fun TileScreen(a: Actions) {
    val ctx = LocalContext.current
    val s by ctx.store.state.collectAsState()
    val view by ctx.store.view.collectAsState()
    val h = rememberHaptics()
    val st = s ?: return
    val accent = Color(Palette.accent(st.accent))
    val onAccent = Color(Palette.onAccent(st.accent))
    fun pick(t: String) { h.confirm(); ctx.store.setTile(t); a.flash.show("ה-Tile עודכן", Ph.Cards) }
    val notes = view.notes(st).sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.at }).take(30)
    ListScreen {
        item { BasicText("ב-Tile", style = display(26.sp)) }
        item { PickRow("כל הפתקים", "הנעוצים ראשונים", view.tile == WView.TILE_PINNED, accent, onAccent) { pick(WView.TILE_PINNED) } }
        item { PickRow("אחרונים", "מה שהשתנה לאחרונה", view.tile == WView.TILE_RECENT, accent, onAccent) { pick(WView.TILE_RECENT) } }
        item { PickRow("תזכורות קרובות", "מה ומתי", view.tile == WView.TILE_REMS, accent, onAccent) { pick(WView.TILE_REMS) } }
        val cats = view.live(st).let { l -> if (l.isEmpty()) st.tops else st.tops.filter { it.id in l } }
        if (cats.isNotEmpty()) item { Section("קטגוריה אחת") }
        items(cats, key = { "c:" + it.id }) { c ->
            PickRow(c.name, null, view.tile == "cat:" + c.id, accent, onAccent) { pick("cat:" + c.id) }
        }
        if (notes.isNotEmpty()) item { Section("פתק אחד — לסמן מה-Tile") }
        items(notes, key = { "n:" + it.id }) { n ->
            val (done, total) = n.progress
            PickRow(
                if (n.locked) "פתק נעול" else if (n.blurTitle) "••••••" else n.title.ifBlank { n.lines.firstOrNull { it.text.isNotBlank() }?.text ?: "פתק בלי כותרת" },
                if (total > 0 && !n.locked && !n.blurBody) "$done/$total" else null,
                view.tile == "note:" + n.id, accent, onAccent, paper = C.note(n.color),
            ) { pick("note:" + n.id) }
        }
    }
}

/** A choice in a list: a disc that fills with the accent and a tick when it's on. */
@Composable
private fun PickRow(label: String, sub: String?, on: Boolean, accent: Color, onAccent: Color, paper: Color? = null, onClick: () -> Unit) {
    val k by animateFloatAsState(if (on) 1f else 0f, Motion.touch(), label = "pick")
    Row(
        Modifier.fillMaxWidth().press(onClick = onClick).clip(RoundedCornerShape(20.dp)).background(C.surface)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (paper != null) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(paper))
            Spacer(Modifier.width(9.dp))
        }
        Column(Modifier.weight(1f)) {
            BasicText(label, style = body(14.sp, C.ink, 500), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) BasicText(sub, style = body(11.sp, C.ink3), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.size(22.dp).graphicsLayer { val p = 1f + (k - k * k) * .5f; scaleX = p; scaleY = p }
                .clip(CircleShape).background(accent.copy(alpha = k.coerceIn(0f, 1f))).border(2.dp, if (on) accent else C.ink3, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Tick(onAccent, k, Modifier.size(14.dp)) }
    }
}
