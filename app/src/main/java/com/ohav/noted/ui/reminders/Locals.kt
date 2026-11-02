package com.ohav.noted.ui.reminders

import androidx.compose.runtime.compositionLocalOf
import com.ohav.noted.data.ReminderEntity

/** Every reminder by id (see [ReminderEntity.idFor]) — cards and the editor read their chips from it. */
val LocalReminders = compositionLocalOf<Map<String, ReminderEntity>> { emptyMap() }

/** "מחר 08:00 · כל יום" for a chip. */
fun ReminderEntity.chip(): String = com.ohav.noted.ui.home.fmtRel(occAt) + When.repeat(repeat).let { if (it.isEmpty()) "" else " · $it" }

/** Opens the reminder picker from inside the editor: for the note (`""`) or for one line. */
val LocalRemind = androidx.compose.runtime.staticCompositionLocalOf<(blockId: String, line: String?) -> Unit> { { _, _ -> } }
