package com.example.ui.screens.launchersettings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ui.LAUNCHER_CLOCK_12H
import com.example.ui.LAUNCHER_CLOCK_24H
import com.example.ui.LAUNCHER_CLOCK_SYSTEM
import com.example.ui.LAUNCHER_LAYOUT_GRID
import com.example.ui.LAUNCHER_LAYOUT_LIST
import com.example.ui.MainViewModel
import com.example.ui.components.formatFileSize
import com.example.ui.components.AppIcons
import com.example.ui.components.FloatingTopBar
import com.example.ui.screens.home.decodeSampledBitmap
import com.example.ui.theme.AppCornerRadius
import com.example.util.LauncherWallpapers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Standalone Launcher settings app. Holds what used to be the "Launcher" card on
 * the main Settings screen: grid columns, tile size and the Home background
 * (image + opacity), plus a reset. Launched from the Home grid's System section.
 */
@Composable
fun LauncherSettingsScreen(
    viewModel: MainViewModel
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val launcherLayout by viewModel.launcherLayout.collectAsState()
    val launcherColumns by viewModel.launcherColumns.collectAsState()
    val launcherShowClock by viewModel.launcherShowClock.collectAsState()
    val launcherClockFormat by viewModel.launcherClockFormat.collectAsState()
    val launcherShowSections by viewModel.launcherShowSections.collectAsState()
    val launcherShowRecent by viewModel.launcherShowRecent.collectAsState()
    val launcherShowLabels by viewModel.launcherShowLabels.collectAsState()
    val launcherTileSize by viewModel.launcherTileSize.collectAsState()
    val launcherWallpaper by viewModel.launcherWallpaper.collectAsState()
    val launcherOpacity by viewModel.launcherBackgroundOpacity.collectAsState()
    val launcherScrim by viewModel.launcherScrim.collectAsState()
    val launcherWallpaperMode by viewModel.launcherWallpaperMode.collectAsState()
    val launcherWallpaperPaths by viewModel.launcherWallpaperPaths.collectAsState()
    val launcherSlideshowInterval by viewModel.launcherSlideshowIntervalSec.collectAsState()
    val launcherSlideshowShuffle by viewModel.launcherSlideshowShuffle.collectAsState()
    val launcherShowSystemPage by viewModel.launcherShowSystemPage.collectAsState()
    val launcherShowNowPlaying by viewModel.launcherShowNowPlaying.collectAsState()
    val launcherShowNotificationBadges by viewModel.launcherShowNotificationBadges.collectAsState()
    val launcherDoubleTapLock by viewModel.launcherDoubleTapLock.collectAsState()
    val launcherQuickShadeEnabled by viewModel.launcherQuickShadeEnabled.collectAsState()

    fun hasNotificationAccess(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)

    var notificationAccess by remember { mutableStateOf(hasNotificationAccess()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationAccess = hasNotificationAccess()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val wallpaperPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val dst = File(context.filesDir, "launcher_bg")
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        dst.outputStream().use { out -> input.copyTo(out) }
                        true
                    } ?: false
                }.getOrDefault(false)
            }
            if (ok) {
                viewModel.setLauncherWallpaper(dst.absolutePath)
            } else {
                Toast.makeText(context, "Could not set background", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val slideshowPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val before = viewModel.launcherWallpaperPaths.value
            val merged = withContext(Dispatchers.IO) {
                LauncherWallpapers.import(context, uris, before)
            }
            viewModel.setLauncherWallpaperPaths(merged)
            val added = merged.size - before.size
            Toast.makeText(
                context,
                when {
                    added <= 0 -> "No images added (limit ${LauncherWallpapers.MAX_IMAGES})"
                    added < uris.size -> "Added $added image(s); limit is ${LauncherWallpapers.MAX_IMAGES}"
                    else -> "Added $added image(s)"
                },
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    val slideshowBytes by produceState(0L, launcherWallpaperPaths) {
        value = withContext(Dispatchers.IO) { LauncherWallpapers.totalSize(context) }
    }
    val slideshowIntervals = remember {
        listOf(15 to "15s", 30 to "30s", 60 to "1m", 300 to "5m", 900 to "15m", 1800 to "30m", 3600 to "1h")
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
    val useGlassmorphism by viewModel.useGlassmorphism.collectAsState()
    FloatingTopBar(useGlass = useGlassmorphism) {
        Text(
            "Launcher Settings",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f)
        )
    }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "Home layout",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(
                                    LAUNCHER_LAYOUT_GRID to ("Grid" to AppIcons.GridView),
                                    LAUNCHER_LAYOUT_LIST to ("List" to AppIcons.ViewList)
                                ).forEach { (value, meta) ->
                                    val (label, icon) = meta
                                    FilterChip(
                                        selected = launcherLayout == value,
                                        onClick = { viewModel.setLauncherLayout(value) },
                                        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                                        leadingIcon = {
                                            Icon(
                                                if (launcherLayout == value) AppIcons.Check else icon,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    )
                                }
                            }
                            if (launcherLayout != LAUNCHER_LAYOUT_LIST) {
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    "Grid columns",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(-1 to "Auto", 4 to "4", 5 to "5", 6 to "6").forEach { (value, label) ->
                                        FilterChip(
                                            selected = launcherColumns == value,
                                            onClick = { viewModel.setLauncherColumns(value) },
                                            label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                                            leadingIcon = if (launcherColumns == value) {
                                                { Icon(AppIcons.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                            } else null
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    "Tile size",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(0 to "Compact", 1 to "Default", 2 to "Large").forEach { (value, label) ->
                                        FilterChip(
                                            selected = launcherTileSize == value,
                                            onClick = { viewModel.setLauncherTileSize(value) },
                                            label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                                            leadingIcon = if (launcherTileSize == value) {
                                                { Icon(AppIcons.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                            } else null
                                        )
                                    }
                                }
                            }
                            ListItem(
                                headlineContent = { Text("Show app labels") },
                                supportingContent = {
                                    Text("Names under grid tiles. List rows always show names")
                                },
                                leadingContent = { Icon(AppIcons.FormatSize, contentDescription = null) },
                                trailingContent = {
                                    Switch(
                                        checked = launcherShowLabels,
                                        onCheckedChange = { viewModel.setLauncherShowLabels(it) }
                                    )
                                }
                            )
                        }
                    }
                }

                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "Home content",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            ListItem(
                                headlineContent = { Text("Show clock & date") },
                                supportingContent = {
                                    Text(
                                        if (launcherShowClock)
                                            "The time and date appear above your apps"
                                        else "The header starts with the search button only"
                                    )
                                },
                                leadingContent = { Icon(AppIcons.Home, contentDescription = null) },
                                trailingContent = {
                                    Switch(
                                        checked = launcherShowClock,
                                        onCheckedChange = { viewModel.setLauncherShowClock(it) }
                                    )
                                }
                            )
                            Text(
                                "Clock format",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(
                                    LAUNCHER_CLOCK_SYSTEM to "System",
                                    LAUNCHER_CLOCK_12H to "12-hour",
                                    LAUNCHER_CLOCK_24H to "24-hour"
                                ).forEach { (value, label) ->
                                    FilterChip(
                                        selected = launcherClockFormat == value,
                                        enabled = launcherShowClock,
                                        onClick = { viewModel.setLauncherClockFormat(value) },
                                        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                                        leadingIcon = if (launcherClockFormat == value) {
                                            { Icon(AppIcons.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                        } else null
                                    )
                                }
                            }
                            ListItem(
                                headlineContent = { Text("Show section headers") },
                                supportingContent = {
                                    Text(
                                        if (launcherShowSections)
                                            "Comms, Apps, Tools, Web apps and System labels"
                                        else "All apps flow as one continuous group"
                                    )
                                },
                                leadingContent = { Icon(AppIcons.ViewAgenda, contentDescription = null) },
                                trailingContent = {
                                    Switch(
                                        checked = launcherShowSections,
                                        onCheckedChange = { viewModel.setLauncherShowSections(it) }
                                    )
                                }
                            )
                            ListItem(
                                headlineContent = { Text("Show Recent row") },
                                supportingContent = {
                                    Text("A quick-access shelf of your last launched apps")
                                },
                                leadingContent = { Icon(AppIcons.History, contentDescription = null) },
                                trailingContent = {
                                    Switch(
                                        checked = launcherShowRecent,
                                        onCheckedChange = { viewModel.setLauncherShowRecent(it) }
                                    )
                                }
                            )
                            ListItem(
                                headlineContent = { Text("Show System Tools page") },
                                supportingContent = {
                                    Text(
                                        if (launcherShowSystemPage)
                                            "Swipe for phone settings & device shortcuts"
                                        else "The Home grid is the only launcher page"
                                    )
                                },
                                leadingContent = { Icon(AppIcons.Tune, contentDescription = null) },
                                trailingContent = {
                                    Switch(
                                        checked = launcherShowSystemPage,
                                        onCheckedChange = { viewModel.setLauncherShowSystemPage(it) }
                                    )
                                }
                            )
                            ListItem(
                                headlineContent = { Text("Pull-down quick shade") },
                                supportingContent = {
                                    Text(
                                        if (launcherQuickShadeEnabled)
                                            "Pull down from the top of the launcher for notifications or quick settings"
                                        else "Disabled on launcher pages"
                                    )
                                },
                                leadingContent = { Icon(AppIcons.NotificationsActive, contentDescription = null) },
                                trailingContent = {
                                    Switch(
                                        checked = launcherQuickShadeEnabled,
                                        onCheckedChange = { viewModel.setLauncherQuickShadeEnabled(it) }
                                    )
                                }
                            )
                        }
                    }
                }

                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "Glance",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            ListItem(
                                headlineContent = { Text("Now playing") },
                                supportingContent = {
                                    Text("Show the current track and transport controls on Home")
                                },
                                leadingContent = { Icon(AppIcons.MusicNote, contentDescription = null) },
                                trailingContent = {
                                    Switch(
                                        checked = launcherShowNowPlaying,
                                        onCheckedChange = { viewModel.setLauncherShowNowPlaying(it) }
                                    )
                                }
                            )
                        }
                    }
                }

                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "Notifications",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            ListItem(
                                headlineContent = { Text("Notification badges") },
                                supportingContent = {
                                    Text(
                                        if (notificationAccess)
                                            "Unread counts on installed app search results"
                                        else "Grant Notification Access below to enable counts"
                                    )
                                },
                                leadingContent = { Icon(AppIcons.NotificationsActive, contentDescription = null) },
                                trailingContent = {
                                    Switch(
                                        checked = launcherShowNotificationBadges,
                                        onCheckedChange = { viewModel.setLauncherShowNotificationBadges(it) }
                                    )
                                }
                            )
                            ListItem(
                                headlineContent = { Text("Notification access") },
                                supportingContent = {
                                    Text(
                                        if (notificationAccess) "Granted"
                                        else "Not granted"
                                    )
                                },
                                leadingContent = { Icon(AppIcons.NotificationsActive, contentDescription = null) },
                                trailingContent = {
                                    TextButton(onClick = {
                                        runCatching {
                                            context.startActivity(
                                                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                            )
                                        }
                                    }) {
                                        Text(if (notificationAccess) "Manage" else "Grant")
                                    }
                                }
                            )
                        }
                    }
                }

                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "Gestures",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            ListItem(
                                headlineContent = { Text("Double-tap to lock") },
                                supportingContent = {
                                    Text(
                                        "Double-tap the Home clock or tap the lock icon. The first use asks to activate device admin."
                                    )
                                },
                                leadingContent = { Icon(AppIcons.Lock, contentDescription = null) },
                                trailingContent = {
                                    Switch(
                                        checked = launcherDoubleTapLock,
                                        onCheckedChange = { viewModel.setLauncherDoubleTapLock(it) }
                                    )
                                }
                            )
                        }
                    }
                }

                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "Extra pages",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            val dashboard by viewModel.launcherShowDashboardPage.collectAsState()
                            ListItem(
                                headlineContent = { Text("Tools & Dashboard (right)") },
                                supportingContent = { Text("Combine System Tools shortcuts with At-a-glance info") },
                                trailingContent = {
                                    Switch(
                                        checked = dashboard,
                                        onCheckedChange = { viewModel.setLauncherShowDashboardPage(it) }
                                    )
                                }
                            )
                        }
                    }
                }

                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "Icons & search",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            ListItem(
                                headlineContent = { Text("Search all apps") },
                                supportingContent = { Text("Search across all apps, not just the launcher catalog") },
                                leadingContent = { Icon(AppIcons.Search, contentDescription = null) },
                                trailingContent = {
                                    Switch(
                                        checked = viewModel.launcherSearchAll.collectAsState().value,
                                        onCheckedChange = { viewModel.setLauncherSearchAll(it) }
                                    )
                                }
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Icon shape",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(androidx.compose.foundation.rememberScrollState())
                            ) {
                                listOf(0 to "Rounded", 1 to "Circle", 2 to "Square", 3 to "Squircle").forEach { (value, label) ->
                                    FilterChip(
                                        selected = viewModel.launcherIconShape.collectAsState().value == value,
                                        onClick = { viewModel.setLauncherIconShape(value) },
                                        label = { Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1) },
                                        leadingIcon = if (viewModel.launcherIconShape.collectAsState().value == value) {
                                            { Icon(AppIcons.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                        } else null
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "Launcher background",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                if (launcherWallpaperMode == 1)
                                    if (launcherWallpaperPaths.isEmpty()) "Rotate several images behind the Home grid"
                                    else "Rotating ${launcherWallpaperPaths.size} image(s) behind your apps"
                                else if (launcherWallpaper != null) "A custom image is set behind your apps"
                                else "Use a custom image behind the Home grid",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                SegmentedButton(
                                    selected = launcherWallpaperMode == 0,
                                    onClick = { viewModel.setLauncherWallpaperMode(0) },
                                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                                    label = { Text("Static") }
                                )
                                SegmentedButton(
                                    selected = launcherWallpaperMode == 1,
                                    onClick = { viewModel.setLauncherWallpaperMode(1) },
                                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                                    label = { Text("Slideshow") }
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            if (launcherWallpaperMode == 0) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    if (launcherWallpaper != null) {
                                        TextButton(onClick = { viewModel.setLauncherWallpaper(null) }) {
                                            Text("Remove")
                                        }
                                    }
                                    TextButton(onClick = { wallpaperPicker.launch("image/*") }) {
                                        Text(if (launcherWallpaper != null) "Change" else "Choose")
                                    }
                                }
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    if (launcherWallpaperPaths.isNotEmpty()) {
                                        TextButton(onClick = {
                                            viewModel.setLauncherWallpaperPaths(emptyList())
                                            scope.launch(Dispatchers.IO) { LauncherWallpapers.clear(context) }
                                        }) { Text("Clear") }
                                    }
                                    TextButton(
                                        onClick = { slideshowPicker.launch("image/*") },
                                        enabled = launcherWallpaperPaths.size < LauncherWallpapers.MAX_IMAGES
                                    ) { Text("Add images") }
                                }
                                Text(
                                    "${launcherWallpaperPaths.size} of ${LauncherWallpapers.MAX_IMAGES} images • ${formatFileSize(slideshowBytes)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (launcherWallpaperPaths.isEmpty()) {
                                    Text(
                                        "Add images to start the slideshow",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    LazyRow(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        items(launcherWallpaperPaths, key = { it }) { path ->
                                            SlideshowThumb(path = path, onRemove = {
                                                val next = launcherWallpaperPaths - path
                                                viewModel.setLauncherWallpaperPaths(next)
                                                scope.launch(Dispatchers.IO) {
                                                    LauncherWallpapers.prune(context, next)
                                                }
                                            })
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        "Change every",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                                    )
                                    LazyRow(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        items(slideshowIntervals, key = { it.first }) { (seconds, label) ->
                                            FilterChip(
                                                selected = launcherSlideshowInterval == seconds,
                                                onClick = { viewModel.setLauncherSlideshowIntervalSec(seconds) },
                                                label = { Text(label) }
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                "Shuffle order",
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                                            )
                                            Text(
                                                "Pick a new order each time the launcher opens",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Switch(
                                            checked = launcherSlideshowShuffle,
                                            onCheckedChange = { viewModel.setLauncherSlideshowShuffle(it) }
                                        )
                                    }
                                }
                            }
                            val showBackgroundSliders =
                                (launcherWallpaperMode == 0 && launcherWallpaper != null) ||
                                    (launcherWallpaperMode == 1 && launcherWallpaperPaths.isNotEmpty())
                            if (showBackgroundSliders) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "Background opacity",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    "${(launcherOpacity * 100).toInt()}%",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Slider(
                                    value = launcherOpacity,
                                    onValueChange = { viewModel.setLauncherBackgroundOpacity(it) },
                                    valueRange = 0.1f..1f
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "Wallpaper dim",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    "${(launcherScrim * 100).toInt()}%",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Slider(
                                    value = launcherScrim,
                                    onValueChange = { viewModel.setLauncherScrim(it) },
                                    valueRange = 0f..0.85f
                                )
                            }
                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { viewModel.resetLauncherDefaults() }) {
                            Text("Reset launcher to defaults")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SlideshowThumb(path: String, onRemove: () -> Unit) {
    val image by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching { decodeSampledBitmap(path, 192)?.asImageBitmap() }.getOrNull()
        }
    }
    Box(
        modifier = Modifier
            .size(84.dp)
            .clip(AppCornerRadius.sm)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        image?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(2.dp)
                .size(22.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
                .clickable { onRemove() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                AppIcons.Close,
                contentDescription = "Remove image",
                tint = MaterialTheme.colorScheme.surface,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}
