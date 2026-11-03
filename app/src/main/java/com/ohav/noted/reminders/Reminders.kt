package com.ohav.noted.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ohav.noted.MainActivity
import com.ohav.noted.NotedApp
import com.ohav.noted.R
import com.ohav.noted.data.Block
import com.ohav.noted.data.BlockType
import com.ohav.noted.data.Blocks
import com.ohav.noted.data.NotedDatabase
import com.ohav.noted.data.ReminderEntity
import com.ohav.noted.data.Repeat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Time reminders: storage, ringing and the one alarm that wakes us. Only the earliest pending
 * reminder holds an alarm; whenever it rings (or anything changes) the next one is armed.
 */
class Reminders(private val ctx: Context, private val db: NotedDatabase) {
    private val dao = db.reminders()
    private val notes = db.notes()
    val all: Flow<List<ReminderEntity>> = dao.observeAll()

    suspend fun get(noteId: String, blockId: String = ""): ReminderEntity? = dao.get(ReminderEntity.idFor(noteId, blockId))

    /** Sets (or replaces) the reminder of a note / of one line, starting at [at] local time. */
    suspend fun set(noteId: String, blockId: String, at: LocalDateTime, repeat: Repeat): ReminderEntity {
        // "on Sun/Tue" starting on a Monday: the first ring is the first chosen day
        val first = if (repeat.kind == Repeat.Kind.Days && at.dayOfWeek.value !in repeat.days) repeat.next(at, at.minusSeconds(1)) ?: at else at
        val r = ReminderEntity(
            id = ReminderEntity.idFor(noteId, blockId), noteId = noteId, blockId = blockId,
            anchor = at.toString(), occ = first.toString(), zone = ZoneId.systemDefault().id,
            rule = repeat.rule(), until = repeat.until?.toString(),
        )
        dao.upsert(r)
        if (blockId.isEmpty()) dao.mirror(noteId, r.occAt)
        clear(r.id)
        return r
    }

    suspend fun remove(noteId: String, blockId: String = "") {
        val id = ReminderEntity.idFor(noteId, blockId)
        dao.delete(id)
        if (blockId.isEmpty()) dao.mirror(noteId, null)
        clear(id)
    }

    /** Every time reminder of a note (it's being deleted for good). */
    suspend fun removeAll(noteId: String) = dao.forNote(noteId).forEach { remove(it.noteId, it.blockId) }

    /** Puts a removed reminder back exactly as it was (undo). */
    suspend fun restore(r: ReminderEntity) {
        dao.upsert(r)
        if (r.blockId.isEmpty()) dao.mirror(r.noteId, r.occAt)
    }

    suspend fun snooze(id: String, at: Long) {
        val r = dao.get(id) ?: return
        dao.upsert(r.copy(snoozeAt = at))
        clear(id)
    }

    /** "Done" from the notification: a one-shot goes away (its line gets checked); a series just quiets. */
    suspend fun done(id: String) {
        val r = dao.get(id) ?: return
        clear(id)
        if (r.repeat.repeats) {
            dao.upsert(r.copy(snoozeAt = null))
            return
        }
        if (r.blockId.isNotEmpty()) {
            notes.get(r.noteId)?.let { n ->
                val blocks = Blocks.parse(n.blocks).map { if (it.id == r.blockId) it.copy(done = true) else it }
                notes.setBlocks(n.id, Blocks.serialize(blocks), System.currentTimeMillis())
            }
        }
        remove(r.noteId, r.blockId)
    }

    /**
     * ✓ on an upcoming reminder (the widget): a one-shot is done like from the notification; a
     * series lets go of a pending snooze, or else skips its coming occurrence for the next one.
     */
    suspend fun skip(id: String) {
        val r = dao.get(id) ?: return
        if (!r.repeat.repeats || (r.snoozeAt != null && (r.fired || r.snoozeAt <= r.occAt))) { done(id); return }
        clear(id)
        val next = r.repeat.next(r.anchorTime, r.occTime)
        val nr = (if (next != null) r.copy(occ = next.toString()) else r.copy(fired = true)).copy(snoozeAt = null)
        dao.upsert(nr)
        if (nr.blockId.isEmpty()) dao.mirror(nr.noteId, nr.occAt)
    }

