package com.ohav.noted.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SinkTest {
    private fun c(id: String, done: Boolean = false, indent: Int = 0) = Block(id, BlockType.Check, id, done, indent)
    private fun ids(l: List<Block>) = l.joinToString(" ") { it.id }

    @Test fun settleMovesEveryTickedRowDownInOrder() {
        val l = listOf(c("a", true), c("b", true), c("c"), c("d", true), c("e"))
        assertEquals("c e a b d", ids(l.settleChecked()))
    }

    @Test fun settleKeepsListsApartAndChildrenWithParents() {
        val l = listOf(c("a", true), c("a1", indent = 1), c("b"), Block("p", BlockType.P, "x"), c("x", true), c("y"))
        assertEquals("b a a1 p y x", ids(l.settleChecked()))
    }

    @Test fun settledListIsUntouched() {
        val l = listOf(c("a"), c("b", true))
        assertEquals(l, l.settleChecked())
    }
}
