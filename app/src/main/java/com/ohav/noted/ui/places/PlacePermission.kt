package com.ohav.noted.ui.places

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.ohav.noted.design.Btn
import com.ohav.noted.design.BtnKind
import com.ohav.noted.design.Noted
import com.ohav.noted.design.Ph
import com.ohav.noted.design.Shapes
import com.ohav.noted.design.Squircle
import com.ohav.noted.places.Geo
import com.ohav.noted.reminders.Notifier
import com.ohav.noted.ui.reminders.PermRow

/** Whether place reminders can ring right now (all four of the card's rows). */
fun placesReady(ctx: android.content.Context) = Geo.ready(ctx) && Geo.locationOn(ctx) && Notifier.permitted(ctx)

/**
 * What a place reminder needs from the phone. "Allow all the time" can't be granted from a dialog
 * since Android 11 — only on the app's location page — so the card says exactly where, instead
 * of the reminder silently never ringing. Rows re-check when we come back from settings.
 */
@Composable
fun PlacePermissionCard(saved: Boolean, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val c = Noted.colors
    val t = Noted.type
    val haptics = Noted.haptics
    var tick by remember { mutableIntStateOf(0) }
    var askedFine by remember { mutableStateOf(false) }
    var askedBg by remember { mutableStateOf(false) }
    var askedNotify by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val fine = remember(tick) { Geo.hasFine(ctx) }
    val always = remember(tick) { Geo.hasBackground(ctx) && Geo.hasFine(ctx) }
    val on = remember(tick) { Geo.locationOn(ctx) }
    val notify = remember(tick) { Notifier.permitted(ctx) }
    val all = fine && always && on && notify
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val one = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    fun appSettings() = runCatching {
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    Column {
        Box(Modifier.size(74.dp).clip(Squircle(24.dp)).background(c.accentSoft), contentAlignment = Alignment.Center) {
            Icon(Ph.MapPinFill, null, Modifier.size(30.dp), tint = c.accent)
        }
        Spacer(Modifier.height(16.dp))
        Text("שהתזכורת תקפוץ במקום", style = t.title.copy(fontSize = t.title.fontSize * (27f / 30f)), color = c.ink)
        Spacer(Modifier.height(6.dp))
        Text(
            (if (saved) "התזכורת נשמרה. " else "") +
                "הטלפון הוא ששם לב שהגעת — גם כשהאפליקציה סגורה. בשביל זה הוא צריך:",
            style = t.body, color = c.ink2,
        )
        Spacer(Modifier.height(20.dp))
        PermRow(Ph.Crosshair, "מיקום מדויק", if (fine || !Geo.hasCoarse(ctx)) "כדי לדעת מתי עוברים את הסף" else "בחרת ״משוער״ — הוא לא מספיק לגדר", fine) {
            if (!askedFine) {
                askedFine = true
                launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            } else appSettings()
        }
        Spacer(Modifier.height(10.dp))
        PermRow(
            Ph.NavigationArrow, "לאפשר תמיד",
            if (Build.VERSION.SDK_INT >= 30) "בהגדרות: הרשאות ← מיקום ← ״לאפשר כל הזמן״" else "גם כשהאפליקציה ברקע",
            always,
        ) {
            if (!fine) { haptics.reject(); return@PermRow }
            // on 11+ this request opens the app's location page itself; once refused it does nothing
            if (Build.VERSION.SDK_INT >= 29 && !askedBg) {
                askedBg = true
                one.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            } else appSettings()
        }
        Spacer(Modifier.height(10.dp))
        if (!on) {
            PermRow(Ph.MapPin, "המיקום בטלפון", "כבוי — בלעדיו אין גדרות בכלל", false) {
                runCatching { ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            Spacer(Modifier.height(10.dp))
        }
        PermRow(Ph.Bell, "התראות", "בלעדיהן הטלפון יזהה שהגעת — ולא יגיד", notify) {
            if (Build.VERSION.SDK_INT >= 33 && !askedNotify) {
                askedNotify = true
                one.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else runCatching {
                ctx.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
        if (!always && fine) {
            Text(
                "בלי ״לאפשר כל הזמן״ התזכורת נשמרת, אבל לא תקפוץ.",
                style = t.caption, color = c.danger, modifier = Modifier.padding(top = 12.dp, start = 4.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        Btn(
            if (all) "מעולה, סיימנו" else "אחר כך", Modifier.fillMaxWidth(),
            kind = if (all) BtnKind.Dark else BtnKind.Quiet,
        ) {
            if (all) haptics.confirm()
            onDone()
        }
    }
}
