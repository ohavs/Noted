package com.ohav.noted.sync

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.ohav.noted.data.CategoryEntity
import com.ohav.noted.data.NotesRepository
import com.ohav.noted.data.Share
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom

/**
 * Sharing a note: invite links, joining, roles, leaving. Membership lives on the note
 * document itself (memberIds / members / people); invites are `invites/{token}` documents
 * the owner creates and anyone signed in can read by their (unguessable) token.
 */
class Sharing(private val account: Account, private val sync: Sync, private val notes: NotesRepository) {
    data class Invite(
        val token: String,
        val note: String,
        val role: String,
        val from: String,
        val fromPhoto: String?,
        val title: String,
        val color: String,
        val expired: Boolean,
        /** A shared folder (category) instead of a single note: its id, and its icon. */
        val folder: String = "",
        val icon: String = "folder",
    ) {
        val isFolder: Boolean get() = folder.isNotEmpty()
    }

    sealed interface Result<out T> {
        data class Ok<T>(val value: T) : Result<T>
        data class Failed(val message: String) : Result<Nothing>
    }

    private val fs get() = FirebaseFirestore.getInstance()

    private fun reason(e: Throwable): String = when ((e as? FirebaseFirestoreException)?.code) {
        FirebaseFirestoreException.Code.UNAVAILABLE -> "צריך חיבור לרשת"
        FirebaseFirestoreException.Code.PERMISSION_DENIED -> "אין הרשאה לפעולה הזו"
        FirebaseFirestoreException.Code.NOT_FOUND -> "הפתק לא נמצא בענן"
        else -> if (e is java.io.IOException) "צריך חיבור לרשת" else "משהו השתבש — נסו שוב"
    }

    /** Waits (briefly) until a fresh note has reached the cloud. */
    private suspend fun awaitCloud(id: String): Boolean =
        withTimeoutOrNull(12_000) { while (!sync.inCloud(id)) delay(250); true } ?: false

    /** A new invite link for [noteId] with [role]. */
    suspend fun invite(noteId: String, role: String): Result<String> {
        val u = account.user.value ?: return Result.Failed("צריך להתחבר קודם")
        if (!awaitCloud(noteId)) return Result.Failed("הפתק עוד לא הגיע לענן — בדקו חיבור ונסו שוב")
        val n = notes.get(noteId) ?: return Result.Failed("הפתק לא נמצא")
        return runCatching {
            // make sure the others will see who I am
            fs.collection("notes").document(noteId)
                .update("people.${u.uid}", mapOf("n" to u.name.ifBlank { u.email.substringBefore('@') }, "p" to u.photoUrl))
                .await()
            val token = token()
            fs.collection("invites").document(token).set(
                mapOf(
                    "note" to noteId, "role" to role, "by" to u.uid,
                    "from" to u.first, "fromPhoto" to u.photoUrl,
                    "title" to n.title, "color" to n.color,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "exp" to Timestamp(java.util.Date(System.currentTimeMillis() + INVITE_DAYS * 86_400_000L)),
                ),
            ).await()
            link(token)
        }.fold({ Result.Ok(it) }, { Result.Failed(reason(it)) })
    }

    suspend fun read(token: String): Result<Invite> = runCatching {
        val d = fs.collection("invites").document(token).get().await()
        if (!d.exists()) return Result.Failed("הקישור לא תקין או שבוטל")
        Invite(
            token, d.getString("note").orEmpty(), d.getString("role") ?: Share.ROLE_VIEWER,
            d.getString("from").orEmpty(), d.getString("fromPhoto"), d.getString("title").orEmpty(),
            d.getString("color") ?: "Butter",
            (d.getTimestamp("exp")?.toDate()?.time ?: Long.MAX_VALUE) < System.currentTimeMillis(),
            folder = d.getString("folder").orEmpty(),
            icon = d.getString("icon") ?: "folder",
        )
    }.fold({ Result.Ok(it) }, { Result.Failed(reason(it)) })

