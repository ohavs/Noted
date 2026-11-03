package com.ohav.noted.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.ohav.noted.BuildConfig
import com.ohav.noted.NotedApp
import com.ohav.noted.data.Blocks
import com.ohav.noted.data.CategoryEntity
import com.ohav.noted.data.Media
import com.ohav.noted.data.MediaExtra
import com.ohav.noted.data.NoteEntity
import com.ohav.noted.data.PlaceEntity
import com.ohav.noted.data.PlaceReminderEntity
import com.ohav.noted.data.Privacy
import com.ohav.noted.data.ReminderEntity
import com.ohav.noted.data.Share
import com.ohav.noted.data.TemplateEntity
import com.ohav.noted.sync.Sync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Everything in one `.noted` file (a zip): `backup.json` with notes, categories, reminders,
 * places and privacy marks, and `media/<name>` for every file a note points at.
 *
 * Restoring merges, never wipes: what isn't here is added, a note here that is newer than
 * the one on the phone replaces its content, everything else stays as it is. Someone else's
 * shared note / folder comes back as my own copy (a new id, not shared), so it never writes
 * into their cloud document.
 */
class Backup(private val app: NotedApp) {
    private val db get() = app.db
    private val prefs = app.getSharedPreferences("backup", Context.MODE_PRIVATE)

    /** When the last backup file was written (epoch ms, 0 = never). */
    var lastAt: Long
        get() = prefs.getLong("last", 0L)
        private set(v) { prefs.edit().putLong("last", v).apply() }

    class Summary(val notes: Int, val categories: Int, val files: Int, val at: Long, val version: String = "")

    class Restored(val added: Int, val updated: Int, val kept: Int, val files: Int)

    class BadFile : Exception()

    fun fileName(now: LocalDateTime = LocalDateTime.now()) = "noted-%04d-%02d-%02d.noted".format(now.year, now.monthValue, now.dayOfMonth)

    // ------------------------------------------------------------------ write

