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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ohav.noted.NotedApp
import com.ohav.noted.data.CatTree
import com.ohav.noted.design.IconButton
import com.ohav.noted.design.Motion
import com.ohav.noted.design.Noted
import com.ohav.noted.design.NotedTheme
import com.ohav.noted.design.Ph
import com.ohav.noted.design.Shapes
import com.ohav.noted.design.press
import com.ohav.noted.ui.categories.CatIcons
import kotlinx.coroutines.launch

/**
 * The stack widget's setup (and its "pick again"): every note, the pinned, the favourites, or one
 * category — each with how many notes it holds now.
 */
class PickDeckActivity : ComponentActivity() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        val app = application as NotedApp
        setContent {
            val settings by app.settings.settings.collectAsStateWithLifecycle(initialValue = null)
            settings?.let { NotedTheme(it) { PickDeck(app, widgetId, onPick = ::pick, onClose = ::finish) } }
        }
    }

    private fun pick(source: String) {
        val app = application as NotedApp
        app.scope.launch {
            val glance = runCatching { GlanceAppWidgetManager(this@PickDeckActivity).getGlanceIdBy(widgetId) }.getOrNull()
            if (glance != null) {
                updateAppWidgetState(this@PickDeckActivity, glance) { it[DeckWidget.SOURCE] = source; it.remove(DeckWidget.FRONT) }
                DeckWidget().update(this@PickDeckActivity, glance)
            }
        }
        setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }
}

private class Choice(val source: String, val name: String, val icon: ImageVector, val count: Int, val sub: Boolean = false)

@Composable
private fun PickDeck(app: NotedApp, widgetId: Int, onPick: (String) -> Unit, onClose: () -> Unit) {
    val c = Noted.colors
    val t = Noted.type
    val haptics = Noted.haptics
    val notes by app.notes.notes.collectAsStateWithLifecycle(initialValue = null)
    val cats by app.notes.categories.collectAsStateWithLifecycle(initialValue = emptyList())
    var current by remember { mutableStateOf<String?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(widgetId) {
        current = runCatching {
            val g = GlanceAppWidgetManager(ctx).getGlanceIdBy(widgetId)
            getAppWidgetState<Preferences>(ctx, PreferencesGlanceStateDefinition, g)[DeckWidget.SOURCE]
        }.getOrNull()
    }
    val choices = remember(notes, cats) {
        val ns = notes.orEmpty()
        fun count(s: String) = DeckWidget.pick(s, ns, cats)?.second?.size ?: 0
        val tree = CatTree(cats)
        listOf(
            Choice(DeckWidget.SRC_ALL, "כל הפתקים", Ph.Notepad, count(DeckWidget.SRC_ALL)),
            Choice(DeckWidget.SRC_PINNED, "נעוצים", Ph.PushPinFill, count(DeckWidget.SRC_PINNED)),
            Choice(DeckWidget.SRC_FAV, "מועדפים", Ph.StarFill, count(DeckWidget.SRC_FAV)),
        ) + tree.flat.map { k -> Choice(k.id, k.name, CatIcons.of(k.icon), count(k.id), sub = k.id !in tree.tops.map { it.id }) }
    }
    val rise = remember { Animatable(0f) }
    LaunchedEffect(notes != null) { if (notes != null) rise.animateTo(1f, Motion.glide()) }

    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(top = 6.dp)) {
                Text("מה בערימה?", style = t.title, color = c.ink)
                Spacer(Modifier.height(4.dp))
                Text("הפתקים האלה ייערמו בוידג'ט — נגיעה בפס מעלה פתק לראש", style = t.sub, color = c.ink2)
            }
            IconButton(Ph.X, "סגירה", onClick = onClose)
        }
        Spacer(Modifier.height(10.dp))
        if (notes != null) LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(choices, key = { _, ch -> ch.source }) { i, ch ->
                val on = ch.source == (current ?: DeckWidget.SRC_ALL)
                // the fixed three, a gap, then the categories
                if (i == 3) Spacer(Modifier.height(10.dp))
                Row(
                    Modifier
                        .padding(start = if (ch.sub) 28.dp else 0.dp)
                        .fillMaxWidth()
                        .graphicsLayer {
                            val k = ((rise.value * 1.6f) - i.coerceAtMost(8) * .08f).coerceIn(0f, 1f)
                            alpha = k
                            translationY = (1f - k) * 24.dp.toPx()
                        }
                        .press(scale = .97f, haptic = false) { haptics.confirm(); onPick(ch.source) }
                        .clip(Shapes.lg)
                        .background(if (on) c.accentSoft else c.surface)
                        .then(if (on) Modifier.border(2.dp, c.accent, Shapes.lg) else Modifier)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(38.dp).clip(Shapes.md).background(if (on) c.accent else c.bg2), contentAlignment = Alignment.Center) {
                        Icon(ch.icon, null, Modifier.size(20.dp), tint = if (on) c.accentInk else c.ink2)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(ch.name, style = t.bodyStrong, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(if (ch.count == 0) "ריק" else "${ch.count}", style = t.caption, color = c.ink3)
                    if (on) {
                        Spacer(Modifier.width(10.dp))
                        Icon(Ph.CheckCircleFill, "בוידג'ט עכשיו", Modifier.size(22.dp), tint = c.accent)
                    }
                }
            }
            item { Spacer(Modifier.navigationBarsPadding()) }
        }
    }
}
