package com.ohav.noted.ui.bin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ohav.noted.NotedApp
import com.ohav.noted.data.Block
import com.ohav.noted.data.BlockType
import com.ohav.noted.data.Blocks
import com.ohav.noted.data.CategoryEntity
import com.ohav.noted.data.MediaExtra
import com.ohav.noted.data.NoteEntity
import com.ohav.noted.design.IconButton
import com.ohav.noted.design.LocalOverlays
import com.ohav.noted.design.Motion
import com.ohav.noted.design.NoteColor
import com.ohav.noted.design.Noted
import com.ohav.noted.design.Overlays
import com.ohav.noted.design.Ph
import com.ohav.noted.design.SheetRow
import com.ohav.noted.design.Shapes
import com.ohav.noted.design.ToastState
import com.ohav.noted.design.VSpace
import com.ohav.noted.design.press
import com.ohav.noted.sync.Bin
import com.ohav.noted.ui.common.EmptyArt
import com.ohav.noted.ui.common.EmptyState
import com.ohav.noted.ui.lock.LocalPrivacy
import com.ohav.noted.ui.lock.PrivacyView
import kotlinx.coroutines.launch

enum class Shelf { Archive, Trash }

/**
 * The archive (notes put away from home without deleting them; reminders keep working) and
 * the recycle bin (30 days, then gone). Each note is a small paper card; a tap opens a sheet
 * with a peek at its content and what can be done with it.
 */
@Composable
fun ShelfScreen(app: NotedApp, toasts: ToastState, shelf: Shelf, onBack: () -> Unit) {
    val c = Noted.colors
    val t = Noted.type
    val overlays = LocalOverlays.current
    val haptics = Noted.haptics
    val scope = rememberCoroutineScope()
    val trash = shelf == Shelf.Trash
    val notes by (if (trash) app.notes.trashed else app.notes.archived).collectAsStateWithLifecycle(initialValue = null)
    val cats by app.notes.categories.collectAsStateWithLifecycle(initialValue = emptyList())
    val catOf = remember(cats) { cats.associateBy { it.id } }
    val privacy = LocalPrivacy.current
    val list = notes

    fun empty(all: List<NoteEntity>) = scope.launch {
        val ok = overlays.dialog.confirm(
            if (all.size == 1) "למחוק לצמיתות פתק אחד?" else "למחוק לצמיתות ${all.size} פתקים?",
            "אי אפשר לשחזר אותם אחר כך.", Ph.Trash, danger = true, confirm = "מחיקה לצמיתות",
        )
        if (!ok) return@launch
        haptics.confirm()
        app.bin.purge(all.map { it.id })
        toasts.show("הסל רוקן", Ph.Trash)
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(Ph.CaretStart, "חזרה", onClick = onBack)
            Spacer(Modifier.weight(1f))
            if (trash && !list.isNullOrEmpty()) {
                Text(
                    "לרוקן", style = t.label, color = c.danger,
                    modifier = Modifier.padding(end = 8.dp).clip(Shapes.sm).press(scale = .92f) { empty(list) }
                        .background(c.dangerSoft).padding(horizontal = 14.dp, vertical = 9.dp),
                )
            }
        }
        Text(if (trash) "סל מחזור" else "ארכיון", style = t.hero, color = c.ink, modifier = Modifier.padding(horizontal = 20.dp))
        Text(
            if (trash) "פתקים נמחקים לגמרי ${Bin.DAYS} יום אחרי שהגיעו לכאן." else "פתקים שהוצאו מהבית בלי למחוק. התזכורות שלהם ממשיכות לעבוד.",
            style = t.caption, color = c.ink3, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp),
        )
        VSpace(14.dp)
        when {
            list == null -> Unit
            list.isEmpty() -> EmptyState(
                if (trash) EmptyArt.Bin else EmptyArt.Archive,
                if (trash) "הסל ריק" else "אין כאן כלום",
                if (trash) "מה שתמחקו יחכה כאן ${Bin.DAYS} יום, למקרה שתתחרטו." else "לחיצה ארוכה על פתק ← ארכיון, והוא יחכה כאן בשקט.",
                Modifier.padding(top = 40.dp),
            )
            else -> LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(list, key = { it.id }) { n ->
                    Paper(
                        n, privacy,
                        meta = if (trash) leftLabel(n.trashedAt ?: 0L) else catOf[n.categoryId]?.name.orEmpty(),
                        metaIcon = if (trash) Ph.ClockCountdown else Ph.FolderSimple,
                        modifier = Modifier.animateItem(fadeInSpec = Motion.glide(), placementSpec = Motion.glide(IntOffset(1, 1)), fadeOutSpec = Motion.glide()),
                    ) { if (trash) trashSheet(app, overlays, toasts, n, privacy) else archiveSheet(app, overlays, toasts, n, privacy, catOf) }
                }
                item(key = "end") { Box(Modifier.navigationBarsPadding()) }
            }
        }
    }
}

private fun leftLabel(trashedAt: Long): String = when (val d = Bin.daysLeft(trashedAt)) {
    0 -> "נמחק היום"
    1 -> "עוד יום אחד"
    2 -> "עוד יומיים"
    else -> "עוד $d ימים"
}

