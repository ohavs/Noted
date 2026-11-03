package com.ohav.noted.ui.share

import com.ohav.noted.ui.lock.veil
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ohav.noted.NotedApp
import com.ohav.noted.data.BlockType
import com.ohav.noted.data.Blocks
import com.ohav.noted.data.CategoryEntity
import com.ohav.noted.data.NoteEntity
import com.ohav.noted.data.Share
import com.ohav.noted.data.shareInfo
import com.ohav.noted.design.Squircle
import com.ohav.noted.ui.categories.CatIcons
import com.ohav.noted.design.Btn
import com.ohav.noted.design.BtnKind
import com.ohav.noted.design.LocalOverlays
import com.ohav.noted.design.NoteColor
import com.ohav.noted.design.Noted
import com.ohav.noted.design.Ph
import com.ohav.noted.design.Shapes
import com.ohav.noted.design.ToastState
import com.ohav.noted.design.press
import com.ohav.noted.ui.common.EmptyArt
import com.ohav.noted.ui.common.EmptyState
import kotlinx.coroutines.launch

/**
 * The "shared" tab: notes others shared with me, and mine that others are on. Tapping one opens
 * the editor from it; a new shared note starts here too.
 */
@Composable
fun SharedScreen(
    app: NotedApp, toasts: ToastState, notes: List<NoteEntity>, cats: List<CategoryEntity>,
    onFolder: (String) -> Unit, onOpen: (NoteEntity, Rect?, Boolean) -> Unit,
) {
    val c = Noted.colors
    val t = Noted.type
    val scope = rememberCoroutineScope()
    val overlays = LocalOverlays.current
    val user by app.account.user.collectAsState()
    // notes in a shared folder are found through the folder
    val withMe = remember(notes) { notes.filter { it.shareInfo.let { s -> s.withMe && s.folder.isEmpty() } } }
    val mine = remember(notes) { notes.filter { it.shareInfo.let { s -> s.shared && !s.withMe && s.folder.isEmpty() } } }
    val folders = remember(cats) { cats.filter { it.share.isNotEmpty() && it.parent.isEmpty() }.sortedBy { it.position } }

    fun newShared() {
        scope.launch {
            val cat = app.notes.categoriesFirst() ?: "home"
            val colors = listOf(NoteColor.Mint, NoteColor.Sky, NoteColor.Butter, NoteColor.Peach)
            val n = app.notes.create(cat, colors[(notes.size) % colors.size])
            onOpen(n, null, true)
            toasts.show("כשהפתק מוכן — מזמינים אליו", Ph.UsersThree, action = "הזמנה", millis = 6000) {
                openShareSheet(app, overlays, toasts, app.scope, n.id)
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Box(Modifier.statusBarsPadding().padding(top = 14.dp).height(64.dp))
        Text("משותף", style = t.cat, color = c.ink, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 4.dp))
        val u = user
        when {
            !app.account.available || u == null -> EmptyState(
                EmptyArt.Shared, "שיתוף מתחיל בחשבון",
                "מתחברים עם גוגל, ואז כל פתק יכול להיות משותף — רשימת קניות, טיול, משימות בבית.",
                Modifier.padding(top = 40.dp),
            ) { GoogleButton(app, toasts, Modifier.padding(horizontal = 24.dp).fillMaxWidth()) }
            withMe.isEmpty() && mine.isEmpty() && folders.isEmpty() -> EmptyState(
                EmptyArt.Shared, "עוד לא שיתפת כלום",
                "פותחים פתק ולוחצים על סמל השיתוף למעלה — או מתחילים כאן פתק משותף חדש.",
                Modifier.padding(top = 40.dp),
            ) { Btn("פתק משותף חדש", icon = Ph.Plus, kind = BtnKind.Dark) { newShared() } }
            else -> {
                if (folders.isNotEmpty()) {
                    Label("תיקיות")
                    folders.forEach { k -> FolderRow(k, notes.count { it.categoryId == k.id || cats.any { s -> s.parent == k.id && s.id == it.categoryId } }, u.uid) { onFolder(k.id) } }
                }
                if (withMe.isNotEmpty()) {
                    Label("שיתפו איתי")
                    withMe.forEach { n -> SharedRow(n, u.uid, onOpen) }
                }
                if (mine.isNotEmpty()) {
                    Label("שיתפתי")
                    mine.forEach { n -> SharedRow(n, u.uid, onOpen) }
                }
                Row(
                    Modifier.padding(start = 16.dp, top = 8.dp).press(scale = .95f) { newShared() }.padding(vertical = 10.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Ph.Plus, null, Modifier.size(20.dp), tint = c.accent)
                    Spacer(Modifier.width(8.dp))
                    Text("פתק משותף חדש", style = t.label, color = c.accent)
                }
            }
        }
        Box(Modifier.height(140.dp))
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = Noted.type.section, color = Noted.colors.ink3, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 10.dp))
}

