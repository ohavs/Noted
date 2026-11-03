package com.ohav.noted.backup

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.MetricAffectingSpan
import android.text.style.StrikethroughSpan
import android.text.style.UnderlineSpan
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.FileProvider
import com.ohav.noted.R
import com.ohav.noted.data.Block
import com.ohav.noted.data.BlockType
import com.ohav.noted.data.Media
import com.ohav.noted.data.MediaExtra
import com.ohav.noted.data.Span
import com.ohav.noted.design.NoteColor
import com.ohav.noted.ui.editor.Ink
import com.ohav.noted.ui.editor.Rich
import com.ohav.noted.ui.media.StrokeFile
import com.ohav.noted.ui.media.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** A note as something to send away: readable text, Markdown, or a printable PDF. */
object NoteExport {
    class Doc(val title: String, val blocks: List<Block>, val color: NoteColor, val category: String, val updatedAt: Long)

    // ------------------------------------------------------------------ text

    /** Plain text that reads well in a chat: bullets, ☐ / ☑, numbers, no markup. */
    fun text(d: Doc): String = buildString {
        if (d.title.isNotBlank()) append(d.title.trim()).append("\n\n")
        lines(d.blocks) { b, num ->
            val pad = "  ".repeat(b.indent)
            when (b.type) {
                BlockType.Bullet -> "$pad• ${b.text}"
                BlockType.Num -> "$pad$num. ${b.text}"
                BlockType.Check -> "$pad${if (b.done) "☑" else "☐"} ${b.text}"
                BlockType.Quote -> "❝ ${b.text}"
                BlockType.Divider -> "———"
                else -> media(b, md = false) ?: b.text
            }
        }
    }.trimEnd()

    /** Markdown: headings, lists, tasks, quotes and inline bold / italic / strike / links. */
    fun markdown(d: Doc): String = buildString {
        if (d.title.isNotBlank()) append("# ").append(d.title.trim()).append("\n\n")
        lines(d.blocks, gap = true) { b, num ->
            val pad = "  ".repeat(b.indent)
            val t = marks(b.text, b.spans)
            when (b.type) {
                BlockType.H1 -> "## $t"
                BlockType.H2 -> "### $t"
                BlockType.H3 -> "#### $t"
                BlockType.Bullet -> "$pad- $t"
                BlockType.Num -> "$pad$num. $t"
                BlockType.Check -> "$pad- [${if (b.done) "x" else " "}] $t"
                BlockType.Quote -> "> $t"
                BlockType.Divider -> "---"
                else -> media(b, md = true) ?: t
            }
        }
    }.trimEnd() + "\n"

    /** Walks the blocks with list numbering; list items stay together, other blocks get a blank line in Markdown. */
    private inline fun StringBuilder.lines(blocks: List<Block>, gap: Boolean = false, line: (Block, Int) -> String) {
        val counters = IntArray(4)
        var prev: BlockType? = null
        for (b in blocks) {
            if (b.type == BlockType.Num) {
                if (prev != BlockType.Num && prev != BlockType.Bullet && prev != BlockType.Check) counters.fill(0)
                val lvl = b.indent.coerceIn(0, 3)
                counters[lvl]++
                for (i in lvl + 1..3) counters[i] = 0
            } else if (b.type !in LISTS) counters.fill(0)
            val list = b.type in LISTS
            if (gap && prev != null && !(list && prev in LISTS)) append('\n')
            append(line(b, counters[b.indent.coerceIn(0, 3)])).append('\n')
            prev = b.type
        }
    }

    private val LISTS = setOf(BlockType.Bullet, BlockType.Num, BlockType.Check)

    private fun media(b: Block, md: Boolean): String? = when (b.type) {
        BlockType.Images -> MediaExtra.pics(b.extra).size.let { n -> if (n == 1) "[תמונה]" else "[$n תמונות]" }.let { if (md) "_${it}_" else it }
        BlockType.Draw -> if (md) "_[ציור]_" else "[ציור]"
        BlockType.Voice -> MediaExtra.voice(b.extra)?.let { v ->
            val head = "🎙 הקלטה ${Media.fmtMs(v.ms)}"
            when {
                v.text.isBlank() -> if (md) "_${head}_" else head
                md -> "_${head}_\n\n> ${v.text.trim()}"
                else -> "$head\n${v.text.trim()}"
            }
        } ?: ""
        BlockType.Link -> MediaExtra.link(b.extra)?.let { l ->
            val name = l.title.ifBlank { l.site }.trim()
            if (md) (if (name.isEmpty()) "<${l.url}>" else "[$name](${l.url})") else listOf(name, l.url).filter { it.isNotEmpty() }.joinToString("\n")
        } ?: ""
        else -> null
    }

