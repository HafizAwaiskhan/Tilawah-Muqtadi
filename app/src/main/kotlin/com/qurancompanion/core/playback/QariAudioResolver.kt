package com.qurancompanion.core.playback

import com.qurancompanion.core.qari.AlafasyTestQari
import com.qurancompanion.core.qari.QariDownloadManager
import com.qurancompanion.core.qari.QariTimingSource
import com.qurancompanion.core.net.NetworkStatus
import javax.inject.Inject

/** A playable clip of Qari audio for one ayah, with optional word-level timings (ms). */
data class AyahAudio(
    /** Content, file or https URI string (parsed by the player). */
    val uri: String,
    val startMs: Long = 0,
    val endMs: Long? = null,
    /**
     * wordTimings[i] = (startMs, endMs) of word i+1; empty when no timing data is available.
     * A word without a segment has (-1, -1).
     */
    val wordTimings: List<Pair<Long, Long>> = emptyList(),
    /** True when streamed from the network (not downloaded). */
    val streamed: Boolean = false,
)

/** Result of looking up the audio for one ayah. */
sealed interface QariAudioLookup {
    data class Available(val audio: AyahAudio) : QariAudioLookup
    /** Not downloaded and the phone is offline. */
    data object Offline : QariAudioLookup
    /** No Qari audio source for this Qari id. */
    data object Unavailable : QariAudioLookup
}

interface QariAudioResolver {
    suspend fun resolve(qariId: String?, surah: Int, ayah: Int): QariAudioLookup
}

/**
 * The Alafasy **test** source (docs/LICENSES.md row 9): the downloaded file when present, else —
 * if the phone is online — the same everyayah.com URL streamed; offline → [QariAudioLookup.Offline].
 * Word timings come from the debug-only timing asset (none in release builds).
 */
class TestQariAudioResolver @Inject constructor(
    private val downloads: QariDownloadManager,
    private val timings: QariTimingSource,
    private val network: NetworkStatus,
) : QariAudioResolver {
    override suspend fun resolve(qariId: String?, surah: Int, ayah: Int): QariAudioLookup {
        if (qariId != null && qariId != AlafasyTestQari.ID) return QariAudioLookup.Unavailable
        if (ayah !in 1..AlafasyTestQari.ayahCount(surah)) return QariAudioLookup.Unavailable
        val t = timings.timings(surah, ayah)
        downloads.ayahFile(surah, ayah)?.let { f ->
            return QariAudioLookup.Available(AyahAudio("file://" + f.absolutePath, wordTimings = t))
        }
        if (!network.isOnline()) return QariAudioLookup.Offline
        return QariAudioLookup.Available(AyahAudio(AlafasyTestQari.url(surah, ayah), wordTimings = t, streamed = true))
    }
}
