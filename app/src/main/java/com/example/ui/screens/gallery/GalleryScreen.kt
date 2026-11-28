package com.example.ui.screens.gallery

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.ui.components.landscapeContentWidth
import com.example.ui.components.AppIcons
import com.example.ui.components.AppSearchField
import com.example.ui.components.FloatingTopBar
import com.example.ui.screens.media.ImageViewerScreen
import com.example.util.rememberSavedGridState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class GalleryItem(
    val uri: Uri,
    val name: String,
    val size: Long,
    val mimeType: String,
    val dateAdded: Long,
    val dateModified: Long
)

private data class GalleryFolder(
    val id: String,
    val name: String,
    val relativePath: String,
    val items: List<GalleryItem>
) {
    val coverUri: Uri?
        get() = items.maxByOrNull { it.dateAdded }?.uri
}

private enum class PhotoSort(val label: String) {
    NEWEST("Newest first"),
    OLDEST("Oldest first"),
    NAME("Name A-Z"),
    SIZE("Largest first")
}

private enum class FolderSort(val label: String) {
    NAME("Name"),
    COUNT("Photo count"),
    NEWEST("Newest activity")
}

private object GalleryFavorites {
    private const val PREFS = "gallery_favorites"
    private const val KEY = "fav_uris"

    fun get(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY, emptySet()) ?: emptySet()

    fun isFavorite(context: Context, uri: Uri): Boolean = uri.toString() in get(context)

    fun toggle(context: Context, uri: Uri): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = (prefs.getStringSet(KEY, emptySet()) ?: emptySet()).toMutableSet()
        val key = uri.toString()
        val added = if (key in current) {
            current.remove(key)
            false
        } else {
            current.add(key)
            true
        }
        prefs.edit().putStringSet(KEY, current).apply()
        return added
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(
    viewModel: com.example.ui.MainViewModel,
    onBack: () -> Unit,
    /**
     * Reports whether the full-screen photo viewer is up. MainActivity uses it
     * to drop the Scaffold inset for this screen while the viewer is shown, so
     * the viewer (and its floating bars) run edge-to-edge instead of sitting
     * below a theme-coloured status/navigation strip — see MainActivity's
     * `immersiveContentPadding`.
     */
    onViewerVisibleChange: (Boolean) -> Unit = {},
    onEditImage: ((Uri, String) -> Unit)? = null
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    var refreshKey by remember { mutableStateOf(0) }

    val hasMediaPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED

    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        refreshKey++
    }

