package com.ohav.noted.ui.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import com.ohav.noted.data.Span
import kotlin.math.max
import kotlin.math.min

/** Small text palette that sits well on every note colour; dark mode gets lighter inks. */
enum class Ink(val label: String, val light: Color, val dark: Color, val markLight: Color, val markDark: Color) {
    Red("אדום", Color(0xFFC8372D), Color(0xFFFF8A7A), Color(0x66FF8A7A), Color(0x55C8372D)),
    Orange("כתום", Color(0xFFC2620F), Color(0xFFFFB067), Color(0x80FFC46B), Color(0x55C2620F)),
    Green("ירוק", Color(0xFF2B7A4B), Color(0xFF7FD6A2), Color(0x6684D9A4), Color(0x552B7A4B)),
    Blue("כחול", Color(0xFF2D5FC4), Color(0xFF8FB4FF), Color(0x668FB4FF), Color(0x552D5FC4)),
    Purple("סגול", Color(0xFF8136B0), Color(0xFFD7A2F5), Color(0x66D7A2F5), Color(0x558136B0));

    fun ink(dark: Boolean) = if (dark) this.dark else light
    fun mark(dark: Boolean) = if (dark) markDark else markLight

    companion object {
        fun of(name: String) = entries.firstOrNull { it.name == name }
    }
}

object Rich {
    const val BOLD = "b"
    const val ITALIC = "i"
    const val UNDER = "u"
    const val STRIKE = "s"
    const val LINK = "a:"

    private fun styleOf(tag: String, dark: Boolean): SpanStyle? = when {
        tag == BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
        tag == ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
        tag == UNDER -> SpanStyle(textDecoration = TextDecoration.Underline)
        tag == STRIKE -> SpanStyle(textDecoration = TextDecoration.LineThrough)
        tag.startsWith("c:") -> Ink.of(tag.drop(2))?.let { SpanStyle(color = it.ink(dark)) }
        tag.startsWith("h:") -> Ink.of(tag.drop(2))?.let { SpanStyle(background = it.mark(dark)) }
        tag.startsWith(LINK) -> SpanStyle(color = Ink.Blue.ink(dark), textDecoration = TextDecoration.Underline)
        else -> null
    }

