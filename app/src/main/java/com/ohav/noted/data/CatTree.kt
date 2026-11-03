package com.ohav.noted.data

/**
 * Categories as a one-level tree: top-level categories in order, each followed by its
 * subcategories in order. A category whose parent is gone (or is itself a subcategory)
 * counts as top level, so a half-synced list never hides anything.
 */
class CatTree(all: List<CategoryEntity>) {
    private val sorted = all.sortedBy { it.position }
    private val ids = sorted.mapTo(HashSet()) { it.id }
    private val rawParent = sorted.associate { it.id to it.parent }

    /** child id → its top-level parent; tops are not in it. */
    val parentOf: Map<String, String> = sorted.mapNotNull { k ->
        val p = k.parent
        if (p.isNotEmpty() && p != k.id && p in ids && rawParent[p].isNullOrEmpty()) k.id to p else null
    }.toMap()

    val tops: List<CategoryEntity> = sorted.filter { it.id !in parentOf }
    val children: Map<String, List<CategoryEntity>> = sorted.filter { it.id in parentOf }.groupBy { parentOf.getValue(it.id) }

    /** Tops, each followed by its children — the order of the stack swipe and of lists. */
    val flat: List<CategoryEntity> = tops.flatMap { listOf(it) + children[it.id].orEmpty() }

    fun topOf(id: String): String = parentOf[id] ?: id
    fun isTop(id: String) = id in ids && id !in parentOf
    fun hasChildren(id: String) = !children[id].isNullOrEmpty()

    /** The ids whose notes show under [id]: itself, plus its children when it's a top. */
    fun family(id: String): Set<String> = buildSet { add(id); children[id]?.forEach { add(it.id) } }

    companion object {
        /**
         * Where a row dragged in the categories list lands. [order] = visual order after the drag
         * (with the dragged row's own children left out while it's dragged); [into] = a top it
         * was dropped onto; [parents] = id → parent before the drag ("" = top). Returns
         * (id, parent) for every row, children of the dragged row right after it.
         *
         * Between two siblings → their sibling. Right under a top that has children → its first
         * child. At the end of a group → stays in it only if it was already there. A category
         * with children never nests: inside another group it moves to just after that group.
         */
        fun arrange(order: List<String>, moved: String, into: String?, parents: Map<String, String>, hidden: List<String>): List<Pair<String, String>> {
            val list = order.toMutableList()
            val p = { id: String -> parents[id].orEmpty() }
            var parent: String
            if (into != null && hidden.isEmpty() && into != moved && p(into).isEmpty()) {
                list.remove(moved)
                var at = list.indexOf(into) + 1
                while (at < list.size && p(list[at]) == into) at++
                list.add(at, moved)
                parent = into
            } else {
                parent = predict(list, moved, parents, hidden.isNotEmpty())
                if (hidden.isNotEmpty()) {
                    // a top with children can't sit inside another group: move past its end
                    val i = list.indexOf(moved)
                    val below = list.getOrNull(i + 1)
                    if (below != null && p(below).isNotEmpty()) {
                        list.removeAt(i)
                        var at = i
                        while (at < list.size && p(list[at]).isNotEmpty()) at++
                        list.add(at, moved)
                    }
                    parent = ""
                }
            }
            val out = ArrayList<Pair<String, String>>()
            for (id in list) {
                out += id to if (id == moved) parent else p(id)
                if (id == moved) hidden.forEach { out += it to moved }
            }
            return out
        }

        /** The parent [moved] would get where it sits in [order] now (see [arrange]). */
        fun predict(order: List<String>, moved: String, parents: Map<String, String>, hasChildren: Boolean): String {
            if (hasChildren) return ""
            val p = { id: String -> parents[id].orEmpty() }
            val i = order.indexOf(moved)
            val above = order.getOrNull(i - 1)
            val below = order.getOrNull(i + 1)
            if (below != null && p(below).isNotEmpty()) return p(below)
            if (above != null && p(above).isNotEmpty()) return if (p(moved) == p(above)) p(above) else ""
            return ""
        }
    }
}
