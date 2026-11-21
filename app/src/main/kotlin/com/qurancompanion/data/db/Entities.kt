package com.qurancompanion.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
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

/**
 * A word flagged in a session: a mistake the feedback policy surfaced. Position and type only —
 * **no audio, no transcript** (the recognized text is never stored). Deleted with its session.
 */
@Entity(
    tableName = "flagged_words",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class FlaggedWordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val surah: Int,
    val ayah: Int,
    /** 1-based word index within the ayah. */
    val wordIndex: Int,
    /** Engine MistakeType name (SUBSTITUTION, DELETION, ORDER, INSERTION). */
    val mistakeType: String,
    val flaggedAt: Long,
)
