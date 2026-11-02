package com.ohav.noted.ui.editor

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.ohav.noted.data.Block
import com.ohav.noted.data.BlockType
import com.ohav.noted.data.Span
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/**
 * Every text field starts with this invisible character. Deleting it is how a backspace at the
 * start of a block shows up (soft keyboards send nothing for an empty field otherwise).
 */
const val SENTINEL = "​"

val TEXT_TYPES = setOf(BlockType.P, BlockType.H1, BlockType.H2, BlockType.H3, BlockType.Quote, BlockType.Bullet, BlockType.Num, BlockType.Check)
val LIST_TYPES = setOf(BlockType.Bullet, BlockType.Num, BlockType.Check)

@Stable
class EdBlock(val id: String, type: BlockType, text: String, done: Boolean, indent: Int, spans: List<Span>) {
    var type by mutableStateOf(type)
    var done by mutableStateOf(done)
    var indent by mutableStateOf(indent)
    var spans by mutableStateOf(spans)
    var value by mutableStateOf(TextFieldValue(SENTINEL + text, TextRange(SENTINEL.length + text.length)))
    val focus = FocusRequester()

    val text: String get() = value.text.removePrefix(SENTINEL)

    /** Selection in text coordinates (without the sentinel). */
    val sel: TextRange get() = TextRange(max(0, value.selection.min - 1), max(0, value.selection.max - 1))

    fun setText(t: String, cursor: Int = t.length) {
        value = TextFieldValue(SENTINEL + t, TextRange(cursor.coerceIn(0, t.length) + 1))
    }

    fun toBlock() = Block(id, type, if (type in TEXT_TYPES) text else "", done, indent, if (type in TEXT_TYPES) spans else emptyList())

    companion object {
        fun of(b: Block) = EdBlock(b.id, b.type, b.text, b.done, b.indent, b.spans)
        fun new(type: BlockType = BlockType.P, text: String = "", spans: List<Span> = emptyList(), indent: Int = 0) =
            EdBlock(UUID.randomUUID().toString(), type, text, false, indent, spans)
    }
}

private data class Snap(val title: String, val blocks: List<Block>, val focus: String?, val cursor: Int)

@Stable
class EditorState(val noteId: String, title: String, blocks: List<Block>) {
    var title by mutableStateOf(TextFieldValue(title, TextRange(title.length)))
    val blocks = mutableStateListOf<EdBlock>().apply {
        addAll(blocks.map(EdBlock::of))
        if (none { it.type in TEXT_TYPES }) add(EdBlock.new())
    }
    val titleFocus = FocusRequester()

    /** Block with keyboard focus (null = title / nothing). */
    var focused by mutableStateOf<String?>(null)

    /** Focus request for the next composition: block id → cursor (text coordinates). */
    var pendingFocus by mutableStateOf<Pair<String, Int>?>(null)

    /** Styles armed with a collapsed cursor; applied to whatever is typed next. */
    var pending by mutableStateOf(emptySet<String>())

    /** Bumped on every content change — autosave and the undo buttons watch it. */
    var version by mutableStateOf(0)
        private set

    private val original = snap()
    private val undo = ArrayList<Snap>()
    private val redo = ArrayList<Snap>()
    private var lastPush = 0L
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    val current: EdBlock? get() = blocks.firstOrNull { it.id == focused }
    fun indexOf(id: String) = blocks.indexOfFirst { it.id == id }

    fun content(): Pair<String, List<Block>> = title.text.trim() to blocks.map { it.toBlock() }
    val isEmpty: Boolean get() = title.text.isBlank() && blocks.all { it.type in TEXT_TYPES && it.text.isBlank() }

    private fun snap() = Snap(title.text, blocks.map { it.toBlock() }, focused, current?.sel?.max ?: 0)

    /** Record the state before a change. Typing coalesces into one step per pause. */
    private fun record(structural: Boolean) {
        val now = System.currentTimeMillis()
        if (structural || now - lastPush > 900 || undo.isEmpty()) {
            undo += snap()
            if (undo.size > 200) undo.removeAt(0)
            redo.clear()
        }
        lastPush = if (structural) 0L else now
        canUndo = undo.isNotEmpty()
        canRedo = false
    }

    private fun changed() { version++ }

    private fun restore(s: Snap) {
        title = TextFieldValue(s.title, TextRange(s.title.length))
        blocks.clear()
        blocks.addAll(s.blocks.map(EdBlock::of))
        if (blocks.none { it.type in TEXT_TYPES }) blocks.add(EdBlock.new())
        val f = s.focus?.takeIf { id -> blocks.any { it.id == id } }
        if (f != null) pendingFocus = f to s.cursor
        changed()
    }

