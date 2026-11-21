package com.qurancompanion.core.recognition

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Used until a recognition model is chosen and installed (Phase 2). It drains the audio flow
 * (so capture, focus and FGS behaviour can be exercised end-to-end on a device) but discards
 * every frame and emits no events. Position is then advanced manually from the UI.
 */
@Singleton
class UnavailableRecognitionEngine @Inject constructor() : QuranRecognitionEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> })
    private var job: Job? = null
    private val _readiness = MutableStateFlow<ModelReadiness>(ModelReadiness.NotInstalled)
    override val readiness: StateFlow<ModelReadiness> = _readiness.asStateFlow()

    override suspend fun initialize(): ModelReadiness = _readiness.value

    override fun startListening(audio: Flow<ShortArray>) {
        job?.cancel()
        job = scope.launch { audio.collect { /* discarded: no model installed */ } }
    }

    override fun stopListening() {
        job?.cancel()
        job = null
    }

    override fun observeRecognition(): Flow<RecognitionEvent> = emptyFlow()
}
