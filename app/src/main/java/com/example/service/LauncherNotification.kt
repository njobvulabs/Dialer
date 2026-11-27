package com.example.service

import android.app.PendingIntent

data class LauncherRemoteInput(
    val resultKey: String,
    val label: String,
    val allowFreeFormInput: Boolean,
    val choices: List<String>
)

data class LauncherNotificationAction(
    val title: String,
    val pendingIntent: PendingIntent?,
    val remoteInputs: List<LauncherRemoteInput> = emptyList(),
    val isAuthenticationRequired: Boolean = false
)

data class LauncherNotification(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val postTime: Long,
    val isClearable: Boolean,
    val contentIntent: PendingIntent? = null,
    val fullTitle: String = "",
    val fullText: String = "",
    val textLines: List<String> = emptyList(),
    val subText: String = "",
    val summaryText: String = "",
    val infoText: String = "",
    val conversationTitle: String = "",
    val actions: List<LauncherNotificationAction> = emptyList(),
    val groupKey: String? = null,
    val isGroup: Boolean = false,
    val isGroupSummary: Boolean = false,
    val isOngoing: Boolean = false,
    val channelId: String = "",
    val category: String = "",
    val deleteIntent: PendingIntent? = null
)
