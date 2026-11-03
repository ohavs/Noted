package com.ohav.noted.ui.share

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ohav.noted.NotedApp
import com.ohav.noted.data.CatTree
import com.ohav.noted.data.Member
import com.ohav.noted.data.NoteEntity
import com.ohav.noted.data.Share
import com.ohav.noted.data.shareInfo
import com.ohav.noted.design.Motion
import com.ohav.noted.design.Noted
import com.ohav.noted.design.Overlays
import com.ohav.noted.design.Ph
import com.ohav.noted.design.SheetRow
import com.ohav.noted.design.Shapes
import com.ohav.noted.design.Squircle
import com.ohav.noted.design.ToastState
import com.ohav.noted.design.VSpace
import com.ohav.noted.design.press
import com.ohav.noted.sync.Sharing
import com.ohav.noted.ui.categories.CatIcons
import com.ohav.noted.ui.editor.findActivity
import com.ohav.noted.sync.Account
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

fun roleWord(role: String) = when (role) {
    Share.ROLE_OWNER, "" -> "בעלות"
    Share.ROLE_EDITOR -> "עריכה"
    else -> "צפייה"
}

/**
 * Who a note is shared with and how to bring someone in. The owner invites by link (editor
 * or viewer), changes roles and removes people; anyone else can place the note in one of
 * their categories or leave it. [onLeft] runs after leaving (the note is gone from here).
 */
fun openShareSheet(app: NotedApp, overlays: Overlays, toasts: ToastState, scope: CoroutineScope, noteId: String, onLeft: () -> Unit = {}) {
    overlays.sheet.show("שיתוף") { ShareBody(app, overlays, toasts, scope, noteId, onLeft) }
}

