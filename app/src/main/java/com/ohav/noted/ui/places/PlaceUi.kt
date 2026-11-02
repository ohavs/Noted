package com.ohav.noted.ui.places

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
import com.ohav.noted.places.Found
import com.ohav.noted.places.Geo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun PlaceKind.icon(): ImageVector = when (this) {
    PlaceKind.Home -> Ph.House
    PlaceKind.Work -> Ph.Briefcase
    PlaceKind.Super -> Ph.ShoppingCart
    PlaceKind.Pharmacy -> Ph.Pill
    PlaceKind.Family -> Ph.Users
    PlaceKind.Sport -> Ph.Barbell
    PlaceKind.Cafe -> Ph.Coffee
    PlaceKind.Food -> Ph.ForkKnife
    PlaceKind.Study -> Ph.GraduationCap
    PlaceKind.Shop -> Ph.Storefront
    PlaceKind.Park -> Ph.Tree
    PlaceKind.Pray -> Ph.Church
    PlaceKind.City -> Ph.Buildings
    PlaceKind.Other -> Ph.MapPin
}

/** A place's icon on a squircle tile; [on] = the chosen one. */
@Composable
fun PlaceBadge(kind: PlaceKind, size: Dp = 44.dp, on: Boolean = false) {
    val c = Noted.colors
    val bg by animateColorAsState(if (on) c.inverse else c.accentSoft, Motion.glide(), label = "badgeBg")
    val fg by animateColorAsState(if (on) c.inverseInk else c.accent, Motion.glide(), label = "badgeFg")
    Box(Modifier.size(size).clip(Squircle(size * .34f)).background(bg), contentAlignment = Alignment.Center) {
        Icon(kind.icon(), null, Modifier.size(size * .5f), tint = fg)
    }
}

/** A first guess at the kind from the name — the user can always pick another. */
fun guessKind(name: String, places: List<PlaceEntity>): PlaceKind {
    val n = name.lowercase()
    fun has(vararg w: String) = w.any { it in n }
    return when {
        has("סופר-פארם", "סופר פארם", "superpharm", "בית מרקחת", "פארם", "pharm") -> PlaceKind.Pharmacy
        has("סופר", "שופרסל", "רמי לוי", "יוחננוף", "ויקטורי", "טיב טעם", "אושר עד", "מגה", "חצי חינם", "יינות ביתן", "carrefour", "קרפור", "market", "מרקט", "מכולת") -> PlaceKind.Super
        has("קפה", "cafe", "coffee", "ארומה", "קופיקס", "גרג") -> PlaceKind.Cafe
        has("מסעד", "פיצ", "בורגר", "שווארמ", "פלאפל", "סושי", "restaurant") -> PlaceKind.Food
        has("כושר", "gym", "הולמס", "בריכה", "סטודיו") -> PlaceKind.Sport
        has("אוניברסיט", "מכללה", "בית ספר", "בית הספר", "גן ילדים", "ישיבה") -> PlaceKind.Study
        has("בית כנסת", "בית הכנסת") -> PlaceKind.Pray
        has("פארק", "גן לאומי", "יער") -> PlaceKind.Park
        has("קניון", "חנות", "mall") -> PlaceKind.Shop
        else -> PlaceKind.Other
    }
}

/** The saved place sitting on the same spot, so "the supermarket" isn't saved twice. */
fun existingAt(places: List<PlaceEntity>, lat: Double, lng: Double): PlaceEntity? =
    places.minByOrNull { Geo.distance(it.lat, it.lng, lat, lng) }?.takeIf { Geo.distance(it.lat, it.lng, lat, lng) <= maxOf(60f, it.radius * .5f) }

// ------------------------------------------------------------------ finder

/**
 * Where is it: the current location in one tap, an address / business search, or a pasted link
 * from Google Maps / Waze. Hands back a point; saving it is the caller's next step.
 */
