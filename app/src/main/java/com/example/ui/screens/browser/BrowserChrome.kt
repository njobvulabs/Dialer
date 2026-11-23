package com.example.ui.screens.browser

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ui.components.AppIcons

/**
 * The browser's floating chrome: a primary omnibox row (security pill, address,
 * reload/stop, tab counter, menu) plus a stable navigation row
 * (Back / Forward / Home / Downloads).
 *
 * Purely presentational — every action is a callback so the host screen keeps
 * owning session and tab state.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BrowserChrome(
    isPrivate: Boolean,
    secure: Boolean,
    displayUrl: String,
    canGoBack: Boolean,
    canGoForward: Boolean,
    loading: Boolean,
    progress: Int,
    tabCount: Int,
    onAddressClick: () -> Unit,
    onSiteInfoClick: () -> Unit,
    onBack: () -> Unit,
    onBackLongClick: () -> Unit,
    onForward: () -> Unit,
    onForwardLongClick: () -> Unit,
    onReloadOrStop: () -> Unit,
    onHome: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenTabs: () -> Unit,
    onMenu: () -> Unit,
    onExit: () -> Unit,
    onNewPrivateTab: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp,
        shadowElevation = 8.dp,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
            .padding(bottom = 12.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    onClick = onAddressClick,
                    shape = CircleShape,
                    color = if (isPrivate) MaterialTheme.colorScheme.tertiaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .clickable(onClick = onSiteInfoClick),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = when {
                                    isPrivate -> AppIcons.VisibilityOff
                                    secure -> AppIcons.Lock
                                    else -> AppIcons.Public
                                },
                                contentDescription = "Site information",
                                tint = when {
                                    isPrivate -> MaterialTheme.colorScheme.onTertiaryContainer
                                    secure -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(15.dp)
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = displayUrl.ifEmpty { "Search or enter address" },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isPrivate) MaterialTheme.colorScheme.onTertiaryContainer
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                if (isPrivate && onNewPrivateTab != null) {
                    IconButton(onClick = onNewPrivateTab) {
                        Icon(
                            AppIcons.Add,
                            "New private tab",
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }

                IconButton(onClick = onReloadOrStop) {
                    Icon(
                        if (loading) AppIcons.Close else AppIcons.Refresh,
                        if (loading) "Stop" else "Reload"
                    )
                }

                Surface(
                    onClick = onOpenTabs,
                    shape = MaterialTheme.shapes.small,
                    color = if (isPrivate) MaterialTheme.colorScheme.tertiaryContainer
                    else MaterialTheme.colorScheme.primaryContainer
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            AppIcons.Tab,
                            null,
                            tint = if (isPrivate) MaterialTheme.colorScheme.onTertiaryContainer
                            else MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "$tabCount",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (isPrivate) MaterialTheme.colorScheme.onTertiaryContainer
                            else MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                IconButton(onClick = onMenu) {
                    Icon(AppIcons.MoreVert, "Menu")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().height(44.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    AppIcons.ArrowBack, "Back",
                    tint = if (canGoBack) LocalContentColor.current
                    else LocalContentColor.current.copy(alpha = 0.38f),
                    modifier = Modifier
                        .size(24.dp)
                        .combinedClickable(
                            enabled = canGoBack,
                            onClick = onBack,
                            onLongClick = onBackLongClick
                        )
                        .padding(6.dp)
                )
                Icon(
                    AppIcons.ArrowForward, "Forward",
                    tint = if (canGoForward) LocalContentColor.current
                    else LocalContentColor.current.copy(alpha = 0.38f),
                    modifier = Modifier
                        .size(24.dp)
                        .combinedClickable(
                            enabled = canGoForward,
                            onClick = onForward,
                            onLongClick = onForwardLongClick
                        )
                        .padding(6.dp)
                )
                IconButton(onClick = onHome) {
                    Icon(AppIcons.Home, "Home page")
                }
                IconButton(onClick = onOpenDownloads) {
                    Icon(AppIcons.Download, "Downloads")
                }
                IconButton(onClick = onExit) {
                    Icon(AppIcons.Close, "Exit browser")
                }
            }
        }
    }
}

/** Small handle to show or hide the browser chrome. */
@Composable
internal fun ChromeToggleHandle(
    onClick: () -> Unit,
    atTop: Boolean = false,
    expanded: Boolean = false
) {
    val symbol = when {
        expanded && atTop -> "⌃"
        expanded && !atTop -> "⌄"
        atTop -> "⌄"
        else -> "⌃"
    }
    val topPad = if (expanded) (if (atTop) 0.dp else 4.dp) else (if (atTop) 10.dp else 0.dp)
    val bottomPad = if (expanded) (if (atTop) 4.dp else 0.dp) else (if (atTop) 0.dp else 10.dp)
    Box(
        modifier = Modifier
            .padding(top = topPad)
            .padding(bottom = bottomPad)
            .size(width = 44.dp, height = 22.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            symbol,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelSmall
        )
    }
}
