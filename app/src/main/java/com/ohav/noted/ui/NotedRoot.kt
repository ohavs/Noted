package com.ohav.noted.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.ohav.noted.NotedApp
import com.ohav.noted.design.Btn
import com.ohav.noted.design.BtnKind
import com.ohav.noted.design.DialogHost
import com.ohav.noted.design.LocalOverlays
import com.ohav.noted.design.Overlays
import com.ohav.noted.design.SheetHost
import com.ohav.noted.design.Motion
import com.ohav.noted.design.Noted
import com.ohav.noted.design.Ph
import com.ohav.noted.design.Shadows
import com.ohav.noted.design.Squircle
import com.ohav.noted.design.ToastHost
import com.ohav.noted.design.ToastState
import com.ohav.noted.design.press
import com.ohav.noted.design.softShadow
import com.ohav.noted.ui.common.EmptyArt
import com.ohav.noted.ui.common.EmptyState
import com.ohav.noted.ui.categories.CategoriesScreen
import com.ohav.noted.ui.editor.EditorHost
import com.ohav.noted.ui.editor.EditorNav
import com.ohav.noted.ui.home.HomeScreen
import com.ohav.noted.ui.places.PlacesScreen
import com.ohav.noted.ui.reminders.LocalReminders
import com.ohav.noted.ui.reminders.LocalPlaceChips
import com.ohav.noted.ui.reminders.PlaceChip
import com.ohav.noted.ui.reminders.PlaceWords
import com.ohav.noted.ui.reminders.Agenda
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ohav.noted.ui.settings.SettingsScreen

/** Shared chrome state between the home stack and the floating nav. */
@Stable
class Chrome {
    /** Nav + FAB stepped aside (scrolling down). */
    var navAway by mutableStateOf(false)

    /** Nav replaced by something else (selection bar). */
    var navHidden by mutableStateOf(false)

    /** Bumped when the active Home tab is tapped again → scroll to top. */
    var scrollTopTick by mutableIntStateOf(0)

    /** The note editor, opened as a container transform over everything. */
    val editor = EditorNav()
}

enum class Tab(val label: String, val icon: ImageVector, val iconOn: ImageVector) {
    Home("בית", Ph.House, Ph.HouseFill),
    Shared("משותף", Ph.UsersThree, Ph.UsersThreeFill),
    Agenda("לוח", Ph.CalendarBlank, Ph.CalendarBlankFill),
    Places("מקומות", Ph.MapPin, Ph.MapPinFill),
}

@Composable
fun NotedRoot(app: NotedApp, openNote: MutableState<String?>) {
    var screen by rememberSaveable { mutableStateOf("main") }
    // categories open from settings or from home; back returns to whichever it was
    var catsFrom by rememberSaveable { mutableStateOf("main") }
    val toasts = remember { ToastState() }
    val overlays = remember { Overlays() }
    BackHandler(enabled = screen != "main") { screen = if (screen == "categories") catsFrom else "main" }
    // a reminder notification was tapped: back to the main screen, where the note opens
    LaunchedEffect(openNote.value) { if (openNote.value != null) screen = "main" }
    val reminders by app.reminders.all.collectAsStateWithLifecycle(initialValue = emptyList())
    val remindersById = remember(reminders) { reminders.associateBy { it.id } }
    val placeReminders by app.places.reminders.collectAsStateWithLifecycle(initialValue = emptyList())
    val places by app.places.places.collectAsStateWithLifecycle(initialValue = emptyList())
    val placeChips = remember(placeReminders, places) {
        val byId = places.associateBy { it.id }
        placeReminders.filter { !it.fired }.associate { it.id to PlaceChip(PlaceWords.chip(it, byId[it.placeId]), it.leave, it.always) }
    }

    CompositionLocalProvider(LocalOverlays provides overlays, LocalReminders provides remindersById, LocalPlaceChips provides placeChips) {
    Box(Modifier.fillMaxSize().background(Noted.colors.bg)) {
        AnimatedContent(
            modifier = Modifier.graphicsLayer {
                val b = overlays.blur
                renderEffect = if (Build.VERSION.SDK_INT >= 31 && b > .01f) BlurEffect(b * 10.dp.toPx(), b * 10.dp.toPx(), TileMode.Clamp) else null
            },
            targetState = screen,
            transitionSpec = {
                // RTL: going deeper enters from the left (end side), back returns from the right.
                val forward = targetState == "categories" || targetState == "settings" && initialState == "main"
                (slideInHorizontally(Motion.glide()) { if (forward) -it / 3 else it / 3 } + fadeIn(Motion.glide()))
                    .togetherWith(slideOutHorizontally(Motion.glide()) { if (forward) it / 4 else -it / 4 } + fadeOut(Motion.glide()))
            },
            label = "screen",
        ) { s ->
            when (s) {
                "settings" -> SettingsScreen(app, toasts, onBack = { screen = "main" }, onCategories = { catsFrom = "settings"; screen = "categories" })
                "categories" -> CategoriesScreen(app, toasts, overlays, onBack = { screen = catsFrom })
                else -> MainScreen(app, toasts, openNote, onSettings = { screen = "settings" }, onCategories = { catsFrom = "main"; screen = "categories" })
            }
        }
        overlays.layer?.invoke()
        SheetHost(overlays.sheet)
        DialogHost(overlays.dialog)
        ToastHost(toasts, Modifier.align(Alignment.BottomCenter), lift = if (screen == "main") 90.dp else 0.dp)
    }
    }
}