/** A note as a small paper card: title, a few lines, and one quiet line of what matters here. */
@Composable
private fun Paper(n: NoteEntity, privacy: PrivacyView, meta: String, metaIcon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) {
    val c = Noted.colors
    val t = Noted.type
    val locked = privacy.locked(n.id)
    val lines = remember(n.blocks) { preview(Blocks.parse(n.blocks), 3) }
    Column(
        modifier.fillMaxWidth().press(scale = .97f, onClick = onClick).clip(Shapes.lg)
            .background(c.note(NoteColor.of(n.color))).padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Text(
            if (locked || privacy.blurTitle(n.id)) "••••••" else n.title.ifBlank { "ללא כותרת" },
            style = t.cardTitle, color = c.noteInk, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        when {
            locked -> Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Ph.Lock, null, Modifier.size(15.dp), tint = c.noteInk2)
                Text("  פתק נעול", style = t.meta, color = c.noteInk2)
            }
            privacy.blurBody(n.id) -> Text("•••• ••• •••••", style = t.snippet, color = c.noteInk2, modifier = Modifier.padding(top = 4.dp))
            lines.isNotEmpty() -> Text(
                lines.joinToString("\n"), style = t.snippet, color = c.noteInk2, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (meta.isNotEmpty()) {
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(metaIcon, null, Modifier.size(15.dp), tint = c.noteInk2)
                Text("  $meta", style = t.meta, color = c.noteInk2)
            }
        }
    }
}

/** What a note says, line by line: checks as ○/✓, media by name. */
private fun preview(blocks: List<Block>, max: Int): List<String> = blocks.mapNotNull { b ->
    when (b.type) {
        BlockType.Check -> b.text.trim().ifEmpty { null }?.let { (if (b.done) "✓ " else "○ ") + it }
        BlockType.Bullet -> b.text.trim().ifEmpty { null }?.let { "• $it" }
        BlockType.Divider -> null
        BlockType.Images -> MediaExtra.pics(b.extra).size.takeIf { it > 0 }?.let { if (it == 1) "תמונה" else "$it תמונות" }
        BlockType.Voice -> "הקלטה"
        BlockType.Draw -> "ציור"
        BlockType.Link -> MediaExtra.link(b.extra)?.let { it.title.ifBlank { it.url } }
        else -> b.text.trim().ifEmpty { null }
    }
}.take(max)

@Composable
private fun Peek(n: NoteEntity, privacy: PrivacyView) {
    val c = Noted.colors
    val t = Noted.type
    if (privacy.locked(n.id) || privacy.blurBody(n.id)) return
    val lines = remember(n.blocks) { preview(Blocks.parse(n.blocks), 8) }
    if (lines.isEmpty()) return
    Text(
        lines.joinToString("\n"), style = t.snippet, color = c.noteInk2, maxLines = 8, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().clip(Shapes.md).background(c.note(NoteColor.of(n.color))).padding(16.dp),
    )
    VSpace(14.dp)
}

private fun titleOf(n: NoteEntity, privacy: PrivacyView) =
    if (privacy.locked(n.id) || privacy.blurTitle(n.id)) "פתק" else n.title.ifBlank { "ללא כותרת" }

private fun trashSheet(app: NotedApp, overlays: Overlays, toasts: ToastState, n: NoteEntity, privacy: PrivacyView) {
    overlays.sheet.show(titleOf(n, privacy)) {
        Peek(n, privacy)
        SheetRow(Ph.ArrowCounterClockwise, "שחזור", end = if (n.archived) "לארכיון" else "לבית") {
            overlays.sheet.close()
            app.scope.launch {
                app.notes.restore(listOf(n.id))
                toasts.show("״${titleOf(n, privacy)}״ שוחזר", Ph.ArrowCounterClockwise, action = "ביטול") {
                    app.scope.launch { app.notes.trash(listOf(n.id)) }
                }
            }
        }
        SheetRow(Ph.Trash, "מחיקה לצמיתות", danger = true, last = true) {
            app.scope.launch {
                val ok = overlays.dialog.confirm(
                    "למחוק לצמיתות?", "״${titleOf(n, privacy)}״ לא יחזור — גם לא מהענן.", Ph.Trash, danger = true, confirm = "מחיקה לצמיתות",
                )
                if (!ok) return@launch
                overlays.sheet.close()
                app.bin.purge(listOf(n.id))
                toasts.show("נמחק לצמיתות", Ph.Trash)
            }
        }
    }
}

private fun archiveSheet(app: NotedApp, overlays: Overlays, toasts: ToastState, n: NoteEntity, privacy: PrivacyView, cats: Map<String, CategoryEntity>) {
    overlays.sheet.show(titleOf(n, privacy)) {
        Peek(n, privacy)
        SheetRow(Ph.ArrowCounterClockwise, "החזרה לבית", end = cats[n.categoryId]?.name) {
            overlays.sheet.close()
            app.scope.launch {
                app.notes.archive(listOf(n.id), false)
                toasts.show("חזר לבית", Ph.ArrowCounterClockwise, action = "ביטול") {
                    app.scope.launch { app.notes.archive(listOf(n.id)) }
                }
            }
        }
        SheetRow(Ph.Trash, "העברה לסל", danger = true, last = true) {
            overlays.sheet.close()
            app.scope.launch {
                app.notes.trash(listOf(n.id))
                toasts.show("הועבר לסל", Ph.Trash, action = "ביטול") {
                    app.scope.launch { app.notes.restore(listOf(n.id)) }
                }
            }
        }
    }
}
