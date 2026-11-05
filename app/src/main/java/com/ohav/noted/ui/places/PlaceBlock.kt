package com.ohav.noted.ui.places

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ohav.noted.NotedApp
import com.ohav.noted.data.BlockType
import com.ohav.noted.data.MediaExtra
import com.ohav.noted.data.PlaceEntity
import com.ohav.noted.data.PlaceInfo
import com.ohav.noted.data.PlaceKind
import com.ohav.noted.design.Noted
import com.ohav.noted.design.Overlays
import com.ohav.noted.design.Ph
import com.ohav.noted.design.SheetRow
import com.ohav.noted.design.Squircle
import com.ohav.noted.design.ToastState
import com.ohav.noted.design.VSpace
import com.ohav.noted.design.press
import com.ohav.noted.places.PlacesApi
import com.ohav.noted.ui.editor.EditorState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * A place inside a note: a still map of the spot, its name and address, and a way to get there.
 * Self-contained (name, address, point) so it reads the same for everyone the note is shared with,
 * whether or not the place is in their own catalog. [compact] = the home card's slim row, no map.
 */
@Composable
fun PlaceBlockCard(p: PlaceInfo, compact: Boolean = false, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val c = Noted.colors
    val t = Noted.type
    val ctx = LocalContext.current
    val kind = PlaceKind.of(p.kind)
    Column(
        modifier
            .fillMaxWidth()
            .clip(Squircle(18.dp))
            .background(c.noteInk.copy(alpha = .07f))
            .press(scale = .98f) { onClick?.invoke() ?: navigate(ctx, p) },
    ) {
        if (!compact && PlacesApi.enabled) MiniMap(p.lat, p.lng, 120, kind, Modifier.fillMaxWidth().height(132.dp), ring = false)
        Row(Modifier.padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (compact || !PlacesApi.enabled) {
                PlaceBadge(kind, 40.dp)
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    p.name.ifBlank { p.address.ifBlank { "מקום" } },
                    style = t.note.copy(fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 21.sp, textDirection = TextDirection.Content),
                    color = c.noteInk, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (p.address.isNotBlank() && p.address != p.name) {
                    Text(p.address, style = t.meta, color = c.noteInk2, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(8.dp))
            // its own target: the card itself opens the sheet in the editor, this always drives there
            Box(
                Modifier.size(44.dp).clip(Squircle(14.dp)).background(c.noteInk.copy(alpha = .08f)).press(scale = .88f) { navigate(ctx, p) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Ph.NavigationArrowFill, "ניווט", Modifier.size(20.dp), tint = c.noteInk)
            }
        }
    }
}

/** Hands the point to the phone's maps / navigation app (Google Maps, Waze — whatever handles geo:). */
fun navigate(ctx: Context, p: PlaceInfo) {
    val q = Uri.encode(p.name.ifBlank { p.address })
    runCatching {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${p.lat},${p.lng}?q=${p.lat},${p.lng}($q)")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

fun PlaceEntity.info() = PlaceInfo(name, address, lat, lng, kind)

/** Sheet for a place card in the editor: drive there, copy the address, keep it, swap or remove it. */
fun placeBlockSheet(app: NotedApp, overlays: Overlays, toasts: ToastState, scope: CoroutineScope, state: EditorState, blockId: String, p: PlaceInfo) {
    overlays.sheet.show(p.name.ifBlank { "מקום" }) {
        val all by app.places.places.collectAsStateWithLifecycle(emptyList())
        val saved = remember(all) { existingAt(all, p.lat, p.lng) }
        SheetRow(Ph.NavigationArrow, "ניווט") { overlays.sheet.close(); navigate(app, p) }
        SheetRow(Ph.CopySimple, "העתקת הכתובת") {
            overlays.sheet.close()
            val text = p.address.ifBlank { "${p.lat}, ${p.lng}" }
            (app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText(p.name, text))
            toasts.show("הכתובת הועתקה", Ph.CopySimple)
        }
        if (saved == null) {
            SheetRow(Ph.MapPinPlus, "שמירה במקומות שלי") {
                overlays.sheet.close()
                scope.launch {
                    app.places.create(p.name.ifBlank { p.address }, p.kind, p.lat, p.lng, 150, p.address)
                    toasts.show("״${p.name.ifBlank { "המקום" }}״ נשמר", PlaceKind.of(p.kind).icon())
                }
            }
        }
        if (!state.readOnly) {
            SheetRow(Ph.ArrowsClockwise, "מקום אחר") { pickPlace(app, overlays, toasts) { np -> state.patchExtra(blockId, MediaExtra.ofPlace(np)) } }
            SheetRow(Ph.Trash, "הסרה מהפתק", danger = true, last = true) { overlays.sheet.close(); state.delete(blockId) }
        }
    }
}

/** Adds a place card under the focused line. */
fun EditorState.placeSheet(app: NotedApp, overlays: Overlays, toasts: ToastState) {
    val at = focused
    pickPlace(app, overlays, toasts) { p -> insertMedia(BlockType.Place, MediaExtra.ofPlace(p), at) }
}

/**
 * Where: search an address or business, the current location, a pasted map link — or one of my
 * saved places, listed under the search.
 */
fun pickPlace(app: NotedApp, overlays: Overlays, toasts: ToastState, onPick: (PlaceInfo) -> Unit) {
    overlays.sheet.show("מקום") {
        val all by app.places.places.collectAsStateWithLifecycle(emptyList())
        val haptics = Noted.haptics
        PlaceFinder { f ->
            overlays.sheet.close()
            val same = existingAt(all, f.lat, f.lng)
            onPick(same?.info() ?: PlaceInfo(f.name, f.address, f.lat, f.lng, guessKind(f.name, all, f.types).key))
            haptics.confirm()
        }
        val spots = remember(all) { all.filterNot { it.isDevice } }
        if (spots.isNotEmpty()) SavedPlaces(spots) { p ->
            overlays.sheet.close()
            onPick(p.info())
            haptics.confirm()
        }
    }
}

@Composable
private fun ColumnScope.SavedPlaces(all: List<PlaceEntity>, onPick: (PlaceEntity) -> Unit) {
    val c = Noted.colors
    val t = Noted.type
    VSpace(14.dp)
    Text("מהמקומות שלי", style = t.section, color = c.ink2, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
    Column(Modifier.animateContentSize()) {
        all.forEachIndexed { i, p ->
            Row(
                Modifier.fillMaxWidth().press(scale = .98f) { onPick(p) }.padding(horizontal = 20.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlaceBadge(PlaceKind.of(p.kind), 40.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = t.bodyStrong, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (p.address.isNotBlank()) Text(p.address, style = t.caption, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (i == all.lastIndex) VSpace(8.dp)
        }
    }
}
