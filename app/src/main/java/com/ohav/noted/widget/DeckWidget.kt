package com.ohav.noted.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.ohav.noted.NotedApp
import com.ohav.noted.R
import com.ohav.noted.data.BlockType
import com.ohav.noted.data.Blocks
import com.ohav.noted.data.CatTree
import com.ohav.noted.data.CategoryEntity
import com.ohav.noted.data.NoteEntity
import com.ohav.noted.data.Share
import com.ohav.noted.design.NoteColor
import com.ohav.noted.settings.AppSettings
import com.ohav.noted.ui.home.fmtRel
import kotlinx.coroutines.flow.first

/**
 * The stack, like home: the notes behind leave their title strips on top (up to [STRIPS]), the
 * front note lies open under them. A strip → that note comes to the front; the front note →
 * the editor; its checklist rows tick in place. Which notes: picked when the widget is added
 * ([PickDeckActivity]) — every note, the pinned, the favourites or one category (with its
 * subcategories). Tapping the name picks again.
 */
class DeckWidget : GlanceAppWidget() {
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as NotedApp
        val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val notes0 = app.notes.notes.first()
        val cats0 = app.notes.categories.first()
        val set0 = app.settings.settings.first()
        provideContent {
            val notes by app.notes.notes.collectAsState(notes0)
            val cats by app.notes.categories.collectAsState(cats0)
            val settings by app.settings.settings.collectAsState(set0)
            val state = currentState<Preferences>()
            Deck(notes, cats, settings, state[SOURCE] ?: SRC_ALL, state[FRONT], widgetId)
        }
    }

    companion object {
        val SOURCE = stringPreferencesKey("source")
        val FRONT = stringPreferencesKey("front")
        const val SRC_ALL = "all"
        const val SRC_PINNED = "pinned"
        const val SRC_FAV = "fav"

        /** What [source] holds and what it's called; null = the category is gone. */
        fun pick(source: String, notes: List<NoteEntity>, cats: List<CategoryEntity>): Pair<String, List<NoteEntity>>? {
            val placed = notes.filter { it.categoryId.isNotEmpty() }
            return when (source) {
                SRC_ALL -> "כל הפתקים" to placed
                SRC_PINNED -> "נעוצים" to placed.filter { it.pinned }
                SRC_FAV -> "מועדפים" to placed.filter { it.favorite }
                else -> {
                    val tree = CatTree(cats)
                    val k = cats.firstOrNull { it.id == source } ?: return null
                    val family = tree.family(k.id)
                    k.name to placed.filter { it.categoryId in family }
                }
            }
        }
    }
}

class DeckWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DeckWidget()
}

private const val STRIPS = 3
private val PEEK = 38.dp

internal fun pickDeckIntent(context: Context, widgetId: Int) = Intent(context, PickDeckActivity::class.java)
    .setData(Uri.parse("noted-widget://deck/$widgetId"))
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)

@Composable
private fun Deck(notes: List<NoteEntity>, cats: List<CategoryEntity>, settings: AppSettings, source: String, front: String?, widgetId: Int) {
    val context = LocalContext.current
    val c = remember(settings.theme, settings.accent) { WColors(settings) }
    val picked = remember(notes, cats, source) { DeckWidget.pick(source, notes, cats) }
    val name = picked?.first ?: "כל הפתקים"
    val shown = picked?.second ?: DeckWidget.pick(DeckWidget.SRC_ALL, notes, cats)!!.second
    val cat = source.takeIf { picked != null && it != DeckWidget.SRC_ALL && it != DeckWidget.SRC_PINNED && it != DeckWidget.SRC_FAV }

    Column(GlanceModifier.fillMaxSize().background(c.bg.p).cornerRadius(28.dp).padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 10.dp)) {
        RRow(
            GlanceModifier.fillMaxWidth().padding(bottom = 8.dp),
            {
                RRow(
                    GlanceModifier.defaultWeight().clickable(actionStartActivity(pickDeckIntent(context, widgetId))),
                    { Spacer(GlanceModifier.width(4.dp)) },
                    {
                        val bmp = remember(name) { titleBitmap(context, name, 24f) }
                        Image(ImageProvider(bmp), contentDescription = "$name — נגיעה לבחירה אחרת", colorFilter = ColorFilter.tint(c.ink.p))
                    },
                    { Spacer(GlanceModifier.width(7.dp)) },
                    { RText("${shown.size}", c.ink3, 14f, weight = W.Medium, maxLines = 1) },
                    { Spacer(GlanceModifier.width(2.dp)) },
                    { Image(ImageProvider(R.drawable.wg_caret_down), null, GlanceModifier.size(14.dp), colorFilter = ColorFilter.tint(c.ink3.p)) },
                )
            },
            {
                Box(
                    GlanceModifier.size(40.dp).cornerRadius(20.dp).background(c.accentC.p)
                        .clickable(actionStartActivity(newNoteIntent(context, cat))),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(ImageProvider(R.drawable.wg_plus), "פתק חדש", GlanceModifier.size(20.dp), colorFilter = ColorFilter.tint(c.accentInk.p))
                }
            },
        )
        if (shown.isEmpty()) {
            DeckEmpty(c, source)
            return@Column
        }
        // the front note, then the ones after it (round the end) peek out above it
        val at = shown.indexOfFirst { it.id == front }.coerceAtLeast(0)
        val top = shown[at]
        val behind = (1..minOf(STRIPS, shown.size - 1)).map { shown[(at + it) % shown.size] }
        Box(GlanceModifier.fillMaxSize()) {
            behind.forEachIndexed { i, n ->
                Box(GlanceModifier.fillMaxSize().padding(top = PEEK * i)) { Strip(n, c) }
            }
            Box(GlanceModifier.fillMaxSize().padding(top = PEEK * behind.size)) { Front(top, c) }
        }
    }
}

