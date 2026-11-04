package com.ohav.noted.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ohav.noted.MainActivity
import com.ohav.noted.NotedApp
import com.ohav.noted.R
import com.ohav.noted.data.Blocks
import com.ohav.noted.data.NoteEntity
import com.ohav.noted.data.Privacy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Notifications that stay in the shade until removed by hand: a sticky reminder after it rang,
 * or a note pinned there with no time at all.
 *
 * Since Android 14 a user can swipe even an ongoing notification away, so each one carries a
 * delete intent that puts it straight back (quietly); only its "הסר" button really removes it.
 * What is held is kept by key (`t:` time reminder, `p:` place reminder, `n:` note) so it comes
 * back after a reboot too.
 */
object Shade {
    fun time(id: String) = "t:$id"
    fun place(id: String) = "p:$id"
    fun note(id: String) = "n:$id"

    private const val HELD = "held"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("shade", Context.MODE_PRIVATE)

    private val _held = MutableStateFlow<Set<String>>(emptySet())
    /** What is held right now (the note menus show "pinned" from it). */
    val held: StateFlow<Set<String>> = _held

    fun load(ctx: Context) { _held.value = prefs(ctx).getStringSet(HELD, emptySet())!!.toSet() }

    fun isHeld(ctx: Context, key: String) = key in prefs(ctx).getStringSet(HELD, emptySet())!!

    fun hold(ctx: Context, key: String) = edit(ctx) { it + key }
    fun forget(ctx: Context, key: String) = edit(ctx) { it - key }

    private fun edit(ctx: Context, f: (Set<String>) -> Set<String>) {
        val p = prefs(ctx)
        val now = f(p.getStringSet(HELD, emptySet())!!.toSet())
        p.edit().putStringSet(HELD, now).apply()
        _held.value = now
    }

    fun idOf(key: String): Int = when {
        key.startsWith("t:") -> Notifier.idOf(key.drop(2))
        key.startsWith("p:") -> PlaceNotifier.idOf(key.drop(2))
        else -> key.hashCode()
    }

    private fun pi(ctx: Context, action: String, key: String) = PendingIntent.getBroadcast(
        ctx, (action + key).hashCode(),
        Intent(ctx, ShadeReceiver::class.java).setAction(action).putExtra(ShadeReceiver.EXTRA_KEY, key),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * A ringing reminder: sticky → ongoing, comes back when swiped, with "הסר"; otherwise a
     * "הצמד" button that turns it sticky. Exactly one of the two, never both.
     */
    fun decorate(b: NotificationCompat.Builder, ctx: Context, key: String, sticky: Boolean, @Suppress("UNUSED_PARAMETER") code: Int) {
        if (sticky) {
            b.setOngoing(true).setAutoCancel(false)
                .setDeleteIntent(pi(ctx, ShadeReceiver.REPOST, key))
                .addAction(0, "הסר", pi(ctx, ShadeReceiver.UNPIN, key))
            hold(ctx, key)
        } else {
            b.addAction(0, "הצמד", pi(ctx, ShadeReceiver.PIN, key))
        }
    }

    /** Shows what [key] holds again, silently; forgets it when what it pointed at is gone. */
    suspend fun repost(app: NotedApp, key: String) {
        val id = key.drop(2)
        val ok = when {
            key.startsWith("t:") -> {
                val r = app.db.reminders().get(id)
                val n = r?.let { app.db.notes().get(it.noteId) }?.takeIf { it.trashedAt == null }
                if (r == null || n == null) false else {
                    val blocks = Blocks.parse(n.blocks)
                    Notifier.show(app, r, n.title, blocks.firstOrNull { it.id == r.blockId }.takeIf { r.blockId.isNotEmpty() }, blocks, silent = true)
                    true
                }
            }
            key.startsWith("p:") -> {
                val r = app.places.reminderById(id)
                val p = r?.let { app.places.place(it.placeId) }
                val n = r?.let { app.db.notes().get(it.noteId) }?.takeIf { it.trashedAt == null }
                if (r == null || p == null || n == null) false else {
                    val blocks = Blocks.parse(n.blocks)
                    PlaceNotifier.show(app, r, p, n.title, blocks.firstOrNull { it.id == r.blockId }.takeIf { r.blockId.isNotEmpty() }, blocks, silent = true)
                    true
                }
            }
            else -> app.db.notes().get(id)?.takeIf { it.trashedAt == null && !it.archived }?.let { NoteShade.show(app, it); true } ?: false
        }
        if (!ok) remove(app, key)
    }

    /** After a reboot / app update: everything held comes back. */
    suspend fun repostAll(app: NotedApp) = prefs(app).getStringSet(HELD, emptySet())!!.toList().forEach { repost(app, it) }

    fun remove(ctx: Context, key: String) {
        NotificationManagerCompat.from(ctx).cancel(idOf(key))
        forget(ctx, key)
    }
}

/** A note pinned to the shade: its title and open lines, kept up to date, quiet. */
object NoteShade {
    private const val CHANNEL = "pinned"

