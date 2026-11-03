package com.ohav.noted.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CatTreeTest {
    private fun k(id: String, pos: Int, parent: String = "") = CategoryEntity(id, id, pos, parent = parent)

    // A [a1 a2] B C
    private val parents = mapOf("A" to "", "a1" to "A", "a2" to "A", "B" to "", "C" to "")

    @Test fun treeGroupsChildrenUnderTops() {
        val t = CatTree(listOf(k("B", 3), k("a2", 2, "A"), k("A", 0), k("a1", 1, "A"), k("x", 4, "gone")))
        assertEquals(listOf("A", "a1", "a2", "B", "x"), t.flat.map { it.id })
        assertEquals(setOf("A", "a1", "a2"), t.family("A"))
        assertEquals("A", t.topOf("a2"))
        assertEquals(true, t.isTop("x"))
    }

    @Test fun nestedTwoDeepCountsAsTop() {
        val t = CatTree(listOf(k("A", 0), k("a1", 1, "A"), k("z", 2, "a1")))
        assertEquals(listOf("A", "a1", "z"), t.flat.map { it.id })
        assertEquals(true, t.isTop("z"))
    }

    @Test fun dropIntoTopAppendsToItsGroup() {
        val r = CatTree.arrange(listOf("A", "a1", "a2", "B", "C"), "C", "A", parents, emptyList())
        assertEquals(listOf("A" to "", "a1" to "A", "a2" to "A", "C" to "A", "B" to ""), r)
    }

    @Test fun betweenSiblingsBecomesSibling() {
        val r = CatTree.arrange(listOf("A", "a1", "B", "a2", "C"), "B", null, parents, emptyList())
        assertEquals("A", r.toMap()["B"])
    }

    @Test fun endOfGroupKeepsOwnDepth() {
        // a1 dragged to the end of its own group stays a child; B there stays a top
        assertEquals("A", CatTree.arrange(listOf("A", "a2", "a1", "B", "C"), "a1", null, parents, emptyList()).toMap()["a1"])
        assertEquals("", CatTree.arrange(listOf("A", "a1", "a2", "B", "C"), "B", null, parents, emptyList()).toMap()["B"])
    }

    @Test fun childDraggedBetweenTopsStepsUp() {
        val r = CatTree.arrange(listOf("A", "a2", "B", "a1", "C"), "a1", null, parents, emptyList())
        assertEquals("", r.toMap()["a1"])
    }

    @Test fun parentWithChildrenNeverNests() {
        val p = parents + mapOf("c1" to "C")
        // C (children hidden while dragged) dropped between a1 and a2 → right after A's group
        val r = CatTree.arrange(listOf("A", "a1", "C", "a2", "B"), "C", null, p, listOf("c1"))
        assertEquals(listOf("A" to "", "a1" to "A", "a2" to "A", "C" to "", "c1" to "C", "B" to ""), r)
        // and it can't be dropped into another top
        val s = CatTree.arrange(listOf("A", "a1", "a2", "B", "C"), "C", "B", p, listOf("c1"))
        assertEquals("", s.toMap()["C"])
    }
}
