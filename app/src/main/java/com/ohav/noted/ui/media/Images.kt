package com.ohav.noted.ui.media

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ohav.noted.data.Media
import com.ohav.noted.data.Pic
import com.ohav.noted.design.Motion
import com.ohav.noted.design.Noted
import com.ohav.noted.design.Ph
import com.ohav.noted.design.Squircle
import com.ohav.noted.design.press
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max

/** Decoded pictures, keyed by file + size bucket; ~1/8 of the heap. */
private object BitmapCache {
    val cache = object : LruCache<String, ImageBitmap>((Runtime.getRuntime().maxMemory() / 8 / 1024).toInt()) {
        override fun sizeOf(key: String, value: ImageBitmap) = max(1, value.width * value.height * 4 / 1024)
    }

    fun decode(ctx: Context, name: String, maxPx: Int): ImageBitmap? {
        val f = Media.file(ctx, name)
        if (!f.exists()) return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var s = 1
        while (max(o.outWidth, o.outHeight) / (s * 2) >= maxPx) s *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
    }
}

/** A picture from the media folder, decoded off the main thread; [maxPx] picks the size bucket. */
@Composable
fun rememberMediaBitmap(name: String, maxPx: Int = 1024): State<ImageBitmap?> {
    val ctx = LocalContext.current.applicationContext
    val key = "$name@$maxPx"
    return produceState(BitmapCache.cache.get(key), key) {
        if (value == null) {
            value = withContext(Dispatchers.IO) { runCatching { BitmapCache.decode(ctx, name, maxPx) }.getOrNull() }
                ?.also { BitmapCache.cache.put(key, it) }
        }
    }
}

/** Shows a media picture; a soft placeholder until it's decoded, then it fades in (on glide). */
@Composable
fun MediaImage(name: String, modifier: Modifier, maxPx: Int = 1024, fit: Boolean = false) {
    val c = Noted.colors
    val bmp by rememberMediaBitmap(name, maxPx)
    val fade = remember(name) { Animatable(if (bmp != null) 1f else 0f) }
    LaunchedEffect(bmp != null) { if (bmp != null) fade.animateTo(1f, Motion.glide()) }
    Box(modifier.background(c.noteInk.copy(alpha = .07f))) {
        bmp?.let {
            Image(
                it, null,
                Modifier.fillMaxSize().graphicsLayer { alpha = fade.value },
                contentScale = if (fit) ContentScale.Fit else ContentScale.Crop,
            )
        }
    }
}

/**
 * Pictures of an Images block: one picture gets the full width at (about) its own shape;
 * more go two to a row, the first one wide when the count is odd. Scanned pages are never cropped.
 */
@Composable
fun PicGrid(pics: List<Pic>, modifier: Modifier = Modifier, corner: Dp = 16.dp, gap: Dp = 6.dp, maxShown: Int = Int.MAX_VALUE, maxPx: Int = 1024, onClick: ((Int) -> Unit)? = null) {
    val shown = pics.take(maxShown)
    val more = pics.size - shown.size
    @Composable
    fun Cell(i: Int, m: Modifier) {
        val p = shown[i]
        Box(m.clip(Squircle(corner)).then(if (onClick != null) Modifier.press(scale = .97f) { onClick(i) } else Modifier)) {
            MediaImage(p.f, Modifier.fillMaxSize(), maxPx, fit = p.doc)
            if (more > 0 && i == shown.lastIndex) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .45f)), contentAlignment = Alignment.Center) {
                    Text("+$more", style = Noted.type.cardTitle, color = Color.White)
                }
            }
        }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
        if (shown.size == 1) {
            val p = shown[0]
            Cell(0, Modifier.fillMaxWidth().aspectRatio(p.r.coerceIn(if (p.doc) .7f else .75f, 2.2f)))
        } else {
            val odd = shown.size % 2 == 1
            var i = 0
            if (odd) { Cell(0, Modifier.fillMaxWidth().aspectRatio(shown[0].r.coerceIn(1.3f, 2.2f))); i = 1 }
            while (i < shown.size) {
                val a = i
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    Cell(a, Modifier.weight(1f).aspectRatio(1f))
                    Cell(a + 1, Modifier.weight(1f).aspectRatio(1f))
                }
                i += 2
            }
        }
    }
}

/**
 * Full-screen pictures: swipe between them, pinch or double-tap to zoom, drag down to close.
 * [onRemove] takes the current picture out of its block.
 */
