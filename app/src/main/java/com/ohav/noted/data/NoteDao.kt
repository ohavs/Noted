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

    @Query("SELECT * FROM categories ORDER BY position ASC")
    fun observeCategories(): Flow<List<CategoryEntity>>

    @Query("SELECT MIN(position) FROM notes")
    suspend fun minPosition(): Double?

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun categoryCount(): Int

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
}
