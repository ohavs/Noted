package com.ohav.noted.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [NoteEntity::class, CategoryEntity::class], version = 1, exportSchema = true)
abstract class NotedDatabase : RoomDatabase() {
    abstract fun notes(): NoteDao

    companion object {
        fun create(context: Context): NotedDatabase =
            Room.databaseBuilder(context, NotedDatabase::class.java, "noted.db").build()
    }
}
