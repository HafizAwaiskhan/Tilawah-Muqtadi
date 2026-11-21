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
import kotlinx.coroutines.flow.MutableStateFlow
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
 * The engine the app uses, chosen per session (in [initialize]):
 * 1. debug builds with Settings → Developer → "Simulated recognizer": the simulator;
 * 2. a verified model is installed and loads: [AbdouRecognitionEngine] (default);
 * 3. otherwise [UnavailableRecognitionEngine] — manual mode (tap the word you are on).
 * [readiness] follows the model (download progress, loading, ready) unless the simulator is chosen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class SelectableRecognitionEngine @Inject constructor(
    private val model: AbdouRecognitionEngine,
    private val manual: UnavailableRecognitionEngine,
    simulatedFactory: Optional<SimulatedRecognizerFactory>,
    settings: SettingsRepository,
    @ApplicationScope scope: CoroutineScope,
) : QuranRecognitionEngine {
    private val simulated: QuranRecognitionEngine? by lazy {
        if (BuildConfig.DEBUG) simulatedFactory.orElse(null)?.create() else null
    }

    private val useSimulator: StateFlow<Boolean> = settings.settings
        .map { it.developer.simulatedRecognizer }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, false)

    override val readiness: StateFlow<ModelReadiness> = useSimulator
        .flatMapLatest { sim -> if (sim) (simulated ?: model).readiness else model.readiness }
        .stateIn(scope, SharingStarted.Eagerly, model.readiness.value)

    /** Engine of the current/next session. */
    private val active = MutableStateFlow<QuranRecognitionEngine>(manual)
    val activeEngine: StateFlow<QuranRecognitionEngine> = active

    override val unit: TokenUnit get() = active.value.unit

    private var listening: QuranRecognitionEngine? = null

    private fun preferred(): QuranRecognitionEngine = when {
        useSimulator.value && simulated != null -> simulated!!
        model.isInstalled -> model
        else -> manual
    }

    override suspend fun initialize(): ModelReadiness {
        val e = preferred()
        val r = e.initialize()
        // A model that fails to load falls back to manual mode (its readiness shows the error).
        active.value = if (e === model && r !is ModelReadiness.Ready) manual else e
        return r
    }

    override fun startListening(audio: Flow<ShortArray>) {
        listening?.stopListening()
        listening = active.value.also { it.startListening(audio) }
    }

    override fun stopListening() {
        listening?.stopListening()
        listening = null
    }

    override suspend fun flush() = (listening ?: active.value).flush()

    override fun observeRecognition(): Flow<RecognitionEvent> = active.flatMapLatest { it.observeRecognition() }
    override fun observeStatus(): Flow<RecognizerStatus> = active.flatMapLatest { it.observeStatus() }
}
