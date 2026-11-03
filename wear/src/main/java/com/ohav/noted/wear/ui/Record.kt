package com.ohav.noted.wear.ui

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ohav.noted.wear.Palette
import com.ohav.noted.wear.store
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** AAC in m4a (what the phone's player reads), the level every 50 ms for the waveform. */
class WatchRecorder(private val ctx: Context) {
    private var rec: MediaRecorder? = null
    val file = File(ctx.cacheDir, "rec-${System.currentTimeMillis()}.m4a")
    val levels = mutableStateListOf<Float>()
    private val all = ArrayList<Float>()
    private var started = 0L

    fun start(): Boolean = runCatching {
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioSamplingRate(16_000)
        r.setAudioEncodingBitRate(32_000)
        r.setAudioChannels(1)
        r.setOutputFile(file.absolutePath)
        r.prepare()
        r.start()
        rec = r
        started = System.currentTimeMillis()
    }.isSuccess

    val elapsed get() = if (started == 0L) 0L else System.currentTimeMillis() - started

    fun sample() {
        val amp = runCatching { rec?.maxAmplitude ?: 0 }.getOrDefault(0)
        val l = sqrt(amp / 32767f).coerceIn(0f, 1f)
        all += l
        levels += l
        if (levels.size > 48) levels.removeAt(0)
    }

    /** Stops; returns the length, or 0 if nothing usable was recorded. */
    fun stop(): Long {
        val ms = elapsed
        val ok = runCatching { rec?.stop() }.isSuccess
        runCatching { rec?.release() }
        rec = null
        return if (ok && ms > 600) ms else 0L
    }

    fun discard() { stop(); file.delete() }

    /** Peaks in [n] bars, 0..100 (the phone's waveform format). */
    fun bars(n: Int = 56): List<Int> {
        if (all.isEmpty()) return List(n) { 0 }
        return List(n) { i ->
            val a = i * all.size / n
            val b = max(a + 1, (i + 1) * all.size / n)
            var m = 0f
            for (k in a until min(b, all.size)) m = max(m, all[k])
            (m * 100).roundToInt()
        }
    }
}

/**
 * Recording: starts on arrival, a live waveform breathing around the time, one big stop.
 * Swiping back throws it away.
 */
@Composable
fun RecordScreen(into: String?, a: Actions, done: () -> Unit) {
    val ctx = LocalContext.current
    val s by ctx.store.state.collectAsState()
    val accent = Color(Palette.accent(s?.accent))
    val onAccent = Color(Palette.onAccent(s?.accent))
    val rec = remember { WatchRecorder(ctx) }
    var running by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(false) }
    var ms by remember { mutableLongStateOf(0L) }
    val h = rememberHaptics()

    LaunchedEffect(Unit) {
        running = rec.start()
        failed = !running
        if (running) h.confirm()
        while (running) {
            rec.sample()
            ms = rec.elapsed
            delay(50)
        }
    }
    DisposableEffect(Unit) { onDispose { if (!sent) rec.discard() } }

    val pulse by animateFloatAsState(rec.levels.lastOrNull() ?: 0f, Motion.touch(), label = "pulse")
    Box(Modifier.fillMaxSize().background(C.bg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(if (failed) "אין גישה למיקרופון" else mmss(ms), style = if (failed) body(14.sp, C.ink, 500, TextAlign.Center) else display(40.sp))
            if (failed) Spacer(Modifier.height(12.dp))
            if (!failed) {
                Canvas(Modifier.fillMaxWidth().height(52.dp)) {
                    val n = 48
                    val gap = size.width * .78f / n
                    val x0 = (size.width - gap * (n - 1)) / 2
                    val mid = size.height * .5f
                    val lv = rec.levels
                    for (i in 0 until n) {
                        val v = lv.getOrNull(lv.size - n + i) ?: 0f
                        val hgt = (size.height * .08f) + v * size.height * .9f
                        // RTL: the newest bar is on the left, the past drifts right
                        val x = size.width - (x0 + i * gap)
                        drawLine(accent.copy(alpha = .25f + .75f * (i / n.toFloat())), Offset(x, mid - hgt / 2), Offset(x, mid + hgt / 2), gap * .55f, StrokeCap.Round)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier.size(54.dp).graphicsLayer { val k = 1f + pulse * .12f; scaleX = k; scaleY = k }
                        .press {
                            running = false
                            val len = rec.stop()
                            if (len > 0) {
                                sent = true
                                ctx.store.recorded(rec.file, len, rec.bars(), into)
                                a.flash.show(if (into == null) "נשמר כפתק חדש" else "נוסף לפתק")
                            } else {
                                rec.discard()
                                a.flash.show("קצר מדי — לא נשמר", Ph.WarningCircle)
                            }
                            done()
                        }
                        .clip(CircleShape).background(accent),
                    contentAlignment = Alignment.Center,
                ) { Box(Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)).background(onAccent)) }
                Spacer(Modifier.height(4.dp))
                BasicText(if (into == null) "פתק חדש" else "לסוף הפתק", Modifier.fillMaxWidth().padding(horizontal = 20.dp), style = body(11.sp, C.ink3, 500, TextAlign.Center))
            } else {
                RoundAction(Ph.X, C.surface2, C.ink, 40.dp, onClick = done)
            }
        }
    }
}
