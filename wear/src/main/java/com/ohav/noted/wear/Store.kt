package com.ohav.noted.wear

import android.app.Application
import android.content.Context
import android.net.Uri
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.GZIPInputStream

class WatchApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var store: Store private set

    override fun onCreate() {
        super.onCreate()
        store = Store(this, scope)
    }
}

val Context.store: Store get() = (applicationContext as WatchApp).store

// ---- what the phone sends (see app/.../wear/WearLink.kt) ----

data class Line(
    val id: String, val kind: String, val text: String, val done: Boolean, val indent: Int, val ms: Long,
    /** Pictures: up to 3 file names the phone will send as thumbnails ([Store.pic]). */
    val files: List<String> = emptyList(),
) {
    val isCheck get() = kind == "Check"
}

data class Note(
    val id: String, val title: String, val color: String, val cat: String,
    val pinned: Boolean, val favorite: Boolean, val at: Long, val readOnly: Boolean,
    val locked: Boolean, val blurTitle: Boolean, val blurBody: Boolean, val lines: List<Line>,
) {
    val checks get() = lines.filter { it.isCheck }
    val progress: Pair<Int, Int> get() = checks.let { c -> c.count { it.done } to c.size }
}

data class Cat(val id: String, val name: String, val icon: String, val parent: String)

data class Rem(
    val id: String, val noteId: String, val blockId: String, val at: Long,
    val repeats: Boolean, val due: Boolean, val text: String, val noteTitle: String,
) {
    val title get() = text.ifBlank { noteTitle }.ifBlank { "פתק בלי כותרת" }
}

/** A note waiting for a place: arriving at / leaving [place]. */
data class PlaceRem(
    val id: String, val noteId: String, val blockId: String, val place: String, val kind: String,
    val leave: Boolean, val always: Boolean, val text: String, val noteTitle: String,
) {
    val title get() = text.ifBlank { noteTitle }.ifBlank { "פתק בלי כותרת" }
}

data class WState(
    val accent: String, val cats: List<Cat>, val notes: List<Note>, val rems: List<Rem>, val at: Long,
    val places: List<PlaceRem> = emptyList(),
) {
    fun note(id: String) = notes.firstOrNull { it.id == id }
    fun cat(id: String) = cats.firstOrNull { it.id == id }
    val tops get() = cats.filter { it.parent.isEmpty() || cat(it.parent) == null }

    /** The top-level category [id] sits under (itself for a top one). */
    fun top(id: String): String {
        var c = cat(id) ?: return id
        repeat(8) { c = cat(c.parent) ?: return c.id }
        return c.id
    }
}

/**
 * What the wearer chose to see. [cats]: top-level categories on the watch (empty = all).
 * [tile]: [TILE_PINNED] / [TILE_RECENT] / [TILE_REMS] / "cat:<id>" / "note:<id>".
 */
data class WView(val cats: Set<String> = emptySet(), val tile: String = TILE_PINNED) {
    /** Chosen categories that still exist; none left = everything. */
    fun live(s: WState): Set<String> = cats.filterTo(HashSet()) { s.cat(it) != null }
    fun shows(s: WState, n: Note): Boolean = live(s).let { it.isEmpty() || s.top(n.cat) in it }
    fun notes(s: WState): List<Note> = live(s).let { l -> if (l.isEmpty()) s.notes else s.notes.filter { s.top(it.cat) in l } }
    fun rems(s: WState): List<Rem> = s.rems.filter { r -> s.note(r.noteId)?.let { shows(s, it) } ?: true }
    fun places(s: WState): List<PlaceRem> = s.places.filter { r -> s.note(r.noteId)?.let { shows(s, it) } ?: true }

    companion object {
        const val TILE_PINNED = "pinned"
        const val TILE_RECENT = "recent"
        const val TILE_REMS = "rems"
    }
}

/** A ticked row sinks to the bottom of its checklist (same rule as the phone's `sinkChecked`). */
fun List<Line>.sink(id: String): List<Line> {
    val i = indexOfFirst { it.id == id }
    if (i < 0 || !this[i].isCheck) return this
    val lvl = this[i].indent
    fun inList(k: Int) = this[k].isCheck && this[k].indent >= lvl
    var s = i
    while (s > 0 && inList(s - 1)) s--
    var e = i
    while (e < lastIndex && inList(e + 1)) e++
    var k = s
    while (k <= e && this[k].indent > lvl) k++
    val head = s until k
    val units = ArrayList<IntRange>()
    while (k <= e) {
        val st = k++
        while (k <= e && this[k].indent > lvl) k++
        units += st until k
    }
    val mine = units.firstOrNull { i in it } ?: return this
    val others = units.filter { it != mine }
    val at = if (this[i].done) others.size else others.indexOfFirst { this[it.first].done }.let { if (it < 0) others.size else it }
    val ordered = others.toMutableList().apply { add(at, mine) }
    if (ordered == units) return this
    return subList(0, s) + head.map { this[it] } + ordered.flatMap { r -> r.map { this[it] } } + subList(e + 1, size)
}