    LaunchedEffect(Unit) {
        if (!hasMediaPermission) mediaPermissionLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES)
    }

    val foldersState = rememberSavedGridState("gallery_folders", viewModel)

    val folders by produceState<List<GalleryFolder>>(
        initialValue = emptyList(),
        context, refreshKey, hasMediaPermission
    ) {
        value = if (hasMediaPermission) loadGalleryFolders(context) else emptyList()
    }

    var currentFolderId by rememberSaveable { mutableStateOf<String?>(null) }
    val currentFolder = folders.firstOrNull { it.id == currentFolderId }
    val photosState = rememberSavedGridState("gallery_photos_${currentFolder?.id}", viewModel)
    var photoSort by remember { mutableStateOf(PhotoSort.NEWEST) }
    var folderSort by remember { mutableStateOf(FolderSort.COUNT) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var selectedFolders by remember { mutableStateOf(setOf<String>()) }
    var viewingIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var slideshowIntervalMs by remember { mutableStateOf<Long?>(null) }
    var showFolderSortMenu by remember { mutableStateOf(false) }
    var showPhotoSortMenu by remember { mutableStateOf(false) }
    var showPhotoSearch by remember { mutableStateOf(false) }
    var showSlideshowDialog by remember { mutableStateOf(false) }
    var showCopyDialog by remember { mutableStateOf(false) }
    var showFolderCopyDialog by remember { mutableStateOf(false) }
    var copyIsMove by remember { mutableStateOf(false) }
    var folderCopyIsMove by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showFolderDeleteConfirm by remember { mutableStateOf(false) }
    /** Photo whose long-press action sheet is open (grid-level, inside a folder). */
    var actionSheetItem by remember { mutableStateOf<GalleryItem?>(null) }
    /** When set, the confirm dialog deletes just this photo instead of the selection. */
    var singleDeleteTarget by remember { mutableStateOf<GalleryItem?>(null) }
    /** Uris awaiting the system delete-consent dialog; empty = nothing in flight. */
    var pendingDeleteUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    /** True when the in-flight delete was started from the full-screen viewer. */
    var deleteFromViewer by remember { mutableStateOf(false) }

    val favorites by remember(refreshKey) { mutableStateOf(GalleryFavorites.get(context)) }

    val allItems = remember(folders) { folders.flatMap { it.items } }
    val favoriteItems = remember(folders, favorites) { allItems.filter { it.uri.toString() in favorites } }

    val allFolder = remember(allItems) {
        GalleryFolder("all", "All Photos", "", allItems)
    }
    val favoriteFolder = remember(favoriteItems) {
        GalleryFolder("favorites", "Favorites", "", favoriteItems)
    }

    val currentItems = remember(currentFolder, query, photoSort) {
        val base = when (currentFolder?.id) {
            "all" -> allItems
            "favorites" -> favoriteItems
            else -> currentFolder?.items ?: emptyList()
        }
        val filtered = if (query.isBlank()) base else base.filter { it.name.contains(query.trim(), ignoreCase = true) }
        when (photoSort) {
            PhotoSort.NEWEST -> filtered.sortedByDescending { it.dateAdded }
            PhotoSort.OLDEST -> filtered.sortedBy { it.dateAdded }
            PhotoSort.NAME -> filtered.sortedBy { it.name.lowercase() }
            PhotoSort.SIZE -> filtered.sortedByDescending { it.size }
        }
    }

    val selectionUris = remember(selected) { selected.map { Uri.parse(it) } }

    // URIs of every image inside the currently-selected folders (for folder-level
    // multi-select copy/move/delete/share).
    val folderSelectionUris = remember(selectedFolders, folders) {
        folders.filter { it.id in selectedFolders }
            .flatMap { it.items }
            .map { it.uri }
    }

    fun clearSelection() {
        selected = emptySet()
        selectedFolders = emptySet()
    }
    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    // Android 11+ needs the user's consent for every MediaStore delete. Routing
    // MediaStore.createDeleteRequest(...) through StartIntentSenderForResult
    // matters here: PendingIntent.send() is a blocking call, so the whole UI
    // froze behind the system dialog — and deleting is now a one-tap action
    // from a long-press. There is no DeleteRequest contract in
    // androidx.activity:1.10.1, so this is the non-blocking equivalent.
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val uris = pendingDeleteUris
        val fromViewer = deleteFromViewer
        pendingDeleteUris = emptyList()
        deleteFromViewer = false
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            refreshKey++
            toast(
                if (uris.size == 1) "Image deleted"
                else "${uris.size} images deleted"
            )
            // The viewer is showing a photo that no longer exists; leave it so
            // the pager index can't dangle on a deleted item.
            if (fromViewer) {
                viewingIndex = null
                slideshowIntervalMs = null
            }
        } else {
            toast("Nothing was deleted")
        }
        clearSelection()
    }

    /**
     * Delete one or more gallery photos.
     *
     * @param fromViewer when true, a successful delete also closes the
     *   full-screen viewer (the current photo is gone).
     */
    fun deleteUris(uris: List<Uri>, fromViewer: Boolean = false) {
        if (uris.isEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pendingDeleteUris = uris
            deleteFromViewer = fromViewer
            runCatching {
                deleteLauncher.launch(
                    androidx.activity.result.IntentSenderRequest.Builder(
                        MediaStore.createDeleteRequest(context.contentResolver, uris).intentSender
                    ).build()
                )
            }.onFailure {
                pendingDeleteUris = emptyList()
                deleteFromViewer = false
                toast("Unable to request deletion")
            }
        } else {
            val resolver = context.contentResolver
            var ok = true
            for (u in uris) {
                val r = runCatching { resolver.delete(u, null, null) }.getOrDefault(0)
                if (r <= 0) ok = false
            }
            toast(if (ok) "Images deleted" else "Some images could not be deleted")
            refreshKey++
            clearSelection()
            if (fromViewer) {
                viewingIndex = null
                slideshowIntervalMs = null
            }
        }
    }

    fun copyToFolder(uris: List<Uri>, destPath: String, move: Boolean) {
        val resolver = context.contentResolver
        var ok = true
        var copied = 0
        for (u in uris) {
            val item = allItems.firstOrNull { it.uri.toString() == u.toString() } ?: continue
            if (insertIntoFolder(context, resolver, item, destPath)) {
                copied++
                if (move) runCatching { resolver.delete(u, null, null) }
            } else {
                ok = false
            }
        }
        toast(if (ok) "$copied image(s) ${if (move) "moved" else "copied"}" else "Some images failed")
        refreshKey++
        clearSelection()
    }

    fun toggleFavorites(uris: List<Uri>) {
        var count = 0
        uris.forEach { if (GalleryFavorites.toggle(context, it)) count++ }
        toast("$count image(s) added to Favorites")
        refreshKey++
        clearSelection()
    }

    val sortedFolders = remember(folders, favorites, favoriteItems, folderSort) {
        val base = folders.filter { it.id != "all" && it.id != "favorites" }
        when (folderSort) {
            FolderSort.NAME -> base.sortedBy { it.name.lowercase() }
            FolderSort.COUNT -> base.sortedByDescending { it.items.size }
            FolderSort.NEWEST -> base.sortedByDescending { it.items.maxOfOrNull { i -> i.dateAdded } ?: 0L }
        }
    }

    BackHandler {
        when {
            viewingIndex != null -> {
                viewingIndex = null
                slideshowIntervalMs = null
            }
            currentFolder != null -> currentFolderId = null
            selected.isNotEmpty() || selectedFolders.isNotEmpty() -> clearSelection()
            else -> {
                currentFolderId = null
                viewingIndex = null
                onBack()
            }
        }
    }

    // Tell the host when the full-screen viewer takes over, so it can switch
    // this screen to edge-to-edge (and back) — the grid itself keeps the
    // Scaffold inset, only the viewer escapes it. SideEffect (not LaunchedEffect)
    // so the flag flips in the same apply phase as the state change, before the
    // frame is drawn.
    SideEffect { onViewerVisibleChange(viewingIndex != null) }

    // ---------------- Delete confirmation (shared by the grid and the viewer) ----------------
    // Kept at the top level (not inside the folder branch) so the full-screen
    // viewer can trigger it too — the viewer itself only owns the file-path
    // dialog, and the gallery passes a placeholder File that can't be deleted.
    if (showDeleteConfirm) {
        val single = singleDeleteTarget
        val uris = if (single != null) listOf(single.uri) else selectionUris
        val label = if (single != null) "\"${single.name}\"" else "${uris.size} selected image(s)"
        fun closeConfirm() {
            showDeleteConfirm = false
            singleDeleteTarget = null
            deleteFromViewer = false
        }
        AlertDialog(
            onDismissRequest = { closeConfirm() },
            title = { Text("Delete Image${if (single == null) "s" else ""}") },
            text = { Text("Delete $label? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    val fromViewer = deleteFromViewer
                    closeConfirm()
                    deleteUris(uris, fromViewer)
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { closeConfirm() }) { Text("Cancel") }
            }
        )
    }

    // ---------------- Full-screen viewer ----------------
    if (viewingIndex != null && currentItems.isNotEmpty()) {
        val safeIndex = viewingIndex!!.coerceIn(0, currentItems.size - 1)
        val currentItem = currentItems[safeIndex]
        ImageViewerScreen(
            imageFile = File(currentItem.name),
            imageUri = currentItem.uri,
            galleryItems = currentItems.map { it.name to it.uri },
            initialIndex = safeIndex,
            onIndexChanged = { viewingIndex = it },
            positionLabel = "${safeIndex + 1} of ${currentItems.size}",
            slideshowIntervalMs = slideshowIntervalMs,
            onEdit = onEditImage?.let { edit -> { edit(currentItem.uri, currentItem.name) } },
            onDeleteImage = { uri ->
                // Hand the decision back to this screen: the confirm dialog and
                // the MediaStore consent prompt both belong to the gallery.
                val target = currentItems.firstOrNull { it.uri == uri }
                if (target == null) {
                    toast("Photo is no longer available")
                } else {
                    singleDeleteTarget = target
                    deleteFromViewer = true
                    showDeleteConfirm = true
                }
            },
            onBack = {
                viewingIndex = null
                slideshowIntervalMs = null
            }
        )
        return
    }

    // ---------------- Photo grid (inside a folder) ----------------
    if (currentFolder != null) {
        Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            // FloatingTopBar (rounded card, 12.dp side margins) instead of the
            // full-width flush Surface this screen used to draw.
            val useGlassmorphism by viewModel.useGlassmorphism.collectAsState()
            FloatingTopBar(useGlass = useGlassmorphism) {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = {
                            if (selected.isNotEmpty()) clearSelection() else currentFolderId = null
                        }) {
                            Icon(
                                if (selected.isNotEmpty()) AppIcons.Close else AppIcons.ArrowBack,
                                contentDescription = if (selected.isNotEmpty()) "Exit selection" else "Back"
                            )
                        }
                        Text(
                            text = if (selected.isNotEmpty()) "${selected.size} selected" else currentFolder!!.name,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (selected.isNotEmpty()) {
                            IconButton(onClick = {
                                selected = currentItems.map { it.uri.toString() }.toSet()
                            }) {
                                Icon(AppIcons.SelectAll, contentDescription = "Select all")
                            }
                        } else {
                            IconButton(onClick = { showSlideshowDialog = true }) {
                                Icon(AppIcons.Slideshow, contentDescription = "Slideshow")
                            }
                            IconButton(onClick = { showPhotoSearch = !showPhotoSearch }) {
                                Icon(
                                    AppIcons.Search,
                                    contentDescription = "Search",
                                    tint = if (showPhotoSearch || query.isNotBlank()) MaterialTheme.colorScheme.primary
                                    else LocalContentColor.current
                                )
                            }
                            IconButton(onClick = { showPhotoSortMenu = true }) {
                                Icon(AppIcons.Sort, contentDescription = "Sort")
                            }
                        }
                    }
                    if (showPhotoSearch) {
                        AnimatedVisibility(visible = showPhotoSearch, enter = fadeIn(), exit = fadeOut()) {
                            AppSearchField(
                                value = query,
                                onValueChange = { query = it },
                                placeholder = "Search photos…",
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            if (currentItems.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (query.isBlank()) "No photos in this folder" else "No matches", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyVerticalGrid(
                    state = photosState,
                    columns = GridCells.Adaptive(minSize = 110.dp),
                    contentPadding = PaddingValues(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize().landscapeContentWidth()
                ) {
                    items(currentItems, key = { it.uri.toString() }) { item ->
                        val isSelected = item.uri.toString() in selected
                        val isFav = item.uri.toString() in favorites
                        Box(
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clip(MaterialTheme.shapes.small)
                                .combinedClickable(
                                    onClick = {
                                        if (selected.isNotEmpty()) {
                                            selected = if (isSelected) selected - item.uri.toString() else selected + item.uri.toString()
                                        } else {
                                            viewingIndex = currentItems.indexOf(item)
                                        }
                                    },
                                    onLongClick = {
                                        // Long-press opens the per-photo action
                                        // sheet (Delete, Share, Copy, …) instead of
                                        // dropping straight into selection mode.
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        actionSheetItem = item
                                    }
                                )
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f) else Color.Transparent
                                )
                        ) {
                            AsyncImage(
                                model = item.uri,
                                contentDescription = item.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            if (isFav) {
                                Icon(
                                    AppIcons.Star,
                                    contentDescription = null,
                                    tint = Color(0xFFFFC107),
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(4.dp)
                                        .size(18.dp)
                                )
                            }
                            if (isSelected) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(6.dp)
                                        .size(22.dp)
                                ) {
                                    Icon(
                                        AppIcons.Check,
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.padding(2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Selection action bar
            AnimatedVisibility(visible = selected.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.inverseSurface, tonalElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Text(
                            "${selected.size}",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.inverseOnSurface
                        )
                        IconButton(onClick = {
                            showCopyDialog = true
                            copyIsMove = false
                        }) {
                            Icon(AppIcons.ContentCopy, contentDescription = "Copy", tint = MaterialTheme.colorScheme.inverseOnSurface)
                        }
                        IconButton(onClick = {
                            showCopyDialog = true
                            copyIsMove = true
                        }) {
                            Icon(AppIcons.DriveFileMove, contentDescription = "Move", tint = MaterialTheme.colorScheme.inverseOnSurface)
                        }
                        IconButton(onClick = { toggleFavorites(selectionUris) }) {
                            Icon(AppIcons.StarBorder, contentDescription = "Favorite", tint = MaterialTheme.colorScheme.inverseOnSurface)
                        }
                        IconButton(onClick = { singleDeleteTarget = null; showDeleteConfirm = true }) {
                            Icon(AppIcons.Delete, contentDescription = "Delete", tint = Color(0xFFFF5252))
                        }
                        IconButton(onClick = { clearSelection() }) {
                            Icon(AppIcons.Close, contentDescription = "Close selection", tint = MaterialTheme.colorScheme.inverseOnSurface)
                        }
                    }
                }
            }

            DropdownMenu(expanded = showPhotoSortMenu, onDismissRequest = { showPhotoSortMenu = false }) {
                PhotoSort.values().forEach { s ->
                    DropdownMenuItem(
                        text = { Text(s.label, fontSize = 14.sp) },
                        onClick = {
                            photoSort = s
                            showPhotoSortMenu = false
                        }
                    )
                }
            }

            if (showSlideshowDialog) {
                AlertDialog(
                    onDismissRequest = { showSlideshowDialog = false },
                    title = { Text("Slideshow") },
                    text = {
                        Column {
                            listOf(2000L to "2 seconds", 3000L to "3 seconds", 5000L to "5 seconds", 10000L to "10 seconds")
                                .forEach { (ms, label) ->
                                    TextButton(onClick = {
                                        slideshowIntervalMs = ms
                                        viewingIndex = 0
                                        showSlideshowDialog = false
                                    }) { Text(label) }
                                }
                        }
                    },
                    confirmButton = {},
                    dismissButton = {
                        TextButton(onClick = { showSlideshowDialog = false }) { Text("Cancel") }
                    }
                )
            }

            // ---------------- Long-press action sheet ----------------
            if (actionSheetItem != null) {
                val sheet = actionSheetItem!!
                val sheetIsFav = sheet.uri.toString() in favorites
                @Composable
                fun sheetRow(
                    icon: androidx.compose.ui.graphics.vector.ImageVector,
                    label: String,
                    tint: Color = MaterialTheme.colorScheme.onSurface,
                    action: () -> Unit
                ) {
                    Surface(
                        color = Color.Transparent,
                        onClick = {
                            actionSheetItem = null
                            action()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.padding(horizontal = 20.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
                            Text(
                                label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = tint,
                                modifier = Modifier.padding(start = 18.dp)
                            )
                        }
                    }
                }
                ModalBottomSheet(
                    onDismissRequest = { actionSheetItem = null },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                ) {
                    // "Select" keeps long-press as a way into multi-select.
                    sheetRow(AppIcons.CheckCircle, "Select for multi-edit") {
                        selected = selected + sheet.uri.toString()
                    }
                    sheetRow(AppIcons.ContentCopy, "Copy to folder…") {
                        selected = selected + sheet.uri.toString()
                        copyIsMove = false
                        showCopyDialog = true
                    }
                    sheetRow(AppIcons.DriveFileMove, "Move to folder…") {
                        selected = selected + sheet.uri.toString()
                        copyIsMove = true
                        showCopyDialog = true
                    }
                    sheetRow(
                        if (sheetIsFav) AppIcons.Star else AppIcons.StarBorder,
                        if (sheetIsFav) "Remove from Favorites" else "Add to Favorites",
                        tint = if (sheetIsFav) Color(0xFFFFC107) else MaterialTheme.colorScheme.onSurface
                    ) { toggleFavorites(listOf(sheet.uri)) }
                    sheetRow(AppIcons.Info, "Details") {
                        viewingIndex = currentItems.indexOf(sheet)
                    }
                    sheetRow(
                        AppIcons.Delete,
                        // Never delete one photo while N are selected: if a
                        // multi-selection exists, Delete means the whole
                        // selection — and the row says so before you tap it.
                        if (selected.isEmpty()) "Delete"
                        else "Delete ${selected.size} selected image(s)",
                        tint = MaterialTheme.colorScheme.error
                    ) {
                        singleDeleteTarget = if (selected.isEmpty()) sheet else null
                        deleteFromViewer = false
                        showDeleteConfirm = true
                    }
                    Spacer(
                        Modifier.navigationBarsPadding().height(8.dp)
                    )
                }
            }

            if (showCopyDialog) {
                val destFolders = remember(folders, currentFolder) {
                    folders.filter { it.id != currentFolder?.id && it.id != "all" && it.id != "favorites" }
                }
                AlertDialog(
                    onDismissRequest = { showCopyDialog = false },
                    title = { Text(if (copyIsMove) "Move to folder" else "Copy to folder") },
                    text = {
                        if (destFolders.isEmpty()) {
                            Text("No other folders available.", fontSize = 14.sp)
                        } else {
                            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                                items(destFolders, key = { it.id }) { folder ->
                                    TextButton(
                                        onClick = {
                                            val dest = folder.relativePath.ifBlank { folder.name }
                                            copyToFolder(selectionUris, dest, copyIsMove)
                                            showCopyDialog = false
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("${folder.name}  (${folder.items.size})", fontSize = 14.sp)
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {},
                    dismissButton = {
                        TextButton(onClick = { showCopyDialog = false }) { Text("Cancel") }
                    }
                )
            }
        }
        return
    }

    // ---------------- Folder grid (level 1) ----------------
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val entries = listOf(
            allFolder to allItems.size,
            favoriteFolder to favoriteItems.size
        ) + sortedFolders.map { it to it.items.size }

        Column(modifier = Modifier.fillMaxSize()) {
            // Folder-selection mode header
            if (selectedFolders.isNotEmpty()) {
                AnimatedVisibility(visible = selectedFolders.isNotEmpty()) {
                    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { clearSelection() }) {
                                Icon(AppIcons.Close, contentDescription = "Exit selection")
                            }
                            Text(
                                text = "${selectedFolders.size} selected",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            IconButton(onClick = { selectedFolders = entries.map { it.first.id }.toSet() }) {
                                Icon(AppIcons.SelectAll, contentDescription = "Select all")
                            }
                            IconButton(onClick = { showFolderSortMenu = true }) {
                                Icon(AppIcons.Sort, contentDescription = "Sort folders")
                            }
                        }
                    }
                }
            } else {
                Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.background) {
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { showFolderSortMenu = true }) {
                            Icon(AppIcons.Sort, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Sort", fontSize = 14.sp)
                        }
                    }
                }
            }

            if (entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(AppIcons.PhotoLibrary, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        if (!hasMediaPermission) {
                            Text(
                                "Allow photos & media access to show your gallery.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 24.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = {
                                mediaPermissionLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES)
                            }) { Text("Allow Access") }
                        } else {
                            Text("No photos found on this device", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    state = foldersState,
                    columns = GridCells.Adaptive(minSize = 150.dp),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize().landscapeContentWidth()
                ) {
                    items(entries, key = { it.first.id }) { (folder, count) ->
                        val isSel = folder.id in selectedFolders
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        if (selectedFolders.isNotEmpty()) {
                                            selectedFolders =
                                                if (isSel) selectedFolders - folder.id else selectedFolders + folder.id
                                        } else {
                                            currentFolderId = folder.id
                                        }
                                    },
                                    onLongClick = {
                                        selectedFolders =
                                            if (isSel) selectedFolders - folder.id else selectedFolders + folder.id
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    }
                                )
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1.3f)
                            ) {
                                val cover = folder.coverUri
                                if (cover != null) {
                                    AsyncImage(
                                        model = cover,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Icon(AppIcons.Photo, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                if (isSel) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                                    )
                                    Icon(
                                        AppIcons.CheckCircle,
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(8.dp)
                                            .size(26.dp)
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color.Black.copy(alpha = 0.45f))
                                        .align(Alignment.BottomCenter)
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Column {
                                        Text(
                                            folder.name,
                                            color = Color.White,
                                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text("$count photo(s)", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Folder-selection bottom action bar
            AnimatedVisibility(visible = selectedFolders.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.inverseSurface, tonalElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .navigationBarsPadding(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Text(
                            "${selectedFolders.size}",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.inverseOnSurface
                        )
                        IconButton(onClick = {
                            showFolderCopyDialog = true
                            folderCopyIsMove = false
                        }) {
                            Icon(AppIcons.ContentCopy, contentDescription = "Copy", tint = MaterialTheme.colorScheme.inverseOnSurface)
                        }
                        IconButton(onClick = {
                            showFolderCopyDialog = true
                            folderCopyIsMove = true
                        }) {
                            Icon(AppIcons.DriveFileMove, contentDescription = "Move", tint = MaterialTheme.colorScheme.inverseOnSurface)
                        }
                        IconButton(onClick = { toggleFavorites(folderSelectionUris) }) {
                            Icon(AppIcons.StarBorder, contentDescription = "Favorite", tint = MaterialTheme.colorScheme.inverseOnSurface)
                        }
                        IconButton(onClick = { showFolderDeleteConfirm = true }) {
                            Icon(AppIcons.Delete, contentDescription = "Delete", tint = Color(0xFFFF5252))
                        }
                        IconButton(onClick = { clearSelection() }) {
                            Icon(AppIcons.Close, contentDescription = "Close selection", tint = MaterialTheme.colorScheme.inverseOnSurface)
                        }
                    }
                }
            }
        }

        DropdownMenu(expanded = showFolderSortMenu, onDismissRequest = { showFolderSortMenu = false }) {
            FolderSort.values().forEach { s ->
                DropdownMenuItem(
                    text = { Text(s.label, fontSize = 14.sp) },
                    onClick = {
                        folderSort = s
                        showFolderSortMenu = false
                    }
                )
            }
        }

        if (showFolderCopyDialog) {
            val destFolders = remember(folders, selectedFolders) { folders.filter { it.id !in selectedFolders } }
            AlertDialog(
                onDismissRequest = { showFolderCopyDialog = false },
                title = { Text(if (folderCopyIsMove) "Move photos to folder" else "Copy photos to folder") },
                text = {
                    if (destFolders.isEmpty()) {
                        Text("No other folders available.", fontSize = 14.sp)
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                            items(destFolders, key = { it.id }) { folder ->
                                TextButton(
                                    onClick = {
                                        val dest = if (folder.relativePath.isNotBlank()) folder.relativePath else folder.name
                                        copyToFolder(folderSelectionUris, dest, folderCopyIsMove)
                                        showFolderCopyDialog = false
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("${folder.name}  (${folder.items.size})", fontSize = 14.sp)
                                }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showFolderCopyDialog = false }) { Text("Cancel") }
                }
            )
        }

        if (showFolderDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showFolderDeleteConfirm = false },
                title = { Text("Delete Photos") },
                text = { Text("Delete all photos in the ${selectedFolders.size} selected folder(s)? This cannot be undone.") },
                confirmButton = {
                    TextButton(onClick = {
                        showFolderDeleteConfirm = false
                        deleteUris(folderSelectionUris)
                    }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { showFolderDeleteConfirm = false }) { Text("Cancel") }
                }
            )
        }
    }
}

private suspend fun loadGalleryFolders(context: Context): List<GalleryFolder> = withContext(Dispatchers.IO) {
    val map = LinkedHashMap<String, MutableList<GalleryItem>>()
    val bucketNames = HashMap<String, String>()
    val bucketPaths = HashMap<String, String>()

    val projection = mutableListOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.BUCKET_ID,
        MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        MediaStore.Images.Media.SIZE,
        MediaStore.Images.Media.MIME_TYPE,
        MediaStore.Images.Media.DATE_ADDED,
        MediaStore.Images.Media.DATE_MODIFIED
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) projection.add(MediaStore.Images.Media.RELATIVE_PATH)

    val collection =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    runCatching {
        context.contentResolver.query(collection, projection.toTypedArray(), null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val bucketIdCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val bucketNameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val addedCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val modifiedCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val pathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) c.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH) else -1

            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val name = c.getString(nameCol) ?: "image_$id"
                val bucketId = c.getString(bucketIdCol) ?: "unknown"
                val bucketName = c.getString(bucketNameCol)?.takeIf { it.isNotBlank() } ?: "Unknown"
                val size = if (c.isNull(sizeCol)) 0L else c.getLong(sizeCol)
                val mime = c.getString(mimeCol) ?: "image/*"
                val added = if (c.isNull(addedCol)) 0L else c.getLong(addedCol)
                val modified = if (c.isNull(modifiedCol)) 0L else c.getLong(modifiedCol)
                val relPath = if (pathCol >= 0 && !c.isNull(pathCol)) c.getString(pathCol) ?: "" else ""

                map.getOrPut(bucketId) { mutableListOf() }.add(
                    GalleryItem(ContentUris.withAppendedId(collection, id), name, size, mime, added, modified)
                )
                bucketNames[bucketId] = bucketName
                if (relPath.isNotBlank() && bucketPaths[bucketId].isNullOrBlank()) bucketPaths[bucketId] = relPath
            }
        }
    }

    map.map { (id, items) ->
        GalleryFolder(id, bucketNames[id] ?: "Unknown", bucketPaths[id] ?: "", items)
    }.sortedBy { it.name.lowercase() }
}

private fun insertIntoFolder(context: Context, resolver: ContentResolver, item: GalleryItem, destPath: String): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, item.name)
                put(MediaStore.Images.Media.MIME_TYPE, item.mimeType)
                put(MediaStore.Images.Media.RELATIVE_PATH, destPath)
                put(MediaStore.Images.Media.DATE_ADDED, item.dateAdded)
                put(MediaStore.Images.Media.DATE_MODIFIED, item.dateModified)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val newUri = resolver.insert(collection, values) ?: return false
            val ok = resolver.openInputStream(item.uri)?.use { ins ->
                resolver.openOutputStream(newUri)?.use { outs ->
                    ins.copyTo(outs)
                    true
                } ?: false
            } ?: false
            if (!ok) {
                resolver.delete(newUri, null, null)
                return false
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(newUri, values, null, null)
            true
        }.getOrElse { false }
    } else {
        runCatching {
            val destDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), destPath.trimEnd('/'))
            if (!destDir.exists() && !destDir.mkdirs()) return@runCatching false
            val outFile = File(destDir, item.name)
            resolver.openInputStream(item.uri)?.use { ins -> outFile.outputStream().use { outs -> ins.copyTo(outs) } } ?: return@runCatching false
            MediaScannerConnection.scanFile(context, arrayOf(outFile.absolutePath), null, null)
            true
        }.getOrElse { false }
    }
}
