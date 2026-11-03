package com.ohav.noted.ui.reminders

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ohav.noted.data.Blocks
import com.ohav.noted.data.Privacy
import com.ohav.noted.data.CategoryEntity
import com.ohav.noted.data.NoteEntity
import com.ohav.noted.design.NoteColor
import com.ohav.noted.design.Noted
import com.ohav.noted.design.Ph
import com.ohav.noted.design.Shapes
import com.ohav.noted.design.press
import com.ohav.noted.ui.common.EmptyArt
import com.ohav.noted.ui.common.EmptyState
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

private class AgendaItem(
    val at: LocalDateTime, val note: NoteEntity, val title: String, val sub: String,
    val line: Boolean, val repeats: Boolean,
)

/** How far ahead a repeating reminder is spelled out, occurrence by occurrence. */
private const val REPEAT_DAYS = 7L
private const val REPEAT_MAX = 7

/**
 * The "לוח" tab: every reminder (notes and single lines) on a timeline grouped by day, a "now"
 * line in today's group. Tapping an item opens its note from that item.
 */
@Composable
fun Agenda(notes: List<NoteEntity>, categories: List<CategoryEntity>, onOpen: (NoteEntity, Rect) -> Unit) {
    val reminders = LocalReminders.current
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = LocalDateTime.now()
        }
    }
    val zone = ZoneId.systemDefault()
    val today = now.toLocalDate()
    val flags by (LocalContext.current.applicationContext as com.ohav.noted.NotedApp).privacy.flags.collectAsStateWithLifecycle()
    val items = remember(reminders, notes, categories, today, flags) {
        val byId = notes.associateBy { it.id }
        val cats = categories.associateBy { it.id }
        buildList {
            reminders.values.forEach { r ->
                val n = byId[r.noteId] ?: return@forEach
                val line = if (r.blockId.isEmpty()) null else Blocks.parse(n.blocks).firstOrNull { it.id == r.blockId } ?: return@forEach
                val m = Privacy.mask(flags[n.id] ?: 0, n.title, line?.text)
                val title = m.head
                val rep = When.repeat(r.repeat)
                val sub = when {
                    m.locked -> if (line != null) "שורה ברשימה" else rep
                    line != null -> "שורה מתוך ״${m.note}״"
                    rep.isNotEmpty() -> rep
                    else -> cats[n.categoryId]?.name.orEmpty()
                }
                val first = Instant.ofEpochMilli(r.occAt).atZone(zone).toLocalDateTime()
                if (first.toLocalDate() >= today) add(AgendaItem(first, n, title, sub, line != null, r.repeat.repeats))
                if (r.repeat.repeats && !r.fired) {
                    var k = 0
                    var t0 = r.occTime
                    val horizon = today.plusDays(REPEAT_DAYS).atStartOfDay()
                    while (k < REPEAT_MAX) {
                        t0 = r.repeat.next(r.anchorTime, t0) ?: break
                        val local = t0.atZone(r.zoneId).withZoneSameInstant(zone).toLocalDateTime()
                        if (local >= horizon) break
                        add(AgendaItem(local, n, title, sub, line != null, true))
                        k++
                    }
                }
            }
        }.sortedBy { it.at }
    }

    if (items.isEmpty()) {
        EmptyState(
            EmptyArt.Agenda, "אין תזכורות קרובות",
            "תזכורות מכל הפתקים, ושורות מתוך רשימות, יופיעו כאן לפי יום.",
            Modifier.padding(top = 60.dp),
        )
        return
    }

    Column(Modifier.padding(horizontal = 16.dp)) {
        items.groupBy { it.at.toLocalDate() }.forEach { (day, list) ->
            DayHeader(day, today)
            var placedNow = day != today
            list.forEach { item ->
                if (!placedNow && item.at > now) { NowLine(now); placedNow = true }
                AgendaRow(item, past = item.at <= now, onOpen = onOpen)
            }
            if (!placedNow) NowLine(now)
        }
    }
}

@Composable
private fun DayHeader(day: LocalDate, today: LocalDate) {
    val c = Noted.colors
    val t = Noted.type
    val dd = ChronoUnit.DAYS.between(today, day)
    val label = when (dd) {
        0L -> "היום"
        1L -> "מחר"
        else -> "יום ${When.dayFull(day)}"
    }
    Row(Modifier.padding(top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, style = t.barTitle, color = c.ink)
        Text("${day.dayOfMonth} ב${When.MONTHS[day.monthValue - 1]}", style = t.label, color = c.ink3, modifier = Modifier.padding(bottom = 3.dp))
    }
}

@Composable
private fun NowLine(now: LocalDateTime) {
    val c = Noted.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("עכשיו ${When.hm(now)}", style = Noted.type.tiny, color = c.accent, modifier = Modifier.width(64.dp))
        Box(Modifier.size(9.dp).clip(CircleShape).background(c.accent))
        Box(Modifier.weight(1f).height(2.dp).background(c.accent))
    }
}

@Composable
private fun AgendaRow(it: AgendaItem, past: Boolean, onOpen: (NoteEntity, Rect) -> Unit) {
    val c = Noted.colors
    val t = Noted.type
    var rect by remember { mutableStateOf(Rect.Zero) }
    val paper = c.note(NoteColor.of(it.note.color))
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp).press(scale = .97f) { onOpen(it.note, rect) },
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            When.hm(it.at), style = t.label.copy(fontWeight = FontWeight.ExtraBold),
            color = if (past) c.ink3 else c.ink, modifier = Modifier.width(64.dp).padding(top = 16.dp),
        )
        Column(
            Modifier
                .weight(1f)
                .onGloballyPositioned { lc -> rect = lc.boundsInRoot() }
                .graphicsLayer { alpha = if (past) .6f else 1f }
                .clip(Shapes.md)
                .background(paper)
                .padding(horizontal = 16.dp, vertical = 13.dp),
        ) {
            Text(it.title, style = t.bodyStrong, color = c.noteInk, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (it.sub.isNotEmpty()) {
                Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(
                        when {
                            it.line -> Ph.CheckSquare
                            it.repeats -> Ph.Repeat
                            else -> Ph.Bell
                        },
                        null, Modifier.size(15.dp), tint = c.noteInk2,
                    )
                    Text(it.sub, style = t.meta, color = c.noteInk2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