/**
 * The watch's copy of the notes: the phone's last published state, kept in a file so the app
 * and the tile open instantly, plus what the watch did that the phone hasn't confirmed yet
 * (checked lines show checked meanwhile; messages wait in a queue while the phone is away).
 */
class Store(private val ctx: Context, private val scope: CoroutineScope) {
    private val file = File(ctx.filesDir, "state.json")
    private val outbox = File(ctx.filesDir, "outbox").apply { mkdirs() }
    private val prefs = ctx.getSharedPreferences("store", Context.MODE_PRIVATE)
    private val lock = Mutex()
    private var raw: String? = null

    private val _state = MutableStateFlow(runCatching { file.readText() }.getOrNull()?.let { raw = it; parse(it) })
    val state: StateFlow<WState?> = _state

    /** Whether the phone answered lately; null = still asking. */
    private val _phone = MutableStateFlow<Boolean?>(null)
    val phone: StateFlow<Boolean?> = _phone

    /** Lines checked here, waiting for the phone: "note|line" → done. */
    private val pending = MutableStateFlow(loadPending())

    // ---- what to show ----

    private val vp = ctx.getSharedPreferences("view", Context.MODE_PRIVATE)
    private val _view = MutableStateFlow(WView(vp.getStringSet("cats", null).orEmpty().toSet(), vp.getString("tile", null) ?: WView.TILE_PINNED))
    val view: StateFlow<WView> = _view

    fun setCats(cats: Set<String>) = setView(_view.value.copy(cats = cats))
    fun setTile(tile: String) = setView(_view.value.copy(tile = tile))
    private fun setView(v: WView) {
        _view.value = v
        vp.edit().putStringSet("cats", v.cats).putString("tile", v.tile).apply()
        NotedTile.refresh(ctx)
    }

    /** Rows ticked here a moment ago: they stay in sight (struck, at the bottom) for [SHOW_MS], then fold away. */
    private val _ticked = MutableStateFlow<Map<String, Long>>(emptyMap())
    val ticked: StateFlow<Map<String, Long>> = _ticked
    fun fresh(lineId: String, now: Long = System.currentTimeMillis()) = _ticked.value[lineId]?.let { now - it <= SHOW_MS } == true

    // ---- incoming ----

