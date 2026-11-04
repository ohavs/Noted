package com.ohav.noted.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentResolver
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import com.ohav.noted.R

/**
 * Notification sounds, one choice per kind (time reminders, place reminders, shared changes).
 *
 * A channel's sound is fixed once Android has it, so each (kind, tone) pair is its own channel
 * and choosing a tone swaps channels (the old one is deleted). The system tone keeps the original
 * channel ids, so installs from before this change keep their channel and its settings.
 *
 * Adding a tone: put `tone_<name>.ogg` in `res/raw` and a line in [tones] (`scripts/tones.py`
 * made the built-in ones).
 */
object Sounds {
    enum class Kind(val key: String, val title: String, val desc: String, val legacy: String?, val importance: Int) {
        Time("time", "תזכורות בזמן", "תזכורות בזמן שקבעת לפתקים ולשורות", "reminders", NotificationManager.IMPORTANCE_HIGH),
        Place("place", "תזכורות מקום", "כשמגיעים למקום או יוצאים ממנו", null, NotificationManager.IMPORTANCE_HIGH),
        Shared("shared", "שינויים בפתקים משותפים", "כשמישהו כותב בפתק או בתיקייה ששיתפו איתך, או מצטרף אליהם", "shared", NotificationManager.IMPORTANCE_DEFAULT),
    }

    /** [res] null: the system's notification sound ("system") or none at all ("silent"). */
    data class Tone(val id: String, val name: String, val res: Int?)

    const val SYSTEM = "system"
    const val SILENT = "silent"

    val tones = listOf(
        Tone(SYSTEM, "צליל המערכת", null),
        Tone("bell", "פעמון", R.raw.tone_bell),
        Tone("glass", "זכוכית", R.raw.tone_glass),
        Tone("soft", "אקורד רך", R.raw.tone_soft),
        Tone("marimba", "מרימבה", R.raw.tone_marimba),
        Tone("drop", "טיפה", R.raw.tone_drop),
        Tone("pop", "נקישה", R.raw.tone_pop),
        Tone(SILENT, "שקט · רטט בלבד", null),
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("sounds", Context.MODE_PRIVATE)

    fun chosen(ctx: Context, kind: Kind): Tone {
        val id = prefs(ctx).getString(kind.key, SYSTEM)
        return tones.firstOrNull { it.id == id } ?: tones.first()
    }

    fun choose(ctx: Context, kind: Kind, tone: Tone) {
        prefs(ctx).edit().putString(kind.key, tone.id).apply()
        channel(ctx, kind)
    }

    fun uri(ctx: Context, tone: Tone): Uri? = when {
        tone.res != null -> Uri.Builder().scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
            .authority(ctx.packageName).appendPath(ctx.resources.getResourceTypeName(tone.res))
            .appendPath(ctx.resources.getResourceEntryName(tone.res)).build()
        tone.id == SYSTEM -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        else -> null
    }

    private fun idFor(kind: Kind, tone: Tone) =
        if (tone.id == SYSTEM && kind.legacy != null) kind.legacy else "${kind.key}.${tone.id}"

    /** The channel to post [kind] on: created with the chosen tone if missing, stale ones removed. */
    fun channel(ctx: Context, kind: Kind): String {
        val tone = chosen(ctx, kind)
        val id = idFor(kind, tone)
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return id
        if (nm.getNotificationChannel(id) == null) {
            nm.createNotificationChannel(
                NotificationChannel(id, kind.title, kind.importance).apply {
                    description = kind.desc
                    enableVibration(true)
                    if (tone.id != SYSTEM) setSound(
                        uri(ctx, tone),
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                    )
                },
            )
        }
        val mine = tones.map { idFor(kind, it) }.toSet()
        nm.notificationChannels.filter { it.id != id && it.id in mine }.forEach { nm.deleteNotificationChannel(it.id) }
        return id
    }

    /** Plays [tone] once (the settings picker). */
    fun preview(ctx: Context, tone: Tone) {
        val u = uri(ctx, tone) ?: return
        runCatching {
            RingtoneManager.getRingtone(ctx, u)?.apply {
                audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            }?.play()
        }
    }
}
