package com.qurancompanion.core.model

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Minimal HTTP abstraction so the download logic is JVM-testable. */
fun interface HttpSource {
    /** GET [url]; when [fromByte] > 0 asks for `Range: bytes=fromByte-`. */
    @Throws(IOException::class)
    fun open(url: String, fromByte: Long): HttpResponse
}

/** [code] 200 = whole body, 206 = body starts at the requested byte, anything else = error. */
class HttpResponse(val code: Int, val body: InputStream, private val onClose: () -> Unit = {}) : Closeable {
    override fun close() {
        runCatching { body.close() }
        onClose()
    }
}

/** HttpURLConnection-based source (follows HF's redirect to its CDN). */
class UrlConnectionHttpSource : HttpSource {
    override fun open(url: String, fromByte: Long): HttpResponse {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "QuranCompanion-Android")
        if (fromByte > 0) c.setRequestProperty("Range", "bytes=$fromByte-")
        val code = c.responseCode
        val body = if (code in 200..299) c.inputStream else (c.errorStream ?: ByteArrayInputStream(ByteArray(0)))
        return HttpResponse(code, body) { c.disconnect() }
    }
}

sealed interface ModelInstallState {
    data object NotInstalled : ModelInstallState
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : ModelInstallState {
        val fraction: Float get() = if (totalBytes <= 0) 0f else (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    }
    data object Verifying : ModelInstallState
    data class Installed(val dir: File) : ModelInstallState
    data class Failed(val reason: Reason, val message: String) : ModelInstallState {
        /** A retry continues from the bytes already on disk (interruptions only). */
        val resumable: Boolean get() = reason == Reason.NETWORK || reason == Reason.CANCELLED
    }

    enum class Reason { NETWORK, VERIFICATION, NO_SPACE, STORAGE, CANCELLED }
}

class ModelVerificationException(message: String) : IOException(message)

/**
 * Downloads a pinned [AsrModelSpec] on explicit user action only (Onboarding / Settings).
 *
 * - Files go to `<root>/.staging-<dir>/<name>.part`. An interrupted transfer keeps its `.part`
 *   and the next attempt resumes with an HTTP Range request (or restarts if the server answers 200).
 * - Each completed file is checked against the spec's size + SHA-256; a mismatch deletes it.
 * - When all files are verified a `VERSION` file is written and the staging dir is renamed to
 *   `<root>/<dir>` in one step, so a half-installed model is never visible. Other versions are removed.
 */
class ModelDownloadManager(
    private val root: File,
    val spec: AsrModelSpec,
    private val http: HttpSource,
    private val io: CoroutineDispatcher,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<ModelInstallState>(
        installedDir()?.let { ModelInstallState.Installed(it) } ?: ModelInstallState.NotInstalled
    )
    val state: StateFlow<ModelInstallState> = _state.asStateFlow()

    private var job: Job? = null

    val targetDir: File get() = File(root, spec.dirName)
    private val stagingDir: File get() = File(root, ".staging-${spec.dirName}")

    /** The installed model dir if it matches this spec's version tag and file sizes, else null. */
    fun installedDir(): File? {
        val dir = targetDir
        val tag = File(dir, VERSION_FILE)
        if (!tag.isFile || runCatching { tag.readText() }.getOrNull() != spec.versionTag) return null
        return dir.takeIf { spec.files.all { f -> File(dir, f.name).length() == f.sizeBytes } }
    }

    /** Bytes of the model already on disk from an interrupted download. */
    fun partialBytes(): Long = spec.files.sumOf { f ->
        val done = File(stagingDir, f.name)
        if (done.length() == f.sizeBytes) f.sizeBytes else File(stagingDir, f.name + PART).length().coerceAtMost(f.sizeBytes)
    }

    /** Starts (or resumes) the download in the background; no-op while one is running or when installed. */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { runCatching { download() } }
    }

    fun cancel() { job?.cancel() }

    /** Removes the installed model and any partial download. */
    suspend fun delete() = withContext(io) {
        job?.cancel()
        stagingDir.deleteRecursively()
        targetDir.deleteRecursively()
        _state.value = ModelInstallState.NotInstalled
    }

