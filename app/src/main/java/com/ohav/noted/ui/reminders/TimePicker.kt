package com.ohav.noted.ui.reminders

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.AbsoluteAlignment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ohav.noted.NotedApp
import com.ohav.noted.data.ReminderEntity
import com.ohav.noted.data.Repeat
import com.ohav.noted.design.Btn
import com.ohav.noted.design.BtnKind
import com.ohav.noted.design.IconButton
import com.ohav.noted.design.Motion
import com.ohav.noted.design.Noted
import com.ohav.noted.design.NotedSwitch
import com.ohav.noted.design.Overlays
import com.ohav.noted.design.Ph
import com.ohav.noted.design.Shapes
import com.ohav.noted.design.Squircle
import com.ohav.noted.design.ToastState
import com.ohav.noted.design.press
import com.ohav.noted.reminders.Alarm
import com.ohav.noted.reminders.Notifier
import com.ohav.noted.ui.home.fmtRel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

private const val DAYS_AHEAD = 120

@Stable
private class PickerModel(start: LocalDateTime, repeat: Repeat, val existing: ReminderEntity?) {
    var at by mutableStateOf(start)
    var repeat by mutableStateOf(repeat)
    var calOpen by mutableStateOf(false)
    var calMonth by mutableStateOf(YearMonth.from(start))
    var untilMonth by mutableStateOf(YearMonth.from(start).plusMonths(1))
    var asking by mutableStateOf(false)
    var saved = false
    private val initial = start to repeat
    val dirty get() = at != initial.first || repeat != initial.second
}

/**
 * Opens the reminder picker for a note, or for one line of it ([blockId]). The sheet guards
 * against losing a changed time; after saving it asks for whatever permission is missing.
 */
