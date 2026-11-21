package com.qurancompanion.engine.session

import com.qurancompanion.engine.recognition.RecognitionEvent
import com.qurancompanion.engine.recognition.Token

/**
 * Drops recognition produced by our own Qari playback: any token overlapping a playback window
 * (extended by [tailMs]) or the event's suppressed window, and everything while playback is open.
 * Tokens without timestamps use the event timestamp.
 */
class PlaybackGuard(private val tailMs: Long = 300, private val keepWindows: Int = 8) {
    private val windows = ArrayDeque<LongRange>()
    private var openSince: Long? = null

    val isPlaying: Boolean get() = openSince != null

    fun onPlaybackStart(atMs: Long) { if (openSince == null) openSince = atMs }

    fun onPlaybackEnd(atMs: Long) {
        val s = openSince ?: return
        openSince = null
        windows.addLast(s..(atMs + tailMs))
        while (windows.size > keepWindows) windows.removeFirst()
    }

    /** Returns the event with only acceptable tokens, or null when nothing is left. */
    fun filter(event: RecognitionEvent): RecognitionEvent? {
        if (isPlaying) return null
        val kept = event.tokens.filter { !blocked(it, event) }
        if (kept.isEmpty() && event.tokens.isNotEmpty()) return null
        return if (kept.size == event.tokens.size) event else event.copy(tokens = kept)
    }

    private fun blocked(t: Token, e: RecognitionEvent): Boolean {
        val s = t.startMs ?: t.endMs ?: e.timestampMs
        val end = t.endMs ?: t.startMs ?: e.timestampMs
        val all = windows + listOfNotNull(e.suppressedWindow)
        return all.any { w -> s <= w.last && end >= w.first }
    }

    fun reset() { windows.clear(); openSince = null }
}
