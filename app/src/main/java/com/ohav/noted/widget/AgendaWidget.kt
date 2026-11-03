package com.ohav.noted.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
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
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
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
import com.ohav.noted.NotedApp
import com.ohav.noted.R
import com.ohav.noted.data.Blocks
import com.ohav.noted.data.NoteEntity
import com.ohav.noted.data.PlaceEntity
import com.ohav.noted.data.PlaceReminderEntity
import com.ohav.noted.data.ReminderEntity
import com.ohav.noted.design.NoteColor
import com.ohav.noted.reminders.Notifier
import com.ohav.noted.settings.AppSettings
import com.ohav.noted.ui.editor.Start
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * What's coming: time reminders for the next seven days, grouped today / tomorrow / this week,
 * then the place reminders still waiting. Each row: ✓ (done; a series skips to its next time),
 * a time row also "an hour later", and a tap opens the note. + → a new note with a reminder.
 */
class AgendaWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as NotedApp
        val notes0 = app.notes.notes.first()
        val rem0 = app.reminders.all.first()
        val pr0 = app.places.reminders.first()
        val pl0 = app.places.places.first()
        val set0 = app.settings.settings.first()
        provideContent {
            val notes by app.notes.notes.collectAsState(notes0)
            val rems by app.reminders.all.collectAsState(rem0)
            val prs by app.places.reminders.collectAsState(pr0)
            val pls by app.places.places.collectAsState(pl0)
            val settings by app.settings.settings.collectAsState(set0)
            Agenda(agenda(notes, rems, prs, pls), settings)
        }
    }
}

class AgendaWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AgendaWidget()
}

private sealed interface AgRow {
    val key: Long
}

private class Head(val text: String) : AgRow {
    override val key = ("h:$text").hashCode().toLong()
}

private class Item(
    val id: String, val note: NoteEntity, val text: String, val sub: String,
    /** Time rows: the clock label; place rows: null. */
    val time: String?, val soon: Boolean, val repeats: Boolean,
    val leave: Boolean = false,
) : AgRow {
    override val key = ("i:$id").hashCode().toLong()
}

private val DAY = listOf("א׳", "ב׳", "ג׳", "ד׳", "ה׳", "ו׳", "ש׳")

private fun agenda(notes: List<NoteEntity>, rems: List<ReminderEntity>, prs: List<PlaceReminderEntity>, pls: List<PlaceEntity>): List<AgRow> {
    val byId = notes.associateBy { it.id }
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val end = today.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
    fun what(n: NoteEntity, blockId: String): Pair<String, String>? {
        val blocks = Blocks.parse(n.blocks)
        if (blockId.isEmpty()) return n.title.ifBlank { Notifier.summary(blocks) } to ""
        val line = blocks.firstOrNull { it.id == blockId } ?: return null
        return clean(line.text).ifBlank { "שורה" } to n.title
    }
    val timed = rems.mapNotNull { r ->
        val n = byId[r.noteId] ?: return@mapNotNull null
        val at = r.dueAt?.takeIf { it < end } ?: return@mapNotNull null
        val (text, sub) = what(n, r.blockId) ?: return@mapNotNull null
        Triple(at, r, Pair(n, text to sub))
    }.sortedBy { it.first }
    val out = ArrayList<AgRow>()
    var group: String? = null
    for ((at, r, ns) in timed) {
        val d = Instant.ofEpochMilli(at).atZone(zone)
        val days = java.time.temporal.ChronoUnit.DAYS.between(today, d.toLocalDate())
        val g = when { days <= 0 -> "היום"; days == 1L -> "מחר"; else -> "השבוע" }
        if (g != group) { out += Head(g); group = g }
        val hm = "%02d:%02d".format(d.hour, d.minute)
        val label = if (days >= 2) "${DAY[d.dayOfWeek.value % 7]} $hm" else hm
        out += Item(r.id, ns.first, ns.second.first, ns.second.second, label, soon = days <= 0, repeats = r.repeat.repeats)
    }
    val places = pls.associateBy { it.id }
    val waiting = prs.mapNotNull { r ->
        val n = byId[r.noteId] ?: return@mapNotNull null
        val p = places[r.placeId] ?: return@mapNotNull null
        val (text, sub) = what(n, r.blockId) ?: return@mapNotNull null
        Item(r.id, n, text, (if (r.leave) "ביציאה מ" else "בהגעה ל") + p.name + if (sub.isNotEmpty()) " · $sub" else "", null, false, r.always, r.leave)
    }
    if (waiting.isNotEmpty()) { out += Head("במקומות"); out += waiting }
    return out
}

