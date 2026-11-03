package com.ohav.noted.ui.reminders

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ohav.noted.NotedApp
import com.ohav.noted.data.PlaceEntity
import com.ohav.noted.data.PlaceKind
import com.ohav.noted.data.PlaceReminderEntity
import com.ohav.noted.design.Btn
import com.ohav.noted.design.BtnKind
import com.ohav.noted.design.Motion
import com.ohav.noted.design.Noted
import com.ohav.noted.design.NotedSwitch
import com.ohav.noted.design.Ph
import com.ohav.noted.design.Shapes
import com.ohav.noted.design.Squircle
import com.ohav.noted.design.press
import com.ohav.noted.places.Found
import com.ohav.noted.places.Geo
import com.ohav.noted.ui.places.PlaceBadge
import com.ohav.noted.ui.places.PlaceFinder
import com.ohav.noted.ui.places.PlaceForm
import com.ohav.noted.ui.places.existingAt
import com.ohav.noted.ui.places.guessKind
import kotlinx.coroutines.launch

/** Words for place reminders — chips, summaries and the notification all say it the same way. */
object PlaceWords {
    /** Sun–Thu: the Israeli work week. */
    val WORKWEEK = setOf(7, 1, 2, 3, 4)

    fun trigger(leave: Boolean) = if (leave) "ביציאה" else "בהגעה"

    fun days(d: Set<Int>): String = when {
        d.isEmpty() || d.size == 7 -> ""
        d == WORKWEEK -> "א׳–ה׳"
        d == setOf(5, 6) -> "סופ״ש"
        else -> (0 until 7).map { if (it == 0) 7 else it }.filter { it in d }.joinToString(" ") { When.DAYS[it % 7] }
    }

    fun hm(min: Int) = "%02d:%02d".format(min / 60, min % 60)

    fun window(r: PlaceReminderEntity): String = window(r.daySet, r.fromMin, r.toMin)

    fun window(d: Set<Int>, from: Int?, to: Int?): String =
        listOf(days(d), if (from != null && to != null) "${hm(from)}–${hm(to)}" else "").filter { it.isNotEmpty() }.joinToString(" ")

    /** "בהגעה · סופר" for a chip. */
    fun chip(r: PlaceReminderEntity, place: PlaceEntity?): String = trigger(r.leave) + " · " + (place?.name ?: "מקום")
}

@Stable
internal class PlacePickModel(val existing: PlaceReminderEntity?, startWithFinder: Boolean) {
    enum class Step { Choose, Find, Form }

    var placeId by mutableStateOf(existing?.placeId)
    var leave by mutableStateOf(existing?.leave ?: false)
    var always by mutableStateOf(existing?.always ?: false)
    var windowOn by mutableStateOf(existing?.hasWindow ?: false)
    var days by mutableStateOf(existing?.daySet?.ifEmpty { null } ?: PlaceWords.WORKWEEK)
    var from by mutableStateOf(existing?.fromMin ?: (8 * 60))
    var to by mutableStateOf(existing?.toMin ?: (18 * 60))
    var step by mutableStateOf(if (startWithFinder && existing == null) Step.Find else Step.Choose)
    var found by mutableStateOf<Found?>(null)
    var saved = false
    private val initial = listOf(placeId, leave, always, windowOn, days, from, to)
    val dirty get() = listOf(placeId, leave, always, windowOn, days, from, to) != initial
}

