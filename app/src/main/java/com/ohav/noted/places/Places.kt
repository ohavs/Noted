package com.ohav.noted.places

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.location.Location
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.common.api.ApiException
import com.ohav.noted.MainActivity
import com.ohav.noted.NotedApp
import com.ohav.noted.R
import com.ohav.noted.data.Block
import com.ohav.noted.data.Blocks
import com.ohav.noted.data.sinkChecked
import com.ohav.noted.data.NotedDatabase
import com.ohav.noted.data.PlaceEntity
import com.ohav.noted.data.PlaceReminderEntity
import com.ohav.noted.data.ReminderEntity
import com.ohav.noted.reminders.Notifier
import com.ohav.noted.reminders.Shade
import com.ohav.noted.reminders.Sounds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Places and place reminders, and the geofences that make them ring. The OS watches the fences
 * (they fire with the app dead); [GeofenceReceiver] decides whether a crossing counts.
 *
 * Per place, only the fences some open reminder needs:
 *  - `in:<id>`  ENTER at the radius — an arrival, but only when armed ([PlaceEntity.armIn]).
 *  - `far:<id>` EXIT at radius + [ARM_MARGIN_M], initial EXIT — being well away arms the arrival,
 *    so "when I get home" set from home doesn't ring on the first GPS jitter at the door.
 *  - `out:<id>` ENTER/EXIT at radius + [LEAVE_MARGIN_M], initial ENTER — being here arms the
 *    departure; leaving the wider ring is the departure.
 * A crossing whose fix is coarser than the radius (and at least 200 m) is not a crossing.
 */
class Places(private val ctx: Context, private val db: NotedDatabase) {
    private val dao = db.places()
    private val notes = db.notes()
    private val lock = Mutex()
    val places: Flow<List<PlaceEntity>> = dao.observePlaces()
    val reminders: Flow<List<PlaceReminderEntity>> = dao.observeReminders()
    val status = PlaceStatus(ctx)

    suspend fun all() = dao.places()
    suspend fun place(id: String) = dao.place(id)

    // ------------------------------------------------------------------ places

    suspend fun create(name: String, kind: String, lat: Double, lng: Double, radius: Int, address: String): PlaceEntity {
        val p = PlaceEntity(
            id = UUID.randomUUID().toString(), name = name.trim(), kind = kind, lat = lat, lng = lng,
            radius = radius, address = address, position = dao.maxPosition() + 1.0, createdAt = System.currentTimeMillis(),
        )
        dao.upsertPlace(p)
        return p
    }

    /** A Bluetooth device as a place: no point, no fence — its links are the crossings. */
    suspend fun createDevice(name: String, kind: String, address: String): PlaceEntity {
        val p = PlaceEntity(
            id = UUID.randomUUID().toString(), name = name.trim(), kind = kind, lat = 0.0, lng = 0.0, radius = 0,
            address = "", position = dao.maxPosition() + 1.0, createdAt = System.currentTimeMillis(), device = address,
        )
        dao.upsertPlace(p)
        return p
    }

    /** The device at [address] linked ([up]) or dropped: its "connect" / "disconnect" reminders ring. */
    suspend fun device(address: String, up: Boolean) = lock.withLock {
        for (p in dao.places()) {
            if (p.device != address) continue
            status.recordCross(p.name, if (up) "התחבר" else "התנתק", ring(p, leave = !up))
        }
    }

    /** Edits; a moved point or radius starts the arming over. */
    suspend fun update(p: PlaceEntity) {
        val old = dao.place(p.id)
        val moved = old != null && (old.lat != p.lat || old.lng != p.lng || old.radius != p.radius)
        dao.upsertPlace(if (moved) p.copy(armIn = false, armOut = false) else p)
    }

    /** Deletes the place and its reminders; returns both for undo. */
    suspend fun delete(id: String): Pair<PlaceEntity, List<PlaceReminderEntity>>? {
        val p = dao.place(id) ?: return null
        val rs = dao.remindersAt(id)
        dao.deletePlaceWithReminders(id)
        rs.forEach { clear(it.id) }
        return p to rs
    }

    suspend fun restore(p: PlaceEntity, rs: List<PlaceReminderEntity>) = dao.restore(p, rs)

    suspend fun move(id: String, position: Double) = dao.setPosition(id, position)

    // ------------------------------------------------------------------ reminders

    suspend fun reminder(noteId: String, blockId: String = ""): PlaceReminderEntity? = dao.reminder(ReminderEntity.idFor(noteId, blockId))
    suspend fun reminderById(id: String): PlaceReminderEntity? = dao.reminder(id)

