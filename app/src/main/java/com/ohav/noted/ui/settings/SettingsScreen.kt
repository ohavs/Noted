package com.ohav.noted.ui.settings

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ohav.noted.NotedApp
import com.ohav.noted.design.Accent
import com.ohav.noted.design.IconButton
import com.ohav.noted.design.Motion
import com.ohav.noted.design.NoteColor
import com.ohav.noted.design.Noted
import com.ohav.noted.design.NotedSwitch
import com.ohav.noted.design.Ph
import com.ohav.noted.design.Shapes
import com.ohav.noted.design.Squircle
import com.ohav.noted.design.ThemeMode
import com.ohav.noted.design.ToastState
import com.ohav.noted.design.VSpace
import com.ohav.noted.design.press
import com.ohav.noted.settings.AppSettings
import com.ohav.noted.update.Updater
import com.ohav.noted.widget.NotedWidgetReceiver
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(app: NotedApp, toasts: ToastState, onBack: () -> Unit, onCategories: () -> Unit) {
    val c = Noted.colors
    val t = Noted.type
    val haptics = Noted.haptics
    val scope = rememberCoroutineScope()
    val s by app.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding()
            .padding(bottom = 120.dp),
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(Ph.CaretStart, "חזרה", onClick = onBack)
        }
        Text("הגדרות", style = t.hero, color = c.ink, modifier = Modifier.padding(horizontal = 20.dp))
        VSpace(20.dp)

        Section("חשבון") { AccountCard(app, toasts) }
        Section("ארגון") {
            Row(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(Shapes.lg).background(c.surface)
                    .press(scale = .98f, onClick = onCategories)
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Ph.FolderSimple, null, Modifier.size(22.dp), tint = c.ink2)
                Spacer(Modifier.width(14.dp))
                Text("קטגוריות", style = t.body, color = c.ink, modifier = Modifier.weight(1f))
                Text("שם, אייקון, סדר", style = t.caption, color = c.ink3)
                Spacer(Modifier.width(8.dp))
                Icon(Ph.CaretEnd, null, Modifier.size(18.dp), tint = c.ink3)
            }
        }
        Section("מראה") {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ThemeMode.entries.forEach { m ->
                    ThemeOption(m, selected = s.theme == m, modifier = Modifier.weight(1f)) {
                        haptics.light(); scope.launch { app.settings.setTheme(m) }
                    }
                }
            }
        }
        Section("צבע הדגשה") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Accent.entries.forEach { a ->
                    AccentOption(a, s.accent == a) { scope.launch { app.settings.setAccent(a) } }
                }
            }
        }
        Section("וידג'טים") { WidgetCard(toasts) }
        Section("תחושה") {
            Row(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(Shapes.lg).background(c.surface)
                    .press(scale = .98f, haptic = false) {
                        val v = !s.haptics
                        scope.launch { app.settings.setHaptics(v) }
                        if (v) haptics.toggle(true)
                    }
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Ph.Vibrate, null, Modifier.size(22.dp), tint = c.ink2)
                Spacer(Modifier.width(14.dp))
                Text("רטט עדין בכל פעולה", style = t.body, color = c.ink, modifier = Modifier.weight(1f))
                NotedSwitch(s.haptics)
            }
        }
        Section("עדכונים") { UpdateCard(app, toasts) }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Text(
        title, style = Noted.type.section, color = Noted.colors.ink2,
        modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 10.dp),
    )
    content()
}

@Composable
private fun ThemeOption(m: ThemeMode, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Noted.colors
    val ring by animateColorAsState(if (selected) c.accent else Color.Transparent, Motion.glide(), label = "ring")
    val bars = when (m) {
        ThemeMode.Light -> NoteColor.Butter.light to NoteColor.Mint.light
        ThemeMode.Dark -> NoteColor.Butter.dark to NoteColor.Mint.dark
        ThemeMode.System -> NoteColor.Butter.light to NoteColor.Mint.dark
    }
    val bg = when (m) {
        ThemeMode.Light -> Brush.linearGradient(listOf(Color(0xFFF5F1EA), Color(0xFFF5F1EA)))
        ThemeMode.Dark -> Brush.linearGradient(listOf(Color(0xFF12110F), Color(0xFF12110F)))
        ThemeMode.System -> Brush.horizontalGradient(0f to Color(0xFF12110F), .5f to Color(0xFF12110F), .5f to Color(0xFFF5F1EA), 1f to Color(0xFFF5F1EA))
    }
    Column(
        modifier.clip(Shapes.lg).background(c.surface).border(2.dp, ring, Shapes.lg).press(haptic = false, onClick = onClick).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().height(66.dp).clip(Shapes.md).background(bg).padding(10.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth().height(18.dp).clip(Shapes.sm).background(bars.first))
                Box(Modifier.fillMaxWidth(.7f).height(18.dp).clip(Shapes.sm).background(bars.second))
            }
        }
        VSpace(8.dp)
        Text(m.label, style = Noted.type.label, color = if (selected) c.ink else c.ink2)
    }
}