    /** Markdown inline marks over [text]: the text is cut at every span edge and each piece wrapped on its own. */
    private fun marks(text: String, spans: List<Span>): String {
        if (spans.isEmpty()) return escape(text)
        val len = text.length
        val cuts = sortedSetOf(0, len).apply { spans.forEach { add(it.start.coerceIn(0, len)); add(it.end.coerceIn(0, len)) } }.toList()
        val out = StringBuilder()
        for (i in 0 until cuts.size - 1) {
            val a = cuts[i]
            val e = cuts[i + 1]
            val piece = text.substring(a, e)
            val on = spans.filter { it.start <= a && it.end >= e && it.start < it.end }.map { it.style }
            val core = piece.trim()
            if (core.isEmpty() || on.isEmpty()) { out.append(escape(piece)); continue }
            val lead = piece.substring(0, piece.indexOf(core[0]))
            val trail = piece.substring(lead.length + core.length)
            var w = escape(core)
            if (Rich.STRIKE in on) w = "~~$w~~"
            if (Rich.ITALIC in on) w = "_${w}_"
            if (Rich.BOLD in on) w = "**$w**"
            on.firstOrNull { it.startsWith(Rich.LINK) }?.let { w = "[$w](${it.drop(Rich.LINK.length)})" }
            out.append(lead).append(w).append(trail)
        }
        return out.toString()
    }

    private fun escape(s: String) = s.replace(Regex("([\\\\`*_\\[\\]])"), "\\\\$1")

    // ------------------------------------------------------------------ PDF

    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MX = 54f
    private const val TOP = 56f
    private const val BOTTOM = 62f
    private const val CW = PAGE_W - 2 * MX

    private const val INK = 0xFF1F1B16.toInt()
    private const val INK2 = 0xFF5E574D.toInt()
    private const val INK3 = 0xFF9A9286.toInt()
    private const val LINE = 0xFFE7E1D7.toInt()
    private const val WELL = 0xFFF6F2EA.toInt()

    private class Fonts(val body: Typeface, val display: Typeface, val displayBold: Typeface)

    /** Variable Heebo at a given weight — a span, so bold inside a line is the real cut, not smeared. */
    private class Weight(val w: Int) : MetricAffectingSpan() {
        override fun updateDrawState(tp: TextPaint) = set(tp)
        override fun updateMeasureState(tp: TextPaint) = set(tp)
        private fun set(tp: TextPaint) { tp.setFontVariationSettings("'wght' $w") }
    }

    /** Hebrew has no italic cut: a slant, as the editor does. */
    private class Slant : MetricAffectingSpan() {
        override fun updateDrawState(tp: TextPaint) { tp.textSkewX = -0.18f }
        override fun updateMeasureState(tp: TextPaint) { tp.textSkewX = -0.18f }
    }

    /** Writes the note as an A4 PDF into the cache and returns the file. */
    suspend fun pdf(ctx: Context, d: Doc): File = withContext(Dispatchers.IO) {
        val fonts = Fonts(
            ctx.resources.getFont(R.font.heebo),
            ctx.resources.getFont(R.font.karantina_regular),
            ctx.resources.getFont(R.font.karantina_bold),
        )
        val doc = PdfDocument()
        val pager = Pager(doc)
        try {
            header(pager, d, fonts)
            body(ctx, pager, d.blocks, fonts)
            pager.finish()
            val f = outFile(ctx, d.title, "pdf")
            f.outputStream().use { doc.writeTo(it) }
            f
        } finally {
            doc.close()
        }
    }

    /** Pages, the pen's place on the current one, and a footer on each. */
    private class Pager(val doc: PdfDocument) {
        var page: PdfDocument.Page? = null
        var n = 0
        var y = TOP
        val canvas: Canvas get() = page!!.canvas
        val room: Float get() = PAGE_H - BOTTOM - y