    suspend fun set(
        noteId: String, blockId: String, placeId: String, leave: Boolean, always: Boolean,
        days: Set<Int>, fromMin: Int?, toMin: Int?, sticky: Boolean = false, dropAfter: Boolean = false,
    ): PlaceReminderEntity {
        val r = PlaceReminderEntity(
            id = ReminderEntity.idFor(noteId, blockId), noteId = noteId, blockId = blockId, placeId = placeId,
            leave = leave, always = always, days = days.sorted().joinToString(","),
            fromMin = fromMin, toMin = if (fromMin == null) null else toMin, createdAt = System.currentTimeMillis(),
            sticky = sticky, dropAfter = dropAfter && !always,
        )
        dao.upsertReminder(r)
        clear(r.id)
        return r
    }

    suspend fun remove(noteId: String, blockId: String = "") {
        val id = ReminderEntity.idFor(noteId, blockId)
        dao.deleteReminder(id)
        clear(id)
    }

    suspend fun restoreReminder(r: PlaceReminderEntity) = dao.upsertReminder(r)

    /** Every place reminder of a note (it's being deleted for good). */
    suspend fun removeAll(noteId: String) = dao.remindersFor(noteId).forEach { remove(it.noteId, it.blockId) }

    /** "Next time" from the notification: a fired one-shot waits for the next crossing. */
    suspend fun again(id: String) {
        val r = dao.reminder(id) ?: return
        dao.upsertReminder(r.copy(fired = false))
        clear(id)
    }

    /** "Done" from the notification: its line gets checked and the reminder goes away. */
    suspend fun done(id: String) {
        val r = dao.reminder(id)
        clear(id)
        // gone already ("delete after it rings"): the id still names the note and the line
        val noteId = r?.noteId ?: id.substringBefore(":")
        val blockId = r?.blockId ?: id.substringAfter(":", "")
        if (blockId.isNotEmpty()) {
            notes.get(noteId)?.let { n ->
                val blocks = Blocks.parse(n.blocks).map { if (it.id == blockId) it.copy(done = true) else it }.sinkChecked(blockId)
                notes.setBlocks(n.id, Blocks.serialize(blocks), System.currentTimeMillis())
            }
        }
        if (r != null) dao.deleteReminder(id)
    }

    fun observeTick(): Flow<String> = dao.observeTick()

    // ------------------------------------------------------------------ fences

