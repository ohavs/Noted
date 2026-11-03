package com.ohav.noted.sync

import android.content.Context
import com.google.firebase.storage.FirebaseStorage
import com.ohav.noted.data.Blocks
import com.ohav.noted.data.Media
import com.ohav.noted.data.MediaExtra
import com.ohav.noted.data.NotesRepository
import com.ohav.noted.data.shareInfo
import com.ohav.noted.places.Places
import com.ohav.noted.reminders.Reminders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The recycle bin's far end: deleting notes for good. A note goes with its reminders (time and
 * place), the media files no other note still uses (here and, for my own notes, in the cloud)
 * and its cloud copy — or, for a note someone shared with me, my seat in it.
 */
class Bin(
    private val ctx: Context,
    private val notes: NotesRepository,
    private val reminders: Reminders,
    private val places: Places,
    private val account: Account,
    private val sync: Sync,
    private val sharing: Sharing,
) {
    suspend fun purge(ids: List<String>) {
        if (ids.isEmpty()) return
        val gone = ids.mapNotNull { notes.get(it) }
        val keep = notes.filesInUse(except = ids.toSet())
        val uid = account.user.value?.uid
        val storage = if (account.available && uid != null) runCatching { FirebaseStorage.getInstance() }.getOrNull() else null
        for (n in gone) {
            reminders.removeAll(n.id)
            places.removeAll(n.id)
            val sh = n.shareInfo
            // someone else's note shared with me alone: step out, or it comes back with the next pull
            if (!sh.isOwner && sh.folder.isEmpty() && uid != null) sharing.leave(n.id)
            val files = Blocks.parse(n.blocks).flatMap { MediaExtra.files(it) }.filter { it !in keep }
            sync.discard(n.id)
            withContext(Dispatchers.IO) { files.forEach { Media.file(ctx, it).delete() } }
            // not awaited: offline, the delete waits like any other write; others' uploads stay (rules)
            if (sh.isOwner && storage != null) files.forEach { f -> runCatching { storage.reference.child("users/$uid/media/$f").delete() } }
        }
    }

    /** Notes that sat in the bin longer than [DAYS]: gone (on every launch). */
    suspend fun sweep(now: Long = System.currentTimeMillis()) = purge(notes.trashedBefore(now - DAYS * DAY_MS))

    companion object {
        const val DAYS = 30
        const val DAY_MS = 24L * 60 * 60 * 1000

        /** Whole days left before a note thrown at [trashedAt] is deleted (0 = today). */
        fun daysLeft(trashedAt: Long, now: Long = System.currentTimeMillis()): Int =
            ((trashedAt + DAYS * DAY_MS - now) / DAY_MS).toInt().coerceIn(0, DAYS)
    }
}
