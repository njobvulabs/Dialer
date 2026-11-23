package com.example.ui.screens.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ui.components.AppIcons
import com.example.util.SiteDecision
import com.example.util.SitePermission
import com.example.util.SitePermissions

/**
 * Per-site permission control (B6), embedded in the Site Info sheet. A decision
 * made here is permanent and wins over both Gecko's own prompt and the global
 * Mic & Camera switch, whereas leaving a capability on "Ask" keeps Gecko asking.
 *
 * Shows the origin currently in the active tab plus every origin that already has
 * a stored decision, so a site that asked once is still reachable after navigation.
 */
@Composable
internal fun SitePermissionsEditor(
    currentUrl: String?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var refreshKey by remember { mutableIntStateOf(0) }
    var confirmClearAll by remember { mutableStateOf(false) }

    val currentOrigin = SitePermissions.originOf(currentUrl)
    val stored = remember(refreshKey) { SitePermissions.storedOrigins(context) }

    Column(modifier = modifier.heightIn(max = 480.dp)) {
        // ---- The site in the active tab ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                AppIcons.Public,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = currentOrigin ?: "No site loaded",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (currentOrigin != null) {
                "Ask lets Gecko show its own prompt again."
            } else {
                "Open a page to change what that site may ask for."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))

        SitePermission.entries.forEach { perm ->
            PermissionRow(
                label = perm.label,
                current = SitePermissions.decision(context, currentOrigin, perm),
                enabled = currentOrigin != null,
                onPick = { decision ->
                    currentOrigin?.let { SitePermissions.set(context, it, perm, decision) }
                    refreshKey++
                }
            )
        }

        // ---- Everything else that has a stored decision ----
        val others = stored.filter { it != currentOrigin }
        if (others.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            HorizontalRule()
            Spacer(Modifier.height(10.dp))
            Text(
                "Other sites with saved choices",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Column(
                Modifier
                    .heightIn(max = 170.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                others.forEach { origin ->
                    val summary = SitePermission.entries
                        .mapNotNull { p ->
                            SitePermissions.decision(context, origin, p)
                                .takeIf { it != SiteDecision.ASK }
                                ?.let { "${p.label} ${verb(it)}" }
                        }
                        .joinToString(", ")
                        .ifEmpty { "all Ask" }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                SitePermissions.clearOrigin(context, origin)
                                refreshKey++
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                origin,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            AppIcons.DeleteSweep,
                            contentDescription = "Forget $origin",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Tap a site to forget its choices.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (stored.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { confirmClearAll = true }) {
                Icon(
                    AppIcons.DeleteSweep,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.width(6.dp))
                Text("Clear all site permissions", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (confirmClearAll) {
        AlertDialog(
            onDismissRequest = { confirmClearAll = false },
            title = { Text("Clear all site permissions?") },
            text = {
                Text(
                    "Every saved site choice is forgotten. Sites you have not pinned " +
                        "will start asking again from Gecko."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    SitePermissions.clearAll(context)
                    confirmClearAll = false
                    refreshKey++
                }) {
                    Text("Clear all", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearAll = false }) { Text("Cancel") }
            }
        )
    }
}

private fun verb(d: SiteDecision) = when (d) {
    SiteDecision.ALLOW -> "allowed"
    SiteDecision.DENY -> "denied"
    SiteDecision.ASK -> "ask"
}

/** One capability plus a three-way Ask / Allow / Deny segmented control. */
@Composable
private fun PermissionRow(
    label: String,
    current: SiteDecision,
    enabled: Boolean,
    onPick: (SiteDecision) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            },
            modifier = Modifier.weight(1f)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SiteDecision.entries.forEach { decision ->
                val selected = decision == current
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = when {
                        selected && decision == SiteDecision.ALLOW -> MaterialTheme.colorScheme.primary
                        selected && decision == SiteDecision.DENY -> MaterialTheme.colorScheme.error
                        selected -> MaterialTheme.colorScheme.secondaryContainer
                        else -> MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    modifier = Modifier
                        .clickable(enabled = enabled) { onPick(decision) }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = when (decision) {
                            SiteDecision.ASK -> "Ask"
                            SiteDecision.ALLOW -> "Allow"
                            SiteDecision.DENY -> "Deny"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = when {
                            selected && decision == SiteDecision.ALLOW -> MaterialTheme.colorScheme.onPrimary
                            selected && decision == SiteDecision.DENY -> MaterialTheme.colorScheme.onError
                            selected -> MaterialTheme.colorScheme.onSecondaryContainer
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun HorizontalRule() {
    Spacer(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}
