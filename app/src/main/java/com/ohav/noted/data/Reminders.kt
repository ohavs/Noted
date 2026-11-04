package com.ohav.noted.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * A time reminder on a note ([blockId] empty) or on one line of it. Times are local wall-clock
 * ([anchor], [occ]) in an IANA [zone], so "every day at 08:00" stays 08:00 across DST.
 *  - [anchor]: the first occurrence; fixes time of day, weekday and day of month for repeats.
 *  - [occ]: the occurrence that is pending (or was last shown, for a one-shot that [fired]).
 *  - [snoozeAt]: an extra one-off ring (epoch ms) from "snooze", on top of the schedule.
 */
@Entity(tableName = "reminders", indices = [Index("noteId")])
data class ReminderEntity(
    @PrimaryKey val id: String,
    val noteId: String,
    val blockId: String,
    val anchor: String,
    val occ: String,
    val zone: String,
    val rule: String,
    val until: String?,
    val fired: Boolean = false,
    val snoozeAt: Long? = null,
    /** Once it rings it stays in the notification shade until removed by hand (not by a swipe). */
    val sticky: Boolean = false,
    /** Last change, epoch ms — the newest copy wins when devices (and the website) sync. */
    val at: Long = 0L,
) {
    val repeat: Repeat get() = Repeat.parse(rule, until)
    val zoneId: ZoneId get() = runCatching { ZoneId.of(zone) }.getOrDefault(ZoneId.systemDefault())
    val occTime: LocalDateTime get() = LocalDateTime.parse(occ)
    val anchorTime: LocalDateTime get() = LocalDateTime.parse(anchor)

    /** When the current occurrence rings, epoch ms. */
    val occAt: Long get() = occTime.atZone(zoneId).toInstant().toEpochMilli()

    /** The next moment this reminder wants to ring, or null when nothing is pending. */
    val dueAt: Long?
        get() = listOfNotNull(snoozeAt, if (fired) null else occAt).minOrNull()

    companion object {
        fun idFor(noteId: String, blockId: String) = if (blockId.isEmpty()) noteId else "$noteId:$blockId"
    }
}

/** How a reminder repeats. Serialised into [ReminderEntity.rule] + [ReminderEntity.until]. */
data class Repeat(
    val kind: Kind = Kind.None,
    /** Every N units (Custom only). */
    val every: Int = 1,
    val unit: Step = Step.Day,
    /** ISO weekdays 1 (Mon) … 7 (Sun) for [Kind.Days]. */
    val days: Set<Int> = emptySet(),
    val until: LocalDate? = null,
) {
    enum class Kind { None, Daily, Days, Monthly, Custom }
    enum class Step { Day, Week, Month }

    val repeats get() = kind != Kind.None

    fun rule(): String = when (kind) {
        Kind.None -> ""
        Kind.Daily -> "d"
        Kind.Days -> "w:" + days.sorted().joinToString(",")
        Kind.Monthly -> "m"
        Kind.Custom -> "n:$every:" + when (unit) { Step.Day -> "d"; Step.Week -> "w"; Step.Month -> "m" }
    }

    /** The first occurrence strictly after [after], or null when the series is over. */
    fun next(anchor: LocalDateTime, after: LocalDateTime): LocalDateTime? {
        if (kind == Kind.None) return null
        val last = until?.let { it.plusDays(1).atStartOfDay() }
        val t = when (kind) {
            Kind.Daily -> step(anchor, after) { k -> anchor.plusDays(k) }
            Kind.Monthly -> step(anchor, after) { k -> anchor.plusMonths(k) }
            Kind.Custom -> step(anchor, after) { k ->
                val n = every.coerceAtLeast(1).toLong() * k
                when (unit) { Step.Day -> anchor.plusDays(n); Step.Week -> anchor.plusWeeks(n); Step.Month -> anchor.plusMonths(n) }
            }
            Kind.Days -> {
                if (days.isEmpty()) null
                else {
                    var d = maxOf(anchor.toLocalDate(), after.toLocalDate())
                    var found: LocalDateTime? = null
                    repeat(8) {
                        if (found == null) {
                            val c = d.atTime(anchor.toLocalTime())
                            if (d.dayOfWeek.value in days && c > after && c >= anchor) found = c
                            d = d.plusDays(1)
                        }
                    }
                    found
                }
            }
            Kind.None -> null
        }
        return t?.takeIf { last == null || it < last }
    }

    /** Smallest k ≥ 0 with f(k) > after; jumps close first so old anchors stay cheap. */
    private inline fun step(anchor: LocalDateTime, after: LocalDateTime, f: (Long) -> LocalDateTime): LocalDateTime {
        val span = when {
            kind == Kind.Monthly || kind == Kind.Custom && unit == Step.Month -> ChronoUnit.MONTHS.between(anchor, after) / every.coerceAtLeast(1)
            kind == Kind.Custom && unit == Step.Week -> ChronoUnit.WEEKS.between(anchor, after) / every.coerceAtLeast(1)
            kind == Kind.Custom -> ChronoUnit.DAYS.between(anchor, after) / every.coerceAtLeast(1)
            else -> ChronoUnit.DAYS.between(anchor, after)
        }
        var k = (span - 1).coerceAtLeast(0)
        while (f(k) <= after) k++
        return f(k)
    }

    companion object {
        val None = Repeat()

        fun parse(rule: String, until: String?): Repeat {
            val u = until?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val p = rule.split(":")
            return when (p[0]) {
                "d" -> Repeat(Kind.Daily, until = u)
                "m" -> Repeat(Kind.Monthly, until = u)
                "w" -> Repeat(Kind.Days, days = p.getOrNull(1).orEmpty().split(",").mapNotNull { it.toIntOrNull() }.toSet(), until = u)
                "n" -> Repeat(
                    Kind.Custom, every = p.getOrNull(1)?.toIntOrNull() ?: 1,
                    unit = when (p.getOrNull(2)) { "w" -> Step.Week; "m" -> Step.Month; else -> Step.Day }, until = u,
                )
                else -> None
            }
        }

        /** Hebrew weekday letters, Sunday first (index = ISO % 7). */
        val DAY_LETTERS = listOf("א׳", "ב׳", "ג׳", "ד׳", "ה׳", "ו׳", "ש׳")

        fun iso(d: DayOfWeek) = d.value
    }
}

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders")
    fun observeAll(): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders")
    suspend fun all(): List<ReminderEntity>

    @Query("SELECT r.* FROM reminders r JOIN notes n ON n.id = r.noteId WHERE n.trashedAt IS NULL")
    suspend fun live(): List<ReminderEntity>

    /** Re-emits whenever reminders or notes change — the scheduler's trigger. */
    @Query("SELECT COUNT(*) FROM reminders r JOIN notes n ON n.id = r.noteId WHERE n.trashedAt IS NULL")
    fun observeLiveCount(): Flow<Int>

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun get(id: String): ReminderEntity?

    @Query("SELECT * FROM reminders WHERE noteId = :noteId")
    suspend fun forNote(noteId: String): List<ReminderEntity>

    @Upsert
    suspend fun upsert(r: ReminderEntity)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE reminders SET sticky = :on, at = :at WHERE id = :id")
    suspend fun setSticky(id: String, on: Boolean, at: Long)

    @Query("UPDATE notes SET reminderAt = :at WHERE id = :noteId")
    suspend fun mirror(noteId: String, at: Long?)
}
