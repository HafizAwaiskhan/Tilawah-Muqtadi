package com.qurancompanion

import android.net.Uri
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

class FakeClock(var now: Long = 1_000_000L, var elapsed: Long = 0L) : Clock {
    override fun nowMillis() = now
    override fun elapsedRealtime() = elapsed
    fun advance(ms: Long) { now += ms; elapsed += ms }
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

    override fun play(uri: Uri, tag: String, startMs: Long, endMs: Long?, speed: Float, volume: Float) {
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
