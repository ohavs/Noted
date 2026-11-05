package com.ohav.noted.ui.places

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.ohav.noted.data.PlaceKind
import com.ohav.noted.data.PlaceEntity
import com.ohav.noted.design.Motion
import com.ohav.noted.design.Noted
import com.ohav.noted.design.Ph
import com.ohav.noted.design.Shapes
import com.ohav.noted.design.press

/**
 * The catalog's shelves. One shelf per kind left a heading over every single place ("בית" over
 * "בית"); these gather kinds that live together, so a few places make a few tidy cards.
 * Keys are stored (folded shelves) — never rename them.
 */
enum class Shelf(val key: String, val label: String, val kinds: List<PlaceKind>) {
    Fixed("fixed", "הקבועים", listOf(PlaceKind.Home, PlaceKind.Family, PlaceKind.Work, PlaceKind.Study, PlaceKind.Pray)),
    Errands("errands", "סידורים וקניות", listOf(PlaceKind.Super, PlaceKind.Pharmacy, PlaceKind.Shop)),
    Out("out", "בילוי וספורט", listOf(PlaceKind.Cafe, PlaceKind.Food, PlaceKind.Sport, PlaceKind.Park)),
    Cities("cities", "ערים", listOf(PlaceKind.City)),
    Other("other", "עוד מקומות", listOf(PlaceKind.Other)),
    Devices("devices", "רכב ובלוטות'", PlaceKind.devices),
    ;

    val icon: ImageVector
        get() = when (this) {
            Fixed -> Ph.House
            Errands -> Ph.Storefront
            Out -> Ph.Coffee
            Cities -> Ph.Buildings
            Other -> Ph.MapPin
            Devices -> Ph.Bluetooth
        }

    companion object {
        fun of(kind: PlaceKind): Shelf = entries.firstOrNull { kind in it.kinds } ?: Other

        /** Places on their shelves, shelves in order; inside one, by kind and then as saved. */
        fun sort(places: List<PlaceEntity>): List<Pair<Shelf, List<PlaceEntity>>> =
            places.groupBy { of(PlaceKind.of(it.kind)) }.toSortedMap(compareBy { it.ordinal })
                .map { (s, ps) -> s to ps.sortedBy { s.kinds.indexOf(PlaceKind.of(it.kind)) } }
    }
}

/** Which shelves are folded — this phone's own view, so plain prefs, not the synced DB. */
class Folds(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("places_ui", Context.MODE_PRIVATE)
    private val state = mutableStateOf(prefs.getStringSet("folded", emptySet()).orEmpty().toSet())

    fun folded(s: Shelf) = s.key in state.value

    fun toggle(s: Shelf) {
        val next = if (folded(s)) state.value - s.key else state.value + s.key
        state.value = next
        prefs.edit().putStringSet("folded", next).apply()
    }
}

@Composable
fun rememberFolds(): Folds {
    val ctx = LocalContext.current
    return remember { Folds(ctx.applicationContext) }
}

/**
 * One shelf as a card: a header that folds it (caret, count, and — folded — the names and how
 * many reminders wait inside), then its rows with hairlines between them.
 */
@Composable
fun ShelfCard(
    shelf: Shelf,
    places: List<PlaceEntity>,
    folded: Boolean,
    bells: (PlaceEntity) -> Int,
    onFold: () -> Unit,
    row: @Composable (PlaceEntity) -> Unit,
) {
    val c = Noted.colors
    val t = Noted.type
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // caret: down when open, toward the reading end when folded
    val turn by animateFloatAsState(if (folded) (if (rtl) 90f else -90f) else 0f, Motion.glide(), label = "caret")
    val total = places.sumOf(bells)

    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(Shapes.lg).background(c.bg)) {
        Row(
            Modifier.fillMaxWidth().press(scale = .98f, onClick = onFold).padding(start = 16.dp, end = 14.dp, top = 13.dp, bottom = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(shelf.icon, null, Modifier.size(18.dp), tint = c.ink2)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(shelf.label, style = t.label, color = c.ink, maxLines = 1)
                    Text("  ${places.size}", style = t.label, color = c.ink3, maxLines = 1)
                }
                AnimatedVisibility(
                    visible = folded,
                    enter = expandVertically(Motion.glide(), expandFrom = Alignment.Top) + fadeIn(Motion.glide()),
                    exit = shrinkVertically(Motion.glide(), shrinkTowards = Alignment.Top) + fadeOut(Motion.glide()),
                ) {
                    Text(
                        places.joinToString(" · ") { it.name },
                        style = t.caption, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            AnimatedVisibility(visible = folded && total > 0, enter = fadeIn(Motion.glide()), exit = fadeOut(Motion.glide())) {
                BellCount(total)
            }
            Icon(Ph.CaretDown, null, Modifier.size(16.dp).graphicsLayer { rotationZ = turn }, tint = c.ink3)
        }
        AnimatedVisibility(
            visible = !folded,
            enter = expandVertically(Motion.glide(), expandFrom = Alignment.Top) + fadeIn(Motion.glide()),
            exit = shrinkVertically(Motion.glide(), shrinkTowards = Alignment.Top) + fadeOut(Motion.glide()),
        ) {
            Column(Modifier.padding(bottom = 4.dp)) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(c.line))
                places.forEachIndexed { i, p ->
                    if (i > 0) Box(Modifier.fillMaxWidth().padding(start = 70.dp, end = 16.dp).height(1.dp).background(c.line))
                    Box(Modifier.padding(horizontal = 14.dp)) { row(p) }
                }
            }
        }
    }
}

/** How many reminders wait at a place (or a folded shelf). */
@Composable
fun BellCount(count: Int) {
    val c = Noted.colors
    val t = Noted.type
    Row(
        Modifier.clip(Shapes.sm).background(c.accentSoft).padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(Ph.BellRinging, null, Modifier.size(14.dp), tint = c.accent)
        Text("$count", style = t.label, color = c.accent)
    }
}
