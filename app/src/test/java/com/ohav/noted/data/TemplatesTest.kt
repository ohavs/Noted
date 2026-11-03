package com.ohav.noted.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplatesTest {
    @Test fun shapeKeepsTextDropsMediaClearsBoxes() {
        val src = listOf(
            Block("1", BlockType.H3, "מקרר"),
            Block("2", BlockType.Check, "חלב", done = true, spans = listOf(Span(0, 3, "b"))),
            Block("3", BlockType.Images, extra = """{"pics":[]}"""),
            Block("4", BlockType.Bullet, "​ביצים", indent = 1, spans = listOf(Span(1, 3, "i"))),
        )
        val out = Templates.shape(src)
        assertEquals(listOf(BlockType.H3, BlockType.Check, BlockType.Bullet), out.map { it.type })
        assertTrue(out.none { it.done })
        assertEquals(listOf(Span(0, 3, "b")), out[1].spans)
        assertEquals("ביצים", out[2].text)
        assertEquals(1, out[2].indent)
        assertEquals(listOf(Span(0, 2, "i")), out[2].spans)
        assertTrue(out.all { it.extra.isEmpty() })
    }
}
