package com.ohav.noted.ui.share

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.ohav.noted.data.Member
import com.ohav.noted.design.Noted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Profile photos of people on shared notes: memory → disk → network, one fetch per photo. */
object Faces {
    private val mem = LruCache<String, ImageBitmap>(48)
    private val locks = HashMap<String, Mutex>()

    fun cached(url: String): ImageBitmap? = mem.get(url)

    suspend fun load(ctx: Context, url: String): ImageBitmap? {
        mem.get(url)?.let { return it }
        val lock = synchronized(locks) { locks.getOrPut(url) { Mutex() } }
        return lock.withLock {
            mem.get(url) ?: withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(ctx.cacheDir, "faces").apply { mkdirs() }
                    val f = File(dir, Integer.toHexString(url.hashCode()) + ".img")
                    if (!f.exists() || System.currentTimeMillis() - f.lastModified() > 7 * 86_400_000L) {
                        // Google serves avatars at any size: one that stays sharp at the biggest place we show it
                        val sized = url.replace(Regex("=s\\d+(-c)?$"), "=s128-c")
                        val c = URL(sized).openConnection() as HttpURLConnection
                        c.connectTimeout = 8000; c.readTimeout = 8000
                        val tmp = File(dir, f.name + ".part")
                        c.inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
                        tmp.renameTo(f)
                    }
                    BitmapFactory.decodeFile(f.path)?.asImageBitmap()
                }.getOrNull()
            }?.also { mem.put(url, it) }
        }
    }

    private val tones = listOf(0xFFE0542C, 0xFF1E7552, 0xFF3442CF, 0xFF8E3AB3, 0xFFB7791F, 0xFF0F7C8C).map { Color(it) }
    fun tone(uid: String): Color = tones[(uid.hashCode() and 0x7fffffff) % tones.size]
}

/** One person: their photo in a circle, or their initial on a tone of their own. [ring] = a gap colour around it. */
@Composable
fun Face(m: Member, size: Dp, modifier: Modifier = Modifier, ring: Color? = null) {
    val ctx = LocalContext.current
    val url = m.photo
    val img by produceState(url?.let { Faces.cached(it) }, url) { if (url != null && value == null) value = Faces.load(ctx, url) }
    val ringW = if (ring != null) (size.value / 12f).coerceAtLeast(1.5f).dp else 0.dp
    Box(
        modifier
            .size(size)
            .then(if (ring != null) Modifier.border(ringW, ring, CircleShape) else Modifier)
            .clip(CircleShape)
            .background(Faces.tone(m.uid)),
        contentAlignment = Alignment.Center,
    ) {
        val pic = img
        if (pic != null) Image(pic, m.name, Modifier.fillMaxSize().clip(CircleShape).border(ringW, ring ?: Color.Transparent, CircleShape), contentScale = ContentScale.Crop)
        else Text(m.letter.ifEmpty { "?" }, style = Noted.type.button.copy(fontFamily = com.ohav.noted.design.NotedFonts.display, fontSize = (size.value / 2.4f).sp), color = Color.White)
    }
}

/** Overlapping faces, the first on top; past [max] a "+N" chip. */
@Composable
fun FacePile(members: List<Member>, size: Dp, ring: Color, modifier: Modifier = Modifier, max: Int = 3) {
    val c = Noted.colors
    val shown = members.take(max)
    val extra = members.size - shown.size
    val step = size * .68f
    val count = shown.size + if (extra > 0) 1 else 0
    Box(modifier.size(width = size + step * (count - 1).coerceAtLeast(0), height = size)) {
        shown.forEachIndexed { i, m ->
            // offset() follows layout direction: the first face sits at the start
            Face(m, size, Modifier.offset(x = step * i).zIndex((max - i).toFloat()), ring = ring)
        }
        if (extra > 0) {
            Box(
                Modifier.offset(x = step * shown.size).size(size).border((size.value / 12f).coerceAtLeast(1.5f).dp, ring, CircleShape)
                    .clip(CircleShape).background(c.inverse),
                contentAlignment = Alignment.Center,
            ) { Text("+$extra", style = Noted.type.tiny.copy(fontSize = (size.value / 2.8f).sp), color = c.inverseInk) }
        }
    }
}
