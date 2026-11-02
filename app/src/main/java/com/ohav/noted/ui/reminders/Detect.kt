package com.ohav.noted.ui.reminders

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Finds a time in Hebrew text ("מחר ב-5", "ביום שלישי בשעה 18:30", "הערב", "בעוד שעתיים") so the
 * editor can offer a reminder. Needs a day word or a relative phrase — a bare "ב-5" is too often
 * not a time. Returns the phrase that matched and the moment, only when it's in the future.
 */
object Detect {
    class Hit(val phrase: String, val at: LocalDateTime)

    private val WEEKDAYS = mapOf(
        "ראשון" to DayOfWeek.SUNDAY, "שני" to DayOfWeek.MONDAY, "שלישי" to DayOfWeek.TUESDAY, "רביעי" to DayOfWeek.WEDNESDAY,
        "חמישי" to DayOfWeek.THURSDAY, "שישי" to DayOfWeek.FRIDAY, "שבת" to DayOfWeek.SATURDAY,
    )
    private const val DAY = "(היום|מחרתיים|מחר|הערב|ב?יום\\s+(?:ראשון|שני|שלישי|רביעי|חמישי|שישי)|בשבת)"
    private const val PART = "(?:\\s+(בבוקר|בצהריים|בצהרים|אחה\"צ|אחר\\s+הצהריים|בערב|בלילה))?"
    private const val TIME = "(?:\\s+(?:ב-?|בשעה\\s+|ב\\s+)(\\d{1,2})(?::(\\d{2}))?)?"
    private val DAY_TIME = Regex("$DAY$TIME$PART")
    private val TIME_DAY = Regex("(?:בשעה\\s+|ב-?)(\\d{1,2})(?::(\\d{2}))?\\s+$DAY")
    private val AT_CLOCK = Regex("בשעה\\s+(\\d{1,2}):(\\d{2})")
    private val IN = Regex("בעוד\\s+(חצי\\s+שעה|רבע\\s+שעה|שעתיים|שעה|יומיים|(\\d{1,3})\\s+(דקות|דק׳|דק'|שעות|ימים))")

    fun find(text: String, now: LocalDateTime = LocalDateTime.now()): Hit? {
        IN.find(text)?.let { m -> inHit(m, now)?.let { return it } }
        TIME_DAY.find(text)?.let { m ->
            val day = dayOf(m.groupValues[3], now.toLocalDate()) ?: return@let
            build(m.value, day, m.groupValues[1], m.groupValues[2], "", m.groupValues[3], now)?.let { return it }
        }
        DAY_TIME.find(text)?.let { m ->
            val word = m.groupValues[1]
            val day = dayOf(word, now.toLocalDate()) ?: return@let
            val h = m.groupValues[2]
            val part = m.groupValues[4]
            // "מחר" alone isn't a time; "מחר בבוקר" / "הערב" / "מחר ב-5" are
            if (h.isEmpty() && part.isEmpty() && word != "הערב") return@let
            build(m.value, day, h, m.groupValues[3], part, word, now)?.let { return it }
        }
        AT_CLOCK.find(text)?.let { m ->
            val h = m.groupValues[1].toInt()
            val mi = m.groupValues[2].toInt()
            if (h > 23 || mi > 59) return null
            var t = now.toLocalDate().atTime(h, mi)
            if (t <= now) t = t.plusDays(1)
            return Hit(m.value, t)
        }
        return null
    }

    private fun inHit(m: MatchResult, now: LocalDateTime): Hit? {
        val what = m.groupValues[1]
        val t = when {
            what.startsWith("חצי") -> now.plusMinutes(30)
            what.startsWith("רבע") -> now.plusMinutes(15)
            what == "שעתיים" -> now.plusHours(2)
            what == "שעה" -> now.plusHours(1)
            what == "יומיים" -> now.plusDays(2)
            else -> {
                val n = m.groupValues[2].toLongOrNull() ?: return null
                when {
                    m.groupValues[3].startsWith("דק") -> now.plusMinutes(n)
                    m.groupValues[3] == "שעות" -> now.plusHours(n)
                    else -> now.plusDays(n)
                }
            }
        }
        return Hit(m.value, t.withSecond(0).withNano(0))
    }

    private fun dayOf(word: String, today: LocalDate): LocalDate? = when {
        word == "היום" || word == "הערב" -> today
        word == "מחר" -> today.plusDays(1)
        word == "מחרתיים" -> today.plusDays(2)
        word == "בשבת" -> next(today, DayOfWeek.SATURDAY)
        else -> WEEKDAYS.entries.firstOrNull { word.endsWith(it.key) }?.let { next(today, it.value) }
    }

    /** The coming [d] — today counts only when it's today and the time hasn't passed (checked later). */
    private fun next(today: LocalDate, d: DayOfWeek): LocalDate {
        val diff = (d.value - today.dayOfWeek.value + 7) % 7
        return today.plusDays(diff.toLong())
    }

    private fun build(phrase: String, day: LocalDate, hs: String, ms: String, part: String, word: String, now: LocalDateTime): Hit? {
        val evening = part == "בערב" || part == "בלילה" || word == "הערב"
        val afternoon = part.startsWith("בצהר") || part.startsWith("אח")
        var h: Int
        val mi = ms.toIntOrNull() ?: 0
        if (hs.isEmpty()) {
            h = when {
                evening -> 20
                afternoon -> 14
                part == "בבוקר" -> 8
                else -> return null
            }
        } else {
            h = hs.toInt()
            if (h > 23 || mi > 59) return null
            if (h < 12 && (evening || afternoon)) h += 12
            // "מחר ב-5" means five in the afternoon, not before dawn
            else if (h in 1..7 && ms.isEmpty() && part != "בבוקר") h += 12
        }
        var at = day.atTime(h % 24, mi)
        // a weekday that already passed today → next week
        val weekday = word == "בשבת" || word.contains("יום") && word != "היום"
        if (at <= now && weekday) at = at.plusWeeks(1)
        if (at <= now) return null
        return Hit(phrase.trim(), at)
    }
}