    private fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "פתקים מוצמדים", NotificationManager.IMPORTANCE_LOW).apply {
                description = "פתקים שהצמדת לחלון ההתראות"
                setShowBadge(false)
            },
        )
    }

    fun pinned(ctx: Context, noteId: String) = Shade.isHeld(ctx, Shade.note(noteId))

    /** Pins or unpins; returns whether it is pinned now. */
    fun toggle(app: NotedApp, n: NoteEntity): Boolean {
        val key = Shade.note(n.id)
        return if (Shade.isHeld(app, key)) { Shade.remove(app, key); false } else { Shade.hold(app, key); show(app, n); true }
    }

    fun show(ctx: Context, n: NoteEntity) {
        if (!Notifier.permitted(ctx)) return
        ensureChannel(ctx)
        val key = Shade.note(n.id)
        val code = Shade.idOf(key)
        val m = Privacy.mask((ctx.applicationContext as? NotedApp)?.privacy?.of(n.id) ?: 0, n.title, null)
        val blocks = Blocks.parse(n.blocks)
        val body = when {
            m.locked -> "פתק נעול"
            m.body -> Notifier.summary(blocks)
            else -> "התוכן מוסתר"
        }
        val open = PendingIntent.getActivity(
            ctx, code,
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(Notifier.EXTRA_NOTE, n.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        fun pi(action: String) = PendingIntent.getBroadcast(
            ctx, (action + key).hashCode(),
            Intent(ctx, ShadeReceiver::class.java).setAction(action).putExtra(ShadeReceiver.EXTRA_KEY, key),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = m.head.ifBlank { "פתק" }
        val notif = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setColor(0xFFE0542C.toInt())
            .setContentTitle(title)
            .apply { if (body.isNotBlank()) setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body)) }
            .setShowWhen(false)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setDeleteIntent(pi(ShadeReceiver.REPOST))
            .addAction(0, "הסר", pi(ShadeReceiver.UNPIN))
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(code, notif)
        } catch (_: SecurityException) {
        }
    }

    /** Pinned notes follow their content; a note gone to the bin/archive lets go. */
    fun refresh(app: NotedApp, notes: List<NoteEntity>) {
        val keys = Shade.held.value.filter { it.startsWith("n:") }
        if (keys.isEmpty()) return
        val byId = notes.associateBy { it.id }
        for (k in keys) {
            val n = byId[k.drop(2)]
            if (n == null || n.trashedAt != null || n.archived) Shade.remove(app, k) else show(app, n)
        }
    }
}

class ShadeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? NotedApp ?: return
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        val pending = goAsync()
        app.scope.launch {
            try {
                when (intent.action) {
                    UNPIN -> Shade.remove(app, key)
                    REPOST -> if (Shade.isHeld(app, key)) Shade.repost(app, key)
                    PIN -> {
                        val id = key.drop(2)
                        if (key.startsWith("t:")) app.reminders.setSticky(id, true) else app.places.setSticky(id, true)
                        Shade.hold(app, key)
                        Shade.repost(app, key)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val PIN = "com.ohav.noted.shade.PIN"
        const val UNPIN = "com.ohav.noted.shade.UNPIN"
        const val REPOST = "com.ohav.noted.shade.REPOST"
        const val EXTRA_KEY = "key"
    }
}
