package com.ohav.noted.sync

import android.content.Context
import androidx.room.withTransaction
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.ohav.noted.data.Block
import com.ohav.noted.data.BlockType
import com.ohav.noted.data.Blocks
import com.ohav.noted.data.CategoryEntity
import com.ohav.noted.data.Media
import com.ohav.noted.data.MediaExtra
import com.ohav.noted.data.MediaSyncEntity
import com.ohav.noted.data.NoteEntity
import com.ohav.noted.data.NotedDatabase
import com.ohav.noted.data.ShadowEntity
import com.ohav.noted.data.Span
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.io.File

/**
 * Keeps Room (the source of truth on this device) and Firestore in step while signed in.
 *
 * Cloud layout: `notes/{id}` (owner, memberIds, meta fields and a `blocks` map of
 * id → {o, type, text, done, indent, spans, x}); `users/{uid}/categories/{id}`;
 * media files in Storage at `users/{owner}/media/{name}`.
 *
 * Every row keeps a shadow — the cloud state this device last agreed with. Pushing sends
 * only what differs from the shadow (a single block, a single field); pulling merges the
 * incoming document against the shadow and the local row (see [Merge]). Firestore's own
 * offline queue carries writes made without a network.
 */
class Sync(private val ctx: Context, private val db: NotedDatabase, private val account: Account, private val scope: CoroutineScope) {
    enum class Phase { Off, Connecting, Syncing, Synced, Offline, Error }

