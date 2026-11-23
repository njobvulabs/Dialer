package com.example.ui.screens.notes

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.example.ui.components.AppSearchField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.ui.components.AppIcons
import com.example.ui.theme.AppCornerRadius
import com.example.data.db.NoteEntity
import com.example.ui.MainViewModel
import com.example.ui.components.FloatingTopBar
import com.example.ui.components.landscapeContentWidth
import com.example.util.AppLock
import com.example.util.PublicDownloads
import com.example.util.rememberSavedListState
import com.example.util.rememberSavedStaggeredGridState
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(
    viewModel: MainViewModel
) {
    val context = LocalContext.current
    val notes by viewModel.notes.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var viewFilter by remember { mutableStateOf("Notes") } // Notes | Tasks
    var showSearch by remember { mutableStateOf(false) }
    val notesPrefs = remember { context.getSharedPreferences("notes", android.content.Context.MODE_PRIVATE) }
    var useGrid by remember { mutableStateOf(notesPrefs.getBoolean("use_grid", true)) }
    SideEffect { notesPrefs.edit().putBoolean("use_grid", useGrid).apply() }
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var editingNote by remember { mutableStateOf<NoteEntity?>(null) }
    var lockedNote by remember { mutableStateOf<NoteEntity?>(null) }
    var showPinSetup by remember { mutableStateOf(false) }
    var showPinVerify by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    /** Set when Export is tapped on a private note: the export runs only after the PIN. */
    var pendingExport by remember { mutableStateOf<NoteEntity?>(null) }
    /** Set when API 26-28 needs WRITE_EXTERNAL_STORAGE before the export can be written. */
    var pendingStorageExport by remember { mutableStateOf<NoteEntity?>(null) }
    val scope = rememberCoroutineScope()

    // Export writes a user-visible .txt into Download/NJ-Dialer/{Notes,Tasks}.
    // A "private" note still clears the same PIN gate as opening it (wired into
    // the dialogs below): the file leaves the PIN-protected app, so the user has
    // to unlock it first. The write is I/O, never on the main thread.
    val runExport: (NoteEntity) -> Unit = { note ->
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val fallbackTitle = if (note.isTodo) "Untitled Task" else "Note"
                    val sanitizedTitle = note.title
                        .ifEmpty { fallbackTitle }
                        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                        .trim()
                        .ifBlank { fallbackTitle }
                        .take(60)
                    val fileName = "${sanitizedTitle}_${System.currentTimeMillis()}.txt"
                    val body = if (note.isTodo) {
                        buildString {
                            append(if (note.done) "[x] " else "[ ] ")
                            append(note.title.ifEmpty { fallbackTitle })
                            append("\nStatus: ")
                            append(if (note.done) "Done" else "Open")
                            append("\n\n")
                            append(note.content)
                        }
                    } else {
                        "${note.title}\n\n${note.content}"
                    }
                    PublicDownloads.saveText(
                        context = context,
                        folder = if (note.isTodo) "Tasks" else "Notes",
                        fileName = fileName,
                        text = body
                    ).displayPath
                }
            }
            val msg = result.fold(
                onSuccess = { "Saved to $it" },
                onFailure = { "Export failed: ${it.localizedMessage}" }
            )
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val note = pendingStorageExport
        pendingStorageExport = null
        if (granted && note != null) {
            runExport(note)
        } else if (!granted) {
            Toast.makeText(
                context,
                "Storage permission is required to export to Downloads",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    val startExport: (NoteEntity) -> Unit = { note ->
        val needsLegacyPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        if (needsLegacyPermission) {
            pendingStorageExport = note
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            runExport(note)
        }
    }

    val selectMode = selectedIds.isNotEmpty()

    LaunchedEffect(Unit) {
        viewModel.importLegacyTodos()
    }

    val filteredNotes = remember(notes, searchQuery, viewFilter) {
        notes.filter { note ->
            val matchesSearch = searchQuery.isEmpty() ||
                    note.title.contains(searchQuery, ignoreCase = true) ||
                    note.content.contains(searchQuery, ignoreCase = true)
            val matchesView = when (viewFilter) {
                "Notes" -> !note.isTodo
                "Tasks" -> note.isTodo
                else -> true
            }
            matchesSearch && matchesView
        }.sortedWith(compareByDescending<NoteEntity> { it.isPinned }.thenByDescending { it.timestamp })
    }

    val notesState = rememberSavedStaggeredGridState("notes", viewModel)
    val notesListState = rememberSavedListState("notes_list", viewModel)

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    editingNote = null
                    showEditDialog = true
                },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(AppIcons.Add, contentDescription = "New Note / Task")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Floating nav bar: Notes/Tasks segmented control + icon-only actions.
            val useGlassmorphism by viewModel.useGlassmorphism.collectAsState()
            FloatingTopBar(useGlass = useGlassmorphism) {
                Row(
                    modifier = Modifier
                        .clip(AppCornerRadius.xl)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    listOf("Notes" to "Notes", "Tasks" to "Tasks").forEach { (v, label) ->
                        val selected = viewFilter == v
                        Surface(
                            onClick = { viewFilter = v },
                            shape = AppCornerRadius.pill,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 7.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                if (selectMode) {
                    Text(
                        text = "${selectedIds.size} selected",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(
                            AppIcons.Delete,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                    IconButton(onClick = { selectedIds = emptySet() }) {
                        Icon(
                            AppIcons.Close,
                            contentDescription = "Clear selection"
                        )
                    }
                } else {
                    IconButton(onClick = { showSearch = !showSearch }) {
                        Icon(
                            AppIcons.Search,
                            contentDescription = "Search",
                            tint = if (showSearch) MaterialTheme.colorScheme.primary else LocalContentColor.current
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(onClick = { useGrid = !useGrid }) {
                        Icon(
                            imageVector = if (useGrid) AppIcons.GridView else AppIcons.ViewList,
                            contentDescription = if (useGrid) "Switch to list" else "Switch to grid",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Search box pops up under the bar when the search icon is tapped.
            if (showSearch) {
                AnimatedVisibility(
                    visible = showSearch,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    AppSearchField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = "Search notes...",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
            }

            if (filteredNotes.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().landscapeContentWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = if (searchQuery.isNotEmpty()) AppIcons.SearchOff else AppIcons.NoteAdd,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = when {
                                searchQuery.isNotEmpty() -> "No matching items found"
                                viewFilter == "Tasks" -> "No tasks yet — tap + to add one"
                                else -> "No notes created yet"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                val noteCard: @Composable (NoteEntity) -> Unit = { note ->
                    NoteCardItem(
                        note = note,
                        selected = note.id in selectedIds,
                        onClick = {
                            if (selectMode) {
                                selectedIds =
                                    if (note.id in selectedIds) selectedIds - note.id else selectedIds + note.id
                            } else if (note.isPrivate) {
                                lockedNote = note
                                if (isPinSet(context)) {
                                    showPinVerify = true
                                } else {
                                    showPinSetup = true
                                }
                            } else {
                                editingNote = note
                                showEditDialog = true
                            }
                        },
                        onLongPress = { selectedIds = selectedIds + note.id },
                        onTogglePin = { viewModel.toggleNotePin(note) },
                        onToggleDone = {
                            if (note.isTodo) viewModel.toggleNoteDone(note)
                        },
                        onExport = {
                            if (note.isPrivate) {
                                pendingExport = note
                                if (isPinSet(context)) showPinVerify = true else showPinSetup = true
                            } else {
                                startExport(note)
                            }
                        }
                    )
                }
                if (useGrid) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        LazyVerticalStaggeredGrid(
                            state = notesState,
                            columns = StaggeredGridCells.Fixed(2),
                            modifier = Modifier.fillMaxSize().landscapeContentWidth(),
                            contentPadding = PaddingValues(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalItemSpacing = 12.dp
                        ) {
                            items(filteredNotes, key = { it.id }) { note -> noteCard(note) }
                        }
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        LazyColumn(
                            state = notesListState,
                            modifier = Modifier.fillMaxSize().landscapeContentWidth(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(filteredNotes, key = { it.id }) { note -> noteCard(note) }
                        }
                    }
                }
            }
        }
    }

    if (showEditDialog) {
        AddEditNoteDialog(
            initialNote = editingNote,
            onDismiss = { showEditDialog = false },
            onSave = { title, content, id, colorHex, isPinned, category, isPrivate, isTodo, done ->
                viewModel.saveNote(
                    title = title,
                    content = content,
                    id = id,
                    colorHex = colorHex,
                    isPinned = isPinned,
                    category = category,
                    isPrivate = isPrivate,
                    isTodo = isTodo,
                    done = done
                )
                showEditDialog = false
            }
        )
    }

    if (showDeleteConfirm) {
        val selectedNotes = filteredNotes.filter { it.id in selectedIds }
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete ${selectedNotes.size} item${if (selectedNotes.size == 1) "" else "s"}?") },
            text = {
                Text("Are you sure you want to delete ${selectedNotes.size} selected item${if (selectedNotes.size == 1) "" else "s"}? This can't be undone.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        selectedNotes.forEach { viewModel.deleteNote(it) }
                        selectedIds = emptySet()
                        showDeleteConfirm = false
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showPinSetup) {
        PinEntryDialog(
            title = "Set a PIN",
            message = "Private notes are protected by a PIN. Choose a 4-6 digit PIN to lock them.",
            showPinSetup = true,
            onConfirm = { pin ->
                setPin(context, pin)
                showPinSetup = false
                val exporting = pendingExport
                if (exporting != null) {
                    pendingExport = null
                    startExport(exporting)
                } else {
                    lockedNote?.let { note ->
                        editingNote = note
                        showEditDialog = true
                    }
                }
                lockedNote = null
            },
            onDismiss = {
                showPinSetup = false
                pendingExport = null
                lockedNote = null
            }
        )
    }

    if (showPinVerify) {
        PinEntryDialog(
            title = "Private Note Locked",
            message = if (pendingExport != null)
                "Enter your PIN to export this private note."
            else
                "Enter your PIN to view this private note.",
            showPinSetup = false,
            onConfirm = { _ ->
                showPinVerify = false
                val exporting = pendingExport
                if (exporting != null) {
                    pendingExport = null
                    startExport(exporting)
                } else {
                    lockedNote?.let { note ->
                        editingNote = note
                        showEditDialog = true
                    }
                }
                lockedNote = null
            },
            onDismiss = {
                showPinVerify = false
                pendingExport = null
                lockedNote = null
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NoteCardItem(
    note: NoteEntity,
    selected: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleDone: () -> Unit = {},
    onExport: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()) }
    val cardColor = parseColorHex(note.colorHex)

    Card(
        colors = CardDefaults.cardColors(containerColor = cardColor.copy(alpha = 0.15f)),
        shape = MaterialTheme.shapes.large,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    if (selected) {
                        Icon(
                            AppIcons.CheckCircle,
                            contentDescription = "Selected",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    if (note.isTodo) {
                        Icon(
                            AppIcons.CheckCircle,
                            contentDescription = if (note.done) "Done" else "Not done",
                            tint = if (note.done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier
                                .size(22.dp)
                                .clickable(onClick = onToggleDone)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    if (note.isPinned) {
                        Icon(
                            AppIcons.PushPin,
                            contentDescription = "Pinned",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        text = note.title.ifEmpty { if (note.isTodo) "Untitled Task" else "Untitled Note" },
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            textDecoration = if (note.isTodo && note.done) TextDecoration.LineThrough else null
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(
                    onClick = onTogglePin,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = AppIcons.PushPin,
                        contentDescription = "Pin",
                        tint = if (note.isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            if (note.isPrivate) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 12.dp)
                ) {
                    Icon(
                        AppIcons.Lock,
                        contentDescription = "Private Note",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Private Content Hidden",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Text(
                    text = note.content,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = dateFormat.format(Date(note.timestamp)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row {
                    IconButton(onClick = onExport, modifier = Modifier.size(24.dp)) {
                        Icon(AppIcons.SaveAlt, contentDescription = "Save to Downloads", modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun AddEditNoteDialog(
    initialNote: NoteEntity?,
    onDismiss: () -> Unit,
    onSave: (String, String, Long, String, Boolean, String, Boolean, Boolean, Boolean) -> Unit
) {
    var title by remember { mutableStateOf(initialNote?.title ?: "") }
    var content by remember { mutableStateOf(initialNote?.content ?: "") }
    var selectedColor by remember { mutableStateOf(initialNote?.colorHex ?: "#3B82F6") }
    var isPinned by remember { mutableStateOf(initialNote?.isPinned ?: false) }
    var category by remember { mutableStateOf(initialNote?.category ?: "General") }
    var isPrivate by remember { mutableStateOf(initialNote?.isPrivate ?: false) }
    var isTodo by remember { mutableStateOf(initialNote?.isTodo ?: false) }
    var done by remember { mutableStateOf(initialNote?.isTodo == true && (initialNote?.done ?: false)) }

    val colorOptions = listOf("#3B82F6", "#10B981", "#F59E0B", "#EF4444", "#8B5CF6", "#EC4899")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(when {
            isTodo -> if (initialNote == null) "New Task" else "Edit Task"
            else -> if (initialNote == null) "New Note" else "Edit Note"
        }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(if (isTodo) "Task" else "Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text(if (isTodo) "Details (optional)" else "Content") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (isTodo) 60.dp else 130.dp)
                )

                // Type toggle: Note vs Task
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = isTodo, onCheckedChange = { isTodo = it })
                    Text("Mark as task", style = MaterialTheme.typography.bodySmall)
                }
                if (isTodo) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = done,
                            onCheckedChange = { done = it },
                            enabled = isTodo
                        )
                        Text(
                            if (done) "Completed" else "Not started",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Options Row: Pin & Private Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = isPinned, onCheckedChange = { isPinned = it })
                        Text("Pin Note", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = isPrivate, onCheckedChange = { isPrivate = it })
                        Text("Private", style = MaterialTheme.typography.bodySmall)
                    }
                }

                // Color Selection
                Row(
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    colorOptions.forEach { hex ->
                        val parsedColor = parseColorHex(hex)
                        val isSelected = selectedColor.equals(hex, ignoreCase = true)
                        
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(parsedColor)
                                .then(
                                    if (isSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                    else Modifier
                                )
                                .clickable { selectedColor = hex },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Icon(
                                    AppIcons.Check,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (title.isNotBlank() || content.isNotBlank()) {
                        onSave(title, content, initialNote?.id ?: 0L, selectedColor, isPinned, category, isPrivate, isTodo, done)
                    }
                },
                enabled = title.isNotBlank() || content.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

fun parseColorHex(hex: String): Color {
    return try {
        Color(android.graphics.Color.parseColor(hex))
    } catch (e: Exception) {
        Color(0xFF3B82F6)
    }
}

private fun isPinSet(context: android.content.Context): Boolean {
    return AppLock.isPinSet(context)
}

private fun setPin(context: android.content.Context, pin: String) {
    AppLock.setPin(context, pin)
}

private fun verifyPin(context: android.content.Context, pin: String): Boolean {
    return AppLock.verifyPin(context, pin)
}

@Composable
private fun PinEntryDialog(
    title: String,
    message: String,
    showPinSetup: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val dialogContext = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter { c -> c.isDigit() }.take(6) },
                    label = { Text(if (showPinSetup) "Set a 4-6 digit PIN" else "Enter PIN") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword
                    ),
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth()
                )
                if (error != null) {
                    Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (pin.length < 4) {
                        error = "PIN must be at least 4 digits"
                    } else if (!showPinSetup && !verifyPin(dialogContext, pin)) {
                        error = "Incorrect PIN"
                    } else {
                        onConfirm(pin)
                    }
                },
                enabled = pin.isNotEmpty()
            ) {
                Text(if (showPinSetup) "Set PIN" else "Unlock")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