@Composable
private fun ShareBody(app: NotedApp, overlays: Overlays, toasts: ToastState, outer: CoroutineScope, noteId: String, onLeft: () -> Unit) {
    val user by app.account.user.collectAsState()
    val note by remember(noteId) { app.notes.notes.map { l -> l.firstOrNull { it.id == noteId } } }.collectAsState(null)
    var page by remember { mutableStateOf("main") }
    val u = user
    if (!app.account.available || u == null) {
        SignInPrompt(
            app, toasts, Ph.UsersThree, "כדי לשתף צריך חשבון",
            "מתחברים עם גוגל — והפתק נפתח לאנשים שתבחרו, מתעדכן אצל כולם בזמן אמת.",
        )
        return
    }
    val n = note ?: return
    val share = n.shareInfo
    if (page == "cats") {
        overlays.sheet.setTitle("הצגה בבית")
        CategoryPick(app, toasts, n) { overlays.sheet.setTitle("שיתוף"); page = "main" }
        return
    }
    val isOwner = share.owner.isEmpty() || share.owner == u.uid
    val members = share.members.ifEmpty { listOf(Member(u.uid, u.name, u.photoUrl, Share.ROLE_OWNER)) }

    val cats by app.notes.categories.collectAsState(emptyList())
    val folder = cats.firstOrNull { it.id == share.folder && share.folder.isNotEmpty() }
    if (folder != null) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 16.dp).clip(Shapes.md).background(Noted.colors.bg2).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(CatIcons.of(folder.icon), null, Modifier.size(22.dp), tint = Noted.colors.accent)
            Spacer(Modifier.width(12.dp))
            Text("בתיקייה המשותפת ״${folder.name}״ — כל מי שבה רואה את הפתק", style = Noted.type.sub, color = Noted.colors.ink2, modifier = Modifier.weight(1f))
        }
    }
    val viaMe = u.uid in share.via
    if (members.size > 1 || !isOwner) {
        Section("משתתפים · ${members.size}")
        var open by remember { mutableStateOf<String?>(null) }
        members.forEachIndexed { i, m ->
            MemberRow(
                m, me = m.uid == u.uid, manage = isOwner && m.uid != u.uid && m.uid !in share.via, open = open == m.uid,
                last = i == members.lastIndex, note = if (m.uid in share.via && folder != null) "דרך התיקייה" else null,
                onToggle = { open = if (open == m.uid) null else m.uid },
                onRole = { r ->
                    if (r != m.role) outer.launch {
                        when (val res = app.sharing.setRole(n.id, m.uid, r)) {
                            is Sharing.Result.Ok -> toasts.show("${m.first} · ${roleWord(r)}", if (r == Share.ROLE_VIEWER) Ph.Eye else Ph.PencilSimple)
                            is Sharing.Result.Failed -> toasts.show(res.message, Ph.WarningCircle)
                        }
                    }
                },
                onRemove = {
                    outer.launch {
                        val ok = overlays.dialog.confirm(
                            title = "להסיר את ${m.first} מהפתק?", text = "הפתק ייעלם אצלו/ה. אפשר להזמין שוב בקישור חדש.",
                            icon = Ph.UserMinus, danger = true, confirm = "הסרה", cancel = "השאר",
                        )
                        if (!ok) return@launch
                        when (val res = app.sharing.remove(n.id, m.uid)) {
                            is Sharing.Result.Ok -> toasts.show("${m.first} הוסר/ה מהפתק", Ph.UserMinus)
                            is Sharing.Result.Failed -> toasts.show(res.message, Ph.WarningCircle)
                        }
                    }
                },
            )
        }
        VSpace(22.dp)
    }

    if (isOwner) {
        InviteBlock(app, toasts, n, first = members.size <= 1)
    } else {
        if (share.readOnly) {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Ph.Eye, null, Modifier.size(18.dp), tint = Noted.colors.ink3)
                Spacer(Modifier.width(8.dp))
                Text("יש לך הרשאת צפייה — ${share.ownerMember?.first ?: "הבעלים"} יכול/ה לשנות אותה", style = Noted.type.caption, color = Noted.colors.ink3)
            }
        }
        val where = cats.firstOrNull { it.id == n.categoryId }?.name
        SheetRow(Ph.House, if (where != null) "בבית: $where" else "להציג גם בבית…", end = if (where != null) "שינוי" else null, last = viaMe) { page = "cats" }
        // brought in by a folder: leaving is the folder's business
        if (!viaMe) SheetRow(Ph.SignOut, "יציאה מהשיתוף", danger = true, last = true) {
            outer.launch {
                val ok = overlays.dialog.confirm(
                    title = "לצאת מהפתק?", text = "הפתק ייעלם אצלך, ואצל השאר יישאר כמו שהוא. כדי לחזור צריך הזמנה חדשה.",
                    icon = Ph.SignOut, danger = true, confirm = "יציאה", cancel = "השאר",
                )
                if (!ok) return@launch
                when (val res = app.sharing.leave(n.id)) {
                    is Sharing.Result.Ok -> { overlays.sheet.close(); onLeft(); toasts.show("יצאת מהפתק", Ph.SignOut) }
                    is Sharing.Result.Failed -> toasts.show(res.message, Ph.WarningCircle)
                }
            }
        }
    }
}

@Composable
internal fun Section(text: String) {
    Text(text, style = Noted.type.section, color = Noted.colors.ink3, modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
internal fun MemberRow(
    m: Member, me: Boolean, manage: Boolean, open: Boolean, last: Boolean,
    onToggle: () -> Unit, onRole: (String) -> Unit, onRemove: () -> Unit,
    removeLabel: String = "הסרה מהפתק", note: String? = null,
) {
    val c = Noted.colors
    val t = Noted.type
    val turn by animateFloatAsState(if (open) 1f else 0f, Motion.glide(), label = "caret")
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().then(if (manage) Modifier.press(scale = .98f, onClick = onToggle) else Modifier).padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Face(m, 42.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(m.name.ifBlank { "משתתף" } + if (me) " · אני" else "", style = t.bodyStrong, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(roleWord(m.role) + (note?.let { " · $it" } ?: ""), style = t.caption, color = c.ink2)
            }
            if (manage) Icon(Ph.CaretDown, null, Modifier.size(18.dp).graphicsLayer { rotationZ = turn * 180f }, tint = c.ink3)
        }
        AnimatedVisibility(open, enter = expandVertically(Motion.glide()) + fadeIn(Motion.glide()), exit = shrinkVertically(Motion.glide()) + fadeOut(Motion.glide())) {
            Column(Modifier.padding(bottom = 12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RoleTile(Ph.PencilSimple, "עריכה", "כותבים ומסמנים", m.role == Share.ROLE_EDITOR, Modifier.weight(1f)) { onRole(Share.ROLE_EDITOR) }
                    RoleTile(Ph.Eye, "צפייה", "רק רואים", m.role == Share.ROLE_VIEWER, Modifier.weight(1f)) { onRole(Share.ROLE_VIEWER) }
                }
                Row(
                    Modifier.padding(top = 12.dp).press(scale = .95f, onClick = onRemove).padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Ph.UserMinus, null, Modifier.size(18.dp), tint = c.danger)
                    Spacer(Modifier.width(8.dp))
                    Text(removeLabel, style = t.label, color = c.danger)
                }
            }
        }
        if (!last) Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
    }
}

