package com.qurancompanion.data.session

import com.qurancompanion.data.db.BookmarkDao
import com.qurancompanion.data.db.BookmarkEntity
import com.qurancompanion.data.db.FlaggedWordDao
import com.qurancompanion.data.db.FlaggedWordEntity
import com.qurancompanion.data.db.SessionDao
import com.qurancompanion.data.db.SessionEntity
import com.qurancompanion.data.db.SessionStatus
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

interface SessionRepository {
    fun observeSessions(): Flow<List<SessionEntity>>
    fun observeLatestUnfinished(): Flow<SessionEntity?>
    suspend fun latestUnfinished(): SessionEntity?
    suspend fun get(id: Long): SessionEntity?
    suspend fun create(session: SessionEntity): Long
    suspend fun updatePosition(id: Long, surah: Int, ayah: Int, word: Int, durationMs: Long)
    /** Sets the start ayah once the engine has located where the user actually began. */
    suspend fun updateStart(id: Long, surah: Int, ayah: Int)
    suspend fun updateStatus(id: Long, status: SessionStatus, durationMs: Long, endedAt: Long?)
    /** Deletes the session and its flagged words. */
    suspend fun delete(id: Long)
    suspend fun deleteAll()
    /** Records a flagged word (position + mistake type only) for session [FlaggedWordEntity.sessionId]. */
    suspend fun addFlag(flag: FlaggedWordEntity)
    fun observeFlags(sessionId: Long): Flow<List<FlaggedWordEntity>>
}

interface BookmarkRepository {
    fun observeBookmarks(): Flow<List<BookmarkEntity>>
    suspend fun add(surah: Int, ayah: Int, createdAt: Long)
    suspend fun remove(surah: Int, ayah: Int)
}

@Singleton
class RoomSessionRepository @Inject constructor(
    private val dao: SessionDao,
    private val flags: FlaggedWordDao,
) : SessionRepository {
    override fun observeSessions() = dao.observeAll()
    override fun observeLatestUnfinished() = dao.observeLatestUnfinished()
    override suspend fun latestUnfinished() = dao.latestUnfinished()
    override suspend fun get(id: Long) = dao.get(id)
    override suspend fun create(session: SessionEntity) = dao.insert(session)
    override suspend fun updatePosition(id: Long, surah: Int, ayah: Int, word: Int, durationMs: Long) =
        dao.updatePosition(id, surah, ayah, word, durationMs)
    override suspend fun updateStart(id: Long, surah: Int, ayah: Int) = dao.updateStart(id, surah, ayah)
    override suspend fun updateStatus(id: Long, status: SessionStatus, durationMs: Long, endedAt: Long?) =
        dao.updateStatus(id, status, durationMs, endedAt)
    // The foreign key cascades too; the explicit delete keeps it correct even with FKs off.
    override suspend fun delete(id: Long) { flags.deleteForSession(id); dao.delete(id) }
    override suspend fun deleteAll() { flags.deleteAll(); dao.deleteAll() }
    override suspend fun addFlag(flag: FlaggedWordEntity) { flags.insert(flag) }
    override fun observeFlags(sessionId: Long) = flags.observeForSession(sessionId)
}

@Singleton
class RoomBookmarkRepository @Inject constructor(private val dao: BookmarkDao) : BookmarkRepository {
    override fun observeBookmarks() = dao.observeAll()
    override suspend fun add(surah: Int, ayah: Int, createdAt: Long) {
        dao.upsert(BookmarkEntity(surah = surah, ayah = ayah, createdAt = createdAt))
    }
    override suspend fun remove(surah: Int, ayah: Int) = dao.delete(surah, ayah)
}
