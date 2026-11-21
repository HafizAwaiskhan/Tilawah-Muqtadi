package com.qurancompanion.core.playback

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed interface PlaybackEvent {
    data class Started(val tag: String) : PlaybackEvent
    /** Playback finished, was stopped, or failed. [atElapsedMs] lets recognition apply a tail. */
    data class Ended(val tag: String, val atElapsedMs: Long, val error: String? = null) : PlaybackEvent
}

/** Abstraction over Qari audio playback so session logic can be tested without Media3. */
interface QuranAudioPlayback {
    /** True while hint/ayah audio is audible; recognition must be suppressed meanwhile. */
    val isPlaying: StateFlow<Boolean>
    val events: SharedFlow<PlaybackEvent>
    /** Plays [uri] from [startMs] to [endMs] (null = to the end). Must be called on the main thread. */
    fun play(uri: String, tag: String, startMs: Long = 0, endMs: Long? = null, speed: Float = 1f, volume: Float = 1f)
    fun stop()
    fun release()
}

/**
 * Media3/ExoPlayer implementation. Audio focus is NOT handled by ExoPlayer here: the recitation
 * session already holds focus (AudioInterruptionMonitor), and a second focus request from our own
 * player would be reported as an interruption.
 */
@Singleton
class QuranAudioPlaybackController @Inject constructor(
    @ApplicationContext private val context: Context,
) : QuranAudioPlayback {

    private var player: ExoPlayer? = null
    private var currentTag: String? = null
    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    private val _events = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = 8)
    override val events: SharedFlow<PlaybackEvent> = _events.asSharedFlow()

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                _isPlaying.value = true
                currentTag?.let { _events.tryEmit(PlaybackEvent.Started(it)) }
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED || state == Player.STATE_IDLE) finish(null)
        }

        override fun onPlayerError(error: PlaybackException) = finish(error.errorCodeName)
    }

    private fun finish(error: String?) {
        if (!_isPlaying.value && error == null) return
        _isPlaying.value = false
        val tag = currentTag ?: return
        currentTag = null
        _events.tryEmit(PlaybackEvent.Ended(tag, android.os.SystemClock.elapsedRealtime(), error))
    }

    private fun obtainPlayer(): ExoPlayer = player ?: ExoPlayer.Builder(context)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .build(),
            /* handleAudioFocus = */ false,
        )
        .build()
        .also {
            it.addListener(listener)
            player = it
        }

    override fun play(uri: String, tag: String, startMs: Long, endMs: Long?, speed: Float, volume: Float) {
        val p = obtainPlayer()
        if (currentTag != null) finish(null)
        currentTag = tag
        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(startMs)
            .apply { if (endMs != null) setEndPositionMs(endMs) }
            .build()
        p.setMediaItem(MediaItem.Builder().setUri(uri).setClippingConfiguration(clipping).build())
        p.setPlaybackSpeed(speed)
        p.volume = volume
        p.prepare()
        p.play()
    }

    override fun stop() {
        player?.stop()
        finish(null)
    }

    override fun release() {
        stop()
        player?.release()
        player = null
    }
}
