package com.ohav.noted.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val position: Int,
    /** Key into the category icon set (see ui/categories). */
    @ColumnInfo(defaultValue = "folder") val icon: String = "folder",
    /** The category this one sits under ("" = top level). One level only — see [CatTree]. */
    @ColumnInfo(defaultValue = "") val parent: String = "",
)

/**
 * A note. Content is an ordered list of blocks stored as JSON (see [Blocks]); the editor
 * (stage 4) owns that format. Order inside the stack is a fractional [position] so a move
 * rewrites one row.
 */
@Entity(tableName = "notes", indices = [Index("categoryId"), Index("trashedAt")])
data class NoteEntity(
    @PrimaryKey val id: String,
    val title: String,
    val blocks: String,
    val color: String,
    val categoryId: String,
    val pinned: Boolean = false,
    val favorite: Boolean = false,
    val archived: Boolean = false,
    val trashedAt: Long? = null,
    val reminderAt: Long? = null,
    val position: Double,
    val createdAt: Long,
    val updatedAt: Long,
    /** Who the note is shared with, as sync last saw it ([Share] JSON; "" = only mine). */
    @ColumnInfo(defaultValue = "") val share: String = "",
)
