package com.example.ui.screens.media

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.ui.components.landscapeContentWidth
import com.example.ui.components.AppIcons
import com.example.ui.components.AppSearchField
import com.example.ui.theme.AppCornerRadius
import com.example.ui.components.FloatingTopBar
import com.example.ui.MainViewModel
import com.example.util.SortOption
import com.example.util.RecycleBin
import com.example.util.StorageHelper
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// -----------------------------------------------------------------------------
// Data Structures
// -----------------------------------------------------------------------------

enum class VideoDisplayMode {
    FOLDERS, // VLC-style folder grouping
    GRID,    // Direct video grid view
    LIST     // Direct video list view
}

data class VideoFolderGroup(
    val folder: File,
    val videos: List<File>
) {
    val totalSize: Long get() = videos.sumOf { it.length() }
}

// -----------------------------------------------------------------------------
// Main Video Tab Screen
// -----------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoTabScreen(
    viewModel: MainViewModel,
    onOpenMedia: (File, String) -> Unit,
    // For playback we pass the whole visible playlist so the player can offer
    // prev/next, auto-advance and an on-screen playlist of the folder's videos.
    onOpenMediaWithPlaylist: (File, List<File>) -> Unit = { file, _ -> onOpenMedia(file, StorageHelper.getMimeType(file).ifEmpty { "video/*" }) }
) {
    val context = LocalContext.current
    val currentDir by viewModel.currentDirectory.collectAsState()
    val mediaIndex by viewModel.mediaIndex.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(Unit) {
        viewModel.ensureMediaIndexFresh()
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.ensureMediaIndexFresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var searchQuery by remember { mutableStateOf("") }
    var showSearchBar by remember { mutableStateOf(false) }
    var displayMode by remember { mutableStateOf(VideoDisplayMode.FOLDERS) }
    val lastVideoFolderPath by viewModel.lastVideoFolderPath.collectAsState()
    var selectedFolderGroup by remember { mutableStateOf<VideoFolderGroup?>(null) }

    // Long-press multi-select delete mode
    var selectMode by remember { mutableStateOf(false) }
    var selectedPaths by remember { mutableStateOf(setOf<String>()) }
    var showDeleteSelectedDialog by remember { mutableStateOf(false) }

    var sortOption by remember { mutableStateOf(SortOption.NAME) }
    var sortAscending by remember { mutableStateOf(true) }
    var showSortMenu by remember { mutableStateOf(false) }

    val videoExtensions = remember { setOf("mp4", "mkv", "webm", "avi", "3gp", "mov", "flv", "ts", "m4v") }

    // 1. Gather all video files across directory scanning and media index
    val allVideos = remember(currentDir, mediaIndex) {
        val storageFiles = StorageHelper.listFiles(currentDir).map { it.file }
        val allAccessible = mediaIndex.map { it.file }

        (storageFiles + allAccessible)
            .distinctBy { it.absolutePath }
            .filter { file ->
                file.isFile && (
                    file.extension.lowercase() in videoExtensions ||
                    StorageHelper.getMimeType(file).startsWith("video/")
                )
            }
    }

    // 2. Group videos into VLC-style folder buckets
    val folderGroups = remember(allVideos, searchQuery, sortOption, sortAscending) {
        val filtered = if (searchQuery.isBlank()) {
            allVideos
        } else {
            allVideos.filter { file ->
                file.name.contains(searchQuery, ignoreCase = true) ||
                file.parentFile?.name?.contains(searchQuery, ignoreCase = true) == true
            }
        }

        val grouped = filtered
            .groupBy { it.parentFile }
            .mapNotNull { (parentDir, files) ->
                if (parentDir != null && files.isNotEmpty()) {
                    val comparator = when (sortOption) {
                        SortOption.NAME -> compareBy<File> { it.name.lowercase() }
                        SortOption.DATE -> compareBy<File> { it.lastModified() }
                        SortOption.SIZE -> compareBy<File> { it.length() }
                        SortOption.TYPE -> compareBy<File> { it.extension.lowercase() }
                    }
                    val sortedFiles = if (sortAscending) files.sortedWith(comparator) else files.sortedWith(comparator).reversed()
                    VideoFolderGroup(folder = parentDir, videos = sortedFiles)
                } else null
            }

        val folderComparator = when (sortOption) {
            SortOption.NAME -> compareBy<VideoFolderGroup> { it.folder.name.lowercase() }
            SortOption.DATE -> compareBy<VideoFolderGroup> { it.folder.lastModified() }
            SortOption.SIZE -> compareBy<VideoFolderGroup> { it.totalSize }
            SortOption.TYPE -> compareBy<VideoFolderGroup> { it.videos.size }
        }

        if (sortAscending) grouped.sortedWith(folderComparator) else grouped.sortedWith(folderComparator).reversed()
    }

    // Restore the folder the user was in so returning from the video player /
    // screen switch lands back in the same folder instead of the folder list.
    LaunchedEffect(lastVideoFolderPath, folderGroups) {
        if (selectedFolderGroup == null && lastVideoFolderPath != null) {
            selectedFolderGroup = folderGroups.firstOrNull { it.folder.absolutePath == lastVideoFolderPath }
        }
    }

    // 3. Flat filtered videos for direct List/Grid view
    val flatFilteredVideos = remember(allVideos, searchQuery, sortOption, sortAscending) {
        val filtered = if (searchQuery.isBlank()) {
            allVideos
        } else {
            allVideos.filter { it.name.contains(searchQuery, ignoreCase = true) }
        }

        val comparator = when (sortOption) {
            SortOption.NAME -> compareBy<File> { it.name.lowercase() }
            SortOption.DATE -> compareBy<File> { it.lastModified() }
            SortOption.SIZE -> compareBy<File> { it.length() }
            SortOption.TYPE -> compareBy<File> { it.extension.lowercase() }
        }
        if (sortAscending) filtered.sortedWith(comparator) else filtered.sortedWith(comparator).reversed()
    }

    fun onRowClicked(file: File) {
        if (selectMode) {
            val p = file.absolutePath
            selectedPaths = if (p in selectedPaths) selectedPaths - p else selectedPaths + p
            if (selectedPaths.isEmpty()) selectMode = false
        } else {
            val playlist = selectedFolderGroup?.videos ?: flatFilteredVideos
            if (playlist.size > 1 && playlist.any { it.absolutePath == file.absolutePath }) {
                onOpenMediaWithPlaylist(file, playlist)
            } else {
                onOpenMedia(file, StorageHelper.getMimeType(file).ifEmpty { "video/*" })
            }
        }
    }

    fun onRowLongPressed(file: File) {
        val p = file.absolutePath
        if (!selectMode) {
            selectMode = true
            selectedPaths = setOf(p)
        } else {
            selectedPaths = if (p in selectedPaths) selectedPaths - p else selectedPaths + p
            if (selectedPaths.isEmpty()) selectMode = false
        }
    }

    fun onSelectAllVisible() {
        val visible = selectedFolderGroup?.videos ?: flatFilteredVideos
        selectedPaths = visible.map { it.absolutePath }.toSet()
    }

    fun onDeleteSelectedVideos() {
        val toDelete = (selectedFolderGroup?.videos ?: flatFilteredVideos).filter { it.absolutePath in selectedPaths }
        // B10: the Recycle bin, not an unlink. Toast (not a snackbar) because
        // these screens have no snackbar host, and Undo is one tap away in the
        // File Manager's Recycle bin.
        val binResult = runCatching { RecycleBin.trash(context, toDelete) }.getOrNull()
        if (binResult == null || binResult.trashed == 0) {
            android.widget.Toast.makeText(
                context, "Delete failed — files left in place", android.widget.Toast.LENGTH_LONG
            ).show()
        } else {
            android.widget.Toast.makeText(
                context,
                "Moved ${binResult.trashed} video(s) to Recycle bin",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
        selectedFolderGroup?.let { group ->
            selectedFolderGroup = group.copy(
                videos = group.videos.filterNot { it.absolutePath in selectedPaths }
            )
        }
        selectedPaths = emptySet()
        selectMode = false
        viewModel.refreshMediaIndex(force = true)
    }

    // Subfolders for standard filesystem navigation breadcrumb fallback
    val subFolders = remember(currentDir) {
        runCatching { StorageHelper.listFiles(currentDir).filter { it.isDirectory } }
            .getOrDefault(emptyList())
            .sortedBy { it.name.lowercase() }
    }

    val pathSegments = remember(currentDir) {
        val parts = currentDir.absolutePath.split(File.separator).filter { it.isNotEmpty() }
        val list = mutableListOf<Pair<String, File>>()
        var path = File(File.separator)
        for (part in parts) {
            path = File(path, part)
            list.add(part to path)
        }
        list
    }

    // Handle back button when drilled into a folder
    if (selectedFolderGroup != null) {
        BackHandler {
            selectedFolderGroup = null
            viewModel.setLastVideoFolderPath(null)
        }
    }

    // Back exits multi-select mode first
    if (selectMode) {
        BackHandler {
            selectedPaths = emptySet()
            selectMode = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Multi-select action bar (long-press rows to enter)
        if (selectMode) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                tonalElevation = 3.dp,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(top = 8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${selectedPaths.size} selected",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { onSelectAllVisible() }) { Text("All") }
                    TextButton(
                        onClick = {
                            if (selectedPaths.isNotEmpty()) showDeleteSelectedDialog = true
                        },
                        enabled = selectedPaths.isNotEmpty()
                    ) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = {
                        selectedPaths = emptySet()
                        selectMode = false
                    }) { Text("Cancel") }
                }
            }
        }

        // Header Bar: Search, View Switcher, Refresh, Sort & Equalizer
        if (!selectMode) {
        val useGlassmorphism by viewModel.useGlassmorphism.collectAsState()
        FloatingTopBar(useGlass = useGlassmorphism) {
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showSearchBar) {
                    AppSearchField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = "Search folders or videos...",
                        onClose = { showSearchBar = false },
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Text(
                        text = if (displayMode == VideoDisplayMode.FOLDERS) "Video Directories" else "Videos",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(
                        onClick = { showSearchBar = true },
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Icon(AppIcons.Search, contentDescription = "Search")
                    }
                }

            Spacer(modifier = Modifier.width(6.dp))

            // Refresh Media Index
            IconButton(
                onClick = { viewModel.refreshMediaIndex(force = true) },
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Icon(AppIcons.Refresh, contentDescription = "Refresh")
            }

            Spacer(modifier = Modifier.width(4.dp))

            // Display Mode Switcher (VLC Folders / Grid / List)
            IconButton(
                onClick = {
                    displayMode = when (displayMode) {
                        VideoDisplayMode.FOLDERS -> VideoDisplayMode.GRID
                        VideoDisplayMode.GRID -> VideoDisplayMode.LIST
                        VideoDisplayMode.LIST -> VideoDisplayMode.FOLDERS
                    }
                },
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Icon(
                    imageVector = when (displayMode) {
                        VideoDisplayMode.FOLDERS -> AppIcons.FolderSpecial
                        VideoDisplayMode.GRID -> AppIcons.GridView
                        VideoDisplayMode.LIST -> AppIcons.ViewList
                    },
                    contentDescription = "Toggle Mode",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            // Sort Dropdown Menu
            Box {
                IconButton(
                    onClick = { showSortMenu = true },
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Icon(AppIcons.Sort, contentDescription = "Sort")
                }
                DropdownMenu(
                    expanded = showSortMenu,
                    onDismissRequest = { showSortMenu = false }
                ) {
                    SortOption.values().forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = {
                                sortOption = option
                                showSortMenu = false
                            },
                            trailingIcon = {
                                if (sortOption == option) {
                                    Icon(AppIcons.Check, contentDescription = null)
                                }
                            }
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(if (sortAscending) "Ascending" else "Descending") },
                        onClick = {
                            sortAscending = !sortAscending
                            showSortMenu = false
                        },
                        trailingIcon = {
                            Icon(
                                imageVector = if (sortAscending) AppIcons.ArrowUpward else AppIcons.ArrowDownward,
                                contentDescription = null
                            )
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.width(4.dp))

            }
        }

        // Sub-bar Mode Indicator & Navigation Header
        if (selectedFolderGroup != null) {
            FloatingTopBar {
                IconButton(onClick = {
                    selectedFolderGroup = null
                    viewModel.setLastVideoFolderPath(null)
                }) {
                    Icon(AppIcons.ArrowBack, contentDescription = "Back to Folders")
                }
                Spacer(modifier = Modifier.width(4.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = selectedFolderGroup!!.folder.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${selectedFolderGroup!!.videos.size} videos • ${formatFileSize(selectedFolderGroup!!.totalSize)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else if (displayMode == VideoDisplayMode.FOLDERS) {
            FloatingTopBar {
                Icon(
                    imageVector = AppIcons.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "VLC Auto-Grouped Folders",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${folderGroups.size} Directories",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        } else {
            // Traditional Breadcrumb Toolbar for Direct Directory Browsing
            FloatingTopBar {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(AppIcons.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    pathSegments.forEachIndexed { index, (label, dir) ->
                        val isLast = index == pathSegments.lastIndex
                        TextButton(
                            onClick = { viewModel.navigateToDirectory(dir) },
                            enabled = !isLast,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                        ) {
                            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (!isLast) {
                            Icon(
                                AppIcons.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            if (subFolders.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(subFolders, key = { it.path }) { folder ->
                        AssistChip(
                            onClick = { viewModel.navigateToDirectory(folder.file) },
                            label = { Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = { Icon(AppIcons.Folder, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        )
                    }
                }
            }
        }
        }

        // Dynamic Main Content Area
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                // Case A: Inside a specific VLC folder group
                selectedFolderGroup != null -> {
                    val currentGroup = selectedFolderGroup!!
                    if (currentGroup.videos.isEmpty()) {
                        EmptyStateView("No videos inside this folder")
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(currentGroup.videos, key = { it.absolutePath }) { file ->
                                VideoRowItem(
                                    file = file,
                                    selectMode = selectMode,
                                    selected = file.absolutePath in selectedPaths,
                                    onClick = { onRowClicked(file) },
                                    onLongPress = { onRowLongPressed(file) }
                                )
                            }
                        }
                    }
                }

                // Case B: Default VLC-Style Folder Grouping View
                displayMode == VideoDisplayMode.FOLDERS -> {
                    if (folderGroups.isEmpty()) {
                        EmptyStateView(
                            if (searchQuery.isNotEmpty()) "No video folders match search"
                            else "No video folders found on storage"
                        )
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 160.dp),
                            contentPadding = PaddingValues(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            gridItems(folderGroups, key = { it.folder.absolutePath }) { group ->
                                FolderGroupCard(
                                    group = group,
                                    onClick = {
                                        selectedFolderGroup = group
                                        viewModel.setLastVideoFolderPath(group.folder.absolutePath)
                                    }
                                )
                            }
                        }
                    }
                }

                // Case C: Direct Grid View
                displayMode == VideoDisplayMode.GRID -> {
                    if (flatFilteredVideos.isEmpty()) {
                        EmptyStateView("No videos available")
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 160.dp),
                            contentPadding = PaddingValues(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            gridItems(flatFilteredVideos, key = { it.absolutePath }) { file ->
                                VideoGridItem(
                                    file = file,
                                    onOpen = { onOpenMediaWithPlaylist(file, flatFilteredVideos) }
                                )
                            }
                        }
                    }
                }

                // Case D: Direct List View
                displayMode == VideoDisplayMode.LIST -> {
                    if (flatFilteredVideos.isEmpty()) {
                        EmptyStateView("No videos available")
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(flatFilteredVideos, key = { it.absolutePath }) { file ->
                                VideoRowItem(
                                    file = file,
                                    selectMode = selectMode,
                                    selected = file.absolutePath in selectedPaths,
                                    onClick = { onRowClicked(file) },
                                    onLongPress = { onRowLongPressed(file) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Batch delete selected videos (long-press multi-select)
    if (showDeleteSelectedDialog) {
        val count = selectedPaths.size
        AlertDialog(
            onDismissRequest = { showDeleteSelectedDialog = false },
            title = { Text("Delete $count video${if (count == 1) "" else "s"}?") },
            text = { Text("Delete the selected file${if (count == 1) "" else "s"} from storage? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteSelectedVideos()
                    showDeleteSelectedDialog = false
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteSelectedDialog = false }) { Text("Cancel") }
            }
        )
    }
}

// -----------------------------------------------------------------------------
// Component UI Widgets
// -----------------------------------------------------------------------------

private data class VideoMeta(val thumbnail: Bitmap? = null, val durationMs: Long = 0L)

private fun formatVideoDuration(ms: Long): String {
    if (ms <= 0) return ""
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s)
    else String.format(Locale.getDefault(), "%d:%02d", m, s)
}

@Composable
private fun rememberVideoMeta(file: File): VideoMeta {
    val cached = remember(file.absolutePath) {
        synchronized(videoMetaCacheLock) { videoMetaCache[file.absolutePath] } ?: VideoMeta()
    }
    return produceState<VideoMeta>(initialValue = cached, file.absolutePath) {
        value = withContext(Dispatchers.IO) {
            synchronized(videoMetaCacheLock) { videoMetaCache[file.absolutePath] }?.let { return@withContext it }
            runCatching {
                val mmr = MediaMetadataRetriever()
                try {
                    mmr.setDataSource(file.absolutePath)
                    val dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    val frame = runCatching {
                        mmr.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    }.getOrNull()?.let { downscaleThumb(it) }
                    VideoMeta(frame, dur).also {
                        synchronized(videoMetaCacheLock) { videoMetaCache[file.absolutePath] = it }
                    }
                } finally {
                    runCatching { mmr.release() }
                }
            }.getOrElse { VideoMeta() }
        }
    }.value
}

// Bounded LRU cache so thumbnails are generated only once per file while scrolling.
private val videoMetaCache = object : LinkedHashMap<String, VideoMeta>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, VideoMeta>?): Boolean = size > 96
}
private val videoMetaCacheLock = Any()

private fun downscaleThumb(src: Bitmap): Bitmap {
    val w = src.width
    val h = src.height
    if (w <= 256 && h <= 256) return src
    val scale = 256f / Math.max(w, h)
    val nw = (w * scale).toInt().coerceAtLeast(1)
    val nh = (h * scale).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(src, nw, nh, true)
}

@Composable
private fun VideoThumbnailBox(
    file: File,
    modifier: Modifier = Modifier,
    fallbackIconSize: Int = 40
) {
    val context = LocalContext.current
    val meta = rememberVideoMeta(file)
    val resumeMs = remember(file.absolutePath) {
        context.getSharedPreferences("video_resume", android.content.Context.MODE_PRIVATE)
            .getInt(file.absolutePath, 0)
    }

    Box(
        modifier = modifier.background(Color(0xFF0F172A)),
        contentAlignment = Alignment.Center
    ) {
        val thumb = meta.thumbnail
        if (thumb != null) {
            Image(
                bitmap = thumb.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = AppIcons.PlayCircleFilled,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(fallbackIconSize.dp)
            )
        }

        // Center play affordance over the thumbnail
        if (thumb != null) {
            Surface(
                color = Color.Black.copy(alpha = 0.45f),
                shape = CircleShape,
                modifier = Modifier.align(Alignment.Center)
            ) {
                Icon(
                    imageVector = AppIcons.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .padding(6.dp)
                        .size(20.dp)
                )
            }
        }

        // Duration badge (bottom-end)
        if (meta.durationMs > 0) {
            Surface(
                color = Color.Black.copy(alpha = 0.6f),
                shape = MaterialTheme.shapes.extraSmall,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
            ) {
                Text(
                    text = formatVideoDuration(meta.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }

        // Resume badge (top-end) — only when a meaningful saved position exists
        if (resumeMs > 15_000) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = MaterialTheme.shapes.extraSmall,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = AppIcons.PlayCircleFilled,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier
                            .padding(start = 4.dp, top = 1.dp, bottom = 1.dp)
                            .size(12.dp)
                    )
                    Text(
                        text = "RESUME ${formatVideoDuration(resumeMs.toLong())}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderGroupCard(
    group: VideoFolderGroup,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .padding(14.dp)
                .fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(86.dp)
                    .clip(AppCornerRadius.sm)
                    .background(Color(0xFFFF9800).copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = AppIcons.Folder,
                    contentDescription = null,
                    tint = Color(0xFFFF9800),
                    modifier = Modifier.size(48.dp)
                )
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = CircleShape,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                ) {
                    Text(
                        text = "${group.videos.size}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = group.folder.name,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = formatFileSize(group.totalSize),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun VideoGridItem(file: File, onOpen: () -> Unit) {
    Card(
        onClick = onOpen,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth()
        ) {
            VideoThumbnailBox(
                file = file,
                fallbackIconSize = 48,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .clip(MaterialTheme.shapes.small)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = file.name,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = formatFileSize(file.length()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoRowItem(file: File, selectMode: Boolean, selected: Boolean, onClick: () -> Unit, onLongPress: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
    ) {
        Row(
            modifier = Modifier
                .padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            VideoThumbnailBox(
                file = file,
                fallbackIconSize = 32,
                modifier = Modifier
                    .size(72.dp)
                    .clip(MaterialTheme.shapes.small)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${formatFileSize(file.length())} • ${file.extension.uppercase()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (selectMode) {
                Icon(
                    imageVector = if (selected) AppIcons.CheckCircle else AppIcons.RadioButtonUnchecked,
                    contentDescription = if (selected) "Selected" else "Not selected",
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Icon(
                    imageVector = AppIcons.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun EmptyStateView(message: String) {
    Box(
        modifier = Modifier.fillMaxSize().landscapeContentWidth(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = AppIcons.OndemandVideo,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> String.format(Locale.getDefault(), "%.2f GB", gb)
        mb >= 1.0 -> String.format(Locale.getDefault(), "%.1f MB", mb)
        else -> String.format(Locale.getDefault(), "%.0f KB", kb)
    }
}