@Composable
private fun SharedRow(n: NoteEntity, me: String, onOpen: (NoteEntity, Rect?, Boolean) -> Unit) {
    val c = Noted.colors
    val t = Noted.type
    val haptics = Noted.haptics
    val share = n.shareInfo
    val tint = c.note(NoteColor.of(n.color))
    var rect by remember { mutableStateOf<Rect?>(null) }
    val preview = remember(n.blocks) {
        val bs = Blocks.parse(n.blocks)
        val checks = bs.filter { it.type == BlockType.Check }
        if (checks.isNotEmpty()) "${checks.count { it.done }} מתוך ${checks.size} סומנו"
        else bs.firstOrNull { it.text.isNotBlank() }?.text?.trim()?.replace('\n', ' ').orEmpty()
    }
    Column(
        Modifier
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .fillMaxWidth()
            .onGloballyPositioned { rect = it.boundsInRoot() }
            .press(scale = .97f) { haptics.light(); onOpen(n, rect, false) }
            .clip(Shapes.lg)
            .background(tint)
            .padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                val pv = com.ohav.noted.ui.lock.LocalPrivacy.current
                Text(
                    n.title.ifBlank { "ללא כותרת" }, style = t.noteTitle, color = c.noteInk, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = if (pv.blurTitle(n.id)) Modifier.veil(tint, 12.dp) { 1f } else Modifier,
                )
                if (pv.locked(n.id)) Text("נעול · נגיעה לפתיחה", style = t.meta, color = c.noteInk2, modifier = Modifier.padding(top = 6.dp))
                else if (preview.isNotEmpty()) Text(preview, style = t.snippet, color = c.noteInk2, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp).then(if (pv.blurBody(n.id)) Modifier.veil(tint, 9.dp) { 1f } else Modifier))
            }
            Spacer(Modifier.width(12.dp))
            FacePile(share.members.filter { it.uid != me }.ifEmpty { share.members }, 30.dp, ring = tint)
        }
        val owner = share.ownerMember
        if (share.withMe || share.readOnly) {
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(if (share.readOnly) Ph.Eye else Ph.PencilSimple, null, Modifier.size(15.dp), tint = c.noteInk2)
                Text(
                    (if (share.readOnly) "צפייה" else "עריכה") + (owner?.let { " · של ${it.first}" } ?: ""),
                    style = t.meta, color = c.noteInk2,
                )
            }
        }
    }
}

@Composable
private fun FolderRow(k: CategoryEntity, count: Int, me: String, onClick: () -> Unit) {
    val c = Noted.colors
    val t = Noted.type
    val haptics = Noted.haptics
    val share = Share.parse(k.share)
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .fillMaxWidth()
            .press(scale = .97f) { haptics.light(); onClick() }
            .clip(Shapes.lg)
            .background(c.bg2)
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(46.dp).clip(Squircle(15.dp)).background(c.accentSoft), contentAlignment = Alignment.Center) {
            Icon(CatIcons.of(k.icon), null, Modifier.size(24.dp), tint = c.accent)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(k.name, style = t.noteTitle, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val whose = if (share.withMe) share.ownerMember?.first?.let { "של $it" } ?: "משותפת איתי" else "שיתפתי"
            val many = when (count) { 0 -> "ריקה"; 1 -> "פתק אחד"; else -> "$count פתקים" }
            Text("$many · $whose" + if (share.readOnly) " · צפייה" else "", style = t.meta, color = c.ink2, maxLines = 1)
        }
        Spacer(Modifier.width(12.dp))
        FacePile(share.members.filter { it.uid != me }.ifEmpty { share.members }, 30.dp, ring = c.bg2)
    }
}
