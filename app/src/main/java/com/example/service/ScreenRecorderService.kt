package com.example.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.util.InternalAudioCapture
import com.example.util.MediaRemuxer
import com.example.util.ScreenRecorderController
import com.example.util.ScreenRecorderOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

class ScreenRecorderService : Service() {
    companion object {
        const val ACTION_START = "com.example.SCREEN_RECORDER_START"
        const val ACTION_STOP = "com.example.SCREEN_RECORDER_STOP"
        const val ACTION_PAUSE = "com.example.SCREEN_RECORDER_PAUSE"
        const val ACTION_RESUME = "com.example.SCREEN_RECORDER_RESUME"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_OPTIONS = "options"
        const val EXTRA_MIC_ENABLED = "mic_enabled"
        const val EXTRA_SYSTEM_AUDIO_ENABLED = "system_audio_enabled"
        const val EXTRA_DURATION_SEC = "duration_sec"

        private const val CHANNEL_ID = "screen_recorder"
        private const val NOTIF_ID = 4401
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var projection: MediaProjection? = null
    private var recorder: MediaRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var audioCapture: InternalAudioCapture? = null
    private var videoFile: File? = null
    private var audioFile: File? = null
    private var finalFile: File? = null
    private var timerJob: Job? = null
    private var options = ScreenRecorderOptions()
    private var startTimeMs = 0L
    private var pausedAtMs = 0L
    private var pausedTotalMs = 0L
    private var isPaused = false
    private var stopping = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> return if (startRecording(intent)) START_STICKY else START_NOT_STICKY
            ACTION_STOP -> {
                stopRecording(null)
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                pauseRecording()
                return START_NOT_STICKY
            }
            ACTION_RESUME -> {
                resumeRecording()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        timerJob?.cancel()
        finishRecorder(null)
        scope.cancel()
        super.onDestroy()
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun startRecording(intent: Intent): Boolean {
        if (recorder != null || stopping) return true
        stopping = false
        options = ScreenRecorderOptions(
            quality = runCatching {
                com.example.util.ScreenRecorderQuality.valueOf(
                    intent.getStringExtra(EXTRA_OPTIONS) ?: ""
                )
            }.getOrDefault(com.example.util.ScreenRecorderQuality.STANDARD),
            micEnabled = intent.getBooleanExtra(EXTRA_MIC_ENABLED, false) && hasMicPermission(),
            systemAudioEnabled = intent.getBooleanExtra(EXTRA_SYSTEM_AUDIO_ENABLED, false),
            durationSec = intent.getIntExtra(EXTRA_DURATION_SEC, 0).coerceAtLeast(0)
        )
        if (options.micEnabled) options = options.copy(micEnabled = true)
        if (options.systemAudioEnabled && !InternalAudioCapture.supported()) {
            options = options.copy(systemAudioEnabled = false)
            mainHandler.post {
                android.widget.Toast.makeText(
                    applicationContext,
                    "System audio capture needs Android 10 or newer; recording silently",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        }
        if (data == null) return fail("Screen capture permission was not returned")

        ScreenRecorderController.setState(
            ScreenRecorderController.State(isStarting = true, options = options)
        )
        startForegroundCompat("Preparing recording")

        val manager = getSystemService(MediaProjectionManager::class.java)
        val newProjection = runCatching { manager.getMediaProjection(resultCode, data) }
            .getOrElse { return fail(it.message ?: "Could not start screen capture") }
            ?: return fail("Could not start screen capture")
        projection = newProjection
        newProjection.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    mainHandler.post { stopRecording("Screen capture stopped") }
                }
            },
            mainHandler
        )

        if (!prepareRecorder(newProjection)) {
            finishRecorder(null)
            stopSelf()
            return false
        }

        startTimeMs = System.currentTimeMillis()
        pausedAtMs = 0L
        pausedTotalMs = 0L
        isPaused = false
        ScreenRecorderController.setState(
            ScreenRecorderController.State(
                isRecording = true,
                outputPath = finalFile?.absolutePath,
                options = options
            )
        )
        startTicker()
        updateNotification()
        return true
    }

    private fun prepareRecorder(newProjection: MediaProjection): Boolean {
        val (width, height) = screenSize(options.quality.maxDimension)
        return runCatching {
            finalFile = ScreenRecorderController.createOutputFile(this)
            val target = finalFile ?: error("Could not create output file")
            val systemAudio = options.systemAudioEnabled
            if (systemAudio) {
                videoFile = File(cacheDir, "nj_screen_video_${System.currentTimeMillis()}.mp4")
                audioFile = File(cacheDir, "nj_screen_audio_${System.currentTimeMillis()}.m4a")
            } else {
                videoFile = target
            }
            val file = videoFile ?: error("Could not create temporary video file")

            val newRecorder = MediaRecorder(this)
            recorder = newRecorder
            newRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            val useMicInRecorder = options.micEnabled && !systemAudio
            if (useMicInRecorder) {
                newRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            }
            newRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            newRecorder.setOutputFile(file.absolutePath)
            newRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            if (useMicInRecorder) {
                newRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                newRecorder.setAudioEncodingBitRate(128_000)
                newRecorder.setAudioSamplingRate(44_100)
            }
            newRecorder.setVideoSize(width, height)
            newRecorder.setVideoFrameRate(options.quality.fps)
            newRecorder.setVideoEncodingBitRate(options.quality.bitrate)
            newRecorder.prepare()
            val display = newProjection.createVirtualDisplay(
                "NJ Screen Recorder",
                width,
                height,
                resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                newRecorder.surface,
                null,
                null
            )
            virtualDisplay = display
            newRecorder.start()

            if (systemAudio) {
                val audio = InternalAudioCapture(includeMic = options.micEnabled) { message ->
                    mainHandler.post { stopRecording(message) }
                }
                audioCapture = audio
                audio.start(this, newProjection, audioFile ?: error("Could not create audio file"))
            }
            true
        }.getOrElse {
            ScreenRecorderController.setState(
                ScreenRecorderController.State(options = options, error = it.message ?: "Could not start recording")
            )
            false
        }
    }

    private fun startTicker() {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (isActive) {
                val elapsed = elapsedMs()
                ScreenRecorderController.setState(
                    ScreenRecorderController.State(
                        isRecording = true,
                        isPaused = isPaused,
                        elapsedMs = elapsed,
                        outputPath = finalFile?.absolutePath,
                        options = options
                    )
                )
                updateNotification(
                    when {
                        isPaused -> "Recording paused ${formatElapsed(elapsed)}"
                        else -> "Recording ${formatElapsed(elapsed)}"
                    }
                )
                if (options.durationSec > 0 && elapsed >= options.durationSec * 1000L) {
                    stopRecording(null)
                    return@launch
                }
                delay(1000)
            }
        }
    }

