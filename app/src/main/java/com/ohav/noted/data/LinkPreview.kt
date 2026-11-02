package com.ohav.noted.data

import android.content.Context
import android.net.Uri
import android.text.Html
import android.util.Patterns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

/** Reads a page's title / description / picture (Open Graph, then plain HTML) for link cards. */
object LinkPreview {
    /** "example.com/x" → "https://example.com/x"; null if it isn't a web address. */
    fun normalize(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty() || s.any { it.isWhitespace() }) return null
        val u = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(s)) s else "https://$s"
        return u.takeIf { Patterns.WEB_URL.matcher(it).matches() && (it.startsWith("http://") || it.startsWith("https://")) }
    }

    fun host(url: String): String = runCatching { Uri.parse(url).host.orEmpty().removePrefix("www.") }.getOrDefault(url)

    suspend fun fetch(ctx: Context, url: String): LinkInfo = withContext(Dispatchers.IO) {
        val base = LinkInfo(url, site = host(url), loaded = true)
        runCatching {
            val (html, finalUrl) = get(url) ?: return@runCatching base
            val metas = Regex("<meta\\s[^>]*>", RegexOption.IGNORE_CASE).findAll(html).map { it.value }.toList()
            fun attr(tag: String, name: String) =
                Regex("\\b$name\\s*=\\s*(\"([^\"]*)\"|'([^']*)')", RegexOption.IGNORE_CASE).find(tag)?.let { m -> m.groupValues[2].ifEmpty { m.groupValues[3] } }
            fun meta(vararg keys: String): String {
                for (k in keys) {
                    val tag = metas.firstOrNull { (attr(it, "property") ?: attr(it, "name")).equals(k, true) } ?: continue
                    val v = attr(tag, "content")?.let(::clean).orEmpty()
                    if (v.isNotBlank()) return v
                }
                return ""
            }
            val title = meta("og:title", "twitter:title").ifEmpty {
                Regex("<title[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.let(::clean).orEmpty()
            }
            val desc = meta("og:description", "twitter:description", "description")
            val site = meta("og:site_name").ifEmpty { host(finalUrl) }
            val imgUrl = meta("og:image", "og:image:url", "twitter:image").takeIf { it.isNotBlank() }?.let { runCatching { URL(URL(finalUrl), it).toString() }.getOrNull() }
            val img = imgUrl?.let { saveImage(ctx, it) }.orEmpty()
            LinkInfo(url, title.take(200), desc.take(300), site, img, loaded = true)
        }.getOrDefault(base)
    }

    private fun clean(s: String) = Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString().replace(Regex("\\s+"), " ").trim()

    private fun open(url: String): HttpURLConnection {
        var u = URL(url)
        repeat(5) {
            val c = (u.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36")
                setRequestProperty("Accept-Language", "he,en;q=0.8")
            }
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: return c
                c.disconnect()
                u = URL(u, loc)
            } else return c
        }
        return u.openConnection() as HttpURLConnection
    }

    /** Page HTML (first 600 KB) and the address after redirects. */
    private fun get(url: String): Pair<String, String>? {
        val c = open(url)
        try {
            if (c.responseCode !in 200..299) return null
            val type = c.contentType.orEmpty()
            if (!type.contains("html", true) && type.isNotEmpty()) return null
            val bytes = c.inputStream.use { ins ->
                val buf = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(16 * 1024)
                while (buf.size() < 600 * 1024) {
                    val n = ins.read(chunk)
                    if (n < 0) break
                    buf.write(chunk, 0, n)
                }
                buf.toByteArray()
            }
            val headerCs = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(type)?.groupValues?.get(1)
            val sniff = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
            val metaCs = Regex("charset=[\"']?([\\w-]+)", RegexOption.IGNORE_CASE).find(sniff)?.groupValues?.get(1)
            val cs = runCatching { Charset.forName(headerCs ?: metaCs ?: "UTF-8") }.getOrDefault(Charsets.UTF_8)
            return String(bytes, cs) to c.url.toString()
        } finally {
            c.disconnect()
        }
    }

    private suspend fun saveImage(ctx: Context, url: String): String? {
        val tmp = File(ctx.cacheDir, "link-${System.nanoTime()}")
        return try {
            val c = open(url)
            try {
                if (c.responseCode !in 200..299) return null
                c.inputStream.use { ins -> tmp.outputStream().use { ins.copyTo(it) } }
            } finally { c.disconnect() }
            Media.importImage(ctx, Uri.fromFile(tmp), maxPx = 900)?.f
        } catch (_: Exception) {
            null
        } finally {
            tmp.delete()
        }
    }
}