        fun open() {
            page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, ++n).create())
            y = TOP
        }

        fun ensure(h: Float) { if (page == null) open() else if (h > room && y > TOP + 1f) { close(); open() } }

        fun close() {
            val p = page ?: return
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = INK3; textSize = 8.5f; textAlign = Paint.Align.CENTER }
            p.canvas.drawText("$n", PAGE_W / 2f, PAGE_H - 30f, paint)
            doc.finishPage(p)
            page = null
        }

        fun finish() { if (page == null) open(); close() }
    }

    private fun paint(size: Float, face: Typeface, color: Int = INK) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = face; textSize = size; this.color = color
    }

    private fun layout(text: CharSequence, p: TextPaint, w: Float, spacing: Float = 1.4f): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, p, w.toInt().coerceAtLeast(10))
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_RTL)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, spacing)
            .setIncludePad(false)
            .build()

    private fun StaticLayout.rtl() = lineCount > 0 && getParagraphDirection(0) == Layout.DIR_RIGHT_TO_LEFT

    /** The title on a band of the note's own colour, with category and date under it. */
    private fun header(pg: Pager, d: Doc, f: Fonts) {
        pg.open()
        val title = d.title.trim().ifEmpty { "פתק" }
        val tl = layout(title, paint(40f, f.displayBold), CW - 36f, 1.0f)
        val date = DateTimeFormatter.ofPattern("d 'ב'MMMM yyyy", Locale.forLanguageTag("he"))
            .format(Instant.ofEpochMilli(d.updatedAt).atZone(ZoneId.systemDefault()))
        val meta = listOf(d.category, date).filter { it.isNotBlank() }.joinToString("  ·  ")
        val ml = layout(meta, paint(10f, f.body, INK2).apply { setFontVariationSettings("'wght' 500") }, CW - 36f)
        val h = 26f + tl.height + 8f + ml.height + 22f
        val c = pg.canvas
        c.drawRoundRect(RectF(MX, pg.y, MX + CW, pg.y + h), 18f, 18f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = d.color.light.toArgb() })
        c.save(); c.translate(MX + 18f, pg.y + 26f); tl.draw(c); c.restore()
        c.save(); c.translate(MX + 18f, pg.y + 26f + tl.height + 8f); ml.draw(c); c.restore()
        pg.y += h + 22f
    }

    private suspend fun body(ctx: Context, pg: Pager, blocks: List<Block>, f: Fonts) {
        val counters = IntArray(4)
        var prev: BlockType? = null
        for (b in blocks) {
            if (b.type == BlockType.Num) {
                if (prev !in LISTS) counters.fill(0)
                val lvl = b.indent.coerceIn(0, 3)
                counters[lvl]++
                for (i in lvl + 1..3) counters[i] = 0
            } else if (b.type !in LISTS) counters.fill(0)
            val gap = when {
                prev == null -> 0f
                b.type in LISTS && prev in LISTS -> 3f
                b.type == BlockType.H1 || b.type == BlockType.H2 -> 16f
                b.type == BlockType.H3 -> 12f
                else -> 9f
            }
            pg.y += gap
            when (b.type) {
                BlockType.Divider -> {
                    pg.ensure(14f)
                    pg.canvas.drawRect(MX, pg.y + 6f, MX + CW, pg.y + 7f, Paint().apply { color = LINE })
                    pg.y += 13f
                }
                BlockType.Images -> images(ctx, pg, b)
                BlockType.Draw -> drawing(ctx, pg, b)
                BlockType.Voice -> voice(pg, b, f)
                BlockType.Link -> link(pg, b, f)
                else -> textBlock(pg, b, f, counters[b.indent.coerceIn(0, 3)])
            }
            prev = b.type
        }
    }

    private fun styled(b: Block, base: Int): CharSequence {
        val s = SpannableStringBuilder(b.text)
        val len = b.text.length
        s.setSpan(Weight(base), 0, len, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        b.spans.forEach { sp ->
            val a = sp.start.coerceIn(0, len)
            val e = sp.end.coerceIn(0, len)
            if (a >= e) return@forEach
            val what: Any? = when {
                sp.style == Rich.BOLD -> Weight(700)
                sp.style == Rich.ITALIC -> Slant()
                sp.style == Rich.UNDER -> UnderlineSpan()
                sp.style == Rich.STRIKE -> StrikethroughSpan()
                sp.style.startsWith("c:") -> Ink.of(sp.style.drop(2))?.let { ForegroundColorSpan(it.light.toArgb()) }
                sp.style.startsWith("h:") -> Ink.of(sp.style.drop(2))?.let { BackgroundColorSpan(it.markLight.toArgb()) }
                sp.style.startsWith(Rich.LINK) -> ForegroundColorSpan(Ink.Blue.light.toArgb())
                else -> null
            }
            what?.let { s.setSpan(it, a, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            if (sp.style.startsWith(Rich.LINK)) s.setSpan(UnderlineSpan(), a, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (b.type == BlockType.Check && b.done) {
            s.setSpan(StrikethroughSpan(), 0, len, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            s.setSpan(ForegroundColorSpan(INK3), 0, len, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return s
    }

    /** Text rows: paragraphs, headings, quotes and list items; long ones flow onto the next page line by line. */
    private fun textBlock(pg: Pager, b: Block, f: Fonts, num: Int) {
        val list = b.type in LISTS
        val indent = if (list) 18f + b.indent.coerceIn(0, 3) * 18f else if (b.type == BlockType.Quote) 16f else 0f
        val (p, weight) = when (b.type) {
            BlockType.H1 -> paint(26f, f.displayBold) to 0
            BlockType.H2 -> paint(21f, f.displayBold) to 0
            BlockType.H3 -> paint(13.5f, f.body) to 700
            BlockType.Quote -> paint(11.5f, f.body, INK2) to 400
            else -> paint(11.5f, f.body) to 400
        }
        val text: CharSequence = if (weight == 0) b.text else styled(b, weight)
        if (text.isEmpty()) { pg.ensure(p.textSize * 1.4f); pg.y += p.textSize * 1.4f; return }
        val l = layout(text, p, CW - indent, if (b.type == BlockType.H1 || b.type == BlockType.H2) 1.05f else 1.42f)
        val rtl = l.rtl()
        val x = if (rtl) MX else MX + indent
        var from = 0
        var first = true
        while (from < l.lineCount) {
            pg.ensure(l.getLineBottom(from) - l.getLineTop(from) + 0f)
            // as many lines as fit here
            var to = from
            val top = l.getLineTop(from)
            while (to < l.lineCount && l.getLineBottom(to) - top <= pg.room) to++
            if (to == from) to = from + 1
            val h = (l.getLineBottom(to - 1) - top).toFloat()
            val c = pg.canvas
            c.save()
            c.clipRect(0f, pg.y, PAGE_W.toFloat(), pg.y + h)
            c.translate(x, pg.y - top)
            l.draw(c)
            c.restore()
            if (first) marker(c, b, num, l, rtl, x, pg.y, f)
            if (b.type == BlockType.Quote) {
                val bx = if (rtl) MX + CW - 3f else MX
                c.drawRoundRect(RectF(bx, pg.y + 2f, bx + 3f, pg.y + h - 2f), 1.5f, 1.5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LINE })
            }
            pg.y += h
            first = false
            from = to
        }
    }

    /** Bullet dot, number or checkbox beside the first line, on the line's start side. */
    private fun marker(c: Canvas, b: Block, num: Int, l: StaticLayout, rtl: Boolean, x: Float, y: Float, f: Fonts) {
        if (b.type !in LISTS) return
        val mid = y + (l.getLineTop(0) + l.getLineBaseline(0)) / 2f + 1f
        val cx = if (rtl) x + l.width + 9f else x - 9f
        val pt = Paint(Paint.ANTI_ALIAS_FLAG)
        when (b.type) {
            BlockType.Bullet -> { pt.color = INK2; c.drawCircle(cx, mid, if (b.indent == 0) 2.4f else 2f, pt) }
            BlockType.Num -> {
                val tp = paint(11.5f, f.body, INK2).apply { setFontVariationSettings("'wght' 600"); textAlign = Paint.Align.CENTER }
                c.drawText("$num.", cx, y + l.getLineBaseline(0), tp)
            }
            BlockType.Check -> {
                val r = RectF(cx - 5.5f, mid - 5.5f, cx + 5.5f, mid + 5.5f)
                if (b.done) {
                    pt.color = INK; c.drawRoundRect(r, 3f, 3f, pt)
                    pt.color = 0xFFFFFFFF.toInt(); pt.style = Paint.Style.STROKE; pt.strokeWidth = 1.5f; pt.strokeCap = Paint.Cap.ROUND; pt.strokeJoin = Paint.Join.ROUND
                    c.drawPath(Path().apply { moveTo(cx - 2.8f, mid + .2f); lineTo(cx - .7f, mid + 2.3f); lineTo(cx + 3f, mid - 2.2f) }, pt)
                } else {
                    pt.color = INK3; pt.style = Paint.Style.STROKE; pt.strokeWidth = 1.1f
                    c.drawRoundRect(r, 3f, 3f, pt)
                }
            }
            else -> Unit
        }
    }

    /** Pictures in rows of up to two, each row as tall as fits the width (capped), rounded. */
    private fun images(ctx: Context, pg: Pager, b: Block) {
        val pics = MediaExtra.pics(b.extra).filter { Media.file(ctx, it.f).exists() }
        pics.chunked(2).forEachIndexed { i, row ->
            if (i > 0) pg.y += 8f
            val gap = 8f * (row.size - 1)
            val h = min((CW - gap) / row.sumOf { it.r.toDouble() }.toFloat(), 380f)
            pg.ensure(h)
            var x = MX + CW
            row.forEach { p ->
                val w = h * p.r
                val rect = RectF(x - w, pg.y, x, pg.y + h)
                decode(ctx, p.f, (w * 2.2f).toInt())?.let { bm ->
                    val c = pg.canvas
                    c.save()
                    c.clipPath(Path().apply { addRoundRect(rect, 10f, 10f, Path.Direction.CW) })
                    c.drawBitmap(bm, null, rect, Paint(Paint.FILTER_BITMAP_FLAG))
                    c.restore()
                    bm.recycle()
                }
                x -= w + 8f
            }
            pg.y += h
        }
    }

    private fun decode(ctx: Context, name: String, maxPx: Int): Bitmap? = runCatching {
        val f = Media.file(ctx, name)
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0) return null
        BitmapFactory.decodeFile(f.path, Media.scaledOptions(o.outWidth, o.outHeight, max(maxPx, 200)))
    }.getOrNull()

    /** The drawing's strokes, as vectors in the light inks. */
    private suspend fun drawing(ctx: Context, pg: Pager, b: Block) {
        val d = MediaExtra.drawing(b.extra) ?: return
        val strokes = StrokeFile.load(ctx, d.strokes)
        if (strokes.isEmpty()) return
        val w = CW
        val h = min(w / d.r, PAGE_H - TOP - BOTTOM - 20f)
        val k = h * d.r
        pg.ensure(h)
        val c = pg.canvas
        val ox = MX + (CW - k) / 2f
        c.drawRoundRect(RectF(MX, pg.y, MX + CW, pg.y + h), 12f, 12f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WELL })
        c.save()
        c.clipRect(MX, pg.y, MX + CW, pg.y + h)
        c.translate(ox, pg.y - d.y0 * k)
        val pt = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        strokes.forEach { s ->
            val ink = Ink.of(s.color)
            pt.color = if (s.tool == Tool.Marker) (ink?.markLight?.toArgb() ?: 0x42000000) else (ink?.light?.toArgb() ?: INK)
            pt.strokeWidth = s.w * k
            val n = s.pts.size / 2
            if (n == 0) return@forEach
            val path = Path().apply {
                moveTo(s.pts[0] * k, s.pts[1] * k)
                if (n == 1) lineTo(s.pts[0] * k + .01f, s.pts[1] * k)
                for (i in 1 until n - 1) {
                    val x = s.pts[2 * i] * k; val y = s.pts[2 * i + 1] * k
                    val nx = s.pts[2 * i + 2] * k; val ny = s.pts[2 * i + 3] * k
                    quadTo(x, y, (x + nx) / 2, (y + ny) / 2)
                }
                if (n > 1) lineTo(s.pts[2 * n - 2] * k, s.pts[2 * n - 1] * k)
            }
            c.drawPath(path, pt)
        }
        c.restore()
        pg.y += h
    }

    /** A recording: a well with its waveform and length, the transcript under it. */
    private fun voice(pg: Pager, b: Block, f: Fonts) {
        val v = MediaExtra.voice(b.extra) ?: return
        val tl = v.text.trim().takeIf { it.isNotEmpty() }?.let { layout(it, paint(10.5f, f.body, INK2), CW - 28f) }
        val h = 44f + (tl?.let { it.height + 10f } ?: 0f)
        pg.ensure(h)
        val c = pg.canvas
        c.drawRoundRect(RectF(MX, pg.y, MX + CW, pg.y + h), 12f, 12f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WELL })
        val mid = pg.y + 22f
        val time = paint(10.5f, f.body, INK2).apply { setFontVariationSettings("'wght' 600") }
        val label = "🎙  ${Media.fmtMs(v.ms)}"
        c.drawText(label, MX + CW - 14f - time.measureText(label), mid + 4f, time)
        val bars = v.wave.ifEmpty { List(40) { 20 } }
        val span = CW - 28f - time.measureText(label) - 16f
        val n = min(bars.size, (span / 4f).toInt()).coerceAtLeast(1)
        val bp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INK3; strokeWidth = 2f; strokeCap = Paint.Cap.ROUND }
        for (i in 0 until n) {
            val a = bars[(i * bars.size / n).coerceAtMost(bars.size - 1)].coerceIn(4, 100) / 100f
            val bx = MX + 14f + i * (span / n)
            c.drawLine(bx, mid - 9f * a, bx, mid + 9f * a, bp)
        }
        tl?.let { c.save(); c.translate(MX + 14f, pg.y + 44f); it.draw(c); c.restore() }
        pg.y += h
    }

    /** A link card: title, a line of description, the address. */
    private fun link(pg: Pager, b: Block, f: Fonts) {
        val l = MediaExtra.link(b.extra) ?: return
        val w = CW - 28f
        val title = l.title.ifBlank { l.site.ifBlank { l.url } }
        val tl = layout(TextUtils.ellipsize(title, paint(11.5f, f.body).apply { setFontVariationSettings("'wght' 650") }, w * 2, TextUtils.TruncateAt.END), paint(11.5f, f.body).apply { setFontVariationSettings("'wght' 650") }, w, 1.3f)
        val dl = l.desc.trim().takeIf { it.isNotEmpty() }?.let { layout(TextUtils.ellipsize(it, paint(10f, f.body, INK2), w * 2, TextUtils.TruncateAt.END), paint(10f, f.body, INK2), w, 1.35f) }
        val ul = layout(TextUtils.ellipsize(l.url, paint(9.5f, f.body, Ink.Blue.light.toArgb()), w, TextUtils.TruncateAt.END), paint(9.5f, f.body, Ink.Blue.light.toArgb()), w, 1.2f)
        val h = 14f + tl.height + (dl?.let { 4f + it.height } ?: 0f) + 6f + ul.height + 14f
        pg.ensure(h)
        val c = pg.canvas
        c.drawRoundRect(RectF(MX + .5f, pg.y + .5f, MX + CW - .5f, pg.y + h - .5f), 12f, 12f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LINE; style = Paint.Style.STROKE; strokeWidth = 1f })
        var y = pg.y + 14f
        listOfNotNull(tl, dl, ul).forEachIndexed { i, lay ->
            if (i > 0) y += if (lay === ul) 6f else 4f
            c.save(); c.translate(MX + 14f, y); lay.draw(c); c.restore()
            y += lay.height
        }
        pg.y += h
    }

    // ------------------------------------------------------------------ files & sharing

    /** A fresh file in cache/exports named after the note (the folder is emptied first). */
    fun outFile(ctx: Context, title: String, ext: String): File {
        val dir = File(ctx.cacheDir, "exports")
        dir.deleteRecursively()
        dir.mkdirs()
        return File(dir, "${fileName(title)}.$ext")
    }

    fun fileName(title: String): String =
        title.trim().replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), " ").replace(Regex("\\s+"), " ").take(60).trim().ifEmpty { "פתק" }

    fun shareText(ctx: Context, text: String, subject: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        if (subject.isNotBlank()) send.putExtra(Intent.EXTRA_SUBJECT, subject)
        ctx.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun shareFile(ctx: Context, file: File, mime: String, subject: String) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(file.name, uri)
        if (subject.isNotBlank()) send.putExtra(Intent.EXTRA_SUBJECT, subject)
        ctx.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}
