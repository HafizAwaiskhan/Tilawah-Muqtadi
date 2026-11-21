package com.qurancompanion.core

import com.qurancompanion.core.model.AsrModelSpec
import com.qurancompanion.core.model.HttpResponse
import com.qurancompanion.core.model.HttpSource
import com.qurancompanion.core.model.ModelDownloadManager
import com.qurancompanion.core.model.ModelFileSpec
import com.qurancompanion.core.model.ModelInstallState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class ModelDownloadManagerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val model = Random(1).nextBytes(700_000)
    private val tokens = Random(2).nextBytes(180)

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun spec(modelSha: String = sha(model)) = AsrModelSpec(
        id = "test-model", version = "v1", dirName = "test-v1",
        files = listOf(
            ModelFileSpec("model.int8.onnx", "https://example.invalid/model", model.size.toLong(), modelSha),
            ModelFileSpec("tokens.txt", "https://example.invalid/tokens", tokens.size.toLong(), sha(tokens)),
        ),
        licenseName = "MIT", attribution = "test", licenseText = "test",
    )

    /** Serves [files] by URL; honours Range unless [ignoreRange]; cuts the body after [failAfter] bytes. */
    private class FakeHttp(
        val files: Map<String, ByteArray>,
        var failAfter: Long? = null,
        var ignoreRange: Boolean = false,
    ) : HttpSource {
        var onRead: (() -> Unit)? = null
        val requests = mutableListOf<Pair<String, Long>>()
        override fun open(url: String, fromByte: Long): HttpResponse {
            requests += url to fromByte
            val data = files[url] ?: return HttpResponse(404, ByteArrayInputStream(ByteArray(0)))
            val partial = fromByte > 0 && !ignoreRange
            val body = if (partial) data.copyOfRange(fromByte.toInt(), data.size) else data
            val limit = failAfter ?: Long.MAX_VALUE
            val stream: InputStream = object : InputStream() {
                val inner = ByteArrayInputStream(body)
                var n = 0L
                override fun read(): Int { if (n >= limit) throw IOException("connection reset"); n++; return inner.read() }
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    onRead?.invoke()
                    if (n >= limit) throw IOException("connection reset")
                    val r = inner.read(b, off, minOf(len.toLong(), limit - n).toInt())
                    if (r > 0) n += r
                    return r
                }
            }
            return HttpResponse(if (partial) 206 else 200, stream)
        }
    }

    private fun http(vararg extra: Pair<String, ByteArray>) =
        FakeHttp(mapOf("https://example.invalid/model" to model, "https://example.invalid/tokens" to tokens) + extra)

    @Test fun hashOkInstallsAtomicallyWithProgress() = runTest {
        val root = tmp.newFolder("models")
        File(root, "test-v0").mkdirs() // an older version of the same model
        val d = UnconfinedTestDispatcher(testScheduler)
        val h = http()
        val m = ModelDownloadManager(root, spec(), h, d, backgroundScope)
        assertEquals(ModelInstallState.NotInstalled, m.state.value)
        val seen = mutableListOf<ModelInstallState>()
        h.onRead = { seen += m.state.value }

        val result = m.download()

        assertTrue(result is ModelInstallState.Installed)
        val dir = File(root, "test-v1")
        assertEquals(dir, (result as ModelInstallState.Installed).dir)
        assertArrayEquals(model, File(dir, "model.int8.onnx").readBytes())
        assertArrayEquals(tokens, File(dir, "tokens.txt").readBytes())
        assertEquals(spec().versionTag, File(dir, ModelDownloadManager.VERSION_FILE).readText())
        assertFalse(File(root, ".staging-test-v1").exists())
        assertFalse("old version removed", File(root, "test-v0").exists())
        val progress = seen.filterIsInstance<ModelInstallState.Downloading>()
        assertTrue(progress.any { it.fraction > 0f && it.fraction < 1f })
        assertEquals(progress.map { it.downloadedBytes }, progress.map { it.downloadedBytes }.sorted())
        assertEquals(dir, m.installedDir())
        // A new manager (next app start) sees the install without network.
        assertEquals(dir, ModelDownloadManager(root, spec(), { _, _ -> error("no network") }, d, backgroundScope).installedDir())
    }

    @Test fun hashMismatchDeletesAndFails() = runTest {
        val root = tmp.newFolder("models")
        val m = ModelDownloadManager(root, spec(modelSha = "0".repeat(64)), http(), UnconfinedTestDispatcher(testScheduler), backgroundScope)
        val result = m.download()
        assertTrue(result is ModelInstallState.Failed)
        assertEquals(ModelInstallState.Reason.VERIFICATION, (result as ModelInstallState.Failed).reason)
        assertFalse(result.resumable)
        assertFalse(File(root, "test-v1").exists())
        assertFalse(File(root, ".staging-test-v1/model.int8.onnx.part").exists())
        assertFalse(File(root, ".staging-test-v1/model.int8.onnx").exists())
        assertNull(m.installedDir())
    }

    @Test fun interruptedDownloadResumesWithRange() = runTest {
        val root = tmp.newFolder("models")
        val h = http().apply { failAfter = 250_000 }
        val m = ModelDownloadManager(root, spec(), h, UnconfinedTestDispatcher(testScheduler), backgroundScope)

        val first = m.download()
        assertTrue(first is ModelInstallState.Failed)
        assertEquals(ModelInstallState.Reason.NETWORK, (first as ModelInstallState.Failed).reason)
        assertTrue(first.resumable)
        assertFalse(File(root, "test-v1").exists())
        assertEquals(250_000L, File(root, ".staging-test-v1/model.int8.onnx.part").length())
        assertEquals(250_000L, m.partialBytes())

        h.failAfter = null
        val second = m.download()
        assertTrue(second is ModelInstallState.Installed)
        assertEquals("https://example.invalid/model" to 250_000L, h.requests[1])
        assertArrayEquals(model, File(root, "test-v1/model.int8.onnx").readBytes())
    }

    @Test fun serverIgnoringRangeRestartsFromZero() = runTest {
        val root = tmp.newFolder("models")
        val h = http().apply { failAfter = 100_000 }
        val m = ModelDownloadManager(root, spec(), h, UnconfinedTestDispatcher(testScheduler), backgroundScope)
        m.download()
        h.failAfter = null
        h.ignoreRange = true
        assertTrue(m.download() is ModelInstallState.Installed)
        assertArrayEquals(model, File(root, "test-v1/model.int8.onnx").readBytes())
    }

    @Test fun versionMismatchIsNotInstalled() = runTest {
        val root = tmp.newFolder("models")
        val dir = File(root, "test-v1").apply { mkdirs() }
        File(dir, "model.int8.onnx").writeBytes(model)
        File(dir, "tokens.txt").writeBytes(tokens)
        File(dir, ModelDownloadManager.VERSION_FILE).writeText("test-model/v0\n")
        val m = ModelDownloadManager(root, spec(), http(), UnconfinedTestDispatcher(testScheduler), backgroundScope)
        assertNull(m.installedDir())
        assertEquals(ModelInstallState.NotInstalled, m.state.value)
        assertTrue(m.download() is ModelInstallState.Installed)
        assertEquals(spec().versionTag, File(dir, ModelDownloadManager.VERSION_FILE).readText())
    }

    @Test fun deleteRemovesModel() = runTest {
        val root = tmp.newFolder("models")
        val m = ModelDownloadManager(root, spec(), http(), UnconfinedTestDispatcher(testScheduler), backgroundScope)
        m.download()
        m.delete()
        assertEquals(ModelInstallState.NotInstalled, m.state.value)
        assertFalse(File(root, "test-v1").exists())
    }

    /** The shipped constants must match the files the evaluation used (models/, gitignored; skipped when absent). */
    @Test fun shippedHashesMatchLocalModelFiles() {
        val dir = File(System.getProperty("quranRefDb") ?: return).parentFile?.parentFile?.parentFile
            ?.let { File(it, "models/abdou-quran-asr-small") }
        org.junit.Assume.assumeTrue(dir != null && File(dir, "model.int8.onnx").isFile)
        for (f in AsrModelSpec.ABDOU_V1.files) {
            val file = File(dir, f.name)
            assertEquals(f.name, f.sizeBytes, file.length())
            assertEquals(f.name, f.sha256, ModelDownloadManager.sha256(file))
        }
        assertEquals(36L, AsrModelSpec.ABDOU_V1.totalBytes / 1_000_000)
    }
}
