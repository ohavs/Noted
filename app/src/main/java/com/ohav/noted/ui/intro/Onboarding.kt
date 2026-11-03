package com.ohav.noted.ui.intro

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ohav.noted.NotedApp
import com.ohav.noted.design.Btn
import com.ohav.noted.design.BtnKind
import com.ohav.noted.design.Motion
import com.ohav.noted.design.Noted
import com.ohav.noted.design.Ph
import com.ohav.noted.design.Shapes
import com.ohav.noted.design.VSpace
import com.ohav.noted.design.press
import com.ohav.noted.sync.Account
import com.ohav.noted.ui.editor.findActivity
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

private class Page(val title: String, val text: String)

private val pages = listOf(
    Page("הכל בערימה אחת", "כל פתק מציץ בכותרת שלו. נגיעה פורשת אותו במקום, משיכה למעלה פותחת לעריכה."),
    Page("כותבים, וזה מסתדר", "כותרות, רשימות ותיבות סימון — הפתק מתארגן תוך כדי כתיבה ונשמר לבד."),
    Page("נזכיר בזמן. או במקום.", "תזכורת לשעה מסוימת, או כשמגיעים למקום — לפתק שלם או לשורה אחת ברשימה."),
)

/**
 * First-run intro: three live demos built from the app's own pieces (the stack, the check rows, the
 * reminder line) that play while their page is in view, then an optional Google sign-in. Nothing is
 * asked for here — permissions come up only when a feature needs them.
 */
@Composable
fun Onboarding(app: NotedApp, onDone: () -> Unit) {
    val c = Noted.colors
    val t = Noted.type
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == pages.lastIndex
    val haptics = Noted.haptics
    androidx.compose.runtime.LaunchedEffect(pager) {
        androidx.compose.runtime.snapshotFlow { pager.currentPage }.drop(1).collect { haptics.tick() }
    }

    BackHandler {
        if (pager.currentPage > 0) scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } else onDone()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            // nothing under the intro takes a touch while it is up
            .pointerInput(Unit) { detectTapGestures { } }
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("noted", style = t.barTitle, color = c.ink, modifier = Modifier.padding(start = 8.dp))
            Spacer(Modifier.weight(1f))
            val skipAlpha = remember { Animatable(1f) }
            androidx.compose.runtime.LaunchedEffect(last) { skipAlpha.animateTo(if (last) 0f else 1f, Motion.glide()) }
            Box(
                Modifier
                    .graphicsLayer { alpha = skipAlpha.value }
                    .clip(Shapes.sm)
                    .press(enabled = !last, scale = .9f) { scope.launch { pager.animateScrollToPage(pages.lastIndex) } }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) { Text("דלג", style = t.label, color = c.ink2) }
        }

        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), beyondViewportPageCount = 1) { i ->
            PageBody(pager, i)
        }

        Dashes(pager, Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp, bottom = 18.dp))

        Box(Modifier.fillMaxWidth().height(150.dp).padding(horizontal = 20.dp)) {
            AnimatedContent(
                targetState = last,
                transitionSpec = {
                    (fadeIn(Motion.glide()) + slideInVertically(Motion.glide()) { it / 5 })
                        .togetherWith(fadeOut(Motion.glide()) + slideOutVertically(Motion.glide()) { -it / 6 })
                },
                label = "cta",
            ) { end ->
                if (!end) Btn("הבא", Modifier.fillMaxWidth()) { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }
                else Finish(app, onDone)
            }
        }
    }
}

@Composable
private fun PageBody(pager: PagerState, i: Int) {
    val c = Noted.colors
    val t = Noted.type
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // a demo plays only while its page is the one in view, from its beginning
    val active = pager.settledPage == i
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        Column(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp)
                    .graphicsLayer {
                        // the demo trails its page a little: depth without a second motion
                        val d = pager.getOffsetDistanceInPages(i)
                        translationX = (if (rtl) 1f else -1f) * d * w * .28f
                        alpha = 1f - .5f * abs(d).coerceIn(0f, 1f)
                    },
                contentAlignment = Alignment.Center,
            ) {
                when (i) {
                    0 -> StackDemo(active)
                    1 -> WriteDemo(active)
                    else -> RemindDemo(active)
                }
            }
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 28.dp).graphicsLayer {
                    val d = pager.getOffsetDistanceInPages(i)
                    translationX = (if (rtl) 1f else -1f) * d * w * .1f
                },
            ) {
                Text(pages[i].title, style = t.hero, color = c.ink)
                VSpace(10.dp)
                Text(pages[i].text, style = t.sub, color = c.ink2)
            }
        }
    }
}

