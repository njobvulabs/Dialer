package com.example.ui.screens.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.service.AudioEngine
import com.example.service.LauncherNotification
import com.example.ui.components.AppIcons
import com.example.ui.theme.AppCornerRadius

@Composable
fun LauncherNotificationsPanel(
    notifications: List<LauncherNotification>,
    hasAccess: Boolean,
    onOpenNotification: (LauncherNotification) -> Unit,
    onDismissNotification: (String) -> Unit,
    onClearAllNotifications: () -> Unit = {},
    onPerformAction: (String, Int, String?) -> Unit,
    onReplyModeChange: (Boolean) -> Unit = {},
    onOpenPlayer: () -> Unit = {},
    modifier: Modifier = Modifier,
    fillRemaining: Boolean = false
) {
    val context = LocalContext.current
    val expandedKeys = remember { mutableStateSetOf<String>() }
    var menuKey by remember { mutableStateOf<String?>(null) }
    var inlineReply by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var replyText by remember { mutableStateOf("") }

    BackHandler(enabled = inlineReply != null) {
        inlineReply = null
        replyText = ""
    }

    SideEffect {
        onReplyModeChange(inlineReply != null)
    }

    LaunchedEffect(notifications) {
        val keys = notifications.map { it.key }.toSet()
        expandedKeys.removeAll { it !in keys }
        val reply = inlineReply
        if (reply != null && reply.first !in keys) inlineReply = null
        if (menuKey != null && menuKey !in keys) menuKey = null
    }

    fun copyText(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("Notification", text))
    }

    fun clearReply() {
        inlineReply = null
        replyText = ""
    }

    GlassPanel(
        modifier = modifier.fillMaxWidth(),
        shape = AppCornerRadius.xl,
        elevation = 6.dp,
        contentPadding = PaddingValues(14.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            val nowPlayingActive = AudioEngine.active.value
            if (nowPlayingActive) {
                NowPlayingCard(
                    modifier = Modifier.padding(bottom = 8.dp),
                    onOpen = onOpenPlayer
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Notifications",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (!hasAccess) {
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }
                    }) {
                        Text("Grant access")
                    }
                } else if (notifications.any { it.isClearable }) {
                    TextButton(onClick = onClearAllNotifications) {
                        Text("Clear all")
                    }
                }
            }

            if (!hasAccess) {
                Text(
                    "Allow notification access to show app notifications here. This in-app panel is an approximation of the system shade.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (notifications.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "No notifications",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    modifier = if (fillRemaining) {
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .height(280.dp)
                    },
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(notifications, key = { it.key }) { notif ->
                        val reply = inlineReply
                        val replyIndex = if (reply?.first == notif.key) reply.second else null
                        NotificationRow(
                            notification = notif,
                            expanded = notif.key in expandedKeys,
                            onToggleExpanded = {
                                if (notif.key in expandedKeys) expandedKeys.remove(notif.key)
                                else expandedKeys.add(notif.key)
                            },
                            onOpen = { onOpenNotification(notif) },
                            onDismiss = { onDismissNotification(notif.key) },
                            onCopy = { copyText(it) },
                            onAction = { index, input -> onPerformAction(notif.key, index, input) },
                            onStartReply = { index ->
                                inlineReply = notif.key to index
                                replyText = ""
                            },
                            replyIndex = replyIndex,
                            replyText = replyText,
                            onReplyText = { replyText = it },
                            onSendReply = { index ->
                                onPerformAction(notif.key, index, replyText)
                                clearReply()
                            },
                            onCancelReply = { clearReply() },
                            menuExpanded = menuKey == notif.key,
                            onToggleMenu = { menuKey = if (menuKey == notif.key) null else notif.key },
                            onDismissMenu = { menuKey = null }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationRow(
    notification: LauncherNotification,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    onCopy: (String) -> Unit,
    onAction: (Int, String?) -> Unit,
    onStartReply: (Int) -> Unit,
    replyIndex: Int?,
    replyText: String,
    onReplyText: (String) -> Unit,
    onSendReply: (Int) -> Unit,
    onCancelReply: () -> Unit,
    menuExpanded: Boolean,
    onToggleMenu: () -> Unit,
    onDismissMenu: () -> Unit
) {
    val relative = DateUtils.getRelativeTimeSpanString(
        notification.postTime,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE
    ).toString()
    val meta = listOfNotNull(
        notification.conversationTitle.takeIf { it.isNotBlank() },
        notification.subText.takeIf { it.isNotBlank() },
        notification.summaryText.takeIf { it.isNotBlank() },
        notification.infoText.takeIf { it.isNotBlank() },
        notification.category.takeIf { it.isNotBlank() }
    ).joinToString(" · ")

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        shape = AppCornerRadius.md,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                IconButton(onClick = onToggleExpanded) {
                    Icon(
                        if (expanded) AppIcons.ExpandLess else AppIcons.ExpandMore,
                        contentDescription = if (expanded) "Collapse notification" else "Expand notification"
                    )
                }
                Spacer(Modifier.width(4.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            notification.appLabel.ifBlank { notification.packageName },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(
                            relative,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (notification.title.isNotBlank()) {
                        Text(
                            notification.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = if (expanded) Int.MAX_VALUE else 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { onToggleExpanded() }
                        )
                    }
                    if (notification.text.isNotBlank()) {
                        Text(
                            notification.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (expanded) Int.MAX_VALUE else 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { onToggleExpanded() }
                        )
                    }
                    if (expanded && notification.fullText.isNotBlank() && notification.fullText != notification.text) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            notification.fullText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    if (expanded && meta.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            meta,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (expanded && notification.isOngoing) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Ongoing notification",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(onClick = onOpen) {
                    Icon(AppIcons.OpenInNew, contentDescription = "Open notification")
                }
                BoxWithMenu(
                    expanded = menuExpanded,
                    onToggle = onToggleMenu,
                    onDismiss = onDismissMenu,
                    notification = notification,
                    onOpen = onOpen,
                    onCopy = onCopy,
                    onDismissNotification = onDismiss
                )
                if (notification.isClearable) {
                    IconButton(onClick = onDismiss) {
                        Icon(AppIcons.Close, contentDescription = "Dismiss notification")
                    }
                }
            }

            if (expanded && notification.actions.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    notification.actions.forEachIndexed { index, action ->
                        if (action.isAuthenticationRequired) {
                            Text(
                                "${action.title.ifBlank { "Action" }} needs authentication",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        } else if (action.remoteInputs.any { it.allowFreeFormInput || it.choices.isNotEmpty() }) {
                            TextButton(onClick = { onStartReply(index) }) {
                                Text(action.title.ifBlank { "Reply" })
                            }
                        } else {
                            TextButton(onClick = { onAction(index, null) }) {
                                Text(action.title.ifBlank { "Action" })
                            }
                        }
                    }
                }
            }

            val activeReply = replyIndex?.let { notification.actions.getOrNull(it) }
            if (activeReply != null) {
                val input = activeReply.remoteInputs.firstOrNull()
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = replyText,
                    onValueChange = onReplyText,
                    label = { Text(input?.label?.takeIf { it.isNotBlank() } ?: "Reply") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .imePadding(),
                    singleLine = true
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(onClick = onCancelReply) { Text("Cancel") }
                    Button(onClick = { onSendReply(replyIndex) }) {
                        Icon(AppIcons.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Send")
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxWithMenu(
    expanded: Boolean,
    onToggle: () -> Unit,
    onDismiss: () -> Unit,
    notification: LauncherNotification,
    onOpen: () -> Unit,
    onCopy: (String) -> Unit,
    onDismissNotification: () -> Unit
) {
    IconButton(onClick = onToggle) {
        Icon(AppIcons.MoreVert, contentDescription = "More notification actions")
    }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("Open") },
            onClick = {
                onDismiss()
                onOpen()
            }
        )
        val copyText = notification.fullText.ifBlank { notification.title }
        if (copyText.isNotBlank()) {
            DropdownMenuItem(
                text = { Text("Copy text") },
                onClick = {
                    onDismiss()
                    onCopy(copyText)
                }
            )
        }
        if (notification.isClearable) {
            DropdownMenuItem(
                text = { Text("Dismiss") },
                onClick = {
                    onDismiss()
                    onDismissNotification()
                }
            )
        }
    }
}
