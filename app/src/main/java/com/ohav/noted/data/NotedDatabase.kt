package com.ohav.noted.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [NoteEntity::class, CategoryEntity::class, ReminderEntity::class, PlaceEntity::class, PlaceReminderEntity::class, ShadowEntity::class, MediaSyncEntity::class], version = 5, exportSchema = true)
abstract class NotedDatabase : RoomDatabase() {
    abstract fun notes(): NoteDao
    abstract fun reminders(): ReminderDao
    abstract fun places(): PlaceDao
    abstract fun sync(): SyncDao

    companion object {
        /** v2: categories get an icon; the seeded ones keep the icons they always showed. */
        private val M1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE categories ADD COLUMN icon TEXT NOT NULL DEFAULT 'folder'")
                db.execSQL("UPDATE categories SET icon = id WHERE id IN ('home', 'work', 'ideas')")
            }
        }

        /** v3: time reminders (stage 6). */
        private val M2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `reminders` (`id` TEXT NOT NULL, `noteId` TEXT NOT NULL, `blockId` TEXT NOT NULL, " +
                        "`anchor` TEXT NOT NULL, `occ` TEXT NOT NULL, `zone` TEXT NOT NULL, `rule` TEXT NOT NULL, `until` TEXT, " +
                        "`fired` INTEGER NOT NULL, `snoozeAt` INTEGER, PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_reminders_noteId` ON `reminders` (`noteId`)")
                db.execSQL("UPDATE notes SET reminderAt = NULL")
            }
        }

        /** v4: places and place reminders (stage 7). */
        private val M3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `places` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                        "`lat` REAL NOT NULL, `lng` REAL NOT NULL, `radius` INTEGER NOT NULL, `address` TEXT NOT NULL, " +
                        "`position` REAL NOT NULL, `createdAt` INTEGER NOT NULL, `armIn` INTEGER NOT NULL, `armOut` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `place_reminders` (`id` TEXT NOT NULL, `noteId` TEXT NOT NULL, `blockId` TEXT NOT NULL, " +
                        "`placeId` TEXT NOT NULL, `leave` INTEGER NOT NULL, `always` INTEGER NOT NULL, `days` TEXT NOT NULL, " +
                        "`fromMin` INTEGER, `toMin` INTEGER, `fired` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_place_reminders_noteId` ON `place_reminders` (`noteId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_place_reminders_placeId` ON `place_reminders` (`placeId`)")
            }
        }

        /** v5: cloud sync bookkeeping (stage 8). */
        private val M4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `sync_shadows` (`id` TEXT NOT NULL, `json` TEXT NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `media_sync` (`name` TEXT NOT NULL, `owner` TEXT NOT NULL, `uploaded` INTEGER NOT NULL, PRIMARY KEY(`name`))")
            }
        }

        fun create(context: Context): NotedDatabase =
            Room.databaseBuilder(context, NotedDatabase::class.java, "noted.db").addMigrations(M1_2, M2_3, M3_4, M4_5).build()
    }
}
