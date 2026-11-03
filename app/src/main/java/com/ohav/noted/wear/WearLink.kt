package com.ohav.noted.wear

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.nio.ByteBuffer
import com.ohav.noted.data.PlaceReminderEntity
import com.ohav.noted.data.PlaceEntity
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.ohav.noted.NotedApp
import com.ohav.noted.data.Block
import com.ohav.noted.data.BlockType
import com.ohav.noted.data.Blocks
import com.ohav.noted.data.CategoryEntity
import com.ohav.noted.data.Media
import com.ohav.noted.data.MediaExtra
import com.ohav.noted.ui.media.FileTranscriber
import com.ohav.noted.data.NoteEntity
import com.ohav.noted.data.Privacy
import com.ohav.noted.data.ReminderEntity
import com.ohav.noted.data.Voice
import com.ohav.noted.data.shareInfo
import com.ohav.noted.design.NoteColor
import com.ohav.noted.settings.AppSettings
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.util.UUID
import java.util.zip.GZIPOutputStream

/**
 * The watch's view of the phone (Wearable Data Layer — works without an account, the watch
 * needs the phone nearby). The phone publishes one data item, [STATE]: every live note
 * (locked ones as a bare title), categories and the coming reminders, gzipped JSON in an asset.
 * The watch sends back small messages (check a line, a dictated note, done / snooze) and
 * recordings over a channel; [WearService] applies them here.
 */
class WearLink(private val app: NotedApp) {
    private var lastHash = 0

    @OptIn(FlowPreview::class)
    fun start() {
        app.scope.launch {
            val base = combine(app.notes.notes, app.notes.categories) { n, c -> n to c }
            val where = combine(app.places.places, app.places.reminders) { p, r -> p to r }
            combine(base, app.reminders.all, app.privacy.flags, app.settings.settings, where) { (n, c), r, p, s, w ->
                build(n, c, r, p, s, w)
            }.debounce(800).collect { publish(it, force = false) }
        }
    }

    /** The watch asked (it just opened): the current state again, even if nothing changed. */
    suspend fun publishNow() {
        val s = build(
            app.notes.notes.first(), app.notes.categories.first(), app.reminders.all.first(), app.privacy.flags.value, app.settings.settings.first(),
            app.places.places.first() to app.places.reminders.first(),
        )
        publish(s, force = true)
    }

