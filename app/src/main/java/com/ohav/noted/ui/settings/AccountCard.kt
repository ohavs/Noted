package com.ohav.noted.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ohav.noted.NotedApp
import com.ohav.noted.design.LocalOverlays
import com.ohav.noted.design.Motion
import com.ohav.noted.design.Noted
import com.ohav.noted.design.Ph
import com.ohav.noted.design.Shapes
import com.ohav.noted.design.Squircle
import com.ohav.noted.design.ToastState
import com.ohav.noted.design.VSpace
import com.ohav.noted.design.press
import com.ohav.noted.sync.Account
import com.ohav.noted.sync.Sync
import com.ohav.noted.ui.editor.findActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Account + sync: sign in with Google, see where sync stands, sign out. */
@Composable
fun AccountCard(app: NotedApp, toasts: ToastState) {
    val c = Noted.colors
    val user by app.account.user.collectAsState()
    val photo by app.account.photo.collectAsState()
    val status by app.sync.status.collectAsState()

    Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(Shapes.lg).background(c.surface)) {
        AnimatedContent(
            targetState = user,
            contentKey = { it?.uid },
            transitionSpec = { fadeIn(Motion.glide()) togetherWith fadeOut(Motion.glide()) },
            label = "account",
        ) { u ->
            if (u == null) SignedOut(app, toasts) else SignedIn(app, u, photo, status)
        }
    }
}

@Composable
private fun SignedOut(app: NotedApp, toasts: ToastState) {
    val c = Noted.colors
    val t = Noted.type
    val haptics = Noted.haptics
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val dim by animateFloatAsState(if (busy) .55f else 1f, Motion.glide(), label = "busy")

    Column(Modifier.padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(Squircle(16.dp)).background(c.accentSoft), contentAlignment = Alignment.Center) {
                Icon(Ph.CloudArrowUp, null, Modifier.size(26.dp), tint = c.accent)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("הפתקים שלך, בכל מכשיר", style = t.bodyStrong, color = c.ink)
                Text("גיבוי וסנכרון עם חשבון גוגל. בלי חשבון — הכל נשאר רק כאן.", style = t.caption, color = c.ink2)
            }
        }
        VSpace(16.dp)
        Row(
            Modifier.fillMaxWidth().height(52.dp).clip(Shapes.md).background(c.ink)
                .graphicsLayer { alpha = dim }
                .press(scale = .97f) {
                    if (busy) return@press
                    val act = ctx.findActivity() ?: return@press
                    busy = true
                    scope.launch {
                        when (val r = app.account.signIn(act)) {
                            Account.SignIn.Ok -> { haptics.confirm(); toasts.show("מחובר — מסנכרן את הפתקים", Ph.CloudCheck) }
                            Account.SignIn.Cancelled -> Unit
                            is Account.SignIn.Failed -> { haptics.reject(); toasts.show(r.message, Ph.WarningCircle) }
                        }
                        busy = false
                    }
                },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Ph.GoogleLogo, null, Modifier.size(20.dp), tint = c.bg)
            Spacer(Modifier.width(10.dp))
            Text(if (busy) "מתחבר…" else "המשך עם Google", style = t.button, color = c.bg)
        }
        if (!app.account.available) {
            VSpace(10.dp)
            Text("החיבור לענן עוד לא מוגדר בגרסה הזו", style = t.caption, color = c.ink3)
        }
    }
}

