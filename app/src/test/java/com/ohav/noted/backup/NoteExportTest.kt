package com.ohav.noted.backup

import com.ohav.noted.data.Block
import com.ohav.noted.data.BlockType
import com.ohav.noted.data.Span
import com.ohav.noted.design.NoteColor
import org.junit.Assert.assertEquals
import org.junit.Test

class NoteExportTest {
    private var n = 0
    private fun b(type: BlockType, text: String = "", done: Boolean = false, indent: Int = 0, spans: List<Span> = emptyList()) =
        Block("b${n++}", type, text, done, indent, spans)

    private fun doc(vararg blocks: Block) = NoteExport.Doc("קניות", blocks.toList(), NoteColor.Butter, "בית", 0L)

    @Test fun markdownListsAndMarks() {
        val d = doc(
            b(BlockType.H1, "לשבת"),
            b(BlockType.Check, "חלה", done = true),
            b(BlockType.Check, "יין"),
            b(BlockType.Num, "ראשון"),
            b(BlockType.Num, "פנימי", indent = 1),
            b(BlockType.Num, "שני"),
            b(BlockType.P, "חשוב מאוד", spans = listOf(Span(0, 4, "b"), Span(5, 9, "a:https://x.y"))),
            b(BlockType.Divider),
        )
        assertEquals(
            "# קניות\n\n## לשבת\n\n- [x] חלה\n- [ ] יין\n1. ראשון\n  1. פנימי\n2. שני\n\n**חשוב** [מאוד](https://x.y)\n\n---\n",
            NoteExport.markdown(d),
        )
    }

    @Test fun plainTextReadsInAChat() {
        val d = doc(b(BlockType.Bullet, "אחד"), b(BlockType.Bullet, "שתיים", indent = 1), b(BlockType.Quote, "ציטוט"), b(BlockType.Check, "משימה"))
        assertEquals("קניות\n\n• אחד\n  • שתיים\n❝ ציטוט\n☐ משימה", NoteExport.text(d))
    }

    @Test fun markdownEscapesAndKeepsSpacesOutsideMarks() {
        val d = NoteExport.Doc("", listOf(b(BlockType.P, "a_b  bold ", spans = listOf(Span(3, 10, "b")))), NoteColor.Sand, "", 0L)
        assertEquals("a\\_b  **bold**\n", NoteExport.markdown(d))
    }

    @Test fun fileNamesAreSafe() {
        assertEquals("a b c", NoteExport.fileName(" a/b:c "))
        assertEquals("פתק", NoteExport.fileName("  "))
    }
}
