package com.ohav.noted.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.time.LocalDateTime

/**
 * A saved place: a point and a radius (metres) that a geofence is drawn around. [kind] keys into
 * the place kinds (icon + default radius). The arming flags belong to the geofence engine:
 *  - [armIn]: we have been seen well away from here, so the next precise entry is an arrival.
 *  - [armOut]: we have been seen here, so the next exit is a departure.
 */
@Entity(tableName = "places")
data class PlaceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val kind: String,
    val lat: Double,
    val lng: Double,
    val radius: Int,
    val address: String,
    val position: Double,
    val createdAt: Long,
    val armIn: Boolean = false,
    val armOut: Boolean = false,
)

/**
 * "When I get to / leave [placeId]" on a note ([blockId] empty) or on one line. Same id scheme as
 * time reminders ([ReminderEntity.idFor]), in its own table — a note may have both.
 *  - [always]: every arrival / departure; otherwise once, after which [fired] is set (kept, so
 *    "next time" from the notification can re-arm it).
 *  - [days]: ISO weekdays it may ring on ("" = any day); [fromMin]..[toMin]: minutes of the day
 *    (local), null = any time. A window that wraps midnight (22:00–06:00) is allowed.
 */
@Entity(tableName = "place_reminders", indices = [Index("noteId"), Index("placeId")])
data class PlaceReminderEntity(
    @PrimaryKey val id: String,
    val noteId: String,
    val blockId: String,
    val placeId: String,
    val leave: Boolean,
    val always: Boolean,
    val days: String = "",
    val fromMin: Int? = null,
    val toMin: Int? = null,
    val fired: Boolean = false,
    val createdAt: Long,
) {
    val daySet: Set<Int> get() = days.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
    val hasWindow: Boolean get() = daySet.isNotEmpty() || fromMin != null

    /** Whether a crossing at local time [t] may ring. */
    fun allows(t: LocalDateTime): Boolean {
        val d = daySet
        if (d.isNotEmpty() && t.dayOfWeek.value !in d) return false
        val f = fromMin ?: return true
        val e = toMin ?: return true
        val m = t.hour * 60 + t.minute
        return if (f <= e) m in f..e else m >= f || m <= e
    }
}

@Dao
interface PlaceDao {
    @Query("SELECT * FROM places ORDER BY position")
    fun observePlaces(): Flow<List<PlaceEntity>>

    @Query("SELECT * FROM places ORDER BY position")
    suspend fun places(): List<PlaceEntity>

    @Query("SELECT * FROM places WHERE id = :id")
    suspend fun place(id: String): PlaceEntity?

    @Query("SELECT COALESCE(MAX(position), 0) FROM places")
    suspend fun maxPosition(): Double

    @Upsert
    suspend fun upsertPlace(p: PlaceEntity)

    @Query("DELETE FROM places WHERE id = :id")
    suspend fun deletePlace(id: String)

    @Query("UPDATE places SET armIn = :v WHERE id = :id")
    suspend fun setArmIn(id: String, v: Boolean)

    @Query("UPDATE places SET armOut = :v WHERE id = :id")
    suspend fun setArmOut(id: String, v: Boolean)

    @Query("UPDATE places SET position = :position WHERE id = :id")
    suspend fun setPosition(id: String, position: Double)

    @Query("SELECT * FROM place_reminders")
    fun observeReminders(): Flow<List<PlaceReminderEntity>>

    /** Reminders that can still ring: not fired, note not in the bin. */
    @Query("SELECT r.* FROM place_reminders r JOIN notes n ON n.id = r.noteId WHERE n.trashedAt IS NULL AND r.fired = 0")
    suspend fun live(): List<PlaceReminderEntity>

    /** Re-emits whenever anything the fences depend on changes. */
    @Query(
        "SELECT COUNT(*) || ':' || COALESCE(SUM(r.leave), 0) || ':' || (SELECT COUNT(*) FROM places) FROM place_reminders r " +
            "JOIN notes n ON n.id = r.noteId WHERE n.trashedAt IS NULL AND r.fired = 0",
    )
    fun observeTick(): Flow<String>

    @Query("SELECT * FROM place_reminders WHERE id = :id")
    suspend fun reminder(id: String): PlaceReminderEntity?

    @Query("SELECT * FROM place_reminders WHERE placeId = :placeId")
    suspend fun remindersAt(placeId: String): List<PlaceReminderEntity>

    @Upsert
    suspend fun upsertReminder(r: PlaceReminderEntity)

    @Query("DELETE FROM place_reminders WHERE id = :id")
    suspend fun deleteReminder(id: String)

    @Query("DELETE FROM place_reminders WHERE placeId = :placeId")
    suspend fun deleteRemindersAt(placeId: String)

    @Transaction
    suspend fun deletePlaceWithReminders(id: String) {
        deleteRemindersAt(id)
        deletePlace(id)
    }

    @Transaction
    suspend fun restore(p: PlaceEntity, rs: List<PlaceReminderEntity>) {
        upsertPlace(p)
        rs.forEach { upsertReminder(it) }
    }
}
