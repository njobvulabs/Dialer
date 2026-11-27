package com.example.service

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

        private val activeKeys = ConcurrentHashMap<String, MutableSet<String>>()
        private val activeSnapshots = ConcurrentHashMap<String, LauncherNotification>()
        private val activeSbns = ConcurrentHashMap<String, StatusBarNotification>()

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

        fun clearAll() {
            val listener = instance ?: return
            val clearable = activeSnapshots.values.filter { it.isClearable }
            if (clearable.isEmpty()) return
            clearable.forEach { snapshot ->
                val canceled = runCatching { listener.cancelNotification(snapshot.key) }.isSuccess
                if (!canceled) return@forEach
                activeSnapshots.remove(snapshot.key)
                activeSbns.remove(snapshot.key)
                val set = activeKeys[snapshot.packageName]
                if (set != null) {
                    synchronized(set) { set.remove(snapshot.key) }
                    if (synchronized(set) { set.isEmpty() }) activeKeys.remove(snapshot.packageName, set)
                }
            }
            listener.broadcastCounts()
        }

        fun performAction(key: String, index: Int, input: String?): Boolean {
            val notif = activeSbns[key]?.notification ?: return false
            val action = notif.actions?.getOrNull(index) ?: return false
            val pendingIntent = action.actionIntent ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && action.isAuthenticationRequired) return false
            val context = instance?.applicationContext ?: return false
            val fillIn = Intent()
            val remoteInputs = action.remoteInputs
            if (!remoteInputs.isNullOrEmpty()) {
                val results = Bundle()
                for (remoteInput in remoteInputs) {
                    val resultKey = remoteInput.resultKey ?: continue
                    val text = input ?: ""
                    if (!remoteInput.allowFreeFormInput && !remoteInput.choices.isNullOrEmpty()) {
                        val match = remoteInput.choices.firstOrNull { it?.toString() == text } ?: continue
                        results.putCharSequence(resultKey, match)
                    } else if (remoteInput.allowFreeFormInput) {
                        results.putCharSequence(resultKey, text)
                    }
                }
                RemoteInput.addResultsToIntent(remoteInputs, fillIn, results)
            }
            val options = ActivityOptions.makeBasic()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                options.setPendingIntentBackgroundActivityLaunchAllowed(true)
            }
            return runCatching {
                pendingIntent.send(
                    context,
                    0,
                    fillIn,
                    null,
                    Handler(Looper.getMainLooper()),
                    null,
                    options.toBundle()
                )
                true
            }.getOrDefault(false)
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
        activeSbns.clear()
        runCatching {
            activeNotifications?.forEach { track(it, broadcast = false) }
        }
        broadcastCounts()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        activeKeys.clear()
        activeSnapshots.clear()
        activeSbns.clear()
        if (instance === this) instance = null
        broadcastCounts()
    }

    override fun onDestroy() {
        activeKeys.clear()
        activeSnapshots.clear()
        activeSbns.clear()
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
        val isGroupSummary = sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0

        var changed = false
        if (!sbn.isOngoing && !isGroupSummary) {
            val set = activeKeys.computeIfAbsent(pkg) {
                Collections.synchronizedSet(mutableSetOf())
            }
            changed = synchronized(set) { set.add(key) } || changed
        }

        val snapshot = buildSnapshot(sbn)
        val prev = activeSnapshots.put(key, snapshot)
        val prevSbn = activeSbns.put(key, sbn)
        if (prev != snapshot || prevSbn !== sbn) changed = true

        if (changed && broadcast) broadcastCounts()
    }

    private fun untrack(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        val key = sbn.key ?: return
        var changed = activeSnapshots.remove(key) != null
        changed = activeSbns.remove(key) != null || changed
        val set = activeKeys[pkg]
        if (set != null) {
            val removed = synchronized(set) { set.remove(key) }
            changed = changed || removed
            if (synchronized(set) { set.isEmpty() }) activeKeys.remove(pkg, set)
        }
        if (changed) broadcastCounts()
    }

    private fun buildSnapshot(sbn: StatusBarNotification): LauncherNotification {
        val notif = sbn.notification
        val groupKey = sbn.groupKey
        val pkg = sbn.packageName.orEmpty()
        val extras = notif?.extras
        val title = extras?.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString()
            ?: extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: extras?.getString("android.title").orEmpty()
        val text = extras?.getCharSequence(NotificationCompat.EXTRA_BIG_TEXT)?.toString()
            ?: extras?.getCharSequence(NotificationCompat.EXTRA_TEXT)?.toString()
            ?: extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras?.getString("android.text").orEmpty()
        val lines = extras?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.map { it?.toString().orEmpty() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        val sub = extras?.getCharSequence(NotificationCompat.EXTRA_SUB_TEXT)?.toString().orEmpty()
        val summary = extras?.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString().orEmpty()
        val info = extras?.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString().orEmpty()
        val conversation = extras?.getCharSequence(NotificationCompat.EXTRA_CONVERSATION_TITLE)?.toString().orEmpty()
        val fullText = when {
            text.isNotBlank() -> text
            lines.isNotEmpty() -> lines.joinToString("\n")
            sub.isNotBlank() -> sub
            else -> ""
        }
        val displayText = when {
            text.isNotBlank() -> text
            lines.isNotEmpty() -> lines.first()
            sub.isNotBlank() -> sub
            else -> ""
        }
        val label = runCatching {
            val pm = packageManager
            val ai = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(ai)?.toString().orEmpty().ifBlank { pkg }
        }.getOrDefault(pkg)
        val actions = (notif?.actions ?: emptyArray()).map { action ->
            LauncherNotificationAction(
                title = action.title?.toString().orEmpty(),
                pendingIntent = action.actionIntent,
                remoteInputs = (action.remoteInputs ?: emptyArray()).map { input ->
                    LauncherRemoteInput(
                        resultKey = input.resultKey.orEmpty(),
                        label = input.label?.toString().orEmpty(),
                        allowFreeFormInput = input.allowFreeFormInput,
                        choices = (input.choices ?: emptyArray()).map { choice -> choice?.toString().orEmpty() }
                    )
                },
                isAuthenticationRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                    action.isAuthenticationRequired
            )
        }
        return LauncherNotification(
            key = sbn.key.orEmpty(),
            packageName = pkg,
            appLabel = label,
            title = title,
            text = displayText,
            postTime = sbn.postTime,
            isClearable = sbn.isClearable && !sbn.isOngoing,
            contentIntent = notif?.contentIntent,
            fullTitle = title,
            fullText = fullText,
            textLines = lines,
            subText = sub,
            summaryText = summary,
            infoText = info,
            conversationTitle = conversation,
            actions = actions,
            groupKey = groupKey?.takeIf { it.isNotBlank() },
            isGroup = !groupKey.isNullOrBlank(),
            isGroupSummary = notif?.flags?.and(Notification.FLAG_GROUP_SUMMARY) != 0,
            isOngoing = sbn.isOngoing,
            channelId = notif?.channelId.orEmpty(),
            category = notif?.category.orEmpty(),
            deleteIntent = notif?.deleteIntent
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