@Composable
private fun AccentOption(a: Accent, selected: Boolean, onClick: () -> Unit) {
    val c = Noted.colors
    val col = if (c.isDark) a.dark else a.light
    val ink = if (c.isDark) a.darkInk else Color.White
    val checkScale by animateFloatAsState(if (selected) 1f else 0f, Motion.touch(), label = "check")
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.press(scale = .9f, onClick = onClick)) {
        Box(Modifier.size(58.dp).clip(Squircle(20.dp)).background(col), contentAlignment = Alignment.Center) {
            Icon(Ph.Check, null, Modifier.size(24.dp).graphicsLayer { scaleX = checkScale.coerceAtLeast(0f); scaleY = checkScale.coerceAtLeast(0f) }, tint = ink)
        }
        VSpace(6.dp)
        Text(a.label, style = Noted.type.label, color = if (selected) c.ink else c.ink2)
    }
}

/** The three home-screen widgets, each with a small likeness in the current theme; pinned straight from here. */
private enum class WKind(val title: String, val desc: String) {
    Stack("ערימה", "קטגוריה אחת (או כל הפתקים) על המסך — סימון משימות במקום, פתק חדש בנגיעה"),
    Note("פתק בודד", "פתק אחד שלם על המסך, נבחר בהוספה — השורות שלו מסומנות ישר מהוידג'ט"),
    Quick("יצירה מהירה", "פתק, רשימה, הקלטה, צילום או תזכורת — כל כפתור פותח פתק חדש שכבר עושה את זה"),
}

@Composable
private fun WidgetCard(toasts: ToastState) {
    val c = Noted.colors
    val t = Noted.type
    val haptics = Noted.haptics
    val context = LocalContext.current
    var kind by rememberSaveable { mutableStateOf(WKind.Stack) }
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(Shapes.lg).background(c.surface).padding(18.dp)) {
        Row(Modifier.fillMaxWidth().height(116.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            WKind.entries.forEach { k ->
                val on = k == kind
                val ring by androidx.compose.animation.animateColorAsState(if (on) c.ink else Color.Transparent, Motion.glide(), label = "wring")
                Box(
                    Modifier.weight(1f).fillMaxHeight()
                        .press(scale = .95f) { kind = k }
                        .border(2.dp, ring, Shapes.md)
                        .padding(4.dp)
                        .clip(Shapes.sm).background(c.bg2).padding(8.dp),
                ) { Likeness(k) }
            }
        }
        VSpace(14.dp)
        androidx.compose.animation.AnimatedContent(
            targetState = kind,
            transitionSpec = { androidx.compose.animation.fadeIn(Motion.glide()) togetherWith androidx.compose.animation.fadeOut(Motion.glide()) },
            label = "wkind",
        ) { k ->
            Column {
                Text("וידג'ט ${k.title}", style = t.bodyStrong, color = c.ink)
                Text(k.desc, style = t.caption, color = c.ink2)
            }
        }
        VSpace(14.dp)
        ActionButton("הוספה למסך הבית") {
            val mgr = AppWidgetManager.getInstance(context)
            val receiver = when (kind) {
                WKind.Stack -> NotedWidgetReceiver::class.java
                WKind.Note -> com.ohav.noted.widget.NoteWidgetReceiver::class.java
                WKind.Quick -> com.ohav.noted.widget.QuickWidgetReceiver::class.java
            }
            val ok = mgr.isRequestPinAppWidgetSupported &&
                runCatching { mgr.requestPinAppWidget(ComponentName(context, receiver), null, null) }.getOrDefault(false)
            if (ok) haptics.confirm()
            else { haptics.reject(); toasts.show("המסך הראשי לא מאפשר — לחיצה ארוכה על המסך ← וידג'טים ← noted", Ph.Info, millis = 4200) }
        }
    }
}

