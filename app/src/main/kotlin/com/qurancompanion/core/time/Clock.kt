package com.qurancompanion.core.time

import android.os.SystemClock
import javax.inject.Inject

/** Time source abstraction so session logic is JVM-testable. */
interface Clock {
    /** Wall-clock time in epoch millis (for persisted timestamps). */
    fun nowMillis(): Long

    /** Monotonic time in millis (for durations; unaffected by wall-clock changes). */
    fun elapsedRealtime(): Long
}

class SystemClockImpl @Inject constructor() : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
    override fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()
}