@Composable
fun ColumnScope.PlaceFinder(autoLocate: Boolean = false, onFound: (Found) -> Unit) {
    val ctx = LocalContext.current
    val c = Noted.colors
    val t = Noted.type
    val haptics = Noted.haptics
    val scope = rememberCoroutineScope()
    val fm = LocalFocusManager.current
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Found>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var locating by remember { mutableStateOf(false) }

    fun locate() {
        if (!Geo.locationOn(ctx)) { haptics.reject(); note = "המיקום כבוי בטלפון — מדליקים אותו מהגדרות מהירות"; return }
        locating = true
        note = null
        scope.launch {
            val l = Geo.current(ctx)
            if (l == null) {
                locating = false
                haptics.reject()
                note = "לא הצלחנו לאתר אותך. נסו שוב בחוץ, או חפשו כתובת."
                return@launch
            }
            val addr = Geo.reverse(ctx, l.latitude, l.longitude).orEmpty()
            locating = false
            haptics.confirm()
            onFound(Found(addr.substringBefore(",").trim().ifEmpty { "המיקום הנוכחי" }, addr, l.latitude, l.longitude))
        }
    }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { g ->
        if (g.values.any { it }) locate() else { haptics.reject(); note = "בלי הרשאת מיקום — אפשר לחפש כתובת או להדביק קישור" }
    }

    fun tapLocate() {
        if (locating) return
        if (Geo.hasFine(ctx) || Geo.hasCoarse(ctx)) locate()
        // fine alone is dropped without a dialog from Android 12 — ask for both
        else perm.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    LaunchedEffect(Unit) { if (autoLocate) tapLocate() }

    // search as you type: a link / coordinates resolve directly, words go to the geocoder
    LaunchedEffect(q) {
        val text = q.trim()
        note = null
        if (text.length < 2) { results = emptyList(); searching = false; return@LaunchedEffect }
        delay(450)
        searching = true
        Geo.parse(text)?.let { (lat, lng) ->
            val addr = Geo.reverse(ctx, lat, lng).orEmpty()
            results = listOf(Found(addr.substringBefore(",").trim().ifEmpty { "הנקודה מהקישור" }, addr, lat, lng))
            searching = false
            return@LaunchedEffect
        }
        if (text.startsWith("http", ignoreCase = true) || Geo.isShortLink(text)) {
            val (pt, named) = Geo.expand(text)
            if (pt != null) {
                val addr = Geo.reverse(ctx, pt.first, pt.second).orEmpty()
                results = listOf(Found(named?.substringBefore(",")?.trim()?.ifEmpty { null } ?: addr.substringBefore(",").trim().ifEmpty { "הנקודה מהקישור" }, addr, pt.first, pt.second))
            } else if (named != null) {
                results = Geo.search(ctx, named, Geo.last(ctx)).take(5)
            } else results = emptyList()
            if (results.isEmpty()) note = "לא מצאנו נקודה בקישור. נסו ״שיתוף ← העתקת קישור״ מהמפה, או כתובת."
            searching = false
            return@LaunchedEffect
        }
        results = Geo.search(ctx, text, Geo.last(ctx)).take(6)
        searching = false
        if (results.isEmpty()) note = "לא נמצא. כתובת מלאה עובדת הכי טוב — רחוב, מספר ועיר."
    }

    // ---- current location
    Row(
        Modifier.fillMaxWidth().press(scale = .97f) { tapLocate() }.clip(Shapes.md).background(c.inverse).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val spin = rememberInfiniteTransition(label = "locSpin")
        val a by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "locA")
        Icon(Ph.Crosshair, null, Modifier.size(24.dp).graphicsLayer { rotationZ = if (locating) a else 0f }, tint = c.inverseInk)
        Column(Modifier.weight(1f)) {
            Text(if (locating) "מאתר…" else "המיקום הנוכחי", style = t.bodyStrong, color = c.inverseInk)
            Text("שומרים את המקום שבו אתם עומדים", style = t.caption, color = c.inverseInk2)
        }
    }
    VSpace(10.dp)

    // ---- search
    Row(
        Modifier.fillMaxWidth().height(56.dp).clip(Shapes.md).background(c.bg).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Ph.MagnifyingGlass, null, Modifier.size(20.dp), tint = c.ink2)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (q.isEmpty()) Text("כתובת, מקום או קישור ממפות", style = t.field, color = c.ink3, maxLines = 1)
            BasicTextField(
                value = q, onValueChange = { q = it.take(300) }, singleLine = true,
                textStyle = t.field.copy(color = c.ink),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { fm.clearFocus() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (q.isNotEmpty()) Icon(Ph.X, "ניקוי", Modifier.size(20.dp).press(scale = .8f) { q = "" }, tint = c.ink3)
    }

    AnimatedContent(
        targetState = Triple(searching, results, note),
        transitionSpec = { fadeIn(Motion.glide()) togetherWith fadeOut(Motion.glide()) },
        label = "finderResults",
    ) { (busy, list, msg) ->
        Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            when {
                busy -> Text("מחפש…", style = t.caption, color = c.ink3, modifier = Modifier.padding(start = 4.dp, top = 10.dp))
                msg != null -> Text(msg, style = t.caption, color = c.ink2, modifier = Modifier.padding(start = 4.dp, top = 10.dp))
                else -> list.forEachIndexed { i, f ->
                    FoundRow(f, last = i == list.lastIndex) { fm.clearFocus(); haptics.light(); onFound(f) }
                }
            }
        }
    }
}

