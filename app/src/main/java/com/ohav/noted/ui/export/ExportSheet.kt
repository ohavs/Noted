package com.ohav.noted.ui.export

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ohav.noted.NotedApp
import com.ohav.noted.backup.NoteExport
import com.ohav.noted.design.Motion
import com.ohav.noted.design.NoteColor
import com.ohav.noted.design.Noted
import com.ohav.noted.design.Overlays
import com.ohav.noted.design.Ph
import com.ohav.noted.design.SheetRow
import com.ohav.noted.design.Squircle
import com.ohav.noted.design.ToastState
import com.ohav.noted.design.press
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private enum class Format(val label: String, val hint: String) {
    Pdf("PDF", "להדפסה ולשליחה"),
    Text("טקסט", "להדבקה בצ'אט"),
    Md("Markdown", "לעורכי טקסט"),
}

/** ⋮ → ייצוא: the note as a PDF, as chat-ready text or as Markdown, or straight to the clipboard. */
fun openExportSheet(app: NotedApp, overlays: Overlays, toasts: ToastState, scope: CoroutineScope, doc: suspend () -> NoteExport.Doc) {
    overlays.sheet.show("ייצוא") { ExportBody(app, overlays, toasts, scope, doc) }
}

@Composable
private fun ColumnScope.ExportBody(app: NotedApp, overlays: Overlays, toasts: ToastState, scope: CoroutineScope, doc: suspend () -> NoteExport.Doc) {
    val c = Noted.colors
    val t = Noted.type
    val haptics = Noted.haptics
    val ctx = LocalContext.current
    var busy by remember { mutableStateOf<Format?>(null) }
    var paper by remember { mutableStateOf<NoteColor?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) { paper = doc().color }

    fun go(f: Format) {
        if (busy != null) return
        haptics.light()
        busy = f
        scope.launch {
            val d = doc()
            runCatching {
                when (f) {
                    Format.Pdf -> NoteExport.shareFile(ctx, NoteExport.pdf(app, d), "application/pdf", d.title)
                    Format.Text -> NoteExport.shareText(ctx, NoteExport.text(d), d.title)
                    Format.Md -> {
                        val file = NoteExport.outFile(app, d.title, "md").apply { writeText(NoteExport.markdown(d)) }
                        NoteExport.shareFile(ctx, file, "text/markdown", d.title)
                    }
                }
            }.onSuccess { overlays.sheet.close() }
                .onFailure { haptics.reject(); toasts.show("הייצוא לא הצליח", Ph.WarningCircle) }
            busy = null
        }
    }

    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Format.entries.forEach { f ->
            FormatCard(f, c.note(paper ?: NoteColor.Sand), busy == f, dim = busy != null && busy != f, modifier = Modifier.weight(1f)) { go(f) }
        }
    }
    Spacer(Modifier.height(10.dp))
    SheetRow(Ph.CopySimple, "העתקה כטקסט", last = true) {
        scope.launch {
            val d = doc()
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText(d.title, NoteExport.text(d)))
            haptics.confirm()
            overlays.sheet.close()
            toasts.show("הפתק הועתק", Ph.CopySimple, millis = 1600)
        }
    }
    Text(
        "תמונות, ציורים והקלטות נכנסים ל-PDF. בטקסט וב-Markdown הם מסומנים במקומם.",
        style = t.caption, color = c.ink3, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
    )
}

/** One format as a little picture of what comes out: a printed page, a chat bubble, a marked-up page. */
@Composable
private fun FormatCard(f: Format, paper: Color, busy: Boolean, dim: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Noted.colors
    val t = Noted.type
    val lift by animateFloatAsState(if (busy) 1f else 0f, Motion.touch(), label = "fmtBusy")
    val fade by animateFloatAsState(if (dim) .45f else 1f, Motion.glide(), label = "fmtDim")
    Column(
        modifier.graphicsLayer { alpha = fade }.press(scale = .93f, haptic = false, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.fillMaxWidth().height(118.dp).clip(Squircle(20.dp)).background(c.bg2),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.graphicsLayer { translationY = -6.dp.toPx() * lift; scaleX = 1f + .04f * lift; scaleY = 1f + .04f * lift }) {
                when (f) {
                    Format.Pdf -> PagePic(paper)
                    Format.Text -> BubblePic()
                    Format.Md -> MarkPic()
                }
            }
        }
        Text(f.label, style = t.bodyStrong, color = c.ink, modifier = Modifier.padding(top = 8.dp))
        Text(if (busy) "מכין…" else f.hint, style = t.caption, color = c.ink3, maxLines = 1)
    }
}

@Composable
private fun Lines(color: Color, widths: List<Float>, gap: Int = 5, h: Int = 4) {
    Column(verticalArrangement = Arrangement.spacedBy(gap.dp)) {
        widths.forEach { w -> Box(Modifier.fillMaxWidth(w).height(h.dp).clip(RoundedCornerShape(2.dp)).background(color)) }
    }
}

/** A white A4 sheet with the note's coloured title band. */
@Composable
private fun PagePic(paper: Color) {
    val ink = Color(0xFF1F1B16)
    Column(
        Modifier.width(58.dp).aspectRatio(.71f).clip(RoundedCornerShape(5.dp)).background(Color.White).padding(6.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(17.dp).clip(RoundedCornerShape(3.dp)).background(paper).padding(start = 4.dp, top = 4.dp)) {
            Box(Modifier.fillMaxWidth(.6f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(ink.copy(alpha = .7f)))
        }
        Spacer(Modifier.height(6.dp))
        Lines(ink.copy(alpha = .22f), listOf(1f, .85f, .95f, .55f), gap = 4, h = 3)
        Spacer(Modifier.height(5.dp))
        Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(3.dp)).background(paper.copy(alpha = .7f)))
    }
}

/** A chat bubble with a list in it. */
@Composable
private fun BubblePic() {
    val c = Noted.colors
    Column(
        Modifier.width(70.dp).clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomStart = 14.dp, bottomEnd = 4.dp))
            .background(c.accentSoft).padding(horizontal = 9.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.fillMaxWidth(.55f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.accent))
        listOf(.9f, .7f, .8f).forEach { w ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(5.dp).clip(RoundedCornerShape(1.5.dp)).background(c.accent.copy(alpha = .6f)))
                Spacer(Modifier.width(4.dp))
                Box(Modifier.fillMaxWidth(w).height(3.dp).clip(RoundedCornerShape(2.dp)).background(c.accent.copy(alpha = .45f)))
            }
        }
    }
}

/** A page whose lines start with # and -. */
@Composable
private fun MarkPic() {
    val c = Noted.colors
    val mono = Noted.type.caption.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 9.sp, lineHeight = 9.sp)
    Column(
        Modifier.width(58.dp).aspectRatio(.71f).clip(RoundedCornerShape(5.dp)).background(c.surface).padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf("#" to .7f, "" to .95f, "-" to .8f, "-" to .6f, ">" to .85f).forEach { (m, w) ->
            Row(Modifier.height(9.dp), verticalAlignment = Alignment.CenterVertically) {
                if (m.isNotEmpty()) {
                    Text(m, style = mono, color = c.accent)
                    Spacer(Modifier.width(3.dp))
                }
                Box(Modifier.fillMaxWidth(w).height(3.dp).clip(RoundedCornerShape(2.dp)).background(c.ink3.copy(alpha = .5f)))
            }
        }
    }
}

