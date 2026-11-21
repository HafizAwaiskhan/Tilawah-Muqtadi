package com.qurancompanion.core

import com.qurancompanion.core.model.AsrModelSpec
import com.qurancompanion.core.model.ModelInstallState
import com.qurancompanion.core.recognition.AbdouRecognitionEngine
import com.qurancompanion.core.recognition.ModelReadiness
import com.qurancompanion.core.recognition.StreamingRecognizer
import com.qurancompanion.core.recognition.StreamingRecognizerLoader
import com.qurancompanion.engine.recognition.RecognitionEvent
import com.qurancompanion.engine.recognition.RecognizerStatus
import com.qurancompanion.engine.recognition.Token
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Threading / lifecycle of the engine around a fake recognizer (no model, no Quran text). */
class AbdouRecognitionEngineTest {
    private class FakeStreaming(private val wordEvery: Int = 10) : StreamingRecognizer {
        val threads: MutableSet<String> = Collections.synchronizedSet(HashSet())
        val captureTimes = CopyOnWriteArrayList<Long>()
        @Volatile var concurrent = 0
        @Volatile var maxConcurrent = 0
        @Volatile var resets = 0
        @Volatile var flushes = 0
        @Volatile var closed = false
        @Volatile var failNext = false
        private var sinceFlush = 0
        private var words = 0

        private inline fun <T> on(block: () -> T): T {
            threads += Thread.currentThread().name.substringBefore(" @")
            concurrent++; maxConcurrent = maxOf(maxConcurrent, concurrent)
            try { return block() } finally { concurrent-- }
        }

        override fun accept(pcm: ShortArray, captureTimeMs: Long): List<RecognitionEvent> = on {
            if (failNext) { failNext = false; throw IllegalStateException("boom") }
            Thread.sleep(1) // a "decode" takes time
            captureTimes += captureTimeMs
            sinceFlush++
            if (sinceFlush % wordEvery == 0) listOf(word(captureTimeMs)) else emptyList()
        }

        override fun flush(): List<RecognitionEvent> = on {
            flushes++
            if (sinceFlush % wordEvery != 0) { sinceFlush = 0; listOf(RecognitionEvent(listOf(Token("tail")), true, 0)) }
            else { sinceFlush = 0; emptyList() }
        }

