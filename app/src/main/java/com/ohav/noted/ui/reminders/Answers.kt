package com.ohav.noted.ui.reminders

import android.content.SharedPreferences
import androidx.compose.ui.graphics.vector.ImageVector
import com.ohav.noted.data.PlaceEntity
import com.ohav.noted.data.PlaceKind
import com.ohav.noted.ui.places.icon

/** One ready answer in the reminder sheet: a time shortcut, or a place with its direction. */
internal class Answer(
    val key: String,
    val icon: ImageVector,
    val name: String,
    val sub: String,
    val time: When.Shortcut? = null,
    val place: PlaceEntity? = null,
    val leave: Boolean = false,
    val always: Boolean = false,
    val flip: Boolean = false,
)

/**
 * The sheet's ready answers, learned on this phone (prefs `reminder_ui`): `ans_<key>` counts each
 * answer, `dir_<place>` / `alw_<place>` keep a place's last direction and "every time".
 */
internal object Answers {
    private const val MAX = 6
    private val TIME_BASE = mapOf("eve" to 50, "morn" to 40, "hour" to 30, "week" to 10)

    fun list(prefs: SharedPreferences, places: List<PlaceEntity>, use: Map<String, Int>): List<Answer> {
        val scored = ArrayList<Pair<Answer, Int>>()
        When.shortcuts().forEach { s ->
            val key = "t:${s.key}"
            scored += Answer(key, s.icon, s.name, s.sub, time = s) to score(prefs, key, TIME_BASE[s.key] ?: 0)
        }
        places.forEach { p ->
            val kind = PlaceKind.of(p.kind)
            val always = prefs.getBoolean("alw_${p.id}", false)
            // its usual direction, plus the other one once it was used too
            val dirs = linkedSetOf(prefs.getBoolean("dir_${p.id}", false))
            if (prefs.getInt("ans_${placeKey(p.id, false)}", 0) > 0) dirs += false
            if (prefs.getInt("ans_${placeKey(p.id, true)}", 0) > 0) dirs += true
            dirs.forEach { leave ->
                val base = when {
                    kind == PlaceKind.Home && !leave -> 45
                    kind == PlaceKind.Work -> 25
                    kind == PlaceKind.Car -> 20
                    else -> minOf((use[p.id] ?: 0) * 2, 18)
                }
                val key = placeKey(p.id, leave)
                scored += Answer(
                    key, kind.icon(), p.name, trigger(p, kind, leave) + if (always) " · בכל פעם" else "",
                    place = p, leave = leave, always = always, flip = kind == PlaceKind.Car,
                ) to score(prefs, key, base)
            }
        }
        return scored.sortedByDescending { it.second }.take(MAX).map { it.first }
    }

    fun used(prefs: SharedPreferences, key: String) {
        prefs.edit().putInt("ans_$key", prefs.getInt("ans_$key", 0) + 1).apply()
    }

    /** Any place reminder set (ready answer or details) teaches the place's direction. */
    fun usedPlace(prefs: SharedPreferences, placeId: String, leave: Boolean, always: Boolean) {
        val key = "ans_" + placeKey(placeId, leave)
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).putBoolean("dir_$placeId", leave).putBoolean("alw_$placeId", always).apply()
    }

    private fun placeKey(id: String, leave: Boolean) = "p:$id:${if (leave) 1 else 0}"

    /** Use outweighs any default order; ties keep the default. */
    private fun score(prefs: SharedPreferences, key: String, base: Int) = prefs.getInt("ans_$key", 0) * 100 + base

    private fun trigger(p: PlaceEntity, kind: PlaceKind, leave: Boolean) = when {
        kind == PlaceKind.Car -> if (leave) "כשאצא" else "כשאכנס"
        p.isDevice -> if (leave) "בניתוק" else "בחיבור"
        else -> if (leave) "כשאצא" else "כשאגיע"
    }
}