    /** Joins the invite's note (or folder); Ok(true) = joined now, Ok(false) = was already on it. */
    suspend fun join(inv: Invite): Result<Boolean> {
        val u = account.user.value ?: return Result.Failed("צריך להתחבר קודם")
        val ref = if (inv.isFolder) fs.collection("folders").document(inv.folder) else fs.collection("notes").document(inv.note)
        // already a member (or the owner): reading works only then
        val already = runCatching { ref.get().await().let { it.exists() && (it.get("memberIds") as? List<*>)?.contains(u.uid) == true } }.getOrDefault(false)
        if (already) return Result.Ok(false)
        if (inv.expired) return Result.Failed("הקישור פג תוקף — בקשו קישור חדש")
        return runCatching {
            ref.update(
                mapOf(
                    "memberIds" to FieldValue.arrayUnion(u.uid),
                    "members.${u.uid}" to inv.role,
                    "people.${u.uid}" to mapOf("n" to u.name.ifBlank { u.email.substringBefore('@') }, "p" to u.photoUrl),
                    "inv" to inv.token,
                ),
            ).await()
            true
        }.fold({ Result.Ok(it) }, { Result.Failed(if ((it as? FirebaseFirestoreException)?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) "הקישור כבר לא בתוקף" else reason(it)) })
    }

    /** Owner: change someone's role (on a note, or with [folder] on a shared folder). */
    suspend fun setRole(noteId: String, uid: String, role: String, folder: Boolean = false): Result<Unit> = runCatching {
        fs.collection(if (folder) "folders" else "notes").document(noteId).update("members.$uid", role).await()
        Unit
    }.fold({ Result.Ok(it) }, { Result.Failed(reason(it)) })

    /** Owner: take someone off the note or folder (or anyone: take myself off). */
    suspend fun remove(noteId: String, uid: String, folder: Boolean = false): Result<Unit> = runCatching {
        val upd = mutableMapOf<String, Any>(
            "memberIds" to FieldValue.arrayRemove(uid),
            "members.$uid" to FieldValue.delete(),
            "people.$uid" to FieldValue.delete(),
        )
        if (!folder) upd["u.$uid"] = FieldValue.delete()
        fs.collection(if (folder) "folders" else "notes").document(noteId).update(upd).await()
        Unit
    }.fold({ Result.Ok(it) }, { Result.Failed(reason(it)) })

    // ------------------------------------------------------------------ shared folders

    /** Starts sharing my top-level category [k] (needs the network once). */
    suspend fun shareFolder(k: CategoryEntity): Result<Unit> {
        val u = account.user.value ?: return Result.Failed("צריך להתחבר קודם")
        return runCatching { sync.shareFolder(u, k) }.fold({ Result.Ok(Unit) }, { Result.Failed(reason(it)) })
    }

    /** A new invite link to my shared folder [k]. */
    suspend fun folderInvite(k: CategoryEntity, role: String): Result<String> {
        val u = account.user.value ?: return Result.Failed("צריך להתחבר קודם")
        return runCatching {
            val token = token()
            fs.collection("invites").document(token).set(
                mapOf(
                    "folder" to k.id, "role" to role, "by" to u.uid,
                    "from" to u.first, "fromPhoto" to u.photoUrl,
                    "title" to k.name, "icon" to k.icon,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "exp" to Timestamp(java.util.Date(System.currentTimeMillis() + INVITE_DAYS * 86_400_000L)),
                ),
            ).await()
            link(token)
        }.fold({ Result.Ok(it) }, { Result.Failed(reason(it)) })
    }

    /** Owner: nobody else sees the folder any more; the category and its notes stay mine. */
    suspend fun unshareFolder(id: String): Result<Unit> =
        runCatching { sync.unshareFolder(id) }.fold({ Result.Ok(Unit) }, { Result.Failed(reason(it)) })

    /** Leaves someone else's folder: its notes go, mine from it move to my own category. */
    suspend fun leaveFolder(id: String): Result<Unit> {
        val u = account.user.value ?: return Result.Failed("צריך להתחבר קודם")
        return when (val r = remove(id, u.uid, folder = true)) {
            is Result.Ok -> { sync.leftFolder(id, u.uid); r }
            is Result.Failed -> r
        }
    }

    suspend fun leave(noteId: String): Result<Unit> {
        val u = account.user.value ?: return Result.Failed("צריך להתחבר קודם")
        return remove(noteId, u.uid)
    }

    companion object {
        const val HOST = "noted-ce451.web.app"
        const val INVITE_DAYS = 14
        fun link(token: String) = "https://$HOST/i/$token"

        /** The token in an invite link, or null. */
        fun tokenOf(url: String?): String? {
            val u = url ?: return null
            val m = Regex("""https?://$HOST/i/([A-Za-z0-9]{12,40})""").find(u) ?: return null
            return m.groupValues[1]
        }

        private const val ABC = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        private fun token(): String {
            val r = SecureRandom()
            return (1..22).map { ABC[r.nextInt(ABC.length)] }.joinToString("")
        }
    }
}
