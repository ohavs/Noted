package com.ohav.noted.ui.share

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ohav.noted.NotedApp
import com.ohav.noted.data.Member
import com.ohav.noted.data.NoteEntity
import com.ohav.noted.data.Share
import com.ohav.noted.design.Btn
import com.ohav.noted.design.BtnKind
import com.ohav.noted.design.Motion
import com.ohav.noted.design.NoteColor
import com.ohav.noted.design.Noted
import com.ohav.noted.design.Overlays
import com.ohav.noted.design.Ph
import com.ohav.noted.design.Shapes
import com.ohav.noted.design.Squircle
import com.ohav.noted.design.ToastState
import com.ohav.noted.design.VSpace
import com.ohav.noted.design.press
import com.ohav.noted.sync.Sharing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** An invite link was opened: show whose note it is and join it. [onOpen] opens the note once it's here. */
fun openJoinSheet(app: NotedApp, overlays: Overlays, toasts: ToastState, token: String, onFolder: (String) -> Unit = {}, onOpen: (NoteEntity) -> Unit) {
    overlays.sheet.show("הזמנה") { JoinBody(app, overlays, toasts, token, onFolder, onOpen) }
}

private sealed interface Step {
    data object Loading : Step
    data class Ready(val inv: Sharing.Invite) : Step
    data class Failed(val message: String) : Step
}

