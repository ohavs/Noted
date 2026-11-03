package com.ohav.noted.inbox

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.drawBehind
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ohav.noted.MainActivity
import com.ohav.noted.NotedApp
import com.ohav.noted.data.Blocks
import com.ohav.noted.data.NoteEntity
import com.ohav.noted.data.Pic
import com.ohav.noted.data.foreign
import com.ohav.noted.data.shareInfo
import com.ohav.noted.design.Btn
import com.ohav.noted.design.BtnKind
import com.ohav.noted.design.Motion
import com.ohav.noted.design.NoteColor
import com.ohav.noted.design.Noted
import com.ohav.noted.design.NotedTheme
import com.ohav.noted.design.Ph
import com.ohav.noted.design.Shadows
import com.ohav.noted.design.Shapes
import com.ohav.noted.design.Squircle
import com.ohav.noted.design.press
import com.ohav.noted.design.softShadow
import com.ohav.noted.data.LinkPreview
import com.ohav.noted.data.Privacy
import com.ohav.noted.reminders.Notifier
import com.ohav.noted.ui.home.snippetOf
import com.ohav.noted.ui.media.MediaImage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "Share → noted" from any app: a small sheet over that app. A new note, or the end of an
 * existing one; then a moment of "saved" with a way in, and back to where the user was.
 */
class ShareInActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as NotedApp
        val inc = Incoming.of(this, intent)
        if (inc.empty) { finish(); return }
        // pictures are copied in right away: the grant to read them ends with this screen
        val pics = CompletableDeferred<List<Pic>>()
        app.scope.launch { pics.complete(runCatching { Inbox.importImages(applicationContext, inc.images) }.getOrDefault(emptyList())) }
        var used = false
        setContent {
            val settings by app.settings.settings.collectAsStateWithLifecycle(initialValue = null)
            settings?.let {
                NotedTheme(it) {
                    ShareSheet(
                        app, inc, pics,
                        onUsed = { used = true },
                        onOpen = { id ->
                            startActivity(
                                Intent(this, MainActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                    .putExtra(Notifier.EXTRA_NOTE, id),
                            )
                        },
                        onGone = {
                            if (!used) app.scope.launch { Inbox.forget(applicationContext, pics.await()) }
                            finish(); overridePendingTransition(0, 0)
                        },
                    )
                }
            }
        }
    }
}

private sealed interface Step {
    data object Pick : Step
    data object Choose : Step
    data class Done(val id: String, val title: String, val fresh: Boolean) : Step
}

@Composable
private fun ShareSheet(
    app: NotedApp,
    inc: Incoming,
    pics: CompletableDeferred<List<Pic>>,
    onUsed: () -> Unit,
    onOpen: (String) -> Unit,
    onGone: () -> Unit,
) {
    val c = Noted.colors
    val haptics = Noted.haptics
    val scope = rememberCoroutineScope()
    val p = remember { Animatable(0f) }
    val fade = remember { Animatable(0f) }
    var leaving by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf<Step>(Step.Pick) }
    var busy by remember { mutableStateOf(false) }
    var imported by remember { mutableStateOf<List<Pic>?>(null) }
    LaunchedEffect(Unit) { imported = pics.await() }

    fun leave() {
        if (leaving) return
        leaving = true
        scope.launch {
            launch { fade.animateTo(0f, Motion.glide()) }
            p.animateTo(0f, Motion.glide())
            onGone()
        }
    }
    LaunchedEffect(Unit) {
        haptics.light()
        launch { fade.animateTo(1f, Motion.glide()) }
        p.animateTo(1f, Motion.touch())
    }
    // after "saved" the sheet bows out by itself, unless the user reaches for it
    var touched by remember { mutableStateOf(false) }
    LaunchedEffect(step, touched) {
        if (step is Step.Done && !touched) { delay(2200); leave() }
    }
    BackHandler { if (step == Step.Choose) step = Step.Pick else leave() }

    fun put(into: NoteEntity?) {
        if (busy) return
        busy = true
        scope.launch {
            val ps = pics.await()
            val id = if (into == null) Inbox.intoNew(app, inc, ps) else into.id.takeIf { Inbox.intoExisting(app, it, inc, ps) }
            busy = false
            if (id == null) { haptics.reject(); leave(); return@launch }
            onUsed()
            haptics.confirm()
            step = Step.Done(id, into?.title.orEmpty().ifBlank { if (into == null) inc.title else "" }, fresh = into == null)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }.background(c.scrim)
                .pointerInput(Unit) { detectTapGestures { leave() } },
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(14.dp)
                .fillMaxWidth()
                .graphicsLayer {
                    alpha = fade.value
                    translationY = (1f - p.value) * 30.dp.toPx()
                    val s = .94f + .06f * p.value
                    scaleX = s; scaleY = s
                }
                .softShadow(Shadows.float(c.isDark), 26.dp, c.surface)
                .clip(Shapes.lg)
                .background(c.surface)
                .pointerInput(Unit) { detectTapGestures { touched = true } }
                .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 14.dp),
        ) {
            AnimatedContent(
                step,
                transitionSpec = { (fadeIn(Motion.glide()) + scaleIn(Motion.glide(), initialScale = .96f)) togetherWith fadeOut(Motion.glide()) },
                contentKey = { it::class },
                label = "share-step",
            ) { s ->
                when (s) {
                    Step.Pick -> PickBody(app, inc, imported, busy, onNew = { put(null) }, onExisting = { haptics.light(); step = Step.Choose }, onCancel = { leave() })
                    Step.Choose -> ChooseBody(app, onBack = { step = Step.Pick }, onPick = { put(it) })
                    is Step.Done -> DoneBody(s, onOpen = { onOpen(s.id); leave() }, onClose = { leave() })
                }
            }
        }
    }
}