@Composable
private fun MainScreen(app: NotedApp, toasts: ToastState, openNote: MutableState<String?>, onSettings: () -> Unit, onCategories: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    var sharedSeen by rememberSaveable { mutableStateOf(false) }
    val chrome = remember { Chrome() }
    val density = LocalDensity.current
    val shift = with(density) { 28.dp.roundToPx() }
    val config = LocalConfiguration.current
    BackHandler(enabled = tab != Tab.Home) { tab = Tab.Home }

    // open a note from a notification: from its card when home shows it, else from mid-screen
    LaunchedEffect(openNote.value) {
        val id = openNote.value ?: return@LaunchedEffect
        openNote.value = null
        val n = app.notes.get(id)?.takeIf { it.trashedAt == null } ?: run { toasts.show("הפתק כבר לא קיים", Ph.Info); return@LaunchedEffect }
        chrome.editor.note?.let { if (it.id == id) return@LaunchedEffect }
        val rect = chrome.editor.cardRect?.invoke(id) ?: with(density) {
            val w = config.screenWidthDp.dp.toPx()
            val h = config.screenHeightDp.dp.toPx()
            Rect(16.dp.toPx(), h / 2 - 100.dp.toPx(), w - 16.dp.toPx(), h / 2 + 100.dp.toPx())
        }
        chrome.editor.open(n, rect)
    }
    val notes by app.notes.notes.collectAsStateWithLifecycle(initialValue = emptyList())
    val categories by app.notes.categories.collectAsStateWithLifecycle(initialValue = emptyList())

    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = tab,
            transitionSpec = {
                // tabs further along sit to the left in RTL
                val dir = if (targetState.ordinal > initialState.ordinal) -1 else 1
                (slideInHorizontally(Motion.glide()) { dir * shift } + fadeIn(Motion.glide()))
                    .togetherWith(fadeOut(Motion.glide()))
            },
            label = "tab",
        ) { t ->
            when (t) {
                Tab.Home -> HomeScreen(app, toasts, chrome, onSettings, onCategories)
                Tab.Shared -> TabPage("משותף") {
                    EmptyState(
                        EmptyArt.Shared, "עוד לא שיתפת כלום",
                        "רשימת קניות משותפת, תיקייה משפחתית — כולם רואים שינויים בזמן אמת.",
                        Modifier.padding(top = 60.dp),
                    ) { Btn("הזמנה בלינק", icon = Ph.Link, kind = BtnKind.Dark) { toasts.show("שיתוף מגיע עם החשבון והסנכרון", Ph.Info) } }
                }
                Tab.Agenda -> TabPage("לוח") {
                    Agenda(notes, categories) { n, rect -> chrome.editor.open(n, rect) }
                }
                Tab.Places -> PlacesScreen(app, toasts, notes) { n ->
                    val rect = with(density) {
                        val w = config.screenWidthDp.dp.toPx()
                        val h = config.screenHeightDp.dp.toPx()
                        Rect(16.dp.toPx(), h / 2 - 100.dp.toPx(), w - 16.dp.toPx(), h / 2 + 100.dp.toPx())
                    }
                    chrome.editor.open(n, rect)
                }
            }
        }
        BottomNav(
            tab = tab,
            away = chrome.navAway || chrome.navHidden,
            badge = if (sharedSeen) null else Tab.Shared,
            modifier = Modifier.align(Alignment.BottomCenter),
        ) { picked ->
            if (picked == tab) {
                if (picked == Tab.Home) chrome.scrollTopTick++
            } else {
                tab = picked
                chrome.navAway = false
                if (picked == Tab.Shared) sharedSeen = true
            }
        }
        EditorHost(app, chrome.editor, toasts)
    }
}

