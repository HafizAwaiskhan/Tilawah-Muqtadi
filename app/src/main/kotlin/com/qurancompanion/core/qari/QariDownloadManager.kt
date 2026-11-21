package com.qurancompanion.core.qari

import com.qurancompanion.core.model.HttpSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

data class QariDownloadState(
    /** Surahs whose every ayah file is on disk. */
    val downloaded: Set<Int> = emptySet(),
    /** Surah being downloaded now, with ayahs done / total. */
    val current: Int? = null,
    val currentDone: Int = 0,
    val currentTotal: Int = 0,
    /** Surahs waiting after [current]. */
    val queued: List<Int> = emptyList(),
    val error: String? = null,
) {
    val busy: Boolean get() = current != null
    val downloadedBytes: Long get() = downloaded.sumOf { AlafasyTestQari.surahBytes(it) }
}

/**
 * On-demand download of the Alafasy test audio (user action only: Settings → Offline downloads),
 * one surah at a time, into `<root>/SSSAAA.mp3`. Each ayah is written to a `.part` file and renamed
 * when complete, so a half-written file is never used; a finished surah gets a `SSS.done` marker.
 * An interrupted download keeps the finished ayahs and continues with the missing ones next time.
 */
class QariDownloadManager(
    private val root: File,
    private val http: HttpSource,
    private val io: CoroutineDispatcher,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(QariDownloadState(downloaded = scanDone()))
    val state: StateFlow<QariDownloadState> = _state.asStateFlow()
    private var job: Job? = null

    private fun marker(surah: Int) = File(root, String.format(java.util.Locale.ROOT, "%03d.done", surah))

    private fun scanDone(): Set<Int> = (1..114).filter { marker(it).isFile }.toSet()

    /** The downloaded file for one ayah, or null. */
    fun ayahFile(surah: Int, ayah: Int): File? =
        File(root, AlafasyTestQari.fileName(surah, ayah)).takeIf { it.isFile && it.length() > 0 }

    fun downloadSurah(surah: Int) = enqueue(listOf(surah))

    /** "Download whole Quran" (≈ [AlafasyTestQari.TOTAL_BYTES]). */
    fun downloadAll() = enqueue((1..114).toList())

    @Synchronized
    private fun enqueue(surahs: List<Int>) {
        val add = surahs.filter { it in 1..114 && it !in _state.value.downloaded && it != _state.value.current && it !in _state.value.queued }
        if (add.isEmpty()) return
        _state.update { it.copy(queued = it.queued + add, error = null) }
        if (job?.isActive != true) job = scope.launch { runQueue() }
    }

    fun cancel() {
        job?.cancel()
        _state.update { it.copy(current = null, queued = emptyList(), currentDone = 0, currentTotal = 0) }
    }

    suspend fun deleteAll() {
        cancel()
        withContext(io) { root.listFiles()?.forEach { it.delete() } }
        _state.value = QariDownloadState()
    }

    private suspend fun runQueue() {
        while (true) {
            val next = _state.value.queued.firstOrNull() ?: break
            _state.update { it.copy(current = next, queued = it.queued.drop(1), currentDone = 0, currentTotal = AlafasyTestQari.ayahCount(next)) }
            try {
                downloadSurahNow(next)
                _state.update { it.copy(downloaded = it.downloaded + next, current = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                _state.update { it.copy(current = null, queued = emptyList(), error = "Surah $next: ${e.message ?: "network error"}") }
                break
            }
        }
    }

    /** Downloads every missing ayah of [surah]; throws on the first failure. */
    suspend fun downloadSurahNow(surah: Int) = withContext(io) {
        if (!root.isDirectory && !root.mkdirs()) throw IOException("cannot create ${root.name}")
        val n = AlafasyTestQari.ayahCount(surah)
        for (ayah in 1..n) {
            ensureActive()
            if (ayahFile(surah, ayah) == null) fetch(surah, ayah)
            _state.update { it.copy(currentDone = ayah) }
        }
        marker(surah).writeText("$n\n")
    }

    private fun fetch(surah: Int, ayah: Int) {
        val target = File(root, AlafasyTestQari.fileName(surah, ayah))
        val part = File(root, target.name + ".part")
        http.open(AlafasyTestQari.url(surah, ayah), 0).use { r ->
            if (r.code != 200) throw IOException("HTTP ${r.code}")
            FileOutputStream(part).use { out -> r.body.copyTo(out) }
        }
        if (!looksLikeMp3(part)) { part.delete(); throw IOException("not an MP3 file") }
        if (!part.renameTo(target)) { part.delete(); throw IOException("cannot store ${target.name}") }
    }

    companion object {
        /** ID3 tag or an MPEG frame sync at the start. */
        fun looksLikeMp3(f: File): Boolean {
            if (f.length() < 4) return false
            val b = f.inputStream().use { s -> ByteArray(3).also { s.read(it) } }
            val id3 = b[0] == 'I'.code.toByte() && b[1] == 'D'.code.toByte() && b[2] == '3'.code.toByte()
            val sync = (b[0].toInt() and 0xFF) == 0xFF && (b[1].toInt() and 0xE0) == 0xE0
            return id3 || sync
        }
    }
}