        override fun reset() = on { resets++; sinceFlush = 0 }
        override fun close() { closed = true }
        private fun word(t: Long) = RecognitionEvent(listOf(Token("w${words++}", t - 200, t)), true, t)
    }

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "asr-test-worker") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock = AtomicLong(100_000)
    private val install = MutableStateFlow<ModelInstallState>(ModelInstallState.Installed(File("/nonexistent/abdou-v1")))
    private val fake = FakeStreaming()
    private var loads = 0
    private val loader = StreamingRecognizerLoader { loads++; fake }
    private val spec = AsrModelSpec.ABDOU_V1

    private fun engine(l: StreamingRecognizerLoader = loader) =
        AbdouRecognitionEngine(spec, install, l, worker, clock::get, scope)

    @After fun tearDown() { scope.cancel(); worker.close() }

    /** [n] 20 ms frames; the clock advances as each frame "arrives"; optional jump before frame [gapAt]. */
    private fun frames(n: Int, gapAt: Int = -1, gapMs: Long = 0): Flow<ShortArray> = flow {
        for (i in 0 until n) {
            if (i == gapAt) clock.addAndGet(gapMs)
            clock.addAndGet(20)
            emit(ShortArray(320))
        }
    }

    private suspend fun AbdouRecognitionEngine.awaitFrames(n: Long) = withTimeout(10_000) {
        while (framesProcessed < n) delay(5)
    }

    @Test fun notInstalledFallsBackWithoutLoading() = runBlocking {
        install.value = ModelInstallState.NotInstalled
        val e = engine()
        assertEquals(ModelReadiness.NotInstalled, e.initialize())
        assertEquals(0, loads)
        assertTrue(!e.isInstalled)
    }

    @Test fun readinessFollowsDownloadAndLoad() = runBlocking {
        install.value = ModelInstallState.Downloading(18, 36)
        val e = engine()
        withTimeout(5_000) { e.readiness.first { it is ModelReadiness.Downloading && it.progress == 0.5f } }
        install.value = ModelInstallState.Installed(File("/nonexistent/abdou-v1"))
        val r = e.initialize()
        assertEquals(ModelReadiness.Ready(spec.id, spec.version), r)
        assertEquals(1, loads)
        e.initialize()
        assertEquals("loaded once", 1, loads)
        assertEquals("asr-test-worker", e.workerThreadName?.substringBefore(" @"))
        install.value = ModelInstallState.NotInstalled // model removed in Settings
        withTimeout(5_000) { e.readiness.first { it == ModelReadiness.NotInstalled } }
        withTimeout(5_000) { while (!fake.closed) delay(5) }
    }

    @Test fun loadFailureReportsFailedAndStatusError() = runBlocking {
        val e = engine { throw IllegalStateException("bad model") }
        val statuses = CopyOnWriteArrayList<RecognizerStatus>()
        val j = launch(Dispatchers.Default) { e.observeStatus().collect { statuses += it } }
        delay(50)
        val r = e.initialize()
        assertTrue(r is ModelReadiness.Failed)
        withTimeout(5_000) { e.readiness.first { it is ModelReadiness.Failed } }
        withTimeout(5_000) { while (statuses.none { it is RecognizerStatus.Error }) delay(5) }
        j.cancel()
    }

    @Test fun framesRunOnOneWorkerThreadInOrderWithSessionClock() = runBlocking {
        val e = engine()
        e.initialize()
        val got = CopyOnWriteArrayList<RecognitionEvent>()
        val j = launch(Dispatchers.Default) { e.observeRecognition().collect { got += it } }
        val t0 = clock.get()
        e.startListening(frames(100, gapAt = 50, gapMs = 5_000))
        e.awaitFrames(100)
        withTimeout(5_000) { while (got.size < 10) delay(5) }

        assertEquals(setOf("asr-test-worker"), fake.threads)
        assertEquals(1, fake.maxConcurrent)
        assertEquals(1, fake.resets) // startListening resets the recognizer (new session)
        // captureTimeMs = clock at arrival − 20 ms: contiguous 20 ms steps, then the 5 s gap.
        val ct = fake.captureTimes
        assertEquals(t0, ct[0])
        for (i in 1 until 50) assertEquals(20L, ct[i] - ct[i - 1])
        assertEquals(5_020L, ct[50] - ct[49])
        for (i in 51 until 100) assertEquals(20L, ct[i] - ct[i - 1])
        assertEquals((0 until 10).map { "w$it" }, got.map { it.tokens.single().text })
        j.cancel()
    }

    @Test fun flushReturnsAfterItsEventsWereDelivered() = runBlocking {
        val e = engine()
        e.initialize()
        val got = CopyOnWriteArrayList<String>()
        val j = launch(Dispatchers.Default) { e.observeRecognition().collect { delay(20); got += it.tokens.single().text } }
        e.startListening(frames(25))
        e.awaitFrames(25)
        e.flush()
        // The slow collector has already handled w0, w1 and the flushed tail when flush() returns.
        assertEquals(listOf("w0", "w1", "tail"), got.toList())
        assertEquals(1, fake.flushes)
        e.flush() // nothing left: returns promptly
        assertEquals(3, got.size)
        j.cancel()
    }

    @Test fun flushWithoutCollectorOrModelDoesNotHang() = runBlocking {
        install.value = ModelInstallState.NotInstalled
        val e = engine()
        withTimeout(1_000) { e.flush() }
    }

    @Test fun stopListeningStopsConsumingAndRestartResets() = runBlocking {
        val e = engine()
        e.initialize()
        e.startListening(frames(10))
        e.awaitFrames(10)
        e.stopListening()
        val stopped = e.framesProcessed
        // a never-ending source after stop: nothing is consumed
        delay(100)
        assertEquals(stopped, e.framesProcessed)
        e.startListening(frames(5))
        e.awaitFrames(stopped + 5)
        assertEquals(2, fake.resets)
    }

    @Test fun recognizerErrorIsReportedAndEngineKeepsRunning() = runBlocking {
        val e = engine()
        e.initialize()
        val statuses = CopyOnWriteArrayList<RecognizerStatus>()
        val j = launch(Dispatchers.Default) { e.observeStatus().collect { statuses += it } }
        delay(50)
        fake.failNext = true
        e.startListening(frames(20))
        e.awaitFrames(20)
        withTimeout(5_000) { while (statuses.none { it is RecognizerStatus.Error }) delay(5) }
        assertEquals(19, fake.captureTimes.size) // the failing frame only
        j.cancel()
    }
}
