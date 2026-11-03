package com.ohav.noted.data

import org.json.JSONArray
import org.json.JSONObject

/** Someone on a shared note. [role] = owner / editor / viewer. */
data class Member(val uid: String, val name: String, val photo: String?, val role: String) {
    val first: String get() = name.trim().split(' ').firstOrNull().orEmpty().ifEmpty { "משתתף" }
    val letter: String get() = first.take(1)
}

/**
 * How a note is shared, from this account's point of view (stored on the note row by sync).
 * [role] is mine; [by] = block id → uid of whoever last changed that line.
 */
data class Share(
    val owner: String = "",
    val role: String = "",
    val members: List<Member> = emptyList(),
    val by: Map<String, String> = emptyMap(),
    /** The shared folder this note sits in ("" = none). */
    val folder: String = "",
    /** Members a shared folder brought in (not managed on the note itself). */
    val via: List<String> = emptyList(),
) {
    /** More than one person on it. */
    val shared: Boolean get() = members.size > 1

    /** Someone else's note that I'm on. */
    val withMe: Boolean get() = role == ROLE_EDITOR || role == ROLE_VIEWER

    val readOnly: Boolean get() = role == ROLE_VIEWER
    val isOwner: Boolean get() = role == ROLE_OWNER || role.isEmpty()

    fun member(uid: String?): Member? = members.firstOrNull { it.uid == uid }
    val ownerMember: Member? get() = member(owner)

    fun encode(): String {
        if (owner.isEmpty()) return ""
        val m = JSONArray()
        members.forEach { m.put(JSONObject().put("u", it.uid).put("n", it.name).put("p", it.photo ?: "").put("r", it.role)) }
        return JSONObject().put("o", owner).put("r", role).put("m", m).put("b", JSONObject(by as Map<*, *>))
            .put("f", folder).put("v", JSONArray(via)).toString()
    }

    companion object {
        const val ROLE_OWNER = "owner"
        const val ROLE_EDITOR = "editor"
        const val ROLE_VIEWER = "viewer"
        val None = Share()

        private val cache = object : LinkedHashMap<String, Share>(64, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Share>?) = size > 200
        }

        fun parse(s: String): Share {
            if (s.isEmpty()) return None
            synchronized(cache) { cache[s]?.let { return it } }
            val v = runCatching {
                val o = JSONObject(s)
                val arr = o.optJSONArray("m") ?: JSONArray()
                val members = (0 until arr.length()).map { i ->
                    val m = arr.getJSONObject(i)
                    Member(m.getString("u"), m.optString("n"), m.optString("p").ifEmpty { null }, m.optString("r"))
                }
                val b = o.optJSONObject("b")
                val v = o.optJSONArray("v")
                Share(
                    o.getString("o"), o.optString("r"), members, b?.keys()?.asSequence()?.associateWith { b.getString(it) }.orEmpty(),
                    o.optString("f"), (0 until (v?.length() ?: 0)).map { v!!.getString(it) },
                )
            }.getOrDefault(None)
            synchronized(cache) { cache[s] = v }
            return v
        }
    }
}

val NoteEntity.shareInfo: Share get() = Share.parse(share)

/** A shared folder's members (for a category; [Share.None] when it isn't shared). */
val CategoryEntity.shareInfo: Share get() = Share.parse(share)

/** Someone else's shared folder, here as a category. */
val CategoryEntity.foreign: Boolean get() = shareInfo.withMe
