package com.qurancompanion.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Lifecycle status of a persisted recitation session. Stored by name. */
enum class SessionStatus {
    ACTIVE,
    PAUSED,
    INTERRUPTED,
    COMPLETED;

    /** Sessions in these states were not stopped by the user and can be resumed. */
    val isResumable: Boolean get() = this != COMPLETED
}

/**
 * One recitation session. Only positions and timings are stored: **no raw audio, no
 * transcripts** are ever persisted.
 */
@Entity(tableName = "sessions", indices = [Index("startedAt")])
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long? = null,
    /** Active listening time (paused/interrupted time excluded). */
    val durationMs: Long = 0,
    val status: SessionStatus = SessionStatus.ACTIVE,
    val startSurah: Int,
    val startAyah: Int,
    val lastSurah: Int,
    val lastAyah: Int,
    /** 1-based word index within [lastAyah]; 0 = before the first word. */
    val lastWord: Int = 0,
    val notes: String? = null,
)

@Entity(tableName = "bookmarks", indices = [Index(value = ["surah", "ayah"], unique = true)])
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val surah: Int,
    val ayah: Int,
    val createdAt: Long,
    val label: String? = null,
)