    /** Downloads, verifies and installs. Returns the final state (also published on [state]). */
    suspend fun download(): ModelInstallState = withContext(io) {
        installedDir()?.let { return@withContext publish(ModelInstallState.Installed(it)) }
        try {
            if (!stagingDir.isDirectory && !stagingDir.mkdirs()) throw StorageException("cannot create ${stagingDir.name}")
            val needed = spec.totalBytes - partialBytes()
            val usable = root.usableSpace
            if (usable in 1 until needed + SPACE_MARGIN) {
                return@withContext publish(ModelInstallState.Failed(ModelInstallState.Reason.NO_SPACE,
                    "Not enough free space (${needed / 1_000_000 + 1} MB needed)"))
            }
            var done = 0L
            publish(ModelInstallState.Downloading(partialBytes(), spec.totalBytes))
            for (f in spec.files) {
                val final = File(stagingDir, f.name)
                if (final.length() == f.sizeBytes && sha256(final) == f.sha256) { done += f.sizeBytes; continue }
                final.delete()
                val part = File(stagingDir, f.name + PART)
                val base = done
                fetch(f, part) { bytes -> publish(ModelInstallState.Downloading(base + bytes, spec.totalBytes)) }
                publish(ModelInstallState.Verifying)
                val actual = sha256(part)
                if (actual != f.sha256) {
                    part.delete()
                    throw ModelVerificationException("${f.name}: SHA-256 mismatch")
                }
                if (!part.renameTo(final)) throw StorageException("cannot rename ${part.name}")
                done += f.sizeBytes
            }
            File(stagingDir, VERSION_FILE).writeText(spec.versionTag)
            val target = targetDir
            if (target.exists()) target.deleteRecursively()
            if (!stagingDir.renameTo(target)) throw StorageException("cannot install ${spec.dirName}")
            val family = spec.dirName.substringBefore('-') + "-"
            root.listFiles()?.filter { it.isDirectory && it.name != spec.dirName && it.name.removePrefix(".staging-").startsWith(family) }
                ?.forEach { it.deleteRecursively() } // other versions of this model
            publish(ModelInstallState.Installed(target))
        } catch (e: CancellationException) {
            publish(ModelInstallState.Failed(ModelInstallState.Reason.CANCELLED, "Download paused"))
            throw e
        } catch (e: ModelVerificationException) {
            publish(ModelInstallState.Failed(ModelInstallState.Reason.VERIFICATION, "The downloaded file was corrupt and was deleted. Please try again."))
        } catch (e: StorageException) {
            publish(ModelInstallState.Failed(ModelInstallState.Reason.STORAGE, "Storage error: ${e.message}"))
        } catch (e: IOException) {
            publish(ModelInstallState.Failed(ModelInstallState.Reason.NETWORK, "Download interrupted (${e.message ?: "network error"}). Tap to resume."))
        }
    }

    private suspend fun fetch(f: ModelFileSpec, part: File, onProgress: (Long) -> Unit) {
        var have = part.length()
        if (have > f.sizeBytes) { part.delete(); have = 0 }
        if (have == f.sizeBytes) { onProgress(have); return }
        var resp = http.open(f.url, have)
        if (resp.code == 416 && have > 0) { // stale range: start over
            resp.close(); part.delete(); have = 0
            resp = http.open(f.url, 0)
        }
        resp.use { r ->
            val append = when (r.code) {
                206 -> have > 0
                200 -> false
                else -> throw IOException("HTTP ${r.code}")
            }
            if (!append) have = 0
            var tooLarge = false
            FileOutputStream(part, append).use { out ->
                val buf = ByteArray(64 * 1024)
                var lastReport = have
                onProgress(have)
                while (true) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val n = r.body.read(buf)
                    if (n < 0) break
                    if (have + n > f.sizeBytes) { tooLarge = true; break }
                    out.write(buf, 0, n)
                    have += n
                    if (have - lastReport >= REPORT_BYTES) { lastReport = have; onProgress(have) }
                }
                out.fd.sync()
            }
            if (tooLarge) { part.delete(); throw ModelVerificationException("${f.name}: larger than expected") }
            onProgress(have)
            if (have < f.sizeBytes) throw IOException("connection closed at $have of ${f.sizeBytes} bytes")
        }
    }

    private fun publish(s: ModelInstallState): ModelInstallState { _state.value = s; return s }

    private class StorageException(message: String) : IOException(message)

    companion object {
        const val VERSION_FILE = "VERSION"
        private const val PART = ".part"
        private const val REPORT_BYTES = 256 * 1024L
        private const val SPACE_MARGIN = 10_000_000L

        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
