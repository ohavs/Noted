package com.ohav.noted.ui.places

import android.location.Location
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMapOptions
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.MarkerComposable
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState
import com.ohav.noted.data.PlaceEntity
import com.ohav.noted.data.PlaceKind
import com.ohav.noted.data.PlaceReminderEntity
import com.ohav.noted.design.NotedColors
import com.ohav.noted.design.Noted
import com.ohav.noted.design.Shapes
import kotlin.math.cos
import kotlin.math.ln

/** The map drawn in the app's own colours: land = background, roads = surface, quiet labels, no shop clutter. */
internal object MapLook {
    private fun hex(c: Color) = String.format("#%06X", 0xFFFFFF and c.toArgb())

    fun style(c: NotedColors): MapStyleOptions {
        val land = hex(c.bg)
        val road = hex(c.surface)
        val big = hex(lerp(c.surface, c.ink, .08f))
        val water = hex(lerp(c.bg, c.accent, if (c.isDark) .22f else .16f))
        val park = hex(lerp(c.bg, Color(0xFF5E9B62), if (c.isDark) .16f else .14f))
        val label = hex(c.ink2)
        val minor = hex(c.ink3)
        return MapStyleOptions(
            """
            [
              {"elementType":"geometry","stylers":[{"color":"$land"}]},
              {"elementType":"labels.text.fill","stylers":[{"color":"$minor"}]},
              {"elementType":"labels.text.stroke","stylers":[{"color":"$land"},{"weight":3}]},
              {"elementType":"labels.icon","stylers":[{"saturation":-100},{"lightness":${if (c.isDark) -30 else 20}}]},
              {"featureType":"administrative.locality","elementType":"labels.text.fill","stylers":[{"color":"$label"}]},
              {"featureType":"road","elementType":"geometry","stylers":[{"color":"$road"}]},
              {"featureType":"road.highway","elementType":"geometry","stylers":[{"color":"$big"}]},
              {"featureType":"road","elementType":"labels.icon","stylers":[{"visibility":"off"}]},
              {"featureType":"water","elementType":"geometry","stylers":[{"color":"$water"}]},
              {"featureType":"water","elementType":"labels.text.fill","stylers":[{"color":"$minor"}]},
              {"featureType":"poi","elementType":"geometry","stylers":[{"color":"$land"}]},
              {"featureType":"poi.park","elementType":"geometry","stylers":[{"color":"$park"}]},
              {"featureType":"poi.business","stylers":[{"visibility":"off"}]},
              {"featureType":"transit","stylers":[{"visibility":"off"}]}
            ]
            """.trimIndent(),
        )
    }

    /** Zoom at which a circle of [radius] metres spans about [dp] on screen (256-dp tiles). */
    fun zoomFor(lat: Double, radius: Int, dp: Float): Float =
        (ln(156_543.03 * cos(Math.toRadians(lat)) * dp / (2.0 * radius)) / ln(2.0)).toFloat().coerceIn(3f, 18f)

    val ISRAEL = LatLng(31.6, 34.95)
}

/**
 * A still picture of the spot with its radius — lite mode (a bitmap, no gestures), for the
 * place form and card. Tapping it does nothing (lite mode would otherwise open Google Maps).
 */
@Composable
fun MiniMap(lat: Double, lng: Double, radius: Int, kind: PlaceKind, modifier: Modifier = Modifier) {
    val c = Noted.colors
    val at = LatLng(lat, lng)
    val cam = rememberCameraPositionState(key = "$lat,$lng") {
        position = CameraPosition.fromLatLngZoom(at, MapLook.zoomFor(lat, radius, 96f))
    }
    // lite mode can't animate: the frame jumps to fit a new radius
    LaunchedEffect(radius) { cam.position = CameraPosition.fromLatLngZoom(at, MapLook.zoomFor(lat, radius, 96f)) }
    val style = remember(c) { MapLook.style(c) }
    Box(modifier.clip(Shapes.md).background(c.bg)) {
        GoogleMap(
            modifier = Modifier.matchParentSize(),
            cameraPositionState = cam,
            googleMapOptionsFactory = { GoogleMapOptions().liteMode(true) },
            properties = MapProperties(mapStyleOptions = style),
            uiSettings = MapUiSettings(mapToolbarEnabled = false, zoomControlsEnabled = false, myLocationButtonEnabled = false),
            onMapClick = {},
        ) {
            Circle(center = at, radius = radius.toDouble(), fillColor = c.accent.copy(alpha = .16f), strokeColor = c.accent, strokeWidth = 4f)
        }
        Box(Modifier.align(Alignment.Center)) { PlaceBadge(kind, 34.dp, on = true) }
    }
}

/**
 * The places tab's map: every saved place as its badge with its radius, the ones with notes
 * waiting lit in the accent, and you as a dot. Frames everything once when it first loads;
 * after that the camera is yours.
 */
