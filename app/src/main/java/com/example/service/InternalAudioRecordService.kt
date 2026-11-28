package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.MainActivity
import com.example.R
import com.example.ui.screens.recorder.RecorderStore
import com.example.util.InternalAudioCapture
import com.example.util.InternalAudioRecordController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

class InternalAudioRecordService : Service() {
    companion object {
        const val ACTION_START = "com.example.INTERNAL_AUDIO_START"
        const val ACTION_STOP = "com.example.INTERNAL_AUDIO_STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        private const val CHANNEL_ID = "internal_audio_recorder"
        private const val NOTIF_ID = 4402
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var projection: MediaProjection? = null
    private var capture: InternalAudioCapture? = null
    private var outputFile: File? = null
    private var timerJob: Job? = null
    private var startTimeMs = 0L
    private var stopping = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> return if (startCapture(intent)) START_STICKY else START_NOT_STICKY
            ACTION_STOP -> {
                stopCapture(null)
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        timerJob?.cancel()
        finishCapture(null)
        scope.cancel()
        super.onDestroy()
    }

    private fun startCapture(intent: Intent): Boolean {
        if (capture?.isRunning() == true || stopping) return true
        stopping = false
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        } ?: return fail("Screen capture permission was not returned")

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val manager = getSystemService(MediaProjectionManager::class.java)
        val newProjection = runCatching { manager.getMediaProjection(resultCode, data) }
            .getOrElse { return fail(it.message ?: "Could not start screen capture") }
            ?: return fail("Could not start screen capture")
        projection = newProjection
        newProjection.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    mainHandler.post { stopCapture("Screen capture stopped") }
                }
            },
            mainHandler
        )

        val file = RecorderStore.newFile(this, "SYS")
        outputFile = file
        val newCapture = InternalAudioCapture(onError = { message ->
            mainHandler.post { stopCapture(message) }
        })
        capture = newCapture
        startForegroundCompat("Preparing audio capture")
        runCatching { newCapture.start(this, newProjection, file) }
            .onFailure { return fail(it.message ?: "Could not start audio capture") }

        startTimeMs = System.currentTimeMillis()
        InternalAudioRecordController.setState(
            InternalAudioRecordController.State(
                isRecording = true,
                outputPath = file.absolutePath
            )
        )
        startTicker()
        updateNotification()
        return true
    }

    private fun startTicker() {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (isActive) {
                val elapsed = System.currentTimeMillis() - startTimeMs
                InternalAudioRecordController.setState(
                    InternalAudioRecordController.State(
                        isRecording = true,
                        elapsedMs = elapsed,
                        outputPath = outputFile?.absolutePath
                    )
                )
                updateNotification("Recording system audio ${formatElapsed(elapsed)}")
                delay(1000)
            }
        }
    }

    private fun stopCapture(error: String?) {
        finishCapture(error)
        stopSelf()
    }

    private fun finishCapture(error: String?) {
        if (stopping) return
        stopping = true
        timerJob?.cancel()
        capture?.stop()
        runCatching { projection?.stop() }
        outputFile?.let { RecorderStore.scan(this, it) }
        capture = null
        projection = null
        InternalAudioRecordController.setState(
            InternalAudioRecordController.State(
                outputPath = outputFile?.absolutePath,
                error = error
            )
        )
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun fail(message: String): Boolean {
        InternalAudioRecordController.setState(
            InternalAudioRecordController.State(error = message)
        )
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        return false
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "System audio recorder", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun startForegroundCompat(text: String) {
        createChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                buildNotification(text),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(text), 0)
        }
    }

    private fun updateNotification(text: String = "Recording system audio") {
        val manager = getSystemService(NotificationManager::class.java)
        runCatching { manager.notify(NOTIF_ID, buildNotification(text)) }
    }

    private fun buildNotification(text: String): Notification {
        val app = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, InternalAudioRecordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle("System audio recording")
            .setContentText(text)
            .setContentIntent(app)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun formatElapsed(ms: Long): String {
        val seconds = ms / 1000
        return String.format(java.util.Locale.getDefault(), "%02d:%02d", seconds / 60, seconds % 60)
    }
}
