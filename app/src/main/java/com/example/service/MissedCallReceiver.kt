package com.example.service

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.ui.components.AppIcons
import com.example.util.SystemCallHelper

/** Posts missed-call notifications with Call back / Message / Dismiss actions
 *  and optional repeating reminders until dismissed. */
class MissedCallReceiver : BroadcastReceiver() {

    companion object {
        const val CHANNEL_ID = "missed_calls"
        const val ACTION_CALLBACK = "com.example.MISSED_CALL_BACK"
        const val ACTION_SMS = "com.example.MISSED_CALL_SMS"
        const val ACTION_DISMISS = "com.example.MISSED_CALL_DISMISS"
        const val ACTION_REMIND = "com.example.MISSED_CALL_REMIND"

        const val KEY_NUMBER = "number"
        const val KEY_NAME = "name"

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                nm.getNotificationChannel(CHANNEL_ID) == null
            ) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Missed Calls", NotificationManager.IMPORTANCE_HIGH)
                        .apply { description = "Missed call alerts and reminders" }
                )
            }
        }

        private fun pending(context: Context): android.content.SharedPreferences =
            context.getSharedPreferences("missed_call", Context.MODE_PRIVATE)

        fun post(context: Context, number: String, name: String) {
            ensureChannel(context)
            pending(context).edit()
                .putString(KEY_NUMBER, number)
                .putString(KEY_NAME, name)
                .putLong("ts", System.currentTimeMillis())
                .apply()

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val open = PendingIntent.getActivity(
                context, 1,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val callBack = PendingIntent.getBroadcast(
                context, 2,
                Intent(context, MissedCallReceiver::class.java).setAction(ACTION_CALLBACK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val sms = PendingIntent.getBroadcast(
                context, 3,
                Intent(context, MissedCallReceiver::class.java).setAction(ACTION_SMS),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val dismiss = PendingIntent.getBroadcast(
                context, 4,
                Intent(context, MissedCallReceiver::class.java).setAction(ACTION_DISMISS),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val title = name.ifBlank { "Missed call" }
            val text = if (name.isNotBlank()) number else "From $number"

            val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(open)
                .addAction(0, "Call back", callBack)
                .addAction(0, "Message", sms)
                .addAction(0, "Dismiss", dismiss)
                .build()
            nm.notify(1001, notif)

            scheduleReminder(context)
        }

        fun scheduleReminder(context: Context) {
            val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            val enabled = prefs.getBoolean("missed_reminder_enabled", false)
            val intervalMin = prefs.getString("missed_reminder_interval", "10")?.toIntOrNull() ?: 10
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                context, 5,
                Intent(context, MissedCallReceiver::class.java).setAction(ACTION_REMIND),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.cancel(pi)
            if (!enabled || intervalMin <= 0) return
            val at = System.currentTimeMillis() + intervalMin * 60_000L
            runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
        }

        fun clearAll(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(1001)
            pending(context).edit().remove(KEY_NUMBER).remove(KEY_NAME).remove("ts").apply()
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                context, 5,
                Intent(context, MissedCallReceiver::class.java).setAction(ACTION_REMIND),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.cancel(pi)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_DISMISS -> clearAll(context)
            ACTION_CALLBACK -> {
                val number = pending(context).getString(KEY_NUMBER, null) ?: return
                runCatching { SystemCallHelper.makeCall(context, number) }
                clearAll(context)
            }
            ACTION_SMS -> {
                val number = pending(context).getString(KEY_NUMBER, null) ?: return
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:$number"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
            ACTION_REMIND -> {
                val p = pending(context)
                val number = p.getString(KEY_NUMBER, null)
                if (number != null) {
                    post(context, number, p.getString(KEY_NAME, "") ?: "")
                }
            }
        }
    }
}
