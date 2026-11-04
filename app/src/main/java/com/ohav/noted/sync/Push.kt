package com.ohav.noted.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.ohav.noted.MainActivity
import com.ohav.noted.R
import com.ohav.noted.reminders.Notifier
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest

/**
 * Notifications from the people a note or folder is shared with. The server (functions/)
 * sends data messages — what changed is already worded there — and this side draws them:
 * one notification per note, replaced as more changes come in.
 */
object Push {
    const val CHANNEL = "shared"

    /** The note open in the editor right now, while the app is in front (no notification for it). */
    @Volatile var viewing: String? = null
    @Volatile var foreground = false

    private fun idOf(token: String): String =
        MessageDigest.getInstance("SHA-1").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }

    /** This device's token under `users/{uid}/devices`, so the server can reach it. */
    suspend fun register(ctx: Context, uid: String) {
        val token = FirebaseMessaging.getInstance().token.await()
        save(ctx, uid, token)
    }

    fun save(ctx: Context, uid: String, token: String) {
        FirebaseFirestore.getInstance().collection("users").document(uid).collection("devices").document(idOf(token)).set(
            mapOf("token" to token, "at" to FieldValue.serverTimestamp(), "model" to Build.MODEL, "app" to appVersion(ctx)),
        )
    }

    /** Before signing out: this device stops getting the account's notifications. */
    suspend fun unregister(uid: String) {
        runCatching {
            val token = FirebaseMessaging.getInstance().token.await()
            FirebaseFirestore.getInstance().collection("users").document(uid).collection("devices").document(idOf(token)).delete().await()
        }
    }

    private fun appVersion(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName.orEmpty() }.getOrDefault("")

    fun show(ctx: Context, data: Map<String, String>) {
        val note = data["note"].orEmpty()
        val folder = data["folder"].orEmpty()
        if (note.isNotEmpty() && foreground && viewing == note) return
        if (!Notifier.permitted(ctx)) return
        val channel = com.ohav.noted.reminders.Sounds.channel(ctx, com.ohav.noted.reminders.Sounds.Kind.Shared)
        val tag = note.ifEmpty { "f:$folder" }
        val open = PendingIntent.getActivity(
            ctx, tag.hashCode(),
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .apply { if (note.isNotEmpty()) putExtra(Notifier.EXTRA_NOTE, note) },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // a note kept private on this device says only that something changed, and by whom
        val flags = if (note.isEmpty()) 0 else (ctx.applicationContext as? com.ohav.noted.NotedApp)?.privacy?.of(note) ?: 0
        val m = com.ohav.noted.data.Privacy.mask(flags, data["title"].orEmpty(), null)
        val who = data["who"].orEmpty()
        val body = when {
            m.body -> data["body"].orEmpty()
            who.isNotEmpty() -> "$who · יש שינוי"
            else -> "יש שינוי"
        }
        val title = if (flags == 0) data["title"].orEmpty() else m.note
        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat_share)
            .setColor(0xFFE0542C.toInt())
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setOnlyAlertOnce(false)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(tag, 1, n)
        } catch (_: SecurityException) {
        }
    }

    /** Opening the note clears its notification. */
    fun clear(ctx: Context, note: String) = NotificationManagerCompat.from(ctx).cancel(note, 1)
}

class PushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        Push.save(this, uid, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        if (data.isEmpty()) return
        // a reminder set / changed elsewhere (the website, another phone): into Room and onto the alarm, no notification
        if (data["kind"] == "rem") {
            val app = applicationContext as? com.ohav.noted.NotedApp ?: return
            val f = HashMap<String, Any?>()
            data.forEach { (k, v) ->
                f[k] = when (k) {
                    "at", "snoozeAt" -> v.toLongOrNull()
                    "fired", "sticky", "deleted" -> v == "1"
                    else -> v.ifEmpty { null }
                }
            }
            f["blockId"] = data["blockId"].orEmpty()
            kotlinx.coroutines.runBlocking { runCatching { app.sync.applyPushedReminder(data["id"] ?: return@runBlocking, f) } }
            return
        }
        Push.show(this, data)
    }
}
