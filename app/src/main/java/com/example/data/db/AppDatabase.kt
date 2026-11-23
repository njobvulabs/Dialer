package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ContactEntity::class,
        CallLogEntity::class,
        BlockedNumberEntity::class,
        SpeedDialEntity::class,
        BookmarkEntity::class,
        HistoryEntity::class,
        NoteEntity::class,
        PlaylistEntity::class,
        PlaylistItemEntity::class,
        LocalSmsEntity::class,
        CalendarEventEntity::class
    ],
    version = 10,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun callLogDao(): CallLogDao

    abstract fun blockedNumberDao(): BlockedNumberDao
    abstract fun speedDialDao(): SpeedDialDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun historyDao(): HistoryDao
    abstract fun noteDao(): NoteDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun localSmsDao(): LocalSmsDao
    abstract fun calendarEventDao(): CalendarEventDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Table schema created or updated in v2
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE call_logs ADD COLUMN note TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `notifications` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`packageName` TEXT NOT NULL, " +
                        "`appName` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`text` TEXT NOT NULL, " +
                        "`timestamp` INTEGER NOT NULL, " +
                        "`isRead` INTEGER NOT NULL, " +
                        "`canReply` INTEGER NOT NULL, " +
                        "`canOpen` INTEGER NOT NULL, " +
                        "`replyKey` TEXT NOT NULL, " +
                        "`replyAction` TEXT NOT NULL, " +
                        "`tag` TEXT NOT NULL, " +
                        "`notificationId` INTEGER NOT NULL)"
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Notifications feature removed entirely
                db.execSQL("DROP TABLE IF EXISTS `notifications`")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `local_sms` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`threadId` INTEGER NOT NULL, " +
                        "`address` TEXT NOT NULL, " +
                        "`body` TEXT NOT NULL, " +
                        "`date` INTEGER NOT NULL, " +
                        "`simSlot` INTEGER NOT NULL, " +
                        "`status` TEXT NOT NULL)"
                )
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN isTodo INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE notes ADD COLUMN done INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * B8: one row per visited URL with a visit counter, which is what feeds the
         * new-tab page's "Top sites". Existing rows default to a single visit, so
         * old history still appears (just unranked relative to repeat visits).
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE browser_history ADD COLUMN visitCount INTEGER NOT NULL DEFAULT 1")
                // The pre-B8 history was append-only, so the same URL can already
                // occupy many rows. Fold them into one row per URL, carrying the
                // merged row count into visitCount — otherwise "Top sites" would
                // list the same site N times, and bumpVisit would inflate counts
                // on every leftover duplicate. The newest row (highest id) wins,
                // since the list itself is ordered by recency.
                db.execSQL(
                    "UPDATE browser_history SET visitCount = " +
                        "(SELECT COUNT(*) FROM browser_history b WHERE b.url = browser_history.url)"
                )
                db.execSQL(
                    "DELETE FROM browser_history WHERE id NOT IN " +
                        "(SELECT MAX(id) FROM browser_history GROUP BY url)"
                )
            }
        }

        /**
         * B12: explicit playlist ordering. Rows created before this migration all
         * default to position 0, so they keep their previous (id) order until the
         * user moves something.
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE playlist_items ADD COLUMN position INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `calendar_events` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`title` TEXT NOT NULL, `details` TEXT NOT NULL, " +
                        "`startAt` INTEGER NOT NULL, `endAt` INTEGER NOT NULL, " +
                        "`reminderMinutes` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)"
                )
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "dialer_app_db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