@Composable
private fun SignedIn(app: NotedApp, u: Account.User, photo: ImageBitmap?, st: Sync.Status) {
    val c = Noted.colors
    val t = Noted.type
    val haptics = Noted.haptics
    val overlays = LocalOverlays.current
    val scope = rememberCoroutineScope()
    // "לפני 2 דק׳" keeps counting while the screen is open
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(20_000); now = System.currentTimeMillis() } }

    Column(Modifier.padding(top = 18.dp, start = 18.dp, end = 18.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AccountAvatar(u.letter, photo, 52.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(u.name.ifBlank { u.first }, style = t.bodyStrong, color = c.ink, maxLines = 1)
                Text(u.email, style = t.caption, color = c.ink2, maxLines = 1)
            }
        }
        VSpace(14.dp)
        val (icon, text, tint) = describe(st, now)
        AnimatedContent(
            targetState = Triple(icon, text, tint),
            transitionSpec = { fadeIn(Motion.glide()) togetherWith fadeOut(Motion.glide()) },
            label = "syncStatus",
        ) { (i, s, k) ->
            Row(
                Modifier.fillMaxWidth().clip(Shapes.md).background(c.bg2).padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(i, null, Modifier.size(20.dp), tint = k)
                Spacer(Modifier.width(10.dp))
                Text(s, style = t.label, color = c.ink, modifier = Modifier.weight(1f))
            }
        }
        if (st.mediaOff) {
            VSpace(8.dp)
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(horizontal = 4.dp)) {
                Icon(Ph.Info, null, Modifier.padding(top = 1.dp).size(16.dp), tint = c.ink3)
                Spacer(Modifier.width(8.dp))
                Text("תמונות, הקלטות וציורים נשארים במכשיר עד שיוגדר אחסון בענן", style = t.caption, color = c.ink2)
            }
        }
        VSpace(6.dp)
        Row(
            Modifier.fillMaxWidth().clip(Shapes.md)
                .press(scale = .98f) {
                    scope.launch {
                        val ok = overlays.dialog.confirm(
                            title = "להתנתק מ-${u.first}?",
                            text = "הפתקים נשארים במכשיר ובענן. הסנכרון ייעצר עד שתתחבר שוב.",
                            icon = Ph.SignOut, confirm = "התנתקות", cancel = "להישאר מחובר",
                        )
                        if (ok) { haptics.confirm(); app.account.signOut() }
                    }
                }
                .padding(vertical = 14.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Ph.SignOut, null, Modifier.size(20.dp), tint = c.ink2)
            Spacer(Modifier.width(12.dp))
            Text("התנתקות", style = t.body, color = c.ink)
        }
        var deleting by remember { mutableStateOf(false) }
        Row(
            Modifier.fillMaxWidth().clip(Shapes.md)
                .press(scale = .98f, enabled = !deleting) {
                    scope.launch {
                        val ok = overlays.dialog.confirm(
                            title = "למחוק את החשבון?",
                            text = "כל מה שבענן נמחק לצמיתות: הפתקים, הקטגוריות, התזכורות, התמונות וההקלטות. מפתקים ששיתפו איתך יוצאים. הפתקים במכשיר הזה נשארים.",
                            icon = Ph.UserCircleMinus, danger = true, confirm = "מחיקה לצמיתות", cancel = "ביטול",
                        )
                        if (!ok) return@launch
                        deleting = true
                        val err = app.account.deleteAccount()
                        deleting = false
                        if (err == null) { haptics.confirm(); toasts.show("החשבון נמחק", Ph.CheckCircleFill) }
                        else { haptics.reject(); toasts.show(err, Ph.WarningCircle) }
                    }
                }
                .padding(vertical = 14.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Ph.UserCircleMinus, null, Modifier.size(20.dp), tint = c.danger)
            Spacer(Modifier.width(12.dp))
            Text(if (deleting) "מוחק…" else "מחיקת החשבון", style = t.body, color = c.danger)
        }
    }
}

@Composable
private fun describe(st: Sync.Status, now: Long): Triple<ImageVector, String, androidx.compose.ui.graphics.Color> {
    val c = Noted.colors
    val media = if (st.mediaPending > 0 && !st.mediaOff) " · ${st.mediaPending} קבצים בדרך" else ""
    return when (st.phase) {
        Sync.Phase.Off, Sync.Phase.Connecting -> Triple(Ph.Cloud, "מתחבר לענן…", c.ink2)
        Sync.Phase.Syncing -> Triple(Ph.CloudArrowUp, "מסנכרן…$media", c.accent)
        Sync.Phase.Synced -> Triple(Ph.CloudCheck, "מסונכרן · ${ago(st.at, now)}$media", c.accent)
        Sync.Phase.Offline -> Triple(Ph.CloudSlash, "אין חיבור — השינויים יעלו כשיחזור", c.ink2)
        Sync.Phase.Error -> Triple(Ph.WarningCircle, st.message ?: "הסנכרון נעצר", c.danger)
    }
}

private fun ago(at: Long, now: Long): String {
    val m = ((now - at) / 60_000).toInt()
    return when {
        at == 0L || m < 1 -> "עכשיו"
        m < 60 -> "לפני $m דק׳"
        else -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at))
    }
}

/** The account's Google photo in a circle, or its first letter on the warm gradient tile. */
@Composable
fun AccountAvatar(letter: String, photo: ImageBitmap?, size: Dp) {
    val c = Noted.colors
    Box(Modifier.size(size).clip(CircleShape).background(c.accentSoft), contentAlignment = Alignment.Center) {
        if (photo != null) Image(photo, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(letter, style = Noted.type.button.copy(fontFamily = com.ohav.noted.design.NotedFonts.display, fontSize = (size.value / 2.6f).sp), color = c.accent)
    }
}