@Composable
fun RoleTile(icon: ImageVector, title: String, sub: String, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Noted.colors
    val t = Noted.type
    val haptics = Noted.haptics
    val bg by animateColorAsState(if (on) c.accentSoft else c.bg2, Motion.glide(), label = "roleBg")
    val edge by animateColorAsState(if (on) c.accent else c.bg2, Motion.glide(), label = "roleEdge")
    Column(
        modifier
            .press(scale = .95f) { haptics.tick(); onClick() }
            .clip(Shapes.md)
            .background(bg)
            .border(2.dp, edge, Shapes.md)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = if (on) c.accent else c.ink2)
        VSpace(8.dp)
        Text(title, style = t.bodyStrong, color = c.ink)
        Text(sub, style = t.caption, color = c.ink2)
    }
}

@Composable
private fun InviteBlock(app: NotedApp, toasts: ToastState, n: NoteEntity, first: Boolean) {
    val title = n.title.ifBlank { "ללא כותרת" }
    InviteLinks(
        toasts, first,
        intro = "מי שמקבל את הקישור מצטרף לפתק ורואה כל שינוי בזמן אמת.",
        message = { url -> "הזמנה לפתק ״$title״ ב-noted\n$url" },
    ) { role -> app.sharing.invite(n.id, role) }
}

/** Editor / viewer, then send or copy a fresh invite link that [make] creates. */
@Composable
fun InviteLinks(
    toasts: ToastState, first: Boolean, intro: String, message: (String) -> String,
    make: suspend (String) -> Sharing.Result<String>,
) {
    val c = Noted.colors
    val t = Noted.type
    val ctx = LocalContext.current
    val haptics = Noted.haptics
    val scope = rememberCoroutineScope()
    var role by remember { mutableStateOf(Share.ROLE_EDITOR) }
    var busy by remember { mutableStateOf(false) }
    val dim by animateFloatAsState(if (busy) .55f else 1f, Motion.glide(), label = "busy")

    if (first) {
        Text(intro, style = t.sub, color = c.ink2, modifier = Modifier.padding(bottom = 16.dp))
    } else Section("הזמנה בקישור")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        RoleTile(Ph.PencilSimple, "עריכה", "כותבים ומסמנים", role == Share.ROLE_EDITOR, Modifier.weight(1f)) { role = Share.ROLE_EDITOR }
        RoleTile(Ph.Eye, "צפייה", "רק רואים", role == Share.ROLE_VIEWER, Modifier.weight(1f)) { role = Share.ROLE_VIEWER }
    }
    VSpace(14.dp)

    fun make(then: (String) -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            when (val r = make(role)) {
                is Sharing.Result.Ok -> then(r.value)
                is Sharing.Result.Failed -> { haptics.reject(); toasts.show(r.message, Ph.WarningCircle) }
            }
            busy = false
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).height(56.dp).graphicsLayer { alpha = dim }
                .press(scale = .96f) {
                    make { url ->
                        haptics.confirm()
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, message(url))
                        }
                        ctx.startActivity(Intent.createChooser(send, "שליחת הזמנה").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
                .clip(Shapes.md).background(c.inverse),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Ph.PaperPlaneTilt, null, Modifier.size(20.dp), tint = c.inverseInk)
            Spacer(Modifier.width(10.dp))
            Text(if (busy) "יוצר קישור…" else "שליחת קישור", style = t.button, color = c.inverseInk)
        }
        Box(
            Modifier.size(56.dp).graphicsLayer { alpha = dim }
                .press(scale = .9f) {
                    make { url ->
                        haptics.confirm()
                        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("noted", url))
                        toasts.show("הקישור הועתק", Ph.Copy)
                    }
                }
                .clip(Shapes.md).background(c.bg2),
            contentAlignment = Alignment.Center,
        ) { Icon(Ph.Copy, "העתקת קישור", Modifier.size(22.dp), tint = c.ink) }
    }
    Text(
        "הקישור בתוקף ${Sharing.INVITE_DAYS} יום. אפשר לשתף אותו בוואטסאפ, במייל או בכל מקום.",
        style = t.caption, color = c.ink3, modifier = Modifier.padding(top = 12.dp),
    )
}

