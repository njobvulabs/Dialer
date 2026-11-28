package com.example.service

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.util.KeepAliveTimeout

abstract class TerminalKeepAliveService : Service() {

    private val releaseReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            stopSelf()
        }
    }

    private val prefs: SharedPreferences
        get() = getSharedPreferences("terminal_keep_alive", Context.MODE_PRIVATE)

    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val timeoutRunnable = Runnable {
        prefs.edit().remove(KEY_CURRENT).apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Linux Terminal",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
        )
        registerReceiver(
            releaseReceiver,
            IntentFilter(ACTION_RELEASE_KEEP_ALIVE),
            Context.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                timeoutHandler.removeCallbacks(timeoutRunnable)
                prefs.edit().remove(KEY_CURRENT).apply()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        val label = intent?.getStringExtra(EXTRA_LABEL) ?: "Linux Terminal"
        val process = intent?.getStringExtra(EXTRA_PROCESS)
            ?: applicationInfo.processName
        prefs.edit().putString(KEY_CURRENT, process).apply()
        startForeground(NOTIF_ID, buildNotification(label))
        timeoutHandler.removeCallbacks(timeoutRunnable)
        val timeoutMs = KeepAliveTimeout.millis(this)
        if (timeoutMs > 0L) timeoutHandler.postDelayed(timeoutRunnable, timeoutMs)
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        prefs.edit().remove(KEY_CURRENT).apply()
        stopSelf()
    }

    private fun buildNotification(label: String): Notification {
        val stopPi = PendingIntent.getForegroundService(
            this,
            0,
            Intent(this, this::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openApp = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle("$label is still running")
            .setContentText("Tap to open, Close to stop it fully")
            .setOngoing(true)
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Close", stopPi)
            .build()
    }

    override fun onDestroy() {
        timeoutHandler.removeCallbacks(timeoutRunnable)
        runCatching { unregisterReceiver(releaseReceiver) }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIF_ID)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "terminal_keepalive"
        private const val NOTIF_ID = 4202
        private const val EXTRA_LABEL = "label"
        private const val EXTRA_PROCESS = "process"
        private const val KEY_CURRENT = "current_process"

        const val ACTION_RELEASE_KEEP_ALIVE = "com.example.action.TERMINAL_RELEASE_KEEP_ALIVE"
        const val ACTION_STOP = "com.example.action.TERMINAL_STOP_KEEP_ALIVE"

        fun releaseOthers(context: Context) {
            runCatching {
                context.sendBroadcast(
                    Intent(ACTION_RELEASE_KEEP_ALIVE)
                        .setPackage(context.packageName)
                )
            }
        }

        fun releaseOwn(context: Context) {
            val process = currentProcessName(context)
            runCatching {
                if (isCurrentTarget(context, process)) {
                    context.stopService(startIntent(context, ""))
                }
            }
        }

        fun startIntent(context: Context, label: String): Intent {
            val process = currentProcessName(context)
            return Intent(context, TerminalKeepAliveService::class.java).apply {
                putExtra(EXTRA_LABEL, label)
                putExtra(EXTRA_PROCESS, process)
            }
        }

        fun isCurrentTarget(context: Context, process: String): Boolean {
            val p = context.getSharedPreferences("terminal_keep_alive", Context.MODE_PRIVATE)
                .getString(KEY_CURRENT, null)
            return p == process
        }

        private fun currentProcessName(context: Context): String =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching { Application.getProcessName() }.getOrNull()
                    ?: context.applicationInfo.processName
            } else {
                context.applicationInfo.processName
            }
    }
}

class TerminalKeepAliveServiceImpl : TerminalKeepAliveService()