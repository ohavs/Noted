package com.ohav.noted.data

import androidx.room.withTransaction
import com.ohav.noted.design.NoteColor
import com.ohav.noted.sync.Merge
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class NotesRepository(private val db: NotedDatabase) {
    private val dao: NoteDao = db.notes()
    val notes: Flow<List<NoteEntity>> = dao.observeActive()
    val categories: Flow<List<CategoryEntity>> = dao.observeCategories()
    val trashed: Flow<List<NoteEntity>> = dao.observeTrashed()
    val archived: Flow<List<NoteEntity>> = dao.observeArchived()

    suspend fun trashedBefore(before: Long): List<String> = dao.trashedBefore(before)

    /** Every media file the notes other than [except] still point at. */
    suspend fun filesInUse(except: Set<String>): Set<String> =
        dao.all().filter { it.id !in except }.flatMapTo(HashSet()) { n -> Blocks.parse(n.blocks).flatMap { MediaExtra.files(it) } }

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

    suspend fun get(id: String): NoteEntity? = dao.get(id)

    /** The first top-level category (where a note made outside the home stack lands). */
    suspend fun categoriesNow(): List<CategoryEntity> = dao.categories()

    suspend fun categoriesFirst(): String? = CatTree(dao.categories()).tops.let { t -> t.firstOrNull { !it.foreign } ?: t.firstOrNull() }?.id

    /** Editor autosave: title + blocks in one write. */
    suspend fun save(id: String, title: String, blocks: List<Block>) = dao.setContent(id, title, Blocks.serialize(blocks), now())

    /**
     * Editor save: applies what the editor changed since [base] onto whatever is stored now
     * (sync or a notification action may have changed the note meanwhile). Returns what is stored.
     */
    suspend fun saveMerged(id: String, base: Pair<String, List<Block>>, title: String, blocks: List<Block>): Pair<String, List<Block>> =
        db.withTransaction {
            val cur = dao.get(id) ?: return@withTransaction title to blocks
            val stored = Blocks.parse(cur.blocks)
            val t = Merge.pick(base.first, title, cur.title)
            val b = Merge.blocks(base.second, blocks, stored)
            if (t != cur.title || b != stored) dao.setContent(id, t, Blocks.serialize(b), now())
            t to b
        }

    /**
     * Notes nobody wrote anything in (a new note left empty, e.g. the app was swiped away with
     * it open) and that are only mine to drop: discarded on launch.
     */
    suspend fun leftovers(before: Long): List<String> = dao.all().filter { n ->
        val sh = n.shareInfo
        n.createdAt < before && n.trashedAt == null && isBlank(n.title, Blocks.parse(n.blocks)) && sh.isOwner && (!sh.shared || sh.folder.isNotEmpty())
    }.map { it.id }

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
    /** Back into the bin with its old date (undoing a restore keeps the days it had left). */
    suspend fun retrash(ids: List<String>, at: Long) = dao.setTrashed(ids, at, now())

    /**
     * Writes a new relative order for [ids] (already sorted as wanted) by reusing the
     * positions those notes held, so notes outside the visible list keep their place.
     */
    suspend fun reorder(ids: List<String>, current: List<NoteEntity>) {
        val byId = current.associateBy { it.id }
        val slots = ids.mapNotNull { byId[it]?.position }.sorted()
        db.withTransaction {
            ids.zip(slots).forEach { (id, pos) -> if (byId[id]?.position != pos) dao.setPosition(id, pos) }
        }
    }

    // ------------------------------------------------------------ categories

    suspend fun createCategory(name: String, icon: String, parent: String = ""): CategoryEntity {
        val k = CategoryEntity(UUID.randomUUID().toString(), name.trim(), (dao.maxCategoryPosition() ?: -1) + 1, icon, parent)
        dao.upsertCategory(k)
        return k
    }

    suspend fun editCategory(id: String, name: String, icon: String) = dao.editCategory(id, name.trim(), icon)

    /** [rows] = (id, parent) in the wanted order; rewrites only the rows whose slot or parent changed. */
    suspend fun arrangeCategories(rows: List<Pair<String, String>>, current: List<CategoryEntity>) {
        val byId = current.associateBy { it.id }
        db.withTransaction {
            rows.forEachIndexed { i, (id, parent) ->
                val k = byId[id] ?: return@forEachIndexed
                if (k.position != i || k.parent != parent) dao.placeCategory(id, parent, i)
            }
        }
    }

    /** Puts a category under [parent] ("" = top level), last among its new siblings. */
    suspend fun moveCategory(id: String, parent: String) = db.withTransaction {
        dao.placeCategory(id, parent, (dao.maxCategoryPosition() ?: -1) + 1)
    }

    suspend fun placeCategory(id: String, parent: String, position: Int) = dao.placeCategory(id, parent, position)

    /** What a category deletion did, so it can be undone exactly. */
    class CategoryRemoval(val category: CategoryEntity, val noteIds: List<String>, val movedTo: String?, val children: List<String> = emptyList())

    /**
     * Deletes a category. Its notes move to [moveTo], or go to the trash with it when null; its
     * subcategories step up to the top level. Positions of the categories after it are left
     * alone (gaps are harmless; order is relative).
     */
    suspend fun deleteCategory(k: CategoryEntity, moveTo: String?): CategoryRemoval = db.withTransaction {
        val ids = dao.liveNoteIdsIn(k.id)
        if (ids.isNotEmpty()) {
            if (moveTo != null) dao.setCategory(ids, moveTo, now()) else now().let { dao.setTrashed(ids, it, it) }
        }
        val kids = dao.categories().filter { it.parent == k.id }.map { it.id }
        if (kids.isNotEmpty()) dao.setCategoryParent(kids, "")
        dao.deleteCategory(k.id)
        CategoryRemoval(k, ids, moveTo, kids)
    }

    suspend fun undoDeleteCategory(r: CategoryRemoval) = db.withTransaction {
        dao.upsertCategory(r.category)
        if (r.children.isNotEmpty()) dao.setCategoryParent(r.children, r.category.id)
        if (r.noteIds.isNotEmpty()) {
            if (r.movedTo != null) dao.setCategory(r.noteIds, r.category.id, now()) else dao.setTrashed(r.noteIds, null, now())
        }
    }

    /** First launch: a few notes in the prototype's voice so the stack isn't empty. */
    suspend fun seedIfEmpty() {
        if (dao.categoryCount() > 0) return
        dao.insertCategories(
            listOf(CategoryEntity("home", "בית", 0, "home"), CategoryEntity("work", "עבודה", 1, "work"), CategoryEntity("ideas", "רעיונות", 2, "ideas"))
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

/** Nothing written: a blank title and only blank text rows (no media, no divider). */
fun isBlank(title: String, blocks: List<Block>): Boolean {
    fun blank(t: String) = t.replace("\u200B", "").isBlank()
    val text = setOf(BlockType.P, BlockType.H1, BlockType.H2, BlockType.H3, BlockType.Quote, BlockType.Bullet, BlockType.Num, BlockType.Check)
    return blank(title) && blocks.all { it.type in text && blank(it.text) }
}