/** Other tabs: giant title like the home category, content under it. */
@Composable
private fun TabPage(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Box(Modifier.statusBarsPadding().padding(top = 14.dp).height(64.dp))
        Text(title, style = Noted.type.cat, color = Noted.colors.ink, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 4.dp))
        content()
        Box(Modifier.height(140.dp))
    }
}

/** Floating light bar. The active tab gets a sliding accent-tinted squircle, a filled accent icon and its label. */
@Composable
private fun BottomNav(tab: Tab, away: Boolean, badge: Tab?, modifier: Modifier = Modifier, onSelect: (Tab) -> Unit) {
    val c = Noted.colors
    val awayP by animateFloatAsState(if (away) 1f else 0f, Motion.glide(), label = "navAway")
    BoxWithConstraints(
        modifier
            .navigationBarsPadding()
            .padding(start = 22.dp, end = 22.dp, bottom = 18.dp)
            .fillMaxWidth()
            .height(74.dp)
            .graphicsLayer { translationY = awayP * size.height * 1.4f + awayP * 40.dp.toPx() },
    ) {
        val itemW = (maxWidth - 16.dp) / Tab.entries.size
        val x by animateDpAsState(8.dp + itemW * tab.ordinal + 8.dp, Motion.glide(), label = "ind")
        Box(
            Modifier
                .fillMaxSize()
                .softShadow(Shadows.float(c.isDark), 30.dp, c.surface)
                .clip(Squircle(30.dp))
                .background(c.surface)
                .drawBehind {
                    val o = Squircle(30.dp).createOutline(size, layoutDirection, this)
                    drawOutline(o, c.line, style = Stroke(1.5.dp.toPx()))
                },
        ) {
            // offset() follows layout direction, so the indicator counts from the right in RTL
            Box(Modifier.offset(x = x, y = 9.dp).size(itemW - 16.dp, 56.dp).clip(Squircle(22.dp)).background(c.accentSoft))
            Row(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                Tab.entries.forEach { t -> NavItem(t, t == tab, t == badge, Modifier.weight(1f)) { onSelect(t) } }
            }
        }
    }
}

@Composable
private fun NavItem(t: Tab, on: Boolean, badge: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Noted.colors
    val col by animateColorAsState(if (on) c.accent else c.ink2, Motion.glide(), label = "navCol")
    val p by animateFloatAsState(if (on) 1f else 0f, Motion.glide(), label = "navOn")
    val lift by animateFloatAsState(if (on) 1f else 0f, Motion.touch(), label = "navLift")
    Box(modifier.fillMaxHeight().press(scale = .9f, onClick = onClick), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                if (on) t.iconOn else t.icon, t.label,
                Modifier.size(22.dp).graphicsLayer { translationY = -lift * 1.dp.toPx() }, tint = col,
            )
            Box(Modifier.height(16.dp * p + 1.dp).graphicsLayer { alpha = p; translationY = (1f - p) * 4.dp.toPx() }) {
                Text(t.label, style = Noted.type.tiny, color = col, maxLines = 1)
            }
        }
        if (badge) {
            Box(
                Modifier.align(Alignment.TopCenter).padding(top = 15.dp).offset(x = 14.dp)
                    .size(13.dp).clip(CircleShape).background(c.surface).padding(2.5.dp).clip(CircleShape).background(c.accent),
            )
        }
    }
}