@Composable
internal fun PlacePickBody(app: NotedApp, m: PlacePickModel, line: String?, onSet: () -> Unit, onRemove: (() -> Unit)?) {
    val c = Noted.colors
    val t = Noted.type
    val haptics = Noted.haptics
    val scope = rememberCoroutineScope()
    val places by app.places.places.collectAsStateWithLifecycle(initialValue = null)
    val list = places ?: return

    AnimatedContent(
        targetState = m.step,
        transitionSpec = { (fadeIn(Motion.glide()) togetherWith fadeOut(Motion.glide())).using(SizeTransform { _, _ -> Motion.glide() }) },
        label = "placeStep",
    ) { step ->
        Column(Modifier.fillMaxWidth()) {
            when (step) {
                PlacePickModel.Step.Find -> {
                    if (list.isNotEmpty()) BackRow("המקומות השמורים") { m.step = PlacePickModel.Step.Choose }
                    else Text("איפה להזכיר?", style = t.section, color = c.ink2, modifier = Modifier.padding(bottom = 10.dp))
                    PlaceFinder { f ->
                        val same = existingAt(list, f.lat, f.lng)
                        if (same != null) {
                            m.placeId = same.id
                            m.step = PlacePickModel.Step.Choose
                        } else {
                            m.found = f
                            m.step = PlacePickModel.Step.Form
                        }
                    }
                }
                PlacePickModel.Step.Form -> {
                    val f = m.found
                    BackRow("חיפוש אחר") { m.step = PlacePickModel.Step.Find }
                    if (f != null) {
                        val kind = remember(f) {
                            guessKind(f.name, list, f.types).let { k ->
                                // the first place saved from where you stand is most likely home
                                if (k == PlaceKind.Other && list.none { it.kind == PlaceKind.Home.key } && f.address.isNotEmpty()) PlaceKind.Home else k
                            }
                        }
                        PlaceForm(
                            name = if (kind == PlaceKind.Other) f.name else kind.label.takeIf { kind == PlaceKind.Home } ?: f.name,
                            kind = kind, radius = null, address = f.address, confirm = "שמירת המקום", at = f.lat to f.lng,
                        ) { name, k, r ->
                            scope.launch {
                                val p = app.places.create(name, k.key, f.lat, f.lng, r, f.address)
                                m.placeId = p.id
                                m.step = PlacePickModel.Step.Choose
                            }
                        }
                    }
                }
                PlacePickModel.Step.Choose -> ChooseBody(m, list, line, onSet, onRemove)
            }
        }
    }
}

@Composable
private fun BackRow(label: String, onClick: () -> Unit) {
    val c = Noted.colors
    Row(
        Modifier.padding(bottom = 12.dp).press(scale = .95f, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Ph.CaretStart, null, Modifier.size(18.dp), tint = c.ink2)
        Text(label, style = Noted.type.label, color = c.ink2)
    }
}