    fun undo() {
        val s = undo.removeLastOrNull() ?: return
        redo += snap()
        restore(s)
        lastPush = 0L
        canUndo = undo.isNotEmpty(); canRedo = true
    }

    fun redo() {
        val s = redo.removeLastOrNull() ?: return
        undo += snap()
        restore(s)
        lastPush = 0L
        canUndo = true; canRedo = redo.isNotEmpty()
    }

    val changedSinceOpen: Boolean get() = snap().let { it.title != original.title || it.blocks != original.blocks }

    fun revertAll() {
        record(true)
        restore(original)
    }

    fun onTitle(v: TextFieldValue) {
        if (v.text.contains('\n')) {
            // Enter in the title moves into the note
            title = TextFieldValue(v.text.replace("\n", ""), TextRange(v.text.indexOf('\n')))
            val first = blocks.firstOrNull { it.type in TEXT_TYPES } ?: EdBlock.new().also { blocks.add(0, it) }
            pendingFocus = first.id to 0
            return
        }
        if (v.text != title.text) { record(false); title = v; changed() } else title = v
    }

    /** Every edit of a block's field lands here: Enter splits, a lost sentinel is a backspace at the start. */
    fun onValue(b: EdBlock, v: TextFieldValue) {
        if (!v.text.startsWith(SENTINEL)) { backspaceAtStart(b); return }
        val oldText = b.text
        val newText = v.text.removePrefix(SENTINEL)
        if (newText == oldText) {
            if (v.selection != b.value.selection) pending = emptySet()
            b.value = v.copy(selection = clampSel(v.selection, v.text.length))
            return
        }
        record(false)
        var spans = Rich.shift(oldText, newText, b.spans)
        // armed styles cover what was just typed
        if (pending.isNotEmpty() && newText.length > oldText.length) {
            val end = v.selection.max - 1
            val start = end - (newText.length - oldText.length)
            if (start >= 0) pending.forEach { st -> spans = Rich.normalize(spans + Span(start, end, st)) }
        }
        if (newText.contains('\n')) { split(b, newText, spans); changed(); return }
        b.spans = spans
        b.value = v.copy(selection = clampSel(v.selection, v.text.length))
        markdown(b)
        changed()
    }

    private fun clampSel(s: TextRange, len: Int) = TextRange(s.start.coerceIn(1, len), s.end.coerceIn(1, len))

    /** "- ", "1. ", "[] ", "# ", "> ", "---" at the start of a paragraph turn it into that block. */
    private fun markdown(b: EdBlock) {
        if (b.type != BlockType.P) return
        val t = b.text
        val rules = listOf(
            "- " to BlockType.Bullet, "* " to BlockType.Bullet, "1. " to BlockType.Num, "[] " to BlockType.Check,
            "### " to BlockType.H3, "## " to BlockType.H2, "# " to BlockType.H1, "> " to BlockType.Quote,
        )
        rules.firstOrNull { t.startsWith(it.first) }?.let { (k, type) ->
            b.type = type
            b.spans = Rich.offset(Rich.slice(b.spans, k.length, t.length), 0)
            b.setText(t.drop(k.length), max(0, b.sel.max - k.length))
            return
        }
        if (t == "---") {
            val i = indexOf(b.id)
            b.type = BlockType.Divider
            b.setText("")
            val next = EdBlock.new()
            blocks.add(i + 1, next)
            pendingFocus = next.id to 0
        }
    }

    private fun split(b: EdBlock, newText: String, spans: List<Span>) {
        val lines = newText.split('\n')
        // Enter on an empty list row / quote / heading leaves that format instead of adding a row
        if (lines.size == 2 && lines[0].isEmpty() && lines[1].isEmpty() && b.type != BlockType.P) {
            if (b.indent > 0 && b.type in LIST_TYPES) b.indent-- else b.type = BlockType.P
            b.setText("")
            return
        }
        val nextType = when (b.type) {
            BlockType.Bullet, BlockType.Num, BlockType.Check -> b.type
            else -> BlockType.P
        }
        var pos = 0
        val i = indexOf(b.id)
        val made = ArrayList<EdBlock>()
        lines.forEachIndexed { k, line ->
            val sp = Rich.slice(spans, pos, pos + line.length)
            if (k == 0) {
                b.spans = sp
                b.setText(line)
            } else {
                made += EdBlock.new(nextType, line, sp, if (nextType == b.type) b.indent else 0)
            }
            pos += line.length + 1
        }
        blocks.addAll(i + 1, made)
        made.lastOrNull()?.let { pendingFocus = it.id to if (lines.size == 2) 0 else it.text.length }
    }