    /** A new state from the phone (the listener service or a refresh). */
    suspend fun accept(asset: Asset) {
        val json = withContext(Dispatchers.IO) {
            val fd = Wearable.getDataClient(ctx).getFdForAsset(asset).await()
            fd.inputStream.use { GZIPInputStream(it).readBytes().decodeToString() }
        }
        lock.withLock {
            raw = json
            withContext(Dispatchers.IO) { file.writeText(json) }
            // the phone caught up with a line we checked: let go of our copy
            val s = parse(json) ?: return
            val left = pending.value.filter { (k, done) ->
                val (n, b) = k.split('|', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                s.note(n)?.lines?.firstOrNull { it.id == b }?.let { it.done != done } == true
            }
            setPending(left)
            _state.value = withPending(s)
            _phone.value = true
        }
        NotedTile.refresh(ctx)
    }

    /** Opening the app: the newest item the Data Layer has, and a nudge for the phone to publish. */
    fun refresh() = scope.launch {
        runCatching {
            val items = Wearable.getDataClient(ctx).getDataItems(Uri.Builder().scheme("wear").path(STATE).build()).await()
            val asset = try {
                items.map { DataMapItem.fromDataItem(it).dataMap }.maxByOrNull { it.getLong("at") }?.getAsset("state")
            } finally {
                items.release()
            }
            if (asset != null) accept(asset)
        }
        _phone.value = send(SYNC, JSONObject(), queue = false)
        flush()
    }

    // ---- outgoing ----

    fun toggle(noteId: String, lineId: String, done: Boolean) {
        val now = System.currentTimeMillis()
        _ticked.value = _ticked.value.filterValues { now - it <= SHOW_MS } + (lineId to now)
        scope.launch {
            kotlinx.coroutines.delay(SHOW_MS + 150)
            _ticked.value = _ticked.value.filterValues { System.currentTimeMillis() - it <= SHOW_MS }
            NotedTile.refresh(ctx)
        }
        setPending(pending.value + ("$noteId|$lineId" to done))
        _state.value = _state.value?.let(::withPending)
        NotedTile.refresh(ctx)
        scope.launch { send(TOGGLE, JSONObject().put("n", noteId).put("b", lineId).put("d", done)) }
    }

    fun dictated(text: String, into: String?) = scope.launch {
        if (into == null) send(NEW, JSONObject().put("x", text)) else send(APPEND, JSONObject().put("n", into).put("x", text))
    }

    fun reminderDone(r: Rem) {
        _state.value = _state.value?.let { s -> s.copy(rems = s.rems.filterNot { it.id == r.id }) }
        scope.launch { send(DONE, JSONObject().put("r", r.id).put("due", r.due)) }
    }

    /** A place reminder done from the watch: its line gets checked on the phone and it goes away. */
    fun placeDone(r: PlaceRem) {
        _state.value = _state.value?.let { s -> s.copy(places = s.places.filterNot { it.id == r.id }) }
        if (r.blockId.isNotEmpty()) toggle(r.noteId, r.blockId, true)
        scope.launch { send(PLACE_DONE, JSONObject().put("r", r.id)) }
    }

    // ---- pictures ----

    private val picDir = File(ctx.filesDir, "pics").apply { mkdirs() }
    private val asked = HashSet<String>()
    /** Thumbnails that are here; a view waiting on one recomposes when it lands. */
    private val _pics = MutableStateFlow(picDir.list()?.toSet().orEmpty())
    val pics: StateFlow<Set<String>> = _pics

    fun picFile(f: String) = File(picDir, f)

    /** Asks the phone for [f] once per run, unless it's already here. */
    fun pic(f: String) {
        if (f in _pics.value || !asked.add(f)) return
        scope.launch { if (!send(PIC, JSONObject().put("f", f), queue = false)) asked.remove(f) }
    }

    /** The phone's answer: [name length][name][webp]. Keeps the 60 newest. */
    fun picArrived(data: ByteArray) {
        runCatching {
            val b = java.nio.ByteBuffer.wrap(data)
            val name = ByteArray(b.int.coerceIn(1, 200)).also { b.get(it) }.decodeToString()
            if (!Regex("[A-Za-z0-9_.-]{1,80}").matches(name)) return
            val bytes = ByteArray(b.remaining()).also { b.get(it) }
            picFile(name).writeBytes(bytes)
            picDir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(60)?.forEach { it.delete() }
            _pics.value = picDir.list()?.toSet().orEmpty()
        }
    }

    fun reminderLater(r: Rem, ms: Long) {
        _state.value = _state.value?.let { s -> s.copy(rems = s.rems.filterNot { it.id == r.id }) }
        scope.launch { send(LATER, JSONObject().put("r", r.id).put("ms", ms)) }
    }

    /** A finished recording: goes into the outbox first, so a missing phone doesn't lose it. */
    fun recorded(audio: File, ms: Long, wave: List<Int>, into: String?) = scope.launch(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        audio.renameTo(File(outbox, "$id.m4a"))
        File(outbox, "$id.json").writeText(JSONObject().put("ms", ms).put("w", JSONArray(wave)).put("n", into.orEmpty()).toString())
        flush()
    }

    /** Messages and recordings that waited for the phone. */
    suspend fun flush() = lock.withLock {
        val queued = prefs.getString("queue", null)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: JSONArray()
        if (queued.length() > 0) {
            val left = JSONArray()
            for (i in 0 until queued.length()) {
                val m = queued.getJSONObject(i)
                if (!send(m.getString("p"), m.getJSONObject("d"), queue = false)) left.put(m)
            }
            prefs.edit().putString("queue", left.toString()).apply()
        }
        withContext(Dispatchers.IO) {
            outbox.listFiles { f -> f.name.endsWith(".m4a") }?.sortedBy { it.lastModified() }?.forEach { a ->
                val meta = File(outbox, a.nameWithoutExtension + ".json")
                if (sendVoice(a, runCatching { meta.readText() }.getOrDefault("{}"))) { a.delete(); meta.delete() }
            }
        }
    }

    val waiting: Int get() =
        (prefs.getString("queue", null)?.let { runCatching { JSONArray(it).length() }.getOrNull() } ?: 0) +
            (outbox.listFiles { f -> f.name.endsWith(".m4a") }?.size ?: 0)

    private suspend fun phoneNode(): String? = withTimeoutOrNull(4000) {
        runCatching {
            val cap = Wearable.getCapabilityClient(ctx).getCapability(CAP_PHONE, CapabilityClient.FILTER_REACHABLE).await()
            cap.nodes.firstOrNull { it.isNearby }?.id ?: cap.nodes.firstOrNull()?.id
        }.getOrNull()
    }

    /** Sends now; if the phone isn't there, [queue]s it for [flush]. Returns whether it went. */
    private suspend fun send(path: String, data: JSONObject, queue: Boolean = true): Boolean {
        val ok = phoneNode()?.let { node ->
            runCatching { Wearable.getMessageClient(ctx).sendMessage(node, path, data.toString().toByteArray()).await() }.isSuccess
        } ?: false
        if (!ok && queue) {
            val q = prefs.getString("queue", null)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: JSONArray()
            q.put(JSONObject().put("p", path).put("d", data))
            prefs.edit().putString("queue", q.toString()).apply()
        }
        if (path != SYNC) _phone.value = ok
        return ok
    }

    private suspend fun sendVoice(audio: File, meta: String): Boolean {
        val node = phoneNode() ?: return false
        val cc = Wearable.getChannelClient(ctx)
        return runCatching {
            val ch = cc.openChannel(node, VOICE).await()
            try {
                DataOutputStream(cc.getOutputStream(ch).await()).use { out ->
                    val head = meta.toByteArray()
                    out.writeInt(head.size)
                    out.write(head)
                    audio.inputStream().use { it.copyTo(out) }
                }
            } finally {
                runCatching { cc.close(ch).await() }
            }
        }.isSuccess
    }

    // ---- local bits ----

    private fun withPending(s: WState): WState {
        val p = pending.value
        if (p.isEmpty()) return s
        return s.copy(notes = s.notes.map { n ->
            val mine = p.keys.filter { it.startsWith(n.id + "|") }
            if (mine.isEmpty()) n
            else {
                // checked here: shown checked and sunk to the bottom, as the phone will have it
                var lines = n.lines.map { l -> p["${n.id}|${l.id}"]?.let { l.copy(done = it) } ?: l }
                mine.forEach { k -> lines = lines.sink(k.substringAfter('|')) }
                n.copy(lines = lines)
            }
        })
    }

    private fun loadPending(): Map<String, Boolean> = runCatching {
        val o = JSONObject(prefs.getString("pending", "{}")!!)
        o.keys().asSequence().associateWith { o.getBoolean(it) }
    }.getOrDefault(emptyMap())

    private fun setPending(m: Map<String, Boolean>) {
        pending.value = m
        prefs.edit().putString("pending", JSONObject(m as Map<*, *>).toString()).apply()
    }

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
        const val CAP_PHONE = "noted_phone"
        const val SHOW_MS = 4_000L

        fun parse(json: String): WState? = runCatching {
            val o = JSONObject(json)
            fun <T> JSONArray?.list(f: (JSONObject) -> T): List<T> = if (this == null) emptyList() else List(length()) { f(getJSONObject(it)) }
            WState(
                accent = o.optString("accent", "Fire"),
                cats = o.optJSONArray("cats").list { Cat(it.optString("id"), it.optString("name"), it.optString("icon"), it.optString("parent")) },
                notes = o.optJSONArray("notes").list { n ->
                    Note(
                        id = n.optString("id"), title = n.optString("t"), color = n.optString("c"), cat = n.optString("cat"),
                        pinned = n.optBoolean("pin"), favorite = n.optBoolean("fav"), at = n.optLong("at"), readOnly = n.optBoolean("ro"),
                        locked = n.optBoolean("lock"), blurTitle = n.optBoolean("bt"), blurBody = n.optBoolean("bb"),
                        lines = n.optJSONArray("b").list { b ->
                            Line(
                                b.optString("id"), b.optString("k"), b.optString("x"), b.optBoolean("d"), b.optInt("i"), b.optLong("ms"),
                                b.optJSONArray("f")?.let { a -> List(a.length()) { a.optString(it) } }.orEmpty(),
                            )
                        },
                    )
                },
                rems = o.optJSONArray("rem").list { r ->
                    Rem(
                        r.optString("id"), r.optString("n"), r.optString("b"), r.optLong("at"),
                        r.optBoolean("rep"), r.optBoolean("due"), r.optString("t"), r.optString("nt"),
                    )
                },
                at = System.currentTimeMillis(),
                places = o.optJSONArray("prem").list { r ->
                    PlaceRem(
                        r.optString("id"), r.optString("n"), r.optString("b"), r.optString("p"), r.optString("k"),
                        r.optBoolean("leave"), r.optBoolean("always"), r.optString("t"), r.optString("nt"),
                    )
                },
            )
        }.getOrNull()
    }
}
