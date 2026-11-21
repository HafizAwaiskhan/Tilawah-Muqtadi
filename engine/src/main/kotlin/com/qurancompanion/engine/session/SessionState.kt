package com.qurancompanion.engine.session

import com.qurancompanion.engine.session.SessionState.*

enum class SessionState {
    IDLE, INITIALIZING, CHECKING_PERMISSIONS, DOWNLOADING_MODEL, FETCHING_REFERENCE, READY,
    STARTING_SESSION, LOCATING_POSITION, RECITING, PROCESSING, POSSIBLE_MISTAKE, SELF_CORRECTION_WINDOW,
    PAUSE_DETECTED, ASSISTANCE_OFFERED, WAITING_FOR_CONFIRMATION, PLAYING_LUKMA, PLAYING_FULL_AYAH,
    WAITING_FOR_RETRY, RESUMING, PAUSED, INTERRUPTED, RECOVERING_SESSION, STOPPED, ERROR;

    companion object {
        /** States in which recognition events are processed. */
        val LISTENING: Set<SessionState> = setOf(
            LOCATING_POSITION, RECITING, SELF_CORRECTION_WINDOW, POSSIBLE_MISTAKE, PAUSE_DETECTED,
            WAITING_FOR_CONFIRMATION, WAITING_FOR_RETRY,
        )
        val PLAYING: Set<SessionState> = setOf(PLAYING_LUKMA, PLAYING_FULL_AYAH)
        /** A session exists (Stop is meaningful). */
        val IN_SESSION: Set<SessionState> = LISTENING + PLAYING + setOf(
            STARTING_SESSION, PROCESSING, ASSISTANCE_OFFERED, RESUMING, PAUSED, INTERRUPTED, RECOVERING_SESSION,
        )
        /** Session time counts in these states. */
        val ACTIVE: Set<SessionState> = IN_SESSION - setOf(PAUSED, INTERRUPTED)
    }
}

/** Explicit transition table. ERROR is reachable from every state; STOPPED from every in-session state. */
object SessionTransitions {
    private val recitingLike = setOf(RECITING, SELF_CORRECTION_WINDOW, POSSIBLE_MISTAKE, PAUSE_DETECTED)
    private val hold = setOf(PAUSED, INTERRUPTED)
    private val hint = setOf(PLAYING_LUKMA, PLAYING_FULL_AYAH)

    private val table: Map<SessionState, Set<SessionState>> = mapOf(
        IDLE to setOf(INITIALIZING),
        INITIALIZING to setOf(CHECKING_PERMISSIONS),
        CHECKING_PERMISSIONS to setOf(DOWNLOADING_MODEL),
        DOWNLOADING_MODEL to setOf(FETCHING_REFERENCE),
        FETCHING_REFERENCE to setOf(READY),
        READY to setOf(STARTING_SESSION, RECOVERING_SESSION),
        STARTING_SESSION to setOf(LOCATING_POSITION),
        RECOVERING_SESSION to setOf(RECITING, LOCATING_POSITION),
        LOCATING_POSITION to setOf(PROCESSING) + hold,
        PROCESSING to setOf(LOCATING_POSITION, RESUMING, WAITING_FOR_CONFIRMATION, WAITING_FOR_RETRY) + recitingLike,
        RECITING to setOf(PROCESSING, PAUSE_DETECTED, POSSIBLE_MISTAKE) + hint + hold,
        SELF_CORRECTION_WINDOW to setOf(PROCESSING, PAUSE_DETECTED, POSSIBLE_MISTAKE, RECITING) + hint + hold,
        POSSIBLE_MISTAKE to setOf(PROCESSING, PAUSE_DETECTED, RECITING) + hint + hold,
        PAUSE_DETECTED to setOf(PROCESSING, RECITING, SELF_CORRECTION_WINDOW, POSSIBLE_MISTAKE, ASSISTANCE_OFFERED) + hint + hold,
        ASSISTANCE_OFFERED to setOf(WAITING_FOR_CONFIRMATION),
        WAITING_FOR_CONFIRMATION to setOf(PROCESSING, RECITING, SELF_CORRECTION_WINDOW) + hint + hold,
        PLAYING_LUKMA to setOf(WAITING_FOR_RETRY) + hold,
        PLAYING_FULL_AYAH to setOf(WAITING_FOR_RETRY) + hold,
        WAITING_FOR_RETRY to setOf(PROCESSING) + hint + hold,
        RESUMING to setOf(RECITING, LOCATING_POSITION, SELF_CORRECTION_WINDOW, POSSIBLE_MISTAKE),
        PAUSED to setOf(RESUMING, INTERRUPTED),
        INTERRUPTED to setOf(RESUMING),
        STOPPED to setOf(IDLE, STARTING_SESSION, RECOVERING_SESSION),
        ERROR to setOf(IDLE, INITIALIZING),
    )

    fun allowed(from: SessionState): Set<SessionState> {
        val base = table[from].orEmpty() + ERROR
        return if (from in SessionState.IN_SESSION) base + STOPPED else base
    }

    fun canTransition(from: SessionState, to: SessionState) = to != from && to in allowed(from)
}
