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
import com.ohav.noted.data.ReminderEntity
import com.ohav.noted.data.PlaceEntity
import com.ohav.noted.data.PlaceReminderEntity
import com.ohav.noted.data.TemplateEntity
import com.ohav.noted.data.Share
import com.ohav.noted.data.Member
import com.ohav.noted.data.shareInfo
import com.ohav.noted.data.foreign
import com.ohav.noted.data.Span
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.io.File

/**
 * Keeps Room (the source of truth on this device) and Firestore in step while signed in.
 *
 * Cloud layout: `notes/{id}` (owner, memberIds, members uid → role, people uid → {n, p},
 * the owner's meta fields at the top level and every other member's under `u.<uid>`, and a
 * `blocks` map of id → {o, type, text, done, indent, spans, x, by}); `users/{uid}/categories/{id}`;
 * media files in Storage at `users/{uploader}/media/{name}`.
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
    /** Notes [discard]ed: a push already holding the older list must not send them back up. */
    private val discarded = HashSet<String>()
    private val catShadows = HashMap<String, CatShadow>()
    /** Time reminders: the `at` of the copy last agreed with the cloud (negative = a tombstone). */
    private val remShadows = HashMap<String, Long>()
    /** Places and place reminders: the copy last agreed with the cloud, keyed `p:<id>` / `q:<id>` (see [PlShadow]). */
    private val plShadows = HashMap<String, PlShadow>()
    /** My templates as the cloud has them: id → `at` (last write wins, see [pullTemplates]). */
    private val tplCloud = HashMap<String, Long>()
    /** The templates listener has delivered once: only then is it known what the cloud lacks. */
    private var tplSeen = false
    /** Shared folders I'm on, as the cloud last had them (`folders/{id}`, same id as the owner's category). */
    private val folders = HashMap<String, FolderShadow>()
    /** The folders listener has heard from the server: only then may a missing folder be created. */
    private var foldersSeen = false
    private val mediaKick = Channel<Unit>(Channel.CONFLATED)
    /**
     * My shared folders whose category is gone, and since when. The folder outlives it for
     * [FOLDER_GRACE] so an undo gets back the same folder, members and all.
     */
    private val doomed = HashMap<String, Long>()
    /** Bumped to run a push with no local change (a doomed folder's grace ran out). */
    private val repush = MutableStateFlow(0)

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
    private suspend fun session(u: Account.User): Unit = coroutineScope {
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
            fs.collection("folders").whereArrayContains("memberIds", uid)
                .addSnapshotListener(MetadataChanges.INCLUDE) { s, e -> if (s != null) inbox.trySend('f' to s) else failed(e) },
            fs.collection("users").document(uid).collection("templates")
                .addSnapshotListener(MetadataChanges.INCLUDE) { s, e -> if (s != null) inbox.trySend('t' to s) else failed(e) },
            fs.collection("users").document(uid).collection("reminders")
                .addSnapshotListener(MetadataChanges.INCLUDE) { s, e -> if (s != null) inbox.trySend('r' to s) else failed(e) },
            fs.collection("users").document(uid).collection(PLACES)
                .addSnapshotListener(MetadataChanges.INCLUDE) { s, e -> if (s != null) inbox.trySend('p' to s) else failed(e) },
            fs.collection("users").document(uid).collection(PLACE_REMINDERS)
                .addSnapshotListener(MetadataChanges.INCLUDE) { s, e -> if (s != null) inbox.trySend('q' to s) else failed(e) },
        )
        launch { runCatching { Push.register(ctx, uid) } }
        // nothing is pushed before this device has seen the cloud once, so a second phone merges instead of duplicating
        val primed = MutableStateFlow(prefs.getBoolean("primed:$uid", false))
        try {
            launch {
                var serverNotes = false
                var serverCats = false
                for ((k, s) in inbox) {
                    mutex.withLock {
                        when (k) {
                            'n' -> pullNotes(s, uid)
                            'c' -> pullCats(s, uid)
                            't' -> pullTemplates(s)
                            'r' -> pullReminders(s)
                            'p' -> pullPlaces(s)
                            'q' -> pullPlaceReminders(s)
                            else -> pullFolders(s, uid)
                        }
                    }
                    if (!s.metadata.isFromCache) when (k) {
                        'n' -> serverNotes = true
                        'c' -> serverCats = true
                        't', 'r', 'p', 'q' -> Unit
                        else -> foldersSeen = true
                    }
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
                val placed = combine(db.places().observePlaces(), db.places().observeReminders()) { p, q -> p to q }
                val timed = combine(db.reminders().observeAll(), placed) { r, pq -> Triple(r, pq.first, pq.second) }
                combine(notes.observeAll(), notes.observeCategories(), db.templates().observeAll(), timed, repush) { a, b, t, r, _ -> Pushes(a, b, t, r.first, r.second, r.third) }
                    .debounce(700)
                    .collect { (ns, cs, ts, rs, ps, qs) ->
                        mutex.withLock {
                            pushCats(fs, uid, cs)
                            pushFolders(fs, u, cs)
                            pushNotes(fs, u, ns, cs)
                            pushTemplates(fs, uid, ts)
                            pushReminders(fs, uid, rs)
                            pushPlaces(fs, uid, ps, qs)
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
        noteShadows.clear(); catShadows.clear(); remShadows.clear(); plShadows.clear(); folders.clear(); doomed.clear(); foldersSeen = false
        tplCloud.clear(); tplSeen = false
        dao.shadows().forEach { s ->
            when {
                s.id.startsWith("n:") -> NoteShadow.decode(s.json)?.let { noteShadows[s.id.drop(2)] = it }
                s.id.startsWith("c:") -> CatShadow.decode(s.json)?.let { catShadows[s.id.drop(2)] = it }
                s.id.startsWith("r:") -> s.json.toLongOrNull()?.let { remShadows[s.id.drop(2)] = it }
                s.id.startsWith("p:") || s.id.startsWith("q:") -> PlShadow.decode(s.json)?.let { plShadows[s.id] = it }
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

    /**
     * A new note left empty: off this device and out of the cloud for good, no trash and no
     * tombstone. Only my own note — someone else's never goes from here.
     */
    suspend fun discard(id: String): Unit = mutex.withLock {
        discarded += id
        // before the session loaded the shadows (launch sweep), the stored one tells
        val sh = noteShadows.remove(id) ?: dao.shadows().firstOrNull { it.id == "n:$id" }?.let { NoteShadow.decode(it.json) }
        db.withTransaction { notes.delete(id); dao.dropShadow("n:$id") }
        val uid = account.user.value?.uid
        // not awaited: offline, the delete waits in Firestore's queue like any other write
        if (sh != null && account.available && uid != null && sh.owner == uid) {
            FirebaseFirestore.getInstance().collection("notes").document(id).delete()
        }
    }

    /** The cloud already has this note (it can be shared). */
    suspend fun inCloud(id: String): Boolean = mutex.withLock { noteShadows[id]?.let { !it.gone } == true }

    private suspend fun pullNotes(s: QuerySnapshot, uid: String) {
        for (ch in s.documentChanges) {
            val d = ch.document
            if (d.metadata.hasPendingWrites()) continue // our own write; its server echo comes later
            if (ch.type == com.google.firebase.firestore.DocumentChange.Type.REMOVED) {
                db.withTransaction { notes.delete(d.id); dao.dropShadow("n:${d.id}") }
                noteShadows.remove(d.id)
                continue
            }
            if (d.id in discarded) continue // an echo from before it was dropped
            val r = NoteShadow.of(d, uid) ?: continue
            val base = noteShadows[d.id]
            if (base == r) continue
            db.withTransaction {
                val local = notes.get(d.id)
                if (r.gone) {
                    // the owner put it in their trash: it leaves everyone else's notes until restored
                    if (local != null) notes.delete(d.id)
                } else {
                    val merged = when {
                        local == null -> r.toEntity().let {
                            // joined just now: on top of my stack, until I place it
                            if (r.owner != uid && !r.personal) it.copy(position = (notes.minPosition() ?: 0.0) - 1.0) else it
                        }
                        else -> merge(base?.takeUnless { it.gone }, local, r)
                    }.copy(share = r.share(uid).encode())
                    if (merged != local) notes.upsert(merged)
                }
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

    private suspend fun pushNotes(fs: FirebaseFirestore, u: Account.User, all: List<NoteEntity>, cats: List<CategoryEntity>) {
        val uid = u.uid
        val byId = cats.associateBy { it.id }
        for (n in all) {
            if (n.id in discarded) continue
            val sh = noteShadows[n.id]
            // someone else's note: never recreate it from here, wait for the cloud to hand it over
            if (sh == null && n.shareInfo.owner.let { it.isNotEmpty() && it != uid }) continue
            if (sh?.gone == true) continue
            val folder = if (sh == null || sh.owner == uid) folderOf(n, byId) else sh.folder
            if (sh != null && sh.sameMeta(n) && sh.folder == folder && sh.blocksJson == n.blocks) continue
            val blocks = Blocks.parse(n.blocks)
            if (sh != null && sh.sameMeta(n) && sh.folder == folder && sh.blocks == blocks) continue
            val orders = Orders.assign(blocks.map { it.id }, sh?.orders ?: emptyMap())
            val ref = fs.collection("notes").document(n.id)
            if (sh == null) {
                val doc = HashMap<String, Any?>(meta(n))
                doc["owner"] = uid
                doc["memberIds"] = listOf(uid)
                doc["members"] = mapOf(uid to Share.ROLE_OWNER)
                doc["people"] = mapOf(uid to person(u))
                doc["createdAt"] = n.createdAt
                doc["blocks"] = blocks.associate { it.id to blockMap(it, orders.getValue(it.id)) }
                doc["by"] = uid
                doc["at"] = FieldValue.serverTimestamp()
                if (folder.isNotEmpty()) doc["folder"] = folder
                ref.set(doc, SetOptions.merge()).addOnFailureListener { forget(n.id) }
                val next = NoteShadow.of(n, blocks, orders, uid, null).copy(folder = folder)
                noteShadows[n.id] = next
                dao.putShadow(ShadowEntity("n:${n.id}", next.encode()))
                continue
            }
            val mine = sh.owner == uid
            val canEdit = sh.role(uid) != Share.ROLE_VIEWER
            val upd = HashMap<String, Any?>()
            var content = false
            if (mine && sh.folder != folder) upd["folder"] = if (folder.isEmpty()) FieldValue.delete() else folder
            meta(n).forEach { (k, v) ->
                if (k == "updatedAt" || sh.metaValue(k) == v) return@forEach
                when {
                    k in PERSONAL -> upd[if (mine) k else "u.$uid.$k"] = v
                    canEdit -> { upd[k] = v; content = true }
                }
            }
            // a viewer's copy can't change the shared content: keep agreeing with the cloud's
            var keptBlocks = sh.blocks
            var keptOrders = sh.orders
            val by = HashMap(sh.by)
            if (canEdit) {
                keptBlocks = blocks
                keptOrders = orders
                val prev = sh.blocks.associateBy { it.id }
                for (b in blocks) {
                    val o = orders.getValue(b.id)
                    when {
                        prev[b.id] != b -> { upd["blocks.${b.id}"] = blockMap(b, o) + ("by" to uid); by[b.id] = uid; content = true }
                        sh.orders[b.id] != o -> { upd["blocks.${b.id}.o"] = o; content = true }
                    }
                }
                val ids = blocks.mapTo(HashSet()) { it.id }
                prev.keys.filter { it !in ids }.forEach { upd["blocks.$it"] = FieldValue.delete(); by.remove(it); content = true }
            }
            if (content || (mine && sh.updatedAt != n.updatedAt)) {
                upd["updatedAt"] = n.updatedAt
                upd["by"] = uid
                upd["at"] = FieldValue.serverTimestamp()
            }
            if (upd.isNotEmpty()) ref.update(upd).addOnFailureListener { forget(n.id) }
            val next = NoteShadow.of(n, keptBlocks, keptOrders, sh.owner, sh).copy(folder = folder).let {
                if (canEdit) it.copy(by = by) else it.copy(title = sh.title, color = sh.color, updatedAt = sh.updatedAt)
            }.let { if (!mine && upd.keys.any { k -> k.startsWith("u.") }) it.copy(personal = true) else it }
            noteShadows[n.id] = next
            dao.putShadow(ShadowEntity("n:${n.id}", next.encode()))
            if (next.by != sh.by) notes.setShare(n.id, next.share(uid).encode())
        }
    }

    /**
     * The shared folder a note of mine belongs in: its top-level category's, when that one is
     * shared and I may add to it ("" otherwise).
     */
    private fun folderOf(n: NoteEntity, byId: Map<String, CategoryEntity>): String {
        val k = byId[n.categoryId] ?: return ""
        val top = if (k.parent.isNotEmpty()) byId[k.parent] ?: k else k
        if (top.share.isEmpty() || top.parent.isNotEmpty()) return ""
        return if (top.shareInfo.role == Share.ROLE_VIEWER) "" else top.id
    }

    private fun person(u: Account.User) = mapOf("n" to u.name.ifBlank { u.email.substringBefore('@') }, "p" to u.photoUrl)

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

    private suspend fun pullCats(s: QuerySnapshot, uid: String) {
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
                    if (base == null || base.deleted) {
                        val share = folders[d.id]?.takeIf { it.owner == uid }?.share(uid)?.encode().orEmpty()
                        notes.upsertCategory(CategoryEntity(d.id, r.name, r.position, r.icon, r.parent, share))
                    }
                } else {
                    val m = if (base == null) local else local.copy(
                        name = Merge.pick(base.name, local.name, r.name), position = Merge.pick(base.position, local.position, r.position),
                        icon = Merge.pick(base.icon, local.icon, r.icon), parent = Merge.pick(base.parent, local.parent, r.parent),
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
            if (k.foreign) continue // someone else's folder: it lives in `folders/`, not in my categories
            val sh = catShadows[k.id]
            val now = CatShadow(k.name, k.icon, k.position, false, k.parent)
            if (sh == now) continue
            val upd = HashMap<String, Any?>()
            if (sh == null || sh.deleted || sh.name != k.name) upd["name"] = k.name
            if (sh == null || sh.deleted || sh.icon != k.icon) upd["icon"] = k.icon
            if (sh == null || sh.deleted || sh.position != k.position) upd["position"] = k.position
            if (sh == null || sh.deleted || sh.parent != k.parent) upd["parent"] = k.parent
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

    // ------------------------------------------------------------------ reminders

    private data class Pushes(
        val notes: List<NoteEntity>, val cats: List<CategoryEntity>, val templates: List<TemplateEntity>, val reminders: List<ReminderEntity>,
        val places: List<PlaceEntity>, val placeReminders: List<PlaceReminderEntity>,
    )

    /**
     * `users/{uid}/reminders/{id}`: my time reminders as whole documents, the newest `at` wins
     * (the website and every device ring from the same list). A deletion is a tombstone.
     */
    private suspend fun pullReminders(s: QuerySnapshot) {
        for (d in s.documents) {
            if (d.metadata.hasPendingWrites()) continue
            applyReminder(d.id, d.data ?: continue)
        }
    }

    /** A reminder that came in a push message (the app may have been asleep, with no listener). */
    suspend fun applyPushedReminder(id: String, f: Map<String, Any?>) = mutex.withLock { applyReminder(id, f) }

    /** One cloud copy of a reminder, from the listener or from a push message. */
    private suspend fun applyReminder(id: String, f: Map<String, Any?>) {
        val at = (f["at"] as? Number)?.toLong() ?: return
        val deleted = f["deleted"] == true
        val known = remShadows[id] ?: dao.shadows().firstOrNull { it.id == "r:$id" }?.json?.toLongOrNull()
        if (known != null && kotlin.math.abs(known) == at && (known < 0) == deleted) return
        val rd = db.reminders()
        val local = rd.get(id)
        if (deleted) {
            if (local != null && local.at <= at) {
                rd.delete(id)
                if (local.blockId.isEmpty()) rd.mirror(local.noteId, null)
                androidx.core.app.NotificationManagerCompat.from(ctx).cancel(com.ohav.noted.reminders.Notifier.idOf(id))
                com.ohav.noted.reminders.Shade.forget(ctx, com.ohav.noted.reminders.Shade.time(id))
            }
        } else if (local == null || local.at < at) {
            val r = ReminderEntity(
                id = id, noteId = f["noteId"] as? String ?: return, blockId = f["blockId"] as? String ?: "",
                anchor = f["anchor"] as? String ?: return, occ = f["occ"] as? String ?: return,
                zone = f["zone"] as? String ?: java.time.ZoneId.systemDefault().id, rule = f["rule"] as? String ?: "",
                until = f["until"] as? String, fired = f["fired"] == true, snoozeAt = (f["snoozeAt"] as? Number)?.toLong(),
                sticky = f["sticky"] == true, at = at,
            )
            rd.upsert(r)
            if (r.blockId.isEmpty()) rd.mirror(r.noteId, r.occAt)
            // a reminder done / moved somewhere else stops ringing here
            if (local != null && (r.dueAt ?: Long.MAX_VALUE) > System.currentTimeMillis()) {
                androidx.core.app.NotificationManagerCompat.from(ctx).cancel(com.ohav.noted.reminders.Notifier.idOf(id))
            }
        }
        val sh = if (deleted) -at else at
        remShadows[id] = sh
        dao.putShadow(ShadowEntity("r:$id", sh.toString()))
        (ctx.applicationContext as? com.ohav.noted.NotedApp)?.reminders?.schedule()
    }

    private suspend fun pushReminders(fs: FirebaseFirestore, uid: String, rows: List<ReminderEntity>) {
        val col = fs.collection("users").document(uid).collection("reminders")
        val live = rows.associateBy { it.id }
        for (r in rows) {
            val at = r.at.coerceAtLeast(1L)
            if (remShadows[r.id] == at) continue
            col.document(r.id).set(
                mapOf(
                    "noteId" to r.noteId, "blockId" to r.blockId, "anchor" to r.anchor, "occ" to r.occ, "zone" to r.zone,
                    "rule" to r.rule, "until" to r.until, "fired" to r.fired, "snoozeAt" to r.snoozeAt, "sticky" to r.sticky,
                    "at" to at, "deleted" to false, "by" to "android",
                ),
            )
            remShadows[r.id] = at
            dao.putShadow(ShadowEntity("r:${r.id}", at.toString()))
        }
        for ((id, sh) in remShadows.toList()) {
            if (id in live || sh < 0) continue
            val at = System.currentTimeMillis()
            col.document(id).set(mapOf("deleted" to true, "at" to at, "by" to "android"))
            remShadows[id] = -at
            dao.putShadow(ShadowEntity("r:$id", (-at).toString()))
        }
    }

    // ------------------------------------------------------------------ places

    /** [at] of the last write agreed with the cloud (negative = a tombstone) and the content hash it had. */
    private data class PlShadow(val at: Long, val hash: Int) {
        fun encode() = "$at|$hash"
        companion object {
            fun decode(s: String): PlShadow? {
                val (a, h) = s.split("|").takeIf { it.size == 2 } ?: return null
                return PlShadow(a.toLongOrNull() ?: return null, h.toIntOrNull() ?: return null)
            }
        }
    }

    /** What syncs of a place: everything but the arming, which is this device's own geofence state. */
    private fun PlaceEntity.fields(): Map<String, Any?> = mapOf(
        "name" to name, "kind" to kind, "lat" to lat, "lng" to lng, "radius" to radius, "address" to address,
        "position" to position, "createdAt" to createdAt,
    )

    private fun PlaceReminderEntity.fields(): Map<String, Any?> = mapOf(
        "noteId" to noteId, "blockId" to blockId, "placeId" to placeId, "leave" to leave, "always" to always, "days" to days,
        "fromMin" to fromMin, "toMin" to toMin, "fired" to fired, "createdAt" to createdAt, "sticky" to sticky,
    )

    private fun hashOf(f: Map<String, Any?>) = f.entries.sortedBy { it.key }.joinToString("\u0001") { "${it.key}=${it.value}" }.hashCode()

    private suspend fun plShadow(key: String): PlShadow? =
        plShadows[key] ?: dao.shadow(key)?.let { PlShadow.decode(it.json) }?.also { plShadows[key] = it }

    private suspend fun putPlShadow(key: String, s: PlShadow) {
        plShadows[key] = s
        dao.putShadow(ShadowEntity(key, s.encode()))
    }

    /**
     * `users/{uid}/places/{id}` and `users/{uid}/placeReminders/{id}`: whole documents, like time
     * reminders. A copy changed here and not yet pushed wins over the cloud (it goes up next with
     * a newer `at`); otherwise the cloud's newer write is taken. Every device keeps its own fences.
     */
    private suspend fun pullPlaces(s: QuerySnapshot) {
        var any = false
        for (d in s.documents) {
            if (d.metadata.hasPendingWrites()) continue
            any = applyPlace(d.id, d.data ?: continue) || any
        }
        if (any) refence()
    }

    private suspend fun pullPlaceReminders(s: QuerySnapshot) {
        var any = false
        for (d in s.documents) {
            if (d.metadata.hasPendingWrites()) continue
            any = applyPlaceReminder(d.id, d.data ?: continue) || any
        }
        if (any) refence()
    }

    /** A place or place reminder that came in a push message (the app may have been asleep). */
    suspend fun applyPushedPlace(col: String, id: String, f: Map<String, Any?>) = mutex.withLock {
        val changed = if (col == PLACES) applyPlace(id, f) else applyPlaceReminder(id, f)
        if (changed) refence()
    }

    private fun refence() {
        val app = ctx.applicationContext as? com.ohav.noted.NotedApp ?: return
        app.scope.launch { runCatching { app.places.sync() } }
    }

    /** Returns whether anything changed here. */
    private suspend fun applyPlace(id: String, f: Map<String, Any?>): Boolean {
        val key = "p:$id"
        val at = (f["at"] as? Number)?.toLong() ?: return false
        val deleted = f["deleted"] == true
        val known = plShadow(key)
        if (known != null && kotlin.math.abs(known.at) == at && (known.at < 0) == deleted) return false
        if (known != null && kotlin.math.abs(known.at) > at) return false
        val pd = db.places()
        val local = pd.place(id)
        // changed here since we last agreed: ours goes up next and wins
        if (local != null && known != null && known.at > 0 && hashOf(local.fields()) != known.hash) return false
        if (deleted) {
            if (local != null) pd.deletePlace(id)
            putPlShadow(key, PlShadow(-at, 0))
            return local != null
        }
        val p = PlaceEntity(
            id = id, name = f["name"] as? String ?: "", kind = f["kind"] as? String ?: "other",
            lat = (f["lat"] as? Number)?.toDouble() ?: return false, lng = (f["lng"] as? Number)?.toDouble() ?: return false,
            radius = (f["radius"] as? Number)?.toInt() ?: 150, address = f["address"] as? String ?: "",
            position = (f["position"] as? Number)?.toDouble() ?: 0.0, createdAt = (f["createdAt"] as? Number)?.toLong() ?: at,
        )
        // the arming is this device's: kept unless the point or radius moved
        val moved = local == null || local.lat != p.lat || local.lng != p.lng || local.radius != p.radius
        pd.upsertPlace(if (moved) p else p.copy(armIn = local!!.armIn, armOut = local.armOut))
        putPlShadow(key, PlShadow(at, hashOf(p.fields())))
        return true
    }

    private suspend fun applyPlaceReminder(id: String, f: Map<String, Any?>): Boolean {
        val key = "q:$id"
        val at = (f["at"] as? Number)?.toLong() ?: return false
        val deleted = f["deleted"] == true
        val known = plShadow(key)
        if (known != null && kotlin.math.abs(known.at) == at && (known.at < 0) == deleted) return false
        if (known != null && kotlin.math.abs(known.at) > at) return false
        val pd = db.places()
        val local = pd.reminder(id)
        if (local != null && known != null && known.at > 0 && hashOf(local.fields()) != known.hash) return false
        if (deleted) {
            if (local != null) {
                pd.deleteReminder(id)
                androidx.core.app.NotificationManagerCompat.from(ctx).cancel(com.ohav.noted.places.PlaceNotifier.idOf(id))
                com.ohav.noted.reminders.Shade.forget(ctx, com.ohav.noted.reminders.Shade.place(id))
            }
            putPlShadow(key, PlShadow(-at, 0))
            return local != null
        }
        val r = PlaceReminderEntity(
            id = id, noteId = f["noteId"] as? String ?: return false, blockId = f["blockId"] as? String ?: "",
            placeId = f["placeId"] as? String ?: return false, leave = f["leave"] == true, always = f["always"] == true,
            days = f["days"] as? String ?: "", fromMin = (f["fromMin"] as? Number)?.toInt(), toMin = (f["toMin"] as? Number)?.toInt(),
            fired = f["fired"] == true, createdAt = (f["createdAt"] as? Number)?.toLong() ?: at, sticky = f["sticky"] == true,
        )
        pd.upsertReminder(r)
        putPlShadow(key, PlShadow(at, hashOf(r.fields())))
        return true
    }

    private suspend fun pushPlaces(fs: FirebaseFirestore, uid: String, places: List<PlaceEntity>, rems: List<PlaceReminderEntity>) {
        val user = fs.collection("users").document(uid)
        suspend fun up(col: String, prefix: String, rows: List<Pair<String, Map<String, Any?>>>) {
            val ref = user.collection(col)
            val live = HashSet<String>()
            for ((id, f) in rows) {
                val key = "$prefix:$id"
                live += key
                val h = hashOf(f)
                val known = plShadow(key)
                if (known != null && known.at > 0 && known.hash == h) continue
                val at = maxOf(System.currentTimeMillis(), kotlin.math.abs(known?.at ?: 0L) + 1)
                ref.document(id).set(f + mapOf("at" to at, "deleted" to false, "by" to "android"))
                putPlShadow(key, PlShadow(at, h))
            }
            for ((key, sh) in plShadows.toList()) {
                if (!key.startsWith("$prefix:") || key in live || sh.at < 0) continue
                val at = maxOf(System.currentTimeMillis(), sh.at + 1)
                ref.document(key.drop(2)).set(mapOf("deleted" to true, "at" to at, "by" to "android"))
                putPlShadow(key, PlShadow(-at, 0))
            }
        }
        up(PLACES, "p", places.map { it.id to it.fields() })
        up(PLACE_REMINDERS, "q", rems.map { it.id to it.fields() })
    }

    // ------------------------------------------------------------------ templates

    /**
     * `users/{uid}/templates/{id}`: whole documents, newest `at` wins. A template is small and
     * only mine, so a per-field merge would buy nothing; a deletion is a tombstone (`deleted`).
     */
    private suspend fun pullTemplates(s: QuerySnapshot) {
        val tpl = db.templates()
        for (d in s.documents) {
            if (d.metadata.hasPendingWrites()) continue
            val at = d.getLong("at") ?: continue
            tplCloud[d.id] = at
            val local = tpl.get(d.id)
            if (local != null && local.updatedAt >= at) continue
            tpl.upsert(
                TemplateEntity(
                    id = d.id, name = d.getString("name").orEmpty(), title = d.getString("title").orEmpty(),
                    blocks = d.getString("blocks") ?: "[]", color = d.getString("color") ?: "Sand",
                    categoryId = d.getString("cat").orEmpty(), position = d.getDouble("pos") ?: 0.0,
                    updatedAt = at, deleted = d.getBoolean("deleted") == true,
                )
            )
        }
        if (!s.metadata.isFromCache) tplSeen = true
    }

    private fun pushTemplates(fs: FirebaseFirestore, uid: String, rows: List<TemplateEntity>) {
        if (!tplSeen) return
        val col = fs.collection("users").document(uid).collection("templates")
        for (t in rows) {
            if (t.updatedAt <= (tplCloud[t.id] ?: -1L)) continue
            if (t.deleted && t.id !in tplCloud) continue // never reached the cloud: nothing to bury
            col.document(t.id).set(
                if (t.deleted) mapOf("deleted" to true, "at" to t.updatedAt)
                else mapOf(
                    "name" to t.name, "title" to t.title, "blocks" to t.blocks, "color" to t.color,
                    "cat" to t.categoryId, "pos" to t.position, "at" to t.updatedAt, "deleted" to false,
                )
            )
            tplCloud[t.id] = t.updatedAt
        }
    }

    // ------------------------------------------------------------------ shared folders

    private suspend fun pullFolders(s: QuerySnapshot, uid: String) {
        for (ch in s.documentChanges) {
            val d = ch.document
            if (d.metadata.hasPendingWrites()) continue
            if (ch.type == com.google.firebase.firestore.DocumentChange.Type.REMOVED) {
                folders.remove(d.id)
                db.withTransaction { folderGone(d.id, uid) }
                continue
            }
            val f = FolderShadow.of(d) ?: continue
            folders[d.id] = f
            db.withTransaction {
                val local = notes.categories().firstOrNull { it.id == d.id }
                val share = f.share(uid).encode()
                when {
                    f.owner == uid -> if (local != null && local.share != share) notes.setCategoryShare(d.id, share)
                    // joined just now: first in the category row, so it's easy to find
                    local == null -> notes.upsertCategory(CategoryEntity(d.id, f.name, (notes.categories().minOfOrNull { it.position } ?: 0) - 1, f.icon, "", share))
                    local.name != f.name || local.icon != f.icon || local.share != share || local.parent.isNotEmpty() ->
                        notes.upsertCategory(local.copy(name = f.name, icon = f.icon, share = share, parent = ""))
                }
            }
        }
    }

    /** I'm off folder [id] (left, removed, or it stopped being shared). */
    private suspend fun folderGone(id: String, uid: String) {
        val all = notes.categories()
        val local = all.firstOrNull { it.id == id } ?: return
        if (!local.foreign) {
            if (local.share.isNotEmpty()) notes.setCategoryShare(id, "")
            return
        }
        // someone else's folder: what I wrote in it stays with me, in my own first category
        val kids = all.filter { it.parent == id }.map { it.id }
        if (kids.isNotEmpty()) notes.setCategoryParent(kids, "")
        val ids = notes.liveNoteIdsIn(id)
        val home = all.filter { it.id != id && !it.foreign && it.parent.isEmpty() }.minByOrNull { it.position }
            ?: CategoryEntity(java.util.UUID.randomUUID().toString(), "כללי", (all.maxOfOrNull { it.position } ?: 0) + 1).also { notes.upsertCategory(it) }
        if (ids.isNotEmpty()) notes.setCategory(ids, home.id, System.currentTimeMillis())
        notes.deleteCategory(id)
    }

    /** My shared categories → their `folders/` documents (name, icon; gone when no longer shared). */
    private suspend fun pushFolders(fs: FirebaseFirestore, u: Account.User, cats: List<CategoryEntity>) {
        if (!foldersSeen) return
        val uid = u.uid
        val col = fs.collection("folders")
        val byId = cats.associateBy { it.id }
        val now = System.currentTimeMillis()
        for ((id, f) in folders.toList()) {
            if (f.owner != uid) continue
            val k = byId[id]
            when {
                // the category is gone (deleted here or on another device of mine): so is the
                // folder, once an undo is no longer possible
                k == null -> {
                    val since = doomed.getOrPut(id) { now }
                    if (now - since >= FOLDER_GRACE) { col.document(id).delete(); folders.remove(id); doomed.remove(id) }
                    else scope.launch { delay(FOLDER_GRACE - (now - since) + 50); repush.update { it + 1 } }
                }
                else -> {
                    doomed.remove(id)
                    if (k.share.isEmpty()) notes.setCategoryShare(id, f.share(uid).encode())
                }
            }
        }
        val mine = cats.filter { it.share.isNotEmpty() && it.shareInfo.owner == uid && it.id in byId }
        for (k in mine) {
            val f = folders[k.id]
            if (f == null) {
                // shared, but the cloud doesn't have it (restored after an undo): start it again
                col.document(k.id).set(newFolder(u, k))
                folders[k.id] = FolderShadow(uid, k.name, k.icon, mapOf(uid to Share.ROLE_OWNER), mapOf(uid to Person(u.name, u.photoUrl)))
            } else if (f.name != k.name || f.icon != k.icon) {
                col.document(k.id).update(mapOf("name" to k.name, "icon" to k.icon))
                folders[k.id] = f.copy(name = k.name, icon = k.icon)
            }
        }
    }

    private fun newFolder(u: Account.User, k: CategoryEntity): Map<String, Any?> = mapOf(
        "owner" to u.uid, "memberIds" to listOf(u.uid), "members" to mapOf(u.uid to Share.ROLE_OWNER),
        "people" to mapOf(u.uid to person(u)), "name" to k.name, "icon" to k.icon, "at" to FieldValue.serverTimestamp(),
    )

    /** Starts sharing my category [k]: creates its folder (online), then marks the category. */
    suspend fun shareFolder(u: Account.User, k: CategoryEntity) {
        FirebaseFirestore.getInstance().collection("folders").document(k.id).set(newFolder(u, k)).await()
        mutex.withLock {
            val f = FolderShadow(u.uid, k.name, k.icon, mapOf(u.uid to Share.ROLE_OWNER), mapOf(u.uid to Person(u.name, u.photoUrl)))
            folders[k.id] = f
            notes.setCategoryShare(k.id, f.share(u.uid).encode())
        }
    }

    /** Stops sharing my folder [id]: the category stays mine, the others lose it. */
    suspend fun unshareFolder(id: String) {
        FirebaseFirestore.getInstance().collection("folders").document(id).delete().await()
        mutex.withLock {
            folders.remove(id)
            notes.setCategoryShare(id, "")
        }
    }

    /** After leaving someone's folder: take it off this device now, without waiting for the listener. */
    suspend fun leftFolder(id: String, uid: String) = mutex.withLock {
        folders.remove(id)
        db.withTransaction { folderGone(id, uid) }
    }

    // ------------------------------------------------------------------ media

    private suspend fun media(uid: String) {
        val bucket = runCatching { com.google.firebase.FirebaseApp.getInstance().options.storageBucket }.getOrNull()
        val storage = if (bucket.isNullOrEmpty()) null else runCatching { FirebaseStorage.getInstance() }.getOrNull()
        if (storage == null) { _status.value = _status.value.copy(mediaOff = true); return }
        val known = dao.media().associateBy { it.name }
        // file → folders it may sit in (whoever added it uploads to their own folder), over every note with media
        val wanted = LinkedHashMap<String, List<String>>()
        for (n in notes.all()) {
            if (!n.blocks.contains("\"x\"")) continue
            val sh = noteShadows[n.id]
            Blocks.parse(n.blocks).forEach { b ->
                if (b.type !in MEDIA) return@forEach
                val folders = listOfNotNull(sh?.by?.get(b.id), sh?.owner, uid).plus(sh?.members?.keys.orEmpty()).distinct()
                MediaExtra.files(b).forEach { wanted.putIfAbsent(it, folders) }
            }
        }
        val todo = wanted.filter { (name, _) -> known[name]?.uploaded != true || !Media.file(ctx, name).exists() }
        _status.value = _status.value.copy(mediaPending = todo.size)
        var left = todo.size
        for ((name, folders) in todo) {
            val f = Media.file(ctx, name)
            val ok = runCatching {
                if (f.exists()) {
                    storage.reference.child("users/$uid/media/$name").putFile(android.net.Uri.fromFile(f)).await()
                    dao.putMedia(MediaSyncEntity(name, uid, true))
                } else {
                    val tmp = File(f.parentFile, "$name.part")
                    var from: String? = null
                    var last: Exception? = null
                    for (o in folders) {
                        try { storage.reference.child("users/$o/media/$name").getFile(tmp).await(); from = o; break }
                        catch (e: StorageException) { last = e; if (e.errorCode != StorageException.ERROR_OBJECT_NOT_FOUND) break }
                    }
                    if (from == null) { tmp.delete(); throw last ?: IllegalStateException(name) }
                    if (!tmp.renameTo(f)) tmp.delete()
                    dao.putMedia(MediaSyncEntity(name, from, true))
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
        /** How long a shared folder outlives its deleted category (longer than the undo toast). */
        private const val FOLDER_GRACE = 15_000L
        const val PLACES = "places"
        const val PLACE_REMINDERS = "placeReminders"

        /** Fields every member keeps for themselves (the owner's at the top level, others' under `u.<uid>`). */
        val PERSONAL = setOf("categoryId", "pinned", "favorite", "archived", "trashedAt", "position")
        val SEED_TITLES = setOf("קניות לשבת", "שקשוקה של סבתא", "רעיונות לטיול בגליל", "פגישת צוות", "ספרים לקרוא")
    }
}

/** Someone's name and photo as the note document carries them. */
data class Person(val name: String, val photo: String?)

/**
 * A note as the cloud has it (and as this device last agreed with it), seen by one account:
 * the personal fields (category, pin, favorite, archive, trash, position) are that account's.
 */
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
    /** uid → role. */
    val members: Map<String, String> = emptyMap(),
    val people: Map<String, Person> = emptyMap(),
    /** block id → uid that last changed it. */
    val by: Map<String, String> = emptyMap(),
    /** The owner trashed it and I'm not the owner: it isn't mine to see now. */
    val gone: Boolean = false,
    /** A non-owner already has their own personal fields on it. */
    val personal: Boolean = false,
    /** The shared folder it's in ("" = none). */
    val folder: String = "",
    /** Members the folder brought in. */
    val via: List<String> = emptyList(),
) {
    val blocksJson: String by lazy { Blocks.serialize(blocks) }

    fun role(uid: String): String = members[uid] ?: if (uid == owner) Share.ROLE_OWNER else Share.ROLE_VIEWER

    fun share(uid: String): Share {
        if (members.size <= 1 && owner == uid) return Share.None.copy(owner = uid, role = Share.ROLE_OWNER, members = listOf(member(uid)), by = by, folder = folder)
        val list = members.keys.sortedWith(compareBy({ members[it] != Share.ROLE_OWNER }, { it != uid }, { people[it]?.name.orEmpty() })).map(::member)
        return Share(owner, role(uid), list, by, folder, via)
    }

    private fun member(uid: String) = Member(uid, people[uid]?.name.orEmpty(), people[uid]?.photo, members[uid] ?: Share.ROLE_OWNER)

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

    fun encode(): String {
        val ppl = JSONObject()
        people.forEach { (k, v) -> ppl.put(k, JSONObject().put("n", v.name).put("p", v.photo ?: "")) }
        return JSONObject()
            .put("ow", owner).put("t", title).put("b", blocksJson).put("o", JSONObject(orders as Map<*, *>))
            .put("c", color).put("k", categoryId).put("p", pinned).put("f", favorite).put("a", archived)
            .put("tr", trashedAt ?: JSONObject.NULL).put("pos", position).put("cr", createdAt).put("u", updatedAt)
            .put("m", JSONObject(members as Map<*, *>)).put("pp", ppl).put("by", JSONObject(by as Map<*, *>))
            .put("g", gone).put("pe", personal).put("fo", folder).put("v", org.json.JSONArray(via))
            .toString()
    }

    companion object {
        private fun JSONObject?.strings(): Map<String, String> = this?.keys()?.asSequence()?.associateWith { getString(it) }.orEmpty()

        fun decode(s: String): NoteShadow? = runCatching {
            val o = JSONObject(s)
            val ord = o.getJSONObject("o")
            val pp = o.optJSONObject("pp")
            NoteShadow(
                o.getString("ow"), o.getString("t"), Blocks.parse(o.getString("b")),
                ord.keys().asSequence().associateWith { ord.getDouble(it) },
                o.getString("c"), o.getString("k"), o.getBoolean("p"), o.getBoolean("f"), o.getBoolean("a"),
                if (o.isNull("tr")) null else o.getLong("tr"), o.getDouble("pos"), o.getLong("cr"), o.getLong("u"),
                members = o.optJSONObject("m").strings(),
                people = pp?.keys()?.asSequence()?.associateWith { k -> pp.getJSONObject(k).let { Person(it.optString("n"), it.optString("p").ifEmpty { null }) } }.orEmpty(),
                by = o.optJSONObject("by").strings(),
                gone = o.optBoolean("g"),
                personal = o.optBoolean("pe"),
                folder = o.optString("fo"),
                via = o.optJSONArray("v")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
            )
        }.getOrNull()

        /** What this device just pushed: [prev]'s membership carried over. */
        fun of(n: NoteEntity, blocks: List<Block>, orders: Map<String, Double>, owner: String, prev: NoteShadow?) = NoteShadow(
            owner, n.title, blocks, orders, n.color, n.categoryId, n.pinned, n.favorite, n.archived, n.trashedAt,
            n.position, n.createdAt, n.updatedAt,
            members = prev?.members ?: mapOf(owner to Share.ROLE_OWNER),
            people = prev?.people.orEmpty(),
            by = prev?.by.orEmpty(),
            personal = prev?.personal ?: false,
            folder = prev?.folder.orEmpty(),
            via = prev?.via.orEmpty(),
        )

        /** Reads a `notes/{id}` document as [me] sees it; null if it isn't one of ours yet (half-written). */
        fun of(d: DocumentSnapshot, me: String): NoteShadow? {
            val owner = d.getString("owner") ?: return null
            @Suppress("UNCHECKED_CAST")
            val raw = (d.get("blocks") as? Map<String, Any?>).orEmpty()
            val by = HashMap<String, String>()
            val list = raw.mapNotNull { (id, v) ->
                val m = v as? Map<*, *> ?: return@mapNotNull null
                (m["by"] as? String)?.let { by[id] = it }
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
            val mine = owner == me
            @Suppress("UNCHECKED_CAST")
            val my = if (mine) null else ((d.get("u") as? Map<String, Any?>)?.get(me) as? Map<String, Any?>)
            fun <T> pick(key: String, top: () -> T?, def: T): T = if (mine) top() ?: def else (my?.get(key) as? T) ?: def
            val members = (d.get("members") as? Map<*, *>)?.entries?.mapNotNull { (k, v) -> (k as? String)?.let { it to (v as? String ?: Share.ROLE_VIEWER) } }?.toMap().orEmpty()
            val people = (d.get("people") as? Map<*, *>)?.entries?.mapNotNull { (k, v) ->
                val m = v as? Map<*, *> ?: return@mapNotNull null
                (k as? String)?.let { it to Person(m["n"] as? String ?: "", (m["p"] as? String)?.ifEmpty { null }) }
            }?.toMap().orEmpty()
            val topTrash = d.getLong("trashedAt")
            val folder = d.getString("folder").orEmpty()
            return NoteShadow(
                owner = owner,
                title = d.getString("title").orEmpty(),
                blocks = list.map { it.third },
                orders = list.associate { it.first to it.second },
                color = d.getString("color") ?: "Butter",
                // someone else's note in a shared folder sits in that folder until I place it elsewhere
                categoryId = pick("categoryId", { d.getString("categoryId") }, folder),
                pinned = pick("pinned", { d.getBoolean("pinned") }, false),
                favorite = pick("favorite", { d.getBoolean("favorite") }, false),
                archived = pick("archived", { d.getBoolean("archived") }, false),
                trashedAt = if (mine) topTrash else (my?.get("trashedAt") as? Number)?.toLong(),
                position = if (mine) d.getDouble("position") ?: 0.0 else (my?.get("position") as? Number)?.toDouble() ?: 0.0,
                createdAt = d.getLong("createdAt") ?: 0L,
                updatedAt = d.getLong("updatedAt") ?: 0L,
                members = members.ifEmpty { mapOf(owner to Share.ROLE_OWNER) },
                people = people,
                by = by,
                // the owner trashed it, or I deleted it for good from a shared folder (it can't leave the folder for me alone)
                gone = !mine && (topTrash != null || my?.get("purged") == true),
                personal = my != null,
                folder = folder,
                via = (d.get("via") as? List<*>).orEmpty().filterIsInstance<String>(),
            ).also { it.id = d.id }
        }
    }

    /** Document id, set when read from the cloud (not part of equality). */
    var id: String = ""
}

data class CatShadow(val name: String, val icon: String, val position: Int, val deleted: Boolean, val parent: String = "") {
    fun encode(): String = JSONObject().put("n", name).put("i", icon).put("p", position).put("d", deleted).put("r", parent).toString()

    companion object {
        fun decode(s: String): CatShadow? = runCatching {
            val o = JSONObject(s)
            CatShadow(o.getString("n"), o.getString("i"), o.getInt("p"), o.getBoolean("d"), o.optString("r"))
        }.getOrNull()

        fun of(d: DocumentSnapshot) = CatShadow(
            d.getString("name").orEmpty(), d.getString("icon") ?: "folder", (d.getLong("position") ?: 0L).toInt(), d.getBoolean("deleted") ?: false,
            d.getString("parent").orEmpty(),
        )
    }
}

/** A `folders/{id}` document: a shared category, its members and their names. */
data class FolderShadow(
    val owner: String,
    val name: String,
    val icon: String,
    val members: Map<String, String>,
    val people: Map<String, Person>,
) {
    fun share(uid: String): Share {
        val list = members.keys.sortedWith(compareBy({ members[it] != Share.ROLE_OWNER }, { it != uid }, { people[it]?.name.orEmpty() }))
            .map { Member(it, people[it]?.name.orEmpty(), people[it]?.photo, members[it] ?: Share.ROLE_VIEWER) }
        return Share(owner, members[uid] ?: Share.ROLE_VIEWER, list)
    }

    companion object {
        fun of(d: DocumentSnapshot): FolderShadow? {
            val owner = d.getString("owner") ?: return null
            val members = (d.get("members") as? Map<*, *>)?.entries?.mapNotNull { (k, v) -> (k as? String)?.let { it to (v as? String ?: Share.ROLE_VIEWER) } }?.toMap().orEmpty()
            val people = (d.get("people") as? Map<*, *>)?.entries?.mapNotNull { (k, v) ->
                val m = v as? Map<*, *> ?: return@mapNotNull null
                (k as? String)?.let { it to Person(m["n"] as? String ?: "", (m["p"] as? String)?.ifEmpty { null }) }
            }?.toMap().orEmpty()
            return FolderShadow(owner, d.getString("name").orEmpty(), d.getString("icon") ?: "folder", members.ifEmpty { mapOf(owner to Share.ROLE_OWNER) }, people)
        }
    }
}
