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

/**
 * Foreground service that keeps a web app's OS process alive when the user
 * chooses "Exit & keep running". Only the LAST used web app should stay alive:
 * a subclass is declared per web-app process (android:process), and each one
 * posts a low-priority notification with a "Close" action.
 *
 * Before starting, the web app broadcasts ACTION_RELEASE_KEEP_ALIVE so any
 * previously kept-alive web app (its process is still alive, so it receives
 * the broadcast) stops its own service and is released.
 */
abstract class WebAppKeepAliveService : Service() {

    private val releaseReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            stopSelf()
        }
    }

    private val prefs: SharedPreferences
        get() = getSharedPreferences("keep_alive", Context.MODE_PRIVATE)

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
                "Running web apps",
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
        val label = intent?.getStringExtra(EXTRA_LABEL) ?: "Web app"
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
        // Android 15 dataSync foreground services are stopped after their
        // background timeout; stop gracefully instead of crashing.
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
        private const val CHANNEL_ID = "webapp_keepalive"
        private const val NOTIF_ID = 4201
        private const val EXTRA_LABEL = "label"
        private const val EXTRA_PROCESS = "process"
        private const val KEY_CURRENT = "current_process"

        const val ACTION_RELEASE_KEEP_ALIVE = "com.example.action.RELEASE_KEEP_ALIVE"
        const val ACTION_STOP = "com.example.action.STOP_KEEP_ALIVE"

        /** Send this before keeping a new web app alive, to release any prior one. */
        fun releaseOthers(context: Context) {
            runCatching {
                context.sendBroadcast(
                    Intent(ACTION_RELEASE_KEEP_ALIVE)
                        .setPackage(context.packageName)
                )
            }
        }

        /** Stop this process's keep-alive when its activity returns to foreground. */
        fun releaseOwn(context: Context) {
            val process = currentProcessName(context)
            runCatching {
                if (isCurrentTarget(context, process)) {
                    context.stopService(startIntent(context, ""))
                }
            }
        }

        /** Build the intent to keep THIS web app's process alive. */
        fun startIntent(context: Context, label: String): Intent {
            val process = currentProcessName(context)
            return Intent(context, serviceClassForProcess(process)).apply {
                putExtra(EXTRA_LABEL, label)
                putExtra(EXTRA_PROCESS, process)
            }
        }

        fun isCurrentTarget(context: Context, process: String): Boolean {
            val p = context.getSharedPreferences("keep_alive", Context.MODE_PRIVATE)
                .getString(KEY_CURRENT, null)
            return p == process
        }

        /**
         * The RUNTIME process name of the calling process, e.g. "com.example:whatsapp".
         *
         * `context.applicationInfo.processName` is a static manifest field and is
         * therefore always the main process ("com.example"), even when called from
         * inside :whatsapp / :whatsapp2 / :webapps. That made every caller fall
         * through to [serviceClassForProcess]'s `else` branch and start
         * KeepAliveMainService in the MAIN process, so the isolated web-app
         * processes were never actually pinned, and the stored KEY_CURRENT /
         * EXTRA_PROCESS value was the same string for every web app (so
         * releaseOwn/releaseOthers could not tell them apart).
         *
         * Application.getProcessName() (API 28+) returns the real one; minSdk is 26,
         * so 26/27 fall back to the old (main-process) value.
         */
        private fun currentProcessName(context: Context): String =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching { Application.getProcessName() }.getOrNull()
                    ?: context.applicationInfo.processName
            } else {
                context.applicationInfo.processName
            }

        /** Matches the SUFFIX of a full process name ("com.example:whatsapp"). */
        private fun serviceClassForProcess(process: String): Class<out Service> = when {
            process.endsWith(":whatsapp2") -> KeepAliveWhatsapp2Service::class.java
            process.endsWith(":whatsapp") -> KeepAliveWhatsapp1Service::class.java
            process.endsWith(":webapps") -> KeepAliveWebappsService::class.java
            process.endsWith(":webapp4") -> KeepAliveWebapp4Service::class.java
            process.endsWith(":webapp3") -> KeepAliveWebapp3Service::class.java
            process.endsWith(":webapp2") -> KeepAliveWebapp2Service::class.java
            process.endsWith(":webapp1") -> KeepAliveWebapp1Service::class.java
            else -> KeepAliveMainService::class.java
        }
    }
}

/** Keeps the :webapps process alive (the 14 GeckoView web apps' runtime + profile). */
class KeepAliveWebappsService : WebAppKeepAliveService()

/** Keeps the :whatsapp process (WhatsApp 1) alive. */
class KeepAliveWhatsapp1Service : WebAppKeepAliveService()

/** Keeps the :whatsapp2 process (WhatsApp 2) alive. */
class KeepAliveWhatsapp2Service : WebAppKeepAliveService()

/** Keeps the default/main process alive (legacy IsolatedWebActivity). */
class KeepAliveMainService : WebAppKeepAliveService()

/** Keeps the :webapp1 process (custom web app slot 1) alive. */
class KeepAliveWebapp1Service : WebAppKeepAliveService()

/** Keeps the :webapp2 process (custom web app slot 2) alive. */
class KeepAliveWebapp2Service : WebAppKeepAliveService()

/** Keeps the :webapp3 process (custom web app slot 3) alive. */
class KeepAliveWebapp3Service : WebAppKeepAliveService()

/** Keeps the :webapp4 process (custom web app slot 4) alive. */
class KeepAliveWebapp4Service : WebAppKeepAliveService()
