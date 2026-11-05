package com.ohav.noted.ui.places

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ohav.noted.data.PlaceEntity
import com.ohav.noted.data.PlaceKind
import com.ohav.noted.design.Btn
import com.ohav.noted.design.BtnKind
import com.ohav.noted.design.Motion
import com.ohav.noted.design.Noted
import com.ohav.noted.design.Ph
import com.ohav.noted.design.Shapes
import com.ohav.noted.design.Squircle
import com.ohav.noted.design.VSpace
import com.ohav.noted.design.press
import com.ohav.noted.places.Bt
import com.ohav.noted.places.BtDevice
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/** How a device trigger reads, by kind: a car is got into / out of, the rest connect. */
object DeviceWords {
    fun arrive(kind: PlaceKind) = if (kind == PlaceKind.Car) "כשאכנס לרכב" else "כשיתחבר"
    fun arriveSub(kind: PlaceKind) = if (kind == PlaceKind.Car) "הטלפון מתחבר לרכב" else "בחיבור הבלוטות'"
    fun leave(kind: PlaceKind) = if (kind == PlaceKind.Car) "כשאצא מהרכב" else "כשיתנתק"
    fun leaveSub(kind: PlaceKind) = if (kind == PlaceKind.Car) "הרכב כבה / התנתק" else "בניתוק הבלוטות'"
    fun status(connected: Boolean, kind: PlaceKind) = when {
        connected && kind == PlaceKind.Car -> "מחובר עכשיו · בנסיעה"
        connected -> "מחובר עכשיו"
        kind == PlaceKind.Car -> "חונה · לא מחובר"
        else -> "לא מחובר"
    }
}

/** What the scene is doing: looking for devices, linked (driving), or parked. */
enum class SceneMode { Searching, Connected, Parked }

/**
 * The device hero: a dark panel with the device in the middle. For a car — a road whose lane
 * marks run under it (fast while searching, a calm cruise when linked, still when parked), the
 * body bobbing on its springs, headlights on, Bluetooth waves rising off the roof. Other devices
 * float in place with the same waves. Time is read only while drawing: no recomposition per frame.
 */
