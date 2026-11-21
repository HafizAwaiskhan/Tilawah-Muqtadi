package com.qurancompanion.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter fun statusToString(s: SessionStatus): String = s.name
    @TypeConverter fun stringToStatus(s: String): SessionStatus =
        runCatching { SessionStatus.valueOf(s) }.getOrDefault(SessionStatus.INTERRUPTED)
}

/** User data (sessions, bookmarks). The Quran reference text lives in a separate read-only DB. */
@Database(
    entities = [SessionEntity::class, BookmarkEntity::class, FlaggedWordEntity::class],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun flaggedWordDao(): FlaggedWordDao

    companion object {
        const val NAME = "quran_companion_user.db"

        /** v1 → v2: adds `flagged_words` (must match schemas/…/2.json; checked by RoomMigrationTest). */
        val MIGRATION_1_2_SQL: List<String> = listOf(
            "CREATE TABLE IF NOT EXISTS `flagged_words` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`sessionId` INTEGER NOT NULL, `surah` INTEGER NOT NULL, `ayah` INTEGER NOT NULL, " +
                "`wordIndex` INTEGER NOT NULL, `mistakeType` TEXT NOT NULL, `flaggedAt` INTEGER NOT NULL, " +
                "FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_flagged_words_sessionId` ON `flagged_words` (`sessionId`)",
        )

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) = MIGRATION_1_2_SQL.forEach(db::execSQL)
        }

        val MIGRATIONS = arrayOf(MIGRATION_1_2)
    }
}
