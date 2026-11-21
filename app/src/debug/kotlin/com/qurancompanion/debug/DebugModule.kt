package com.qurancompanion.debug

import com.qurancompanion.core.recognition.QuranRecognitionEngine
import com.qurancompanion.core.recognition.SimulatedRecognizerFactory
import com.qurancompanion.core.time.Clock
import com.qurancompanion.data.reference.EngineReferenceSource
import com.qurancompanion.data.settings.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Inject

class DebugSimulatedRecognizerFactory @Inject constructor(
    private val reference: EngineReferenceSource,
    private val settings: SettingsRepository,
    private val clock: Clock,
) : SimulatedRecognizerFactory {
    override fun create(): QuranRecognitionEngine =
        SimulatedRecognitionEngine(reference, settings.settings, clock, CoroutineScope(SupervisorJob() + Dispatchers.Default))
}

/** Debug builds only: makes the simulated recognizer selectable in Settings → Developer. */
@Module
@InstallIn(SingletonComponent::class)
abstract class DebugModule {
    @Binds abstract fun simulatedRecognizer(impl: DebugSimulatedRecognizerFactory): SimulatedRecognizerFactory
}
