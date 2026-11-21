package com.qurancompanion.di

import android.content.Context
import androidx.room.Room
import com.qurancompanion.core.cue.AndroidSessionCues
import com.qurancompanion.core.cue.SessionCues
import com.qurancompanion.core.net.AndroidNetworkStatus
import com.qurancompanion.core.net.NetworkStatus
import com.qurancompanion.core.playback.TestQariAudioResolver
import com.qurancompanion.core.qari.AlafasyTestQari
import com.qurancompanion.core.qari.AssetQariTimingSource
import com.qurancompanion.core.qari.QariDownloadManager
import com.qurancompanion.core.qari.QariTimingSource
import com.qurancompanion.core.playback.QariAudioResolver
import com.qurancompanion.core.playback.QuranAudioPlayback
import com.qurancompanion.core.playback.QuranAudioPlaybackController
import com.qurancompanion.core.model.AsrModelSpec
import com.qurancompanion.core.model.ModelDownloadManager
import com.qurancompanion.core.model.UrlConnectionHttpSource
import com.qurancompanion.core.recognition.AbdouOnnxRecognizerLoader
import com.qurancompanion.core.recognition.AbdouRecognitionEngine
import com.qurancompanion.core.recognition.QuranRecognitionEngine
import com.qurancompanion.core.recognition.SelectableRecognitionEngine
import com.qurancompanion.core.recognition.SimulatedRecognizerFactory
import com.qurancompanion.core.time.Clock
import com.qurancompanion.core.time.SystemClockImpl
import com.qurancompanion.data.db.AppDatabase
import com.qurancompanion.data.db.BookmarkDao
import com.qurancompanion.data.db.FlaggedWordDao
import com.qurancompanion.data.db.SessionDao
import com.qurancompanion.data.reference.CachedEngineReference
import com.qurancompanion.data.reference.EngineReferenceSource
import com.qurancompanion.data.reference.QuranReferenceRepository
import com.qurancompanion.data.reference.SqliteQuranReferenceRepository
import com.qurancompanion.data.session.BookmarkRepository
import com.qurancompanion.data.session.RoomBookmarkRepository
import com.qurancompanion.data.session.RoomSessionRepository
import com.qurancompanion.data.session.SessionRepository
import com.qurancompanion.data.settings.DataStoreSettingsRepository
import com.qurancompanion.data.settings.SettingsRepository
import dagger.Binds
import dagger.BindsOptionalOf
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import java.io.File
import java.util.concurrent.Executors
import javax.inject.Qualifier
import javax.inject.Singleton

/** Process-wide scope on the main thread (session state, playback calls). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/** Single-threaded dispatcher on which the engine's session controller runs (never Main). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class EngineDispatcher

@Module
@InstallIn(SingletonComponent::class)
object AppProvidesModule {
    @Provides @Singleton @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Provides @Singleton @EngineDispatcher
    fun engineDispatcher(): CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

    @Provides @Singleton
    fun modelDownloads(@ApplicationContext context: Context, @ApplicationScope scope: CoroutineScope): ModelDownloadManager =
        ModelDownloadManager(File(context.filesDir, "models"), AsrModelSpec.ABDOU_V1, UrlConnectionHttpSource(), Dispatchers.IO, scope)

    /** Alafasy test audio (personal testing only), filesDir/qari/alafasy-test/. */
    @Provides @Singleton
    fun qariDownloads(@ApplicationContext context: Context, @ApplicationScope scope: CoroutineScope): QariDownloadManager =
        QariDownloadManager(File(context.filesDir, "qari/${AlafasyTestQari.ID}"), UrlConnectionHttpSource(), Dispatchers.IO, scope)

    /** One dedicated thread runs the ASR model (load, accept, flush). */
    @Provides @Singleton
    fun abdouEngine(downloads: ModelDownloadManager, clock: Clock): AbdouRecognitionEngine {
        val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "abdou-asr").apply { isDaemon = true } }
            .asCoroutineDispatcher()
        return AbdouRecognitionEngine(
            downloads.spec, downloads.state, AbdouOnnxRecognizerLoader(threads = 2), worker,
            clock::elapsedRealtime, CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    }

    @Provides fun sessionDao(db: AppDatabase): SessionDao = db.sessionDao()
    @Provides fun bookmarkDao(db: AppDatabase): BookmarkDao = db.bookmarkDao()
    @Provides fun flaggedWordDao(db: AppDatabase): FlaggedWordDao = db.flaggedWordDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AppBindsModule {
    @Binds abstract fun clock(impl: SystemClockImpl): Clock
    @Binds abstract fun settings(impl: DataStoreSettingsRepository): SettingsRepository
    @Binds abstract fun sessions(impl: RoomSessionRepository): SessionRepository
    @Binds abstract fun bookmarks(impl: RoomBookmarkRepository): BookmarkRepository
    @Binds abstract fun reference(impl: SqliteQuranReferenceRepository): QuranReferenceRepository
    @Binds abstract fun playback(impl: QuranAudioPlaybackController): QuranAudioPlayback
    @Binds abstract fun qariAudio(impl: TestQariAudioResolver): QariAudioResolver
    @Binds abstract fun qariTimings(impl: AssetQariTimingSource): QariTimingSource
    @Binds abstract fun network(impl: AndroidNetworkStatus): NetworkStatus
    @Binds abstract fun cues(impl: AndroidSessionCues): SessionCues

    @Binds abstract fun engineReference(impl: CachedEngineReference): EngineReferenceSource

    /** Abdou model engine when installed, manual mode otherwise, or (debug) the simulator. */
    @Binds abstract fun recognition(impl: SelectableRecognitionEngine): QuranRecognitionEngine

    /** Bound only in debug builds (src/debug DebugModule). */
    @BindsOptionalOf abstract fun simulatedRecognizer(): SimulatedRecognizerFactory
}
