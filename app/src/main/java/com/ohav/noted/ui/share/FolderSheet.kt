package com.ohav.noted.ui.share

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ohav.noted.NotedApp
import com.ohav.noted.data.CatTree
import com.ohav.noted.data.Member
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Sharing a whole category: everyone on it sees every note in it (and in its subcategories),
 * and the notes they add there are shared back. The owner invites by link, manages roles and
 * can stop sharing; anyone else sees who's on it and can leave.
 */
fun openFolderSheet(app: NotedApp, overlays: Overlays, toasts: ToastState, scope: CoroutineScope, catId: String) {
    overlays.sheet.show("שיתוף התיקייה") { FolderBody(app, overlays, toasts, scope, catId) }
}

@Composable
private fun FolderBody(app: NotedApp, overlays: Overlays, toasts: ToastState, outer: CoroutineScope, catId: String) {
    val c = Noted.colors
    val t = Noted.type
    val haptics = Noted.haptics
    val user by app.account.user.collectAsState()
    val cat by remember(catId) { app.notes.categories.map { l -> l.firstOrNull { it.id == catId } } }.collectAsState(null)
    val cats by app.notes.categories.collectAsState(emptyList())
    val u = user
    if (!app.account.available || u == null) {
        SignInPrompt(
            app, toasts, Ph.UsersThree, "כדי לשתף צריך חשבון",
            "מתחברים עם גוגל — והתיקייה נפתחת לאנשים שתבחרו, עם כל הפתקים שבה.",
        )
        return
    }
    val k = cat ?: return
    val share = k.shareInfo
    val isOwner = share.owner.isEmpty() || share.owner == u.uid
    val subs = remember(cats, catId) { CatTree(cats).children[catId].orEmpty().size }

    if (k.share.isEmpty()) {
        // not shared yet: what it means, then one button
        var busy by remember { mutableStateOf(false) }
        val dim by animateFloatAsState(if (busy) .55f else 1f, Motion.glide(), label = "busy")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).clip(Squircle(18.dp)).background(c.accentSoft), contentAlignment = Alignment.Center) {
                Icon(CatIcons.of(k.icon), null, Modifier.size(28.dp), tint = c.accent)
            }
            Spacer(Modifier.width(14.dp))
            Text("״${k.name}״ לכולם", style = t.bodyStrong.copy(fontWeight = FontWeight.ExtraBold), color = c.ink)
        }
        VSpace(14.dp)
        Point(Ph.Notepad, "כל פתק בתיקייה" + (if (subs > 0) " ובתתי-הקטגוריות שלה" else "") + " מופיע אצל מי שמצטרף — ומתעדכן בזמן אמת")
        Point(Ph.PencilSimple, "מי שמקבל הרשאת עריכה יכול לכתוב בפתקים ולהוסיף חדשים")
        Point(Ph.Bell, "כשמישהו משנה משהו — מגיעה התראה (מקובצת, לא על כל אות)")
        VSpace(18.dp)
        Row(
            Modifier.fillMaxWidth().height(56.dp).graphicsLayer { alpha = dim }
                .press(scale = .97f) {
                    if (busy) return@press
                    busy = true
                    outer.launch {
                        when (val r = app.sharing.shareFolder(k)) {
                            is Sharing.Result.Ok -> haptics.confirm()
                            is Sharing.Result.Failed -> { haptics.reject(); toasts.show(r.message, Ph.WarningCircle) }
                        }
                        busy = false
                    }
                }
                .clip(Shapes.md).background(c.inverse),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Ph.UsersThree, null, Modifier.size(20.dp), tint = c.inverseInk)
            Spacer(Modifier.width(10.dp))
            Text(if (busy) "פותחים לשיתוף…" else "לשתף את התיקייה", style = t.button, color = c.inverseInk)
        }
        VSpace(8.dp)
        return
    }

    val members = share.members.ifEmpty { listOf(Member(u.uid, u.name, u.photoUrl, Share.ROLE_OWNER)) }
    if (members.size > 1 || !isOwner) {
        Section("משתתפים · ${members.size}")
        var open by remember { mutableStateOf<String?>(null) }
        members.forEachIndexed { i, m ->
            MemberRow(
                m, me = m.uid == u.uid, manage = isOwner && m.uid != u.uid, open = open == m.uid,
                last = i == members.lastIndex, removeLabel = "הסרה מהתיקייה",
                onToggle = { open = if (open == m.uid) null else m.uid },
                onRole = { r ->
                    if (r != m.role) outer.launch {
                        when (val res = app.sharing.setRole(k.id, m.uid, r, folder = true)) {
                            is Sharing.Result.Ok -> toasts.show("${m.first} · ${roleWord(r)}", if (r == Share.ROLE_VIEWER) Ph.Eye else Ph.PencilSimple)
                            is Sharing.Result.Failed -> toasts.show(res.message, Ph.WarningCircle)
                        }
                    }
                },
                onRemove = {
                    outer.launch {
                        val ok = overlays.dialog.confirm(
                            title = "להסיר את ${m.first} מהתיקייה?", text = "הפתקים שבה ייעלמו אצלו/ה. מה שהוא/היא כתב/ה בה נשאר אצלך.",
                            icon = Ph.UserMinus, danger = true, confirm = "הסרה", cancel = "השאר",
                        )
                        if (!ok) return@launch
                        when (val res = app.sharing.remove(k.id, m.uid, folder = true)) {
                            is Sharing.Result.Ok -> toasts.show("${m.first} הוסר/ה מהתיקייה", Ph.UserMinus)
                            is Sharing.Result.Failed -> toasts.show(res.message, Ph.WarningCircle)
                        }
                    }
                },
            )
        }
        VSpace(22.dp)
    }

    if (isOwner) {
        InviteLinks(
            toasts, first = members.size <= 1,
            intro = "מי שמקבל את הקישור מצטרף לתיקייה ורואה את כל הפתקים בה.",
            message = { url -> "הזמנה לתיקייה ״${k.name}״ ב-noted\n$url" },
        ) { role -> app.sharing.folderInvite(k, role) }
        VSpace(18.dp)
        SheetRow(Ph.LinkBreak, "הפסקת השיתוף", danger = true, last = true) {
            outer.launch {
                val ok = overlays.dialog.confirm(
                    title = "להפסיק לשתף את ״${k.name}״?",
                    text = "התיקייה תיעלם אצל כל השאר. הפתקים שלך נשארים, ופתקים שאחרים כתבו בה יישארו אצלם.",
                    icon = Ph.LinkBreak, danger = true, confirm = "הפסקה", cancel = "השאר",
                )
                if (!ok) return@launch
                when (val r = app.sharing.unshareFolder(k.id)) {
                    is Sharing.Result.Ok -> { overlays.sheet.close(); haptics.confirm(); toasts.show("״${k.name}״ כבר לא משותפת", Ph.LinkBreak) }
                    is Sharing.Result.Failed -> toasts.show(r.message, Ph.WarningCircle)
                }
            }
        }
    } else {
        if (share.readOnly) {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Ph.Eye, null, Modifier.size(18.dp), tint = c.ink3)
                Spacer(Modifier.width(8.dp))
                Text("יש לך הרשאת צפייה — ${share.ownerMember?.first ?: "הבעלים"} יכול/ה לשנות אותה", style = t.caption, color = c.ink3)
            }
        }
        SheetRow(Ph.SignOut, "יציאה מהתיקייה", danger = true, last = true) { leaveFolder(app, overlays, toasts, outer, k.id, k.name) }
    }
}

/** Leave someone else's folder, after asking. */
fun leaveFolder(app: NotedApp, overlays: Overlays, toasts: ToastState, scope: CoroutineScope, id: String, name: String) {
    scope.launch {
        val ok = overlays.dialog.confirm(
            title = "לצאת מ״$name״?",
            text = "הפתקים של אחרים בתיקייה ייעלמו אצלך. פתקים שאת/ה כתבת בה עוברים לקטגוריה שלך.",
            icon = Ph.SignOut, danger = true, confirm = "יציאה", cancel = "השאר",
        )
        if (!ok) return@launch
        when (val r = app.sharing.leaveFolder(id)) {
            is Sharing.Result.Ok -> { overlays.sheet.close(); toasts.show("יצאת מ״$name״", Ph.SignOut) }
            is Sharing.Result.Failed -> toasts.show(r.message, Ph.WarningCircle)
        }
    }
}

@Composable
private fun Point(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, Modifier.size(20.dp).padding(top = 1.dp), tint = Noted.colors.ink2)
        Spacer(Modifier.width(12.dp))
        Text(text, style = Noted.type.sub, color = Noted.colors.ink2, modifier = Modifier.weight(1f))
    }
}
