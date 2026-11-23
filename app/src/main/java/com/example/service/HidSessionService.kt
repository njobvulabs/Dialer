package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.util.BtHidController
import com.example.util.HidState
import com.example.util.HidStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class HidSessionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null
    private var sawRegistered = false
    private var overlayShowing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Bluetooth input",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopHidSession()
            return START_NOT_STICKY
        }
        if (action == ACTION_SHOW_HID_OVERLAY) {
            overlayShowing = true
            runCatching { HidOverlayController.show(this) }
            BtHidController.start(this)
        }
        if (action == ACTION_HIDE_HID_OVERLAY) {
            overlayShowing = false
            runCatching { HidOverlayController.hide(this) }
        }
        if (action == ACTION_OPEN_HID_LOCK_PAD) {
            runCatching { com.example.HidLockPadActivity.start(this) }
        }

        val initial = BtHidController.state.value
        startForegroundCompat(buildNotification(initial))
        BtHidController.start(this)
        stateJob?.cancel()
        stateJob = scope.launch {
            BtHidController.state.collectLatest { state ->
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIF_ID, buildNotification(state))
                if (state.registered) {
                    sawRegistered = true
                } else if (sawRegistered || state.status == HidStatus.ERROR || state.status == HidStatus.UNSUPPORTED) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun stopHidSession() {
        overlayShowing = false
        runCatching { HidOverlayController.hide(this) }
        BtHidController.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(state: HidState): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, HidSessionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val lockPadIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, HidSessionService::class.java).setAction(ACTION_OPEN_HID_LOCK_PAD),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openApp = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = when {
            state.connected -> "Connected to ${state.hostName ?: "computer"}"
            state.registered -> "Waiting for a computer"
            else -> "Bluetooth input"
        }
        val text = if (state.connected) {
            "NJ-Dialer is your mouse and keyboard"
        } else {
            "Open NJ-Dialer to pair or connect a computer"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Disconnect", stopIntent)
            .addAction(0, "Lock pad", lockPadIntent)
            .build()
    }

    override fun onDestroy() {
        overlayShowing = false
        runCatching { HidOverlayController.hide(this) }
        scope.cancel()
        runCatching {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(NOTIF_ID)
        }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "bt_hid_session"
        private const val NOTIF_ID = 4210
        private const val ACTION_STOP = "com.example.action.STOP_HID_SESSION"
        private const val ACTION_SHOW_HID_OVERLAY = "com.example.action.SHOW_HID_OVERLAY"
        private const val ACTION_HIDE_HID_OVERLAY = "com.example.action.HIDE_HID_OVERLAY"
        private const val ACTION_OPEN_HID_LOCK_PAD = "com.example.action.OPEN_HID_LOCK_PAD"

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, HidSessionService::class.java),
                )
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, HidSessionService::class.java).setAction(ACTION_STOP)
                )
            }
        }
    }
}
