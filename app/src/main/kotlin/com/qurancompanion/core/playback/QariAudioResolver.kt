package com.qurancompanion.core.playback

import android.net.Uri
import javax.inject.Inject

/** A playable clip of Qari audio for one ayah, with optional word-level timings (ms). */
data class AyahAudio(
    val uri: Uri,
    val startMs: Long = 0,
    val endMs: Long? = null,
    /** wordTimings[i] = (startMs, endMs) of word i+1; empty when no timing data is licensed/available. */
    val wordTimings: List<Pair<Long, Long>> = emptyList(),
)

/**
 * Locates downloaded Qari audio. INTEGRATION TODO(resources): implement once a licensed Qari
 * audio set + word timings are chosen and the download manager exists (PHASE0_PLAN §7 risk 6).
 */
interface QariAudioResolver {
    suspend fun ayahAudio(qariId: String?, surah: Int, ayah: Int): AyahAudio?
}

/** Default: nothing downloaded yet, so hints report "audio not available". */
class NoDownloadedQariAudio @Inject constructor() : QariAudioResolver {
    override suspend fun ayahAudio(qariId: String?, surah: Int, ayah: Int): AyahAudio? = null
}