    data class Status(
        val phase: Phase = Phase.Off,
        val at: Long = 0L,
        val mediaPending: Int = 0,
        /** Storage isn't set up in the project (needs Blaze) — media stays on the device. */
        val mediaOff: Boolean = false,
        val message: String? = null,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    private val notes = db.notes()
    private val dao = db.sync()
    private val prefs = ctx.getSharedPreferences("sync", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val noteShadows = HashMap<String, NoteShadow>()
    private val catShadows = HashMap<String, CatShadow>()
    private val mediaKick = Channel<Unit>(Channel.CONFLATED)

    fun start() {
        if (!account.available) return
        scope.launch {
            account.user.distinctUntilChanged { a, b -> a?.uid == b?.uid }.collectLatest { u ->
                if (u == null) _status.value = Status() else runCatching { session(u) }.onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    _status.value = Status(Phase.Error, message = it.message)
                }
            }
        }
    }

    /** Try media again now (after the owner set up Storage, or the network came back). */
    fun kick() { mediaKick.trySend(Unit) }

    // ------------------------------------------------------------------ session

    @OptIn(FlowPreview::class)
    private suspend fun session(u: Account.User) = coroutineScope {
        val uid = u.uid
        _status.value = Status(Phase.Connecting)
        mutex.withLock { prepare(uid) }
        val fs = FirebaseFirestore.getInstance()
        fs.collection("users").document(uid).set(
            mapOf("name" to u.name, "email" to u.email, "photo" to u.photoUrl, "seenAt" to FieldValue.serverTimestamp()),
            SetOptions.merge(),
        )

        val inbox = Channel<Pair<Char, QuerySnapshot>>(Channel.UNLIMITED)
        val regs: List<ListenerRegistration> = listOf(
            fs.collection("notes").whereArrayContains("memberIds", uid)
                .addSnapshotListener(MetadataChanges.INCLUDE) { s, e -> if (s != null) inbox.trySend('n' to s) else failed(e) },
            fs.collection("users").document(uid).collection("categories")
                .addSnapshotListener(MetadataChanges.INCLUDE) { s, e -> if (s != null) inbox.trySend('c' to s) else failed(e) },
        )
        // nothing is pushed before this device has seen the cloud once, so a second phone merges instead of duplicating
        val primed = MutableStateFlow(prefs.getBoolean("primed:$uid", false))
        try {
            launch {
                var serverNotes = false
                var serverCats = false
                for ((k, s) in inbox) {
                    mutex.withLock { if (k == 'n') pullNotes(s) else pullCats(s) }
                    if (!s.metadata.isFromCache) if (k == 'n') serverNotes = true else serverCats = true
                    if (!primed.value && serverNotes && serverCats) {
                        mutex.withLock { firstContact() }
                        prefs.edit().putBoolean("primed:$uid", true).apply()
                        primed.value = true
                    }
                    if (k == 'n') {
                        val m = s.metadata
                        _status.value = _status.value.copy(
                            phase = when {
                                !primed.value -> Phase.Connecting
                                m.isFromCache -> Phase.Offline
                                m.hasPendingWrites() -> Phase.Syncing
                                else -> Phase.Synced
                            },
                            at = if (!m.isFromCache && !m.hasPendingWrites()) System.currentTimeMillis() else _status.value.at,
                            message = null,
                        )
                    }
                    kick()
                }
            }
            launch {
                primed.first { it }
                combine(notes.observeAll(), notes.observeCategories()) { a, b -> a to b }
                    .debounce(700)
                    .collect { (ns, cs) ->
                        mutex.withLock {
                            pushCats(fs, uid, cs)
                            pushNotes(fs, uid, ns)
                        }
                        kick()
                    }
            }
            launch {
                primed.first { it }
                mediaKick.receiveAsFlow().debounce(1500).collect { runCatching { media(uid) } }
            }
            awaitCancellation()
        } finally {
            regs.forEach { it.remove() }
            inbox.close()
        }
    }

    private fun failed(e: FirebaseFirestoreException?) {
        _status.value = _status.value.copy(
            phase = Phase.Error,
            message = when (e?.code) {
                FirebaseFirestoreException.Code.PERMISSION_DENIED -> "אין הרשאה לענן"
                FirebaseFirestoreException.Code.NOT_FOUND, FirebaseFirestoreException.Code.FAILED_PRECONDITION -> "מסד הנתונים בענן עוד לא נוצר"
                else -> e?.message
            },
        )
    }

    /** Shadows belong to one account: another account starts from scratch. */
    private suspend fun prepare(uid: String) {
        if (prefs.getString("uid", null) != uid) {
            dao.clearShadows()
            dao.clearMedia()
            prefs.edit().putString("uid", uid).apply()
        }
        noteShadows.clear(); catShadows.clear()
        dao.shadows().forEach { s ->
            when {
                s.id.startsWith("n:") -> NoteShadow.decode(s.json)?.let { noteShadows[s.id.drop(2)] = it }
                s.id.startsWith("c:") -> CatShadow.decode(s.json)?.let { catShadows[s.id.drop(2)] = it }
            }
        }
    }

    /**
     * First time this device meets an account that already has notes: drop the untouched
     * welcome notes of a fresh install instead of uploading a second copy.
     */
    private suspend fun firstContact() {
        if (noteShadows.isEmpty()) return
        notes.all().filter { it.id !in noteShadows && it.createdAt == it.updatedAt && it.title in SEED_TITLES }
            .forEach { notes.delete(it.id) }
    }

    // ------------------------------------------------------------------ notes

    private suspend fun pullNotes(s: QuerySnapshot) {
        for (ch in s.documentChanges) {
            val d = ch.document
            if (d.metadata.hasPendingWrites()) continue // our own write; its server echo comes later
            if (ch.type == com.google.firebase.firestore.DocumentChange.Type.REMOVED) {
                db.withTransaction { notes.delete(d.id); dao.dropShadow("n:${d.id}") }
                noteShadows.remove(d.id)
                continue
            }
            val r = NoteShadow.of(d) ?: continue
            val base = noteShadows[d.id]
            if (base == r) continue
            db.withTransaction {
                val local = notes.get(d.id)
                val merged = if (local == null) r.toEntity() else merge(base, local, r)
                if (merged != local) notes.upsert(merged)
                dao.putShadow(ShadowEntity("n:${d.id}", r.encode()))
            }
            noteShadows[d.id] = r
        }
    }

    private fun merge(base: NoteShadow?, local: NoteEntity, r: NoteShadow): NoteEntity {
        val mine = Blocks.parse(local.blocks)
        val blocks = Merge.blocks(base?.blocks ?: emptyList(), mine, r.blocks)
        fun <T> f(b: T?, m: T, t: T): T = if (base == null) m else Merge.pick(b as T, m, t)
        return local.copy(
            title = f(base?.title, local.title, r.title),
            blocks = if (blocks == mine) local.blocks else Blocks.serialize(blocks),
            color = f(base?.color, local.color, r.color),
            categoryId = f(base?.categoryId, local.categoryId, r.categoryId),
            pinned = f(base?.pinned, local.pinned, r.pinned),
            favorite = f(base?.favorite, local.favorite, r.favorite),
            archived = f(base?.archived, local.archived, r.archived),
            trashedAt = if (base == null) local.trashedAt else Merge.pick(base.trashedAt, local.trashedAt, r.trashedAt),
            position = f(base?.position, local.position, r.position),
            updatedAt = maxOf(local.updatedAt, r.updatedAt),
        )
    }

    private suspend fun pushNotes(fs: FirebaseFirestore, uid: String, all: List<NoteEntity>) {
        for (n in all) {
            val sh = noteShadows[n.id]
            if (sh != null && sh.sameMeta(n) && sh.blocksJson == n.blocks) continue
            val blocks = Blocks.parse(n.blocks)
            if (sh != null && sh.sameMeta(n) && sh.blocks == blocks) continue
            val orders = Orders.assign(blocks.map { it.id }, sh?.orders ?: emptyMap())
            val ref = fs.collection("notes").document(n.id)
            if (sh == null) {
                val doc = HashMap<String, Any?>(meta(n))
                doc["owner"] = uid
                doc["memberIds"] = listOf(uid)
                doc["members"] = mapOf(uid to "owner")
                doc["createdAt"] = n.createdAt
                doc["blocks"] = blocks.associate { it.id to blockMap(it, orders.getValue(it.id)) }
                doc["by"] = uid
                doc["at"] = FieldValue.serverTimestamp()
                ref.set(doc, SetOptions.merge()).addOnFailureListener { forget(n.id) }
            } else {
                val upd = HashMap<String, Any?>()
                meta(n).forEach { (k, v) -> if (sh.metaValue(k) != v) upd[k] = v }
                val prev = sh.blocks.associateBy { it.id }
                for (b in blocks) {
                    val o = orders.getValue(b.id)
                    when {
                        prev[b.id] != b -> upd["blocks.${b.id}"] = blockMap(b, o)
                        sh.orders[b.id] != o -> upd["blocks.${b.id}.o"] = o
                    }
                }
                val ids = blocks.mapTo(HashSet()) { it.id }
                prev.keys.filter { it !in ids }.forEach { upd["blocks.$it"] = FieldValue.delete() }
                upd["updatedAt"] = n.updatedAt
                upd["by"] = uid
                upd["at"] = FieldValue.serverTimestamp()
                ref.update(upd).addOnFailureListener { forget(n.id) }
            }
            val next = NoteShadow.of(n, blocks, orders, sh?.owner ?: uid)
            noteShadows[n.id] = next
            dao.putShadow(ShadowEntity("n:${n.id}", next.encode()))
        }
    }

    /** A write the cloud refused (document gone, rules): the next push sends the whole note again. */
    private fun forget(id: String) {
        scope.launch { mutex.withLock { noteShadows.remove(id); dao.dropShadow("n:$id") } }
    }

    private fun meta(n: NoteEntity): Map<String, Any?> = mapOf(
        "title" to n.title, "color" to n.color, "categoryId" to n.categoryId, "pinned" to n.pinned,
        "favorite" to n.favorite, "archived" to n.archived, "trashedAt" to n.trashedAt, "position" to n.position,
        "updatedAt" to n.updatedAt,
    )

    private fun blockMap(b: Block, o: Double): Map<String, Any?> = buildMap {
        put("o", o)
        put("type", b.type.name)
        put("text", b.text)
        put("done", b.done)
        put("indent", b.indent)
        put("spans", b.spans.map { mapOf("s" to it.start, "e" to it.end, "t" to it.style) })
        put("x", b.extra)
    }

    // ------------------------------------------------------------------ categories

    private suspend fun pullCats(s: QuerySnapshot) {
        for (ch in s.documentChanges) {
            val d = ch.document
            if (d.metadata.hasPendingWrites()) continue
            val r = CatShadow.of(d)
            val base = catShadows[d.id]
            if (base == r) continue
            db.withTransaction {
                val local = notes.categories().firstOrNull { it.id == d.id }
                if (r.deleted) {
                    if (local != null) {
                        val rest = notes.categories().filter { it.id != d.id }.minByOrNull { it.position }
                        val ids = notes.liveNoteIdsIn(d.id)
                        if (rest != null && ids.isNotEmpty()) notes.setCategory(ids, rest.id, System.currentTimeMillis())
                        if (rest != null) notes.deleteCategory(d.id)
                    }
                } else if (local == null) {
                    if (base == null || base.deleted) notes.upsertCategory(CategoryEntity(d.id, r.name, r.position, r.icon))
                } else {
                    val m = if (base == null) local else CategoryEntity(
                        d.id, Merge.pick(base.name, local.name, r.name), Merge.pick(base.position, local.position, r.position),
                        Merge.pick(base.icon, local.icon, r.icon),
                    )
                    if (m != local) notes.upsertCategory(m)
                }
                dao.putShadow(ShadowEntity("c:${d.id}", r.encode()))
            }
            catShadows[d.id] = r
        }
    }

    private suspend fun pushCats(fs: FirebaseFirestore, uid: String, cats: List<CategoryEntity>) {
        val col = fs.collection("users").document(uid).collection("categories")
        val live = cats.associateBy { it.id }
        for (k in cats) {
            val sh = catShadows[k.id]
            val now = CatShadow(k.name, k.icon, k.position, false)
            if (sh == now) continue
            val upd = HashMap<String, Any?>()
            if (sh == null || sh.deleted || sh.name != k.name) upd["name"] = k.name
            if (sh == null || sh.deleted || sh.icon != k.icon) upd["icon"] = k.icon
            if (sh == null || sh.deleted || sh.position != k.position) upd["position"] = k.position
            if (sh == null || sh.deleted) upd["deleted"] = false
            col.document(k.id).set(upd, SetOptions.merge())
            catShadows[k.id] = now
            dao.putShadow(ShadowEntity("c:${k.id}", now.encode()))
        }
        for ((id, sh) in catShadows.toList()) {
            if (id in live || sh.deleted) continue
            col.document(id).set(mapOf("deleted" to true), SetOptions.merge())
            val gone = sh.copy(deleted = true)
            catShadows[id] = gone
            dao.putShadow(ShadowEntity("c:$id", gone.encode()))
        }
    }

    // ------------------------------------------------------------------ media

    private suspend fun media(uid: String) {
        val bucket = runCatching { com.google.firebase.FirebaseApp.getInstance().options.storageBucket }.getOrNull()
        val storage = if (bucket.isNullOrEmpty()) null else runCatching { FirebaseStorage.getInstance() }.getOrNull()
        if (storage == null) { _status.value = _status.value.copy(mediaOff = true); return }
        val known = dao.media().associateBy { it.name }
        // file → owner folder, over every note that has media blocks
        val wanted = LinkedHashMap<String, String>()
        for (n in notes.all()) {
            if (!n.blocks.contains("\"x\"")) continue
            val owner = noteShadows[n.id]?.owner ?: uid
            Blocks.parse(n.blocks).forEach { b -> if (b.type in MEDIA) MediaExtra.files(b).forEach { wanted.putIfAbsent(it, owner) } }
        }
        val todo = wanted.filter { (name, owner) ->
            val f = Media.file(ctx, name)
            if (f.exists()) owner == uid && known[name]?.uploaded != true else true
        }
        _status.value = _status.value.copy(mediaPending = todo.size)
        var left = todo.size
        for ((name, owner) in todo) {
            val f = Media.file(ctx, name)
            val ref = storage.reference.child("users/$owner/media/$name")
            val ok = runCatching {
                if (f.exists()) {
                    ref.putFile(android.net.Uri.fromFile(f)).await()
                    dao.putMedia(MediaSyncEntity(name, owner, true))
                } else {
                    val tmp = File(f.parentFile, "$name.part")
                    ref.getFile(tmp).await()
                    if (!tmp.renameTo(f)) tmp.delete()
                    dao.putMedia(MediaSyncEntity(name, owner, true))
                    Media.arrived.tryEmit(name)
                }
            }
            val e = ok.exceptionOrNull()
            if (e is StorageException) {
                when (e.errorCode) {
                    StorageException.ERROR_BUCKET_NOT_FOUND, StorageException.ERROR_PROJECT_NOT_FOUND, StorageException.ERROR_NOT_AUTHENTICATED -> {
                        _status.value = _status.value.copy(mediaOff = true, mediaPending = left); return
                    }
                    StorageException.ERROR_RETRY_LIMIT_EXCEEDED -> { _status.value = _status.value.copy(mediaPending = left); return }
                    // missing on the cloud too (uploader still offline): skip for now
                }
            } else if (e != null) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _status.value = _status.value.copy(mediaPending = left); return
            }
            left--
            _status.value = _status.value.copy(mediaPending = left, mediaOff = false)
        }
    }

