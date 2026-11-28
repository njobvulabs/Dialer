package com.example.ui.screens.files

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.FileObserver
import android.os.Handler
import android.util.Log
import android.os.Looper
import android.os.StatFs
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.ui.components.landscapeContentWidth
import com.example.ui.components.AppIcons
import com.example.ui.components.AppSearchField
import com.example.ui.components.FloatingTopBar
import com.example.ui.MainViewModel
import com.example.util.FolderStats
import com.example.util.MediaItem
import com.example.util.SortOption
import com.example.util.RecycleBin
import com.example.util.StorageHelper
import com.example.util.StorageRoot
import com.example.util.ConverterCancelledException
import com.example.util.MediaTranscoder
import com.example.util.rememberSavedGridState
import com.example.util.rememberSavedListState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

enum class ViewMode { LIST, GRID, COMPACT }
enum class ClipboardOp { COPY, MOVE }

private val TEXT_EDITOR_EXTENSIONS = setOf(
    "txt", "text", "md", "markdown", "json", "xml", "yaml", "yml", "ini", "cfg", "conf",
    "properties", "log", "csv", "html", "htm", "css", "js", "jsx", "ts", "tsx", "kt", "kts",
    "java", "py", "rb", "php", "c", "h", "cpp", "hpp", "cs", "go", "rs", "sh", "bat", "sql"
)
private val ARCHIVE_MANAGER_EXTENSIONS = setOf("zip", "tar", "gz", "tgz", "tbz", "rar", "7z")

private fun isTextEditorFile(item: MediaItem): Boolean =
    !item.isDirectory && (item.mimeType.startsWith("text/") || item.file.extension.lowercase() in TEXT_EDITOR_EXTENSIONS)

private fun isArchiveManagerFile(item: MediaItem): Boolean =
    !item.isDirectory && item.file.extension.lowercase() in ARCHIVE_MANAGER_EXTENSIONS

// FileObserver is deprecated in favour of the StorageManager APIs, but those need
// per-file change events to be polled anyway, and a single inotify watch is far
// cheaper than the 2 s directory re-list this replaced.
@Suppress("DEPRECATION")
private const val FILE_WATCH_MASK: Int = FileObserver.CREATE or
    FileObserver.DELETE or
    FileObserver.MOVED_FROM or
    FileObserver.MOVED_TO or
    FileObserver.DELETE_SELF or
    FileObserver.MOVE_SELF

data class ClipboardState(
    val items: List<MediaItem>,
    val op: ClipboardOp
)

/** Everything B9 needs to run a paste, resolved by the picker + collision dialog. */
data class PastePlan(
    val payload: ClipboardState,
    val dest: File,
    val collision: StorageHelper.Collision
)

/** "3 of these names already exist in the target" — the choice offered per paste. */
data class CollisionPrompt(
    val plan: PastePlan,
    val names: List<String>
)

