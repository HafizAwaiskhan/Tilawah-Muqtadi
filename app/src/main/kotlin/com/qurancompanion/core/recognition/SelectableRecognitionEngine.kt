package com.qurancompanion.core.recognition

import com.qurancompanion.BuildConfig
import com.qurancompanion.data.settings.SettingsRepository
import com.qurancompanion.di.ApplicationScope
import com.qurancompanion.engine.recognition.RecognitionEvent
import com.qurancompanion.engine.recognition.RecognizerStatus
import com.qurancompanion.engine.recognition.TokenUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton

/** Creates the debug-only simulated recognizer (bound only in debug builds, see src/debug). */
fun interface SimulatedRecognizerFactory {
    fun create(): QuranRecognitionEngine
}

/**
 * The engine the app uses: the real model engine, or — in debug builds, when chosen in
 * Settings → Developer — the simulated recognizer. Selection is read when listening starts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class SelectableRecognitionEngine @Inject constructor(
    private val real: UnavailableRecognitionEngine,
    simulatedFactory: Optional<SimulatedRecognizerFactory>,
    settings: SettingsRepository,
    @ApplicationScope scope: CoroutineScope,
) : QuranRecognitionEngine {
    private val simulated: QuranRecognitionEngine? by lazy {
        if (BuildConfig.DEBUG) simulatedFactory.orElse(null)?.create() else null
    }

    private val selected: StateFlow<QuranRecognitionEngine> = settings.settings
        .map { it.developer.simulatedRecognizer }
        .distinctUntilChanged()
        .map { useSim -> if (useSim) simulated ?: real else real }
        .stateIn(scope, SharingStarted.Eagerly, real)

    override val readiness: StateFlow<ModelReadiness> = selected
        .flatMapLatest { it.readiness }
        .stateIn(scope, SharingStarted.Eagerly, real.readiness.value)

    override val unit: TokenUnit get() = selected.value.unit

    private var listening: QuranRecognitionEngine? = null

    override suspend fun initialize(): ModelReadiness = selected.value.initialize()

    override fun startListening(audio: Flow<ShortArray>) {
        listening?.stopListening()
        listening = selected.value.also { it.startListening(audio) }
    }

    override fun stopListening() {
        listening?.stopListening()
        listening = null
    }

    override fun observeRecognition(): Flow<RecognitionEvent> = selected.flatMapLatest { it.observeRecognition() }
    override fun observeStatus(): Flow<RecognizerStatus> = selected.flatMapLatest { it.observeStatus() }
}
