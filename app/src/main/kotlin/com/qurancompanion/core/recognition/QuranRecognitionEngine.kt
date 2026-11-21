package com.qurancompanion.core.recognition

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

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
 * Thin Android-side recognition event type.
 *
 * INTEGRATION TODO(engine): once `:engine` publishes its `RecognitionEvent` type
 * (docs/ENGINE.md), replace this with a typealias or a mapping adapter (see docs/ANDROID.md).
 */
sealed interface RecognitionEvent {
    /**
     * A recognized token. Provisional tokens may still change; only stable tokens may move
     * the confirmed position. Times are relative to the start of listening.
     */
    data class Token(
        val text: String,
        val stable: Boolean,
        val startMs: Long,
        val endMs: Long,
        val confidence: Float,
    ) : RecognitionEvent

    /** Voice activity changed (speech started/stopped) — used by the pause detector. */
    data class VoiceActivity(val speaking: Boolean, val atMs: Long) : RecognitionEvent

    data class Error(val message: String, val recoverable: Boolean) : RecognitionEvent
}

/**
 * On-device recognition engine (one implementation per model, behind this interface —
 * PHASE0_PLAN §6). Audio never leaves the device.
 */
interface QuranRecognitionEngine {
    val readiness: StateFlow<ModelReadiness>

    /** Loads the model if installed. Idempotent. */
    suspend fun initialize(): ModelReadiness

    /**
     * Starts consuming [audio] (16 kHz mono PCM16 frames). The caller gates the flow (it stops
     * emitting while paused or while Qari playback is active), so the engine never hears hints.
     */
    fun startListening(audio: Flow<ShortArray>)

    fun stopListening()

    fun observeRecognition(): Flow<RecognitionEvent>
}
