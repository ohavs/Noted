package com.ohav.noted.data

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
