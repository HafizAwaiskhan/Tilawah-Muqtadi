package com.qurancompanion.session

import com.qurancompanion.data.reference.QuranPosition
import com.qurancompanion.engine.reference.WordPosition
import com.qurancompanion.engine.session.SessionState as EngineState
import com.qurancompanion.engine.session.SessionUiState

/** Coarse app-level status derived from the engine state (notification, Home, service gating). */
enum class ListeningStatus {
    /** No session (or a stopped one whose summary was dismissed). */
    IDLE,
    /** A session is running: mic capture + recognition (hint playback included). */
    LISTENING,
    /** Paused by the user (mic released). */
    PAUSED,
    /** Paused by the system: call, focus loss, headset unplugged, capture error. */
    INTERRUPTED,
    /** Session finished by the user; summary shown until a new session starts. */
    STOPPED;

    val isRunning: Boolean get() = this == LISTENING || this == PAUSED || this == INTERRUPTED
}

enum class InterruptionReason { CALL, AUDIO_FOCUS, HEADSET_UNPLUGGED, CAPTURE_ERROR, PERMISSION_DENIED }

/** Readiness of position tracking (engine reference + controller), shown on Home. */
enum class TrackingReadiness { FETCHING_REFERENCE, READY, UNAVAILABLE }

/**
 * Live session as seen by the UI: the engine's [SessionUiState] plus Android-side facts
 * (Room id, interruption reason, playback isolation, transient notices).
 */
data class LiveSession(
    val engine: SessionUiState = SessionUiState(),
    val readiness: TrackingReadiness = TrackingReadiness.FETCHING_REFERENCE,
    val referenceLoadMs: Long? = null,
    /** Room id of the persisted session row. */
    val sessionId: Long? = null,
    val startPosition: QuranPosition? = null,
    /** Where the user asked to start (Quran screen); only used for display until located. */
    val requestedStart: QuranPosition? = null,
    /** Active time (paused/interrupted excluded), from the controller snapshot, whole seconds. */
    val durationMs: Long = 0,
    val interruption: InterruptionReason? = null,
    /** True while Qari audio plays (plus a short tail): no audio reaches the recognizer. */
    val recognitionSuppressed: Boolean = false,
    /** Gentle, non-blocking message (e.g. "Qari audio is not downloaded yet"). */
    val notice: String? = null,
    val summaryDismissed: Boolean = false,
    /** Words flagged (surfaced mistakes) in the current/last session, in order. */
    val flagged: List<FlaggedWord> = emptyList(),
    /** "New session" is switching sessions: the STOPPED in between must not end the service. */
    val restarting: Boolean = false,
) {
    val state: EngineState get() = engine.state

    val status: ListeningStatus
        get() = when (engine.state) {
            EngineState.PAUSED -> ListeningStatus.PAUSED
            EngineState.INTERRUPTED -> ListeningStatus.INTERRUPTED
            EngineState.STOPPED -> if (summaryDismissed) ListeningStatus.IDLE else ListeningStatus.STOPPED
            in EngineState.IN_SESSION -> ListeningStatus.LISTENING
            else -> ListeningStatus.IDLE
        }

    val isPlayingHint: Boolean get() = engine.state in EngineState.PLAYING

    /** Word to highlight: the candidate (includes provisional tokens), else the confirmed word. */
    val current: WordPosition? get() = engine.candidate ?: engine.confirmed

    /** Last confirmed position, or where the session was asked to start. */
    val position: QuranPosition
        get() = engine.confirmed?.toQuranPosition() ?: requestedStart ?: startPosition ?: QuranPosition.START

    /** Whether microphone audio should currently flow to the recognizer. */
    val audioGateOpen: Boolean
        get() = status == ListeningStatus.LISTENING && !recognitionSuppressed && !isPlayingHint
}

fun WordPosition.toQuranPosition() = QuranPosition(surah, ayah, wordIndex)

/** A flagged word of the live session (position + mistake type; never audio or transcript). */
data class FlaggedWord(
    val wordId: Int,
    val surah: Int,
    val ayah: Int,
    /** 1-based. */
    val wordIndex: Int,
    /** Engine MistakeType name. */
    val type: String,
    val time: Long,
)