/** A tiny drawing of each widget, in the app's colours. */
@Composable
private fun Likeness(k: WKind) {
    val c = Noted.colors
    fun note(col: NoteColor) = if (c.isDark) col.dark else col.light
    val bar = c.noteInk.copy(alpha = .22f)
    when (k) {
        WKind.Stack -> Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Box(Modifier.fillMaxWidth(.6f).height(8.dp).clip(CircleShape).background(c.ink)) }
                Box(Modifier.size(16.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
                    Icon(Ph.Plus, null, Modifier.size(10.dp), tint = c.accentInk)
                }
            }
            VSpace(8.dp)
            listOf(NoteColor.Butter to .8f, NoteColor.Mint to .55f, NoteColor.Sky to .7f).forEach { (col, w) ->
                Box(Modifier.fillMaxWidth().height(20.dp).padding(bottom = 4.dp).clip(Shapes.sm).background(note(col))) {
                    Box(Modifier.padding(horizontal = 6.dp).fillMaxWidth(w).height(4.dp).align(Alignment.CenterStart).clip(CircleShape).background(bar))
                }
            }
        }
        WKind.Note -> Column(Modifier.fillMaxSize().clip(Shapes.sm).background(note(NoteColor.Peach)).padding(9.dp)) {
            Box(Modifier.fillMaxWidth(.55f).height(8.dp).clip(CircleShape).background(c.noteInk))
            VSpace(9.dp)
            listOf(.8f, .6f, .7f, .45f).forEachIndexed { i, w ->
                Row(Modifier.padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).let { if (i == 2) it.background(c.noteInk) else it.border(1.2.dp, c.noteInk.copy(alpha = .45f), RoundedCornerShape(2.dp)) })
                    Spacer(Modifier.width(5.dp))
                    Box(Modifier.fillMaxWidth(w).height(4.dp).clip(CircleShape).background(bar))
                }
            }
        }
        WKind.Quick -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            Row(Modifier.fillMaxWidth().height(34.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                listOf(Ph.NotePencil, Ph.CheckSquare, Ph.Microphone, Ph.Camera, Ph.Bell).forEachIndexed { i, ic ->
                    Box(
                        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(7.dp)).background(if (i == 0) c.accent else c.surface),
                        contentAlignment = Alignment.Center,
                    ) { Icon(ic, null, Modifier.size(11.dp), tint = if (i == 0) c.accentInk else c.ink) }
                }
            }
        }
    }
}

@Composable
private fun UpdateCard(app: NotedApp, toasts: ToastState) {
    val c = Noted.colors
    val t = Noted.type
    val haptics = Noted.haptics
    val scope = rememberCoroutineScope()
    val st by app.updater.state.collectAsState()

    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(Shapes.lg).background(c.surface).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Ph.ArrowsClockwise, null, Modifier.size(22.dp), tint = c.ink2)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("בדוק עדכונים", style = t.bodyStrong, color = c.ink)
                Text("מותקנת: ${app.updater.installedVersion}", style = t.caption, color = c.ink2)
            }
        }
        VSpace(14.dp)
        AnimatedContent(
            targetState = st,
            contentKey = { it::class },
            transitionSpec = { fadeIn(Motion.glide()) togetherWith fadeOut(Motion.glide()) },
            label = "update",
        ) { state ->
            when (state) {
                is Updater.State.Idle, is Updater.State.Failed, is Updater.State.UpToDate -> Column {
                    if (state is Updater.State.UpToDate) StatusLine(Ph.CheckCircleFill, "זו הגרסה העדכנית", c.accent)
                    if (state is Updater.State.Failed) StatusLine(Ph.WarningCircle, state.message, c.danger)
                    ActionButton("בדיקה עכשיו") {
                        scope.launch {
                            app.updater.check()
                            val r = app.updater.state.value
                            if (r is Updater.State.UpToDate) haptics.confirm()
                            if (r is Updater.State.Failed) haptics.reject()
                        }
                    }
                }
                is Updater.State.Checking -> StatusLine(Ph.ArrowsClockwise, "בודק…", c.ink2)
                is Updater.State.Available -> Column {
                    StatusLine(Ph.Sparkle, "גרסה ${state.versionName} זמינה · ${"%.1f".format(state.sizeBytes / 1_048_576f)} MB", c.accent)
                    ActionButton("הורדה והתקנה") {
                        scope.launch {
                            app.updater.download(state)
                            if (app.updater.state.value is Updater.State.Ready) { haptics.confirm(); app.updater.install() }
                            else toasts.show("ההורדה נכשלה", Ph.WarningCircle)
                        }
                    }
                }
                is Updater.State.Downloading -> Column {
                    StatusLine(Ph.DownloadSimple, "מוריד… ${(state.progress * 100).toInt()}%", c.ink2)
                    Progress(state.progress)
                }
                is Updater.State.Ready -> Column {
                    StatusLine(Ph.CheckCircleFill, "הורד — מוכן להתקנה", c.accent)
                    ActionButton("התקנה") { app.updater.install() }
                }
                is Updater.State.NeedsPermission -> Column {
                    StatusLine(Ph.Info, "צריך לאשר ל-noted להתקין עדכונים, ואז לחזור לכאן", c.ink2)
                    ActionButton("לאישור") { app.updater.install() }
                }
            }
        }
    }
}

@Composable
private fun StatusLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, tint: Color) {
    Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = tint)
        Spacer(Modifier.width(8.dp))
        Text(text, style = Noted.type.label, color = Noted.colors.ink)
    }
}

@Composable
private fun ActionButton(text: String, onClick: () -> Unit) {
    val c = Noted.colors
    Box(
        Modifier.fillMaxWidth().height(52.dp).clip(Shapes.md).background(c.accent).press(scale = .97f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = Noted.type.bodyStrong, color = c.accentInk) }
}

@Composable
private fun Progress(p: Float) {
    val c = Noted.colors
    val w by animateFloatAsState(p, Motion.glide(), label = "progress")
    Box(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(c.bg2)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(w.coerceIn(0.02f, 1f)).clip(CircleShape).background(c.accent))
    }
}