/** A non-owner chooses where the shared note sits in their own home stack (or nowhere). */
@Composable
private fun CategoryPick(app: NotedApp, toasts: ToastState, n: NoteEntity, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val cats by app.notes.categories.collectAsState(emptyList())
    val tree = remember(cats) { CatTree(cats) }
    Text("הפתק תמיד בלשונית ״משותף״. אפשר להציג אותו גם בבית, בקטגוריה שלך — רק אצלך.", style = Noted.type.sub, color = Noted.colors.ink2, modifier = Modifier.padding(bottom = 10.dp))
    SheetRow(Ph.UsersThree, "רק במשותף", end = if (n.categoryId.isEmpty()) "✓" else null) {
        scope.launch { app.notes.move(listOf(n.id), "") }
        onDone()
    }
    tree.flat.forEachIndexed { i, k ->
        SheetRow(CatIcons.of(k.icon), k.name, end = if (n.categoryId == k.id) "✓" else null, inset = k.id in tree.parentOf, last = i == tree.flat.lastIndex) {
            scope.launch { app.notes.move(listOf(n.id), k.id) }
            toasts.show("יופיע בבית תחת ${k.name}", Ph.House)
            onDone()
        }
    }
}

/** "Sign in to …" with the Google button; the surrounding sheet reacts once signed in. */
@Composable
fun SignInPrompt(app: NotedApp, toasts: ToastState, icon: ImageVector, title: String, text: String) {
    val c = Noted.colors
    val t = Noted.type
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Box(Modifier.size(56.dp).clip(Squircle(18.dp)).background(c.accentSoft), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(28.dp), tint = c.accent)
        }
        VSpace(14.dp)
        Text(title, style = t.bodyStrong.copy(fontWeight = FontWeight.ExtraBold), color = c.ink)
        VSpace(4.dp)
        Text(text, style = t.sub, color = c.ink2)
        VSpace(18.dp)
        GoogleButton(app, toasts)
        if (!app.account.available) {
            VSpace(10.dp)
            Text("החיבור לענן עוד לא מוגדר בגרסה הזו", style = t.caption, color = c.ink3)
        }
    }
}

@Composable
fun GoogleButton(app: NotedApp, toasts: ToastState, modifier: Modifier = Modifier.fillMaxWidth()) {
    val c = Noted.colors
    val t = Noted.type
    val haptics = Noted.haptics
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val dim by animateFloatAsState(if (busy) .55f else 1f, Motion.glide(), label = "busy")
    Row(
        modifier.height(56.dp).graphicsLayer { alpha = dim }
            .press(scale = .97f) {
                if (busy) return@press
                val act = ctx.findActivity() ?: return@press
                busy = true
                scope.launch {
                    when (val r = app.account.signIn(act)) {
                        Account.SignIn.Ok -> haptics.confirm()
                        Account.SignIn.Cancelled -> Unit
                        is Account.SignIn.Failed -> { haptics.reject(); toasts.show(r.message, Ph.WarningCircle) }
                    }
                    busy = false
                }
            }
            .clip(Shapes.md).background(c.ink),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Ph.GoogleLogo, null, Modifier.size(20.dp), tint = c.bg)
        Spacer(Modifier.width(10.dp))
        Text(if (busy) "מתחבר…" else "המשך עם Google", style = t.button, color = c.bg)
    }
}
