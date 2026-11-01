package com.ohav.noted.data

import com.ohav.noted.design.NoteColor
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class NotesRepository(private val dao: NoteDao) {
    val notes: Flow<List<NoteEntity>> = dao.observeActive()
    val categories: Flow<List<CategoryEntity>> = dao.observeCategories()

    suspend fun create(categoryId: String, color: NoteColor, title: String = "", blocks: List<Block> = emptyList()): NoteEntity {
        val now = System.currentTimeMillis()
        val note = NoteEntity(
            id = UUID.randomUUID().toString(), title = title, blocks = Blocks.serialize(blocks),
            color = color.name, categoryId = categoryId,
            position = (dao.minPosition() ?: 0.0) - 1.0, createdAt = now, updatedAt = now,
        )
        dao.upsert(note)
        return note
    }

    suspend fun trash(id: String) = dao.trash(id, System.currentTimeMillis())
    suspend fun restore(id: String) = dao.restore(id, System.currentTimeMillis())
    suspend fun togglePin(id: String) = dao.togglePin(id, System.currentTimeMillis())

    private fun now() = System.currentTimeMillis()

    /** Flips one checklist row; returns the note's (done, total) afterwards. */
    suspend fun toggleCheck(noteId: String, blockId: String): Pair<Int, Int>? {
        val n = dao.get(noteId) ?: return null
        val blocks = Blocks.parse(n.blocks).map { if (it.id == blockId) it.copy(done = !it.done) else it }
        dao.setBlocks(noteId, Blocks.serialize(blocks), now())
        val checks = blocks.filter { it.type == BlockType.Check }
        return checks.count { it.done } to checks.size
    }

    suspend fun setPinned(ids: List<String>, on: Boolean) = dao.setPinned(ids, on, now())
    suspend fun setFavorite(ids: List<String>, on: Boolean) = dao.setFavorite(ids, on, now())
    suspend fun setColor(ids: List<String>, color: NoteColor) = dao.setColor(ids, color.name, now())
    suspend fun move(ids: List<String>, categoryId: String) = dao.setCategory(ids, categoryId, now())
    suspend fun archive(ids: List<String>, on: Boolean = true) = dao.setArchived(ids, on, now())
    suspend fun trash(ids: List<String>) = now().let { dao.setTrashed(ids, it, it) }
    suspend fun restore(ids: List<String>) = dao.setTrashed(ids, null, now())

    /**
     * Writes a new relative order for [ids] (already sorted as wanted) by reusing the
     * positions those notes held, so notes outside the visible list keep their place.
     */
    suspend fun reorder(ids: List<String>, current: List<NoteEntity>) {
        val byId = current.associateBy { it.id }
        val slots = ids.mapNotNull { byId[it]?.position }.sorted()
        ids.zip(slots).forEach { (id, pos) -> if (byId[id]?.position != pos) dao.setPosition(id, pos) }
    }

    /** First launch: a few notes in the prototype's voice so the stack isn't empty. */
    suspend fun seedIfEmpty() {
        if (dao.categoryCount() > 0) return
        dao.insertCategories(
            listOf(CategoryEntity("home", "בית", 0), CategoryEntity("work", "עבודה", 1), CategoryEntity("ideas", "רעיונות", 2))
        )
        val now = System.currentTimeMillis()
        var pos = 0.0
        fun b(type: BlockType, text: String, done: Boolean = false) = Block(UUID.randomUUID().toString(), type, text, done)
        fun n(title: String, cat: String, color: NoteColor, pinned: Boolean = false, vararg blocks: Block) = NoteEntity(
            id = UUID.randomUUID().toString(), title = title, blocks = Blocks.serialize(blocks.toList()), color = color.name,
            categoryId = cat, pinned = pinned, position = pos++, createdAt = now, updatedAt = now,
        )
        dao.insertNotes(
            listOf(
                n("קניות לשבת", "home", NoteColor.Butter, true,
                    b(BlockType.Check, "חלה ×2", true), b(BlockType.Check, "יין אדום"), b(BlockType.Check, "פרחים לשולחן"), b(BlockType.Check, "נרות")),
                n("שקשוקה של סבתא", "home", NoteColor.Peach, false,
                    b(BlockType.Quote, "הסוד הוא סבלנות — לא לערבב אחרי"),
                    b(BlockType.P, "שש עגבניות בשלות, פלפל אדום, שום, כמון ופפריקה מעושנת. לבשל לאט עשרים דקות לפני הביצים.")),
                n("רעיונות לטיול בגליל", "ideas", NoteColor.Mint, false,
                    b(BlockType.Bullet, "נחל עמוד — מסלול המים"), b(BlockType.Bullet, "צפת בערב"), b(BlockType.Bullet, "גבינות ברמות")),
                n("פגישת צוות", "work", NoteColor.Sky, false,
                    b(BlockType.P, "לסגור את לוח הזמנים לרבעון ולחלק אחריות על ההשקה.")),
                n("ספרים לקרוא", "ideas", NoteColor.Lilac, false,
                    b(BlockType.Check, "סיפור על אהבה וחושך"), b(BlockType.Check, "המאה הקצרה", true)),
            )
        )
    }
}