@Composable
fun PlacesMap(
    places: List<PlaceEntity>,
    open: List<PlaceReminderEntity>,
    me: Location?,
    cam: CameraPositionState,
    padding: PaddingValues,
    modifier: Modifier = Modifier,
    onPlace: (PlaceEntity) -> Unit,
) {
    val c = Noted.colors
    val density = LocalDensity.current
    val style = remember(c) { MapLook.style(c) }
    val withNotes = remember(open) { open.map { it.placeId }.toSet() }
    var loaded by remember { mutableStateOf(false) }
    var framed by remember { mutableStateOf(false) }

    LaunchedEffect(loaded, places.size, me != null) {
        if (!loaded || framed) return@LaunchedEffect
        if (places.isEmpty() && me == null) return@LaunchedEffect
        frameAll(cam, places, me, with(density) { 56.dp.roundToPx() }, animate = false)
        framed = true
    }

    GoogleMap(
        modifier = modifier,
        cameraPositionState = cam,
        properties = MapProperties(mapStyleOptions = style, isBuildingEnabled = false, isIndoorEnabled = false),
        uiSettings = MapUiSettings(
            zoomControlsEnabled = false, myLocationButtonEnabled = false, mapToolbarEnabled = false,
            compassEnabled = false, tiltGesturesEnabled = false, indoorLevelPickerEnabled = false,
        ),
        contentPadding = padding,
        onMapLoaded = { loaded = true },
    ) {
        places.forEach { p ->
            val lit = p.id in withNotes
            Circle(
                center = LatLng(p.lat, p.lng), radius = p.radius.toDouble(),
                fillColor = if (lit) c.accent.copy(alpha = .14f) else c.ink.copy(alpha = .05f),
                strokeColor = if (lit) c.accent.copy(alpha = .7f) else c.ink.copy(alpha = .18f),
                strokeWidth = with(density) { 1.5.dp.toPx() },
            )
        }
        places.forEach { p ->
            key(p.id) {
                val lit = p.id in withNotes
                // the bitmap is redrawn only when one of these keys changes
                MarkerComposable(
                    p.id, p.name, p.kind, lit, c,
                    state = rememberMarkerState(key = p.id, position = LatLng(p.lat, p.lng)),
                    // the badge's centre sits on the point; the name hangs below it
                    anchor = Offset(.5f, PIN_ANCHOR_Y),
                    zIndex = if (lit) 2f else 1f,
                    onClick = { onPlace(p); true },
                ) { MapPin(p, lit) }
            }
        }
        if (me != null) {
            val dot = rememberMarkerState(key = "me", position = LatLng(me.latitude, me.longitude))
            LaunchedEffect(me) { dot.position = LatLng(me.latitude, me.longitude) }
            if (me.hasAccuracy() && me.accuracy > 30f) {
                Circle(
                    center = LatLng(me.latitude, me.longitude), radius = me.accuracy.toDouble(),
                    fillColor = c.accent.copy(alpha = .08f), strokeColor = Color.Transparent, strokeWidth = 0f,
                )
            }
            MarkerComposable("me", c, state = dot, anchor = Offset(.5f, .5f), zIndex = 3f, onClick = { true }) {
                Box(Modifier.size(22.dp).clip(CircleShape).background(c.surface), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(14.dp).clip(CircleShape).background(c.accent))
                }
            }
        }
    }
}

/** Fits every place (and you) on screen; a single point gets a street-level zoom. */
suspend fun frameAll(cam: CameraPositionState, places: List<PlaceEntity>, me: Location?, pad: Int, animate: Boolean) {
    val pts = places.map { LatLng(it.lat, it.lng) } + listOfNotNull(me?.let { LatLng(it.latitude, it.longitude) })
    val update = when {
        pts.isEmpty() -> CameraUpdateFactory.newLatLngZoom(MapLook.ISRAEL, 7f)
        pts.size == 1 -> CameraUpdateFactory.newLatLngZoom(pts[0], 15f)
        else -> LatLngBounds.builder().apply { pts.forEach { include(it) } }.build().let { b ->
            // points on top of each other: bounds of zero size zoom in absurdly
            val ne = b.northeast
            val sw = b.southwest
            if (ne.latitude - sw.latitude < .002 && ne.longitude - sw.longitude < .002) CameraUpdateFactory.newLatLngZoom(b.center, 15f)
            else CameraUpdateFactory.newLatLngBounds(b, pad)
        }
    }
    runCatching { if (animate) cam.animate(update) else cam.move(update) }
}

private const val PIN_BADGE = 44f
private const val PIN_LABEL = 22f
private const val PIN_ANCHOR_Y = (PIN_BADGE / 2f) / (PIN_BADGE + 3f + PIN_LABEL)

/** A place on the map: its badge (ringed when notes wait there) over a small name tag. */
@Composable
private fun MapPin(p: PlaceEntity, lit: Boolean) {
    val c = Noted.colors
    Column(Modifier.width(96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(PIN_BADGE.dp), contentAlignment = Alignment.Center) {
            if (lit) Box(Modifier.size(PIN_BADGE.dp).clip(CircleShape).background(c.accent.copy(alpha = .28f)))
            PlaceBadge(PlaceKind.of(p.kind), 34.dp, on = lit)
        }
        Box(
            Modifier.padding(top = 3.dp).height(PIN_LABEL.dp).widthIn(max = 96.dp)
                .clip(RoundedCornerShape(11.dp)).background(c.surface).padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(p.name, style = Noted.type.tiny, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}