    private suspend fun publish(json: String, force: Boolean) {
        val h = json.hashCode()
        if (!force && h == lastHash) return
        runCatching {
            val bytes = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(json.toByteArray()) } }.toByteArray()
            val req = PutDataMapRequest.create(STATE).apply {
                dataMap.putAsset("state", Asset.createFromBytes(bytes))
                dataMap.putLong("at", System.currentTimeMillis())
            }.asPutDataRequest().setUrgent()
            Wearable.getDataClient(app).putDataItem(req).await()
            lastHash = h
        }
        // no Wear OS on this phone (the API is missing): nothing to do, nothing to report
    }

    private fun build(
        notes: List<NoteEntity>, cats: List<CategoryEntity>, rems: List<ReminderEntity>, flags: Map<String, Int>, s: AppSettings,
        where: Pair<List<PlaceEntity>, List<PlaceReminderEntity>>,
    ): String {
        val byId = notes.associateBy { it.id }
        val o = JSONObject()
            .put("v", 1)
            .put("accent", s.accent.name)
            .put("cats", JSONArray().apply { cats.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("icon", it.icon).put("parent", it.parent)) } })
        val arr = JSONArray()
        notes.take(MAX_NOTES).forEach { n ->
            val f = flags[n.id] ?: 0
            val locked = f and Privacy.LOCK != 0
            val j = JSONObject()
                .put("id", n.id).put("t", n.title).put("c", n.color).put("cat", n.categoryId)
                .put("pin", n.pinned).put("fav", n.favorite).put("at", n.updatedAt)
                .put("ro", n.shareInfo.readOnly)
            if (locked) j.put("lock", true)
            else {
                if (f and Privacy.BLUR_TITLE != 0) j.put("bt", true)
                if (f and Privacy.BLUR_BODY != 0) j.put("bb", true)
                val pics = f and Privacy.BLUR_BODY == 0
                j.put("b", JSONArray().apply { Blocks.parse(n.blocks).take(MAX_BLOCKS).mapNotNull { line(it, pics) }.forEach { put(it) } })
            }
            arr.put(j)
        }
        o.put("notes", arr)
        val now = System.currentTimeMillis()
        o.put("rem", JSONArray().apply {
            rems.mapNotNull { r -> r.dueAt?.let { r to it } }.filter { (r, _) -> byId[r.noteId] != null }
                .sortedBy { it.second }.take(MAX_REMINDERS).forEach { (r, at) ->
                    val n = byId[r.noteId]!!
                    val locked = (flags[n.id] ?: 0) and Privacy.LOCK != 0
                    val line = if (r.blockId.isEmpty() || locked) null else Blocks.parse(n.blocks).firstOrNull { it.id == r.blockId }
                    put(
                        JSONObject().put("id", r.id).put("n", r.noteId).put("b", r.blockId).put("at", at)
                            .put("rep", r.repeat.repeats).put("due", at <= now)
                            .put("t", if (locked) n.title else line?.text?.trim().orEmpty())
                            .put("nt", n.title),
                    )
                }
        })
        // place reminders still waiting (a one-shot that rang is done with)
        val placeById = where.first.associateBy { it.id }
        o.put("prem", JSONArray().apply {
            where.second.filter { !it.fired && byId[it.noteId]?.trashedAt == null && byId[it.noteId] != null }.take(MAX_REMINDERS).forEach { r ->
                val n = byId[r.noteId]!!
                val p = placeById[r.placeId] ?: return@forEach
                val m = Privacy.mask(flags[n.id] ?: 0, n.title, if (r.blockId.isEmpty()) null else Blocks.parse(n.blocks).firstOrNull { it.id == r.blockId }?.text)
                put(
                    JSONObject().put("id", r.id).put("n", r.noteId).put("b", r.blockId)
                        .put("p", p.name).put("k", p.kind).put("leave", r.leave).put("always", r.always)
                        .put("t", m.head).put("nt", m.note),
                )
            }
        })
        return o.toString()
    }

    /** One block as the watch draws it; media becomes a short line. Pictures name up to 3 files the watch can ask for. */
    private fun line(b: Block, pics: Boolean): JSONObject? {
        val j = JSONObject().put("id", b.id).put("k", b.type.name)
        when (b.type) {
            BlockType.Images -> MediaExtra.pics(b.extra).let { ps ->
                j.put("x", ps.size.toString())
                if (pics) j.put("f", JSONArray().apply { ps.take(3).forEach { put(it.f) } })
            }
            BlockType.Voice -> MediaExtra.voice(b.extra)?.let { v -> j.put("x", v.text.take(MAX_TEXT)).put("ms", v.ms) } ?: return null
            BlockType.Link -> MediaExtra.link(b.extra)?.let { l -> j.put("x", l.title.ifBlank { l.site.ifBlank { l.url } }.take(120)) } ?: return null
            BlockType.Place -> MediaExtra.place(b.extra)?.let { p -> j.put("x", p.name.ifBlank { p.address }.take(120)) } ?: return null
            BlockType.Draw, BlockType.Divider -> {}
            else -> {
                val t = b.text.replace("​", "")
                j.put("x", t.take(MAX_TEXT))
                if (b.done) j.put("d", true)
                if (b.indent > 0) j.put("i", b.indent)
            }
        }
        return j
    }

    // ---- what the watch asked for ----

    suspend fun toggle(noteId: String, blockId: String, done: Boolean) {
        val n = app.notes.get(noteId) ?: return
        if (n.shareInfo.readOnly) return
        val blocks = Blocks.parse(n.blocks)
        if (blocks.none { it.id == blockId && it.done != done }) return
        app.notes.save(noteId, n.title, blocks.map { if (it.id == blockId) it.copy(done = done) else it })
    }

    /** A dictated note: a short phrase is the title, anything longer the first line. */
    suspend fun newNote(text: String): String? {
        val t = text.trim().ifEmpty { return null }
        val cat = app.notes.categoriesFirst() ?: return null
        val short = t.length <= 40 && t.split(' ').size <= 6 && '\n' !in t
        val n = app.notes.create(
            cat, randomColor(),
            title = if (short) t else "",
            blocks = if (short) listOf(Block(id(), BlockType.P, "")) else t.split('\n').filter { it.isNotBlank() }.map { Block(id(), BlockType.P, it.trim()) },
        )
        return n.id
    }

    /** A dictated line at the end of a note: a checklist gets a new row, anything else a paragraph. */
    suspend fun append(noteId: String, text: String) {
        val t = text.trim().ifEmpty { return }
        val n = app.notes.get(noteId)?.takeIf { it.trashedAt == null && !it.shareInfo.readOnly } ?: return
        if (app.privacy.locked(noteId)) return
        val stored = Blocks.parse(n.blocks).dropLastWhile { it.type == BlockType.P && it.text.replace("​", "").isBlank() }
        val type = if (stored.lastOrNull { it.type != BlockType.P || it.text.isNotBlank() }?.type == BlockType.Check) BlockType.Check else BlockType.P
        app.notes.save(noteId, n.title, stored + Block(id(), type, t))
    }

    /** A recording from the watch: a note of its own, or the end of [noteId]. */
    suspend fun voice(file: String, ms: Long, wave: List<Int>, noteId: String?) {
        val v = Block(id(), BlockType.Voice, extra = MediaExtra.ofVoice(Voice(file, ms, wave)))
        val n = noteId?.let { app.notes.get(it) }?.takeIf { it.trashedAt == null && !it.shareInfo.readOnly && !app.privacy.locked(it.id) }
        val target = if (n != null) {
            val stored = Blocks.parse(n.blocks).dropLastWhile { it.type == BlockType.P && it.text.replace("​", "").isBlank() }
            app.notes.save(n.id, n.title, stored + v)
            n.id
        } else {
            val cat = app.notes.categoriesFirst() ?: return
            app.notes.create(cat, randomColor(), title = "הקלטה מהשעון", blocks = listOf(v, Block(id(), BlockType.P, ""))).id
        }
        // nothing listened on the watch: the words are worked out here, then written under the recording
        app.scope.launch {
            val text = runCatching { FileTranscriber.transcribe(app, Media.file(app, file), ms) }.getOrNull()?.trim().orEmpty()
            if (text.isEmpty()) return@launch
            val cur = app.notes.get(target) ?: return@launch
            val blocks = Blocks.parse(cur.blocks)
            if (blocks.none { it.id == v.id }) return@launch
            app.notes.save(target, cur.title, blocks.map { b ->
                if (b.id != v.id) b else MediaExtra.voice(b.extra)?.let { b.copy(extra = MediaExtra.ofVoice(it.copy(text = text))) } ?: b
            })
        }
    }

    /**
     * A picture the watch asked for, as a small WebP sent back to it: only one that a visible note
     * here shows (never from a locked or hidden one), at most [PIC_PX] on its long side.
     */
    suspend fun sendPic(node: String, f: String) {
        if (!Regex("[A-Za-z0-9_-]{1,60}\\.[a-z0-9]{2,5}").matches(f)) return
        val flags = app.privacy.flags.value
        val shown = app.notes.notes.first().any { n ->
            n.trashedAt == null && ((flags[n.id] ?: 0) and (Privacy.LOCK or Privacy.BLUR_BODY)) == 0 &&
                Blocks.parse(n.blocks).any { it.type == BlockType.Images && MediaExtra.pics(it.extra).any { p -> p.f == f } }
        }
        if (!shown) return
        val src = Media.file(app, f)
        val bytes = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(src.path, bounds)
                val bmp = BitmapFactory.decodeFile(src.path, Media.scaledOptions(bounds.outWidth, bounds.outHeight, PIC_PX)) ?: return@runCatching null
                val k = (PIC_PX.toFloat() / maxOf(bmp.width, bmp.height)).coerceAtMost(1f)
                val out = if (k < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true) else bmp
                ByteArrayOutputStream().also { @Suppress("DEPRECATION") out.compress(Bitmap.CompressFormat.WEBP, 72, it) }.toByteArray()
            }.getOrNull()
        } ?: return
        val name = f.toByteArray()
        val msg = ByteBuffer.allocate(4 + name.size + bytes.size).putInt(name.size).put(name).put(bytes).array()
        runCatching { Wearable.getMessageClient(app).sendMessage(node, PIC, msg).await() }
    }

    private fun randomColor() = NoteColor.entries[((System.currentTimeMillis() / 1000) % NoteColor.entries.size).toInt()]
    private fun id() = UUID.randomUUID().toString()

    companion object {
        const val STATE = "/noted/state"
        const val SYNC = "/noted/sync"
        const val TOGGLE = "/noted/toggle"
        const val NEW = "/noted/new"
        const val APPEND = "/noted/append"
        const val DONE = "/noted/done"
        const val LATER = "/noted/later"
        const val VOICE = "/noted/voice"
        const val PIC = "/noted/pic"
        const val PLACE_DONE = "/noted/pdone"

        private const val MAX_NOTES = 120
        private const val MAX_BLOCKS = 80
        private const val MAX_TEXT = 400
        private const val MAX_REMINDERS = 12
        private const val PIC_PX = 320
    }
}

