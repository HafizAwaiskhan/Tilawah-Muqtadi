package com.qurancompanion

import com.qurancompanion.core.playback.AyahAudio
import com.qurancompanion.core.playback.PlaybackEvent
import com.qurancompanion.core.playback.QariAudioResolver
import com.qurancompanion.core.playback.QuranAudioPlayback
import com.qurancompanion.core.time.Clock
import com.qurancompanion.data.db.SessionEntity
import com.qurancompanion.data.db.SessionStatus
import com.qurancompanion.data.session.SessionRepository
import com.qurancompanion.data.settings.SettingsRepository
import com.qurancompanion.data.settings.UserSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestCoroutineScheduler
import com.qurancompanion.core.recognition.ModelReadiness
import com.qurancompanion.core.recognition.QuranRecognitionEngine
import com.qurancompanion.data.reference.EngineReferenceSource
import com.qurancompanion.data.reference.EngineReferenceStatus
import com.qurancompanion.engine.recognition.RecognitionEvent
import com.qurancompanion.engine.reference.InMemoryQuranReference
import com.qurancompanion.engine.reference.QuranReference
import com.qurancompanion.engine.reference.WordRef

class FakeClock(var now: Long = 1_000_000L, var elapsed: Long = 0L) : Clock {
    override fun nowMillis() = now
    override fun elapsedRealtime() = elapsed
    fun advance(ms: Long) { now += ms; elapsed += ms }
}

/** Clock driven by the coroutine test scheduler, so delay()/ticks and token times agree. */
class SchedulerClock(private val scheduler: TestCoroutineScheduler) : Clock {
    override fun nowMillis() = 1_700_000_000_000L + scheduler.currentTime
    override fun elapsedRealtime() = scheduler.currentTime
}

class FakeSessionRepository : SessionRepository {
    val rows = MutableStateFlow<List<SessionEntity>>(emptyList())
    private var nextId = 1L

    override fun observeSessions(): Flow<List<SessionEntity>> = rows.map { l -> l.sortedByDescending { it.startedAt } }
    override fun observeLatestUnfinished(): Flow<SessionEntity?> =
        rows.map { l -> l.filter { it.status.isResumable }.maxByOrNull { it.startedAt } }
    override suspend fun latestUnfinished() = rows.value.filter { it.status.isResumable }.maxByOrNull { it.startedAt }
    override suspend fun get(id: Long) = rows.value.firstOrNull { it.id == id }
    override suspend fun create(session: SessionEntity): Long {
        val id = nextId++
        rows.value = rows.value + session.copy(id = id)
        return id
    }
    private fun edit(id: Long, f: (SessionEntity) -> SessionEntity) {
        rows.value = rows.value.map { if (it.id == id) f(it) else it }
    }
    override suspend fun updatePosition(id: Long, surah: Int, ayah: Int, word: Int, durationMs: Long) =
        edit(id) { it.copy(lastSurah = surah, lastAyah = ayah, lastWord = word, durationMs = durationMs) }
    override suspend fun updateStart(id: Long, surah: Int, ayah: Int) =
        edit(id) { it.copy(startSurah = surah, startAyah = ayah) }
    override suspend fun updateStatus(id: Long, status: SessionStatus, durationMs: Long, endedAt: Long?) =
        edit(id) { it.copy(status = status, durationMs = durationMs, endedAt = endedAt) }
    override suspend fun delete(id: Long) { rows.value = rows.value.filterNot { it.id == id } }
    override suspend fun deleteAll() { rows.value = emptyList() }
}

class FakeSettingsRepository(initial: UserSettings = UserSettings()) : SettingsRepository {
    val state = MutableStateFlow(initial)
    override val settings: Flow<UserSettings> = state
    override suspend fun update(transform: (UserSettings) -> UserSettings) { state.value = transform(state.value) }
}

class FakePlayback : QuranAudioPlayback {
    val playing = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = playing
    val emitted = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = 8)
    override val events: SharedFlow<PlaybackEvent> = emitted
    val played = mutableListOf<Triple<String, Long, Long?>>()

    override fun play(uri: String, tag: String, startMs: Long, endMs: Long?, speed: Float, volume: Float) {
        played += Triple(tag, startMs, endMs)
        playing.value = true
        emitted.tryEmit(PlaybackEvent.Started(tag))
    }
    fun finish(tag: String) {
        playing.value = false
        emitted.tryEmit(PlaybackEvent.Ended(tag, 0))
    }
    override fun stop() { playing.value = false }
    override fun release() = Unit
}

class FakeQariAudio(var audio: AyahAudio? = null) : QariAudioResolver {
    override suspend fun ayahAudio(qariId: String?, surah: Int, ayah: Int) = audio
}

/** Recognizer that emits nothing by itself; tests push events through [emit]. */
class FakeRecognizer(initial: ModelReadiness = ModelReadiness.Ready("fake", "1")) : QuranRecognitionEngine {
    val readinessFlow = MutableStateFlow(initial)
    override val readiness: StateFlow<ModelReadiness> = readinessFlow.asStateFlow()
    val events = MutableSharedFlow<RecognitionEvent>(extraBufferCapacity = 64)
    var listening = false
    override suspend fun initialize() = readinessFlow.value
    override fun startListening(audio: Flow<ShortArray>) { listening = true }
    override fun stopListening() { listening = false }
    override fun observeRecognition(): Flow<RecognitionEvent> = events
    var flushes = 0
    /** Runs inside [flush] (e.g. delivers a tail word synchronously, as the contract requires). */
    var onFlush: (() -> Unit)? = null
    override suspend fun flush() { flushes++; onFlush?.invoke() }
}

/** Silent 20 ms frames on the (virtual) clock. */
fun silentFrames(): Flow<ShortArray> = flow { while (true) { emit(ShortArray(320)); delay(20) } }

/** Synthetic reference: [surahs] × [ayahs] × [words] of random 6-letter Latin tokens (no Quran text). */
fun syntheticReference(surahs: Int = 3, ayahs: Int = 6, words: Int = 6): QuranReference {
    val rnd = kotlin.random.Random(42)
    val list = ArrayList<WordRef>()
    for (s in 1..surahs) for (a in 1..ayahs) for (w in 1..words) {
        list += WordRef(list.size, s, a, w, (1..6).map { 'a' + rnd.nextInt(26) }.joinToString(""))
    }
    return InMemoryQuranReference(list)
}

class FixedEngineReference(private val ref: QuranReference?) : EngineReferenceSource {
    override val status: StateFlow<EngineReferenceStatus> = MutableStateFlow(
        if (ref != null) EngineReferenceStatus.Ready(ref.wordCount, 1) else EngineReferenceStatus.Unavailable("missing")
    )
    override suspend fun get() = ref
}
