package com.ohav.noted.sync

import com.ohav.noted.data.Block

/**
 * Three-way merges. [base] is the last state both sides agreed on, [mine] what this device
 * has now, [theirs] what arrived (another device, a notification action, a collaborator).
 * Blocks merge one by one: a block only one side touched takes that side; a block both
 * touched takes mine (it is pushed right after, so the newest write still wins overall).
 */
object Merge {
    fun <T> pick(base: T, mine: T, theirs: T): T = if (mine == base) theirs else mine

    fun blocks(base: List<Block>, mine: List<Block>, theirs: List<Block>): List<Block> {
        if (mine == base) return theirs
        if (theirs == base || theirs == mine) return mine
        val b = base.associateBy { it.id }
        val m = mine.associateBy { it.id }
        val t = theirs.associateBy { it.id }

        val kept = LinkedHashMap<String, Block>()
        for (id in m.keys + t.keys) {
            if (id in kept) continue
            val o = b[id]
            val mv = m[id]
            val tv = t[id]
            val v = when {
                mv != null && tv != null -> if (mv == o) tv else mv
                mv != null -> if (o == null || mv != o) mv else null // they deleted it; keep only if I changed it
                tv != null -> if (o == null || tv != o) tv else null // I deleted it; keep only if they changed it
                else -> null
            }
            if (v != null) kept[id] = v
        }

        // order: whoever reordered the shared blocks leads (mine on a tie); the other side's new blocks
        // slot in after the block they followed there
        val mineMoved = sharedOrder(base, mine) != sharedOrder(mine, base)
        val (lead, other) = if (mineMoved) mine to theirs else theirs to mine
        val out = lead.mapNotNull { kept[it.id]?.id }.toMutableList()
        val placed = out.toHashSet()
        other.forEachIndexed { i, blk ->
            if (blk.id !in kept || blk.id in placed) return@forEachIndexed
            var at = 0
            for (j in i - 1 downTo 0) {
                val k = out.indexOf(other[j].id)
                if (k >= 0) { at = k + 1; break }
            }
            out.add(at, blk.id)
            placed += blk.id
        }
        return out.map { kept.getValue(it) }
    }

    /** Ids of [a] that also appear in [b], in [a]'s order. */
    private fun sharedOrder(a: List<Block>, b: List<Block>): List<String> {
        val ids = b.mapTo(HashSet()) { it.id }
        return a.filter { it.id in ids }.map { it.id }
    }
}

/**
 * Fractional order keys for blocks, so a move or an insert rewrites only the blocks that moved.
 * Keeps the longest run of [known] keys that is still increasing and fills the rest in between.
 */
object Orders {
    fun assign(ids: List<String>, known: Map<String, Double>): Map<String, Double> {
        val keys = ids.map { known[it] }
        val keep = BooleanArray(ids.size)
        // longest strictly increasing subsequence over the known keys (patience sort)
        val idx = ids.indices.filter { keys[it] != null }
        val tails = ArrayList<Int>()
        val prev = IntArray(ids.size) { -1 }
        for (i in idx) {
            val v = keys[i]!!
            var lo = 0
            var hi = tails.size
            while (lo < hi) {
                val mid = (lo + hi) / 2
                if (keys[tails[mid]]!! < v) lo = mid + 1 else hi = mid
            }
            if (lo > 0) prev[i] = tails[lo - 1]
            if (lo == tails.size) tails.add(i) else tails[lo] = i
        }
        var k = tails.lastOrNull() ?: -1
        while (k >= 0) { keep[k] = true; k = prev[k] }

        val out = DoubleArray(ids.size)
        var i = 0
        while (i < ids.size) {
            if (keep[i]) { out[i] = keys[i]!!; i++; continue }
            var j = i
            while (j < ids.size && !keep[j]) j++
            val lo = if (i > 0) out[i - 1] else null
            val hi = if (j < ids.size) keys[j]!! else null
            val n = j - i
            for (s in 0 until n) {
                out[i + s] = when {
                    lo != null && hi != null -> lo + (hi - lo) * (s + 1) / (n + 1)
                    lo != null -> lo + (s + 1)
                    hi != null -> hi - (n - s)
                    else -> (s + 1).toDouble()
                }
            }
            i = j
        }
        // bisected too far: start over with whole numbers
        for (x in 1 until out.size) if (!(out[x] > out[x - 1]) || out[x] - out[x - 1] < 1e-9) {
            return ids.withIndex().associate { (n, id) -> id to (n + 1).toDouble() }
        }
        return ids.withIndex().associate { (n, id) -> id to out[n] }
    }
}
