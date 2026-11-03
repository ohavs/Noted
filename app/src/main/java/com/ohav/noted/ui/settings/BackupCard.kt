package com.ohav.noted.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ohav.noted.NotedApp
import com.ohav.noted.backup.Backup
import com.ohav.noted.data.Privacy
import com.ohav.noted.design.LocalOverlays
import com.ohav.noted.design.Motion
import com.ohav.noted.design.Noted
import com.ohav.noted.design.Ph
import com.ohav.noted.design.Shapes
import com.ohav.noted.design.Squircle
import com.ohav.noted.design.ToastState
import com.ohav.noted.design.press
import com.ohav.noted.ui.lock.LocalLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private sealed interface BackupJob {
    data object Idle : BackupJob
    data class Writing(val p: Float) : BackupJob
    data class Restoring(val p: Float) : BackupJob
}

/** Settings → גיבוי: everything into one `.noted` file wherever the person picks, and back. */
@Composable
internal fun BackupCard(app: NotedApp, toasts: ToastState) {
    val c = Noted.colors
    val t = Noted.type
    val haptics = Noted.haptics
    val overlays = LocalOverlays.current
    val lock = LocalLock.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val flags by app.privacy.flags.collectAsStateWithLifecycle()
    var job by remember { mutableStateOf<BackupJob>(BackupJob.Idle) }
    var last by remember { mutableLongStateOf(app.backup.lastAt) }

    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(Backup.MIME)) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        job = BackupJob.Writing(0f)
        app.scope.launch {
            val r = runCatching {
                ctx.contentResolver.openOutputStream(uri, "wt")!!.use { out -> app.backup.write(out) { p -> job = BackupJob.Writing(p) } }
            }
            job = BackupJob.Idle
            r.onSuccess { s ->
                last = app.backup.lastAt
                haptics.confirm()
                toasts.show("גובו ${s.notes} פתקים${if (s.files > 0) " ו-${s.files} קבצים" else ""}", Ph.CheckCircleFill)
            }.onFailure {
                haptics.reject()
                toasts.show("הגיבוי לא נשמר", Ph.WarningCircle)
            }
        }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        app.scope.launch {
            val s = runCatching { app.backup.peek(uri) }.getOrElse {
                haptics.reject()
                toasts.show("זה לא קובץ גיבוי של noted", Ph.WarningCircle)
                return@launch
            }
            val ok = overlays.dialog.confirm(
                title = "לשחזר מהגיבוי?",
                text = "${counts(s)}\nמ-${date(s.at)}\n\nמה שחסר כאן יתווסף, ופתק שהשתנה בגיבוי אחרי השינוי האחרון כאן יעודכן. שום דבר לא נמחק.",
                icon = Ph.ClockCounterClockwise, confirm = "שחזור", cancel = "לא עכשיו",
            )
            if (!ok) return@launch
            job = BackupJob.Restoring(0f)
            val r = runCatching { app.backup.restore(uri) { p -> job = BackupJob.Restoring(p) } }
            job = BackupJob.Idle
            r.onSuccess { res ->
                haptics.confirm()
                val msg = when {
                    res.added == 0 && res.updated == 0 -> "הכל כבר כאן — לא היה מה להוסיף"
                    res.updated == 0 -> "שוחזרו ${res.added} פתקים"
                    res.added == 0 -> "עודכנו ${res.updated} פתקים"
                    else -> "שוחזרו ${res.added} פתקים · עודכנו ${res.updated}"
                }
                toasts.show(msg, Ph.CheckCircleFill, millis = 3200)
            }.onFailure {
                haptics.reject()
                toasts.show(if (it is Backup.BadFile) "זה לא קובץ גיבוי של noted" else "השחזור לא הצליח", Ph.WarningCircle)
            }
        }
    }

    // "לפני 3 ימים" stays current while the screen is open
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); now = System.currentTimeMillis() } }

    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(Shapes.lg).background(c.surface).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(Squircle(15.dp)).background(c.bg2), contentAlignment = Alignment.Center) {
                Icon(Ph.HardDrives, null, Modifier.size(21.dp), tint = c.ink2)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("גיבוי לקובץ", style = t.bodyStrong, color = c.ink)
                Text(if (last == 0L) "עוד לא נשמר גיבוי" else "גיבוי אחרון: ${since(last, now)}", style = t.caption, color = c.ink2)
            }
        }
        Text(
            "כל הפתקים, הקטגוריות, התזכורות, התמונות וההקלטות בקובץ אחד — לשמירה בטלפון או בדרייב.",
            style = t.caption, color = c.ink3, modifier = Modifier.padding(top = 12.dp, bottom = 14.dp),
        )
        AnimatedContent(
            targetState = job,
            contentKey = { it::class },
            transitionSpec = { fadeIn(Motion.glide()) togetherWith fadeOut(Motion.glide()) },
            label = "backupJob",
        ) { j ->
            when (j) {
                BackupJob.Idle -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Action("גיבוי עכשיו", Ph.DownloadSimple, primary = true, modifier = Modifier.weight(1f)) {
                        val go = { create.launch(app.backup.fileName()) }
                        val locked = flags.values.any { it and Privacy.LOCK != 0 }
                        if (locked && app.privacy.hasCode) lock.ask("הגיבוי כולל פתקים נעולים") { go() } else go()
                    }
                    Action("שחזור מקובץ", Ph.FileArrowUp, primary = false, modifier = Modifier.weight(1f)) {
                        pick.launch(arrayOf("application/octet-stream", "application/zip", "*/*"))
                    }
                }
                is BackupJob.Writing -> Running("שומר את הגיבוי…", j.p)
                is BackupJob.Restoring -> Running("משחזר…", j.p)
            }
        }
    }
}

