package com.example.ui.screens.home

import android.text.format.DateFormat
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.LAUNCHER_CLOCK_12H
import com.example.ui.LAUNCHER_CLOCK_24H
import com.example.ui.LAUNCHER_DEFAULT_SCRIM
import com.example.ui.components.AppIcons
import com.example.ui.screens.apps.AppItem
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.ui.theme.AppCornerRadius
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The launcher's app tile, shared by the Home grid and the System Tools page so the
 * swipe-left page keeps the same visual grammar. Long-pressing opens the pin menu;
 * [onTogglePin] may be null on a page whose tiles are not pinnable (system tools),
 * in which case the long-press menu is not offered. [badgeCount] > 0 draws a small
 * count badge over the swatch. [showLabel] false gives the icon-only grid; list rows
 * ([LauncherListRow]) always show the app name.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LauncherTile(
    item: AppItem,
    pinned: Boolean,
    swatch: Dp,
    iconSize: Dp,
    onClick: () -> Unit,
    onTogglePin: (() -> Unit)? = null,
    badgeCount: Int = 0,
    showLabel: Boolean = true,
    iconShape: Int = 0
) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current

    Box {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(AppCornerRadius.md)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = if (onTogglePin != null) {
                        {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            menuOpen = true
                        }
                    } else null
                )
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    contentDescription = item.title
                }
                .padding(vertical = if (showLabel) 6.dp else 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (showLabel) 6.dp else 0.dp)
        ) {
            Box {
                GlassIconSwatch(
                    icon = item.icon,
                    shape = launcherIconShape(iconShape),
                    swatch = swatch,
                    iconSize = iconSize
                )
                if (badgeCount > 0) {
                    CountBadge(
                        count = badgeCount,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 6.dp, y = (-6).dp)
                    )
                }
            }
            if (showLabel) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
        if (onTogglePin != null) {
            LauncherPinMenu(
                pinned = pinned,
                expanded = menuOpen,
                onDismiss = { menuOpen = false },
                onToggle = onTogglePin
            )
        }
    }
}

/**
 * Launcher list-mode row: one full-width entry with the app's name and description.
 * Used by the Home list layout and the System Tools page so both keep the same
 * grammar. Long-press still opens the same pin menu as a grid tile.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LauncherListRow(
    item: AppItem,
    pinned: Boolean,
    onClick: () -> Unit,
    onTogglePin: (() -> Unit)? = null,
    badgeCount: Int = 0,
    iconShape: Int = 0,
    glass: Boolean = false
) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val shape = AppCornerRadius.md
    val fill = glassFillBrush()
    val edge = glassEdgeBrush()

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (glass) {
                        Modifier
                            .shadow(2.dp, shape, clip = false)
                            .clip(shape)
                            .background(fill)
                            .border(1.dp, edge, shape)
                    } else {
                        Modifier
                            .clip(shape)
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                    }
                )
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = if (onTogglePin != null) {
                        {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            menuOpen = true
                        }
                    } else null
                )
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    contentDescription = item.title
                }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            GlassIconSwatch(
                icon = item.icon,
                shape = launcherIconShape(iconShape),
                swatch = 40.dp,
                iconSize = 22.dp
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.description.isNotBlank()) {
                    Text(
                        text = item.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (badgeCount > 0) {
                CountBadge(count = badgeCount)
            }
        }
        if (onTogglePin != null) {
            LauncherPinMenu(
                pinned = pinned,
                expanded = menuOpen,
                onDismiss = { menuOpen = false },
                onToggle = onTogglePin
            )
        }
    }
}

private fun launcherIconShape(iconShape: Int): Shape = when (iconShape) {
    1 -> CircleShape
    2 -> RoundedCornerShape(0.dp)
    3 -> RoundedCornerShape(28.dp)
    else -> AppCornerRadius.md
}

@Composable
private fun glassFillBrush(tint: Color? = null): Brush {
    val colors = MaterialTheme.colorScheme
    return remember(colors, tint) {
        if (tint == null) {
            Brush.linearGradient(
                listOf(
                    colors.surface.copy(alpha = 0.92f),
                    colors.surfaceContainerHighest.copy(alpha = 0.66f),
                    colors.surface.copy(alpha = 0.54f)
                )
            )
        } else {
            Brush.linearGradient(
                listOf(
                    tint.copy(alpha = 0.90f),
                    tint.copy(alpha = 0.72f),
                    tint.copy(alpha = 0.60f)
                )
            )
        }
    }
}

@Composable
private fun glassEdgeBrush(): Brush {
    val colors = MaterialTheme.colorScheme
    return remember(colors) {
        Brush.linearGradient(
            listOf(
                Color.White.copy(alpha = 0.36f),
                colors.onSurface.copy(alpha = 0.08f),
                colors.primary.copy(alpha = 0.24f)
            )
        )
    }
}

@Composable
private fun glassSheenBrush(): Brush = remember {
    Brush.verticalGradient(
        listOf(Color.White.copy(alpha = 0.20f), Color.Transparent)
    )
}

/**
 * Lightweight faux-glass surface used by the launcher header and dashboard cards.
 * It deliberately stops at translucent gradients, an edge highlight and a small
 * shadow: per-surface blur is too expensive for a scrolling launcher.
 */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = AppCornerRadius.md,
    onClick: (() -> Unit)? = null,
    tint: Color? = null,
    elevation: Dp = 4.dp,
    contentPadding: PaddingValues = PaddingValues(),
    content: @Composable BoxScope.() -> Unit
) {
    val clickModifier = if (onClick != null) {
        Modifier.clickable(role = Role.Button, onClick = onClick)
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .shadow(elevation, shape, clip = false)
            .clip(shape)
            .background(glassFillBrush(tint))
            .then(clickModifier)
    ) {
        Box(Modifier.matchParentSize().background(glassSheenBrush()))
        Box(Modifier.matchParentSize().border(1.dp, glassEdgeBrush(), shape))
        Box(Modifier.padding(contentPadding)) { content() }
    }
}