    private fun fenceIntent(): PendingIntent = PendingIntent.getBroadcast(
        ctx, 7, Intent(ctx, GeofenceReceiver::class.java).setAction(GeofenceReceiver.CROSS),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    private data class Fence(val id: String, val lat: Double, val lng: Double, val radius: Float, val transitions: Int, val initial: Int)

    private suspend fun fences(): List<Fence> {
        val live = dao.live()
        val byPlace = live.groupBy { it.placeId }
        val out = mutableListOf<Fence>()
        for (p in dao.places()) {
            if (p.isDevice) continue
            val rs = byPlace[p.id] ?: continue
            val r = p.radius.toFloat().coerceAtLeast(MIN_RADIUS_M)
            if (rs.any { !it.leave }) {
                out += Fence("in:${p.id}", p.lat, p.lng, r, Geofence.GEOFENCE_TRANSITION_ENTER, 0)
                out += Fence("far:${p.id}", p.lat, p.lng, r + ARM_MARGIN_M, Geofence.GEOFENCE_TRANSITION_EXIT, GeofencingRequest.INITIAL_TRIGGER_EXIT)
            }
            if (rs.any { it.leave }) {
                out += Fence(
                    "out:${p.id}", p.lat, p.lng, r + LEAVE_MARGIN_M,
                    Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT, GeofencingRequest.INITIAL_TRIGGER_ENTER,
                )
            }
            if (out.size >= MAX_FENCES) break
        }
        return out.take(MAX_FENCES)
    }

    /**
     * Registers exactly the fences open reminders need. Called on every change, on every return
     * to the foreground (where "allow all the time" may just have been granted) and after boot.
     * The same list isn't re-sent within half an hour — frequent registration gets refused.
     */
    @SuppressLint("MissingPermission")
    suspend fun sync(force: Boolean = false) = lock.withLock {
        val list = fences()
        val ready = Geo.ready(ctx)
        val hash = (list.joinToString("|") { "${it.id}@${it.lat},${it.lng},${it.radius}" } + "#" + ready).hashCode()
        val now = System.currentTimeMillis()
        if (!force && hash == status.hash && now - status.syncedAt < 30 * 60_000L) return@withLock
        val client = LocationServices.getGeofencingClient(ctx)
        val pi = fenceIntent()
        if (!ready) {
            status.recordSync(hash, now, if (list.isEmpty()) "idle" else "perm", 0)
            return@withLock
        }
        // remove first and wait: the two are async, and an unchained remove can eat the new fences
        runCatching { client.removeGeofences(pi).await() }
        if (list.isEmpty()) {
            status.recordSync(hash, now, "idle", 0)
            return@withLock
        }
        val result = runCatching {
            list.groupBy { it.initial }.forEach { (initial, group) ->
                val req = GeofencingRequest.Builder().setInitialTrigger(initial).apply {
                    group.forEach { f ->
                        addGeofence(
                            Geofence.Builder()
                                .setRequestId(f.id)
                                .setCircularRegion(f.lat, f.lng, f.radius)
                                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                                .setTransitionTypes(f.transitions)
                                .setNotificationResponsiveness(60_000)
                                .build(),
                        )
                    }
                }.build()
                client.addGeofences(req, pi).await()
            }
        }
        val err = (result.exceptionOrNull() as? ApiException)?.statusCode
        status.recordSync(
            if (result.isSuccess) hash else 0, now,
            when {
                result.isSuccess -> "ok"
                err != null -> "err:$err"
                else -> "err"
            },
            if (result.isSuccess) list.size else 0,
        )
    }

    /** One geofence event. */
    suspend fun crossing(ids: List<String>, transition: Int, loc: Location?) = lock.withLock {
        val acc = loc?.accuracy ?: Float.MAX_VALUE
        for (fid in ids) {
            val kind = fid.substringBefore(":")
            val p = dao.place(fid.substringAfter(":")) ?: continue
            val precise = acc <= maxOf(p.radius.toFloat(), 200f)
            when {
                kind == "far" && transition == Geofence.GEOFENCE_TRANSITION_EXIT -> {
                    // a vague fix far out still means away; only a fix that could be at the door doesn't
                    if (acc <= p.radius + ARM_MARGIN_M) { dao.setArmIn(p.id, true); late.drop(p.id); armLate() }
                    status.recordCross(p.name, "רחוק", if (acc <= p.radius + ARM_MARGIN_M) "נדרך להגעה" else "לא מדויק")
                }
                kind == "in" && transition == Geofence.GEOFENCE_TRANSITION_ENTER -> when {
                    !precise -> status.recordCross(p.name, "הגעה", "לא מדויק (${acc.toInt()} מ׳)")
                    !p.armIn -> status.recordCross(p.name, "הגעה", "לא נדרך — עוד לא התרחקת")
                    else -> {
                        dao.setArmIn(p.id, false)
                        dao.setArmOut(p.id, true)
                        status.recordCross(p.name, "הגעה", ring(p, leave = false))
                        // came before the hours: ring when they open, if still here then
                        val at = nextOpening(p.id)
                        if (at != null) { late.put(p.id, at); armLate() }
                    }
                }
                kind == "out" && transition == Geofence.GEOFENCE_TRANSITION_ENTER -> {
                    if (precise) dao.setArmOut(p.id, true)
                    status.recordCross(p.name, "כאן", if (precise) "נדרך ליציאה" else "לא מדויק")
                }
                kind == "out" && transition == Geofence.GEOFENCE_TRANSITION_EXIT -> when {
                    acc > maxOf(p.radius + LEAVE_MARGIN_M, 200f) -> status.recordCross(p.name, "יציאה", "לא מדויק (${acc.toInt()} מ׳)")
                    !p.armOut -> status.recordCross(p.name, "יציאה", "לא נדרך — לא נראית כאן")
                    else -> {
                        dao.setArmOut(p.id, false)
                        dao.setArmIn(p.id, true)
                        status.recordCross(p.name, "יציאה", ring(p, leave = true))
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ arrived before the hours

    private val late = LatePlaces(ctx)

    /**
     * When the hours of an arrival reminder at [placeId] that just couldn't ring next open —
     * only reminders with set hours, opening within 12 hours (a days-only rule would ring at
     * midnight). Null = nothing waits.
     */
    private suspend fun nextOpening(placeId: String): Long? {
        val now = LocalDateTime.now()
        val horizon = now.plusHours(12)
        var best: LocalDateTime? = null
        for (r in dao.remindersAt(placeId)) {
            if (r.leave || r.fired || r.allows(now)) continue
            val f = r.fromMin ?: continue
            for (plus in 0L..1L) {
                val t = now.toLocalDate().plusDays(plus).atTime(f / 60, f % 60)
                val b = best
                if (t.isAfter(now) && !t.isAfter(horizon) && r.allows(t) && (b == null || t.isBefore(b))) best = t
            }
        }
        return best?.atZone(java.time.ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
    }

    /** One alarm for the earliest waiting place. */
    private fun armLate() {
        val am = ctx.getSystemService(android.app.AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            ctx, 8, Intent(ctx, GeofenceReceiver::class.java).setAction(GeofenceReceiver.LATE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val at = late.all().values.minOrNull()
        if (at == null) { am.cancel(pi); return }
        val t = maxOf(at, System.currentTimeMillis() + 500)
        runCatching {
            if (com.ohav.noted.reminders.Alarm.canExact(ctx)) am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, t, pi)
            else am.setWindow(android.app.AlarmManager.RTC_WAKEUP, t, 60_000L, pi)
        }.onFailure { am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, t, pi) }
    }

    /** The hours opened: places still "here" (arrived, not gone far since) ring now. Also after boot. */
    suspend fun lateDue() = lock.withLock {
        val now = System.currentTimeMillis()
        for ((id, at) in late.all()) {
            if (at > now + 30_000) continue
            late.drop(id)
            val p = dao.place(id) ?: continue
            if (p.armIn) continue // left (or the arrival never counted) since
            status.recordCross(p.name, "פתיחת השעות", ring(p, leave = false))
        }
        armLate()
    }

    /** Rings what this arrival / departure is for; returns what happened, for the status line. */
    private suspend fun ring(p: PlaceEntity, leave: Boolean): String {
        val now = LocalDateTime.now()
        var rang = 0
        var outside = 0
        for (r in dao.remindersAt(p.id)) {
            if (r.leave != leave || r.fired) continue
            val note = notes.get(r.noteId)
            if (note == null) { dao.deleteReminder(r.id); continue }
            if (note.trashedAt != null) continue
            val blocks = Blocks.parse(note.blocks)
            val line = if (r.blockId.isEmpty()) null else blocks.firstOrNull { it.id == r.blockId }
            if (r.blockId.isNotEmpty() && line == null) { dao.deleteReminder(r.id); continue }
            if (!r.allows(now)) { outside++; continue }
            if (line?.done == true && !r.always) { dao.upsertReminder(r.copy(fired = true)); continue }
            PlaceNotifier.show(ctx, r, p, note.title, line, blocks)
            rang++
            if (!r.always) {
                if (r.dropAfter) dao.deleteReminder(r.id) else dao.upsertReminder(r.copy(fired = true))
            }
        }
        return when {
            rang > 0 -> "צלצל ($rang)"
            outside > 0 -> "מחוץ לשעות"
            else -> "אין מה להזכיר"
        }
    }

    suspend fun setSticky(id: String, on: Boolean) {
        val r = dao.reminder(id) ?: return
        dao.upsertReminder(r.copy(sticky = on))
    }

    private fun clear(id: String) {
        NotificationManagerCompat.from(ctx).cancel(PlaceNotifier.idOf(id))
        Shade.forget(ctx, Shade.place(id))
    }

    companion object {
        const val ARM_MARGIN_M = 400f
        const val LEAVE_MARGIN_M = 150f
        const val MIN_RADIUS_M = 80f
        const val MAX_FENCES = 99
    }
}

/** Places arrived at before their reminders' hours: place id → when the hours open (epoch ms). */
private class LatePlaces(ctx: Context) {
    private val prefs: SharedPreferences = ctx.getSharedPreferences("places_late", Context.MODE_PRIVATE)
    fun all(): Map<String, Long> = prefs.all.mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }.toMap()
    fun put(id: String, at: Long) = prefs.edit().putLong(id, at).apply()
    fun drop(id: String) = prefs.edit().remove(id).apply()
}

/** What the fences last did — shown on the places screen, so "it didn't ring" has an answer. */
class PlaceStatus(ctx: Context) {
    private val prefs: SharedPreferences = ctx.getSharedPreferences("places", Context.MODE_PRIVATE)
    val hash get() = prefs.getInt("hash", 0)
    val syncedAt get() = prefs.getLong("syncedAt", 0)
    val result: String get() = prefs.getString("result", "").orEmpty()
    val count get() = prefs.getInt("count", 0)
    val lastCross: String get() = prefs.getString("cross", "").orEmpty()

    fun recordSync(hash: Int, at: Long, result: String, count: Int) =
        prefs.edit().putInt("hash", hash).putLong("syncedAt", at).putString("result", result).putInt("count", count).apply()

    fun recordCross(place: String, what: String, outcome: String) {
        val t = LocalDateTime.now().format(DateTimeFormatter.ofPattern("d/M HH:mm"))
        prefs.edit().putString("cross", "$place · $what · $t · $outcome").apply()
    }

    /** After the system dropped our fences (location switched off): register again next time. */
    fun invalidate() = prefs.edit().putInt("hash", 0).apply()
}

object PlaceNotifier {
    fun idOf(reminderId: String) = "place:$reminderId".hashCode()

    fun show(ctx: Context, r: PlaceReminderEntity, p: PlaceEntity, noteTitle: String, line: Block?, blocks: List<Block>, silent: Boolean = false) {
        if (!Notifier.permitted(ctx)) return
        val channel = Sounds.channel(ctx, Sounds.Kind.Place)
        val m = com.ohav.noted.data.Privacy.mask((ctx.applicationContext as? com.ohav.noted.NotedApp)?.privacy?.of(r.noteId) ?: 0, noteTitle, line?.text)
        val title = m.head
        // the reminder is the notification; the place is only the small header line
        val where = when {
            // "הרכב" can't take a ל/מ prefix cleanly ("להרכב"): the trigger, a dot, the name
            p.isDevice && com.ohav.noted.data.PlaceKind.of(p.kind) == com.ohav.noted.data.PlaceKind.Car -> (if (r.leave) "ביציאה · " else "בכניסה · ") + p.name
            p.isDevice -> (if (r.leave) "בניתוק · " else "בחיבור · ") + p.name
            r.leave -> "ביציאה מ${p.name}"
            else -> p.name
        }
        val body = when {
            m.locked -> "יש לך תזכורת — פותחים עם הקוד"
            line != null -> m.note
            m.body -> Notifier.summary(blocks)
            else -> "התוכן מוסתר"
        }
        val code = idOf(r.id)
        val open = PendingIntent.getActivity(
            ctx, code,
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(Notifier.EXTRA_NOTE, r.noteId).putExtra(Notifier.EXTRA_BLOCK, r.blockId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        fun action(a: String) = PendingIntent.getBroadcast(
            ctx, code + a.hashCode(),
            Intent(ctx, GeofenceReceiver::class.java).setAction(a).putExtra(GeofenceReceiver.EXTRA_ID, r.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setColor(0xFFE0542C.toInt())
            .setContentTitle(title)
            .setSubText(where)
            .apply { if (body.isNotBlank()) setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body)) }
            .setShowWhen(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setSilent(silent)
            .setContentIntent(open)
            .addAction(0, "סמן כבוצע", action(GeofenceReceiver.DONE))
            .apply { if (!r.always && !r.dropAfter) addAction(0, "בפעם הבאה", action(GeofenceReceiver.AGAIN)) }
            .also { Shade.decorate(it, ctx, Shade.place(r.id), r.sticky, code) }
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(code, n)
        } catch (_: SecurityException) {
        }
    }
}

/** Geofence crossings and the place notification's buttons. Runs with the app dead. */
class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? NotedApp ?: return
        val pending = goAsync()
        app.scope.launch {
            try {
                when (intent.action) {
                    DONE -> intent.getStringExtra(EXTRA_ID)?.let { app.places.done(it) }
                    AGAIN -> intent.getStringExtra(EXTRA_ID)?.let { app.places.again(it) }
                    LATE -> app.places.lateDue()
                    else -> {
                        val e = GeofencingEvent.fromIntent(intent) ?: return@launch
                        if (e.hasError()) {
                            if (e.errorCode == GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE) {
                                app.places.status.invalidate()
                                app.places.status.recordSync(0, System.currentTimeMillis(), "err:1000", 0)
                            }
                            return@launch
                        }
                        val ids = e.triggeringGeofences?.map { it.requestId }.orEmpty()
                        app.places.crossing(ids, e.geofenceTransition, e.triggeringLocation)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val CROSS = "com.ohav.noted.place.CROSS"
        const val DONE = "com.ohav.noted.place.DONE"
        const val AGAIN = "com.ohav.noted.place.AGAIN"
        const val LATE = "com.ohav.noted.place.LATE"
        const val EXTRA_ID = "id"
    }
}