@Composable
private fun Action(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, primary: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Noted.colors
    Row(
        modifier.height(52.dp).clip(Shapes.md).background(if (primary) c.accent else c.bg2).press(scale = .96f, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        Icon(icon, null, Modifier.size(19.dp), tint = if (primary) c.accentInk else c.ink)
        Text(text, style = Noted.type.label, color = if (primary) c.accentInk else c.ink, maxLines = 1)
    }
}

@Composable
private fun Running(label: String, p: Float) {
    val c = Noted.colors
    val w by animateFloatAsState(p, Motion.glide(), label = "backupP")
    Column(Modifier.fillMaxWidth().height(52.dp), verticalArrangement = Arrangement.Center) {
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
            Text(label, style = Noted.type.label, color = c.ink, modifier = Modifier.weight(1f))
            Text("${(p * 100).roundToInt()}%", style = Noted.type.caption, color = c.ink2)
        }
        Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(c.bg2)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(w.coerceIn(0.03f, 1f)).clip(CircleShape).background(c.accent))
        }
    }
}

private fun counts(s: Backup.Summary) = buildList {
    add("${s.notes} פתקים")
    if (s.categories > 0) add("${s.categories} קטגוריות")
    if (s.files > 0) add("${s.files} קבצים")
}.joinToString(" · ")

private fun date(at: Long): String =
    DateTimeFormatter.ofPattern("d 'ב'MMMM yyyy, HH:mm", Locale.forLanguageTag("he")).format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))

private fun since(at: Long, now: Long): String {
    val zone = ZoneId.systemDefault()
    val d = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    val days = java.time.temporal.ChronoUnit.DAYS.between(d, today).toInt()
    val time = DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(at).atZone(zone))
    return when {
        now - at < 60_000 -> "עכשיו"
        days == 0 -> "היום, $time"
        days == 1 -> "אתמול, $time"
        days < 7 -> "לפני $days ימים"
        else -> DateTimeFormatter.ofPattern("d 'ב'MMMM", Locale.forLanguageTag("he")).format(d)
    }
}