/** Three marks, one accent mark that slides between them with the finger and stretches mid-way. */
@Composable
private fun Dashes(pager: PagerState, modifier: Modifier) {
    val c = Noted.colors
    val ink = c.ink3
    val accent = c.accent
    Box(
        modifier.size(width = 84.dp, height = 6.dp).drawBehind {
            val n = pages.size
            val gap = 8.dp.toPx()
            val dash = (size.width - gap * (n - 1)) / n
            val h = 3.dp.toPx()
            val y = (size.height - h) / 2f
            val rtl = layoutDirection == LayoutDirection.Rtl
            fun xOf(slot: Float) = if (rtl) size.width - dash - slot * (dash + gap) else slot * (dash + gap)
            for (k in 0 until n) drawRoundRect(ink.copy(alpha = .45f), Offset(xOf(k.toFloat()), y), Size(dash, h), CornerRadius(h / 2))
            val pos = (pager.currentPage + pager.currentPageOffsetFraction).coerceIn(0f, n - 1f)
            val frac = pos - kotlin.math.floor(pos)
            val stretch = (dash + gap) * .6f * sin(PI.toFloat() * frac)
            val x = xOf(pos)
            drawRoundRect(accent, Offset(x - stretch / 2f, y - .5.dp.toPx()), Size(dash + stretch, h + 1.dp.toPx()), CornerRadius(h))
        },
    )
}

/** The last page's way out: Google (optional) or straight in. */
@Composable
private fun Finish(app: NotedApp, onDone: () -> Unit) {
    val c = Noted.colors
    val t = Noted.type
    val haptics = Noted.haptics
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val user by app.account.user.collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        val me = user
        if (me != null || !app.account.available) {
            Btn(if (me != null) "יאללה, מתחילים" else "מתחילים", Modifier.fillMaxWidth()) { haptics.confirm(); onDone() }
            if (me != null) {
                VSpace(12.dp)
                Text("מחובר/ת כ-${me.first} — הפתקים יסונכרנו", style = t.caption, color = c.ink3, textAlign = TextAlign.Center)
            }
        } else {
            val dim = remember { Animatable(1f) }
            androidx.compose.runtime.LaunchedEffect(busy) { dim.animateTo(if (busy) .55f else 1f, Motion.glide()) }
            Row(
                Modifier
                    .fillMaxWidth().height(56.dp)
                    .graphicsLayer { alpha = dim.value }
                    .press(scale = .96f) {
                        if (busy) return@press
                        val act = ctx.findActivity() ?: return@press
                        busy = true
                        error = null
                        scope.launch {
                            when (val r = app.account.signIn(act)) {
                                Account.SignIn.Ok -> { haptics.confirm(); onDone() }
                                Account.SignIn.Cancelled -> Unit
                                is Account.SignIn.Failed -> { haptics.reject(); error = r.message }
                            }
                            busy = false
                        }
                    }
                    .clip(Shapes.md).background(c.ink),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Ph.GoogleLogo, null, Modifier.size(20.dp), tint = c.bg)
                Spacer(Modifier.width(10.dp))
                Text(if (busy) "מתחבר…" else "המשך עם Google", style = t.button, color = c.bg)
            }
            VSpace(6.dp)
            Btn("בלי חשבון — הכל נשאר במכשיר", Modifier.fillMaxWidth(), kind = BtnKind.Quiet) { onDone() }
            AnimatedContent(
                targetState = error,
                transitionSpec = { fadeIn(Motion.glide()) togetherWith fadeOut(Motion.glide()) },
                label = "err",
            ) { e ->
                Text(e ?: "אפשר להתחבר גם אחר כך, בהגדרות", style = t.caption, color = if (e != null) c.danger else c.ink3, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
