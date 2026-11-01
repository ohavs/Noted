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
import com.ohav.noted.ui.home.HomeScreen
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
}

enum class Tab(val label: String, val icon: ImageVector, val iconOn: ImageVector) {
    Home("בית", Ph.House, Ph.HouseFill),
    Shared("משותף", Ph.UsersThree, Ph.UsersThreeFill),
    Agenda("לוח", Ph.CalendarBlank, Ph.CalendarBlankFill),
    Places("מקומות", Ph.MapPin, Ph.MapPinFill),
}

@Composable
fun NotedRoot(app: NotedApp) {
    var screen by rememberSaveable { mutableStateOf("main") }
    val toasts = remember { ToastState() }
    val overlays = remember { Overlays() }
    BackHandler(enabled = screen != "main") { screen = "main" }

    CompositionLocalProvider(LocalOverlays provides overlays) {
    Box(Modifier.fillMaxSize().background(Noted.colors.bg)) {
        AnimatedContent(
            modifier = Modifier.graphicsLayer {
                val b = overlays.blur
                renderEffect = if (Build.VERSION.SDK_INT >= 31 && b > .01f) BlurEffect(b * 10.dp.toPx(), b * 10.dp.toPx(), TileMode.Clamp) else null
            },
            targetState = screen,
            transitionSpec = {
                // RTL: going deeper enters from the left (end side), back returns from the right.
                val forward = targetState != "main"
                (slideInHorizontally(Motion.glide()) { if (forward) -it / 3 else it / 3 } + fadeIn(Motion.glide()))
                    .togetherWith(slideOutHorizontally(Motion.glide()) { if (forward) it / 4 else -it / 4 } + fadeOut(Motion.glide()))
            },
            label = "screen",
        ) { s ->
            when (s) {
                "settings" -> SettingsScreen(app, toasts, onBack = { screen = "main" })
                else -> MainScreen(app, toasts, onSettings = { screen = "settings" })
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
private fun MainScreen(app: NotedApp, toasts: ToastState, onSettings: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    var sharedSeen by rememberSaveable { mutableStateOf(false) }
    val chrome = remember { Chrome() }
    val shift = with(LocalDensity.current) { 28.dp.roundToPx() }
    BackHandler(enabled = tab != Tab.Home) { tab = Tab.Home }

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
                Tab.Home -> HomeScreen(app, toasts, chrome, onSettings)
                Tab.Shared -> TabPage("משותף") {
                    EmptyState(
                        EmptyArt.Shared, "עוד לא שיתפת כלום",
                        "רשימת קניות משותפת, תיקייה משפחתית — כולם רואים שינויים בזמן אמת.",
                        Modifier.padding(top = 60.dp),
                    ) { Btn("הזמנה בלינק", icon = Ph.Link, kind = BtnKind.Dark) { toasts.show("שיתוף מגיע עם החשבון והסנכרון", Ph.Info) } }
                }
                Tab.Agenda -> TabPage("לוח") {
                    EmptyState(
                        EmptyArt.Agenda, "אין תזכורות קרובות",
                        "תזכורות מכל הפתקים, ושורות מתוך רשימות, יופיעו כאן לפי יום.",
                        Modifier.padding(top = 60.dp),
                    )
                }
                Tab.Places -> TabPage("מקומות") {
                    EmptyState(
                        EmptyArt.Places, "המקומות שלך",
                        "שמור בית, עבודה, סופר — ופתקים יקפצו כשתגיע.",
                        Modifier.padding(top = 60.dp),
                    ) { Btn("שמור מיקום נוכחי", icon = Ph.MapPin) { toasts.show("מקומות מגיעים בשלב 7", Ph.Info) } }
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

/** Floating inverse pill. The active tab gets a sliding squircle, a filled icon and its label. */
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
                .softShadow(Shadows.float(c.isDark), 30.dp, c.inverse)
                .clip(Squircle(30.dp))
                .background(c.inverse),
        ) {
            // offset() follows layout direction, so the indicator counts from the right in RTL
            Box(Modifier.offset(x = x, y = 9.dp).size(itemW - 16.dp, 56.dp).clip(Squircle(22.dp)).background(c.inverse2))
            Row(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                Tab.entries.forEach { t -> NavItem(t, t == tab, t == badge, Modifier.weight(1f)) { onSelect(t) } }
            }
        }
    }
}

@Composable
private fun NavItem(t: Tab, on: Boolean, badge: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Noted.colors
    val col by animateColorAsState(if (on) c.inverseInk else c.inverseInk2, Motion.glide(), label = "navCol")
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
                    .size(13.dp).clip(CircleShape).background(c.inverse).padding(2.5.dp).clip(CircleShape).background(c.accent),
            )
        }
    }
}