fun openReminderPicker(
    app: NotedApp, overlays: Overlays, toasts: ToastState, scope: CoroutineScope,
    noteId: String, blockId: String = "", line: String? = null,
) {
    scope.launch {
        val existing = app.reminders.get(noteId, blockId)
        val now = System.currentTimeMillis()
        val start = existing?.takeIf { it.occAt > now }?.let {
            Instant.ofEpochMilli(it.occAt).atZone(ZoneId.systemDefault()).toLocalDateTime()
        } ?: When.default()
        val m = PickerModel(start, existing?.repeat ?: Repeat.None, existing)
        val sheet = overlays.sheet
        val remove: (() -> Unit)? = existing?.let { old ->
            fun() {
                app.scope.launch(Dispatchers.Main) {
                    app.reminders.remove(noteId, blockId)
                    m.saved = true
                    sheet.close()
                    toasts.show("התזכורת הוסרה", Ph.BellSlash, action = "ביטול") {
                        app.scope.launch { app.reminders.restore(old) }
                    }
                }
            }
        }
        sheet.show(
            if (blockId.isEmpty()) "תזכורת" else "תזכורת לשורה",
            guard = {
                !m.dirty || m.saved || overlays.dialog.confirm(
                    "לצאת בלי לקבוע?", "הזמן שבחרת לא יישמר.", Ph.BellSlash,
                    confirm = "צא בלי לשמור", cancel = "המשך לבחור",
                )
            },
        ) {
            AnimatedContent(
                targetState = m.asking,
                transitionSpec = { (fadeIn(Motion.glide()) togetherWith fadeOut(Motion.glide())).using(SizeTransform { _, _ -> Motion.glide() }) },
                label = "pickerStep",
            ) { asking ->
                Column {
                    if (asking) PermissionCard(onDone = { sheet.close() })
                    else PickerBody(
                        m, line,
                        onSet = {
                            app.scope.launch(Dispatchers.Main) {
                                val r = app.reminders.set(noteId, blockId, m.at, m.repeat)
                                m.saved = true
                                toasts.show("תזכורת נקבעה · ${fmtRel(r.occAt)}", Ph.BellRinging)
                                if (Notifier.permitted(app) && Alarm.canExact(app)) sheet.close()
                                else { sheet.setTitle(""); m.asking = true }
                            }
                        },
                        onRemove = remove,
                    )
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.PickerBody(m: PickerModel, line: String?, onSet: () -> Unit, onRemove: (() -> Unit)?) {
    val c = Noted.colors
    val t = Noted.type
    val haptics = Noted.haptics
    val scope = rememberCoroutineScope()
    val today = LocalDate.now()
    val strip = rememberLazyListState(
        initialFirstVisibleItemIndex = (ChronoUnit.DAYS.between(today, m.at.toLocalDate()).toInt() - 2).coerceIn(0, DAYS_AHEAD - 1),
    )

    if (!line.isNullOrBlank()) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 16.dp).clip(Shapes.sm).background(c.bg).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Ph.CheckSquare, null, Modifier.size(20.dp), tint = c.ink2)
            Text(line, style = t.bodyStrong, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    // ---- shortcuts
    val shortcuts = remember { When.shortcuts() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        shortcuts.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { s ->
                    ShortcutTile(s, on = m.at == s.at, Modifier.weight(1f)) {
                        m.at = s.at
                        m.calMonth = YearMonth.from(s.at)
                        haptics.light()
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(22.dp))

    // ---- month label: the strip's month, or the open calendar's
    val stripMonth by remember { derivedStateOf { YearMonth.from(today.plusDays(strip.firstVisibleItemIndex + 1L)) } }
    val label = if (m.calOpen) m.calMonth else stripMonth
    val caret by animateFloatAsState(if (m.calOpen) 180f else 0f, Motion.glide(), label = "caret")
    Row(
        Modifier.press(scale = .96f) {
            m.calOpen = !m.calOpen
            if (m.calOpen) m.calMonth = YearMonth.from(m.at)
        }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("${When.MONTHS[label.monthValue - 1]} ${label.year}", style = t.bodyStrong, color = c.ink)
        Icon(Ph.CaretDown, null, Modifier.size(18.dp).graphicsLayer { rotationZ = caret }, tint = c.ink2)
    }
    Spacer(Modifier.height(10.dp))

    AnimatedContent(
        targetState = m.calOpen,
        transitionSpec = { (fadeIn(Motion.glide()) togetherWith fadeOut(Motion.glide())).using(SizeTransform { _, _ -> Motion.glide() }) },
        label = "cal",
    ) { open ->
        if (open) {
            MonthGrid(
                month = m.calMonth, selected = m.at.toLocalDate(), min = today,
                onMonth = { m.calMonth = it },
                onPick = { d -> m.at = d.atTime(m.at.toLocalTime()) },
            )
        } else {
            DayStrip(strip, today, m.at.toLocalDate()) { d -> m.at = d.atTime(m.at.toLocalTime()) }
        }
    }
    // keep the chosen day in view when a shortcut moves it
    LaunchedEffect(m.at.toLocalDate()) {
        val idx = ChronoUnit.DAYS.between(today, m.at.toLocalDate()).toInt()
        val vis = strip.layoutInfo.visibleItemsInfo
        if (idx in 0 until DAYS_AHEAD && vis.isNotEmpty() && (idx <= vis.first().index || idx >= vis.last().index)) {
            strip.animateScrollToItem((idx - 2).coerceAtLeast(0))
        }
    }
    Spacer(Modifier.height(18.dp))

    // ---- giant digits
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Digits(m.at.hour, stepPx = 18.dp) { d -> m.at = m.at.withHour(Math.floorMod(m.at.hour + d, 24)) }
            Text(":", style = digitStyle(), color = c.ink3, modifier = Modifier.padding(horizontal = 4.dp).padding(bottom = 10.dp))
            Digits(m.at.minute, stepPx = 9.dp) { d -> m.at = m.at.withMinute(Math.floorMod(m.at.minute + d, 60)) }
        }
    }
    Text(
        "גוררים למעלה או למטה", style = t.caption, color = c.ink3, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    )
    Spacer(Modifier.height(22.dp))

    // ---- repeat
    RepeatPicker(m)
    Spacer(Modifier.height(24.dp))

    // ---- summary + set
    val now = LocalDateTime.now()
    val past = m.at <= now
    val sub = When.until(m.at, now) + When.repeat(m.repeat).let { if (it.isEmpty()) "" else " · $it" }
    Text(sub, style = t.label, color = if (past) c.danger else c.ink2, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp), textAlign = TextAlign.Center)
    val shake = remember { Animatable(0f) }
    Btn(
        "קבע תזכורת · ${When.short(m.at)}",
        Modifier.fillMaxWidth().graphicsLayer {
            translationX = shake.value * 8.dp.toPx()
            alpha = if (past) .45f else 1f
        },
        icon = Ph.BellRinging, kind = BtnKind.Dark,
    ) {
        if (m.at <= LocalDateTime.now()) {
            haptics.reject()
            scope.launch { shake.snapTo(0f); shake.animateTo(0f, Motion.touch(), initialVelocity = 14f) }
        } else {
            haptics.confirm()
            onSet()
        }
    }
    if (onRemove != null) {
        Spacer(Modifier.height(6.dp))
        Btn("הסרת התזכורת", Modifier.fillMaxWidth(), icon = Ph.BellSlash, kind = BtnKind.Quiet, onClick = onRemove)
    }
}

private fun Modifier.bleed(x: Dp) = layout { measurable, constraints ->
    val px = x.roundToPx()
    val p = measurable.measure(constraints.copy(minWidth = constraints.maxWidth + 2 * px, maxWidth = constraints.maxWidth + 2 * px))
    layout(constraints.maxWidth, p.height) { p.place(-px, 0) }
}

@Composable
private fun ShortcutTile(s: When.Shortcut, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Noted.colors
    val t = Noted.type
    val bg by animateColorAsState(if (on) c.inverse else c.bg, Motion.glide(), label = "shortBg")
    val fg by animateColorAsState(if (on) c.inverseInk else c.ink, Motion.glide(), label = "shortFg")
    val fg2 by animateColorAsState(if (on) c.inverseInk2 else c.ink2, Motion.glide(), label = "shortFg2")
    Row(
        modifier.press(scale = .95f, onClick = onClick).clip(Shapes.md).background(bg).padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(s.icon, null, Modifier.size(24.dp), tint = fg)
        Column {
            Text(s.name, style = t.label, color = fg, maxLines = 1)
            Text(s.sub, style = t.caption, color = fg2, maxLines = 1)
        }
    }
}

@Composable
private fun DayStrip(state: LazyListState, today: LocalDate, selected: LocalDate, onPick: (LocalDate) -> Unit) {
    val haptics = Noted.haptics
    LazyRow(
        state = state,
        modifier = Modifier.bleed(20.dp),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(DAYS_AHEAD) { i ->
            val d = today.plusDays(i.toLong())
            DayTile(d, i == 0, d == selected) { onPick(d); haptics.tick() }
        }
    }
}

@Composable
private fun DayTile(d: LocalDate, today: Boolean, on: Boolean, onClick: () -> Unit) {
    val c = Noted.colors
    val t = Noted.type
    val bg by animateColorAsState(if (on) c.inverse else c.bg, Motion.glide(), label = "dayBg")
    val fg by animateColorAsState(if (on) c.inverseInk else if (today) c.accent else c.ink, Motion.glide(), label = "dayFg")
    Column(
        Modifier.width(56.dp).height(72.dp).press(scale = .92f, haptic = false, onClick = onClick).clip(Squircle(18.dp)).background(bg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(if (today) "היום" else When.day(d), style = t.caption, color = fg.copy(alpha = .75f), maxLines = 1)
        Text("${d.dayOfMonth}", style = t.barTitle, color = fg)
    }
}

/** A month of days, Sunday on the start side. Days before [min] are out of reach. */
@Composable
internal fun MonthGrid(month: YearMonth, selected: LocalDate?, min: LocalDate, onMonth: (YearMonth) -> Unit, onPick: (LocalDate) -> Unit) {
    val c = Noted.colors
    val t = Noted.type
    val haptics = Noted.haptics
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(Ph.CaretStart, "חודש קודם") { onMonth(month.minusMonths(1)); haptics.tick() }
            Text(
                "${When.MONTHS[month.monthValue - 1]} ${month.year}", style = t.bodyStrong, color = c.ink,
                textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
            )
            IconButton(Ph.CaretEnd, "חודש הבא") { onMonth(month.plusMonths(1)); haptics.tick() }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            When.DAYS.forEach { Text(it, style = t.caption, color = c.ink3, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) }
        }
        val first = month.atDay(1)
        val lead = first.dayOfWeek.value % 7
        val cells = lead + month.lengthOfMonth()
        val today = LocalDate.now()
        for (w in 0 until (cells + 6) / 7) {
            Row(Modifier.fillMaxWidth()) {
                for (k in 0 until 7) {
                    val n = w * 7 + k - lead + 1
                    Box(Modifier.weight(1f).height(46.dp), contentAlignment = Alignment.Center) {
                        if (n in 1..month.lengthOfMonth()) {
                            val d = month.atDay(n)
                            val out = d < min
                            val on = d == selected
                            val bg by animateColorAsState(if (on) c.inverse else c.inverse.copy(alpha = 0f), Motion.glide(), label = "calBg")
                            Box(
                                Modifier.size(40.dp).clip(CircleShape)
                                    .then(if (d == today && !on) Modifier.background(c.accentSoft) else Modifier)
                                    .background(bg)
                                    .press(enabled = !out, scale = .88f, haptic = false) { onPick(d); haptics.tick() },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "$n", style = t.label,
                                    color = when { on -> c.inverseInk; out -> c.ink3.copy(alpha = .5f); d == today -> c.accent; else -> c.ink },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun digitStyle() = Noted.type.hero.copy(fontSize = 80.sp, lineHeight = 84.sp, fontFeatureSettings = "tnum", fontWeight = FontWeight.Bold)

/** Two giant digits: drag up/down (a tick per step), or tap the top/bottom half for ±1. */
@Composable
private fun Digits(value: Int, stepPx: Dp, onStep: (Int) -> Unit) {
    val c = Noted.colors
    val haptics = Noted.haptics
    val step = with(LocalDensity.current) { stepPx.toPx() }
    val stepNow by rememberUpdatedState(onStep)
    var active by remember { mutableStateOf(false) }
    var dir by remember { mutableIntStateOf(1) }
    val bg by animateColorAsState(if (active) c.accentSoft else c.bg, Motion.glide(), label = "digitBg")
    val s by animateFloatAsState(if (active) 1.04f else 1f, Motion.touch(), label = "digitScale")
    fun bump(d: Int) {
        dir = d
        stepNow(d)
        haptics.tick()
    }
    Box(
        Modifier
            .graphicsLayer { scaleX = s; scaleY = s }
            .size(132.dp, 112.dp)
            .clip(Squircle(26.dp))
            .background(bg)
            .pointerInput(Unit) {
                detectTapGestures(onPress = { active = true; tryAwaitRelease(); active = false }) { o -> bump(if (o.y < size.height / 2f) 1 else -1) }
            }
            .pointerInput(step) {
                var acc = 0f
                var done = 0
                detectVerticalDragGestures(
                    onDragStart = { acc = 0f; done = 0; active = true },
                    onDragEnd = { active = false },
                    onDragCancel = { active = false },
                ) { change, dy ->
                    change.consume()
                    acc -= dy
                    val want = (acc / step).toInt()
                    while (done != want) {
                        val d = if (want > done) 1 else -1
                        done += d
                        bump(d)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = value,
            transitionSpec = {
                val d = dir
                (slideInVertically(Motion.touch()) { h -> d * h * 7 / 10 } + fadeIn(Motion.glide()))
                    .togetherWith(slideOutVertically(Motion.touch()) { h -> -d * h * 7 / 10 } + fadeOut(Motion.glide()))
                    .using(SizeTransform(clip = true))
            },
            label = "digits",
        ) { v -> Text("%02d".format(v), style = digitStyle(), color = c.ink) }
    }
}

private val KIND_LABELS = listOf(
    Repeat.Kind.None to "ללא", Repeat.Kind.Daily to "יומי", Repeat.Kind.Days to "ימים",
    Repeat.Kind.Monthly to "חודשי", Repeat.Kind.Custom to "מותאם",
)

@Composable
private fun RepeatPicker(m: PickerModel) {
    val c = Noted.colors
    val t = Noted.type
    val haptics = Noted.haptics
    val density = LocalDensity.current
    val r = m.repeat
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Ph.Repeat, null, Modifier.size(20.dp), tint = c.ink2)
        Text("חזרה", style = t.section, color = c.ink2)
    }
    Spacer(Modifier.height(8.dp))

    // tabs with a sliding underline (measured, absolute — the underline doesn't flip in RTL)
    val xs = remember { mutableStateMapOf<Repeat.Kind, Pair<Float, Float>>() }
    val cur = xs[r.kind]
    val ux by animateDpAsState(with(density) { (cur?.first ?: 0f).toDp() }, Motion.glide(), label = "ulineX")
    val uw by animateDpAsState(with(density) { (cur?.second ?: 0f).toDp() }, Motion.glide(), label = "ulineW")
    Box(Modifier.fillMaxWidth().clip(Shapes.md).background(c.bg)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            KIND_LABELS.forEach { (k, label) ->
                val on = r.kind == k
                val col by animateColorAsState(if (on) c.ink else c.ink3, Motion.glide(), label = "tabCol")
                Box(
                    Modifier.weight(1f).height(48.dp)
                        .onPlaced { lc -> xs[k] = lc.positionInParent().x + with(density) { 4.dp.toPx() } to lc.size.width.toFloat() }
                        .press(scale = .92f, haptic = false) {
                            if (k == r.kind) return@press
                            haptics.tick()
                            m.repeat = when (k) {
                                Repeat.Kind.None -> Repeat.None
                                Repeat.Kind.Days -> Repeat(k, days = setOf(m.at.dayOfWeek.value), until = r.until)
                                Repeat.Kind.Custom -> Repeat(k, every = 2, unit = Repeat.Step.Day, until = r.until)
                                else -> Repeat(k, until = r.until)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) { Text(label, style = t.label, color = col, maxLines = 1) }
            }
        }
        if (cur != null) {
            Box(
                Modifier.align(AbsoluteAlignment.BottomLeft).absoluteOffset(x = ux + uw * .2f, y = (-7).dp)
                    .size(uw * .6f, 3.dp).clip(RoundedCornerShape(2.dp)).background(c.accent),
            )
        }
    }

    AnimatedVisibility(
        r.kind == Repeat.Kind.Days,
        enter = expandVertically(Motion.glide()) + fadeIn(Motion.glide()),
        exit = shrinkVertically(Motion.glide()) + fadeOut(Motion.glide()),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            for (i in 0 until 7) {
                val iso = if (i == 0) 7 else i
                val on = iso in r.days
                val bg by animateColorAsState(if (on) c.inverse else c.bg, Motion.glide(), label = "wdBg")
                val fg by animateColorAsState(if (on) c.inverseInk else c.ink2, Motion.glide(), label = "wdFg")
                Box(
                    Modifier.size(42.dp).press(scale = .86f, haptic = false) {
                        if (on && r.days.size == 1) { haptics.reject(); return@press }
                        haptics.toggle(!on)
                        m.repeat = r.copy(days = if (on) r.days - iso else r.days + iso)
                    }.clip(CircleShape).background(bg),
                    contentAlignment = Alignment.Center,
                ) { Text(When.DAYS[i], style = t.label, color = fg) }
            }
        }
    }

    AnimatedVisibility(
        r.kind == Repeat.Kind.Custom,
        enter = expandVertically(Motion.glide()) + fadeIn(Motion.glide()),
        exit = shrinkVertically(Motion.glide()) + fadeOut(Motion.glide()),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("כל", style = t.bodyStrong, color = c.ink)
            Row(Modifier.clip(Shapes.md).background(c.bg), verticalAlignment = Alignment.CenterVertically) {
                IconButton(Ph.Minus, "פחות") {
                    if (r.every <= 1) haptics.reject() else { haptics.tick(); m.repeat = r.copy(every = r.every - 1) }
                }
                AnimatedContent(r.every, transitionSpec = { fadeIn(Motion.glide()) togetherWith fadeOut(Motion.glide()) }, label = "every") { n ->
                    Text("$n", style = t.barTitle, color = c.ink, textAlign = TextAlign.Center, modifier = Modifier.width(34.dp))
                }
                IconButton(Ph.Plus, "יותר") {
                    if (r.every >= 99) haptics.reject() else { haptics.tick(); m.repeat = r.copy(every = r.every + 1) }
                }
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(Repeat.Step.Day to "ימים", Repeat.Step.Week to "שבועות", Repeat.Step.Month to "חודשים").forEach { (u, label) ->
                    val on = r.unit == u
                    val bg by animateColorAsState(if (on) c.inverse else c.bg, Motion.glide(), label = "unitBg")
                    val fg by animateColorAsState(if (on) c.inverseInk else c.ink2, Motion.glide(), label = "unitFg")
                    Box(
                        Modifier.weight(1f).height(44.dp).press(scale = .92f) { m.repeat = r.copy(unit = u) }.clip(Shapes.sm).background(bg),
                        contentAlignment = Alignment.Center,
                    ) { Text(label, style = t.caption, color = fg, maxLines = 1) }
                }
            }
        }
    }

    AnimatedVisibility(
        r.repeats,
        enter = expandVertically(Motion.glide()) + fadeIn(Motion.glide()),
        exit = shrinkVertically(Motion.glide()) + fadeOut(Motion.glide()),
    ) {
        Column(Modifier.padding(top = 14.dp)) {
            Row(
                Modifier.fillMaxWidth().press(scale = .97f) {
                    m.repeat = if (r.until == null) {
                        val u = m.at.toLocalDate().plusMonths(1)
                        m.untilMonth = YearMonth.from(u)
                        r.copy(until = u)
                    } else r.copy(until = null)
                }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Ph.CalendarCheck, null, Modifier.size(22.dp), tint = c.ink2)
                Column(Modifier.weight(1f)) {
                    Text("עד תאריך", style = t.body.copy(fontWeight = FontWeight.SemiBold), color = c.ink)
                    Text(
                        r.until?.let { "${When.dayFull(it)}, ${it.dayOfMonth} ב${When.MONTHS[it.monthValue - 1]}" } ?: "בלי סוף",
                        style = t.caption, color = c.ink3,
                    )
                }
                NotedSwitch(r.until != null)
            }
            AnimatedVisibility(
                r.until != null,
                enter = expandVertically(Motion.glide()) + fadeIn(Motion.glide()),
                exit = shrinkVertically(Motion.glide()) + fadeOut(Motion.glide()),
            ) {
                MonthGrid(
                    month = m.untilMonth, selected = r.until, min = m.at.toLocalDate(),
                    onMonth = { m.untilMonth = it },
                    onPick = { d -> m.repeat = r.copy(until = d) },
                )
            }
        }
    }
}
