package com.ohav.noted.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes WHERE trashedAt IS NULL AND archived = 0 ORDER BY pinned DESC, position ASC")
    fun observeActive(): Flow<List<NoteEntity>>

    /** Every note, trashed and archived too (sync). */
    @Query("SELECT * FROM notes")
    fun observeAll(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes")
    suspend fun all(): List<NoteEntity>

    /** The bin, latest thrown first. */
    @Query("SELECT * FROM notes WHERE trashedAt IS NOT NULL ORDER BY trashedAt DESC")
    fun observeTrashed(): Flow<List<NoteEntity>>

    /** The archive, latest put away first (archiving stamps updatedAt). */
    @Query("SELECT * FROM notes WHERE archived = 1 AND trashedAt IS NULL ORDER BY updatedAt DESC")
    fun observeArchived(): Flow<List<NoteEntity>>

    @Query("SELECT id FROM notes WHERE trashedAt IS NOT NULL AND trashedAt < :before")
    suspend fun trashedBefore(before: Long): List<String>

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM categories")
    suspend fun categories(): List<CategoryEntity>

    @Query("SELECT * FROM categories ORDER BY position ASC")
    fun observeCategories(): Flow<List<CategoryEntity>>

    @Query("SELECT MIN(position) FROM notes")
    suspend fun minPosition(): Double?

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun categoryCount(): Int

    @Query("SELECT MAX(position) FROM categories")
    suspend fun maxCategoryPosition(): Int?

    @Upsert
    suspend fun upsertCategory(category: CategoryEntity)

    @Query("UPDATE categories SET name = :name, icon = :icon WHERE id = :id")
    suspend fun editCategory(id: String, name: String, icon: String)

    @Query("UPDATE categories SET position = :position WHERE id = :id")
    suspend fun setCategoryPosition(id: String, position: Int)

    @Query("UPDATE categories SET parent = :parent, position = :position WHERE id = :id")
    suspend fun placeCategory(id: String, parent: String, position: Int)

    @Query("UPDATE categories SET parent = :parent WHERE id IN (:ids)")
    suspend fun setCategoryParent(ids: List<String>, parent: String)

    @Query("UPDATE categories SET share = :share WHERE id = :id")
    suspend fun setCategoryShare(id: String, share: String)

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun deleteCategory(id: String)

    @Query("SELECT id FROM notes WHERE categoryId = :cat AND trashedAt IS NULL")
    suspend fun liveNoteIdsIn(cat: String): List<String>

    @Upsert
    suspend fun upsert(note: NoteEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCategories(categories: List<CategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNotes(notes: List<NoteEntity>)

    @Query("UPDATE notes SET trashedAt = :at, updatedAt = :at WHERE id = :id")
    suspend fun trash(id: String, at: Long)

    @Query("UPDATE notes SET trashedAt = NULL, updatedAt = :at WHERE id = :id")
    suspend fun restore(id: String, at: Long)

    @Query("UPDATE notes SET pinned = NOT pinned, updatedAt = :at WHERE id = :id")
    suspend fun togglePin(id: String, at: Long)

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun get(id: String): NoteEntity?

    @Query("UPDATE notes SET title = :title, blocks = :blocks, updatedAt = :at WHERE id = :id")
    suspend fun setContent(id: String, title: String, blocks: String, at: Long)

    @Query("UPDATE notes SET blocks = :blocks, updatedAt = :at WHERE id = :id")
    suspend fun setBlocks(id: String, blocks: String, at: Long)

    @Query("UPDATE notes SET pinned = :on, updatedAt = :at WHERE id IN (:ids)")
    suspend fun setPinned(ids: List<String>, on: Boolean, at: Long)

    @Query("UPDATE notes SET favorite = :on, updatedAt = :at WHERE id IN (:ids)")
    suspend fun setFavorite(ids: List<String>, on: Boolean, at: Long)

    @Query("UPDATE notes SET color = :color, updatedAt = :at WHERE id IN (:ids)")
    suspend fun setColor(ids: List<String>, color: String, at: Long)

    @Query("UPDATE notes SET categoryId = :cat, updatedAt = :at WHERE id IN (:ids)")
    suspend fun setCategory(ids: List<String>, cat: String, at: Long)

    @Query("UPDATE notes SET archived = :on, updatedAt = :at WHERE id IN (:ids)")
    suspend fun setArchived(ids: List<String>, on: Boolean, at: Long)

    @Query("UPDATE notes SET trashedAt = :trashedAt, updatedAt = :at WHERE id IN (:ids)")
    suspend fun setTrashed(ids: List<String>, trashedAt: Long?, at: Long)

    /** Sync bookkeeping: not an edit, so updatedAt stays. */
    @Query("UPDATE notes SET share = :share WHERE id = :id")
    suspend fun setShare(id: String, share: String)

    @Query("UPDATE notes SET position = :position WHERE id = :id")
    suspend fun setPosition(id: String, position: Double)
}
