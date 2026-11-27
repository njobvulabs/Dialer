package com.example.ui.screens.downloader

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.ui.components.landscapeContentWidth
import com.example.ui.components.AppIcons
import com.example.ui.components.AppSearchField
import com.example.ui.components.FloatingTopBar
import com.example.ui.MainViewModel
import com.example.util.DlJob
import com.example.util.DlState
import com.example.util.DownloadEntry
import com.example.util.DownloadLocations
import com.example.util.DownloadManager
import com.example.util.RecycleBin
import com.example.util.humanBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val imageExts = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "svg")
private val videoExts = setOf("mp4", "mkv", "webm", "avi", "3gp", "mov", "flv", "m4v", "ts")
private val audioExts = setOf("mp3", "wav", "ogg", "m4a", "flac", "opus", "aac", "amr", "mid")

private enum class DownloadSort(val label: String) {
    DATE("Date"),
    NAME("Name"),
    SIZE("Size")
}

private fun fileIcon(file: File): ImageVector = when (file.extension.lowercase()) {
    in imageExts -> AppIcons.Image
    in videoExts -> AppIcons.PlayCircle
    in audioExts -> AppIcons.MusicNote
    in setOf("pdf", "txt", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "epub", "csv", "rtf") ->
        AppIcons.Description
    else -> AppIcons.InsertDriveFile
}

private fun mimeFor(file: File): String = when (file.extension.lowercase()) {
    in imageExts -> "image/*"
    in videoExts -> "video/*"
    in audioExts -> "audio/*"
    "pdf" -> "application/pdf"
    "txt", "log", "md" -> "text/plain"
    "html", "htm" -> "text/html"
    "json" -> "application/json"
    "zip" -> "application/zip"
    "apk" -> "application/vnd.android.package-archive"
    else -> "application/octet-stream"
}

fun isOpenableInApp(file: File): Boolean {
    val e = file.extension.lowercase()
    return e in imageExts || e in videoExts || e in audioExts
}