/** What came in, as a little paper card, and the two ways to keep it. */
@Composable
private fun PickBody(app: NotedApp, inc: Incoming, pics: List<Pic>?, busy: Boolean, onNew: () -> Unit, onExisting: () -> Unit, onCancel: () -> Unit) {
    val c = Noted.colors
    val t = Noted.type
    val cats by app.notes.categories.collectAsStateWithLifecycle(initialValue = emptyList())
    val home = remember(cats) { cats.filter { !it.foreign && it.parent.isEmpty() }.minByOrNull { it.position } ?: cats.firstOrNull() }
    Column {
        Text("לשמור ב-noted", style = t.title.copy(fontSize = t.title.fontSize * (27f / 30f)), color = c.ink)
        Spacer(Modifier.height(14.dp))
        Column(
            Modifier.fillMaxWidth().clip(Shapes.md).background(c.note(NoteColor.Butter)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(inc.kind, style = t.caption, color = c.noteInk2)
            if (inc.title.isNotBlank()) Text(inc.title, style = t.noteTitle, color = c.noteInk, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (inc.lines.isNotEmpty()) Text(inc.lines.joinToString("\n"), style = t.note, color = c.noteInk, maxLines = 4, overflow = TextOverflow.Ellipsis)
            inc.urls.forEach { u ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Ph.Globe, null, Modifier.size(16.dp), tint = c.noteInk2)
                    Text(LinkPreview.host(u), style = t.label, color = c.noteInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (inc.images.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val shown = minOf(inc.images.size, 4)
                    repeat(shown) { i ->
                        Box(Modifier.size(58.dp).clip(Squircle(14.dp))) {
                            val pic = pics?.getOrNull(i)
                            if (pic != null) MediaImage(pic.f, Modifier.fillMaxSize(), maxPx = 256)
                            else Box(Modifier.fillMaxSize().background(c.noteInk.copy(alpha = .07f)))
                            if (i == shown - 1 && inc.images.size > shown) {
                                Box(Modifier.fillMaxSize().background(c.noteInk.copy(alpha = .45f)), contentAlignment = Alignment.Center) {
                                    Text("+${inc.images.size - shown}", style = t.label, color = c.note(NoteColor.Butter))
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile(Ph.NotePencil, "פתק חדש", home?.let { "ב״${it.name}״" }, Modifier.weight(1f), enabled = !busy && home != null, onClick = onNew)
            Tile(Ph.Notepad, "לפתק קיים", "בסוף הפתק", Modifier.weight(1f), enabled = !busy, onClick = onExisting)
        }
        Spacer(Modifier.height(10.dp))
        Btn("ביטול", Modifier.fillMaxWidth(), kind = BtnKind.Quiet) { onCancel() }
    }
}

@Composable
private fun Tile(icon: ImageVector, label: String, sub: String?, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    val c = Noted.colors
    Column(
        modifier.clip(Shapes.md).background(c.bg).press(enabled = enabled, scale = .94f, haptic = false, onClick = onClick)
            .graphicsLayer { alpha = if (enabled) 1f else .5f }
            .padding(vertical = 16.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, Modifier.size(26.dp), tint = c.ink)
        Text(label, style = Noted.type.label, color = c.ink, maxLines = 1)
        if (sub != null) Text(sub, style = Noted.type.caption, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Which note: most recently touched first, a search over titles and text; read-only ones aren't offered. */
@Composable
private fun ChooseBody(app: NotedApp, onBack: () -> Unit, onPick: (NoteEntity) -> Unit) {
    val c = Noted.colors
    val t = Noted.type
    val all by app.notes.notes.collectAsStateWithLifecycle(initialValue = emptyList())
    val cats by app.notes.categories.collectAsStateWithLifecycle(initialValue = emptyList())
    val flags by app.privacy.flags.collectAsStateWithLifecycle()
    var q by remember { mutableStateOf("") }
    val rows = remember(all, cats, flags, q) {
        val readOnlyCats = cats.filter { it.foreign && it.shareInfo.readOnly }.map { it.id }.toSet()
        val needle = q.trim()
        all.asSequence()
            .filter { it.categoryId !in readOnlyCats && !com.ohav.noted.data.Share.parse(it.share).readOnly }
            .map { n -> n to Blocks.parse(n.blocks) }
            .filter { (n, b) ->
                val f = flags[n.id] ?: 0
                needle.isEmpty() || n.title.contains(needle, true) ||
                    (f and Privacy.LOCK == 0 && f and Privacy.BLUR_BODY == 0 && b.any { it.text.contains(needle, true) })
            }
            .sortedByDescending { it.first.updatedAt }
            .take(60).toList()
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(40.dp).clip(Squircle(13.dp)).background(c.bg).press(onClick = onBack), contentAlignment = Alignment.Center) {
                Icon(Ph.CaretStart, "חזרה", Modifier.size(20.dp), tint = c.ink)
            }
            Text("לאיזה פתק?", style = t.title.copy(fontSize = t.title.fontSize * (24f / 30f)), color = c.ink)
        }
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().clip(Shapes.md).background(c.bg).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Ph.MagnifyingGlass, null, Modifier.size(19.dp), tint = c.ink2)
            Box(Modifier.weight(1f)) {
                if (q.isEmpty()) Text("חיפוש", style = t.body, color = c.ink3)
                BasicTextField(q, { q = it }, Modifier.fillMaxWidth(), textStyle = t.body.copy(color = c.ink), singleLine = true, cursorBrush = SolidColor(c.accent))
            }
        }
        Spacer(Modifier.height(8.dp))
        if (rows.isEmpty()) {
            Text(if (q.isBlank()) "אין עדיין פתקים" else "לא נמצא פתק כזה", style = t.body, color = c.ink2, modifier = Modifier.padding(vertical = 22.dp).align(Alignment.CenterHorizontally))
        } else {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                items(rows, key = { it.first.id }) { (n, b) ->
                    val f = flags[n.id] ?: 0
                    val locked = f and Privacy.LOCK != 0
                    val hideTitle = f and Privacy.BLUR_TITLE != 0
                    val hideBody = locked || f and Privacy.BLUR_BODY != 0
                    NoteRow(n, if (hideTitle) "••••••" else n.title.ifBlank { "ללא כותרת" }, if (hideBody) "" else snippetOf(b), locked) { onPick(n) }
                }
            }
        }
    }
}

@Composable
private fun NoteRow(n: NoteEntity, title: String, snippet: String, locked: Boolean, onClick: () -> Unit) {
    val c = Noted.colors
    val t = Noted.type
    Row(
        Modifier.fillMaxWidth().clip(Shapes.sm).press(scale = .97f, onClick = onClick).padding(vertical = 9.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(34.dp).clip(Squircle(11.dp)).background(c.note(NoteColor.of(n.color))), contentAlignment = Alignment.Center) {
            if (locked) Icon(Ph.LockFill, null, Modifier.size(15.dp), tint = c.noteInk2)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = t.bodyStrong, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (snippet.isNotBlank()) Text(snippet, style = t.caption, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Saved: a tick that draws itself, where it went, and a way into the note. */
@Composable
private fun DoneBody(s: Step.Done, onOpen: () -> Unit, onClose: () -> Unit) {
    val c = Noted.colors
    val t = Noted.type
    val tick = remember { Animatable(0f) }
    LaunchedEffect(Unit) { tick.animateTo(1f, Motion.glide()) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(74.dp).clip(Squircle(24.dp)).background(c.accentSoft).drawBehind {
                val u = size.width / 74f
                val a = Offset(24 * u, 38 * u)
                val b = Offset(33 * u, 47 * u)
                val e = Offset(51 * u, 28 * u)
                val l1 = (b - a).getDistance()
                val l2 = (e - b).getDistance()
                val drawn = (l1 + l2) * tick.value.coerceIn(0f, 1f)
                val w = 5 * u
                if (drawn <= l1) drawLine(c.accent, a, a + (b - a) * (drawn / l1), w, StrokeCap.Round)
                else {
                    drawLine(c.accent, a, b, w, StrokeCap.Round)
                    drawLine(c.accent, b, b + (e - b) * ((drawn - l1) / l2), w, StrokeCap.Round)
                }
            },
        )
        Spacer(Modifier.height(14.dp))
        Text(if (s.fresh) "נשמר כפתק חדש" else "נוסף לפתק", style = t.title.copy(fontSize = t.title.fontSize * (24f / 30f)), color = c.ink)
        if (s.title.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text("״${s.title}״", style = t.body, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn("סגירה", Modifier.weight(1f), kind = BtnKind.Quiet) { onClose() }
            Btn("לפתק", Modifier.weight(1f), icon = Ph.ArrowSquareOut) { onOpen() }
        }
    }
}