    suspend fun write(out: OutputStream, onProgress: (Float) -> Unit = {}): Summary = withContext(Dispatchers.IO) {
        val me = app.account.user.value?.uid
        val notes = db.notes().all()
        val cats = db.notes().categories()
        val reminders = db.reminders().all()
        val places = db.places().places()
        val placeReminders = db.places().allReminders()
        val files = notes.flatMap { n -> Blocks.parse(n.blocks).flatMap { MediaExtra.files(it) } }.distinct()
            .filter { Media.file(app, it).exists() }
        val at = System.currentTimeMillis()
        val root = JSONObject()
            .put("v", 1).put("app", BuildConfig.VERSION_NAME).put("at", at)
            .put("notes", JSONArray().apply { notes.forEach { put(note(it, me)) } })
            .put("categories", JSONArray().apply { cats.forEach { put(category(it, me)) } })
            .put("reminders", JSONArray().apply { reminders.forEach { put(reminder(it)) } })
            .put("places", JSONArray().apply { places.forEach { put(place(it)) } })
            .put("placeReminders", JSONArray().apply { placeReminders.forEach { put(placeReminder(it)) } })
            .put("templates", JSONArray().apply { db.templates().all().filter { !it.deleted }.forEach { put(template(it)) } })
            .put("privacy", JSONObject().apply { app.privacy.flags.value.forEach { (id, f) -> if (f != 0) put(id, f) } })
            .put("files", files.size)
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(root.toString().toByteArray())
            zip.closeEntry()
            files.forEachIndexed { i, name ->
                zip.putNextEntry(ZipEntry("media/$name"))
                Media.file(app, name).inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                onProgress((i + 1f) / files.size)
            }
        }
        lastAt = at
        Summary(notes.count { it.trashedAt == null }, cats.size, files.size, at, BuildConfig.VERSION_NAME)
    }

    // ------------------------------------------------------------------ read

    /** What a backup file holds, without restoring anything. Throws [BadFile] for anything else. */
    suspend fun peek(uri: Uri): Summary = withContext(Dispatchers.IO) {
        val root = open(uri) { zip -> readManifest(zip) } ?: throw BadFile()
        summaryOf(root)
    }

    private fun summaryOf(root: JSONObject): Summary {
        val notes = root.optJSONArray("notes") ?: JSONArray()
        var live = 0
        for (i in 0 until notes.length()) if (notes.getJSONObject(i).isNull("trashedAt")) live++
        return Summary(live, root.optJSONArray("categories")?.length() ?: 0, root.optInt("files"), root.optLong("at"), root.optString("app"))
    }

    private fun <T> open(uri: Uri, f: (ZipInputStream) -> T): T? =
        app.contentResolver.openInputStream(uri)?.use { s -> ZipInputStream(s.buffered()).use(f) }

    private fun readManifest(zip: ZipInputStream): JSONObject? {
        while (true) {
            val e = zip.nextEntry ?: return null
            if (e.name == MANIFEST) {
                return runCatching { JSONObject(zip.readBytes().decodeToString()) }.getOrNull()?.takeIf { it.has("notes") }
            }
        }
    }

    // ------------------------------------------------------------------ restore

    suspend fun restore(uri: Uri, onProgress: (Float) -> Unit = {}): Restored = withContext(Dispatchers.IO) {
        // media first, into a side folder: only files that aren't here already are kept
        val staging = File(app.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        var root: JSONObject? = null
        try {
            open(uri) { zip ->
                var n = 0
                while (true) {
                    val e = zip.nextEntry ?: break
                    when {
                        e.name == MANIFEST -> root = runCatching { JSONObject(zip.readBytes().decodeToString()) }.getOrNull()
                        e.name.startsWith("media/") && !e.isDirectory -> {
                            val name = e.name.removePrefix("media/")
                            if (name.contains('/') || name.contains("..") || name.isEmpty()) continue
                            if (!Media.file(app, name).exists()) File(staging, name).outputStream().use { zip.copyTo(it) }
                            n++
                            val total = root?.optInt("files") ?: 0
                            if (total > 0) onProgress((n.toFloat() / total).coerceAtMost(1f) * .8f)
                        }
                    }
                }
            }
            val r = root?.takeIf { it.has("notes") } ?: throw BadFile()
            val files = staging.listFiles().orEmpty()
            files.forEach { f -> f.copyTo(Media.file(app, f.name), overwrite = false); f.delete() }
            val res = merge(r, files.size)
            onProgress(1f)
            res
        } finally {
            staging.deleteRecursively()
        }
    }

    private suspend fun merge(root: JSONObject, files: Int): Restored {
        val dao = db.notes()
        val now = System.currentTimeMillis()
        var added = 0
        var updated = 0
        var kept = 0
        val noteMap = HashMap<String, String>()
        val privacy = root.optJSONObject("privacy") ?: JSONObject()
        val restoredIds = ArrayList<String>()

        db.withTransaction {
            // categories: missing ones are added after mine; someone else's folder becomes my own category
            val local = dao.categories().associateBy { it.id }
            val catMap = HashMap<String, String>()
            var pos = (dao.maxCategoryPosition() ?: -1) + 1
            val cats = root.optJSONArray("categories") ?: JSONArray()
            val incoming = List(cats.length()) { cats.getJSONObject(it) }.sortedBy { it.optInt("position") }
            val fresh = ArrayList<CategoryEntity>()
            incoming.forEach { o ->
                val id = o.optString("id")
                if (local.containsKey(id)) { catMap[id] = id; return@forEach }
                val nid = if (o.optBoolean("foreign")) UUID.randomUUID().toString() else id
                catMap[id] = nid
                fresh += CategoryEntity(nid, o.optString("name"), pos++, o.optString("icon", "folder"), o.optString("parent"))
            }
            val known = local.keys + fresh.map { it.id }
            fresh.forEach { k ->
                val p = catMap[k.parent] ?: k.parent
                dao.upsertCategory(k.copy(parent = if (p.isNotEmpty() && p in known && p != k.id) p else ""))
            }
            val fallback = dao.categories().minByOrNull { it.position }?.id.orEmpty()
            fun cat(id: String) = (catMap[id] ?: id).takeIf { it in known } ?: fallback

            // notes
            val mine = dao.all().associateBy { it.id }
            val arr = root.optJSONArray("notes") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optString("id")
                if (id.isEmpty()) continue
                val b = noteOf(o)
                val l = mine[id]
                if (l == null) {
                    val nid = if (o.optBoolean("foreign")) UUID.randomUUID().toString() else id
                    if (nid in mine) { kept++; continue }
                    noteMap[id] = nid
                    dao.upsert(b.copy(id = nid, categoryId = cat(b.categoryId)))
                    restoredIds += nid
                    added++
                } else if (b.updatedAt > l.updatedAt && !Share.parse(l.share).readOnly) {
                    noteMap[id] = id
                    dao.upsert(
                        l.copy(
                            title = b.title, blocks = b.blocks, color = b.color, categoryId = cat(b.categoryId),
                            pinned = b.pinned, favorite = b.favorite, archived = b.archived, trashedAt = b.trashedAt,
                            updatedAt = b.updatedAt,
                        ),
                    )
                    restoredIds += id
                    updated++
                } else {
                    noteMap[id] = id
                    kept++
                }
            }
        }

        // reminders: only what the phone doesn't have; ones whose time went by while away move on quietly
        val present = db.notes().all().map { it.id }.toHashSet()
        root.optJSONArray("reminders")?.let { a ->
            for (i in 0 until a.length()) {
                val r = reminderOf(a.getJSONObject(i)) ?: continue
                val nid = noteMap[r.noteId] ?: continue
                if (nid !in present) continue
                val id = ReminderEntity.idFor(nid, r.blockId)
                if (db.reminders().get(id) != null) continue
                app.reminders.restore(settle(r.copy(id = id, noteId = nid), now))
            }
        }
        // places: missing ones by id, then place reminders for notes that are here
        val placesHere = db.places().places().associateBy { it.id }.toMutableMap()
        root.optJSONArray("places")?.let { a ->
            for (i in 0 until a.length()) {
                val p = placeOf(a.getJSONObject(i)) ?: continue
                if (p.id in placesHere) continue
                db.places().upsertPlace(p.copy(armIn = false, armOut = false))
                placesHere[p.id] = p
            }
        }
        root.optJSONArray("placeReminders")?.let { a ->
            for (i in 0 until a.length()) {
                val r = placeReminderOf(a.getJSONObject(i)) ?: continue
                val nid = noteMap[r.noteId] ?: continue
                if (nid !in present || r.placeId !in placesHere) continue
                val id = ReminderEntity.idFor(nid, r.blockId)
                if (db.places().reminder(id) != null) continue
                db.places().upsertReminder(r.copy(id = id, noteId = nid))
            }
        }
        // templates: missing ones are added, a newer copy replaces mine (a deleted one comes back)
        root.optJSONArray("templates")?.let { a ->
            for (i in 0 until a.length()) {
                val t = templateOf(a.getJSONObject(i)) ?: continue
                val l = db.templates().get(t.id)
                if (l == null || l.deleted || t.updatedAt > l.updatedAt) db.templates().upsert(t.copy(updatedAt = maxOf(t.updatedAt, (l?.updatedAt ?: 0L) + 1)))
            }
        }
        // blur marks come back as they were; a lock only where this phone has a code to open it
        restoredIds.forEach { nid ->
            val src = noteMap.entries.firstOrNull { it.value == nid }?.key ?: nid
            val f = privacy.optInt(src, 0)
            if (f == 0 || app.privacy.of(nid) != 0) return@forEach
            app.privacy.set(nid, if (app.privacy.hasCode) f else f and Privacy.LOCK.inv())
        }
        // the untouched welcome notes of a fresh install make way for the real ones
        if (added > 0) {
            db.notes().all().filter { it.id !in restoredIds && it.createdAt == it.updatedAt && it.title in Sync.SEED_TITLES }
                .forEach { app.sync.discard(it.id) }
        }
        return Restored(added, updated, kept, files)
    }

    /** A reminder that was due while it sat in the file: on to its next time, or done — no burst of rings. */
    private fun settle(r: ReminderEntity, now: Long): ReminderEntity {
        var x = r
        if (x.snoozeAt != null && x.snoozeAt!! <= now) x = x.copy(snoozeAt = null)
        if (!x.fired && x.occAt <= now) {
            val nowLocal = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), x.zoneId)
            val next = x.repeat.next(x.anchorTime, maxOf(x.occTime, nowLocal))
            x = if (next != null) x.copy(occ = next.toString()) else x.copy(fired = true)
        }
        return x
    }

    // ------------------------------------------------------------------ json

    private fun foreign(share: String, me: String?): Boolean {
        if (share.isEmpty()) return false
        val s = Share.parse(share)
        return s.owner.isNotEmpty() && s.owner != me
    }

    private fun note(n: NoteEntity, me: String?) = JSONObject()
        .put("id", n.id).put("title", n.title).put("blocks", n.blocks).put("color", n.color).put("categoryId", n.categoryId)
        .put("pinned", n.pinned).put("favorite", n.favorite).put("archived", n.archived)
        .put("trashedAt", n.trashedAt ?: JSONObject.NULL).put("reminderAt", n.reminderAt ?: JSONObject.NULL)
        .put("position", n.position).put("createdAt", n.createdAt).put("updatedAt", n.updatedAt)
        .put("foreign", foreign(n.share, me))

    private fun noteOf(o: JSONObject) = NoteEntity(
        id = o.optString("id"), title = o.optString("title"), blocks = o.optString("blocks", "[]"), color = o.optString("color"),
        categoryId = o.optString("categoryId"), pinned = o.optBoolean("pinned"), favorite = o.optBoolean("favorite"),
        archived = o.optBoolean("archived"), trashedAt = o.optLongOrNull("trashedAt"), reminderAt = o.optLongOrNull("reminderAt"),
        position = o.optDouble("position", 0.0), createdAt = o.optLong("createdAt"), updatedAt = o.optLong("updatedAt"),
    )

    private fun category(k: CategoryEntity, me: String?) = JSONObject()
        .put("id", k.id).put("name", k.name).put("position", k.position).put("icon", k.icon).put("parent", k.parent)
        .put("foreign", foreign(k.share, me))

    private fun reminder(r: ReminderEntity) = JSONObject()
        .put("id", r.id).put("noteId", r.noteId).put("blockId", r.blockId).put("anchor", r.anchor).put("occ", r.occ)
        .put("zone", r.zone).put("rule", r.rule).put("until", r.until ?: JSONObject.NULL).put("fired", r.fired)
        .put("snoozeAt", r.snoozeAt ?: JSONObject.NULL)

    private fun reminderOf(o: JSONObject): ReminderEntity? = runCatching {
        ReminderEntity(
            id = o.getString("id"), noteId = o.getString("noteId"), blockId = o.optString("blockId"),
            anchor = o.getString("anchor").also { LocalDateTime.parse(it) }, occ = o.getString("occ").also { LocalDateTime.parse(it) },
            zone = o.optString("zone"), rule = o.optString("rule"), until = o.optStringOrNull("until"),
            fired = o.optBoolean("fired"), snoozeAt = o.optLongOrNull("snoozeAt"),
        )
    }.getOrNull()

    private fun place(p: PlaceEntity) = JSONObject()
        .put("id", p.id).put("name", p.name).put("kind", p.kind).put("lat", p.lat).put("lng", p.lng).put("radius", p.radius)
        .put("address", p.address).put("position", p.position).put("createdAt", p.createdAt)

    private fun placeOf(o: JSONObject): PlaceEntity? = runCatching {
        PlaceEntity(
            id = o.getString("id"), name = o.optString("name"), kind = o.optString("kind"), lat = o.getDouble("lat"), lng = o.getDouble("lng"),
            radius = o.optInt("radius", 150), address = o.optString("address"), position = o.optDouble("position", 0.0), createdAt = o.optLong("createdAt"),
        )
    }.getOrNull()

    private fun placeReminder(r: PlaceReminderEntity) = JSONObject()
        .put("id", r.id).put("noteId", r.noteId).put("blockId", r.blockId).put("placeId", r.placeId).put("leave", r.leave)
        .put("always", r.always).put("days", r.days).put("fromMin", r.fromMin ?: JSONObject.NULL).put("toMin", r.toMin ?: JSONObject.NULL)
        .put("fired", r.fired).put("createdAt", r.createdAt)

    private fun placeReminderOf(o: JSONObject): PlaceReminderEntity? = runCatching {
        PlaceReminderEntity(
            id = o.getString("id"), noteId = o.getString("noteId"), blockId = o.optString("blockId"), placeId = o.getString("placeId"),
            leave = o.optBoolean("leave"), always = o.optBoolean("always"), days = o.optString("days"),
            fromMin = o.optIntOrNull("fromMin"), toMin = o.optIntOrNull("toMin"), fired = o.optBoolean("fired"), createdAt = o.optLong("createdAt"),
        )
    }.getOrNull()

    private fun template(t: TemplateEntity) = JSONObject()
        .put("id", t.id).put("name", t.name).put("title", t.title).put("blocks", t.blocks).put("color", t.color)
        .put("categoryId", t.categoryId).put("position", t.position).put("updatedAt", t.updatedAt)

    private fun templateOf(o: JSONObject): TemplateEntity? = runCatching {
        TemplateEntity(
            o.getString("id"), o.optString("name"), o.optString("title"), o.optString("blocks", "[]"), o.optString("color", "Sand"),
            o.optString("categoryId"), o.optDouble("position", 0.0), o.optLong("updatedAt"),
        )
    }.getOrNull()

    private fun JSONObject.optLongOrNull(k: String): Long? = if (isNull(k)) null else optLong(k)
    private fun JSONObject.optIntOrNull(k: String): Int? = if (isNull(k)) null else optInt(k)
    private fun JSONObject.optStringOrNull(k: String): String? = if (isNull(k)) null else optString(k)

    companion object {
        const val MANIFEST = "backup.json"
        const val MIME = "application/octet-stream"
    }
}
