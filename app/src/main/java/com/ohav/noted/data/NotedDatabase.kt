package com.ohav.noted.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [NoteEntity::class, CategoryEntity::class], version = 2, exportSchema = true)
abstract class NotedDatabase : RoomDatabase() {
    abstract fun notes(): NoteDao

    companion object {
        /** v2: categories get an icon; the seeded ones keep the icons they always showed. */
        private val M1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE categories ADD COLUMN icon TEXT NOT NULL DEFAULT 'folder'")
                db.execSQL("UPDATE categories SET icon = id WHERE id IN ('home', 'work', 'ideas')")
            }
        }

        fun create(context: Context): NotedDatabase =
            Room.databaseBuilder(context, NotedDatabase::class.java, "noted.db").addMigrations(M1_2).build()
    }
}
