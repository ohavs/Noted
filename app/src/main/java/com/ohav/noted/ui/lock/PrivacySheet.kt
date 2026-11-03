package com.ohav.noted.ui.lock

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.animation.core.animateFloatAsState
import com.ohav.noted.NotedApp
import com.ohav.noted.data.NoteEntity
import com.ohav.noted.data.Privacy
import com.ohav.noted.design.Motion
import com.ohav.noted.design.NoteColor
import com.ohav.noted.design.Noted
import com.ohav.noted.design.NotedSwitch
import com.ohav.noted.design.Overlays
import com.ohav.noted.design.Ph
import com.ohav.noted.design.Shapes
import com.ohav.noted.design.Squircle
import com.ohav.noted.design.ToastState
import com.ohav.noted.design.press

private enum class BlurPick(val label: String, val title: Boolean, val body: Boolean) {
    None("גלוי", false, false),
    Body("תוכן", false, true),
    Title("כותרת", true, false),
    Both("הכל", true, true),
}

/** Long-press → פרטיות: what the home stack blurs, and the lock. Applies as you pick. */
fun openPrivacySheet(app: NotedApp, overlays: Overlays, lock: LockState, toasts: ToastState, n: NoteEntity) {
    overlays.sheet.show("פרטיות") { PrivacyBody(app, lock, toasts, n) }
}

@Composable
private fun PrivacyBody(app: NotedApp, lock: LockState, toasts: ToastState, n: NoteEntity) {
    val c = Noted.colors
    val t = Noted.type
    val haptics = Noted.haptics
    val flags by app.privacy.flags.collectAsStateWithLifecycle()
    val f = flags[n.id] ?: 0
    val locked = f and Privacy.LOCK != 0
    val pick = BlurPick.entries.first { it.title == (f and Privacy.BLUR_TITLE != 0) && it.body == (f and Privacy.BLUR_BODY != 0) }
    val paper = c.note(NoteColor.of(n.color))

    Text("טשטוש בבית", style = t.section, color = c.ink2, modifier = Modifier.padding(bottom = 10.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        BlurPick.entries.forEach { b ->
            Mini(b, paper, selected = b == pick, Modifier.weight(1f)) {
                haptics.tick()
                app.privacy.setBlur(n.id, b.title, b.body)
            }
        }
    }
    Text(
        "נגיעה בפתק פורשת אותו וחושפת. נסגר — מיטשטש שוב.",
        style = t.caption, color = c.ink3, modifier = Modifier.padding(top = 10.dp, bottom = 18.dp),
    )
    Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
    Row(
        Modifier
            .fillMaxWidth()
            .press(scale = .98f, haptic = false) {
                if (!locked) {
                    if (app.privacy.hasCode) { haptics.toggle(true); app.privacy.setLocked(n.id, true) }
                    else lock.ask(null) { app.privacy.setLocked(n.id, true); toasts.show("הפתק נעול", Ph.LockFill) }
                } else lock.ask(n.title.ifBlank { null }) {
                    app.privacy.setLocked(n.id, false)
                    toasts.show("הנעילה הוסרה", Ph.LockSimpleOpen)
                }
            }
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(Squircle(15.dp)).background(if (locked) c.accent else c.bg2), contentAlignment = Alignment.Center) {
            Icon(if (locked) Ph.LockFill else Ph.Lock, null, Modifier.size(21.dp), tint = if (locked) c.accentInk else c.ink2)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("נעילה", style = t.bodyStrong, color = c.ink)
            Text(
                if (app.privacy.biometricAvailable()) "קוד אחד לכל הפתקים הנעולים, או טביעת אצבע" else "קוד אחד לכל הפתקים הנעולים",
                style = t.caption, color = c.ink2,
            )
        }
        NotedSwitch(locked)
    }
    Text(
        "רק במכשיר הזה. פתק נעול מופיע בבית בלי תוכן, ננעל שוב כשיוצאים ממנו, ולא נמצא בחיפוש לפי תוכן.",
        style = t.caption, color = c.ink3, modifier = Modifier.padding(bottom = 8.dp),
    )
}

/** A tiny card showing exactly what this choice hides. */
@Composable
private fun Mini(b: BlurPick, paper: Color, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Noted.colors
    val t = Noted.type
    val ring by animateFloatAsState(if (selected) 1f else 0f, Motion.touch(), label = "miniRing")
    Column(modifier.press(scale = .93f, haptic = false, onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(92.dp)
                .drawBehind {
                    if (ring > .01f) {
                        val o = Squircle(20.dp).createOutline(size, layoutDirection, this)
                        scale(.94f + .06f * ring) {
                            drawOutline(o, c.ink.copy(alpha = ring.coerceIn(0f, 1f)), style = Stroke(2.5.dp.toPx()))
                        }
                    }
                }
                .padding(5.dp)
                .clip(Squircle(16.dp))
                .background(paper)
                .padding(start = 10.dp, end = 10.dp, top = 10.dp),
        ) {
            Column {
                Text(
                    "רשימה", style = t.label.copy(fontSize = 13.sp), color = c.noteInk, maxLines = 1, overflow = TextOverflow.Clip,
                    modifier = Modifier.veil(paper, 4.dp) { if (b.title) 1f else 0f },
                )
                Column(
                    Modifier.padding(top = 7.dp).veil(paper, 3.dp) { if (b.body) 1f else 0f },
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    listOf(1f, .72f, .86f).forEach { w ->
                        Box(Modifier.fillMaxWidth(w).height(5.dp).clip(RoundedCornerShape(3.dp)).background(c.noteInk.copy(alpha = .55f)))
                    }
                }
            }
            if (selected) {
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(bottom = 7.dp).size(18.dp).clip(CircleShape).background(c.noteInk),
                    contentAlignment = Alignment.Center,
                ) { Icon(Ph.Check, null, Modifier.size(11.dp), tint = paper) }
            }
        }
        Text(
            b.label, style = t.caption, color = if (selected) c.ink else c.ink2,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