@Composable
fun ImageViewer(pics: List<Pic>, start: Int, onClose: () -> Unit, onRemove: (Int) -> Unit) {
    val haptics = Noted.haptics
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val pager = rememberPagerState(start.coerceIn(0, max(0, pics.lastIndex))) { pics.size }
    val enter = remember { Animatable(0f) }
    val drag = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { enter.animateTo(1f, Motion.glide()) }
    fun close() {
        if (closing) return
        closing = true
        scope.launch { enter.animateTo(0f, Motion.glide()); onClose() }
    }
    BackHandler { close() }
    LaunchedEffect(pics.size) { if (pics.isEmpty()) onClose() }
    val far = with(density) { 260.dp.toPx() }
    val fade by remember { derivedStateOf { enter.value * (1f - (abs(drag.value) / far).coerceIn(0f, 1f) * .7f) } }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = fade.coerceIn(0f, 1f) }
            .background(Color.Black)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        if (abs(drag.value) > far * .4f) close()
                        else scope.launch { drag.animateTo(0f, Motion.glide()) }
                    },
                    onDragCancel = { scope.launch { drag.animateTo(0f, Motion.glide()) } },
                ) { ch, dy -> ch.consume(); scope.launch { drag.snapTo(drag.value + dy) } }
            },
    ) {
        HorizontalPager(
            pager,
            Modifier.fillMaxSize().graphicsLayer {
                translationY = drag.value
                val k = .92f + .08f * enter.value
                scaleX = k; scaleY = k
            },
            key = { pics.getOrNull(it)?.f ?: it },
        ) { page ->
            pics.getOrNull(page)?.let { ZoomableImage(it, active = pager.currentPage == page) }
        }
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)
                .graphicsLayer { alpha = enter.value },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ViewerBtn(Ph.X, "סגירה") { close() }
            Spacer(Modifier.weight(1f))
            if (pics.size > 1) Text("${pager.currentPage + 1} / ${pics.size}", style = Noted.type.meta, color = Color.White.copy(alpha = .8f))
            Spacer(Modifier.weight(1f))
            ViewerBtn(Ph.Trash, "הסרה") { haptics.medium(); onRemove(pager.currentPage) }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun ViewerBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = .14f)).press(scale = .85f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, Modifier.size(20.dp), tint = Color.White) }
}

/** Pinch / double-tap zoom; a single finger at 1× is left to the pager and the close drag. */
@Composable
private fun ZoomableImage(p: Pic, active: Boolean) {
    val scope = rememberCoroutineScope()
    val scale = remember { Animatable(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(active) { if (!active) { scale.snapTo(1f); pan = Offset.Zero } }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.count { it.pressed }
                        if (pressed > 1 || scale.value > 1.01f) {
                            val z = ev.calculateZoom()
                            val s = (scale.value * z).coerceIn(1f, 5f)
                            scope.launch { scale.snapTo(s) }
                            val maxX = size.width * (s - 1f) / 2f
                            val maxY = size.height * (s - 1f) / 2f
                            val n = pan + ev.calculatePan()
                            pan = Offset(n.x.coerceIn(-maxX, maxX), n.y.coerceIn(-maxY, maxY))
                            ev.changes.forEach { it.consume() }
                        }
                    } while (ev.changes.any { it.pressed })
                    if (scale.value < 1.05f) scope.launch { scale.animateTo(1f, Motion.glide()); pan = Offset.Zero }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { at ->
                    scope.launch {
                        if (scale.value > 1.01f) { pan = Offset.Zero; scale.animateTo(1f, Motion.glide()) }
                        else {
                            // zoom toward the tapped point
                            pan = Offset((size.width / 2f - at.x) * 1.5f, (size.height / 2f - at.y) * 1.5f)
                            scale.animateTo(2.5f, Motion.glide())
                        }
                    }
                })
            }
            .graphicsLayer {
                scaleX = scale.value; scaleY = scale.value
                translationX = pan.x; translationY = pan.y
            },
        contentAlignment = Alignment.Center,
    ) {
        val bmp by rememberMediaBitmap(p.f, 2048)
        bmp?.let { Image(it, null, Modifier.fillMaxWidth().aspectRatio(p.r), contentScale = ContentScale.Fit) }
    }
}

/** Width / height of a media picture without decoding it. */
fun picRatio(ctx: Context, name: String): Float {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(Media.file(ctx, name).path, o)
    return if (o.outWidth > 0 && o.outHeight > 0) o.outWidth.toFloat() / o.outHeight else 1f
}
