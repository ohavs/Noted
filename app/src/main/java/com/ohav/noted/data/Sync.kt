package com.ohav.noted.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/**
 * The last state of a synced row that this device and the cloud agreed on ("base" of a
 * three-way merge). [id] is "n:<noteId>" or "c:<categoryId>"; [json] is the row as the cloud has it.
 */
@Entity(tableName = "sync_shadows")
data class ShadowEntity(@PrimaryKey val id: String, val json: String)

/** A media file and whether the cloud already has it. [owner] = uid whose folder holds it. */
@Entity(tableName = "media_sync")
data class MediaSyncEntity(@PrimaryKey val name: String, val owner: String, val uploaded: Boolean)

@Dao
interface SyncDao {
    @Query("SELECT * FROM sync_shadows")
    suspend fun shadows(): List<ShadowEntity>

    @Query("SELECT * FROM sync_shadows WHERE id = :id")
    suspend fun shadow(id: String): ShadowEntity?

    @Upsert
    suspend fun putShadow(s: ShadowEntity)

    @Query("DELETE FROM sync_shadows WHERE id = :id")
    suspend fun dropShadow(id: String)

    @Query("DELETE FROM sync_shadows")
    suspend fun clearShadows()

    @Query("SELECT * FROM media_sync")
    suspend fun media(): List<MediaSyncEntity>

    @Upsert
    suspend fun putMedia(m: MediaSyncEntity)

    @Query("DELETE FROM media_sync")
    suspend fun clearMedia()
}
