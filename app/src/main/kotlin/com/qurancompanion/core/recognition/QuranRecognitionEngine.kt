package com.qurancompanion.core.recognition

import com.qurancompanion.engine.recognition.RecognitionEvent
import com.qurancompanion.engine.recognition.RecognizerStatus
import com.qurancompanion.engine.recognition.TokenUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/** Readiness of the on-device recognition model (downloaded, verified, loaded). */
sealed interface ModelReadiness {
    /** No model is bundled/downloaded (current state until Phase 2 picks a model). */
    data object NotInstalled : ModelReadiness
    data class Downloading(val progress: Float) : ModelReadiness
    data object Loading : ModelReadiness
    data class Ready(val modelId: String, val version: String) : ModelReadiness
    data class Failed(val message: String) : ModelReadiness

    val isReady: Boolean get() = this is Ready
}

/**
 * On-device recognition engine (one implementation per model, behind this interface —
 * PHASE0_PLAN §6). Audio never leaves the device.
 *
 * Output is the engine's [RecognitionEvent] (docs/ENGINE.md "Recognition"): stable events are
 * appended (each stable token exactly once, in order), a provisional event replaces the previous
 * provisional hypothesis; token text uses the reference's `norm_skeleton` normalization (word
 * unit) or its phoneme inventory. Token times must be `SystemClock.elapsedRealtime()` ms (the
 * session controller's clock).
 */
interface QuranRecognitionEngine {
    val readiness: StateFlow<ModelReadiness>

    /** Token unit of [observeRecognition]. */
    val unit: TokenUnit get() = TokenUnit.WORD

    /** Loads the model if installed. Idempotent. */
    suspend fun initialize(): ModelReadiness

    /**
     * Starts consuming [audio] (16 kHz mono PCM16 frames). The caller gates the flow (it stops
     * emitting while paused or while Qari playback is active), so the engine never hears hints.
     */
    fun startListening(audio: Flow<ShortArray>)

    fun stopListening()

    fun observeRecognition(): Flow<RecognitionEvent>

    /** Recognizer status changes (errors in particular); forwarded to the session controller. */
    fun observeStatus(): Flow<RecognizerStatus> = emptyFlow()
}