@Composable
fun DeviceScene(kind: PlaceKind, mode: SceneMode, modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = 176.dp) {
    val c = Noted.colors
    val car = kind == PlaceKind.Car
    val icon = rememberVectorPainter(kind.icon())
    val bt = rememberVectorPainter(if (mode == SceneMode.Parked) Ph.BluetoothSlash else Ph.Bluetooth)
    // road speed (marks per second) and how "alive" the scene is — both settle on glide, never jump
    val speed = remember { Animatable(0f) }
    val live = remember { Animatable(0f) }
    LaunchedEffect(mode) {
        val s = when (mode) { SceneMode.Searching -> 2.4f; SceneMode.Connected -> .9f; SceneMode.Parked -> 0f }
        coroutineScope {
            launch { speed.animateTo(s, Motion.glide()) }
            launch { live.animateTo(if (mode == SceneMode.Parked) 0f else 1f, Motion.glide()) }
        }
    }
    val time = remember { mutableFloatStateOf(0f) }
    val road = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            androidx.compose.runtime.withFrameNanos { now ->
                if (last != 0L) {
                    val dt = ((now - last) / 1e9f).coerceAtMost(.05f)
                    time.floatValue += dt
                    road.floatValue = (road.floatValue + dt * speed.value) % 1f
                }
                last = now
            }
        }
    }
    val sky = c.inverse
    val ink = c.inverseInk
    val dim = c.inverseInk2
    val accent = c.accent
    val searching = mode == SceneMode.Searching

    Box(modifier.fillMaxWidth().height(height).clip(Squircle(28.dp)).background(sky)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val t = time.floatValue
            val lv = live.value
            // a faint horizon glow behind the device
            drawRect(Brush.verticalGradient(0f to Color.Transparent, .7f to accent.copy(alpha = .10f * lv), 1f to Color.Transparent))

            val cx = w / 2f
            val groundY = if (car) h * .78f else h * .62f
            val bodyW = if (car) 132.dp.toPx() else 70.dp.toPx()
            val bodyH = bodyW
            val bob = if (car) sin(t * 9f) * 1.6.dp.toPx() * (speed.value / 2.4f).coerceIn(0f, 1f) * 1.4f
            else sin(t * 2.2f) * 4.dp.toPx() * lv

            if (car) {
                // the road: a band, an edge line, lane marks flowing toward the back of the car (to the right)
                drawRect(Color.White.copy(alpha = .05f), topLeft = Offset(0f, groundY - 2.dp.toPx()), size = Size(w, h - groundY + 2.dp.toPx()))
                drawLine(dim.copy(alpha = .35f), Offset(0f, groundY), Offset(w, groundY), 1.5.dp.toPx())
                val dash = 26.dp.toPx()
                val gap = 22.dp.toPx()
                val period = dash + gap
                val y = groundY + (h - groundY) * .55f
                var x = -period + road.floatValue * period
                while (x < w) {
                    // marks fade toward the panel's edges
                    val mid = 1f - (kotlin.math.abs((x + dash / 2) - cx) / (w / 2)).coerceIn(0f, 1f)
                    drawLine(dim.copy(alpha = .15f + .45f * mid), Offset(x, y), Offset(x + dash, y), 3.dp.toPx(), cap = StrokeCap.Round)
                    x += period
                }
                // speed streaks behind the car while it moves
                val sp = (speed.value / 2.4f).coerceIn(0f, 1f)
                if (sp > .02f) for (i in 0 until 3) {
                    val yy = groundY - bodyH * (.28f + i * .12f)
                    val len = (18 + i * 8).dp.toPx() * sp
                    val x0 = cx + bodyW * .5f + 8.dp.toPx() + ((t * 140f + i * 37f) % 30f).dp.toPx() * .3f
                    drawLine(dim.copy(alpha = .35f * sp), Offset(x0, yy), Offset(x0 + len, yy), 2.dp.toPx(), cap = StrokeCap.Round)
                }
            }

            val left = cx - bodyW / 2f
            val top = groundY - bodyH * (if (car) .80f else 1f) + bob
            // headlights: a soft cone ahead (the car faces left — forward in a right-to-left world)
            if (car && lv > .01f) {
                val hx = left + bodyW * .06f
                val hy = groundY - bodyH * .22f + bob
                val cone = Path().apply {
                    moveTo(hx, hy - 3.dp.toPx())
                    lineTo(hx - 92.dp.toPx(), hy - 18.dp.toPx())
                    lineTo(hx - 92.dp.toPx(), hy + 22.dp.toPx())
                    lineTo(hx, hy + 4.dp.toPx())
                    close()
                }
                drawPath(cone, Brush.horizontalGradient(listOf(Color.Transparent, Color(0xFFFFE7A8).copy(alpha = .30f * lv)), startX = hx - 92.dp.toPx(), endX = hx))
                drawCircle(Color(0xFFFFE7A8).copy(alpha = .9f * lv), 3.dp.toPx(), Offset(hx + 2.dp.toPx(), hy))
            }
            // the device itself (the car mirrored so it faces left)
            translate(left, top) {
                if (car) scale(-1f, 1f, pivot = Offset(bodyW / 2f, bodyH / 2f)) {
                    with(icon) { draw(Size(bodyW, bodyH), alpha = .55f + .45f * max(lv, .0f), colorFilter = ColorFilter.tint(ink)) }
                } else with(icon) { draw(Size(bodyW, bodyH), alpha = .6f + .4f * lv, colorFilter = ColorFilter.tint(ink)) }
            }
            // the shadow under it
            drawOval(Color.Black.copy(alpha = .28f), topLeft = Offset(cx - bodyW * .38f, groundY - 3.dp.toPx()), size = Size(bodyW * .76f, 7.dp.toPx()))

            // Bluetooth: waves off the roof — a sweep while searching, a calm pulse when linked
            val wx = cx + (if (car) bodyW * .06f else 0f)
            val wy = top + bodyH * (if (car) .22f else .0f)
            if (lv > .01f) {
                val period = if (searching) .9f else 1.8f
                for (k in 0 until 3) {
                    val p = ((t / period + k / 3f) % 1f)
                    val r = 14.dp.toPx() + p * 46.dp.toPx()
                    val a = (1f - p) * .55f * lv
                    drawArc(
                        accent.copy(alpha = a), startAngle = 200f, sweepAngle = 140f, useCenter = false,
                        topLeft = Offset(wx - r, wy - r), size = Size(r * 2, r * 2), style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
                if (searching) rotate(t * 220f % 360f, Offset(wx, wy)) {
                    drawArc(
                        Brush.sweepGradient(listOf(Color.Transparent, accent.copy(alpha = .35f), Color.Transparent), Offset(wx, wy)),
                        startAngle = 0f, sweepAngle = 70f, useCenter = true,
                        topLeft = Offset(wx - 64.dp.toPx(), wy - 64.dp.toPx()), size = Size(128.dp.toPx(), 128.dp.toPx()),
                    )
                }
            }
            // the badge: Bluetooth in accent when linked, struck through and dim when parked
            val br = 15.dp.toPx()
            val bc = Offset(wx, wy - 4.dp.toPx())
            val badge = if (lv > .5f) accent else Color.White.copy(alpha = .14f)
            drawCircle(badge, br, bc)
            translate(bc.x - 9.dp.toPx(), bc.y - 9.dp.toPx()) {
                with(bt) { draw(Size(18.dp.toPx(), 18.dp.toPx()), colorFilter = ColorFilter.tint(if (lv > .5f) c.accentInk else dim)) }
            }
            if (searching) {
                val sp = 1f + .18f * sin(t * 2f * PI.toFloat() / .9f)
                drawCircle(accent.copy(alpha = .25f), br * sp + 4.dp.toPx(), bc, style = Stroke(1.5.dp.toPx()))
            }
        }
    }
}

