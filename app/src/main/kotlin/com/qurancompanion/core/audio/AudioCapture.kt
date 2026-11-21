package com.qurancompanion.core.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

sealed class AudioCaptureException(message: String) : Exception(message) {
    class PermissionDenied : AudioCaptureException("RECORD_AUDIO permission not granted")
    class InitFailed(detail: String) : AudioCaptureException("AudioRecord init failed: $detail")
    class ReadFailed(val code: Int) : AudioCaptureException("AudioRecord.read error $code")
}

/**
 * Microphone capture: 16 kHz, mono, PCM16, source VOICE_RECOGNITION (minimal platform
 * processing, as recommended for ASR). Frames are emitted as [ShortArray]s of [FRAME_SAMPLES]
 * samples (20 ms). Audio is **never written to disk**; frames go straight to the recognizer.
 */
@Singleton
class AudioCapture @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val _silenced = MutableStateFlow(false)

    /** True while the OS silences our recording (e.g. during a phone call, API 29+). */
    val silenced: StateFlow<Boolean> = _silenced.asStateFlow()

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** Cold flow: collecting starts recording, cancelling stops and releases the recorder. */
    @SuppressLint("MissingPermission") // checked via hasPermission() before creating AudioRecord
    fun frames(): Flow<ShortArray> = flow {
        if (!hasPermission()) throw AudioCaptureException.PermissionDenied()
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuf <= 0) throw AudioCaptureException.InitFailed("getMinBufferSize=$minBuf")
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, CHANNEL, ENCODING,
                maxOf(minBuf, FRAME_SAMPLES * 2 * 4),
            )
        } catch (e: Exception) {
            throw AudioCaptureException.InitFailed(e.message ?: e.javaClass.simpleName)
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw AudioCaptureException.InitFailed("state=${record.state}")
        }
        // Second guard against Qari playback leaking into the mic (PHASE0_PLAN §5).
        val aec = if (AcousticEchoCanceler.isAvailable()) {
            runCatching { AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true } }.getOrNull()
        } else null
        val callback = registerSilencedCallback(record)
        try {
            record.startRecording()
            val buffer = ShortArray(FRAME_SAMPLES)
            while (currentCoroutineContext().isActive) {
                val n = record.read(buffer, 0, FRAME_SAMPLES)
                when {
                    n > 0 -> emit(buffer.copyOf(n))
                    n == 0 -> Unit
                    else -> throw AudioCaptureException.ReadFailed(n)
                }
            }
        } finally {
            callback?.let { unregisterSilencedCallback(record, it) }
            _silenced.value = false
            runCatching { record.stop() }
            aec?.release()
            record.release()
        }
    }.flowOn(Dispatchers.IO)

    private fun registerSilencedCallback(record: AudioRecord): Any? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val cb = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                val mine = configs.firstOrNull { it.clientAudioSessionId == record.audioSessionId }
                _silenced.value = mine?.isClientSilenced == true
            }
        }
        record.registerAudioRecordingCallback(DirectExecutor, cb)
        return cb
    }

    private fun unregisterSilencedCallback(record: AudioRecord, cb: Any) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            record.unregisterAudioRecordingCallback(cb as AudioManager.AudioRecordingCallback)
        }
    }

    private object DirectExecutor : Executor {
        override fun execute(command: Runnable) = command.run()
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_SAMPLES = 320 // 20 ms @ 16 kHz
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

    }
}
