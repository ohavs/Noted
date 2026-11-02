package com.ohav.noted.ui.reminders

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.ohav.noted.design.Btn
import com.ohav.noted.design.BtnKind
import com.ohav.noted.design.Motion
import com.ohav.noted.design.Noted
import com.ohav.noted.design.Ph
import com.ohav.noted.design.Shapes
import com.ohav.noted.design.Squircle
import com.ohav.noted.design.press
import com.ohav.noted.reminders.Alarm
import com.ohav.noted.reminders.Notifier

/**
 * After the first reminder: what the phone needs so it rings on time. Each row turns into a
 * check when granted (re-checked when we come back from system settings).
 */
@Composable
fun PermissionCard(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val c = Noted.colors
    val t = Noted.type
    val haptics = Noted.haptics
    var tick by remember { mutableIntStateOf(0) }
    var asked by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val notify = remember(tick) { Notifier.permitted(ctx) }
    val exact = remember(tick) { Alarm.canExact(ctx) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    fun openNotifySettings() = runCatching {
        ctx.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    Column {
        Box(Modifier.size(74.dp).clip(Squircle(24.dp)).background(c.accentSoft), contentAlignment = Alignment.Center) {
            Icon(Ph.BellRingingFill, null, Modifier.size(30.dp), tint = c.accent)
        }
        Spacer(Modifier.height(16.dp))
        Text("שהתזכורת תצלצל בזמן", style = t.title.copy(fontSize = t.title.fontSize * (27f / 30f)), color = c.ink)
        Spacer(Modifier.height(6.dp))
        Text("התזכורת נשמרה. עוד שני דברים קטנים בטלפון, ואז היא תקפוץ בדיוק בדקה שבחרת.", style = t.body, color = c.ink2)
        Spacer(Modifier.height(20.dp))
        PermRow(
            Ph.Bell, "התראות", "כדי שהתזכורת תופיע במסך", notify,
        ) {
            if (Build.VERSION.SDK_INT >= 33 && !asked) {
                asked = true
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else openNotifySettings()
        }
        Spacer(Modifier.height(10.dp))
        PermRow(
            Ph.Clock, "בזמן המדויק", "בלי זה היא עלולה לאחר בכמה דקות", exact,
        ) {
            if (Build.VERSION.SDK_INT >= 31) runCatching {
                ctx.startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Btn(
            if (notify && exact) "מעולה, סיימנו" else "אחר כך", Modifier.fillMaxWidth(),
            kind = if (notify && exact) BtnKind.Dark else BtnKind.Quiet,
        ) {
            if (notify && exact) haptics.confirm()
            onDone()
        }
    }
}

@Composable
internal fun PermRow(icon: ImageVector, title: String, sub: String, granted: Boolean, onGrant: () -> Unit) {
    val c = Noted.colors
    val t = Noted.type
    val bg by animateColorAsState(if (granted) c.accentSoft else c.bg, Motion.glide(), label = "permBg")
    Row(
        Modifier.fillMaxWidth().clip(Shapes.md).background(bg).padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = c.ink)
        Column(Modifier.weight(1f)) {
            Text(title, style = t.bodyStrong, color = c.ink)
            Text(sub, style = t.caption, color = c.ink2)
        }
        AnimatedContent(
            granted,
            transitionSpec = { (fadeIn(Motion.glide()) + scaleIn(Motion.touch(), initialScale = .6f)) togetherWith fadeOut(Motion.glide()) },
            label = "perm",
        ) { ok ->
            if (ok) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    Icon(Ph.CheckCircleFill, "אושר", Modifier.size(28.dp), tint = c.accent)
                }
            } else {
                Box(
                    Modifier.height(44.dp).press(scale = .93f, onClick = onGrant).clip(Shapes.sm).background(c.inverse).padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("לאפשר", style = t.label, color = c.inverseInk) }
            }
        }
    }
}
