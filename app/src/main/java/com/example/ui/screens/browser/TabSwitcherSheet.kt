package com.example.ui.screens.browser

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ui.components.AppIcons

/**
 * First-class tab switcher: a bottom sheet grouping normal and private tabs,
 * with create/close/reopen actions. Selecting a tab calls back so the host can
 * move the live Gecko session onto its view.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TabSwitcherSheet(
    tabs: List<GeckoTab>,
    activeIndex: Int,
    hasPrivate: Boolean,
    canReopen: Boolean,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
    onClose: (Int) -> Unit,
    onTogglePin: (Int) -> Unit,
    onNewTab: () -> Unit,
    onNewPrivateTab: () -> Unit,
    onReopenClosed: () -> Unit,
    onClosePrivate: () -> Unit,
    onCloseAll: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Tabs (${tabs.size})",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onNewTab) {
                    Icon(AppIcons.Add, "New tab")
                }
                IconButton(onClick = onNewPrivateTab) {
                    Icon(
                        AppIcons.VisibilityOff,
                        "New private tab",
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (canReopen) {
                    TextButton(onClick = onReopenClosed) {
                        Icon(AppIcons.Restore, null, Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Reopen")
                    }
                }
                if (hasPrivate) {
                    TextButton(onClick = onClosePrivate) {
                        Icon(
                            AppIcons.VisibilityOff,
                            null,
                            Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Close private", color = MaterialTheme.colorScheme.tertiary)
                    }
                }
                TextButton(onClick = onCloseAll) {
                    Text("Close all", color = MaterialTheme.colorScheme.error)
                }
            }

            LazyColumn(
                modifier = Modifier.heightIn(max = 440.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val normal = tabs.withIndex()
                    .filter { !it.value.isPrivate }
                    .sortedWith(compareByDescending<IndexedValue<GeckoTab>> { it.value.pinned }.thenBy { it.index })
                val private = tabs.withIndex().filter { it.value.isPrivate }

                if (normal.isNotEmpty()) {
                    item { MenuSection("Tabs") }
                    items(normal, key = { it.value.id }) { entry ->
                        TabCard(
                            tab = entry.value,
                            active = entry.index == activeIndex,
                            onClick = { onSelect(entry.index) },
                            onClose = { onClose(entry.index) },
                            onTogglePin = { onTogglePin(entry.index) }
                        )
                    }
                }
                if (private.isNotEmpty()) {
                    item { MenuSection("Private tabs") }
                    items(private, key = { it.value.id }) { entry ->
                        TabCard(
                            tab = entry.value,
                            active = entry.index == activeIndex,
                            onClick = { onSelect(entry.index) },
                            onClose = { onClose(entry.index) },
                            onTogglePin = { onTogglePin(entry.index) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TabCard(
    tab: GeckoTab,
    active: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
    onTogglePin: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = if (tab.isPrivate) MaterialTheme.colorScheme.tertiaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (active) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (tab.isPrivate) {
                Icon(
                    AppIcons.VisibilityOff,
                    null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(8.dp))
            } else if (tab.pinned) {
                Icon(
                    AppIcons.Star,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    tab.title.ifBlank { if (tab.url == NTP_URL) "New Tab" else tab.url },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    when {
                        tab.crashed -> "Crashed · ${tab.url}"
                        tab.url == NTP_URL -> "New tab"
                        else -> tab.url
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (tab.crashed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onTogglePin, modifier = Modifier.size(32.dp)) {
                Icon(
                    if (tab.pinned) AppIcons.Star else AppIcons.StarBorder,
                    if (tab.pinned) "Unpin tab" else "Pin tab",
                    modifier = Modifier.size(16.dp),
                    tint = if (tab.pinned) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                Icon(AppIcons.Close, "Close tab", modifier = Modifier.size(16.dp))
            }
        }
    }
}
