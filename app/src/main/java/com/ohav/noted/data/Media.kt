package com.ohav.noted.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/** One picture of an Images block: file name in the media folder, width / height, scanned page? */
data class Pic(val f: String, val r: Float, val doc: Boolean = false)

/** Recording: file, length, waveform (0..100 per bar) and the transcript made while recording. */
data class Voice(val f: String, val ms: Long, val wave: List<Int>, val text: String = "")

/** Link card: the address and whatever its page told us about itself. */
data class LinkInfo(val url: String, val title: String = "", val desc: String = "", val site: String = "", val img: String = "", val loaded: Boolean = false)

/**
 * Drawing: a strokes file (vector, drawn live so it follows the theme), the shown crop's
 * width / height and its top edge (in pad widths).
 */
data class Drawing(val strokes: String, val r: Float, val y0: Float)

/** Reads and writes the JSON payload ([Block.extra]) of media blocks. */
object MediaExtra {
    private fun obj(s: String) = runCatching { JSONObject(s) }.getOrDefault(JSONObject())

    fun pics(s: String): List<Pic> {
        val a = obj(s).optJSONArray("pics") ?: return emptyList()
        return List(a.length()) { i -> a.getJSONObject(i).let { Pic(it.optString("f"), it.optDouble("r", 1.0).toFloat(), it.optBoolean("doc")) } }
    }

    fun ofPics(p: List<Pic>): String = JSONObject().put(
        "pics",
        JSONArray().apply { p.forEach { put(JSONObject().put("f", it.f).put("r", it.r.toDouble()).put("doc", it.doc)) } },
    ).toString()

    fun voice(s: String): Voice? {
        val o = obj(s)
        val f = o.optString("f").ifEmpty { return null }
        val w = o.optJSONArray("wave")
        return Voice(f, o.optLong("ms"), if (w == null) emptyList() else List(w.length()) { w.optInt(it) }, o.optString("text"))
    }

    fun ofVoice(v: Voice): String = JSONObject()
        .put("f", v.f).put("ms", v.ms).put("wave", JSONArray(v.wave)).put("text", v.text).toString()

    fun link(s: String): LinkInfo? {
        val o = obj(s)
        val u = o.optString("url").ifEmpty { return null }
        return LinkInfo(u, o.optString("title"), o.optString("desc"), o.optString("site"), o.optString("img"), o.optBoolean("loaded"))
    }

    fun ofLink(l: LinkInfo): String = JSONObject()
        .put("url", l.url).put("title", l.title).put("desc", l.desc).put("site", l.site).put("img", l.img).put("loaded", l.loaded).toString()

    fun drawing(s: String): Drawing? {
        val o = obj(s)
        val f = o.optString("strokes").ifEmpty { return null }
        return Drawing(f, o.optDouble("r", 1.0).toFloat(), o.optDouble("y0", 0.0).toFloat())
    }

    fun ofDrawing(d: Drawing): String = JSONObject().put("strokes", d.strokes).put("r", d.r.toDouble()).put("y0", d.y0.toDouble()).toString()

    /** Every media file a block points at (for cleanup / sync later). */
    fun files(b: Block): List<String> = when (b.type) {
        BlockType.Images -> pics(b.extra).map { it.f }
        BlockType.Voice -> listOfNotNull(voice(b.extra)?.f)
        BlockType.Link -> listOfNotNull(link(b.extra)?.img?.ifEmpty { null })
        BlockType.Draw -> listOfNotNull(drawing(b.extra)?.strokes)
        else -> emptyList()
    }
}

/** Files of media blocks live in `filesDir/media`, named by a fresh id. */
object Media {
    /** Names of files that just landed from the cloud, so views waiting on them can show them. */
    val arrived = MutableSharedFlow<String>(extraBufferCapacity = 64)

    /** Returns once [name] is in the media folder (now, or when sync brings it). */
    suspend fun awaitFile(ctx: Context, name: String) {
        if (file(ctx, name).exists()) return
        arrived.onSubscription { if (file(ctx, name).exists()) emit(name) }.first { it == name }
    }

    fun dir(ctx: Context): File = File(ctx.filesDir, "media").apply { mkdirs() }
    fun file(ctx: Context, name: String) = File(dir(ctx), name)
    fun newName(ext: String) = UUID.randomUUID().toString().replace("-", "").take(20) + "." + ext

    /** Copies an image into the app, upright and at most [maxPx] on its long side. */
    suspend fun importImage(ctx: Context, uri: Uri, doc: Boolean = false, maxPx: Int = 2048): Pic? = withContext(Dispatchers.IO) {
        runCatching {
            val cr = ctx.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
            val src = cr.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return@runCatching null
            val rot = runCatching {
                cr.openInputStream(uri)?.use {
                    when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                        else -> 0f
                    }
                }
            }.getOrNull() ?: 0f
            val k = (maxPx.toFloat() / max(src.width, src.height)).coerceAtMost(1f)
            val m = Matrix().apply { if (k < 1f) postScale(k, k); if (rot != 0f) postRotate(rot) }
            val out = if (m.isIdentity) src else Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
            val name = newName("jpg")
            FileOutputStream(file(ctx, name)).use { out.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            Pic(name, out.width.toFloat() / out.height, doc)
        }.getOrNull()
    }

    /** Saves a rendered bitmap (drawings) as PNG. */
    suspend fun savePng(ctx: Context, bmp: Bitmap, name: String = newName("png")): String = withContext(Dispatchers.IO) {
        FileOutputStream(file(ctx, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        name
    }

    fun fmtMs(ms: Long): String {
        val s = (ms / 1000.0).roundToInt()
        return "%d:%02d".format(s / 60, s % 60)
    }
}
