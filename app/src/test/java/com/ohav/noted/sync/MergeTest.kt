package com.ohav.noted.sync

import com.ohav.noted.data.Block
import com.ohav.noted.data.BlockType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MergeTest {
    private fun b(id: String, text: String = id, done: Boolean = false) = Block(id, BlockType.Check, text, done)
    private fun ids(l: List<Block>) = l.joinToString("") { it.id }

    @Test fun untouchedTakesTheirs() {
        val base = listOf(b("a"), b("b"))
        val theirs = listOf(b("a", "A!"), b("b"), b("c"))
        assertEquals(theirs, Merge.blocks(base, base, theirs))
    }

    @Test fun editsOfDifferentBlocksBothSurvive() {
        val base = listOf(b("a"), b("b"))
        val r = Merge.blocks(base, listOf(b("a", "mine"), b("b")), listOf(b("a"), b("b", done = true)))
        assertEquals(listOf(b("a", "mine"), b("b", done = true)), r)
    }

    @Test fun sameBlockMineWins() {
        val base = listOf(b("a"))
        assertEquals(listOf(b("a", "mine")), Merge.blocks(base, listOf(b("a", "mine")), listOf(b("a", "theirs"))))
    }

    @Test fun insertsFromBothSidesKeepTheirPlace() {
        val base = listOf(b("a"), b("b"), b("c"))
        val mine = listOf(b("a"), b("x"), b("b"), b("c"))
        val theirs = listOf(b("a"), b("b"), b("c"), b("y"))
        assertEquals("axbcy", ids(Merge.blocks(base, mine, theirs)))
    }

    @Test fun deleteVsEdit() {
        val base = listOf(b("a"), b("b"))
        // they deleted a, I didn't touch it → gone
        assertEquals("b", ids(Merge.blocks(base, listOf(b("a"), b("b", "B")), listOf(b("b")))))
        // they deleted a, I edited it → kept
        assertEquals("ab", ids(Merge.blocks(base, listOf(b("a", "A"), b("b")), listOf(b("b")))))
        // I deleted b, they didn't touch it → gone
        assertEquals("a", ids(Merge.blocks(base, listOf(b("a")), listOf(b("a", "A"), b("b")))))
    }

    @Test fun myReorderLeads() {
        val base = listOf(b("a"), b("b"), b("c"))
        val mine = listOf(b("c"), b("a"), b("b"))
        val theirs = listOf(b("a"), b("b"), b("y"), b("c"))
        assertEquals("cayb".toList().sorted(), ids(Merge.blocks(base, mine, theirs)).toList().sorted())
        assertEquals('c', ids(Merge.blocks(base, mine, theirs)).first())
    }

    @Test fun pickPrefersMineWhenChanged() {
        assertEquals("t", Merge.pick("b", "b", "t"))
        assertEquals("m", Merge.pick("b", "m", "t"))
    }

    @Test fun ordersKeepWhatDidntMove() {
        val known = mapOf("a" to 1.0, "b" to 2.0, "c" to 3.0)
        val ins = Orders.assign(listOf("a", "x", "b", "c"), known)
        assertEquals(1.0, ins.getValue("a"), 0.0)
        assertEquals(2.0, ins.getValue("b"), 0.0)
        assertTrue(ins.getValue("x") > 1.0 && ins.getValue("x") < 2.0)

        val moved = Orders.assign(listOf("c", "a", "b"), known)
        assertEquals(1.0, moved.getValue("a"), 0.0)
        assertEquals(2.0, moved.getValue("b"), 0.0)
        assertTrue(moved.getValue("c") < 1.0)

        val fresh = Orders.assign(listOf("p", "q"), emptyMap())
        assertTrue(fresh.getValue("p") < fresh.getValue("q"))
    }
}