/** A note behind: its paper and title, all that shows is the strip above the next card. */
@Composable
private fun Strip(n: NoteEntity, c: WColors) {
    Column(
        GlanceModifier.fillMaxSize().cornerRadius(22.dp).background(c.note(NoteColor.of(n.color)).p)
            .padding(start = 16.dp, end = 16.dp, top = 9.dp)
            .clickable(actionRunCallback<BringToFront>(actionParametersOf(NOTE to n.id))),
    ) {
        RRow(
            GlanceModifier.fillMaxWidth(),
            { RText(n.title.ifBlank { "ללא כותרת" }, c.noteInk, 14.5f, GlanceModifier.defaultWeight(), weight = W.Bold, maxLines = 1) },
            { if (n.pinned) Image(ImageProvider(R.drawable.wg_push_pin_fill), "נעוץ", GlanceModifier.size(13.dp), colorFilter = ColorFilter.tint(c.noteInk2.p)) },
        )
    }
}

/** The note on top, open: title in the display face, its reminder, every row (scrolls). */
@Composable
private fun Front(n: NoteEntity, c: WColors) {
    val context = LocalContext.current
    val blocks = remember(n.blocks) { Blocks.parse(n.blocks) }
    val canTick = remember(n.share) { Share.parse(n.share).role != Share.ROLE_VIEWER }
    val lines = blocks.filter { it.type != BlockType.Divider && (it.type !in TEXT || clean(it.text).isNotBlank()) }
    val open = actionStartActivity(openNoteIntent(context, n.id))
    Column(
        GlanceModifier.fillMaxSize().cornerRadius(22.dp).background(c.note(NoteColor.of(n.color)).p)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp),
    ) {
        RRow(
            GlanceModifier.fillMaxWidth().clickable(open),
            {
                val title = n.title.ifBlank { "ללא כותרת" }
                val bmp = remember(title) { titleBitmap(context, title, 22f) }
                Image(ImageProvider(bmp), title, colorFilter = ColorFilter.tint(c.noteInk.p))
            },
            { Spacer(GlanceModifier.defaultWeight()) },
            { if (n.pinned) Image(ImageProvider(R.drawable.wg_push_pin_fill), "נעוץ", GlanceModifier.size(14.dp), colorFilter = ColorFilter.tint(c.noteInk2.p)) },
        )
        n.reminderAt?.let { at ->
            RRow(
                GlanceModifier.fillMaxWidth().padding(top = 2.dp),
                { Image(ImageProvider(R.drawable.wg_bell), null, GlanceModifier.size(12.dp), colorFilter = ColorFilter.tint(c.noteInk2.p)) },
                { Spacer(GlanceModifier.width(4.dp)) },
                { RText(fmtRel(at), c.noteInk2, 12f, GlanceModifier.defaultWeight(), weight = W.Medium, maxLines = 1) },
            )
        }
        Spacer(GlanceModifier.height(6.dp))
        if (lines.isEmpty()) {
            Box(GlanceModifier.fillMaxSize().clickable(open)) {
                RText("ריק עדיין — נגיעה לכתיבה", c.noteInk2, 13.5f, GlanceModifier.fillMaxWidth())
            }
        } else {
            LazyColumn(GlanceModifier.fillMaxSize()) {
                items(lines, itemId = { it.id.hashCode().toLong() }) { b ->
                    val m = GlanceModifier.fillMaxWidth().padding(bottom = 5.dp)
                    Box(if (b.type == BlockType.Check && canTick) m else m.clickable(open)) { Line(n, b, c, canTick) }
                }
            }
        }
    }
}

@Composable
private fun DeckEmpty(c: WColors, source: String) {
    val context = LocalContext.current
    val (title, sub) = when (source) {
        DeckWidget.SRC_PINNED -> "אין נעוצים" to "נועצים פתק בלחיצה ארוכה עליו באפליקציה"
        DeckWidget.SRC_FAV -> "אין מועדפים" to "מסמנים כוכב בלחיצה ארוכה על פתק"
        else -> "הערימה ריקה" to "נגיעה כאן — והפתק הראשון נפתח"
    }
    Column(
        GlanceModifier.fillMaxSize().cornerRadius(22.dp).background(c.surface.p).padding(16.dp)
            .clickable(actionStartActivity(newNoteIntent(context, null))),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(GlanceModifier.size(48.dp).cornerRadius(17.dp).background(c.bg.p), contentAlignment = Alignment.Center) {
            Image(ImageProvider(R.drawable.wg_note_pencil), null, GlanceModifier.size(24.dp), colorFilter = ColorFilter.tint(c.ink2.p))
        }
        Spacer(GlanceModifier.height(10.dp))
        RText(title, c.ink, 15f, GlanceModifier.fillMaxWidth(), weight = W.Bold, center = true)
        Spacer(GlanceModifier.height(2.dp))
        RText(sub, c.ink2, 13f, GlanceModifier.fillMaxWidth(), center = true)
    }
}

/** A strip tapped: that note comes to the front. */
class BringToFront : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[NOTE] ?: return
        updateAppWidgetState(context, glanceId) { it[DeckWidget.FRONT] = id }
        DeckWidget().update(context, glanceId)
    }
}