    /** [links] = make "a:" runs tappable (cards; the editor opens them from the bar instead). */
    fun annotate(text: String, spans: List<Span>, dark: Boolean, offset: Int = 0, links: Boolean = false): AnnotatedString = buildAnnotatedString {
        append(text)
        val len = text.length
        if (links) spans.filter { it.style.startsWith(LINK) }.forEach { s ->
            val a = (s.start + offset).coerceIn(0, len)
            val b = (s.end + offset).coerceIn(0, len)
            if (a < b) addLink(androidx.compose.ui.text.LinkAnnotation.Url(s.style.drop(LINK.length)), a, b)
        }
        // underline + strike on the same range need one combined decoration
        spans.forEach { s ->
            val a = (s.start + offset).coerceIn(0, len)
            val b = (s.end + offset).coerceIn(0, len)
            if (a >= b) return@forEach
            styleOf(s.style, dark)?.let { addStyle(it, a, b) }
        }
        val u = spans.filter { it.style == UNDER }
        spans.filter { it.style == STRIKE }.forEach { st ->
            u.forEach { un ->
                val a = (max(st.start, un.start) + offset).coerceIn(0, len)
                val b = (min(st.end, un.end) + offset).coerceIn(0, len)
                if (a < b) addStyle(SpanStyle(textDecoration = TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))), a, b)
            }
        }
    }

    /**
     * Moves spans through a text edit (old → new): untouched text keeps its styles, text typed
     * right after a styled run continues that run, deleted ranges collapse.
     */
    fun shift(old: String, new: String, spans: List<Span>): List<Span> {
        if (spans.isEmpty() || old == new) return spans
        var p = 0
        val maxP = min(old.length, new.length)
        while (p < maxP && old[p] == new[p]) p++
        var q = 0
        while (q < old.length - p && q < new.length - p && old[old.length - 1 - q] == new[new.length - 1 - q]) q++
        val bOld = old.length - q
        val bNew = new.length - q
        val delta = new.length - old.length
        fun map(x: Int): Int = when {
            x <= p -> x
            x >= bOld -> x + delta
            else -> bNew
        }
        return spans.mapNotNull { s ->
            val a = if (s.start >= bOld) s.start + delta else if (s.start <= p) s.start else bNew
            var b = map(s.end)
            // typing at the end of a run continues it
            if (s.end == p && s.start < p && bNew > p && bOld == p) b = bNew
            if (a < b) Span(a, b, s.style) else null
        }.let(::normalize)
    }

    fun has(spans: List<Span>, start: Int, end: Int, style: String): Boolean {
        if (start >= end) return spans.any { it.style == style && it.start < start && it.end >= start }
        return covered(spans.filter { it.style == style }, start, end)
    }

    /** Ink name applied over the whole range for a "c:"/"h:" family, if any. */
    fun inkOf(spans: List<Span>, start: Int, end: Int, prefix: String): String? =
        spans.filter { it.style.startsWith(prefix) }.firstOrNull { covered(listOf(it), start, max(end, start + 1)) }?.style?.drop(prefix.length)

    private fun covered(list: List<Span>, start: Int, end: Int): Boolean {
        var pos = start
        list.sortedBy { it.start }.forEach { s -> if (s.start <= pos && s.end > pos) pos = s.end }
        return pos >= end
    }

    /** Toggles [style] over [start, end): removes it if the whole range has it, else adds it. */
    fun toggle(spans: List<Span>, start: Int, end: Int, style: String): List<Span> =
        if (has(spans, start, end, style)) remove(spans, start, end) { it == style } else add(spans, start, end, style)

    /** Sets a colour family ("c:" text / "h:" marker); null ink clears it. */
    fun setInk(spans: List<Span>, start: Int, end: Int, prefix: String, ink: String?): List<Span> {
        val cleared = remove(spans, start, end) { it.startsWith(prefix) }
        return if (ink == null) cleared else add(cleared, start, end, prefix + ink)
    }

    fun clear(spans: List<Span>, start: Int, end: Int) = remove(spans, start, end) { true }

    private fun add(spans: List<Span>, start: Int, end: Int, style: String) = normalize(spans + Span(start, end, style))

    private fun remove(spans: List<Span>, start: Int, end: Int, match: (String) -> Boolean): List<Span> =
        normalize(spans.flatMap { s ->
            if (!match(s.style) || s.end <= start || s.start >= end) listOf(s)
            else listOfNotNull(
                if (s.start < start) Span(s.start, start, s.style) else null,
                if (s.end > end) Span(end, s.end, s.style) else null,
            )
        })

    /** Merges touching/overlapping runs of the same style. */
    fun normalize(spans: List<Span>): List<Span> = spans.groupBy { it.style }.flatMap { (style, list) ->
        val out = ArrayList<Span>()
        list.sortedBy { it.start }.forEach { s ->
            val last = out.lastOrNull()
            if (last != null && s.start <= last.end) out[out.lastIndex] = Span(last.start, max(last.end, s.end), style)
            else out += s
        }
        out
    }.filter { it.start < it.end }

    /** Spans inside [start, end), re-based to start (for splitting a block). */
    fun slice(spans: List<Span>, start: Int, end: Int): List<Span> = spans.mapNotNull { s ->
        val a = max(s.start, start)
        val b = min(s.end, end)
        if (a < b) Span(a - start, b - start, s.style) else null
    }

    fun offset(spans: List<Span>, by: Int) = spans.map { Span(it.start + by, it.end + by, it.style) }
}

/** Draws the block's spans over the field text (which carries a leading sentinel, hence [offset]). */
class RichTransformation(private val spans: List<Span>, private val dark: Boolean, private val offset: Int) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(Rich.annotate(text.text, spans, dark, offset), OffsetMapping.Identity)

    override fun equals(other: Any?) = other is RichTransformation && other.spans == spans && other.dark == dark && other.offset == offset
    override fun hashCode() = spans.hashCode() * 31 + dark.hashCode()
}
