package com.qurancompanion.core.recognition

import com.qurancompanion.asr.AbdouConfig
import com.qurancompanion.asr.AbdouOnnxModel
import com.qurancompanion.asr.AbdouRecognizer
import com.qurancompanion.asr.Vocabulary
import com.qurancompanion.core.audio.AudioCapture
import com.qurancompanion.core.model.AsrModelSpec
import com.qurancompanion.core.model.ModelInstallState
import com.qurancompanion.engine.recognition.RecognitionEvent
import com.qurancompanion.engine.recognition.RecognizerStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/** A streaming recognizer used from one thread only (the engine's worker). */
interface StreamingRecognizer : AutoCloseable {
    /** [captureTimeMs] = session-clock time of `pcm[0]`. */
    fun accept(pcm: ShortArray, captureTimeMs: Long): List<RecognitionEvent>
    fun flush(): List<RecognitionEvent>
    fun reset()
    override fun close() {}
}

/** Loads a recognizer from an installed model dir (blocking; called on the worker thread). */
fun interface StreamingRecognizerLoader {
    fun load(modelDir: File): StreamingRecognizer
}

/** Production loader: `:asr`'s ONNX port of Abdou/quran-asr-small (docs/ASR.md). */
class AbdouOnnxRecognizerLoader(private val threads: Int = 2) : StreamingRecognizerLoader {
    override fun load(modelDir: File): StreamingRecognizer {
        val vocab = Vocabulary.load(File(modelDir, "tokens.txt"))
        val model = AbdouOnnxModel.load(File(modelDir, "model.int8.onnx"), vocab.size, threads)
        val rec = AbdouRecognizer(model, vocab, AbdouConfig())
        return object : StreamingRecognizer {
            override fun accept(pcm: ShortArray, captureTimeMs: Long) = rec.accept(pcm, 0, pcm.size, captureTimeMs)
            override fun flush() = rec.flush()
            override fun reset() = rec.reset()
            override fun close() = model.close()
        }
    }
}

/**
 * [QuranRecognitionEngine] backed by Abdou/quran-asr-small.
 *
 * - **One worker thread** ([worker], a single-thread dispatcher): model load, `accept`, `flush` and
 *   `reset` all run there, in the order they were requested, so the (non-thread-safe) recognizer is
 *   never touched concurrently and a slow window decode never blocks capture or the UI.
 * - Frames from the gated [AudioCapture] flow are time-stamped on arrival with
 *   `captureTimeMs = clock() − frame duration` (the session clock, `SystemClock.elapsedRealtime()`),
 *   smoothed to the sample count while the stream is contiguous (re-synced if it drifts by
 *   > [RESYNC_MS]). A gate closure shows up as a time jump, which the recognizer treats as a new segment.
 * - At most [MAX_QUEUED_FRAMES] (30 s) wait for the worker; beyond that new frames are dropped.
 * - [flush] commits the tail and returns once its events have been delivered to the
 *   [observeRecognition] collector (so a following Pause/Lukma input is ordered after them).
 * - Readiness: NotInstalled / Downloading (from [installState]) → Loading → Ready, or Failed.
 */
