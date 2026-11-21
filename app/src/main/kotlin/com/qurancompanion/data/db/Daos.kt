package com.qurancompanion.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Insert
    suspend fun insert(session: SessionEntity): Long

    @Update
    suspend fun update(session: SessionEntity)

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun get(id: Long): SessionEntity?

    @Query("SELECT * FROM sessions ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE status != 'COMPLETED' ORDER BY startedAt DESC LIMIT 1")
    fun observeLatestUnfinished(): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions WHERE status != 'COMPLETED' ORDER BY startedAt DESC LIMIT 1")
    suspend fun latestUnfinished(): SessionEntity?

    @Query(
        "UPDATE sessions SET lastSurah = :surah, lastAyah = :ayah, lastWord = :word, durationMs = :durationMs " +
            "WHERE id = :id"
    )
    suspend fun updatePosition(id: Long, surah: Int, ayah: Int, word: Int, durationMs: Long)

    @Query("UPDATE sessions SET startSurah = :surah, startAyah = :ayah WHERE id = :id")
    suspend fun updateStart(id: Long, surah: Int, ayah: Int)

    @Query("UPDATE sessions SET status = :status, durationMs = :durationMs, endedAt = :endedAt WHERE id = :id")
    suspend fun updateStatus(id: Long, status: SessionStatus, durationMs: Long, endedAt: Long?)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM sessions")
    suspend fun deleteAll()
}

@Dao
interface BookmarkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(bookmark: BookmarkEntity): Long

    @Query("DELETE FROM bookmarks WHERE surah = :surah AND ayah = :ayah")
    suspend fun delete(surah: Int, ayah: Int)

    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<BookmarkEntity>>

    @Query("DELETE FROM bookmarks")
    suspend fun deleteAll()
}
