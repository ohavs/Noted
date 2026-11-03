package com.ohav.noted.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ohav.noted.NotedApp
import com.ohav.noted.data.BlockType
import com.ohav.noted.data.Blocks
import com.ohav.noted.data.NoteEntity
import com.ohav.noted.design.IconButton
import com.ohav.noted.design.Motion
import com.ohav.noted.design.NoteColor
import com.ohav.noted.design.Noted
import com.ohav.noted.design.NotedTheme
import com.ohav.noted.design.Ph
import com.ohav.noted.design.Shapes
import com.ohav.noted.design.press
import kotlinx.coroutines.launch

/**
 * The single-note widget's setup: which note goes on the home screen. Opens when the widget is
 * dropped on the launcher, from "reconfigure", or by tapping a widget that has no note.
 */
class PickNoteActivity : ComponentActivity() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // backing out of a fresh widget removes it (the launcher's contract)
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        val app = application as NotedApp
        setContent {
            val settings by app.settings.settings.collectAsStateWithLifecycle(initialValue = null)
            settings?.let { NotedTheme(it) { PickNote(app, widgetId, onPick = ::pick, onClose = ::finish) } }
        }
    }

    private fun pick(note: NoteEntity) {
        val app = application as NotedApp
        app.scope.launch {
            val glance = runCatching { GlanceAppWidgetManager(this@PickNoteActivity).getGlanceIdBy(widgetId) }.getOrNull()
            if (glance != null) {
                updateAppWidgetState(this@PickNoteActivity, glance) { it[NoteWidget.NOTE_ID] = note.id }
                NoteWidget().update(this@PickNoteActivity, glance)
            }
        }
        setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }
}

@Composable
private fun PickNote(app: NotedApp, widgetId: Int, onPick: (NoteEntity) -> Unit, onClose: () -> Unit) {
    val c = Noted.colors
    val t = Noted.type
    val haptics = Noted.haptics
    val notes by app.notes.notes.collectAsStateWithLifecycle(initialValue = null)
    val cats by app.notes.categories.collectAsStateWithLifecycle(initialValue = emptyList())
    val names = remember(cats) { cats.associate { it.id to it.name } }
    var query by rememberSaveable { mutableStateOf("") }
    var current by remember { mutableStateOf<String?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // reconfiguring: ring the note that's on the widget now
    LaunchedEffect(widgetId) {
        current = runCatching {
            val g = GlanceAppWidgetManager(ctx).getGlanceIdBy(widgetId)
            getAppWidgetState<Preferences>(ctx, PreferencesGlanceStateDefinition, g)[NoteWidget.NOTE_ID]
        }.getOrNull()
    }
    val shown = remember(notes, query) {
        val q = query.trim()
        notes.orEmpty().filter { n ->
            q.isEmpty() || n.title.contains(q, true) || Blocks.parse(n.blocks).any { it.text.contains(q, true) }
        }
    }
    // the cards rise in, staggered, once the list is there
    val rise = remember { Animatable(0f) }
    LaunchedEffect(notes != null) { if (notes != null) rise.animateTo(1f, Motion.glide()) }

    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(top = 6.dp)) {
                Text("איזה פתק על המסך?", style = t.title, color = c.ink)
                Spacer(Modifier.height(4.dp))
                Text("השורות שלו יופיעו בוידג'ט, ואפשר לסמן אותן ישר משם", style = t.sub, color = c.ink2)
            }
            IconButton(Ph.X, "סגירה", onClick = onClose)
        }
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(50.dp).clip(Shapes.md).background(c.surface).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Ph.MagnifyingGlass, null, Modifier.size(20.dp), tint = c.ink3)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("חיפוש פתק", style = t.field, color = c.ink3)
                BasicTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    textStyle = t.field.copy(color = c.ink),
                    cursorBrush = SolidColor(c.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) IconButton(Ph.X, "ניקוי", tint = c.ink3) { query = "" }
        }
        Spacer(Modifier.height(8.dp))
        when {
            notes == null -> Unit
            shown.isEmpty() -> Column(
                Modifier.fillMaxWidth().padding(top = 64.dp, start = 32.dp, end = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(56.dp).clip(Shapes.md).background(c.surface), contentAlignment = Alignment.Center) {
                    Icon(Ph.NotePencil, null, Modifier.size(26.dp), tint = c.ink2)
                }
                Spacer(Modifier.height(14.dp))
                Text(if (query.isBlank()) "אין עדיין פתקים" else "לא נמצא פתק כזה", style = t.emptyTitle, color = c.ink)
                Spacer(Modifier.height(4.dp))
                Text(if (query.isBlank()) "כותבים פתק באפליקציה — ואז הוא יכול לעלות למסך" else "אולי במילה אחרת?", style = t.sub, color = c.ink2)
            }
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(shown, key = { _, n -> n.id }) { i, n ->
                    PickCard(
                        n, names[n.categoryId], on = n.id == current,
                        modifier = Modifier.graphicsLayer {
                            val k = ((rise.value * 1.6f) - i.coerceAtMost(8) * .08f).coerceIn(0f, 1f)
                            alpha = k
                            translationY = (1f - k) * 28.dp.toPx()
                        },
                    ) { haptics.confirm(); onPick(n) }
                }
                item { Spacer(Modifier.navigationBarsPadding()) }
            }
        }
    }
}

@Composable
private fun PickCard(n: NoteEntity, category: String?, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Noted.colors
    val t = Noted.type
    val blocks = remember(n.blocks) { Blocks.parse(n.blocks) }
    val preview = remember(blocks) {
        blocks.filter { it.type != BlockType.Divider }.mapNotNull { b ->
            when (b.type) {
                BlockType.Images -> "תמונה"
                BlockType.Voice -> "הקלטה"
                BlockType.Draw -> "ציור"
                BlockType.Link -> "קישור"
                BlockType.Check -> clean(b.text).takeIf { it.isNotEmpty() }?.let { (if (b.done) "✓ " else "○ ") + it }
                else -> clean(b.text).takeIf { it.isNotEmpty() }
            }
        }.take(3).joinToString("\n")
    }
    Column(
        modifier
            .fillMaxWidth()
            .press(scale = .97f, haptic = false, onClick = onClick)
            .clip(Shapes.lg)
            .background(c.note(NoteColor.of(n.color)))
            .then(if (on) Modifier.border(2.5.dp, c.ink, Shapes.lg) else Modifier)
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                n.title.ifBlank { "ללא כותרת" }, style = t.cardTitle, color = c.noteInk,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (on) {
                Spacer(Modifier.width(8.dp))
                Icon(Ph.CheckCircleFill, "על המסך עכשיו", Modifier.size(22.dp), tint = c.noteInk)
            }
        }
        if (preview.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(preview, style = t.snippet, color = c.noteInk2, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        category?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = t.tiny, color = c.noteInk2)
        }
    }
}