/**
 * Picking a paired device: asks for "nearby devices" when needed, turns Bluetooth on, then lists
 * what the phone is paired with — cars first, the linked one marked live. The scene drives while
 * it looks (at least a moment, so it doesn't flash).
 */
@Composable
fun DevicePicker(saved: List<PlaceEntity>, onPick: (BtDevice) -> Unit, onSaved: (PlaceEntity) -> Unit) {
    val ctx = LocalContext.current
    val c = Noted.colors
    val t = Noted.type
    val haptics = Noted.haptics
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    var asked by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val permitted = remember(tick) { Bt.permitted(ctx) }
    val on = remember(tick) { Bt.on(ctx) }
    var devices by remember { mutableStateOf<List<BtDevice>?>(null) }
    LaunchedEffect(tick, permitted, on) {
        if (!permitted || !on) return@LaunchedEffect
        val started = System.currentTimeMillis()
        val list = Bt.paired(ctx)
        delay(max(0L, 900L - (System.currentTimeMillis() - started)))
        devices = list
    }
    val state = when {
        !Bt.available(ctx) -> 0
        !permitted -> 1
        !on -> 2
        devices == null -> 3
        else -> 4
    }
    val byAddress = remember(saved) { saved.filter { it.isDevice }.associateBy { it.device } }

    AnimatedContent(
        state,
        transitionSpec = { (fadeIn(Motion.glide()) togetherWith fadeOut(Motion.glide())).using(SizeTransform { _, _ -> Motion.glide() }) },
        label = "devState",
    ) { st ->
        Column(Modifier.fillMaxWidth()) {
            when (st) {
                0 -> {
                    DeviceScene(PlaceKind.Car, SceneMode.Parked)
                    VSpace(18.dp)
                    Text("אין בלוטות' במכשיר הזה", style = t.barTitle, color = c.ink)
                }
                1 -> {
                    DeviceScene(PlaceKind.Car, SceneMode.Parked)
                    VSpace(18.dp)
                    Text("לדעת מתי נכנסים לרכב", style = t.barTitle, color = c.ink)
                    VSpace(6.dp)
                    Text(
                        "צריך אישור ל״מכשירים בקרבת מקום״ — רק כדי לראות למה הטלפון מתחבר. noted לא סורק, לא מתחבר ולא שולח כלום.",
                        style = t.sub, color = c.ink2,
                    )
                    VSpace(18.dp)
                    Btn(if (asked) "פתיחת ההגדרות" else "אישור", Modifier.fillMaxWidth(), icon = Ph.Bluetooth, kind = BtnKind.Dark) {
                        haptics.light()
                        val perm = Bt.permission
                        if (perm == null) tick++
                        else if (!asked) { asked = true; ask.launch(perm) }
                        else runCatching {
                            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                }
                2 -> {
                    DeviceScene(PlaceKind.Car, SceneMode.Parked)
                    VSpace(18.dp)
                    Text("הבלוטות' כבוי", style = t.barTitle, color = c.ink)
                    VSpace(6.dp)
                    Text("מדליקים אותו כדי לראות את המכשירים המוצמדים.", style = t.sub, color = c.ink2)
                    VSpace(18.dp)
                    Btn("הדלקת בלוטות'", Modifier.fillMaxWidth(), icon = Ph.Bluetooth, kind = BtnKind.Dark) {
                        haptics.light()
                        runCatching {
                            ctx.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }.onFailure {
                            runCatching { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }
                    }
                }
                3 -> {
                    DeviceScene(PlaceKind.Car, SceneMode.Searching)
                    VSpace(18.dp)
                    LoadingLine("מחפש את הרכב שלך…")
                }
                else -> {
                    val list = devices.orEmpty()
                    val live = list.firstOrNull { it.connected && it.kind == PlaceKind.Car } ?: list.firstOrNull { it.connected }
                    DeviceScene(live?.kind ?: PlaceKind.Car, if (live != null) SceneMode.Connected else SceneMode.Parked)
                    VSpace(14.dp)
                    if (list.isEmpty()) {
                        Text("אין מכשירים מוצמדים", style = t.barTitle, color = c.ink)
                        VSpace(6.dp)
                        Text("מצמידים את הרכב לטלפון בהגדרות הבלוטות', וחוזרים לכאן.", style = t.sub, color = c.ink2)
                        VSpace(16.dp)
                        Btn("הגדרות בלוטות'", Modifier.fillMaxWidth(), icon = Ph.Bluetooth, kind = BtnKind.Dark) {
                            runCatching { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }
                    } else {
                        Text(
                            if (live != null) "״${live.name}״ מחובר עכשיו" else "המכשירים המוצמדים",
                            style = t.section, color = c.ink2, modifier = Modifier.padding(bottom = 4.dp, start = 2.dp),
                        )
                        list.forEachIndexed { i, d ->
                            val have = byAddress[d.address]
                            DeviceRow(d, have != null, last = i == list.lastIndex) {
                                if (have != null) { haptics.light(); onSaved(have) } else { haptics.tick(); onPick(d) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingLine(text: String) {
    val c = Noted.colors
    val t = Noted.type
    // three dots that breathe in turn
    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = androidx.compose.runtime.withFrameNanos { it }
        while (true) androidx.compose.runtime.withFrameNanos { time.floatValue = (it - start) / 1e9f }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Canvas(Modifier.size(34.dp, 10.dp)) {
            for (i in 0 until 3) {
                val p = (sin((time.floatValue * 5f) - i * .9f) + 1f) / 2f
                drawCircle(c.accent.copy(alpha = .3f + .7f * p), 3.dp.toPx() + 1.2.dp.toPx() * p, Offset(size.width - (5 + i * 12).dp.toPx(), size.height / 2))
            }
        }
        Text(text, style = t.label, color = c.ink2)
    }
}

@Composable
private fun DeviceRow(d: BtDevice, saved: Boolean, last: Boolean, onClick: () -> Unit) {
    val c = Noted.colors
    val t = Noted.type
    Column(Modifier.fillMaxWidth().press(scale = .97f, haptic = false, onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box {
                PlaceBadge(d.kind, 46.dp, on = d.connected)
                if (d.connected) LiveDot(Modifier.align(Alignment.TopEnd))
            }
            Column(Modifier.weight(1f)) {
                Text(d.name, style = t.body.copy(fontWeight = FontWeight.SemiBold), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(d.kind.label, if (d.connected) "מחובר עכשיו" else "מוצמד", if (saved) "כבר שמור" else null).joinToString(" · "),
                    style = t.caption, color = if (d.connected) c.accent else c.ink3, maxLines = 1,
                )
            }
            Icon(if (saved) Ph.CheckCircle else Ph.Plus, null, Modifier.size(20.dp), tint = if (saved) c.ink3 else c.ink2)
        }
        if (!last) Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
    }
}

/** A small accent dot with a ring that keeps widening — "linked right now". */
@Composable
fun LiveDot(modifier: Modifier = Modifier) {
    val c = Noted.colors
    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = androidx.compose.runtime.withFrameNanos { it }
        while (true) androidx.compose.runtime.withFrameNanos { time.floatValue = (it - start) / 1e9f }
    }
    Canvas(modifier.size(14.dp)) {
        val p = (time.floatValue / 1.6f) % 1f
        drawCircle(c.accent.copy(alpha = (1f - p) * .5f), size.minDimension / 2 * (.5f + .5f * p))
        drawCircle(c.surface, 4.5.dp.toPx())
        drawCircle(c.accent, 3.dp.toPx())
    }
}

/** Naming the picked device and its kind. A car gets "הרכב" unless it has a name of its own. */
@Composable
fun DeviceForm(d: BtDevice, confirm: String, onSave: (name: String, kind: PlaceKind) -> Unit) {
    val c = Noted.colors
    val t = Noted.type
    val haptics = Noted.haptics
    var kind by remember { mutableStateOf(d.kind) }
    var name by remember { mutableStateOf(if (d.kind == PlaceKind.Car) "הרכב" else d.name) }
    val linked by Bt.connected.collectAsStateWithLifecycle()
    val connected = d.connected || d.address in linked
    Column(Modifier.fillMaxWidth()) {
        DeviceScene(kind, if (connected) SceneMode.Connected else SceneMode.Parked)
        VSpace(8.dp)
        Text(
            d.name + " · " + DeviceWords.status(connected, kind),
            style = t.caption, color = if (connected) c.accent else c.ink3, modifier = Modifier.padding(start = 4.dp),
        )
        VSpace(16.dp)
        NameField(name) { name = it }
        VSpace(14.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlaceKind.devices.forEach { k ->
                val on = k == kind
                val bg by animateColorAsState(if (on) c.inverse else c.bg, Motion.glide(), label = "dkBg")
                val fg by animateColorAsState(if (on) c.inverseInk else c.ink2, Motion.glide(), label = "dkFg")
                Column(
                    Modifier.weight(1f).press(scale = .92f, haptic = false) {
                        if (on) return@press
                        haptics.tick()
                        // the default name follows the kind, unless it was typed in
                        if (name == "הרכב" || name == d.name || PlaceKind.devices.any { it.label == name }) name = if (k == PlaceKind.Car) "הרכב" else d.name
                        kind = k
                    }.clip(Squircle(18.dp)).background(bg).padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(k.icon(), null, Modifier.size(24.dp), tint = fg)
                    Spacer(Modifier.height(6.dp))
                    Text(k.label, style = t.caption, color = fg, maxLines = 1)
                }
            }
        }
        VSpace(20.dp)
        Btn(confirm, Modifier.fillMaxWidth(), icon = Ph.Bluetooth, kind = BtnKind.Dark) {
            if (name.isBlank()) { haptics.reject(); return@Btn }
            haptics.confirm()
            onSave(name.trim(), kind)
        }
    }
}

@Composable
private fun NameField(value: String, onChange: (String) -> Unit) {
    val c = Noted.colors
    val t = Noted.type
    Box(Modifier.fillMaxWidth().clip(Shapes.md).background(c.bg).padding(horizontal = 16.dp, vertical = 15.dp)) {
        if (value.isEmpty()) Text("שם", style = t.field.copy(fontWeight = FontWeight.SemiBold), color = c.ink3)
        BasicTextField(
            value = value, onValueChange = { onChange(it.take(40)) },
            textStyle = t.field.copy(fontWeight = FontWeight.SemiBold, color = c.ink),
            cursorBrush = SolidColor(c.accent), singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Live link state of a saved device: the broadcasts, refreshed from the profiles on open. */
@Composable
fun rememberLinked(address: String): Boolean {
    val ctx = LocalContext.current
    val linked by Bt.connected.collectAsStateWithLifecycle()
    LaunchedEffect(address) { Bt.connectedNow(ctx) }
    return address in linked
}
