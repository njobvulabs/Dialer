package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.example.MainActivity
import com.example.R
import com.example.util.AssistantPrefs
import com.example.util.AssistantRecorderController

class AssistantOverlayService : Service() {

    private var safetyReceiver: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AudioEngine.init(applicationContext)
        registerSafetyReceiver()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Floating assistant",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopAssistant()
                return START_NOT_STICKY
            }
            ACTION_COLLAPSE -> {
                AssistantRecorderController.stopIfRecording(this)
                AssistantOverlayController.collapse(this)
            }
            ACTION_RECORDING_STARTED, ACTION_RECORDING_STOPPED, ACTION_CAMERA_STARTED -> {
                startForegroundCompat(buildNotification())
                if (intent.action == ACTION_CAMERA_STARTED) _cameraForegroundReady.value = true
                return START_NOT_STICKY
            }
            else -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                running = true
                AssistantOverlayController.show(this, intent?.getBooleanExtra(EXTRA_EXPAND, false) == true)
            }
        }
        startForegroundCompat(buildNotification())
        return START_NOT_STICKY
    }

    private fun stopAssistant() {
        running = false
        _cameraForegroundReady.value = false
        AssistantRecorderController.stopIfRecording(this, notifyService = false)
        AssistantOverlayController.hide(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        runCatching {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(NOTIF_ID)
        }
        stopSelf()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            if (AssistantRecorderController.status.value.recording) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NOTIF_ID, notification, type)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getService(
            this,
            0,
            Intent(this, AssistantOverlayService::class.java)
                .setAction(ACTION_SHOW)
                .putExtra(EXTRA_EXPAND, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val collapse = PendingIntent.getService(
            this,
            1,
            Intent(this, AssistantOverlayService::class.java).setAction(ACTION_COLLAPSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val close = PendingIntent.getService(
            this,
            2,
            Intent(this, AssistantOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val app = PendingIntent.getActivity(
            this,
            3,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle("Floating assistant")
            .setContentText(
                if (AssistantRecorderController.status.value.recording) {
                    "Recording microphone — collapse to save"
                } else {
                    "Quick actions, notes and snippets from a bubble"
                }
            )
            .setOngoing(true)
            .setContentIntent(app)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Open", open)
            .addAction(0, "Collapse", collapse)
            .addAction(0, "Close", close)
            .build()
    }

    private fun registerSafetyReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        if (AssistantPrefs.collapseOnScreenOff(context)) {
                            AssistantRecorderController.stopIfRecording(context)
                            AssistantOverlayController.collapse(context)
                        }
                    }
                    TelephonyManager.ACTION_PHONE_STATE_CHANGED -> {
                        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                        if (AssistantPrefs.collapseOnCall(context) &&
                            (state == TelephonyManager.EXTRA_STATE_RINGING ||
                                state == TelephonyManager.EXTRA_STATE_OFFHOOK)
                        ) {
                            AssistantRecorderController.stopIfRecording(context)
                            AssistantOverlayController.collapse(context)
                        }
                    }
                }
            }
        }
        safetyReceiver = receiver
        runCatching {
            registerReceiver(
                receiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
                }
            )
        }
    }

    override fun onDestroy() {
        running = false
        _cameraForegroundReady.value = false
        AssistantRecorderController.stopIfRecording(this, notifyService = false)
        AssistantOverlayController.hide(this)
        safetyReceiver?.let { receiver -> runCatching { unregisterReceiver(receiver) } }
        safetyReceiver = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "floating_assistant"
        private const val NOTIF_ID = 4230
        private const val ACTION_SHOW = "com.example.action.SHOW_ASSISTANT"
        private const val ACTION_COLLAPSE = "com.example.action.COLLAPSE_ASSISTANT"
        private const val ACTION_STOP = "com.example.action.STOP_ASSISTANT"
        private const val ACTION_RECORDING_STARTED = "com.example.action.ASSISTANT_RECORDING_STARTED"
        private const val ACTION_RECORDING_STOPPED = "com.example.action.ASSISTANT_RECORDING_STOPPED"
        private const val ACTION_CAMERA_STARTED = "com.example.action.ASSISTANT_CAMERA_STARTED"
        private const val EXTRA_EXPAND = "assistant_expand"

        @Volatile
        private var running = false

        private val _cameraForegroundReady = MutableStateFlow(false)
        val cameraForegroundReady: StateFlow<Boolean> = _cameraForegroundReady.asStateFlow()

        fun isRunning(): Boolean = running

        fun startIntent(context: Context): Intent =
            Intent(context, AssistantOverlayService::class.java).setAction(ACTION_SHOW)

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, startIntent(context)) }
        }

        fun recordingIntent(context: Context, active: Boolean): Intent =
            Intent(context, AssistantOverlayService::class.java)
                .setAction(if (active) ACTION_RECORDING_STARTED else ACTION_RECORDING_STOPPED)

        fun cameraIntent(context: Context): Intent =
            Intent(context, AssistantOverlayService::class.java).setAction(ACTION_CAMERA_STARTED)

        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, AssistantOverlayService::class.java).setAction(ACTION_STOP)
                )
            }
        }

        fun collapse(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, AssistantOverlayService::class.java).setAction(ACTION_COLLAPSE)
                )
            }
        }
    }
}
