package com.ohav.noted.ui.intro

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.ohav.noted.design.Motion
import com.ohav.noted.design.NoteColor
import com.ohav.noted.design.Noted
import com.ohav.noted.design.Ph
import com.ohav.noted.design.Shadows
import com.ohav.noted.design.Shapes
import com.ohav.noted.design.fade
import com.ohav.noted.design.softShadow
import com.ohav.noted.ui.home.CheckRow
import com.ohav.noted.ui.home.ProgressRing
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The demos are pictures of the app, not the app: a fixed frame, faded out at the bottom edge. */
private fun Modifier.demoFrame() = this
    .fillMaxWidth()
    .height(340.dp)
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val f = (1f - 44.dp.toPx() / size.height).coerceIn(0f, 1f)
        drawRect(Brush.verticalGradient(0f to Color.Black, f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
    }

private class DemoNote(val color: NoteColor, val title: String, val lines: List<String>)

private val stackNotes = listOf(
    DemoNote(NoteColor.Butter, "קניות לשבת", listOf("חלה, יין ופרחים", "לבדוק אם נשאר שמן זית")),
    DemoNote(NoteColor.Mint, "טיול בגליל", listOf("לסגור צימר עד חמישי", "מסלול: נחל עמוד, יום שישי בבוקר")),
    DemoNote(NoteColor.Lilac, "ספרים לקרוא", listOf("הספרייה של מישהו אחר", "שלושה ימים בקיץ")),
    DemoNote(NoteColor.Peach, "ישיבת צוות", listOf("להביא את המצגת", "לשאול על התקציב לרבעון")),
)

/** Page 1 — the stack: a finger taps a title, that note spreads out in place, then folds back. */
@Composable
internal fun StackDemo(active: Boolean) {
    val c = Noted.colors
    val t = Noted.type
    val open = remember { Animatable(0f) }
    val tap = remember { Animatable(1f) }
    val press = remember { Animatable(1f) }
    var openIdx by remember { mutableIntStateOf(-1) }

    LaunchedEffect(active) {
        open.snapTo(0f); tap.snapTo(1f); press.snapTo(1f); openIdx = -1
        if (!active) return@LaunchedEffect
        delay(650)
        coroutineScope {
            while (true) for (i in listOf(1, 3, 0)) {
                openIdx = i
                launch { tap.snapTo(0f); tap.animateTo(1f, Motion.glide()) }
                launch { press.animateTo(1f, Motion.touch(), initialVelocity = -1.4f) }
                delay(120)
                open.animateTo(1f, Motion.glide())
                delay(1500)
                open.animateTo(0f, Motion.glide())
                delay(500)
            }
        }
    }

    val step = 50.dp
    val cardH = 150.dp
    val spread = cardH + 10.dp - step
    val accent = c.accent
    Box(Modifier.demoFrame().clipToBounds()) {
        stackNotes.forEachIndexed { i, n ->
            val note = c.note(n.color)
            Box(
                Modifier
                    .padding(top = 14.dp)
                    .fillMaxWidth()
                    .height(cardH)
                    .offset {
                        val below = if (openIdx in 0 until i) spread.toPx() * open.value else 0f
                        IntOffset(0, (step.toPx() * i + below).toInt())
                    }
                    .graphicsLayer {
                        val k = if (i == openIdx) press.value else 1f
                        scaleX = k; scaleY = k
                    }
                    .softShadow(Shadows.card(c.isDark), 30.dp, note)
                    .clip(Shapes.card)
                    .background(note)
                    .drawWithContent {
                        drawContent()
                        // the finger: a soft ring that opens out from the title and fades
                        if (i != openIdx) return@drawWithContent
                        val p = tap.value
                        if (p >= 1f) return@drawWithContent
                        val x = if (layoutDirection == LayoutDirection.Rtl) size.width - 64.dp.toPx() else 64.dp.toPx()
                        val center = Offset(x, 34.dp.toPx())
                        drawCircle(accent.copy(alpha = .28f * (1f - p)), radius = 14.dp.toPx() + 22.dp.toPx() * p, center = center)
                        drawCircle(accent.copy(alpha = .5f * (1f - p)), radius = 14.dp.toPx() + 22.dp.toPx() * p, center = center, style = Stroke(2.dp.toPx()))
                    }
                    .padding(start = 24.dp, end = 24.dp, top = 20.dp),
            ) {
                Column {
                    Text(n.title, style = t.cardTitle, color = c.noteInk, maxLines = 1)
                    Spacer(Modifier.height(8.dp))
                    n.lines.forEach { Text(it, style = t.snippet, color = c.noteInk2, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
}

private const val LIST_TITLE = "קניות לשבת"
private val listItems = listOf("חלב", "לחם אחיד", "עגבניות", "קפה טחון")

/** Page 2 — writing: the title types itself, rows arrive one by one, then tick themselves off. */
@Composable
internal fun WriteDemo(active: Boolean) {
    val c = Noted.colors
    val t = Noted.type
    var title by remember { mutableStateOf("") }
    val texts = remember { mutableStateListOf(*Array(listItems.size) { "" }) }
    val done = remember { mutableStateListOf(*Array(listItems.size) { false }) }
    var rows by remember { mutableIntStateOf(0) }
    var typingTitle by remember { mutableStateOf(false) }
    var caretOn by remember { mutableStateOf(true) }
    val content = remember { Animatable(1f) }

    fun reset() {
        title = ""; rows = 0; typingTitle = false
        for (k in listItems.indices) { texts[k] = ""; done[k] = false }
    }
    LaunchedEffect(typingTitle) {
        // a real caret blinks in steps, it doesn't glide
        caretOn = true
        while (typingTitle) { delay(530); caretOn = !caretOn }
    }
    LaunchedEffect(active) {
        reset(); content.snapTo(1f)
        if (!active) return@LaunchedEffect
        delay(450)
        while (true) {
            typingTitle = true
            for (k in 1..LIST_TITLE.length) { title = LIST_TITLE.take(k); delay(70) }
            delay(380)
            typingTitle = false
            for (i in listItems.indices) {
                rows = i + 1
                delay(170)
                for (k in 1..listItems[i].length) { texts[i] = listItems[i].take(k); delay(48) }
                delay(120)
            }
            delay(450); done[0] = true
            delay(520); done[2] = true
            delay(460); done[1] = true
            delay(1900)
            content.animateTo(0f, Motion.glide())
            reset()
            delay(150)
            content.snapTo(1f)
        }
    }

    val note = c.note(NoteColor.Butter)
    Box(Modifier.demoFrame(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .fillMaxWidth()
                .height(300.dp)
                .softShadow(Shadows.float(c.isDark).fade(.55f), 30.dp, note)
                .clip(Shapes.card)
                .background(note)
                .padding(start = 24.dp, end = 24.dp, top = 22.dp)
                .graphicsLayer { alpha = content.value },
        ) {
            Row(Modifier.fillMaxWidth().height(46.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = t.cardTitle, color = c.noteInk, maxLines = 1)
                Box(
                    Modifier
                        .padding(start = 2.dp)
                        .size(width = 2.5.dp, height = 28.dp)
                        .graphicsLayer { alpha = if (typingTitle && caretOn) 1f else 0f }
                        .background(c.accent),
                )
                Spacer(Modifier.weight(1f))
                AnimatedVisibility(rows == listItems.size, enter = fadeIn(Motion.glide()), exit = fadeOut(Motion.glide())) {
                    ProgressRing(done.count { it }, listItems.size)
                }
            }
            Spacer(Modifier.height(10.dp))
            listItems.indices.forEach { i ->
                AnimatedVisibility(
                    i < rows,
                    enter = fadeIn(Motion.glide()) + slideInVertically(Motion.glide()) { it / 2 },
                    exit = fadeOut(Motion.glide()),
                ) {
                    CheckRow(texts[i], done[i], tick = note) { done[i] = !done[i] }
                }
            }
        }
    }
}

/** Page 3 — reminders: a time one rings, then the same note waits for a place and fires on arrival. */
@Composable
internal fun RemindDemo(active: Boolean) {
    val c = Noted.colors
    val t = Noted.type
    var place by remember { mutableStateOf(false) }
    val banner = remember { Animatable(0f) }
    val bell = remember { Animatable(0f) }
    val pulse = remember { Animatable(1f) }

    LaunchedEffect(active) {
        place = false; banner.snapTo(0f); bell.snapTo(0f); pulse.snapTo(1f)
        if (!active) return@LaunchedEffect
        coroutineScope {
            while (true) {
                delay(900)
                launch { bell.animateTo(0f, Motion.touch(), initialVelocity = 900f) }
                banner.animateTo(1f, Motion.glide())
                delay(1700)
                banner.animateTo(0f, Motion.glide())
                delay(250)
                place = true
                delay(500)
                repeat(2) { pulse.snapTo(0f); pulse.animateTo(1f, Motion.glide()) }
                banner.animateTo(1f, Motion.glide())
                delay(1700)
                banner.animateTo(0f, Motion.glide())
                delay(250)
                place = false
            }
        }
    }

    val note = c.note(NoteColor.Sky)
    val ink2 = c.noteInk2
    Box(Modifier.demoFrame()) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 54.dp)
                .fillMaxWidth()
                .softShadow(Shadows.float(c.isDark).fade(.55f), 30.dp, note)
                .clip(Shapes.card)
                .background(note)
                .padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 22.dp),
        ) {
            Text("להחזיר ספרים לספרייה", style = t.cardTitle, color = c.noteInk, maxLines = 1)
            Spacer(Modifier.height(8.dp))
            Text("שלושה ספרים, עד יום חמישי", style = t.snippet, color = c.noteInk2, maxLines = 1)
            Spacer(Modifier.height(12.dp))
            AnimatedContent(
                targetState = place,
                transitionSpec = {
                    (fadeIn(Motion.glide()) + slideInVertically(Motion.glide()) { it })
                        .togetherWith(fadeOut(Motion.glide()) + slideOutVertically(Motion.glide()) { -it })
                },
                label = "when",
            ) { atPlace ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        Modifier
                            .size(16.dp)
                            .drawBehind {
                                if (!atPlace) return@drawBehind
                                val p = pulse.value
                                if (p < 1f) drawCircle(ink2.copy(alpha = .5f * (1f - p)), radius = size.width / 2 + 14.dp.toPx() * p, style = Stroke(1.5.dp.toPx()))
                            }
                            .graphicsLayer {
                                if (!atPlace) {
                                    rotationZ = bell.value
                                    transformOrigin = TransformOrigin(.5f, .1f)
                                }
                            },
                    ) { Icon(if (atPlace) Ph.MapPin else Ph.Bell, null, Modifier.size(16.dp), tint = c.noteInk2) }
                    Text(if (atPlace) "כשמגיעים לספרייה" else "מחר · 9:00", style = t.meta, color = c.noteInk2)
                }
            }
        }

        Banner(
            icon = if (place) Ph.MapPinFill else Ph.BellRinging,
            meta = if (place) "noted · הגעת לספרייה" else "noted · 9:00",
            title = "להחזיר ספרים לספרייה",
            actions = if (place) listOf("סיימתי", "מחר") else listOf("סיימתי", "עוד שעה"),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp)
                .graphicsLayer {
                    val b = banner.value
                    translationY = (1f - b) * -(size.height + 16.dp.toPx())
                    alpha = b.coerceIn(0f, 1f)
                },
        )
    }
}

/** A notification as it lands at the top of the screen — the app's own words and actions. */
@Composable
private fun Banner(icon: ImageVector, meta: String, title: String, actions: List<String>, modifier: Modifier) {
    val c = Noted.colors
    val t = Noted.type
    Row(
        modifier
            .fillMaxWidth()
            .softShadow(Shadows.float(c.isDark).fade(.7f), 26.dp, c.surface)
            .clip(Shapes.lg)
            .background(c.surface)
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(36.dp).clip(Shapes.sm).background(c.accentSoft), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(20.dp), tint = c.accent)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(meta, style = t.tiny, color = c.ink3, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            Text(title, style = t.label, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                actions.forEach { Text(it, style = t.caption, color = c.accent) }
            }
        }
    }
}