    private fun backspaceAtStart(b: EdBlock) {
        val i = indexOf(b.id)
        // keep the sentinel in place whatever happens
        b.setText(b.text, 0)
        when {
            b.type in LIST_TYPES && b.indent > 0 -> { record(true); b.indent-- }
            b.type != BlockType.P -> { record(true); b.type = BlockType.P }
            i > 0 -> {
                val prev = blocks[i - 1]
                record(true)
                if (prev.type !in TEXT_TYPES) {
                    blocks.removeAt(i - 1)
                } else {
                    val at = prev.text.length
                    prev.spans = Rich.normalize(prev.spans + Rich.offset(b.spans, at))
                    prev.setText(prev.text + b.text, at)
                    blocks.removeAt(i)
                    pendingFocus = prev.id to at
                }
            }
            else -> return
        }
        changed()
    }

    // ---- toolbar actions ------------------------------------------------------------------

    fun toggleCheck(b: EdBlock) { record(true); b.done = !b.done; changed() }

    /** Turns the focused block into [type]; the same type again goes back to a paragraph. */
    fun setType(type: BlockType) {
        val b = current ?: return
        record(true)
        b.type = if (b.type == type) BlockType.P else type
        if (b.type !in LIST_TYPES) b.indent = 0
        changed()
    }

    fun indent(by: Int) {
        val b = current ?: return
        if (b.type !in LIST_TYPES) return
        val n = (b.indent + by).coerceIn(0, 3)
        if (n == b.indent) return
        record(true); b.indent = n; changed()
    }

    /** Adds a block under the focused one (an empty paragraph is converted in place). */
    fun insert(type: BlockType) {
        val b = current
        record(true)
        if (type == BlockType.Divider) {
            val i = b?.let { indexOf(it.id) } ?: blocks.lastIndex
            val d = EdBlock.new(BlockType.Divider)
            val next = EdBlock.new()
            blocks.add(i + 1, d)
            blocks.add(i + 2, next)
            pendingFocus = next.id to 0
        } else if (b != null && b.text.isEmpty() && b.type == BlockType.P) {
            b.type = type
            pendingFocus = b.id to 0
        } else {
            val i = b?.let { indexOf(it.id) } ?: blocks.lastIndex
            val n = EdBlock.new(type)
            blocks.add(i + 1, n)
            pendingFocus = n.id to 0
        }
        changed()
    }

    fun style(st: String) {
        val b = current ?: return
        val s = b.sel
        if (s.collapsed) { pending = if (st in pending) pending - st else pending + st; return }
        record(true)
        b.spans = Rich.toggle(b.spans, s.min, s.max, st)
        changed()
    }

    fun ink(prefix: String, ink: Ink?) {
        val b = current ?: return
        val s = b.sel
        if (s.collapsed) {
            pending = pending.filterNot { it.startsWith(prefix) }.toSet() + listOfNotNull(ink?.let { prefix + it.name })
            return
        }
        record(true)
        b.spans = Rich.setInk(b.spans, s.min, s.max, prefix, ink?.name)
        changed()
    }

    fun clearStyles() {
        val b = current ?: return
        val s = b.sel
        pending = emptySet()
        if (s.collapsed) return
        record(true); b.spans = Rich.clear(b.spans, s.min, s.max); changed()
    }

    fun isOn(st: String): Boolean {
        val b = current ?: return false
        val s = b.sel
        return if (s.collapsed) st in pending else Rich.has(b.spans, s.min, s.max, st)
    }

    fun move(from: Int, to: Int) {
        if (from == to || from !in blocks.indices || to !in blocks.indices) return
        record(true)
        blocks.add(to, blocks.removeAt(from))
        changed()
    }

    fun moveBy(id: String, by: Int) = indexOf(id).let { move(it, (it + by).coerceIn(0, blocks.lastIndex)) }

    fun duplicate(id: String) {
        val i = indexOf(id).takeIf { it >= 0 } ?: return
        val b = blocks[i]
        record(true)
        blocks.add(i + 1, EdBlock.new(b.type, b.text, b.spans, b.indent).also { it.done = b.done })
        changed()
    }

    fun delete(id: String) {
        val i = indexOf(id).takeIf { it >= 0 } ?: return
        record(true)
        blocks.removeAt(i)
        if (blocks.none { it.type in TEXT_TYPES }) blocks.add(EdBlock.new())
        val near = blocks.getOrNull(min(i, blocks.lastIndex))
        if (near != null && near.type in TEXT_TYPES) pendingFocus = near.id to near.text.length
        changed()
    }

    /** Paste plain text at the cursor of the focused block (multi-line becomes several blocks). */
    fun paste(text: String) {
        val b = current ?: return
        val s = b.value.selection
        val t = b.value.text
        onValue(b, TextFieldValue(t.substring(0, s.min) + text + t.substring(s.max), TextRange(s.min + text.length)))
    }
}
