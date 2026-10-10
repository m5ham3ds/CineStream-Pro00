package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.model.LibraryItem
import com.example.data.model.DownloadItem
import com.example.data.model.HistoryItem
import com.example.data.model.WatchedEpisode
import com.example.data.model.NotificationItem
import com.example.data.model.SupportMessage

@Database(
    entities = [
        LibraryItem::class,
        DownloadItem::class,
        HistoryItem::class,
        WatchedEpisode::class,
        NotificationItem::class,
        SupportMessage::class
    ],
    version = 9,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun notificationDao(): NotificationDao
    abstract fun supportDao(): SupportDao
    abstract fun libraryDao(): LibraryDao
    abstract fun downloadDao(): DownloadDao
    abstract fun historyDao(): HistoryDao
    abstract fun watchedEpisodeDao(): WatchedEpisodeDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Helper to ensure all pre-v9 tables exist safely without destroying existing records.
         * Enforces strict data preservation for users upgrading across historical versions.
         */
        fun ensurePreV9TablesExist(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `library_items` (
                    `id` TEXT NOT NULL,
                    `title` TEXT NOT NULL,
                    `posterUrl` TEXT NOT NULL,
                    `isMovie` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `download_items` (
                    `id` TEXT NOT NULL,
                    `mediaId` TEXT NOT NULL,
                    `title` TEXT NOT NULL,
                    `posterUrl` TEXT NOT NULL,
                    `isMovie` INTEGER NOT NULL,
                    `quality` TEXT NOT NULL,
                    `progress` REAL NOT NULL,
                    `isPaused` INTEGER NOT NULL,
                    `isCompleted` INTEGER NOT NULL,
                    `fileSizeBytes` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `history_items` (
                    `id` TEXT NOT NULL,
                    `title` TEXT NOT NULL,
                    `posterUrl` TEXT NOT NULL,
                    `isMovie` INTEGER NOT NULL,
                    `timestamp` INTEGER NOT NULL,
                    `positionMillis` INTEGER NOT NULL,
                    `durationMillis` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `watched_episodes` (
                    `id` TEXT NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `notifications` (
                    `id` TEXT NOT NULL,
                    `title` TEXT NOT NULL,
                    `message` TEXT NOT NULL,
                    `timestamp` INTEGER NOT NULL,
                    `isRead` INTEGER NOT NULL,
                    `imageUrl` TEXT,
                    `type` TEXT NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `support_messages` (
                    `id` TEXT NOT NULL,
                    `text` TEXT NOT NULL,
                    `isFromUser` INTEGER NOT NULL,
                    `timestamp` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ensurePreV9TablesExist(db)
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ensurePreV9TablesExist(db)
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ensurePreV9TablesExist(db)
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ensurePreV9TablesExist(db)
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ensurePreV9TablesExist(db)
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ensurePreV9TablesExist(db)
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ensurePreV9TablesExist(db)
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Create new table matching canonical LibraryItem schema
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `library_items_new` (
                        `libraryId` TEXT NOT NULL,
                        `tmdbId` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `posterUrl` TEXT NOT NULL,
                        `contentType` TEXT NOT NULL,
                        `isMovie` INTEGER NOT NULL,
                        PRIMARY KEY(`libraryId`)
                    )
                    """.trimIndent()
                )

                // 2. Check if legacy library_items exists
                val cursor = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='library_items'")
                val tableExists = cursor.count > 0
                cursor.close()

                if (tableExists) {
                    val colCursor = db.query("PRAGMA table_info(`library_items`)")
                    var hasLibraryId = false
                    while (colCursor.moveToNext()) {
                        val nameIndex = colCursor.getColumnIndex("name")
                        if (nameIndex >= 0 && colCursor.getString(nameIndex) == "libraryId") {
                            hasLibraryId = true
                            break
                        }
                    }
                    colCursor.close()

                    if (!hasLibraryId) {
                        // 3. Safely migrate legacy data:
                        //    - isMovie == 1 -> contentType = 'movie', libraryId = 'movie_' || id
                        //    - isMovie == 0 -> contentType = 'tv', libraryId = 'tv_' || id (NEVER assumed anime)
                        db.execSQL(
                            """
                            INSERT OR REPLACE INTO `library_items_new` (`libraryId`, `tmdbId`, `title`, `posterUrl`, `contentType`, `isMovie`)
                            SELECT
                                CASE WHEN `isMovie` = 1 THEN 'movie_' || `id` ELSE 'tv_' || `id` END,
                                `id`,
                                `title`,
                                `posterUrl`,
                                CASE WHEN `isMovie` = 1 THEN 'movie' ELSE 'tv' END,
                                `isMovie`
                            FROM `library_items`
                            """.trimIndent()
                        )
                        db.execSQL("DROP TABLE `library_items`")
                        db.execSQL("ALTER TABLE `library_items_new` RENAME TO `library_items`")
                    } else {
                        db.execSQL("DROP TABLE `library_items_new`")
                    }
                } else {
                    db.execSQL("ALTER TABLE `library_items_new` RENAME TO `library_items`")
                }
            }
        }

        val ALL_MIGRATIONS = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9
        )

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "cinestream-db"
                )
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(*ALL_MIGRATIONS)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