@Composable
private fun Agenda(rows: List<AgRow>, settings: AppSettings) {
    val context = LocalContext.current
    val c = remember(settings.theme, settings.accent) { WColors(settings) }
    val count = rows.count { it is Item }
    Column(GlanceModifier.fillMaxSize().background(c.bg.p).cornerRadius(28.dp).padding(start = 12.dp, end = 12.dp, top = 10.dp)) {
        RRow(
            GlanceModifier.fillMaxWidth().padding(bottom = 6.dp),
            {
                RRow(
                    GlanceModifier.defaultWeight(),
                    { Spacer(GlanceModifier.width(4.dp)) },
                    {
                        val bmp = remember { titleBitmap(context, "קרוב", 26f) }
                        Image(ImageProvider(bmp), "תזכורות קרובות", colorFilter = ColorFilter.tint(c.ink.p))
                    },
                    { Spacer(GlanceModifier.width(8.dp)) },
                    { RText(if (count == 0) "" else "$count", c.ink3, 14f, weight = W.Medium, maxLines = 1) },
                )
            },
            {
                Box(
                    GlanceModifier.size(40.dp).cornerRadius(20.dp).background(c.accentC.p)
                        .clickable(actionStartActivity(newNoteIntent(context, null, Start.REMIND))),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(ImageProvider(R.drawable.wg_plus), "תזכורת חדשה", GlanceModifier.size(20.dp), colorFilter = ColorFilter.tint(c.accentInk.p))
                }
            },
        )
        if (rows.isEmpty()) {
            Column(
                GlanceModifier.fillMaxSize().padding(bottom = 14.dp).clickable(actionStartActivity(newNoteIntent(context, null, Start.REMIND))),
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(GlanceModifier.size(48.dp).cornerRadius(17.dp).background(c.surface.p), contentAlignment = Alignment.Center) {
                    Image(ImageProvider(R.drawable.wg_bell), null, GlanceModifier.size(24.dp), colorFilter = ColorFilter.tint(c.ink2.p))
                }
                Spacer(GlanceModifier.height(10.dp))
                RText("שבוע שקט", c.ink, 15f, GlanceModifier.fillMaxWidth(), weight = W.Bold, center = true)
                Spacer(GlanceModifier.height(2.dp))
                RText("אין תזכורות בשבעת הימים הקרובים", c.ink2, 13f, GlanceModifier.fillMaxWidth(), center = true)
            }
            return@Column
        }
        LazyColumn(GlanceModifier.fillMaxSize()) {
            items(rows, itemId = { it.key }) { r ->
                when (r) {
                    is Head -> RText(r.text, c.ink2, 12.5f, GlanceModifier.fillMaxWidth().padding(top = 6.dp, bottom = 5.dp), weight = W.Bold, maxLines = 1)
                    is Item -> Box(GlanceModifier.fillMaxWidth().padding(bottom = 6.dp)) { AgendaRow(r, c) }
                }
            }
        }
    }
}

@Composable
private fun AgendaRow(r: Item, c: WColors) {
    val context = LocalContext.current
    val paper = c.note(NoteColor.of(r.note.color))
    val place = r.time == null
    RRow(
        GlanceModifier.fillMaxWidth().cornerRadius(18.dp).background(paper.p).padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp)
            .clickable(actionStartActivity(openNoteIntent(context, r.note.id))),
        {
            // when: the time (today's in ink, bold), or the place's sign
            Box(GlanceModifier.width(50.dp), contentAlignment = Alignment.Center) {
                if (place) {
                    Image(
                        ImageProvider(if (r.leave) R.drawable.wg_sign_out else R.drawable.wg_map_pin), null,
                        GlanceModifier.size(20.dp), colorFilter = ColorFilter.tint(c.noteInk.p),
                    )
                } else {
                    RText(r.time.orEmpty(), c.noteInk, if (r.time.orEmpty().length > 5) 12f else 15f, GlanceModifier.fillMaxWidth(), weight = if (r.soon) W.Bold else W.Medium, maxLines = 1, center = true)
                }
            }
        },
        { Spacer(GlanceModifier.width(6.dp)) },
        {
            Column(GlanceModifier.defaultWeight()) {
                RText(r.text, c.noteInk, 14f, GlanceModifier.fillMaxWidth(), weight = W.Medium, maxLines = 2)
                val sub = buildString {
                    if (r.repeats) append(if (place) "בכל פעם" else "חוזרת")
                    if (r.sub.isNotEmpty()) { if (isNotEmpty()) append(" · "); append(r.sub) }
                }
                if (sub.isNotEmpty()) RText(sub, c.noteInk2, 12f, GlanceModifier.fillMaxWidth(), maxLines = 1)
            }
        },
        {
            if (!place) {
                Box(
                    GlanceModifier.size(36.dp).cornerRadius(18.dp)
                        .clickable(actionRunCallback<LaterHour>(actionParametersOf(RID to r.id))),
                    contentAlignment = Alignment.Center,
                ) { Image(ImageProvider(R.drawable.wg_clock), "עוד שעה", GlanceModifier.size(19.dp), colorFilter = ColorFilter.tint(c.noteInk2.p)) }
            }
        },
        {
            Box(
                GlanceModifier.size(36.dp).cornerRadius(18.dp).background(c.noteInk.p)
                    .clickable(actionRunCallback<DoneReminder>(actionParametersOf(RID to r.id, PLACE to place))),
                contentAlignment = Alignment.Center,
            ) { Image(ImageProvider(R.drawable.wg_check), if (r.repeats && !place) "בוצע — לפעם הבאה" else "בוצע", GlanceModifier.size(18.dp), colorFilter = ColorFilter.tint(paper.p)) }
        },
    )
}

private val RID = ActionParameters.Key<String>("rid")
private val PLACE = ActionParameters.Key<Boolean>("place")

/** ✓: a time reminder is done (a series moves to its next time); a place reminder goes away. */
class DoneReminder : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val app = context.applicationContext as NotedApp
        val id = parameters[RID] ?: return
        if (parameters[PLACE] == true) app.places.done(id) else app.reminders.skip(id)
        app.reminders.schedule()
        NotedWidget.refresh(context)
    }
}

/** The clock: rings an hour later. */
class LaterHour : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val app = context.applicationContext as NotedApp
        val id = parameters[RID] ?: return
        app.reminders.later(id, 60 * 60_000L)
        app.reminders.schedule()
        NotedWidget.refresh(context)
    }
}