@Composable
private fun GlassIconSwatch(
    icon: ImageVector,
    shape: Shape,
    swatch: Dp,
    iconSize: Dp
) {
    val fill = glassFillBrush()
    val edge = glassEdgeBrush()
    val sheen = glassSheenBrush()

    Box(
        modifier = Modifier
            .size(swatch)
            .shadow(4.dp, shape, clip = false)
            .clip(shape)
            .background(fill)
    ) {
        Box(Modifier.matchParentSize().background(sheen))
        Box(Modifier.matchParentSize().border(1.dp, edge, shape))
        Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}

/** Count pill shared by the grid tile and the list row (99+ clamp). */
@Composable
private fun CountBadge(count: Int, modifier: Modifier = Modifier) {
    Surface(
        shape = AppCornerRadius.pill,
        color = MaterialTheme.colorScheme.error,
        modifier = modifier
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onError,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
        )
    }
}

/** Pin/Unpin long-press menu, shared by the grid tile and the list row. */
@Composable
private fun LauncherPinMenu(
    pinned: Boolean,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onToggle: () -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(if (pinned) "Unpin from top" else "Pin to top") },
            leadingIcon = {
                Icon(
                    if (pinned) AppIcons.Star else AppIcons.StarBorder,
                    contentDescription = null
                )
            },
            onClick = {
                onDismiss()
                onToggle()
            }
        )
    }
}

/**
 * Large time + date with seconds, refreshed every second. [clockFormat] follows
 * `launcher_clock_format`: System (0), force 12-hour (1), force 24-hour (2).
 */
@Composable
fun ClockHeader(clockFormat: Int = 0, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    val is24h = remember(clockFormat) {
        when (clockFormat) {
            LAUNCHER_CLOCK_12H -> false
            LAUNCHER_CLOCK_24H -> true
            else -> DateFormat.is24HourFormat(context)
        }
    }
    val timeFmt = remember(is24h) {
        DateTimeFormatter.ofPattern(if (is24h) "HH:mm:ss" else "h:mm:ss", Locale.getDefault())
    }
    val dateFmt = remember { DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault()) }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val current = LocalDateTime.now()
                now = current
                delay((1_000L - current.nano / 1_000_000L).coerceAtLeast(1L))
            }
        }
    }

    Column(modifier = modifier) {
        Text(
            text = now.format(timeFmt),
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = now.format(dateFmt),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Small label above a horizontal row of tiles / section header. */
@Composable
fun RowTitle(text: String, topPadding: Dp = 8.dp) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp, top = topPadding, bottom = 4.dp)
    )
}

/**
 * Full-bleed launcher background: the decoded wallpaper (optionally translucent via
 * [opacity]) behind a readability scrim of [scrim] alpha, or the plain themed
 * background when no wallpaper is set. The caller draws it behind its scrolling
 * content so the image fills behind the system bars.
 */
@Composable
fun LauncherBackground(
    wallpaper: androidx.compose.ui.graphics.ImageBitmap?,
    opacity: Float,
    modifier: Modifier = Modifier,
    scrim: Float = LAUNCHER_DEFAULT_SCRIM,
    crossfade: Boolean = true
) {
    if (crossfade) {
        Crossfade(
            targetState = wallpaper,
            animationSpec = tween(durationMillis = 700),
            label = "launcher_wallpaper"
        ) { bitmap ->
            WallpaperLayer(bitmap, opacity, modifier, scrim)
        }
    } else {
        WallpaperLayer(wallpaper, opacity, modifier, scrim)
    }
}

@Composable
private fun WallpaperLayer(
    wallpaper: androidx.compose.ui.graphics.ImageBitmap?,
    opacity: Float,
    modifier: Modifier,
    scrim: Float
) {
    if (wallpaper != null) {
        androidx.compose.foundation.Image(
            bitmap = wallpaper,
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = modifier
                .fillMaxSize()
                .alpha(opacity)
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background.copy(alpha = scrim))
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        )
    }
}