    private fun elapsedMs(): Long {
        val now = System.currentTimeMillis()
        val activePause = if (isPaused) now - pausedAtMs else 0L
        return (now - startTimeMs - pausedTotalMs - activePause).coerceAtLeast(0L)
    }

    private fun pauseRecording() {
        if (recorder == null || isPaused || stopping) return
        isPaused = true
        pausedAtMs = System.currentTimeMillis()
        runCatching { if (recorder != null) recorder?.pause() }
        audioCapture?.pause()
        updateNotification("Recording paused ${formatElapsed(elapsedMs())}")
    }

    private fun resumeRecording() {
        if (recorder == null || !isPaused || stopping) return
        pausedTotalMs += System.currentTimeMillis() - pausedAtMs
        isPaused = false
        runCatching { if (recorder != null) recorder?.resume() }
        audioCapture?.resume()
        updateNotification("Recording ${formatElapsed(elapsedMs())}")
    }

    private fun stopRecording(error: String?) {
        finishRecorder(error)
        stopSelf()
    }

    private fun finishRecorder(error: String?) {
        if (stopping) return
        stopping = true
        timerJob?.cancel()
        var finalError = error
        if (isPaused) {
            runCatching { recorder?.resume() }
            pausedTotalMs += System.currentTimeMillis() - pausedAtMs
            isPaused = false
        }
        runCatching { recorder?.stop() }.onFailure {
            finalError = finalError ?: (it.message ?: "Could not finish recording")
        }
        runCatching { virtualDisplay?.release() }
        runCatching { audioCapture?.stop() }
        runCatching { projection?.stop() }
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }

        if (options.systemAudioEnabled) {
            val video = videoFile
            val audio = audioFile
            val target = finalFile
            if (video != null && target != null) {
                runCatching { MediaRemuxer.mux(video, audio, target) }
                    .onFailure { finalError = finalError ?: (it.message ?: "Could not combine audio and video") }
            }
        }
        finalFile?.let { file ->
            runCatching { MediaScannerConnection.scanFile(applicationContext, arrayOf(file.absolutePath), null, null) }
        }
        recorder = null
        virtualDisplay = null
        audioCapture = null
        projection = null
        ScreenRecorderController.setState(
            ScreenRecorderController.State(
                outputPath = finalFile?.absolutePath,
                options = options,
                error = finalError
            )
        )
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun fail(message: String): Boolean {
        ScreenRecorderController.setState(ScreenRecorderController.State(options = options, error = message))
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        return false
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "Screen recorder", NotificationManager.IMPORTANCE_LOW)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun startForegroundCompat(text: String) {
        createChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            var flags = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (options.micEnabled || options.systemAudioEnabled) {
                flags = flags or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(text), flags)
        } else {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(text), 0)
        }
    }

    private fun updateNotification(text: String = "Recording") {
        val manager = getSystemService(NotificationManager::class.java)
        runCatching { manager.notify(NOTIF_ID, buildNotification(text)) }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPending = PendingIntent.getService(
            this,
            1,
            Intent(this, ScreenRecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pausePending = PendingIntent.getService(
            this,
            2,
            Intent(this, ScreenRecorderService::class.java).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val resumePending = PendingIntent.getService(
            this,
            3,
            Intent(this, ScreenRecorderService::class.java).setAction(ACTION_RESUME),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Screen recording")
            .setContentText(text)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPending)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (isPaused) {
            builder.addAction(android.R.drawable.ic_media_play, "Resume", resumePending)
        } else {
            builder.addAction(android.R.drawable.ic_media_pause, "Pause", pausePending)
        }
        return builder.build()
    }

    @Suppress("DEPRECATION")
    private fun screenSize(maxDimension: Int): Pair<Int, Int> {
        val rawWidth: Int
        val rawHeight: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
            rawWidth = bounds.width()
            rawHeight = bounds.height()
        } else {
            val metrics = DisplayMetrics()
            getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(metrics)
            rawWidth = metrics.widthPixels
            rawHeight = metrics.heightPixels
        }
        val scale = minOf(1f, maxDimension.toFloat() / maxOf(rawWidth, rawHeight).toFloat())
        var width = (rawWidth * scale).toInt()
        var height = (rawHeight * scale).toInt()
        if (width % 2 != 0) width -= 1
        if (height % 2 != 0) height -= 1
        return width.coerceAtLeast(2) to height.coerceAtLeast(2)
    }

    private fun formatElapsed(ms: Long): String {
        val seconds = ms / 1000
        return String.format(Locale.getDefault(), "%02d:%02d", seconds / 60, seconds % 60)
    }
}
