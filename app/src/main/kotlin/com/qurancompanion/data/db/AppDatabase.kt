package com.qurancompanion.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter fun statusToString(s: SessionStatus): String = s.name
    @TypeConverter fun stringToStatus(s: String): SessionStatus =
        runCatching { SessionStatus.valueOf(s) }.getOrDefault(SessionStatus.INTERRUPTED)
}

/** User data (sessions, bookmarks). The Quran reference text lives in a separate read-only DB. */
@Database(entities = [SessionEntity::class, BookmarkEntity::class], version = 1, exportSchema = true)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun bookmarkDao(): BookmarkDao

    companion object {
        const val NAME = "quran_companion_user.db"
    }
}