    /**
     * Pushes the next ring [ms] later (the widget's snooze, before or after it rang). A one-shot
     * that hasn't rung just moves; a series' coming occurrence becomes a snooze and the series
     * moves on, so the rest of it stays on its days.
     */
    suspend fun later(id: String, ms: Long) {
        val r = dao.get(id) ?: return
        val due = r.dueAt ?: return
        val at = maxOf(due, System.currentTimeMillis()) + ms
        val nr = when {
            !r.repeat.repeats && !r.fired && r.occAt <= due -> {
                val t = LocalDateTime.ofInstant(Instant.ofEpochMilli(at), r.zoneId).withSecond(0).withNano(0)
                r.copy(occ = t.toString(), anchor = t.toString(), snoozeAt = null)
            }
            !r.fired && r.occAt <= due -> {
                val next = r.repeat.next(r.anchorTime, r.occTime)
                (if (next != null) r.copy(occ = next.toString()) else r.copy(fired = true)).copy(snoozeAt = at)
            }
            else -> r.copy(snoozeAt = at)
        }
        clear(id)
        dao.upsert(nr)
        if (nr.blockId.isEmpty()) dao.mirror(nr.noteId, nr.occAt)
    }

    /** Rings every reminder that is due, moves series to their next occurrence, arms the next alarm. */
    suspend fun fireDue() {
        val now = System.currentTimeMillis()
        val slack = 2_000L
        for (r in dao.live()) {
            val due = r.dueAt ?: continue
            if (due > now + slack) continue
            val note = notes.get(r.noteId) ?: continue
            val blocks = Blocks.parse(note.blocks)
            val line = if (r.blockId.isEmpty()) null else blocks.firstOrNull { it.id == r.blockId }
            if (r.blockId.isNotEmpty() && line == null) { dao.delete(r.id); continue }

            val skip = line?.done == true && !r.repeat.repeats
            if (!skip) Notifier.show(ctx, r, note.title, line, blocks)

            var nr = r
            if (r.snoozeAt != null && r.snoozeAt <= now + slack) nr = nr.copy(snoozeAt = null)
            if (!r.fired && r.occAt <= now + slack) {
                val nowLocal = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), r.zoneId)
                val next = r.repeat.next(r.anchorTime, maxOf(r.occTime, nowLocal))
                nr = if (next != null) nr.copy(occ = next.toString()) else nr.copy(fired = true)
            }
            dao.upsert(nr)
            if (nr.blockId.isEmpty()) dao.mirror(nr.noteId, nr.occAt)
        }
        schedule()
    }

    /** Arms the single alarm for the earliest pending reminder (or disarms it). */
    suspend fun schedule() {
        val next = dao.live().mapNotNull { it.dueAt }.minOrNull()
        Alarm.set(ctx, next)
    }

    fun observeTick(): Flow<Int> = dao.observeLiveCount()

    private fun clear(id: String) = NotificationManagerCompat.from(ctx).cancel(Notifier.idOf(id))
}

/** The one exact alarm. Exact when the system allows it, otherwise a short window. */
object Alarm {
    private fun intent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 1, Intent(ctx, ReminderReceiver::class.java).setAction(ReminderReceiver.RING),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun canExact(ctx: Context): Boolean {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return false
        return Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
    }

    fun set(ctx: Context, at: Long?) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = intent(ctx)
        if (at == null) { am.cancel(pi); return }
        val t = maxOf(at, System.currentTimeMillis() + 500)
        runCatching {
            if (canExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
            else am.setWindow(AlarmManager.RTC_WAKEUP, t, 60_000L, pi)
        }.onFailure { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi) }
    }
}

object Notifier {
    const val CHANNEL = "reminders"
    const val EXTRA_NOTE = "open_note"
    const val EXTRA_BLOCK = "open_block"