/** Live counters for the cancellable progress dialog. */
data class FileOpProgress(
    val verb: String,
    val done: Int,
    val total: Int,
    val current: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManagerScreen(
    viewModel: MainViewModel,
    onOpenMedia: (File, String) -> Unit,
    onOpenVault: () -> Unit = {},
    onExtractText: (File) -> Unit = {},
    onOpenTextEditor: (File) -> Unit = {},
    onOpenArchiveManager: (List<File>) -> Unit = {},
    onExit: () -> Unit = {}
) {
    val context = LocalContext.current
    val currentDir by viewModel.currentDirectory.collectAsState()
    val showHidden by viewModel.showHiddenFiles.collectAsState()

    // View mode / sort / hidden-files survive leaving and re-entering the screen.
    val prefs = remember { context.getSharedPreferences("files", Context.MODE_PRIVATE) }
    var viewMode by remember {
        mutableStateOf(
            runCatching { ViewMode.valueOf(prefs.getString("view_mode", null) ?: "") }
                .getOrDefault(ViewMode.LIST)
        )
    }
    var sortOption by remember {
        mutableStateOf(
            runCatching { SortOption.valueOf(prefs.getString("sort_option", null) ?: "") }
                .getOrDefault(SortOption.NAME)
        )
    }
    var sortAscending by remember { mutableStateOf(prefs.getBoolean("sort_ascending", true)) }

    LaunchedEffect(viewMode) { prefs.edit().putString("view_mode", viewMode.name).apply() }
    LaunchedEffect(sortOption) { prefs.edit().putString("sort_option", sortOption.name).apply() }
    LaunchedEffect(sortAscending) { prefs.edit().putBoolean("sort_ascending", sortAscending).apply() }

    // Scroll position is per folder: a single shared key restored the offset of
    // whichever directory was viewed previously, which was nonsense in any folder
    // of a different length. MainViewModel caps these keys so prefs stay bounded.
    val listState = rememberSavedListState("files_list_${viewMode}_${currentDir.path}", viewModel)
    val gridState = rememberSavedGridState("files_grid_${viewMode}_${currentDir.path}", viewModel)

    var searchQuery by remember { mutableStateOf("") }
    var showSearchBar by remember { mutableStateOf(false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var showNewFileDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showStorageDialog by remember { mutableStateOf(false) }
    var showStorageLocationsDialog by remember { mutableStateOf(false) }
    var selectedFileForAction by remember { mutableStateOf<MediaItem?>(null) }
    var clipboardState by remember { mutableStateOf<ClipboardState?>(null) }
    // B9: paste used to land in currentDir and silently overwrite same-named
    // files. It now goes through a destination picker, a collision choice, and a
    // cancellable progress dialog.
    var pastePlan by remember { mutableStateOf<PastePlan?>(null) }
    var pickingFor by remember { mutableStateOf<ClipboardState?>(null) }
    var collisionPrompt by remember { mutableStateOf<CollisionPrompt?>(null) }
    var fileOpProgress by remember { mutableStateOf<FileOpProgress?>(null) }
    var fileOpCancel by remember { mutableStateOf<(() -> Unit)?>(null) }

    // Multi-Select Mode
    var isMultiSelectActive by remember { mutableStateOf(false) }
    var selectedItems by remember { mutableStateOf<Set<MediaItem>>(emptySet()) }
    var showSelectionActionsMenu by remember { mutableStateOf(false) }

    // System Back retraces the user's actual trail (0 -> DCIM -> Download -> Back
    // returns to DCIM), not the parent chain. Registered after MainActivity's
    // immersive BackHandler, so this one wins while there is history to unwind; at
    // the trail's start it declines and the screen closes as before.
    val navState by viewModel.nav.collectAsState()

    // Volume label for a storage root ("Internal storage" / "SD card"), or its
    // real folder name for anything below. getExternalStorageDirectory() is
    // /storage/emulated/0 and File.name for that is "0", not "", so the root must
    // be matched by path, not by isEmpty.
    val storageRoots by viewModel.storageRoots.collectAsState()
    fun dirLabel(dir: File): String =
        storageRoots.firstOrNull { it.root.absolutePath == dir.absolutePath }?.label
            ?: dir.name.ifEmpty { dir.path }
    BackHandler(enabled = navState.canGoBack) {
        showSelectionActionsMenu = false
        isMultiSelectActive = false
        selectedItems = emptySet()
        searchQuery = ""
        viewModel.navigateBack()
    }

    // Refresh trigger
    var refreshCounter by remember { mutableIntStateOf(0) }
    var pendingSingleDelete by remember { mutableStateOf<MediaItem?>(null) }

    val scope = rememberCoroutineScope()

    // Every file mutation goes through here. zip/delete/copy walk whole directory
    // trees and used to run inline on the main thread, which froze the screen on
    // large selections; the toast and the list refresh now happen back on main.
    fun runFileOp(label: String, block: () -> Boolean) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching(block).getOrDefault(false) }
            refreshCounter++
            Toast.makeText(
                context,
                if (ok) "$label done" else "$label failed",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }

    /**
     * B10: delete by moving to the Recycle bin, with an Undo affordance. This
     * replaces the two old `StorageHelper.deleteFile` paths that destroyed files
     * outright. If trashing itself fails the file is left exactly where it is —
     * a failed bin must never become an accidental permanent delete.
     */
    fun trashItems(files: List<File>, done: () -> Unit = {}) {
        if (files.isEmpty()) return
        scope.launch {
            val result = withContext(Dispatchers.IO) { RecycleBin.trash(context, files) }
            refreshCounter++
            done()
            if (result.trashed == 0) {
                Toast.makeText(context, "Delete failed — file left in place", Toast.LENGTH_LONG).show()
                return@launch
            }
            val noun = if (result.trashed == 1) "item" else "items"
            val sn = snackbarHostState.showSnackbar(
                message = if (result.evicted.isEmpty())
                    "Moved $noun to Recycle bin" +
                        (if (result.failed > 0) " (${result.failed} failed)" else "")
                else
                    "Moved $noun to Recycle bin — oldest ${
                        if (result.evicted.size == 1) "item" else "${result.evicted.size} items"
                    } removed to stay within the limit",
                actionLabel = "Undo",
                withDismissAction = true,
                duration = SnackbarDuration.Long
            )
            if (sn == SnackbarResult.ActionPerformed) {
                // Match on the ORIGIN PATH, not the name. Name matching picked the
                // wrong rows whenever the bin already held an older file of the same
                // name (trashing "notes.txt" then a later "notes.txt" restored the
                // first one). Path is what actually identifies this delete.
                val undonePaths = files.mapTo(mutableSetOf()) { it.absolutePath }
                val undo = withContext(Dispatchers.IO) {
                    RecycleBin.entries()
                        .filter { e -> e.originPath in undonePaths }
                        .sortedByDescending { it.deletedAt }
                }
                val restored = withContext(Dispatchers.IO) {
                    undo.count { RecycleBin.restore(it) }
                }
                refreshCounter++
                Toast.makeText(
                    context,
                    if (restored > 0) "Restored $restored item(s)"
                    else "Nothing to restore — already emptied",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * B9: run a paste as one cancellable background job. A directory copy can take
     * minutes on this device, so progress and Cancel are not optional — but the
     * job never blocks the main thread, and cancelling stops between chunks rather
     * than leaving a half-written file (copyInto deletes the partial target).
     */
    fun startFileOp(plan: PastePlan) {
        // A plain flag, NOT Job.cancel(): cancelling the coroutine would throw
        // CancellationException straight out of withContext, skipping the cleanup
        // that clears the progress dialog — which is deliberately
        // non-dismissible, so the user would be stuck staring at it forever.
        val cancel = java.util.concurrent.atomic.AtomicBoolean(false)
        fileOpCancel = { cancel.set(true) }
        fileOpProgress = FileOpProgress(
            verb = if (plan.payload.op == ClipboardOp.COPY) "Copying" else "Moving",
            done = 0, total = plan.payload.items.size, current = ""
        )
        scope.launch {
            var done = 0
            var skipped = 0
            var failed = 0
            var wasCancelled = false
            try {
                withContext(Dispatchers.IO) {
                    for (media in plan.payload.items) {
                        // Cooperative: checked between items AND inside each file
                        // and directory level (copyInto takes the same lambda).
                        if (cancel.get()) {
                            wasCancelled = true
                            break
                        }
                        fileOpProgress = fileOpProgress?.copy(
                            done = done, current = media.file.name
                        )
                        val r = StorageHelper.copyInto(
                            src = media.file,
                            destDir = plan.dest,
                            collision = plan.collision,
                            isCancelled = { cancel.get() }
                        )
                        when (r.status) {
                            StorageHelper.OpStatus.DONE -> {
                                if (plan.payload.op == ClipboardOp.MOVE) {
                                    StorageHelper.deleteFile(media.file)
                                }
                                done++
                            }
                            StorageHelper.OpStatus.SKIPPED -> skipped++
                            StorageHelper.OpStatus.CANCELLED -> {
                                wasCancelled = true
                            }
                            StorageHelper.OpStatus.FAILED -> failed++
                        }
                        fileOpProgress = fileOpProgress?.copy(done = done)
                        if (wasCancelled) break
                    }
                }
            } catch (e: Exception) {
                // Never leave the non-dismissible progress dialog up.
                Log.w("NJFileOp", "paste failed", e)
                failed++
            } finally {
                fileOpProgress = null
                fileOpCancel = null
                clipboardState = null
                refreshCounter++
            }
            val verb = if (plan.payload.op == ClipboardOp.COPY) "Copied" else "Moved"
            val msg = when {
                wasCancelled && done == 0 -> "Cancelled"
                wasCancelled -> "Cancelled — $done of ${plan.payload.items.size} $verb"
                skipped > 0 -> "$verb $done, skipped $skipped"
                failed > 0 -> "$verb $done, failed $failed"
                else -> "$verb $done item(s) to ${plan.dest.name}"
            }
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // Live auto-refresh via a FileObserver on the open folder, replacing the old
    // unconditional 2-second poll that re-listed every directory forever (a steady
    // IO and battery cost, worst on the low-RAM device this ships to). A 20 s
    // net is kept as a safety net because inotify does not fire reliably on every
    // FUSE-backed external volume or OEM ROM, and the manual Refresh icon covers
    // the rest. Bursts (a paste, an unzip) are coalesced into one refresh.
    val refreshHandler = remember { Handler(Looper.getMainLooper()) }
    val refreshQueued = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    DisposableEffect(currentDir.path) {
        val observer = object : FileObserver(currentDir.path, FILE_WATCH_MASK) {
            override fun onEvent(event: Int, path: String?) {
                if (refreshQueued.compareAndSet(false, true)) {
                    refreshHandler.post {
                        refreshQueued.set(false)
                        refreshCounter++
                    }
                }
            }
        }
        runCatching { observer.startWatching() }
        onDispose { runCatching { observer.stopWatching() } }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(currentDir.path, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(20_000)
                refreshCounter++
            }
        }
    }

    // The raw listing. Deliberately NOT keyed on the folder-stats cache: a stat
    // arriving must not re-run listFiles() on the directory, only re-sort the rows
    // we already hold.
    val baseList by produceState<List<MediaItem>>(
        initialValue = emptyList(),
        currentDir, showHidden, searchQuery, refreshCounter
    ) {
        value = withContext(Dispatchers.Default) {
            if (searchQuery.isNotEmpty()) {
                runCatching { StorageHelper.searchFiles(currentDir, searchQuery) }.getOrDefault(emptyList())
            } else {
                runCatching { StorageHelper.listFiles(currentDir, showHidden) }.getOrDefault(emptyList())
            }
        }
    }

    // Size + item-count labels for child folders, measured in the background.
    val folderStatsMap by viewModel.folderStats.collectAsState()

    // Measure the folders of this listing. Skipped while searching: a search spans
    // many directories and walking each one would be far more work than the labels
    // are worth.
    LaunchedEffect(baseList, searchQuery) {
        if (searchQuery.isEmpty()) viewModel.requestFolderStats(baseList)
    }

    val fileList = remember(baseList, sortOption, sortAscending, folderStatsMap) {
        StorageHelper.sortedWithFoldersFirst(
            StorageHelper.withFolderSizes(baseList, folderStatsMap),
            sortOption,
            sortAscending
        ).distinctBy { it.path }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // App Bar / Selection Action Toolbar
        val useGlassmorphism by viewModel.useGlassmorphism.collectAsState()
        fun exitSelection() {
            showSelectionActionsMenu = false
            isMultiSelectActive = false
            selectedItems = emptySet()
        }
        fun toggleSelectAll() {
            selectedItems = if (selectedItems.size == fileList.size) emptySet() else fileList.toSet()
        }
        fun copySelected() {
            clipboardState = ClipboardState(selectedItems.toList(), ClipboardOp.COPY)
            Toast.makeText(context, "${selectedItems.size} items copied to clipboard", Toast.LENGTH_SHORT).show()
            exitSelection()
        }
        fun moveSelected() {
            clipboardState = ClipboardState(selectedItems.toList(), ClipboardOp.MOVE)
            Toast.makeText(context, "${selectedItems.size} items cut to clipboard", Toast.LENGTH_SHORT).show()
            exitSelection()
        }
        fun compressSelected() {
            val zipFile = File(currentDir, "Archive_${System.currentTimeMillis()}.zip")
            val payload = selectedItems.map { it.file }
            runFileOp("Compress") { StorageHelper.zipFiles(payload, zipFile) }
            exitSelection()
        }
        fun openArchiveSelected() {
            onOpenArchiveManager(selectedItems.map { it.file })
            exitSelection()
        }
        fun openVaultSelected() {
            exitSelection()
            onOpenVault()
        }
        FloatingTopBar(useGlass = useGlassmorphism) {
            BoxWithConstraints {
            val compactSelectionActions = maxWidth < 600.dp
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isMultiSelectActive) {
                    IconButton(onClick = { exitSelection() }) {
                        Icon(AppIcons.Close, contentDescription = "Exit selection")
                    }
                } else {
                    // Hidden at a volume root (internal storage / SD card).
                    if (navState.canGoBack) {
                        IconButton(onClick = { viewModel.navigateUp() }) {
                            Icon(AppIcons.ArrowBack, contentDescription = "Up one level")
                        }
                    }
                    // Only lit once Back has actually left somewhere to come back to.
                    IconButton(
                        onClick = { viewModel.navigateForward() },
                        enabled = navState.canGoForward
                    ) {
                        Icon(
                            AppIcons.ArrowForward,
                            contentDescription = "Forward",
                            tint = if (navState.canGoForward) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
                            }
                        )
                    }
                }

                Text(
                    text = if (isMultiSelectActive) "${selectedItems.size} selected" else dirLabel(currentDir),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.weight(1f)
                )

                if (isMultiSelectActive) {
                    if (compactSelectionActions) {
                        IconButton(onClick = { copySelected() }) {
                            Icon(AppIcons.ContentCopy, contentDescription = "Copy selected")
                        }
                        IconButton(onClick = { moveSelected() }) {
                            Icon(AppIcons.ContentCut, contentDescription = "Move selected")
                        }
                        Box {
                            IconButton(onClick = { showSelectionActionsMenu = true }) {
                                Icon(AppIcons.MoreVert, contentDescription = "More selection actions")
                            }
                            DropdownMenu(
                                expanded = showSelectionActionsMenu,
                                onDismissRequest = { showSelectionActionsMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(if (selectedItems.size == fileList.size) "Deselect All" else "Select All") },
                                    onClick = {
                                        showSelectionActionsMenu = false
                                        toggleSelectAll()
                                    },
                                    leadingIcon = {
                                        Icon(
                                            if (selectedItems.size == fileList.size) AppIcons.Deselect else AppIcons.SelectAll,
                                            contentDescription = null
                                        )
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Compress to ZIP") },
                                    onClick = {
                                        showSelectionActionsMenu = false
                                        compressSelected()
                                    },
                                    leadingIcon = { Icon(AppIcons.FolderZip, contentDescription = null) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Open Archive Manager") },
                                    onClick = {
                                        showSelectionActionsMenu = false
                                        openArchiveSelected()
                                    },
                                    leadingIcon = { Icon(AppIcons.Archive, contentDescription = null) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Open Encrypted Vault") },
                                    onClick = {
                                        showSelectionActionsMenu = false
                                        openVaultSelected()
                                    },
                                    leadingIcon = { Icon(AppIcons.Lock, contentDescription = null) }
                                )
                            }
                        }
                    } else {
                        IconButton(onClick = { toggleSelectAll() }) {
                            Icon(
                                imageVector = if (selectedItems.size == fileList.size) AppIcons.Deselect else AppIcons.SelectAll,
                                contentDescription = "Select All Toggle"
                            )
                        }
                        IconButton(onClick = { copySelected() }) {
                            Icon(AppIcons.ContentCopy, contentDescription = "Copy selected")
                        }
                        IconButton(onClick = { moveSelected() }) {
                            Icon(AppIcons.ContentCut, contentDescription = "Move selected")
                        }
                        IconButton(onClick = { compressSelected() }) {
                            Icon(AppIcons.FolderZip, contentDescription = "Compress to ZIP")
                        }
                        IconButton(onClick = { openArchiveSelected() }) {
                            Icon(AppIcons.Archive, contentDescription = "Open Archive Manager")
                        }
                        IconButton(onClick = { openVaultSelected() }) {
                            Icon(AppIcons.Lock, contentDescription = "Open Encrypted Vault")
                        }
                    }
                    IconButton(
                        onClick = {
                            showSelectionActionsMenu = false
                            showDeleteConfirmDialog = true
                        },
                        enabled = selectedItems.isNotEmpty()
                    ) {
                        Icon(AppIcons.Delete, contentDescription = "Delete selected", tint = MaterialTheme.colorScheme.error)
                    }
                } else {
                    IconButton(onClick = { showSearchBar = !showSearchBar }) {
                        Icon(
                            imageVector = if (showSearchBar) AppIcons.Close else AppIcons.Search,
                            contentDescription = "Search",
                            tint = if (showSearchBar) MaterialTheme.colorScheme.primary else LocalContentColor.current
                        )
                    }

                    IconButton(onClick = {
                        viewMode = when (viewMode) {
                            ViewMode.LIST -> ViewMode.GRID
                            ViewMode.GRID -> ViewMode.COMPACT
                            ViewMode.COMPACT -> ViewMode.LIST
                        }
                    }) {
                        Icon(
                            imageVector = when (viewMode) {
                                ViewMode.LIST -> AppIcons.GridView
                                ViewMode.GRID -> AppIcons.ViewStream
                                ViewMode.COMPACT -> AppIcons.ViewList
                            },
                            contentDescription = "Change View Mode"
                        )
                    }

                    IconButton(onClick = { viewModel.toggleShowHiddenFiles() }) {
                        Icon(
                            imageVector = if (showHidden) AppIcons.Visibility else AppIcons.VisibilityOff,
                            contentDescription = "Hidden Files"
                        )
                    }

                    IconButton(onClick = {
                        refreshCounter++
                        Toast.makeText(context, "Folder refreshed", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(AppIcons.Refresh, contentDescription = "Refresh Directory")
                    }

                    var showMenu by remember { mutableStateOf(false) }
                    // Sort lives one level down: one "Sort by ..." row, then a
                    // submenu of the four options + direction. Kept in the same popup
                    // (instead of a nested DropdownMenu) because a popup opened from
                    // inside a focusable popup can take the parent down with it.
                    var showSortSubmenu by remember { mutableStateOf(false) }
                    IconButton(onClick = { showMenu = true }) {
                        Icon(AppIcons.MoreVert, contentDescription = "Options")
                    }

                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        if (showSortSubmenu) {
                            DropdownMenuItem(
                                text = { Text("Sort by") },
                                onClick = { showSortSubmenu = false },
                                leadingIcon = { Icon(AppIcons.ArrowBack, contentDescription = null) }
                            )
                            HorizontalDivider()
                            SortOption.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            option.label + if (sortOption == option) {
                                                if (sortAscending) "  \u2191" else "  \u2193"
                                            } else ""
                                        )
                                    },
                                    onClick = {
                                        if (sortOption == option) sortAscending = !sortAscending
                                        else {
                                            sortOption = option
                                            sortAscending = true
                                        }
                                        showSortSubmenu = false
                                        showMenu = false
                                    },
                                    leadingIcon = {
                                        Icon(
                                            when (option) {
                                                SortOption.NAME -> AppIcons.SortByAlpha
                                                SortOption.DATE -> AppIcons.CalendarToday
                                                SortOption.SIZE -> AppIcons.FolderZip
                                                SortOption.TYPE -> AppIcons.Category
                                            },
                                            contentDescription = null
                                        )
                                    }
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(if (sortAscending) "Ascending" else "Descending") },
                                onClick = {
                                    sortAscending = !sortAscending
                                    showSortSubmenu = false
                                    showMenu = false
                                },
                                leadingIcon = {
                                    Icon(
                                        if (sortAscending) AppIcons.ArrowUpward else AppIcons.ArrowDownward,
                                        contentDescription = null
                                    )
                                }
                            )
                        } else {
                            // (Recycle bin used to sit first as the undo path for a
                            // delete; it moved to the Tools tab as the first tile.)
                            DropdownMenuItem(
                                text = { Text("Storage locations") },
                                onClick = {
                                    showMenu = false
                                    viewModel.refreshStorageRoots()
                                    showStorageLocationsDialog = true
                                },
                                leadingIcon = { Icon(AppIcons.Storage, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Storage Usage") },
                                onClick = {
                                    showMenu = false
                                    showStorageDialog = true
                                },
                                leadingIcon = { Icon(AppIcons.Storage, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text("New Folder") },
                                onClick = {
                                    showMenu = false
                                    showNewFolderDialog = true
                                },
                                leadingIcon = { Icon(AppIcons.CreateNewFolder, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text("New File") },
                                onClick = {
                                    showMenu = false
                                    showNewFileDialog = true
                                },
                                leadingIcon = { Icon(AppIcons.NoteAdd, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Open Encrypted Vault") },
                                onClick = {
                                    showMenu = false
                                    onOpenVault()
                                },
                                leadingIcon = { Icon(AppIcons.Lock, contentDescription = null) }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Exit") },
                                onClick = {
                                    showMenu = false
                                    onExit()
                                },
                                leadingIcon = { Icon(AppIcons.FullscreenExit, contentDescription = null) }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text("Sort by ${sortOption.label}" + (if (sortAscending) "  ↑" else "  ↓"))
                                },
                                onClick = { showSortSubmenu = true },
                                leadingIcon = { Icon(AppIcons.Sort, contentDescription = null) },
                                trailingIcon = { Icon(AppIcons.ChevronRight, contentDescription = null) }
                            )
                    }
                    }
                }
            }
        }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Search Bar (appears when the search icon is clicked)
                AnimatedVisibility(visible = showSearchBar) {
                    AppSearchField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = "Search files & folders...",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }

                // Breadcrumb trail. It IS navState.stack, the very list system Back
                // walks, so the path shown and the path Back follows cannot drift.
                // Tapping a crumb truncates the trail there rather than pushing onto
                // it: jumping from 0/A/B to 0 must not make Back bounce through A
                // and B again on the way out.
                if (!isMultiSelectActive && navState.stack.isNotEmpty()) {
                    val crumbs = navState.stack
                    val breadcrumbState = rememberLazyListState()
                    LaunchedEffect(navState.current.absolutePath) {
                        breadcrumbState.animateScrollToItem(crumbs.lastIndex)
                    }
                    LazyRow(
                        state = breadcrumbState,
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        itemsIndexed(crumbs) { index, crumb ->
                            val isCurrent = index == crumbs.lastIndex
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (index > 0) {
                                    Icon(
                                        imageVector = AppIcons.KeyboardArrowRight,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    )
                                }
                                Text(
                                    text = dirLabel(crumb),
                                    maxLines = 1,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                                    ),
                                    color = if (isCurrent) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier
                                        .clip(MaterialTheme.shapes.small)
                                        .clickable(enabled = !isCurrent) { viewModel.jumpToDirectory(crumb) }
                                        .padding(horizontal = 6.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                }

                if (fileList.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = AppIcons.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = if (searchQuery.isNotEmpty()) "No matching items found" else "Folder is empty",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    Box(modifier = Modifier.weight(1f)) {
                        when (viewMode) {
                            ViewMode.LIST -> {
                                LazyColumn(
                                    state = listState,
                                    contentPadding = PaddingValues(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxSize().landscapeContentWidth()
                                ) {
                                    items(fileList, key = { it.path }) { item ->
                                        FileRowItem(
                                            item = item,
                                            isSelected = selectedItems.contains(item),
                                            isMultiSelect = isMultiSelectActive,
                                            stats = if (item.isDirectory) {
                                                folderStatsMap[item.path]
                                                    ?.takeIf { it.dirMtime == item.lastModified }
                                            } else null,
                                            // A walk is only ever requested for a normal
                                            // listing, so searching must not leave folder
                                            // rows stuck on "measuring…".
                                            statsPending = item.isDirectory && searchQuery.isEmpty(),
                                            onClick = {
                                                if (isMultiSelectActive) {
                                                    selectedItems = if (selectedItems.contains(item)) selectedItems - item else selectedItems + item
                                                } else if (item.isDirectory) {
                                                    viewModel.navigateToDirectory(item.file)
                                                } else {
                                                    onOpenMedia(item.file, item.mimeType)
                                                }
                                            },
                                            onLongClick = {
                                                isMultiSelectActive = true
                                                selectedItems = selectedItems + item
                                            },
                                            onMoreClick = { selectedFileForAction = item }
                                        )
                                    }
                                }
                            }
                            ViewMode.GRID -> {
                                LazyVerticalGrid(
                                    state = gridState,
                                    columns = GridCells.Fixed(3),
                                    contentPadding = PaddingValues(16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxSize().landscapeContentWidth()
                                ) {
                                    gridItems(fileList, key = { it.path }) { item ->
                                        FileGridItem(
                                            item = item,
                                            isSelected = selectedItems.contains(item),
                                            isMultiSelect = isMultiSelectActive,
                                            onClick = {
                                                if (isMultiSelectActive) {
                                                    selectedItems = if (selectedItems.contains(item)) selectedItems - item else selectedItems + item
                                                } else if (item.isDirectory) {
                                                    viewModel.navigateToDirectory(item.file)
                                                } else {
                                                    onOpenMedia(item.file, item.mimeType)
                                                }
                                            },
                                            onLongClick = {
                                                isMultiSelectActive = true
                                                selectedItems = selectedItems + item
                                            },
                                            onMoreClick = { selectedFileForAction = item }
                                        )
                                    }
                                }
                            }
                            ViewMode.COMPACT -> {
                                LazyColumn(
                                    state = listState,
                                    contentPadding = PaddingValues(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    items(fileList, key = { it.path }) { item ->
                                        FileCompactRowItem(
                                            item = item,
                                            isSelected = selectedItems.contains(item),
                                            isMultiSelect = isMultiSelectActive,
                                            onClick = {
                                                if (isMultiSelectActive) {
                                                    selectedItems = if (selectedItems.contains(item)) selectedItems - item else selectedItems + item
                                                } else if (item.isDirectory) {
                                                    viewModel.navigateToDirectory(item.file)
                                                } else {
                                                    onOpenMedia(item.file, item.mimeType)
                                                }
                                            },
                                            onLongClick = {
                                                isMultiSelectActive = true
                                                selectedItems = selectedItems + item
                                            },
                                            onMoreClick = { selectedFileForAction = item }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Undo for the Recycle bin (B10). Sits above the paste bar so the two
            // never overlap.
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 96.dp)
            )

            // Clipboard Paste Bar Overlay
            clipboardState?.let { cb ->
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    tonalElevation = 8.dp,
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${cb.op.name}: ${cb.items.size} item(s)",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = "Target: ${currentDir.name.ifEmpty { "Root" }}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { clipboardState = null }) {
                                Text("Cancel")
                            }
                            Button(
                                onClick = {
                                    // Default target is still this folder, but the
                                    // paste can now be re-pointed elsewhere and
                                    // cannot clobber a same-named file by accident.
                                    pickingFor = cb
                                }
                            ) {
                                Icon(AppIcons.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(if (cb.op == ClipboardOp.COPY) "Paste" else "Move here")
                            }
                        }
                    }
                }
            }
        }
    }

    // ---------------- B9: paste destination / collision / progress ----------------

    // 1) Where should it go?
    pickingFor?.let { cb ->
        DestinationPickerDialog(
            initial = currentDir,
            onDismiss = { pickingFor = null },
            onPick = { dest ->
                pickingFor = null
                val plan = PastePlan(
                    payload = cb,
                    dest = dest,
                    collision = StorageHelper.Collision.KEEP_BOTH
                )
                val names = StorageHelper.collidingNames(cb.items.map { it.file }, dest)
                if (names.isEmpty()) {
                    startFileOp(plan)
                } else {
                    collisionPrompt = CollisionPrompt(plan, names)
                }
            }
        )
    }

    // 2) Same names already there — never silently overwrite (B9).
    collisionPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = { collisionPrompt = null },
            title = { Text("Name conflict") },
            text = {
                Column {
                    Text(
                        if (prompt.names.size == 1)
                            "'${prompt.names.first()}' already exists in the target folder."
                        else
                            "${prompt.names.size} items already exist in the target folder " +
                                "(${prompt.names.take(3).joinToString(", ")}" +
                                if (prompt.names.size > 3) ", …" else "" + ").",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Applies to all ${prompt.plan.payload.items.size} item(s).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    startFileOp(prompt.plan.copy(collision = StorageHelper.Collision.KEEP_BOTH))
                    collisionPrompt = null
                }) { Text("Keep both") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        startFileOp(prompt.plan.copy(collision = StorageHelper.Collision.SKIP))
                        collisionPrompt = null
                    }) { Text("Skip existing") }
                    TextButton(onClick = {
                        startFileOp(prompt.plan.copy(collision = StorageHelper.Collision.OVERWRITE))
                        collisionPrompt = null
                    }) {
                        Text("Overwrite", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        )
    }

    // 3) Live progress, cancellable (B9).
    fileOpProgress?.let { p ->
        val frac = if (p.total <= 0) 0f else p.done.toFloat() / p.total
        AlertDialog(
            // Deliberately not dismissable: leaving would strand a half-written
            // copy with no way to stop it.
            onDismissRequest = {},
            title = { Text(p.verb) },
            text = {
                Column {
                    LinearProgressIndicator(
                        progress = { frac },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "${p.done} of ${p.total}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (p.current.isNotEmpty()) {
                        Text(
                            p.current,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { fileOpCancel?.invoke() }) { Text("Cancel") }
            }
        )
    }

    // Storage Usage Dialog
    if (showStorageDialog) {
        StorageStatsDialog(onDismiss = { showStorageDialog = false })
    }

    // Storage locations (Internal/SD) Dialog
    if (showStorageLocationsDialog) {
        StorageLocationsDialog(
            roots = storageRoots,
            onPick = { root ->
                showStorageLocationsDialog = false
                viewModel.openRoot(root.root)
            },
            onDismiss = { showStorageLocationsDialog = false }
        )
    }

    // New Folder Dialog
    if (showNewFolderDialog) {
        var newFolderName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            title = { Text("Create New Folder") },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    label = { Text("Folder Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (newFolderName.trim().isNotEmpty()) {
                        val created = StorageHelper.createFolder(currentDir, newFolderName.trim())
                        if (created) Toast.makeText(context, "Folder created", Toast.LENGTH_SHORT).show()
                        else Toast.makeText(context, "Failed to create folder", Toast.LENGTH_SHORT).show()
                        refreshCounter++
                        showNewFolderDialog = false
                    }
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolderDialog = false }) { Text("Cancel") }
            }
        )
    }

    // New File Dialog
    if (showNewFileDialog) {
        var newFileName by remember { mutableStateOf("") }
        var newFileContent by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewFileDialog = false },
            title = { Text("Create New Text File") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newFileName,
                        onValueChange = { newFileName = it },
                        label = { Text("File Name (e.g. document.txt)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newFileContent,
                        onValueChange = { newFileContent = it },
                        label = { Text("File Content") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (newFileName.trim().isNotEmpty()) {
                        try {
                            val f = File(currentDir, newFileName.trim())
                            f.writeText(newFileContent)
                            Toast.makeText(context, "File created successfully", Toast.LENGTH_SHORT).show()
                            refreshCounter++
                        } catch (e: Exception) {
                            Toast.makeText(context, "Error creating file", Toast.LENGTH_SHORT).show()
                        }
                        showNewFileDialog = false
                    }
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showNewFileDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Confirmation Dialog for Multi-Selection Delete
    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("Delete Selected Items") },
            text = {
                Text(
                    "Move ${selectedItems.size} item(s) to the Recycle bin? " +
                        "You can restore them from there, and Undo is offered right after."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val payload = selectedItems.map { it.file }
                        trashItems(payload) {
                            isMultiSelectActive = false
                            selectedItems = emptySet()
                        }
                        showDeleteConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Single-item delete used to fire the moment the row was tapped in the actions
    // dialog, with no way back. Deleting a whole folder that way was unrecoverable.
    pendingSingleDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingSingleDelete = null },
            title = { Text("Delete ${target.name}?") },
            text = {
                Text(
                    if (target.isDirectory)
                        "This folder and everything inside it will be permanently deleted."
                    else
                        "The file moves to the Recycle bin, so you can restore it later."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val file = target.file
                        pendingSingleDelete = null
                        trashItems(listOf(file))
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingSingleDelete = null }) { Text("Cancel") }
            }
        )
    }

    // File Actions Dialog
    selectedFileForAction?.let { item ->
        FileActionsDialog(
            item = item,
            stats = if (item.isDirectory) {
                folderStatsMap[item.path]?.takeIf { it.dirMtime == item.lastModified }
            } else null,
            onDismiss = { selectedFileForAction = null },
            onCopy = {
                clipboardState = ClipboardState(listOf(item), ClipboardOp.COPY)
                Toast.makeText(context, "Copied ${item.name} to clipboard", Toast.LENGTH_SHORT).show()
                selectedFileForAction = null
            },
            onMove = {
                clipboardState = ClipboardState(listOf(item), ClipboardOp.MOVE)
                Toast.makeText(context, "Cut ${item.name} to clipboard", Toast.LENGTH_SHORT).show()
                selectedFileForAction = null
            },
            onRename = { newName ->
                val file = item.file
                selectedFileForAction = null
                val trimmed = newName.trim()
                val target = File(file.parentFile ?: return@FileActionsDialog, trimmed)
                when {
                    trimmed.isEmpty() || trimmed == "." || trimmed == ".." ->
                        Toast.makeText(context, "Enter a name", Toast.LENGTH_SHORT).show()
                    trimmed.contains('/') || trimmed.contains('\\') ->
                        Toast.makeText(context, "A name cannot contain / or \\", Toast.LENGTH_SHORT).show()
                    target.absolutePath != file.absolutePath && target.exists() ->
                        // renameFile now refuses to clobber; say why instead of "Rename failed".
                        Toast.makeText(
                            context,
                            "\"$trimmed\" already exists here",
                            Toast.LENGTH_SHORT
                        ).show()
                    else -> runFileOp("Rename") { StorageHelper.renameFile(file, trimmed) }
                }
            },
            onDelete = {
                // Deferred to a confirm dialog; this row used to delete immediately.
                selectedFileForAction = null
                pendingSingleDelete = item
            },
            onExtractText = {
                onExtractText(item.file)
                selectedFileForAction = null
            },
            onOpenTextEditor = if (isTextEditorFile(item)) {
                {
                    onOpenTextEditor(item.file)
                    selectedFileForAction = null
                }
            } else null,
            onOpenArchiveManager = if (isArchiveManagerFile(item)) {
                {
                    onOpenArchiveManager(listOf(item.file))
                    selectedFileForAction = null
                }
            } else null,
            runOp = ::runFileOp
        )
    }
}

/**
 * Subtitle for a file row.
 *
 * A directory reports how much it holds instead of the bare "Folder" it used to
 * show. The size is a lower bound whenever [FolderStats.partial] is set (the walk
 * hit its entry budget), so it is rendered with a "+" rather than quietly
 * under-reporting. [statsPending] only covers the window between the listing
 * appearing and its first measurement landing.
 */
private fun folderSubtitle(
    item: MediaItem,
    stats: FolderStats?,
    statsPending: Boolean,
    dateFormat: SimpleDateFormat
): String {
    if (!item.isDirectory) {
        return "${formatFileSize(item.sizeBytes)} • ${dateFormat.format(Date(item.lastModified))}"
    }
    if (stats == null) return if (statsPending) "Folder • measuring…" else "Folder"
    if (stats.items == 0) return "Folder • empty"
    val plus = if (stats.partial) "+" else ""
    val label = if (stats.items == 1) "1 item" else "${stats.items} items"
    val bytes = if (stats.bytes > 0L) formatFileSize(stats.bytes) + plus else plus.trim()
    return "$label • $bytes"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileRowItem(
    item: MediaItem,
    isSelected: Boolean,
    isMultiSelect: Boolean,
    stats: FolderStats? = null,
    statsPending: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMoreClick: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()) }
    val (icon, iconTint) = getFileIconAndTint(item)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isMultiSelect) {
                Checkbox(checked = isSelected, onCheckedChange = { onClick() })
                Spacer(modifier = Modifier.width(8.dp))
            }

            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(36.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = folderSubtitle(item, stats, statsPending, dateFormat),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            IconButton(onClick = onMoreClick) {
                Icon(AppIcons.MoreVert, contentDescription = "More")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileGridItem(
    item: MediaItem,
    isSelected: Boolean,
    isMultiSelect: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMoreClick: () -> Unit
) {
    val (icon, iconTint) = getFileIconAndTint(item)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                if (isMultiSelect) {
                    Checkbox(checked = isSelected, onCheckedChange = { onClick() }, modifier = Modifier.align(Alignment.TopStart))
                }
                IconButton(onClick = onMoreClick, modifier = Modifier.align(Alignment.TopEnd).size(24.dp)) {
                    Icon(AppIcons.MoreVert, contentDescription = "More", modifier = Modifier.size(16.dp))
                }
            }

            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(48.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = item.name,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = if (item.isDirectory) "Folder" else formatFileSize(item.sizeBytes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileCompactRowItem(
    item: MediaItem,
    isSelected: Boolean,
    isMultiSelect: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMoreClick: () -> Unit
) {
    val (icon, iconTint) = getFileIconAndTint(item)

    Surface(
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.extraSmall,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isMultiSelect) {
                Checkbox(checked = isSelected, onCheckedChange = { onClick() })
                Spacer(modifier = Modifier.width(6.dp))
            }

            Icon(imageVector = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(10.dp))

            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            Text(
                text = if (item.isDirectory) "Folder" else formatFileSize(item.sizeBytes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            IconButton(onClick = onMoreClick, modifier = Modifier.size(28.dp)) {
                Icon(AppIcons.MoreVert, contentDescription = "More", modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
fun FileActionsDialog(
    item: MediaItem,
    stats: FolderStats? = null,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onExtractText: (() -> Unit)? = null,
    onOpenTextEditor: (() -> Unit)? = null,
    onOpenArchiveManager: (() -> Unit)? = null,
    // Delegates a mutating operation to the screen's IO runner so archive
    // work never happens on the main thread just because it was started here.
    runOp: (label: String, block: () -> Boolean) -> Unit = { _, b -> b() }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showRenameInput by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf(item.name) }
    var convertingAudio by remember { mutableStateOf(false) }
    var audioProgress by remember { mutableFloatStateOf(0f) }
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }

    fun startAudioConversion() {
        if (convertingAudio) return
        val src = item.file
        if (!src.exists() || !src.canRead()) {
            Toast.makeText(context, "Cannot read this video file", Toast.LENGTH_LONG).show()
            return
        }
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "NJConverter")
        runCatching { dir.mkdirs() }
        val base = src.name.substringBeforeLast(".")
        var candidate = File(dir, "${base}_audio.m4a")
        var n = 1
        while (candidate.exists()) {
            candidate = File(dir, "${base}_audio($n).m4a")
            n++
        }
        val outPath = candidate.absolutePath
        convertingAudio = true
        audioProgress = 0f
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    MediaTranscoder.extractAudio(src.absolutePath, outPath, { p -> audioProgress = p }, { false })
                }
                MediaScannerConnection.scanFile(context, arrayOf(outPath), null, null)
                runCatching {
                    Toast.makeText(context, "Audio saved: ${File(outPath).name}", Toast.LENGTH_LONG).show()
                }
                onDismiss()
            } catch (e: ConverterCancelledException) {
                runCatching { Toast.makeText(context, "Conversion cancelled", Toast.LENGTH_SHORT).show() }
            } catch (e: UnsupportedOperationException) {
                runCatching { Toast.makeText(context, e.message ?: "Unsupported video format", Toast.LENGTH_LONG).show() }
            } catch (e: Exception) {
                runCatching { Toast.makeText(context, "Conversion failed: ${e.message}", Toast.LENGTH_LONG).show() }
            } finally {
                convertingAudio = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            if (showRenameInput) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("New Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            } else if (showDetails) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Path:", style = MaterialTheme.typography.labelMedium)
                    Text(item.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Type: ${if (item.isDirectory) "Directory Folder" else item.mimeType}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = when {
                            !item.isDirectory -> "Size: ${formatFileSize(item.sizeBytes)}"
                            // A folder's size is a measured total, so "N/A" is no
                            // longer the honest answer. Before the walk lands, say
                            // so rather than implying the folder is empty.
                            stats == null -> "Size: measuring…"
                            stats.bytes > 0L ->
                                "Size: ${formatFileSize(stats.bytes)}${if (stats.partial) "+" else ""}" +
                                    "  (${stats.items} items)"
                            else -> "Size: empty"
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text("Last Modified: ${dateFormat.format(Date(item.lastModified))}", style = MaterialTheme.typography.bodyMedium)
                }
            } else if (convertingAudio) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Converting video to audio…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    LinearProgressIndicator(progress = { audioProgress }, modifier = Modifier.fillMaxWidth())
                    Text(
                        "${(audioProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    // Convert video to audio
                    if (!item.isDirectory && item.mimeType.startsWith("video/")) {
                        TextButton(
                            onClick = ::startAudioConversion,
                            enabled = !convertingAudio,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(AppIcons.AudioFile, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Convert to Audio (M4A)", modifier = Modifier.weight(1f))
                        }
                    }

                    // Extract text from an image
                    if (!item.isDirectory && item.mimeType.startsWith("image/")) {
                        TextButton(
                            onClick = { onExtractText?.invoke() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(AppIcons.DocumentScanner, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Extract Text", modifier = Modifier.weight(1f))
                        }
                    }

                    // Copy
                    TextButton(onClick = onCopy, modifier = Modifier.fillMaxWidth()) {
                        Icon(AppIcons.ContentCopy, contentDescription = null)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Copy", modifier = Modifier.weight(1f))
                    }

                    // Move / Cut
                    TextButton(onClick = onMove, modifier = Modifier.fillMaxWidth()) {
                        Icon(AppIcons.ContentCut, contentDescription = null)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Move / Cut", modifier = Modifier.weight(1f))
                    }

                    if (onOpenTextEditor != null) {
                        TextButton(
                            onClick = {
                                onOpenTextEditor()
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(AppIcons.EditNote, contentDescription = null)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Open in Text Editor", modifier = Modifier.weight(1f))
                        }
                    }

                    if (onOpenArchiveManager != null) {
                        TextButton(
                            onClick = {
                                onOpenArchiveManager()
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(AppIcons.Archive, contentDescription = null)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Open in Archive Manager", modifier = Modifier.weight(1f))
                        }
                    }

                    // ZIP / Archive Operations
                    if (item.file.extension.lowercase() in listOf("zip", "rar", "tar", "gz", "tgz", "7z")) {
                        TextButton(
                            onClick = {
                                val archive = item.file
                                // Cheap main-thread check so an unsupported format
                                // still explains itself instead of reporting a
                                // generic failure from the IO worker.
                                val unsupported = StorageHelper.unsupportedArchiveReason(archive.extension)
                                if (unsupported != null) {
                                    Toast.makeText(context, unsupported, Toast.LENGTH_LONG).show()
                                } else {
                                    val extractDir = File(archive.parentFile, archive.nameWithoutExtension)
                                    runOp("Extract") {
                                        StorageHelper.extractArchive(archive, extractDir) == null
                                    }
                                }
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(AppIcons.Unarchive, contentDescription = null)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Extract Archive (Unzip)", modifier = Modifier.weight(1f))
                        }
                    } else if (!item.isDirectory) {
                        TextButton(
                            onClick = {
                                val src = item.file
                                val zipFile = File(src.parentFile, "${src.nameWithoutExtension}.zip")
                                runOp("Compress") { StorageHelper.zipFiles(listOf(src), zipFile) }
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(AppIcons.FolderZip, contentDescription = null)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Compress to ZIP", modifier = Modifier.weight(1f))
                        }
                    }

                    // Auto-Fix .bin file format
                    if (!item.isDirectory && item.file.extension.equals("bin", ignoreCase = true)) {
                        TextButton(
                            onClick = {
                                val fixed = StorageHelper.autoFixBinFile(item.file)
                                if (fixed != null) {
                                    Toast.makeText(context, "Renamed file to ${fixed.name}", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Could not determine format from file header", Toast.LENGTH_SHORT).show()
                                }
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(AppIcons.AutoFixHigh, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Auto-Fix File Extension", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.tertiary)
                        }
                    }

                    // Install APK
                    if (!item.isDirectory && item.file.extension.equals("apk", ignoreCase = true)) {
                        TextButton(
                            onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
                                    Toast.makeText(
                                        context,
                                        "Allow \"Install unknown apps\" for this app first",
                                        Toast.LENGTH_LONG
                                    ).show()
                                    runCatching {
                                        context.startActivity(
                                            Intent(
                                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                                Uri.parse("package:${context.packageName}")
                                            )
                                        )
                                    }
                                    onDismiss()
                                    return@TextButton
                                }
                                runCatching {
                                    val uri = FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        item.file
                                    )
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(uri, "application/vnd.android.package-archive")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(intent)
                                }.onFailure {
                                    Toast.makeText(context, "Cannot open APK installer", Toast.LENGTH_SHORT).show()
                                }
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(AppIcons.InstallMobile, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Install APK", modifier = Modifier.weight(1f))
                        }
                    }

                    if (!item.isDirectory) {
                        // Send via Bluetooth
                        //
                        // Deliberately does NOT reuse the chooser above: pairing
                        // and the transfer live behind the Bluetooth app's own
                        // OPP handler, so we aim at it directly and only fall back
                        // to the generic chooser when this ROM has no such
                        // activity. No BLUETOOTH permission is needed to *send* —
                        // only to discover/scan.
                        TextButton(
                            onClick = {
                                val uri = try {
                                    FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        item.file
                                    )
                                } catch (e: Exception) {
                                    null
                                }
                                if (uri == null) {
                                    Toast.makeText(
                                        context,
                                        "Cannot share this file",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    onDismiss()
                                    return@TextButton
                                }
                                // A FileProvider URI is required: Bluetooth reads
                                // the stream in OUR process, so `Uri.fromFile`
                                // would throw FileUriExposedException on API 24+.
                                val type = item.mimeType
                                    .takeIf { it.isNotBlank() }
                                    ?: "application/octet-stream"
                                val base = Intent(Intent.ACTION_SEND).apply {
                                    this.type = type
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    // ClipData is what actually carries the grant to
                                    // receivers that predate FLAG_GRANT_READ_URI_PERMISSION.
                                    clipData = android.content.ClipData.newRawUri(
                                        item.file.name,
                                        uri
                                    )
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                val explicit = ComponentName(
                                    "com.android.bluetooth",
                                    "com.android.bluetooth.opp.BluetoothOppLauncherActivity"
                                )
                                val launched = runCatching {
                                    val byPackage = Intent(base).setPackage("com.android.bluetooth")
                                    if (byPackage.resolveActivity(context.packageManager) != null) {
                                        context.startActivity(byPackage)
                                        true
                                    } else {
                                        val byComponent = Intent(base).setComponent(explicit)
                                        if (byComponent.resolveActivity(context.packageManager) != null) {
                                            context.startActivity(byComponent)
                                            true
                                        } else {
                                            false
                                        }
                                    }
                                }.getOrDefault(false)
                                if (!launched) {
                                    runCatching {
                                        context.startActivity(
                                            Intent.createChooser(base, "Send via Bluetooth")
                                        )
                                    }.onFailure {
                                        Toast.makeText(
                                            context,
                                            "No app to send the file with",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(AppIcons.Bluetooth, contentDescription = null)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Send via Bluetooth", modifier = Modifier.weight(1f))
                        }
                    }

                    // Rename
                    TextButton(onClick = { showRenameInput = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(AppIcons.Edit, contentDescription = null)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Rename", modifier = Modifier.weight(1f))
                    }

                    // File Details
                    TextButton(onClick = { showDetails = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(AppIcons.Info, contentDescription = null)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Properties & Details", modifier = Modifier.weight(1f))
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    // Delete
                    TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                        Icon(AppIcons.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Delete", color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = {
            if (showRenameInput) {
                Button(onClick = { onRename(newName) }) { Text("Save") }
            } else if (showDetails) {
                Button(onClick = { showDetails = false }) { Text("Back") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

fun getFileIconAndTint(item: MediaItem): Pair<androidx.compose.ui.graphics.vector.ImageVector, Color> {
    val ext = item.file.extension.lowercase()
    val mime = item.mimeType
    return when {
        item.isDirectory -> AppIcons.Folder to Color(0xFFF59E0B)
        mime.startsWith("image/") -> AppIcons.Image to Color(0xFF10B981)
        mime.startsWith("video/") -> AppIcons.Movie to Color(0xFF3B82F6)
        mime.startsWith("audio/") -> AppIcons.MusicNote to Color(0xFF8B5CF6)
        mime == "application/pdf" -> AppIcons.PictureAsPdf to Color(0xFFEF4444)
        mime == "application/vnd.android.package-archive" || ext == "apk" ||
            ext == "apks" || ext == "xapk" -> AppIcons.Android to Color(0xFF3DDC84)
        mime == "application/zip" -> AppIcons.FolderZip to Color(0xFFB45309)
        mime == "application/vnd.ms-excel" || mime == "text/comma-separated-values" ||
            ext == "xls" || ext == "xlsx" || ext == "csv" ->
            AppIcons.BarChart to Color(0xFF16A34A)
        mime == "application/vnd.ms-powerpoint" -> AppIcons.Slideshow to Color(0xFFD97706)
        mime == "application/msword" || mime == "application/rtf" ||
            mime == "application/vnd.oasis.opendocument" ->
            AppIcons.Description to Color(0xFF2563EB)
        mime == "application/epub+zip" || ext == "epub" ->
            AppIcons.MenuBook to Color(0xFF0D9488)
        mime == "text/subtitle" -> AppIcons.StickyNote2 to Color(0xFF6B7280)
        ext in CODE_EXTENSIONS -> AppIcons.Code to Color(0xFF64748B)
        ext == "html" || ext == "htm" -> AppIcons.Language to Color(0xFF0891B2)
        ext == "bin" || ext == "exe" || ext == "so" || ext == "elf" ->
            AppIcons.Memory to Color(0xFF78716C)
        mime.startsWith("text/") -> AppIcons.Description to Color(0xFF10B981)
        else -> AppIcons.InsertDriveFile to Color(0xFF6B7280)
    }
}

private val CODE_EXTENSIONS = setOf(
    "json", "xml", "kt", "kts", "java", "py", "cpp", "cc", "c", "h", "hpp",
    "cs", "go", "rs", "rb", "php", "swift", "js", "ts", "jsx", "tsx", "css",
    "scss", "sh", "bash", "zsh", "yml", "yaml", "gradle", "toml", "ini", "conf", "sql"
)


fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    if (bytes < 1024) return "$bytes B"
    val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt().coerceIn(1, 6)
    val pre = "KMGTPE"[exp - 1]
    return String.format(Locale.US, "%.1f %cB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
}

/** Circular donut storage-usage dialog, opened from the ⋮ Storage menu. */
@Composable
private fun StorageStatsDialog(onDismiss: () -> Unit) {
    data class Usage(val label: String, val total: Long, val free: Long)

    val context = LocalContext.current
    val usages = remember {
        val list = mutableListOf<Usage>()
        // Every browsable volume (internal shared storage + any SD card), so the SD
        // card is listed by its real mount instead of mislabelling internal storage.
        StorageHelper.getStorageRoots(context).forEach { root ->
            runCatching {
                val s = StatFs(root.root.absolutePath)
                Usage(root.label, s.totalBytes, s.availableBytes)
            }.getOrNull()?.let { list.add(it) }
        }
        // Rare device with no enumerable volume: fall back to the data partition.
        if (list.isEmpty()) {
            runCatching {
                val s = StatFs(Environment.getDataDirectory().absolutePath)
                Usage("Internal storage", s.totalBytes, s.availableBytes)
            }.getOrNull()?.let { list.add(it) }
        }
        list.distinctBy { it.total to it.free }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Storage Usage") },
        text = {
            if (usages.isEmpty()) {
                Text("Unable to read storage information.", fontSize = 14.sp)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    usages.forEach { u ->
                        val used = (u.total - u.free).coerceAtLeast(0)
                        val pct = if (u.total > 0) used.toFloat() / u.total.toFloat() else 0f
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            DonutGauge(pct = pct, label = "${(pct * 100).toInt()}%")
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = u.label,
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Text(
                                text = "${formatFileSize(used)} used · ${formatFileSize(u.free)} free of ${formatFileSize(u.total)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

/** Deterministic circular donut gauge drawn with a Canvas ring. */
@Composable
private fun DonutGauge(pct: Float, label: String, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    val sweep = (pct.coerceIn(0f, 1f) * 360f).toFloat()
    Box(
        modifier = modifier.size(120.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 14.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = track,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            if (sweep > 0f) {
                drawArc(
                    color = color,
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = color
        )
    }
}

/**
 * B9: pick a destination folder for a copy/move.
 *
 * Design notes that matter here:
 *  - The scan is **bounded** (depth + entry cap) and runs off the main thread with
 *    cooperative cancellation, exactly like `folderStats`. An unbounded walk of
 *    /storage/emulated/0 is how a file manager OOMs a low-RAM device, and the picker
 *    is opened *before* the user has even chosen where to paste.
 *  - A row's main tap **selects** that folder and closes; the trailing chevron
 *    **descends**. Two distinct intents on one row beats guessing from the tap
 *    count, and beats "tap to select, then a separate Open button" for one-handed use.
 *  - Rows are indented by depth and show a relative path, so two same-named
 *    folders in different trees are still distinguishable.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun DestinationPickerDialog(
    initial: File,
    onPick: (File) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var browseRoot by remember { mutableStateOf(initial) }
    var dirs by remember { mutableStateOf<List<File>>(emptyList()) }
    var scanning by remember { mutableStateOf(true) }
    var truncated by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    // Re-scan whenever the user descends/ascends. Cancelling the previous scan is
    // what keeps a fast user from stacking several full walks.
    LaunchedEffect(browseRoot) {
        scanning = true
        dirs = emptyList()
        truncated = false
        val root = browseRoot
        val found = withContext(Dispatchers.IO) {
            val all = StorageHelper.listDestinations(
                root = root,
                maxDepth = 5,
                cap = 2_000,
                isCancelled = { !isActive }
            )
            Pair(all, all.size >= 2_000)
        }
        dirs = found.first
        truncated = found.second
        scanning = false
    }

    val rootPath = browseRoot.absolutePath
    val shown = if (query.isBlank()) dirs else dirs.filter {
        it.absolutePath.contains(query, ignoreCase = true)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Choose destination")
                Text(
                    rootPath,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        text = {
            Column {
                AppSearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "Search folders",
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                // Go up one level, unless already at a volume root.
                val storageRootPaths = remember(context) {
                    StorageHelper.getStorageRoots(context).map { it.root.absolutePath }.toSet()
                }
                val parentFile = browseRoot.parentFile
                if (parentFile != null && parentFile.absolutePath !in storageRootPaths) {
                    TextButton(onClick = { browseRoot = parentFile }) {
                        Icon(AppIcons.KeyboardArrowUp, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Up one level", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (scanning) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                } else if (shown.isEmpty()) {
                    Text(
                        if (query.isBlank()) "No sub-folders here" else "No folder matches that search",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 18.dp)
                    )
                } else {
                    LazyColumn(Modifier.heightIn(max = 340.dp)) {
                        items(shown) { dir ->
                            val depth = dir.absolutePath.removePrefix(rootPath)
                                .count { it == '/' }
                            val isSelf = dir.absolutePath == rootPath
                            val hasKids = runCatching {
                                dir.listFiles()?.any { it.isDirectory } == true
                            }.getOrDefault(false)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(dir) }
                                    .padding(start = (depth * 12).dp, top = 8.dp, bottom = 8.dp)
                            ) {
                                Icon(
                                    AppIcons.Folder, null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    dir.name.ifBlank { dir.absolutePath },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                if (hasKids && !isSelf) {
                                    IconButton(
                                        onClick = { browseRoot = dir },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            AppIcons.KeyboardArrowRight, "Open ${dir.name}",
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (truncated) {
                        Text(
                            "Showing the first ${dirs.size} folders — narrow the search to reach others.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(browseRoot) }) { Text("Use this folder") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/** Picker for the browsable storage volumes (internal storage / SD card). */
@Composable
private fun StorageLocationsDialog(
    roots: List<StorageRoot>,
    onPick: (StorageRoot) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(AppIcons.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        title = { Text("Storage locations") },
        text = {
            if (roots.isEmpty()) {
                Text(
                    "No storage volumes available.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    roots.forEach { r ->
                        key(r.root.absolutePath) {
                            val stats = remember(r.root.absolutePath) {
                                runCatching {
                                    val s = StatFs(r.root.absolutePath)
                                    s.totalBytes to s.availableBytes
                                }.getOrNull()
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.medium)
                                    .clickable { onPick(r) }
                                    .padding(horizontal = 6.dp, vertical = 10.dp)
                            ) {
                                Icon(
                                    if (r.isRemovable) AppIcons.SdStorage else AppIcons.Storage,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        r.label,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        r.root.absolutePath,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    stats?.let { (total, free) ->
                                        Text(
                                            "${formatFileSize(free)} free of ${formatFileSize(total)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Icon(
                                    AppIcons.ChevronRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}