/** Messages and recordings from the watch. Runs even when the app isn't open. */
class WearService : WearableListenerService() {
    private val app get() = application as NotedApp

    override fun onMessageReceived(e: MessageEvent) {
        val j = runCatching { JSONObject(String(e.data)) }.getOrDefault(JSONObject())
        val link = app.wear
        // the listener runs on a binder thread; keep the work alive until it's written
        runBlocking {
            when (e.path) {
                WearLink.SYNC -> link.publishNow()
                WearLink.TOGGLE -> link.toggle(j.optString("n"), j.optString("b"), j.optBoolean("d"))
                WearLink.NEW -> link.newNote(j.optString("x"))
                WearLink.APPEND -> link.append(j.optString("n"), j.optString("x"))
                // ringing now = "done" like on the phone's notification; still ahead = ✓ like on the widget
                WearLink.DONE -> j.optString("r").let { r -> if (j.optBoolean("due")) app.reminders.done(r) else app.reminders.skip(r); app.reminders.schedule() }
                WearLink.LATER -> { app.reminders.later(j.optString("r"), j.optLong("ms", 10 * 60_000L)); app.reminders.schedule() }
                WearLink.PLACE_DONE -> app.places.done(j.optString("r"))
                WearLink.PIC -> link.sendPic(e.sourceNodeId, j.optString("f"))
            }
        }
    }

    /** A recording: a JSON header (length-prefixed) then the m4a bytes. */
    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (channel.path != WearLink.VOICE) return
        val client = Wearable.getChannelClient(this)
        runBlocking {
            runCatching {
                val input = DataInputStream(client.getInputStream(channel).await())
                input.use { s ->
                    val head = ByteArray(s.readInt().coerceIn(0, 64_000)).also { s.readFully(it) }
                    val meta = JSONObject(String(head))
                    val name = Media.newName("m4a")
                    val f = Media.file(this@WearService, name)
                    f.outputStream().use { s.copyTo(it) }
                    if (f.length() < 512) { f.delete(); return@runCatching }
                    val w = meta.optJSONArray("w") ?: JSONArray()
                    app.wear.voice(name, meta.optLong("ms"), List(w.length()) { w.optInt(it) }, meta.optString("n").ifEmpty { null })
                }
            }
            runCatching { client.close(channel).await() }
        }
    }
}