    fun idOf(reminderId: String) = reminderId.hashCode()

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "תזכורות", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "תזכורות בזמן שקבעת לפתקים ולשורות"
                enableVibration(true)
            },
        )
    }

    fun permitted(ctx: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    fun show(ctx: Context, r: ReminderEntity, noteTitle: String, line: Block?, blocks: List<Block>) {
        if (!permitted(ctx)) return
        ensureChannel(ctx)
        val title = when {
            line != null -> line.text.trim().ifEmpty { "שורה ברשימה" }
            else -> noteTitle.trim().ifEmpty { "פתק בלי כותרת" }
        }
        val body = when {
            line != null -> noteTitle.trim().ifEmpty { "פתק בלי כותרת" }
            else -> summary(blocks)
        }
        val code = idOf(r.id)
        val open = PendingIntent.getActivity(
            ctx, code,
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_NOTE, r.noteId).putExtra(EXTRA_BLOCK, r.blockId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val done = PendingIntent.getBroadcast(
            ctx, code,
            Intent(ctx, ReminderReceiver::class.java).setAction(ReminderReceiver.DONE).putExtra(ReminderReceiver.EXTRA_ID, r.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val snooze = PendingIntent.getActivity(
            ctx, code,
            Intent(ctx, SnoozeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                .putExtra(ReminderReceiver.EXTRA_ID, r.id).putExtra(SnoozeActivity.EXTRA_TITLE, title),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setColor(0xFFE0542C.toInt())
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setWhen(r.dueAt ?: System.currentTimeMillis())
            .setShowWhen(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(Notification.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, "סמן כבוצע", done)
            .addAction(0, "נודניק", snooze)
            // on the watch the phone's snooze sheet can't open: done + two fixed snoozes, all handled on the phone
            .extend(
                NotificationCompat.WearableExtender()
                    .addAction(NotificationCompat.Action.Builder(R.drawable.ic_stat_reminder, "סמן כבוצע", done).build())
                    .addAction(NotificationCompat.Action.Builder(R.drawable.ic_stat_reminder, "עוד 10 דק׳", snoozeIn(ctx, r.id, code, 10)).build())
                    .addAction(NotificationCompat.Action.Builder(R.drawable.ic_stat_reminder, "עוד שעה", snoozeIn(ctx, r.id, code, 60)).build()),
            )
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(code, n)
        } catch (_: SecurityException) {
        }
    }

    private fun snoozeIn(ctx: Context, id: String, code: Int, minutes: Int): PendingIntent = PendingIntent.getBroadcast(
        ctx, code * 31 + minutes,
        Intent(ctx, ReminderReceiver::class.java).setAction(ReminderReceiver.SNOOZE)
            .putExtra(ReminderReceiver.EXTRA_ID, id)
            .putExtra(ReminderReceiver.EXTRA_AT, System.currentTimeMillis() + minutes * 60_000L),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun summary(blocks: List<Block>): String {
        val checks = blocks.filter { it.type == BlockType.Check }
        if (checks.isNotEmpty()) {
            val open = checks.filter { !it.done }.map { it.text.trim() }.filter { it.isNotEmpty() }
            if (open.isEmpty()) return "הכל סומן ✓"
            return open.take(4).joinToString(" · ") + if (open.size > 4) " ועוד ${open.size - 4}" else ""
        }
        return blocks.firstOrNull { it.text.isNotBlank() }?.text?.trim()?.take(160) ?: "תזכורת"
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? NotedApp ?: return
        val pending = goAsync()
        app.scope.launch {
            try {
                when (intent.action) {
                    DONE -> intent.getStringExtra(EXTRA_ID)?.let { app.reminders.done(it) }
                    Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> app.places.sync(force = true)
                    SNOOZE -> {
                        val id = intent.getStringExtra(EXTRA_ID)
                        val at = intent.getLongExtra(EXTRA_AT, 0L)
                        if (id != null && at > 0) app.reminders.snooze(id, at)
                    }
                }
                app.reminders.fireDue()
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val RING = "com.ohav.noted.reminder.RING"
        const val DONE = "com.ohav.noted.reminder.DONE"
        const val SNOOZE = "com.ohav.noted.reminder.SNOOZE"
        const val EXTRA_ID = "id"
        const val EXTRA_AT = "at"
    }
}
