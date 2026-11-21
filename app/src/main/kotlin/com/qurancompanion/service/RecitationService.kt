package com.qurancompanion.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.qurancompanion.MainActivity
import com.qurancompanion.R
import com.qurancompanion.core.audio.AudioCapture
import com.qurancompanion.core.audio.AudioCaptureException
import com.qurancompanion.core.audio.AudioInterruptionMonitor
import com.qurancompanion.core.recognition.QuranRecognitionEngine
import com.qurancompanion.session.InterruptionReason
import com.qurancompanion.session.ListeningStatus
import com.qurancompanion.session.RecitationSessionManager
import com.qurancompanion.session.SessionState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Microphone foreground service for a recitation session.
 *
 * - Started only from the visible app ([start]); Android 14+ forbids starting a
 *   microphone-type FGS from the background. Not sticky: after process death the user resumes
 *   from Home (the last confirmed position is in Room).
 * - Opens the mic only while the session is LISTENING and no Qari audio is playing
 *   ([SessionState.audioGateOpen]); the AudioRecord is released when paused.
 * - Ongoing notification with Pause/Resume/Stop.
 */
@AndroidEntryPoint
class RecitationService : LifecycleService() {

    @Inject lateinit var manager: RecitationSessionManager
    @Inject lateinit var capture: AudioCapture
    @Inject lateinit var interruptions: AudioInterruptionMonitor
    @Inject lateinit var engine: QuranRecognitionEngine

    private var started = false
    private var seenRunning = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_PAUSE -> manager.pause()
            ACTION_RESUME -> manager.resume()
            ACTION_STOP -> manager.stop()
            else -> if (!started) startSession()
        }
        if (!started && intent?.action != null && intent.action != ACTION_START) {
            // A notification action arrived after the session was gone (e.g. process restart).
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startSession() {
        createChannel(this)
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(manager.state.value),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
            )
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException (12+) or SecurityException (14+ when
            // started from background / without RECORD_AUDIO).
            val bgDenied = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException
            Log.w(TAG, "Could not start microphone FGS (backgroundDenied=$bgDenied)", e)
            manager.interrupt(InterruptionReason.PERMISSION_DENIED)
            stopSelf()
            return
        }
        started = true

        lifecycleScope.launch { engine.initialize() }
        engine.startListening(gatedAudio())

        lifecycleScope.launch { engine.observeRecognition().collect(manager::onRecognitionEvent) }
        lifecycleScope.launch { interruptions.signals().collect(manager::onInterruptionSignal) }
        lifecycleScope.launch {
            capture.silenced.filter { it }.collect { manager.interrupt(InterruptionReason.CALL) }
        }
        lifecycleScope.launch {
            manager.state
                .map { NotificationModel(it.status, it.position.label()) }
                .distinctUntilChanged()
                .collect { model ->
                    // A resumed session is restored asynchronously, so IDLE only ends the
                    // service once a session has actually been running.
                    if (model.status.isRunning) seenRunning = true
                    if (model.status == ListeningStatus.STOPPED ||
                        (model.status == ListeningStatus.IDLE && seenRunning)
                    ) {
                        stopSelf()
                    } else {
                        getSystemService(NotificationManager::class.java)
                            .notify(NOTIFICATION_ID, buildNotification(manager.state.value))
                    }
                }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun gatedAudio() = manager.state
        .map { it.audioGateOpen }
        .distinctUntilChanged()
        .flatMapLatest { open ->
            if (!open) emptyFlow() else capture.frames().catch { e ->
                Log.w(TAG, "Audio capture failed", e)
                manager.interrupt(
                    if (e is AudioCaptureException.PermissionDenied) InterruptionReason.PERMISSION_DENIED
                    else InterruptionReason.CAPTURE_ERROR
                )
            }
        }

    override fun onDestroy() {
        engine.stopListening()
        // If the system kills the service while running, keep the position (already persisted)
        // and mark it interrupted so Home can offer resume.
        manager.interrupt(InterruptionReason.CAPTURE_ERROR)
        started = false
        super.onDestroy()
    }

    private data class NotificationModel(val status: ListeningStatus, val position: String)

    private fun buildNotification(state: SessionState): Notification {
        val title = when (state.status) {
            ListeningStatus.PAUSED -> getString(R.string.notification_title_paused)
            ListeningStatus.INTERRUPTED -> getString(R.string.notification_title_interrupted)
            else -> getString(R.string.notification_title_listening)
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_RECITATION, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText("Position ${state.position.label()}")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
        if (state.status == ListeningStatus.LISTENING) {
            builder.addAction(0, getString(R.string.notification_action_pause), actionIntent(ACTION_PAUSE))
        } else {
            builder.addAction(0, getString(R.string.notification_action_resume), actionIntent(ACTION_RESUME))
        }
        builder.addAction(0, getString(R.string.notification_action_stop), actionIntent(ACTION_STOP))
        return builder.build()
    }

    private fun actionIntent(action: String): PendingIntent = PendingIntent.getService(
        this, action.hashCode(),
        Intent(this, RecitationService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val TAG = "RecitationService"
        private const val CHANNEL_ID = "recitation"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.qurancompanion.action.START"
        const val ACTION_PAUSE = "com.qurancompanion.action.PAUSE"
        const val ACTION_RESUME = "com.qurancompanion.action.RESUME"
        const val ACTION_STOP = "com.qurancompanion.action.STOP"

        /** Must be called while the app is visible (user tapped Start/Resume). */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, RecitationService::class.java).setAction(ACTION_START)
            )
        }

        private fun createChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID, context.getString(R.string.notification_channel_recitation),
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply { description = context.getString(R.string.notification_channel_recitation_desc) }
                )
            }
        }
    }
}
