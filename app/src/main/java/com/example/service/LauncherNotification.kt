package com.example.service

import android.app.PendingIntent

/**
 * Snapshot of an active status-bar notification exposed to the in-app launcher
 * shade. Kept in memory only (never persisted).
 */
data class LauncherNotification(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val postTime: Long,
    val isClearable: Boolean,
    val contentIntent: PendingIntent? = null
)