    companion object {
        private val MEDIA = setOf(BlockType.Images, BlockType.Voice, BlockType.Draw, BlockType.Link)
        val SEED_TITLES = setOf("קניות לשבת", "שקשוקה של סבתא", "רעיונות לטיול בגליל", "פגישת צוות", "ספרים לקרוא")
    }
}

/** A note as the cloud has it (and as this device last agreed with it). */
data class NoteShadow(
    val owner: String,
    val title: String,
    val blocks: List<Block>,
    val orders: Map<String, Double>,
    val color: String,
    val categoryId: String,
    val pinned: Boolean,
    val favorite: Boolean,
    val archived: Boolean,
    val trashedAt: Long?,
    val position: Double,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val blocksJson: String by lazy { Blocks.serialize(blocks) }

    fun sameMeta(n: NoteEntity) = title == n.title && color == n.color && categoryId == n.categoryId && pinned == n.pinned &&
        favorite == n.favorite && archived == n.archived && trashedAt == n.trashedAt && position == n.position

    fun metaValue(k: String): Any? = when (k) {
        "title" -> title; "color" -> color; "categoryId" -> categoryId; "pinned" -> pinned; "favorite" -> favorite
        "archived" -> archived; "trashedAt" -> trashedAt; "position" -> position; "updatedAt" -> updatedAt
        else -> null
    }

    fun toEntity() = NoteEntity(
        id = id, title = title, blocks = blocksJson, color = color, categoryId = categoryId, pinned = pinned, favorite = favorite,
        archived = archived, trashedAt = trashedAt, position = position, createdAt = createdAt, updatedAt = updatedAt,
    )

    fun encode(): String = JSONObject()
        .put("ow", owner).put("t", title).put("b", blocksJson).put("o", JSONObject(orders as Map<*, *>))
        .put("c", color).put("k", categoryId).put("p", pinned).put("f", favorite).put("a", archived)
        .put("tr", trashedAt ?: JSONObject.NULL).put("pos", position).put("cr", createdAt).put("u", updatedAt)
        .toString()

    companion object {
        fun decode(s: String): NoteShadow? = runCatching {
            val o = JSONObject(s)
            val ord = o.getJSONObject("o")
            NoteShadow(
                o.getString("ow"), o.getString("t"), Blocks.parse(o.getString("b")),
                ord.keys().asSequence().associateWith { ord.getDouble(it) },
                o.getString("c"), o.getString("k"), o.getBoolean("p"), o.getBoolean("f"), o.getBoolean("a"),
                if (o.isNull("tr")) null else o.getLong("tr"), o.getDouble("pos"), o.getLong("cr"), o.getLong("u"),
            )
        }.getOrNull()

        fun of(n: NoteEntity, blocks: List<Block>, orders: Map<String, Double>, owner: String) = NoteShadow(
            owner, n.title, blocks, orders, n.color, n.categoryId, n.pinned, n.favorite, n.archived, n.trashedAt,
            n.position, n.createdAt, n.updatedAt,
        )

        /** Reads a `notes/{id}` document; null if it isn't one of ours yet (half-written). */
        fun of(d: DocumentSnapshot): NoteShadow? {
            val owner = d.getString("owner") ?: return null
            @Suppress("UNCHECKED_CAST")
            val raw = (d.get("blocks") as? Map<String, Any?>).orEmpty()
            val list = raw.mapNotNull { (id, v) ->
                val m = v as? Map<*, *> ?: return@mapNotNull null
                val o = (m["o"] as? Number)?.toDouble() ?: 0.0
                val spans = (m["spans"] as? List<*>).orEmpty().mapNotNull { sp ->
                    val x = sp as? Map<*, *> ?: return@mapNotNull null
                    Span((x["s"] as? Number)?.toInt() ?: 0, (x["e"] as? Number)?.toInt() ?: 0, x["t"] as? String ?: return@mapNotNull null)
                }
                val extra = (m["x"] as? String).orEmpty().let { x -> if (x.isEmpty()) "" else runCatching { JSONObject(x).toString() }.getOrDefault("") }
                Triple(id, o, Block(
                    id, runCatching { BlockType.valueOf(m["type"] as? String ?: "P") }.getOrDefault(BlockType.P),
                    m["text"] as? String ?: "", m["done"] as? Boolean ?: false, (m["indent"] as? Number)?.toInt() ?: 0, spans, extra,
                ))
            }.sortedWith(compareBy({ it.second }, { it.first }))
            return NoteShadow(
                owner = owner,
                title = d.getString("title").orEmpty(),
                blocks = list.map { it.third },
                orders = list.associate { it.first to it.second },
                color = d.getString("color") ?: "Butter",
                categoryId = d.getString("categoryId").orEmpty(),
                pinned = d.getBoolean("pinned") ?: false,
                favorite = d.getBoolean("favorite") ?: false,
                archived = d.getBoolean("archived") ?: false,
                trashedAt = d.getLong("trashedAt"),
                position = d.getDouble("position") ?: 0.0,
                createdAt = d.getLong("createdAt") ?: 0L,
                updatedAt = d.getLong("updatedAt") ?: 0L,
            ).also { it.id = d.id }
        }
    }

    /** Document id, set when read from the cloud (not part of equality). */
    var id: String = ""
}

data class CatShadow(val name: String, val icon: String, val position: Int, val deleted: Boolean) {
    fun encode(): String = JSONObject().put("n", name).put("i", icon).put("p", position).put("d", deleted).toString()

    companion object {
        fun decode(s: String): CatShadow? = runCatching {
            val o = JSONObject(s)
            CatShadow(o.getString("n"), o.getString("i"), o.getInt("p"), o.getBoolean("d"))
        }.getOrNull()

        fun of(d: DocumentSnapshot) = CatShadow(
            d.getString("name").orEmpty(), d.getString("icon") ?: "folder", (d.getLong("position") ?: 0L).toInt(), d.getBoolean("deleted") ?: false,
        )
    }
}
