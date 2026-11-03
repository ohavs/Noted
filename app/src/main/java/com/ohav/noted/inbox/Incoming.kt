package com.ohav.noted.inbox

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.ohav.noted.NotedApp
import com.ohav.noted.data.Block
import com.ohav.noted.data.BlockType
import com.ohav.noted.data.Blocks
import com.ohav.noted.data.LinkInfo
import com.ohav.noted.data.LinkPreview
import com.ohav.noted.data.Media
import com.ohav.noted.data.MediaExtra
import com.ohav.noted.data.Pic
import kotlinx.coroutines.launch
import java.util.UUID

/** What another app handed us through its share sheet. */
class Incoming(val subject: String, val lines: List<String>, val urls: List<String>, val images: List<Uri>) {
    val empty: Boolean get() = lines.isEmpty() && urls.isEmpty() && images.isEmpty()

    /** A title for a new note: the subject, unless it only repeats the text or is a link. */
    val title: String get() = subject.takeIf { s -> s.isNotBlank() && LinkPreview.normalize(s) == null && lines.none { it == s } }.orEmpty()

    /** Short label of what came in, for the sheet. */
    val kind: String
        get() = when {
            images.size > 1 -> "${images.size} תמונות"
            images.isNotEmpty() -> "תמונה"
            urls.isNotEmpty() && lines.isEmpty() -> "קישור"
            else -> "טקסט"
        }

    companion object {
        private val URL = Regex("""(?i)\bhttps?://[^\s<>"]+""")
        private const val MAX_IMAGES = 12
        private const val MAX_LINKS = 3

        fun of(ctx: Context, intent: Intent?): Incoming {
            if (intent == null) return Incoming("", emptyList(), emptyList(), emptyList())
            val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
            val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty().trim()
            val images = when (intent.action) {
                Intent.ACTION_SEND_MULTIPLE -> streams(intent)
                else -> listOfNotNull(stream(intent))
            }.filter { uri ->
                val t = runCatching { ctx.contentResolver.getType(uri) }.getOrNull() ?: intent.type
                t?.startsWith("image/") == true
            }.take(MAX_IMAGES)
            // links become link cards; whatever text is left stays as lines
            val urls = URL.findAll(text).map { it.value.trimEnd('.', ',', ')', ']', '!', '?', ';', ':', '״', '"', '\'') }.distinct().take(MAX_LINKS).toList()
            val rest = urls.fold(text) { acc, u -> acc.replace(u, "") }
            val lines = rest.lines().map { it.trim() }.filter { it.isNotEmpty() && it.any(Char::isLetterOrDigit) }
            return Incoming(subject, lines, urls, images)
        }

        @Suppress("DEPRECATION")
        private fun stream(i: Intent): Uri? =
            if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableExtra(Intent.EXTRA_STREAM)

        @Suppress("DEPRECATION")
        private fun streams(i: Intent): List<Uri> =
            (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()
    }
}

/** Turning [Incoming] into blocks and putting them in a note. */
object Inbox {
    /** Text lines, then link cards (filled in afterwards), then one Images block. */
    fun blocks(inc: Incoming, pics: List<Pic>): List<Block> = buildList {
        fun id() = UUID.randomUUID().toString()
        inc.lines.forEach { add(Block(id(), BlockType.P, it)) }
        inc.urls.forEach { add(Block(id(), BlockType.Link, extra = MediaExtra.ofLink(LinkInfo(it)))) }
        if (pics.isNotEmpty()) add(Block(id(), BlockType.Images, extra = MediaExtra.ofPics(pics)))
    }

    suspend fun importImages(ctx: Context, uris: List<Uri>): List<Pic> = uris.mapNotNull { Media.importImage(ctx, it) }

    /** Drops pictures that were imported but never used (the share was cancelled). */
    fun forget(ctx: Context, pics: List<Pic>) = pics.forEach { runCatching { Media.file(ctx, it.f).delete() } }

    /** A new note in my first category; returns its id. */
    suspend fun intoNew(app: NotedApp, inc: Incoming, pics: List<Pic>): String? {
        val cat = app.notes.categoriesFirst() ?: return null
        val color = com.ohav.noted.design.NoteColor.entries[((System.currentTimeMillis() / 1000) % 8).toInt()]
        val n = app.notes.create(cat, color, title = inc.title, blocks = blocks(inc, pics))
        fillLinks(app, n.id)
        return n.id
    }

    /** At the end of an existing note (an empty last line is reused). */
    suspend fun intoExisting(app: NotedApp, id: String, inc: Incoming, pics: List<Pic>): Boolean {
        val n = app.notes.get(id)?.takeIf { it.trashedAt == null } ?: return false
        val stored = Blocks.parse(n.blocks).dropLastWhile { it.type == BlockType.P && it.text.replace("\u200B", "").isBlank() }
        app.notes.save(id, n.title, stored + blocks(inc, pics))
        fillLinks(app, id)
        return true
    }

    /**
     * Link cards are saved bare and filled in the background (the share sheet doesn't wait for
     * the network). A note with no title takes the page's.
     */
    private fun fillLinks(app: NotedApp, id: String) {
        app.scope.launch {
            runCatching { fill(app, id) }
        }
    }

    private suspend fun fill(app: NotedApp, id: String) {
        val n = app.notes.get(id) ?: return
        val bare = Blocks.parse(n.blocks).filter { it.type == BlockType.Link && MediaExtra.link(it.extra)?.loaded == false }
        val filled = bare.associate { b -> b.id to LinkPreview.fetch(app, MediaExtra.link(b.extra)!!.url) }
        if (filled.isEmpty()) return
        val now = app.notes.get(id) ?: return
        val blocks = Blocks.parse(now.blocks).map { b -> filled[b.id]?.let { b.copy(extra = MediaExtra.ofLink(it)) } ?: b }
        val title = now.title.ifBlank { filled.values.firstOrNull { it.title.isNotBlank() }?.title.orEmpty().take(80) }
        app.notes.save(id, title, blocks)
    }
}
