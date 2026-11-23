package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.example.R
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Detects incoming SMS by reading notifications posted by the device's SMS app
 * (works without being the default SMS handler), surfaces them as a local
 * foreground-visible notification, and broadcasts an event so the SMS screen can
 * refresh. Requires the user to grant "Notification Access" in Settings.
 *
 * The listener also exposes active notification snapshots/counts for the
 * launcher's in-app shade. Only in-memory metadata is kept — never persisted.
 */
class SmsNotificationListener : NotificationListenerService() {

    companion object {
        const val ACTION_SMS_RECEIVED = "com.example.SMS_RECEIVED"
        const val ACTION_NOTIFICATIONS_CHANGED = "com.example.NOTIFICATIONS_CHANGED"
        const val CHANNEL_ID = "sms_incoming"

        // Covers AOSP Messages, Google Messages, Samsung Messages and most OEM SMS apps
        private val SMS_PACKAGES = setOf(
            "com.android.mms",
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
            "com.hihonor.mms",
            "com.huawei.mms",
            "com.android.messaging"
        )

        // package -> notification keys (badge counts; excludes ongoing/summaries/self).
        private val activeKeys = ConcurrentHashMap<String, MutableSet<String>>()
        // key -> snapshot for the in-app shade (excludes summaries/self).
        private val activeSnapshots = ConcurrentHashMap<String, LauncherNotification>()

        @Volatile
        private var instance: SmsNotificationListener? = null

        fun snapshotCounts(): Map<String, Int> =
            activeKeys.entries
                .filter { it.value.isNotEmpty() }
                .associate { it.key to it.value.size }

        fun snapshotNotifications(): List<LauncherNotification> =
            activeSnapshots.values.sortedByDescending { it.postTime }

        /** Best-effort dismiss of a clearable notification from the in-app shade. */
        fun dismiss(key: String) {
            runCatching { instance?.cancelNotification(key) }
        }

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                nm.getNotificationChannel(CHANNEL_ID) == null
            ) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Incoming SMS",
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply { description = "Incoming SMS alert from NJ-Dialer" }
                )
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        activeKeys.clear()
        activeSnapshots.clear()
        runCatching {
            activeNotifications?.forEach { track(it, broadcast = false) }
        }
        broadcastCounts()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        activeKeys.clear()
        activeSnapshots.clear()
        if (instance === this) instance = null
        broadcastCounts()
    }

    override fun onDestroy() {
        activeKeys.clear()
        activeSnapshots.clear()
        if (instance === this) instance = null
        broadcastCounts()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        track(sbn, broadcast = true)

        val pkg = sbn.packageName ?: return
        val isSms = SMS_PACKAGES.any { it.equals(pkg, ignoreCase = true) }
        if (!isSms) return

        val extras: Bundle? = sbn.notification?.extras
        val title = extras?.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString()
            ?: extras?.getString("android.title") ?: return
        val text = extras?.getCharSequence(NotificationCompat.EXTRA_TEXT)?.toString()
            ?: extras?.getCharSequence(NotificationCompat.EXTRA_BIG_TEXT)?.toString()
            ?: extras?.getString("android.text") ?: return

        // Only surface notifications that look like an actual SMS (sender + body)
        if (title.isBlank() || text.isBlank()) return

        ensureChannel(this)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(2002, notif) }

        // Refresh the SMS screen / conversation list if it is open
        val broadcast = Intent(ACTION_SMS_RECEIVED).setPackage(packageName)
        sendBroadcast(broadcast)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        untrack(sbn)
    }

    private fun track(sbn: StatusBarNotification, broadcast: Boolean) {
        val pkg = sbn.packageName ?: return
        val key = sbn.key ?: return
        if (pkg == packageName) return
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        var changed = false
        if (!sbn.isOngoing) {
            val set = activeKeys.computeIfAbsent(pkg) {
                Collections.synchronizedSet(mutableSetOf())
            }
            changed = synchronized(set) { set.add(key) } || changed
        }

        val snapshot = buildSnapshot(sbn)
        val prev = activeSnapshots.put(key, snapshot)
        if (prev != snapshot) changed = true

        if (changed && broadcast) broadcastCounts()
    }

    private fun untrack(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        val key = sbn.key ?: return
        var changed = activeSnapshots.remove(key) != null
        val set = activeKeys[pkg]
        if (set != null) {
            val removed = synchronized(set) { set.remove(key) }
            changed = changed || removed
            if (synchronized(set) { set.isEmpty() }) activeKeys.remove(pkg, set)
        }
        if (changed) broadcastCounts()
    }

    private fun buildSnapshot(sbn: StatusBarNotification): LauncherNotification {
        val pkg = sbn.packageName.orEmpty()
        val extras = sbn.notification?.extras
        val title = extras?.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString()
            ?: extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: extras?.getString("android.title").orEmpty()
        val text = extras?.getCharSequence(NotificationCompat.EXTRA_TEXT)?.toString()
            ?: extras?.getCharSequence(NotificationCompat.EXTRA_BIG_TEXT)?.toString()
            ?: extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras?.getString("android.text").orEmpty()
        val sub = extras?.getCharSequence(NotificationCompat.EXTRA_SUB_TEXT)?.toString().orEmpty()
        val displayText = when {
            text.isNotBlank() -> text
            sub.isNotBlank() -> sub
            else -> ""
        }
        val label = runCatching {
            val pm = packageManager
            val ai = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(ai)?.toString().orEmpty().ifBlank { pkg }
        }.getOrDefault(pkg)
        val clearable = sbn.isClearable && !sbn.isOngoing
        return LauncherNotification(
            key = sbn.key.orEmpty(),
            packageName = pkg,
            appLabel = label,
            title = title,
            text = displayText,
            postTime = sbn.postTime,
            isClearable = clearable,
            contentIntent = sbn.notification?.contentIntent
        )
    }

    private fun broadcastCounts() {
        runCatching {
            sendBroadcast(
                Intent(ACTION_NOTIFICATIONS_CHANGED).setPackage(packageName)
            )
        }
    }
}
