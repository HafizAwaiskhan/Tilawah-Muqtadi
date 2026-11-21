package com.qurancompanion.session

import com.qurancompanion.data.reference.QuranPosition

enum class ListeningStatus {
    /** No session in this process. */
    IDLE,
    /** Mic capture running, audio fed to the recognizer. */
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

enum class HintKind { NEXT_WORD, FULL_AYAH }

sealed interface AssistanceState {
    data object None : AssistanceState
    /** Gentle offer (user pressed Lukma, or — later — PauseDetector proposed help). */
    data class Offered(val position: QuranPosition) : AssistanceState
    data class Playing(val kind: HintKind, val position: QuranPosition) : AssistanceState
    data class Unavailable(val message: String) : AssistanceState
}

data class SessionState(
    val sessionId: Long? = null,
    val status: ListeningStatus = ListeningStatus.IDLE,
    val startPosition: QuranPosition = QuranPosition.START,
    /** Last confirmed position. Never moved by hint playback. */
    val position: QuranPosition = QuranPosition.START,
    /** Active listening time accumulated before [activeSinceElapsed]. */
    val accumulatedMs: Long = 0,
    /** Monotonic time listening (re)started, null when not listening. */
    val activeSinceElapsed: Long? = null,
    val assistance: AssistanceState = AssistanceState.None,
    /** True while Qari audio plays (plus a short tail): no audio reaches the recognizer. */
    val recognitionSuppressed: Boolean = false,
    val interruption: InterruptionReason? = null,
) {
    fun durationMs(nowElapsed: Long): Long =
        accumulatedMs + (activeSinceElapsed?.let { (nowElapsed - it).coerceAtLeast(0) } ?: 0)

    /** Whether microphone audio should currently flow to the recognizer. */
    val audioGateOpen: Boolean get() = status == ListeningStatus.LISTENING && !recognitionSuppressed
}
