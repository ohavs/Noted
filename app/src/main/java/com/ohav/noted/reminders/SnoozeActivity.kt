package com.ohav.noted.reminders

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ohav.noted.NotedApp
import com.ohav.noted.design.Btn
import com.ohav.noted.design.BtnKind
import com.ohav.noted.design.Motion
import com.ohav.noted.design.Noted
import com.ohav.noted.design.NotedTheme
import com.ohav.noted.design.Ph
import com.ohav.noted.design.Shadows
import com.ohav.noted.design.Shapes
import com.ohav.noted.design.Squircle
import com.ohav.noted.design.press
import com.ohav.noted.design.softShadow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/** "Snooze" from a notification: a small sheet over whatever is on screen, three choices. */
class SnoozeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as NotedApp
        val id = intent.getStringExtra(ReminderReceiver.EXTRA_ID) ?: run { finish(); return }
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        setContent {
            val settings by app.settings.settings.collectAsStateWithLifecycle(initialValue = null)
            settings?.let {
                NotedTheme(it) {
                    SnoozeSheet(title, onPick = { at ->
                        app.scope.launch { app.reminders.snooze(id, at); app.reminders.schedule() }
                    }, onGone = { finish(); overridePendingTransition(0, 0) })
                }
            }
        }
    }

    companion object {
        const val EXTRA_TITLE = "title"
    }
}

@Composable
private fun SnoozeSheet(title: String, onPick: (Long) -> Unit, onGone: () -> Unit) {
    val c = Noted.colors
    val t = Noted.type
    val haptics = Noted.haptics
    val scope = rememberCoroutineScope()
    val p = remember { Animatable(0f) }
    val fade = remember { Animatable(0f) }
    var leaving by remember { mutableStateOf(false) }

    fun leave() {
        if (leaving) return
        leaving = true
        scope.launch {
            launch { fade.animateTo(0f, Motion.glide()) }
            p.animateTo(0f, Motion.glide())
            onGone()
        }
    }
    LaunchedEffect(Unit) {
        haptics.light()
        launch { fade.animateTo(1f, Motion.glide()) }
        p.animateTo(1f, Motion.touch())
    }
    BackHandler { leave() }

    val now = System.currentTimeMillis()
    val zone = ZoneId.systemDefault()
    val morning = LocalDate.now(zone).plusDays(1).atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
    val options = listOf(
        Triple(Ph.ClockCountdown, "10 דק׳", now + 10 * 60_000L),
        Triple(Ph.Clock, "שעה", now + 60 * 60_000L),
        Triple(Ph.SunHorizon, "מחר 08:00", morning),
    )

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }.background(c.scrim)
                .pointerInput(Unit) { detectTapGestures { leave() } },
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(14.dp)
                .fillMaxWidth()
                .graphicsLayer {
                    alpha = fade.value
                    translationY = (1f - p.value) * 30.dp.toPx()
                    val s = .94f + .06f * p.value
                    scaleX = s; scaleY = s
                }
                .softShadow(Shadows.float(c.isDark), 26.dp, c.surface)
                .clip(Shapes.lg)
                .background(c.surface)
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(start = 22.dp, end = 22.dp, top = 26.dp, bottom = 16.dp),
        ) {
            Box(Modifier.size(74.dp).clip(Squircle(24.dp)).background(c.accentSoft), contentAlignment = Alignment.Center) {
                Icon(Ph.BellRingingFill, null, Modifier.size(30.dp), tint = c.accent)
            }
            Spacer(Modifier.height(16.dp))
            Text("להזכיר שוב", style = t.title.copy(fontSize = t.title.fontSize * (27f / 30f)), color = c.ink)
            if (title.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(title, style = t.body, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                options.forEach { (icon, label, at) ->
                    SnoozeTile(icon, label, Modifier.weight(1f)) {
                        haptics.confirm()
                        onPick(at)
                        leave()
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Btn("ביטול", Modifier.fillMaxWidth(), kind = BtnKind.Quiet) { leave() }
        }
    }
}

@Composable
private fun SnoozeTile(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    val c = Noted.colors
    Column(
        modifier.clip(Shapes.md).background(c.bg).press(scale = .94f, onClick = onClick).padding(vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, Modifier.size(26.dp), tint = c.ink)
        Text(label, style = Noted.type.label, color = c.ink, maxLines = 1)
    }
}
