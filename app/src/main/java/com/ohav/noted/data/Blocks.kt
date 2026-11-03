package com.ohav.noted.data

import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Block types mirror the prototype editor. */
enum class BlockType { P, H1, H2, H3, Quote, Bullet, Num, Check, Divider, Images, Voice, Draw, Link, Place }

/**
 * Inline style over [start, end) of a block's text.
 * [style]: "b" bold, "i" italic, "u" underline, "s" strike, "c:<Ink>" text colour, "h:<Ink>" marker.
 */
data class Span(val start: Int, val end: Int, val style: String)

data class Block(
    val id: String,
    val type: BlockType,
    val text: String = "",
    val done: Boolean = false,
    val indent: Int = 0,
    val spans: List<Span> = emptyList(),
    /** Per-type payload of media blocks as a JSON object (see [MediaExtra]); empty for text. */
    val extra: String = "",
)

object Blocks {
    fun parse(json: String): List<Block> = runCatching {
        val a = JSONArray(json)
        List(a.length()) { i ->
            val o = a.getJSONObject(i)
            val sp = o.optJSONArray("spans")
            Block(
                id = o.optString("id"),
                type = runCatching { BlockType.valueOf(o.optString("type", "P")) }.getOrDefault(BlockType.P),
                text = o.optString("text"),
                done = o.optBoolean("done"),
                indent = o.optInt("indent"),
                spans = if (sp == null) emptyList() else List(sp.length()) { j ->
                    val s = sp.getJSONObject(j)
                    Span(s.optInt("s"), s.optInt("e"), s.optString("t"))
                },
                extra = o.optJSONObject("x")?.toString().orEmpty(),
            )
        }
    }.getOrDefault(emptyList())

    fun serialize(blocks: List<Block>): String = JSONArray().apply {
        blocks.forEach { b ->
            val o = JSONObject().put("id", b.id).put("type", b.type.name).put("text", b.text).put("done", b.done).put("indent", b.indent)
            if (b.extra.isNotEmpty()) runCatching { o.put("x", JSONObject(b.extra)) }
            if (b.spans.isNotEmpty()) {
                o.put("spans", JSONArray().apply { b.spans.forEach { put(JSONObject().put("s", it.start).put("e", it.end).put("t", it.style)) } })
            }
            put(o)
        }
    }.toString()
}

/**
 * A ticked checklist row sinks to the bottom of its list; unticked, it rises back above the done ones.
 * The list = the run of checklist rows around [i] at its indent (deeper rows travel with their parent).
 * [list] already holds the new state of row [i]. Shared by the repository, the editor and (copied) the watch.
 */
fun <T> sinkChecked(list: List<T>, i: Int, isCheck: (T) -> Boolean, indent: (T) -> Int, done: (T) -> Boolean): List<T> {
    if (i !in list.indices || !isCheck(list[i])) return list
    val lvl = indent(list[i])
    var s = i
    while (s > 0 && isCheck(list[s - 1]) && indent(list[s - 1]) >= lvl) s--
    var e = i
    while (e < list.lastIndex && isCheck(list[e + 1]) && indent(list[e + 1]) >= lvl) e++
    var k = s
    while (k <= e && indent(list[k]) > lvl) k++
    val head = s until k
    val units = ArrayList<IntRange>()
    while (k <= e) {
        val st = k++
        while (k <= e && indent(list[k]) > lvl) k++
        units += st until k
    }
    val mine = units.firstOrNull { i in it } ?: return list
    val others = units.filter { it != mine }
    val at = if (done(list[i])) others.size else others.indexOfFirst { done(list[it.first]) }.let { if (it < 0) others.size else it }
    val ordered = others.toMutableList().apply { add(at, mine) }
    if (ordered == units) return list
    return list.subList(0, s) + head.map { list[it] } + ordered.flatMap { r -> r.map { list[it] } } + list.subList(e + 1, list.size)
}

fun List<Block>.sinkChecked(id: String): List<Block> =
    sinkChecked(this, indexOfFirst { it.id == id }, { it.type == BlockType.Check }, { it.indent }, { it.done })

/**
 * Rows ticked a moment ago. A done row stays in sight (struck through, at the bottom) for [SHOW_MS]
 * on the widget and the home card, then folds away; the editor always shows it.
 */
object Ticked {
    const val SHOW_MS = 4_000L
    private val at = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** Bumps on a tick and again when it expires, so views re-filter. */
    val version = kotlinx.coroutines.flow.MutableStateFlow(0)

    fun mark(blockId: String, scope: kotlinx.coroutines.CoroutineScope) {
        at[blockId] = System.currentTimeMillis()
        version.value++
        scope.launch {
            kotlinx.coroutines.delay(SHOW_MS + 150)
            at.entries.removeAll { System.currentTimeMillis() - it.value > SHOW_MS }
            version.value++
        }
    }

    /** Whether [b] shows on a glance surface: anything but a done row that wasn't just ticked. */
    fun shows(b: Block): Boolean =
        b.type != BlockType.Check || !b.done || at[b.id]?.let { System.currentTimeMillis() - it <= SHOW_MS } == true
}