@Composable
private fun ChooseBody(m: PlacePickModel, list: List<PlaceEntity>, line: String?, onSet: () -> Unit, onRemove: (() -> Unit)?) {
    val c = Noted.colors
    val t = Noted.type
    val haptics = Noted.haptics
    val scope = rememberCoroutineScope()
    val chosen = list.firstOrNull { it.id == m.placeId }

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

    // ---- where
    Text("איפה", style = t.section, color = c.ink2, modifier = Modifier.padding(bottom = 10.dp))
    LazyRow(
        modifier = Modifier.bleed(20),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "+") {
            PlaceTile(Ph.MapPinPlus, "מקום חדש", on = false) { haptics.light(); m.step = PlacePickModel.Step.Find }
        }
        items(list, key = { it.id }) { p ->
            PlaceTile(null, p.name, on = p.id == m.placeId, kind = PlaceKind.of(p.kind)) {
                haptics.tick()
                m.placeId = p.id
            }
        }
    }
    AnimatedContent(chosen, transitionSpec = { fadeIn(Motion.glide()) togetherWith fadeOut(Motion.glide()) }, label = "chosenAddr") { p ->
        Text(
            p?.let { listOf(it.address, "רדיוס ${Geo.fmtRadius(it.radius)}").filter { s -> s.isNotEmpty() }.joinToString(" · ") } ?: "בוחרים מקום שמור, או מוסיפים חדש",
            style = t.caption, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp, start = 4.dp),
        )
    }
    Spacer(Modifier.height(22.dp))

    // ---- when
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BigChoice(Ph.SignIn, "כשאגיע", "בכניסה לאזור", !m.leave, Modifier.weight(1f)) { m.leave = false }
        BigChoice(Ph.SignOut, "כשאצא", "ביציאה מהאזור", m.leave, Modifier.weight(1f)) { m.leave = true }
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BigChoice(Ph.Bell, "פעם אחת", "ואז היא נחה", !m.always, Modifier.weight(1f)) { m.always = false }
        BigChoice(Ph.Repeat, "בכל פעם", "כל הגעה מחדש", m.always, Modifier.weight(1f)) { m.always = true }
    }
    Spacer(Modifier.height(18.dp))

    // ---- days & hours
    Row(
        Modifier.fillMaxWidth().press(scale = .97f) { m.windowOn = !m.windowOn }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Ph.Clock, null, Modifier.size(22.dp), tint = c.ink2)
        Column(Modifier.weight(1f)) {
            Text("רק בימים ובשעות", style = t.body.copy(fontWeight = FontWeight.SemiBold), color = c.ink)
            Text(if (m.windowOn) PlaceWords.window(m.days, m.from, m.to) else "בכל זמן", style = t.caption, color = c.ink3)
        }
        NotedSwitch(m.windowOn)
    }
    AnimatedVisibility(
        m.windowOn,
        enter = expandVertically(Motion.glide()) + fadeIn(Motion.glide()),
        exit = shrinkVertically(Motion.glide()) + fadeOut(Motion.glide()),
    ) { WindowPicker(m) }
    Spacer(Modifier.height(24.dp))

    // ---- summary + set
    val sub = chosen?.let {
        (if (m.leave) "כשתצא · " else "כשתגיע · ") + it.name + " · " + (if (m.always) "בכל פעם" else "פעם אחת") +
            (if (m.windowOn) " · " + PlaceWords.window(m.days, m.from, m.to) else "")
    } ?: "עוד לא נבחר מקום"
    Text(sub, style = t.label, color = if (chosen == null) c.ink3 else c.ink2, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp), textAlign = TextAlign.Center)
    val shake = remember { Animatable(0f) }
    Btn(
        if (chosen == null) "קבע תזכורת" else "קבע תזכורת · ${chosen.name}",
        Modifier.fillMaxWidth().graphicsLayer {
            translationX = shake.value * 8.dp.toPx()
            alpha = if (chosen == null) .45f else 1f
        },
        icon = Ph.MapPin, kind = BtnKind.Dark,
    ) {
        if (chosen == null) {
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

/** Lets a row run edge to edge of the sheet (past its 20dp padding). */
private fun Modifier.bleed(dp: Int) = layout { measurable, constraints ->
    val px = dp.dp.roundToPx()
    val p = measurable.measure(constraints.copy(minWidth = constraints.maxWidth + 2 * px, maxWidth = constraints.maxWidth + 2 * px))
    layout(constraints.maxWidth, p.height) { p.place(-px, 0) }
}

@Composable
private fun PlaceTile(icon: ImageVector?, label: String, on: Boolean, kind: PlaceKind = PlaceKind.Other, onClick: () -> Unit) {
    val c = Noted.colors
    val t = Noted.type
    val bg by animateColorAsState(if (on) c.inverse else c.bg, Motion.glide(), label = "ptBg")
    val fg by animateColorAsState(if (on) c.inverseInk else c.ink, Motion.glide(), label = "ptFg")
    Column(
        Modifier.width(86.dp).height(92.dp).press(scale = .92f, haptic = false, onClick = onClick).clip(Squircle(20.dp)).background(bg).padding(horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Box(Modifier.size(40.dp).clip(Squircle(14.dp)).background(c.surface), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(22.dp), tint = c.ink)
            }
        } else PlaceBadge(kind, 40.dp, on = false)
        Text(label, style = t.caption, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun BigChoice(icon: ImageVector, name: String, sub: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Noted.colors
    val t = Noted.type
    val haptics = Noted.haptics
    val bg by animateColorAsState(if (on) c.inverse else c.bg, Motion.glide(), label = "bcBg")
    val fg by animateColorAsState(if (on) c.inverseInk else c.ink, Motion.glide(), label = "bcFg")
    val fg2 by animateColorAsState(if (on) c.inverseInk2 else c.ink2, Motion.glide(), label = "bcFg2")
    Row(
        modifier.press(scale = .95f, haptic = false) { if (!on) { haptics.tick(); onClick() } }.clip(Shapes.md).background(bg).padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = fg)
        Column {
            Text(name, style = t.label, color = fg, maxLines = 1)
            Text(sub, style = t.caption, color = fg2, maxLines = 1)
        }
    }
}

private val PRESETS = listOf(
    Triple("ימי חול", PlaceWords.WORKWEEK, 8 * 60 to 18 * 60),
    Triple("בערב", (1..7).toSet(), 17 * 60 to 22 * 60),
    Triple("סופ״ש", setOf(5, 6), 9 * 60 to 21 * 60),
)

@Composable
private fun WindowPicker(m: PlacePickModel) {
    val c = Noted.colors
    val t = Noted.type
    val haptics = Noted.haptics
    Column(Modifier.padding(top = 10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PRESETS.forEach { (name, d, range) ->
                val on = m.days == d && m.from == range.first && m.to == range.second
                val bg by animateColorAsState(if (on) c.inverse else c.bg, Motion.glide(), label = "preBg")
                val fg by animateColorAsState(if (on) c.inverseInk else c.ink2, Motion.glide(), label = "preFg")
                Column(
                    Modifier.weight(1f).press(scale = .93f) { m.days = d; m.from = range.first; m.to = range.second }
                        .clip(Shapes.sm).background(bg).padding(vertical = 9.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(name, style = t.label, color = fg, maxLines = 1)
                    Text("${PlaceWords.hm(range.first)}–${PlaceWords.hm(range.second)}", style = t.tiny, color = fg.copy(alpha = .7f), maxLines = 1)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            for (i in 0 until 7) {
                val iso = if (i == 0) 7 else i
                val on = iso in m.days
                val bg by animateColorAsState(if (on) c.inverse else c.bg, Motion.glide(), label = "pwdBg")
                val fg by animateColorAsState(if (on) c.inverseInk else c.ink2, Motion.glide(), label = "pwdFg")
                Box(
                    Modifier.size(42.dp).press(scale = .86f, haptic = false) {
                        if (on && m.days.size == 1) { haptics.reject(); return@press }
                        haptics.toggle(!on)
                        m.days = if (on) m.days - iso else m.days + iso
                    }.clip(CircleShape).background(bg),
                    contentAlignment = Alignment.Center,
                ) { Text(When.DAYS[i], style = t.label, color = fg) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            DragTime("מ־", m.from, Modifier.weight(1f)) { m.from = it }
            Box(Modifier.width(14.dp).height(2.dp).background(c.ink3))
            DragTime("עד", m.to, Modifier.weight(1f)) { m.to = it }
        }
        Text("גוררים את השעה למעלה או למטה", style = t.caption, color = c.ink3, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
    }
}

/** A time you drag: 15 minutes per notch, a tick on each. */
@Composable
private fun DragTime(label: String, value: Int, modifier: Modifier, onChange: (Int) -> Unit) {
    val c = Noted.colors
    val t = Noted.type
    val haptics = Noted.haptics
    val cur by rememberUpdatedState(value)
    val set by rememberUpdatedState(onChange)
    var acc by remember { mutableFloatStateOf(0f) }
    Row(
        modifier
            .clip(Shapes.md)
            .background(c.bg)
            .pointerInput(Unit) {
                val step = 14.dp.toPx()
                detectVerticalDragGestures(onDragEnd = { acc = 0f }, onDragCancel = { acc = 0f }) { ch, dy ->
                    ch.consume()
                    acc -= dy
                    while (acc >= step) { acc -= step; set(Math.floorMod(cur + 15, 24 * 60)); haptics.tick() }
                    while (acc <= -step) { acc += step; set(Math.floorMod(cur - 15, 24 * 60)); haptics.tick() }
                }
            }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, style = t.label, color = c.ink3)
        Text(
            PlaceWords.hm(value), style = t.barTitle.copy(fontFeatureSettings = "tnum"), color = c.ink,
            modifier = Modifier.weight(1f), textAlign = TextAlign.End,
        )
    }
}