class AbdouRecognitionEngine(
    private val spec: AsrModelSpec,
    private val installState: StateFlow<ModelInstallState>,
    private val loader: StreamingRecognizerLoader,
    private val worker: CoroutineDispatcher,
    private val clock: () -> Long,
    private val scope: CoroutineScope,
) : QuranRecognitionEngine {
    private val loaded = MutableStateFlow<ModelReadiness?>(null) // null = not attempted for the current install
    private val _readiness = MutableStateFlow(readinessFrom(installState.value, null))
    override val readiness: StateFlow<ModelReadiness> = _readiness.asStateFlow()

    private val status = MutableSharedFlow<RecognizerStatus>(extraBufferCapacity = 16)

    private sealed interface Cmd {
        class Frame(val pcm: ShortArray, val captureTimeMs: Long) : Cmd
        class Flush(val done: CompletableDeferred<Long>) : Cmd
        data object Reset : Cmd
    }

    private class Out(val seq: Long, val event: RecognitionEvent)

    private val commands = Channel<Cmd>(Channel.UNLIMITED)
    private val queuedFrames = AtomicInteger(0)
    private val outputs = Channel<Out>(Channel.UNLIMITED)
    private val delivered = MutableStateFlow(0L)

    // Worker-confined.
    private var recognizer: StreamingRecognizer? = null
    private var loadedDir: File? = null
    private var produced = 0L

    private val loadMutex = Mutex()
    private var collectJob: Job? = null

    /** Frames accepted by the worker (diagnostics / tests). */
    @Volatile var framesProcessed = 0L
        private set

    /** Frames dropped because the worker fell more than 30 s behind. */
    @Volatile var droppedFrames = 0L
        private set

    /** Name of the thread that last ran the recognizer (tests check it is the single worker). */
    @Volatile var workerThreadName: String? = null
        private set

    init {
        scope.launch(worker) { for (cmd in commands) runCommand(cmd) }
        scope.launch {
            installState.collect { s ->
                if (s !is ModelInstallState.Installed && loaded.value != null) {
                    loaded.value = null
                    launch(worker) { recognizer?.close(); recognizer = null; loadedDir = null } // model deleted
                }
                _readiness.value = readinessFrom(s, loaded.value)
            }
        }
        scope.launch { loaded.collect { _readiness.value = readinessFrom(installState.value, it) } }
    }

    private fun readinessFrom(s: ModelInstallState, load: ModelReadiness?): ModelReadiness = when (s) {
        ModelInstallState.NotInstalled -> ModelReadiness.NotInstalled
        is ModelInstallState.Downloading -> ModelReadiness.Downloading(s.fraction)
        ModelInstallState.Verifying -> ModelReadiness.Downloading(1f)
        is ModelInstallState.Failed -> ModelReadiness.NotInstalled
        is ModelInstallState.Installed -> load ?: ModelReadiness.Ready(spec.id, spec.version)
    }

    /** True when a verified model is on disk (the engine can be used). */
    val isInstalled: Boolean get() = installState.value is ModelInstallState.Installed

    override suspend fun initialize(): ModelReadiness = loadMutex.withLock {
        val s = installState.value as? ModelInstallState.Installed ?: return readiness.value
        val current = loaded.value
        if (current is ModelReadiness.Ready) return current
        loaded.value = ModelReadiness.Loading
        val result = withContext(worker) {
            workerThreadName = Thread.currentThread().name
            try {
                if (recognizer == null || loadedDir != s.dir) {
                    recognizer?.close()
                    recognizer = loader.load(s.dir)
                    loadedDir = s.dir
                }
                ModelReadiness.Ready(spec.id, spec.version)
            } catch (t: Throwable) {
                recognizer = null
                ModelReadiness.Failed("Could not load the recognition model: ${t.message ?: t.javaClass.simpleName}")
            }
        }
        loaded.value = result
        if (result is ModelReadiness.Failed) status.tryEmit(RecognizerStatus.Error(result.message))
        result
    }

    override fun startListening(audio: Flow<ShortArray>) {
        collectJob?.cancel()
        commands.trySend(Cmd.Reset)
        collectJob = scope.launch {
            var expectedMs = Long.MIN_VALUE
            audio.collect { pcm ->
                if (pcm.isEmpty()) return@collect
                val durMs = pcm.size * 1000L / AudioCapture.SAMPLE_RATE
                val stamped = clock() - durMs
                val t = if (expectedMs != Long.MIN_VALUE && abs(stamped - expectedMs) <= RESYNC_MS) expectedMs else stamped
                expectedMs = t + durMs
                // Worker too slow: drop the newest audio (the time jump starts a new segment).
                if (queuedFrames.get() >= MAX_QUEUED_FRAMES) { droppedFrames++; return@collect }
                queuedFrames.incrementAndGet()
                commands.trySend(Cmd.Frame(pcm, t))
            }
        }
    }

    override fun stopListening() {
        collectJob?.cancel()
        collectJob = null
    }

    override suspend fun flush() {
        val done = CompletableDeferred<Long>()
        if (commands.trySend(Cmd.Flush(done)).isFailure) return
        val seq = withTimeoutOrNull(FLUSH_TIMEOUT_MS) { done.await() } ?: return
        // Wait until the collector has handled every event up to the flush's (bounded, in case nobody collects).
        withTimeoutOrNull(FLUSH_TIMEOUT_MS) { delivered.first { it >= seq } }
    }

    override fun observeRecognition(): Flow<RecognitionEvent> = flow {
        for (o in outputs) {
            emit(o.event)
            delivered.value = o.seq
        }
    }

    override fun observeStatus(): Flow<RecognizerStatus> = status

    private fun runCommand(cmd: Cmd) {
        workerThreadName = Thread.currentThread().name
        val rec = recognizer
        try {
            when (cmd) {
                is Cmd.Frame -> {
                    queuedFrames.decrementAndGet()
                    framesProcessed++
                    rec?.accept(cmd.pcm, cmd.captureTimeMs)?.forEach(::publish)
                }
                is Cmd.Flush -> {
                    rec?.flush()?.forEach(::publish)
                    cmd.done.complete(produced)
                }
                Cmd.Reset -> rec?.reset()
            }
        } catch (t: Throwable) {
            if (cmd is Cmd.Flush) cmd.done.complete(produced)
            runCatching { rec?.reset() }
            status.tryEmit(RecognizerStatus.Error("Recognizer error: ${t.message ?: t.javaClass.simpleName}"))
        }
    }

    private fun publish(e: RecognitionEvent) {
        produced++
        outputs.trySend(Out(produced, e))
    }

    /** Releases the model (process shutdown / tests). */
    suspend fun close() {
        stopListening()
        withContext(worker) { recognizer?.close(); recognizer = null; loadedDir = null }
        loaded.value = null
    }

    companion object {
        const val MAX_QUEUED_FRAMES = 1_500 // 30 s of 20 ms frames
        const val RESYNC_MS = 200L
        const val FLUSH_TIMEOUT_MS = 3_000L
    }
}
