package com.example.ui.screens.home

import android.content.Intent
import android.provider.Settings
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.service.LauncherNotification
import com.example.ui.components.AppIcons
import com.example.ui.theme.AppCornerRadius

@Composable
fun LauncherNotificationsPanel(
    notifications: List<LauncherNotification>,
    hasAccess: Boolean,
    onOpenNotification: (LauncherNotification) -> Unit,
    onDismissNotification: (String) -> Unit,
    modifier: Modifier = Modifier,
    fillRemaining: Boolean = false
) {
    val context = LocalContext.current

    GlassPanel(
        modifier = modifier.fillMaxWidth(),
        shape = AppCornerRadius.xl,
        elevation = 6.dp,
        contentPadding = PaddingValues(14.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Notifications",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
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
                        NotificationRow(
                            notification = notif,
                            onOpen = { onOpenNotification(notif) },
                            onDismiss = { onDismissNotification(notif.key) }
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
    onOpen: () -> Unit,
    onDismiss: () -> Unit
) {
    val relative = DateUtils.getRelativeTimeSpanString(
        notification.postTime,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE
    ).toString()

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        shape = AppCornerRadius.md,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.Top
        ) {
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
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (notification.text.isNotBlank()) {
                    Text(
                        notification.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (notification.isClearable) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(
                        AppIcons.Close,
                        contentDescription = "Dismiss notification",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
