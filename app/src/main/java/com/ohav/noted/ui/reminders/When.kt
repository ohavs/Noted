package com.ohav.noted.ui.reminders

import androidx.compose.ui.graphics.vector.ImageVector
import com.ohav.noted.data.Repeat
import com.ohav.noted.design.Ph
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** Hebrew names and short formats shared by the picker, the agenda and the chips. */
object When {
    /** Sunday first; index = ISO day % 7. */
    val DAYS = listOf("א׳", "ב׳", "ג׳", "ד׳", "ה׳", "ו׳", "ש׳")
    val DAYS_FULL = listOf("ראשון", "שני", "שלישי", "רביעי", "חמישי", "שישי", "שבת")
    val MONTHS = listOf("ינואר", "פברואר", "מרץ", "אפריל", "מאי", "יוני", "יולי", "אוגוסט", "ספטמבר", "אוקטובר", "נובמבר", "דצמבר")

    fun day(d: LocalDate) = DAYS[d.dayOfWeek.value % 7]
    fun dayFull(d: LocalDate) = DAYS_FULL[d.dayOfWeek.value % 7]
    fun hm(t: LocalDateTime) = "%02d:%02d".format(t.hour, t.minute)

    /** "ד׳ 4/11 20:00" */
    fun short(t: LocalDateTime) = "${day(t.toLocalDate())} ${t.dayOfMonth}/${t.monthValue} ${hm(t)}"

    /** "בעוד 3 שעות" — or how long ago, for a time that passed. */
    fun until(t: LocalDateTime, now: LocalDateTime = LocalDateTime.now()): String {
        val past = t < now
        val d = if (past) Duration.between(t, now) else Duration.between(now, t)
        val min = d.toMinutes()
        val days = ChronoUnit.DAYS.between(now.toLocalDate(), t.toLocalDate()).let { if (past) -it else it }
        val s = when {
            min < 1 -> return if (past) "עכשיו" else "בעוד פחות מדקה"
            min < 60 -> if (min == 1L) "דקה" else "$min דק׳"
            min < 60 * 24 && days < 2 -> {
                val h = min / 60
                val m = min % 60
                val hs = when (h) { 1L -> "שעה"; 2L -> "שעתיים"; else -> "$h שעות" }
                if (m >= 5 && h < 6) "$hs ו-$m דק׳" else hs
            }
            days < 14 -> when (days) { 1L -> "יום"; 2L -> "יומיים"; else -> "$days ימים" }
            days < 60 -> "${days / 7} שבועות"
            else -> "${days / 30} חודשים"
        }
        return if (past) "לפני $s" else "בעוד $s"
    }

    fun repeat(r: Repeat): String {
        val base = when (r.kind) {
            Repeat.Kind.None -> return ""
            Repeat.Kind.Daily -> "כל יום"
            Repeat.Kind.Monthly -> "כל חודש"
            Repeat.Kind.Days -> if (r.days.size == 7) "כל יום" else "ימים " + r.days.sortedBy { it % 7 }.joinToString(" ") { DAYS[it % 7] }
            Repeat.Kind.Custom -> {
                val n = r.every.coerceAtLeast(1)
                when (r.unit) {
                    Repeat.Step.Day -> if (n == 1) "כל יום" else if (n == 2) "כל יומיים" else "כל $n ימים"
                    Repeat.Step.Week -> if (n == 1) "כל שבוע" else if (n == 2) "כל שבועיים" else "כל $n שבועות"
                    Repeat.Step.Month -> if (n == 1) "כל חודש" else if (n == 2) "כל חודשיים" else "כל $n חודשים"
                }
            }
        }
        return base + (r.until?.let { " · עד ${it.dayOfMonth}/${it.monthValue}" } ?: "")
    }

    class Shortcut(val key: String, val icon: ImageVector, val name: String, val at: LocalDateTime, val sub: String)

    fun shortcuts(now: LocalDateTime = LocalDateTime.now()): List<Shortcut> {
        val inHour = now.plusHours(1).let { t -> t.withSecond(0).withNano(0).let { it.plusMinutes(((5 - it.minute % 5) % 5).toLong()) } }
        val today = now.toLocalDate()
        val evening = if (now.hour < 20) today.atTime(20, 0) else today.plusDays(1).atTime(20, 0)
        val morning = today.plusDays(1).atTime(8, 0)
        val toSunday = ((7 - today.dayOfWeek.value % 7) % 7).let { if (it == 0) 7 else it }
        val week = today.plusDays(toSunday.toLong()).atTime(9, 0)
        return listOf(
            Shortcut("hour", Ph.ClockCountdown, "בעוד שעה", inHour, hm(inHour)),
            Shortcut("eve", Ph.MoonStars, if (now.hour < 20) "הערב" else "מחר בערב", evening, hm(evening)),
            Shortcut("morn", Ph.SunHorizon, "מחר בבוקר", morning, "08:00"),
            Shortcut("week", Ph.CalendarDots, "בשבוע הבא", week, "${day(week.toLocalDate())} ${hm(week)}"),
        )
    }

    /** Where a fresh picker starts: tonight at 20:00, or tomorrow morning once the evening has begun. */
    fun default(now: LocalDateTime = LocalDateTime.now()): LocalDateTime =
        if (now.hour < 19) now.toLocalDate().atTime(20, 0) else now.toLocalDate().plusDays(1).atTime(8, 0)
}