/**
 * Full-screen download manager for the browser.
 *
 * Files are aggregated from every directory this app writes to, not just the
 * Download root: yt-dlp uses Movies/NJ-Dialer and Music/NJ-Dialer Audio, image
 * downloads use Pictures/NJ-Dialer, and Notes/Tasks export into
 * Download/NJ-Dialer. Tap opens a file in the built-in viewer/player (never an
 * external app); delete only appears in long-press selection mode.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun DownloadsScreen(
    viewModel: MainViewModel,
    onOpenMedia: (File) -> Unit = {}
) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    var files by remember { mutableStateOf(DownloadLocations.load()) }
    var selectMode by remember { mutableStateOf(false) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var sortOption by remember { mutableStateOf(DownloadSort.DATE) }
    var sortAscending by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<List<String>?>(null) }
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val selected = remember { mutableStateMapOf<String, Boolean>() }
    val activeJobs = DownloadManager.jobs.filter { it.state != DlState.DONE }

    val filteredFiles = remember(files, query, sortOption, sortAscending) {
        val needle = query.trim()
        val matched = if (needle.isBlank()) files else files.filter {
            it.file.name.contains(needle, ignoreCase = true) ||
                it.source.label.contains(needle, ignoreCase = true)
        }
        val comparator = when (sortOption) {
            DownloadSort.DATE -> compareBy<DownloadEntry> { it.file.lastModified() }
                .thenBy { it.file.name.lowercase() }
            DownloadSort.NAME -> compareBy<DownloadEntry> { it.file.name.lowercase() }
            DownloadSort.SIZE -> compareBy<DownloadEntry> { it.file.length() }
                .thenBy { it.file.name.lowercase() }
        }
        if (sortAscending) matched.sortedWith(comparator)
        else matched.sortedWith(comparator.reversed())
    }
    val visibleBytes = remember(filteredFiles) {
        filteredFiles.sumOf { it.file.length() }
    }

    fun pruneSelection() {
        val stillThere = files.map { it.file.absolutePath }.toHashSet()
        selected.keys.toList().forEach { if (it !in stillThere) selected.remove(it) }
        if (selected.isEmpty()) selectMode = false
    }

    fun refresh() {
        files = DownloadLocations.load()
        pruneSelection()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(Unit) { DownloadManager.attach(context) }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                DownloadManager.refreshFromSnapshot(context)
                files = withContext(Dispatchers.IO) { DownloadLocations.load() }
                pruneSelection()
                delay(2000)
            }
        }
    }

    LaunchedEffect(showSearch) {
        if (showSearch) {
            delay(80)
            focusRequester.requestFocus()
        }
    }

    BackHandler(enabled = showSearch || selectMode) {
        if (showSearch) {
            query = ""
            showSearch = false
            keyboard?.hide()
        } else {
            selected.clear()
            selectMode = false
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            val useGlassmorphism by viewModel.useGlassmorphism.collectAsState()
            FloatingTopBar(useGlass = useGlassmorphism) {
                Text(
                    if (selectMode) "${selected.size} selected" else "Downloads",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (selectMode) {
                    TextButton(
                        onClick = {
                            selected.clear()
                            filteredFiles.forEach { selected[it.file.absolutePath] = true }
                        },
                        enabled = filteredFiles.isNotEmpty()
                    ) { Text("All") }
                    IconButton(onClick = {
                        val doomed = selected.keys.toList()
                        if (doomed.isNotEmpty()) pendingDelete = doomed
                    }) {
                        Icon(
                            AppIcons.Delete,
                            "Delete selected",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                } else {
                    IconButton(onClick = {
                        if (showSearch) {
                            query = ""
                            showSearch = false
                            keyboard?.hide()
                        } else {
                            showSearch = true
                        }
                    }) {
                        Icon(
                            AppIcons.Search,
                            if (showSearch) "Close search" else "Search downloads",
                            tint = if (showSearch || query.isNotBlank())
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(AppIcons.Sort, "Sort downloads")
                        }
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false }
                        ) {
                            DownloadSort.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label) },
                                    onClick = {
                                        if (sortOption == option) {
                                            sortAscending = !sortAscending
                                        } else {
                                            sortOption = option
                                            sortAscending = option == DownloadSort.NAME
                                        }
                                        showSortMenu = false
                                    },
                                    trailingIcon = {
                                        if (sortOption == option) {
                                            Icon(AppIcons.Check, null, Modifier.size(16.dp))
                                        }
                                    }
                                )
                            }
                        }
                    }
                    IconButton(onClick = { sortAscending = !sortAscending }) {
                        Icon(
                            if (sortAscending) AppIcons.ArrowUpward
                            else AppIcons.ArrowDownward,
                            if (sortAscending) "Ascending" else "Descending"
                        )
                    }
                    IconButton(onClick = { refresh() }) {
                        Icon(AppIcons.Refresh, "Refresh")
                    }
                }
            }

            AnimatedVisibility(visible = showSearch) {
                AppSearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "Search downloads",
                    focusRequester = focusRequester,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }

            val hasRows = filteredFiles.isNotEmpty() || activeJobs.isNotEmpty()
            if (!hasRows) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (query.isNotBlank()) {
                            "No downloads match “$query”."
                        } else {
                            "No downloads yet.\n\n" +
                                "Files downloaded or exported by NJ-Dialer appear here, " +
                                "including browser downloads, notes/tasks exports, and " +
                                "video or audio downloads.\n\n" +
                                "Tap a file to open it in the built-in player/viewer. " +
                                "Long-press to select, share, or delete."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().landscapeContentWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (activeJobs.isNotEmpty()) {
                        item {
                            Text(
                                "Active downloads (${activeJobs.size})",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                            )
                        }
                        items(activeJobs, key = { "job_${it.id}" }) { job ->
                            DownloadJobCard(job, context)
                        }
                    }
                    if (filteredFiles.isNotEmpty()) {
                        item {
                            if (activeJobs.isNotEmpty()) {
                                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                            }
                            Text(
                                (if (query.isBlank()) {
                                    "Downloaded files (${filteredFiles.size})"
                                } else {
                                    "Matches (${filteredFiles.size})"
                                }) + "  •  ${humanBytes(visibleBytes)}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                            )
                        }
                        items(filteredFiles, key = { it.file.absolutePath }) { entry ->
                            val file = entry.file
                            val path = file.absolutePath
                            val checked = selected[path] == true
                            val isPlanned = selectMode
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.medium)
                                    .background(
                                        if (checked) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                                    )
                                    .combinedClickable(
                                        onClick = {
                                            if (isPlanned) {
                                                if (checked) selected.remove(path) else selected[path] = true
                                                if (selected.isEmpty()) selectMode = false
                                            } else {
                                                onOpenMedia(file)
                                            }
                                        },
                                        onLongClick = {
                                            if (!checked) selected[path] = true
                                            selectMode = selected.isNotEmpty()
                                        }
                                    )
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    fileIcon(file),
                                    null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        file.name,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        "${humanBytes(file.length())}  •  " +
                                            SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
                                                .format(Date(file.lastModified())) +
                                            "  •  ${entry.source.label}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (isPlanned) {
                                    Icon(
                                        if (checked) AppIcons.CheckCircle else AppIcons.RadioButtonUnchecked,
                                        if (checked) "Selected" else "Not selected",
                                        tint = if (checked) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Deleting downloads used to unlink every selected file straight away, with no
    // confirmation and no way back — a stray long-press selection was a permanent
    // loss. It now confirms and goes through the Recycle Bin like every other
    // file delete in the app.
    pendingDelete?.let { paths ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            icon = { Icon(AppIcons.Delete, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(if (paths.size == 1) "Delete file?" else "Delete ${paths.size} files?") },
            text = {
                Text(
                    if (paths.size == 1)
                        "${File(paths[0]).name} will be moved to the Recycle bin."
                    else
                        "The ${paths.size} selected files will be moved to the Recycle bin."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDelete = null
                        selected.clear()
                        selectMode = false
                        scope.launch {
                            val existing = paths.map(::File).filter { it.exists() }
                            val result = withContext(Dispatchers.IO) {
                                RecycleBin.trash(context, existing)
                            }
                            refresh()
                            Toast.makeText(
                                context,
                                when {
                                    result.trashed == 0 && existing.isNotEmpty() ->
                                        "Delete failed — files left in place"
                                    result.failed > 0 ->
                                        "Moved ${result.trashed} to Recycle bin (${result.failed} failed)"
                                    result.trashed == 1 -> "Moved 1 file to Recycle bin"
                                    else -> "Moved ${result.trashed} files to Recycle bin"
                                },
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun DownloadJobCard(job: DlJob, context: Context) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                job.title.ifBlank { job.url },
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (job.state == DlState.CANCELED || job.state == DlState.FAILED) {
                IconButton(onClick = { DownloadManager.dismiss(job) }) {
                    Icon(AppIcons.Close, "Dismiss", Modifier.size(16.dp))
                }
            }
        }
        Text(
            job.url,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { job.progress.coerceIn(0f, 100f) / 100f },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(4.dp))
        Text(
            when {
                job.error != null && job.state == DlState.FAILED -> "Failed: ${job.error}"
                job.state == DlState.PAUSED -> "Paused at ${job.progress.toInt()}%${jobSizeSuffix(job)}"
                job.state == DlState.CANCELED -> "Canceled"
                else -> buildString {
                    append("Downloading ${job.progress.toInt()}%")
                    append(jobSizeSuffix(job))
                    if (job.phase != null) append(" • ${job.phase}")
                    if (job.etaSec > 0) append(" • ETA ${job.etaSec}s")
                }
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (job.state == DlState.FAILED) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            when (job.state) {
                DlState.DOWNLOADING, DlState.QUEUED -> {
                    OutlinedButton(
                        onClick = { DownloadManager.pause(job) },
                        enabled = job.state == DlState.DOWNLOADING
                    ) {
                        Icon(AppIcons.Pause, null, Modifier.size(14.dp))
                        Text(" Pause")
                    }
                    OutlinedButton(onClick = { DownloadManager.cancel(job) }) {
                        Icon(AppIcons.Close, null, Modifier.size(14.dp))
                        Text(" Cancel")
                    }
                }
                DlState.PAUSED, DlState.FAILED -> {
                    Button(onClick = { DownloadManager.resumeOrRetry(context, job) }) {
                        Icon(AppIcons.PlayArrow, null, Modifier.size(14.dp))
                        Text(if (job.state == DlState.FAILED) " Retry" else " Resume")
                    }
                    OutlinedButton(onClick = { DownloadManager.cancel(job) }) {
                        Icon(AppIcons.Close, null, Modifier.size(14.dp))
                        Text(" Cancel")
                    }
                }
                DlState.CANCELED -> {
                    OutlinedButton(onClick = { DownloadManager.dismiss(job) }) {
                        Text("Dismiss")
                    }
                }
                DlState.DONE -> {}
            }
        }
    }
}

private fun jobSizeSuffix(job: DlJob): String {
    if (job.totalBytes <= 0L) return ""
    return " • ${humanBytes(job.doneBytes)} of ${humanBytes(job.totalBytes)}"
}
