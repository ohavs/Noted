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
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.RowScope
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
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
import com.ohav.noted.data.NoteEntity
import com.ohav.noted.data.Share
import com.ohav.noted.design.NoteColor
import com.ohav.noted.settings.AppSettings
import com.ohav.noted.ui.editor.Start
import com.ohav.noted.ui.home.fmtRel
import kotlinx.coroutines.flow.first

/**
 * One note on the home screen, whole: its paper colour, title in the display face, every row
 * (scrolls), checklist rows ticked in place. Which note: picked in [PickNoteActivity] when the widget is
 * added (and again from the launcher's "reconfigure", or by tapping an empty one).
 */
class NoteWidget : GlanceAppWidget() {
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as NotedApp
        val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val notes0 = app.notes.notes.first()
        val set0 = app.settings.settings.first()
        provideContent {
            val notes by app.notes.notes.collectAsState(notes0)
            val settings by app.settings.settings.collectAsState(set0)
            val chosen = currentState<Preferences>()[NOTE_ID]
            SingleNote(notes.firstOrNull { it.id == chosen }, chosen != null, settings, widgetId)
        }
    }

    companion object {
        val NOTE_ID = stringPreferencesKey("note")
    }
}

class NoteWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NoteWidget()
}

internal fun pickNoteIntent(context: Context, widgetId: Int) = Intent(context, PickNoteActivity::class.java)
    .setData(Uri.parse("noted-widget://pick/$widgetId"))
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)

@Composable
private fun SingleNote(n: NoteEntity?, wasChosen: Boolean, settings: AppSettings, widgetId: Int) {
    val context = LocalContext.current
    val c = remember(settings.theme, settings.accent) { WColors(settings) }
    if (n == null) {
        Unpicked(c, context, widgetId, gone = wasChosen)
        return
    }
    val blocks = remember(n.blocks) { Blocks.parse(n.blocks) }
    val canTick = remember(n.share) { Share.parse(n.share).role != Share.ROLE_VIEWER }
    val lines = blocks.filter { it.type != BlockType.Divider && (it.type !in TEXT || clean(it.text).isNotBlank()) }
    val open = actionStartActivity(openNoteIntent(context, n.id))
    Column(
        GlanceModifier.fillMaxSize().cornerRadius(28.dp).background(c.note(NoteColor.of(n.color)).p)
            .padding(start = 16.dp, end = 16.dp, top = 14.dp),
    ) {
        RRow(
            GlanceModifier.fillMaxWidth().clickable(open),
            {
                val title = n.title.ifBlank { "ללא כותרת" }
                val bmp = remember(title) { titleBitmap(context, title, 24f) }
                Image(ImageProvider(bmp), title, colorFilter = ColorFilter.tint(c.noteInk.p))
            },
            { Spacer(GlanceModifier.defaultWeight()) },
            { if (n.pinned) Image(ImageProvider(R.drawable.wg_push_pin_fill), "נעוץ", GlanceModifier.size(15.dp), colorFilter = ColorFilter.tint(c.noteInk2.p)) },
        )
        n.reminderAt?.let { at ->
            RRow(
                GlanceModifier.fillMaxWidth().padding(top = 2.dp),
                { Image(ImageProvider(R.drawable.wg_bell), null, GlanceModifier.size(12.dp), colorFilter = ColorFilter.tint(c.noteInk2.p)) },
                { Spacer(GlanceModifier.width(4.dp)) },
                { RText(fmtRel(at), c.noteInk2, 12f, GlanceModifier.defaultWeight(), weight = W.Medium, maxLines = 1) },
            )
        }
        Spacer(GlanceModifier.height(8.dp))
        if (lines.isEmpty()) {
            Box(GlanceModifier.fillMaxSize().clickable(open), contentAlignment = Alignment.TopEnd) {
                RText("ריק עדיין — נגיעה לכתיבה", c.noteInk2, 14f, GlanceModifier.fillMaxWidth())
            }
        } else {
            LazyColumn(GlanceModifier.fillMaxSize()) {
                items(lines, itemId = { it.id.hashCode().toLong() }) { b ->
                    // checklist rows tick themselves; any other row opens the note
                    val m = GlanceModifier.fillMaxWidth().padding(bottom = 5.dp)
                    Box(if (b.type == BlockType.Check && canTick) m else m.clickable(open)) { Line(n, b, c, canTick) }
                }
            }
        }
    }
}

@Composable
private fun Unpicked(c: WColors, context: Context, widgetId: Int, gone: Boolean) {
    Column(
        GlanceModifier.fillMaxSize().cornerRadius(28.dp).background(c.bg.p).padding(16.dp)
            .clickable(actionStartActivity(pickNoteIntent(context, widgetId))),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(GlanceModifier.size(48.dp).cornerRadius(17.dp).background(c.surface.p), contentAlignment = Alignment.Center) {
            Image(ImageProvider(R.drawable.wg_note_pencil), null, GlanceModifier.size(24.dp), colorFilter = ColorFilter.tint(c.ink2.p))
        }
        Spacer(GlanceModifier.height(10.dp))
        RText(if (gone) "הפתק כבר לא כאן" else "איזה פתק להציג?", c.ink, 15f, GlanceModifier.fillMaxWidth(), weight = W.Bold, center = true)
        Spacer(GlanceModifier.height(2.dp))
        RText("נגיעה לבחירת פתק", c.ink2, 13f, GlanceModifier.fillMaxWidth(), center = true)
    }
}

/**
 * Quick create: five doors into a new note — plain, a checklist, a voice recording, the camera,
 * a reminder. Each opens the editor on a fresh note already doing that.
 */
class QuickWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as NotedApp
        val set0 = app.settings.settings.first()
        provideContent {
            val settings by app.settings.settings.collectAsState(set0)
            Quick(settings)
        }
    }
}

class QuickWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickWidget()
}

private class Door(val mode: String, val icon: Int, val label: String)

private val DOORS = listOf(
    Door(Start.TEXT, R.drawable.wg_note_pencil, "פתק"),
    Door(Start.LIST, R.drawable.wg_check_square, "רשימה"),
    Door(Start.VOICE, R.drawable.wg_microphone, "הקלטה"),
    Door(Start.CAMERA, R.drawable.wg_camera, "צילום"),
    Door(Start.REMIND, R.drawable.wg_bell, "תזכורת"),
)

@Composable
private fun Quick(settings: AppSettings) {
    val context = LocalContext.current
    val c = remember(settings.theme, settings.accent) { WColors(settings) }
    val slots = buildList<@Composable RowScope.() -> Unit> {
        DOORS.forEachIndexed { i, d ->
            if (i > 0) add { Spacer(GlanceModifier.width(6.dp)) }
            add {
                // the plain note is the main door: accent; the rest sit on the surface
                val main = i == 0
                Column(
                    GlanceModifier.defaultWeight().fillMaxHeight().cornerRadius(20.dp)
                        .background(if (main) c.accentC.p else c.surface.p)
                        .clickable(actionStartActivity(newNoteIntent(context, null, d.mode))),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Image(ImageProvider(d.icon), d.label, GlanceModifier.size(22.dp), colorFilter = ColorFilter.tint(if (main) c.accentInk.p else c.ink.p))
                    Spacer(GlanceModifier.height(4.dp))
                    RText(d.label, if (main) c.accentInk else c.ink2, 11.5f, GlanceModifier.fillMaxWidth(), weight = W.Medium, maxLines = 1, center = true)
                }
            }
        }
    }
    RRow(GlanceModifier.fillMaxSize().cornerRadius(28.dp).background(c.bg.p).padding(7.dp), *slots.toTypedArray())
}
