package com.ohav.noted.data

import org.json.JSONArray
import org.json.JSONObject

/** Block types mirror the prototype editor. */
enum class BlockType { P, H1, H2, H3, Quote, Bullet, Num, Check, Divider, Images, Voice, Draw, Link }

data class Block(
    val id: String,
    val type: BlockType,
    val text: String = "",
    val done: Boolean = false,
    val indent: Int = 0,
)

object Blocks {
    fun parse(json: String): List<Block> = runCatching {
        val a = JSONArray(json)
        List(a.length()) { i ->
            val o = a.getJSONObject(i)
            Block(
                id = o.optString("id"),
                type = runCatching { BlockType.valueOf(o.optString("type", "P")) }.getOrDefault(BlockType.P),
                text = o.optString("text"),
                done = o.optBoolean("done"),
                indent = o.optInt("indent"),
            )
        }
    }.getOrDefault(emptyList())

    fun serialize(blocks: List<Block>): String = JSONArray().apply {
        blocks.forEach { b ->
            put(JSONObject().put("id", b.id).put("type", b.type.name).put("text", b.text).put("done", b.done).put("indent", b.indent))
        }
    }.toString()
}