@Composable
private fun FoundRow(f: Found, last: Boolean, onClick: () -> Unit) {
    val c = Noted.colors
    val t = Noted.type
    Column(Modifier.fillMaxWidth().press(scale = .97f, haptic = false, onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(Ph.MapPin, null, Modifier.size(22.dp), tint = c.ink2)
            Column(Modifier.weight(1f)) {
                Text(f.name, style = t.body.copy(fontWeight = FontWeight.SemiBold), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (f.address.isNotEmpty()) Text(f.address, style = t.caption, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (!last) Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
    }
}

// ------------------------------------------------------------------ form

/**
 * Name, kind and radius for a new or edited place. The kind sets the radius until the radius is
 * touched by hand.
 */
@Composable
fun ColumnScope.PlaceForm(
    name: String,
    kind: PlaceKind,
    radius: Int?,
    address: String,
    confirm: String,
    onSave: (name: String, kind: PlaceKind, radius: Int) -> Unit,
) {
    val c = Noted.colors
    val t = Noted.type
    val haptics = Noted.haptics
    val fm = LocalFocusManager.current
    var text by remember { mutableStateOf(name) }
    var k by remember { mutableStateOf(kind) }
    var r by remember { mutableStateOf(radius) }
    val rad = r ?: k.radius
    val ok = text.isNotBlank()
    fun save() {
        if (!ok) { haptics.reject(); return }
        fm.clearFocus()
        haptics.confirm()
        onSave(text.trim(), k, rad)
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PlaceBadge(k, 58.dp)
        Box(
            Modifier
                .weight(1f)
                .height(58.dp)
                .clip(Shapes.md)
                .background(c.bg)
                .drawBehind {
                    val o = Shapes.md.createOutline(size, layoutDirection, this)
                    drawOutline(o, c.line, style = Stroke(3.dp.toPx()))
                }
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (text.isEmpty()) Text("שם המקום", style = t.field.copy(fontWeight = FontWeight.SemiBold), color = c.ink3)
            BasicTextField(
                value = text, onValueChange = { if (it.length <= 32) text = it }, singleLine = true,
                textStyle = t.field.copy(color = c.ink, fontWeight = FontWeight.SemiBold),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { fm.clearFocus() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    if (address.isNotEmpty()) {
        Row(Modifier.padding(top = 10.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Ph.MapPin, null, Modifier.size(15.dp), tint = c.ink3)
            Text(address, style = t.caption, color = c.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
    VSpace(18.dp)
    Text("סוג", style = t.section, color = c.ink2, modifier = Modifier.padding(bottom = 10.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PlaceKind.entries.chunked(7).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { kk ->
                    val on = kk == k
                    val bg by animateColorAsState(if (on) c.inverse else c.bg, Motion.glide(), label = "kindBg")
                    val fg by animateColorAsState(if (on) c.inverseInk else c.ink2, Motion.glide(), label = "kindFg")
                    Box(
                        Modifier.weight(1f).height(46.dp).press(scale = .86f, haptic = false) {
                            haptics.tick()
                            // a typed-over name stays; the kind's own name follows the kind
                            if (text.isBlank() || PlaceKind.entries.any { it.label == text.trim() }) text = kk.label
                            k = kk
                        }.clip(Squircle(15.dp)).background(bg),
                        contentAlignment = Alignment.Center,
                    ) { Icon(kk.icon(), kk.label, Modifier.size(22.dp), tint = fg) }
                }
            }
        }
    }
    Text(k.label, style = t.caption, color = c.ink3, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
    VSpace(18.dp)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("רדיוס", style = t.section, color = c.ink2, modifier = Modifier.weight(1f))
        Text(if (r == null) "אוטומטי · ${Geo.fmtRadius(rad)}" else Geo.fmtRadius(rad), style = t.caption, color = c.ink3)
    }
    VSpace(10.dp)
    RadiusRow(rad) { r = it; haptics.tick() }
    VSpace(22.dp)
    val a by animateFloatAsState(if (ok) 1f else .4f, Motion.glide(), label = "placeOk")
    Btn(confirm, Modifier.fillMaxWidth().graphicsLayer { alpha = a }, icon = Ph.Check, kind = BtnKind.Dark) { save() }
    VSpace(8.dp)
}

@Composable
fun RadiusRow(selected: Int, onPick: (Int) -> Unit) {
    val c = Noted.colors
    val t = Noted.type
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PlaceKind.RADII.forEach { m ->
            val on = m == selected
            val bg by animateColorAsState(if (on) c.inverse else c.bg, Motion.glide(), label = "radBg")
            val fg by animateColorAsState(if (on) c.inverseInk else c.ink2, Motion.glide(), label = "radFg")
            Box(
                Modifier.weight(1f).height(44.dp).press(scale = .9f, haptic = false) { onPick(m) }.clip(Shapes.sm).background(bg),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // the dot grows with the radius — a glance says "small / big"
                    Box(Modifier.size((4 + PlaceKind.RADII.indexOf(m) * 2).dp).clip(CircleShape).background(fg.copy(alpha = .5f)))
                    Text(Geo.fmtRadius(m), style = t.tiny, color = fg, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

/** The small "place" line used in chips: icon + text, on a dark rounded tag. */
@Composable
fun PlaceTag(text: String, leave: Boolean, modifier: Modifier = Modifier) {
    val c = Noted.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(if (leave) Ph.SignOut else Ph.MapPin, null, Modifier.size(16.dp), tint = c.noteInk2)
        Text(text, style = Noted.type.meta, color = c.noteInk2, maxLines = 1)
    }
}