@Composable
private fun JoinBody(app: NotedApp, overlays: Overlays, toasts: ToastState, token: String, onFolder: (String) -> Unit, onOpen: (NoteEntity) -> Unit) {
    val c = Noted.colors
    val t = Noted.type
    val haptics = Noted.haptics
    val scope = rememberCoroutineScope()
    val user by app.account.user.collectAsState()
    if (!app.account.available || user == null) {
        SignInPrompt(app, toasts, Ph.UsersThree, "מתחברים ומצטרפים", "כדי להצטרף לפתק משותף צריך חשבון גוגל. הפתקים שכבר יש לך נשארים כמו שהם.")
        return
    }
    var step by remember { mutableStateOf<Step>(Step.Loading) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(token, user?.uid) {
        step = when (val r = app.sharing.read(token)) {
            is Sharing.Result.Ok -> Step.Ready(r.value).also { overlays.sheet.setTitle(if (r.value.isFolder) "הזמנה לתיקייה" else "הזמנה לפתק") }
            is Sharing.Result.Failed -> Step.Failed(r.message)
        }
    }

    suspend fun arrive(id: String): NoteEntity? = withTimeoutOrNull(15_000) {
        var n = app.notes.get(id)
        while (n == null) { delay(300); n = app.notes.get(id) }
        n
    }

    suspend fun arriveFolder(id: String): Boolean = withTimeoutOrNull(15_000) {
        while (app.notes.categoriesNow().none { it.id == id }) delay(300)
        true
    } ?: false

    AnimatedContent(step, transitionSpec = { fadeIn(Motion.glide()) togetherWith fadeOut(Motion.glide()) }, label = "join") { s ->
        Column(Modifier.fillMaxWidth()) {
            when (s) {
                Step.Loading -> {
                    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(.45f, .9f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "p")
                    Box(Modifier.fillMaxWidth().height(150.dp).graphicsLayer { alpha = pulse }.clip(Shapes.card).background(c.bg2))
                    VSpace(16.dp)
                    Text("פותחים את ההזמנה…", style = t.sub, color = c.ink2)
                    VSpace(70.dp)
                }
                is Step.Failed -> {
                    Box(Modifier.size(56.dp).clip(Squircle(18.dp)).background(c.dangerSoft), contentAlignment = Alignment.Center) {
                        Icon(Ph.WarningCircle, null, Modifier.size(28.dp), tint = c.danger)
                    }
                    VSpace(14.dp)
                    Text(s.message, style = t.bodyStrong, color = c.ink)
                    VSpace(4.dp)
                    Text("אפשר לבקש ממי ששלח קישור חדש.", style = t.sub, color = c.ink2)
                    VSpace(20.dp)
                    Btn("סגירה", Modifier.fillMaxWidth(), kind = BtnKind.Dark) { overlays.sheet.close() }
                }
                is Step.Ready -> {
                    val inv = s.inv
                    val tint = if (inv.isFolder) c.bg2 else c.note(NoteColor.of(inv.color))
                    // the note itself, small: its colour, its title, who sent it (a folder: its icon and name)
                    Column(Modifier.fillMaxWidth().clip(Shapes.card).background(tint).padding(22.dp)) {
                        if (inv.isFolder) {
                            Box(Modifier.size(52.dp).clip(Squircle(16.dp)).background(c.accentSoft), contentAlignment = Alignment.Center) {
                                Icon(com.ohav.noted.ui.categories.CatIcons.of(inv.icon), null, Modifier.size(26.dp), tint = c.accent)
                            }
                            VSpace(14.dp)
                        }
                        Text(
                            inv.title.ifBlank { if (inv.isFolder) "תיקייה" else "ללא כותרת" }, style = t.cardTitle,
                            color = if (inv.isFolder) c.ink else c.noteInk, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        VSpace(18.dp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Face(Member("from:" + inv.from, inv.from, inv.fromPhoto, Share.ROLE_OWNER), 34.dp, ring = tint)
                            Spacer(Modifier.width(10.dp))
                            Text("הזמנה מ${inv.from.ifBlank { "חבר/ה" }}", style = t.label, color = if (inv.isFolder) c.ink2 else c.noteInk2, modifier = Modifier.weight(1f))
                        }
                    }
                    VSpace(16.dp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (inv.role == Share.ROLE_VIEWER) Ph.Eye else Ph.PencilSimple, null, Modifier.size(20.dp), tint = c.ink2)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            when {
                                inv.isFolder && inv.role == Share.ROLE_VIEWER -> "התיקייה תופיע אצלך בבית, עם כל הפתקים בה — לצפייה"
                                inv.isFolder -> "התיקייה תופיע אצלך בבית. אפשר לכתוב בפתקים שבה ולהוסיף חדשים — כולם רואים"
                                inv.role == Share.ROLE_VIEWER -> "אפשר יהיה לראות את הפתק ואת כל השינויים בו"
                                else -> "אפשר יהיה לכתוב, לסמן ולהוסיף — וכולם רואים בזמן אמת"
                            },
                            style = t.sub, color = c.ink2, modifier = Modifier.weight(1f),
                        )
                    }
                    if (inv.expired) {
                        VSpace(10.dp)
                        Text("הקישור הזה פג תוקף — אפשר לבקש קישור חדש.", style = t.caption, color = c.danger)
                    }
                    VSpace(22.dp)
                    val dim by animateFloatAsState(if (busy) .55f else 1f, Motion.glide(), label = "busy")
                    Row(
                        Modifier.fillMaxWidth().height(56.dp).graphicsLayer { alpha = dim }
                            .press(scale = .97f) {
                                if (busy) return@press
                                busy = true
                                scope.launch {
                                    when (val r = app.sharing.join(inv)) {
                                        is Sharing.Result.Ok -> if (inv.isFolder) {
                                            val here = arriveFolder(inv.folder)
                                            busy = false
                                            haptics.confirm()
                                            overlays.sheet.close()
                                            if (here) onFolder(inv.folder)
                                            toasts.show(
                                                if (!here) "הצטרפת — התיקייה תופיע בבית בעוד רגע" else if (r.value) "״${inv.title}״ בבית שלך" else "כבר בתיקייה",
                                                com.ohav.noted.ui.categories.CatIcons.of(inv.icon),
                                            )
                                        } else {
                                            val n = arrive(inv.note)
                                            busy = false
                                            if (n == null) { toasts.show("הצטרפת — הפתק יופיע במשותף בעוד רגע", Ph.UsersThree); overlays.sheet.close(); return@launch }
                                            haptics.confirm()
                                            overlays.sheet.close()
                                            delay(220)
                                            onOpen(n)
                                            if (r.value) toasts.show("הצטרפת לפתק · הוא מחכה במשותף", Ph.UsersThreeFill)
                                        }
                                        is Sharing.Result.Failed -> { busy = false; haptics.reject(); toasts.show(r.message, Ph.WarningCircle) }
                                    }
                                }
                            }
                            .clip(Shapes.md).background(c.inverse),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Ph.UserPlus, null, Modifier.size(20.dp), tint = c.inverseInk)
                        Spacer(Modifier.width(10.dp))
                        Text(if (busy) "מצטרפים…" else if (inv.isFolder) "הצטרפות לתיקייה" else "הצטרפות לפתק", style = t.button, color = c.inverseInk)
                    }
                }
            }
        }
    }
}
