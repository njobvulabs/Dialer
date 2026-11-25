package com.example.ui.screens.archivemanager

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ui.MainViewModel
import com.example.ui.components.AppIcons
import com.example.ui.components.FloatingTopBar
import com.example.ui.components.PillTabRow
import com.example.ui.components.landscapeContentWidth
import com.example.util.ArchiveFormats
import com.example.util.StorageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

private val ARCHIVE_EXTENSIONS = setOf("zip", "tar", "gz", "tgz", "tbz", "rar", "7z", "z01")

private fun nameForUri(context: Context, uri: Uri): String {
    val cursor = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) return it.getString(index).ifBlank { "file" }
        }
    }
    return "file"
}

private fun sanitizeName(value: String): String {
    val cleaned = value.trim()
        .replace('\\', '_')
        .replace('/', '_')
        .replace('\u0000', '_')
    return cleaned.ifBlank { "file" }
}

private fun copyUriToCache(context: Context, uri: Uri): File? {
    val target = File(context.cacheDir, "archive_pick_${UUID.randomUUID()}_${sanitizeName(nameForUri(context, uri))}")
    return runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
        target.takeIf { it.exists() && it.length() > 0L }
    }.getOrNull()
}

private fun defaultArchiveDir(): String =
    File(Environment.getExternalStorageDirectory(), "Download/NJ-Dialer/Archives").absolutePath

private fun treePath(context: Context, uri: Uri): String? {
    val decoded = runCatching { Uri.decode(uri.toString()) }.getOrDefault(uri.toString())
    val tree = decoded.substringAfter("/tree/", "")
    if (tree.isBlank()) return null
    val type = tree.substringBefore(':')
    val suffix = tree.substringAfter(':', "").trimStart('/')
    return when (type) {
        "primary", "home" -> File(Environment.getExternalStorageDirectory(), suffix).absolutePath
        "raw" -> File(File("/storage"), suffix).absolutePath
        else -> {
            val cached = context.externalCacheDirs.firstOrNull { it.absolutePath.contains(suffix.substringBefore('/')) }
            cached?.let { File(it, suffix.substringAfter('/', "")).absolutePath }
        }
    }
}

@Composable
fun ArchiveManagerScreen(
    viewModel: MainViewModel? = null,
    pendingFiles: List<File> = emptyList(),
    onPendingConsumed: () -> Unit = {},
    onOpenFiles: () -> Unit = {},
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val useGlass = viewModel?.useGlassmorphism?.collectAsState()?.value ?: false
    val prefs = remember { context.getSharedPreferences("archivemanager", Context.MODE_PRIVATE) }
    val cancel = remember { AtomicBoolean(false) }

    var mode by remember { mutableStateOf("Extract") }
    var sourceArchive by remember { mutableStateOf<File?>(null) }
    var entries by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var tempFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var destination by remember {
        mutableStateOf(prefs.getString("destination", defaultArchiveDir())?.takeIf { it.isNotBlank() } ?: defaultArchiveDir())
    }
    var archiveName by remember { mutableStateOf("Archive") }
    var format by remember { mutableStateOf(prefs.getString("format", "ZIP") ?: "ZIP") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var level by remember { mutableIntStateOf(prefs.getInt("level", 5).coerceIn(1, 9)) }
    var collision by remember {
        mutableStateOf(
            runCatching { StorageHelper.Collision.valueOf(prefs.getString("collision", StorageHelper.Collision.KEEP_BOTH.name)!!) }
                .getOrDefault(StorageHelper.Collision.KEEP_BOTH)
        )
    }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf<ArchiveFormats.Progress?>(null) }

    val pickArchiveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val copied = uris.mapNotNull { uri -> copyUriToCache(context, uri) }
        if (copied.isNotEmpty()) {
            tempFiles = (tempFiles + copied).distinctBy { it.absolutePath }
            sourceArchive = copied.first()
            mode = "Extract"
            message = "Selected ${copied.first().name}"
        }
    }
    val pickFilesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val copied = uris.mapNotNull { uri -> copyUriToCache(context, uri) }
        if (copied.isNotEmpty()) {
            tempFiles = (tempFiles + copied).distinctBy { it.absolutePath }
            val existing = selectedFiles.map { it.absolutePath }.toSet()
            selectedFiles = selectedFiles + copied.filterNot { it.absolutePath in existing }
            mode = "Create"
            message = "Added ${copied.size} file(s)"
        }
    }
    val pickDestinationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val path = treePath(context, uri)
        if (path != null) {
            destination = path
            message = "Destination updated"
        } else {
            message = "This storage provider cannot be used as a folder destination"
        }
    }

    LaunchedEffect(destination, format, level, collision) {
        prefs.edit()
            .putString("destination", destination)
            .putString("format", format)
            .putInt("level", level)
            .putString("collision", collision.name)
            .apply()
    }

    LaunchedEffect(sourceArchive, password) {
        val archive = sourceArchive
        if (archive == null) {
            entries = emptyList()
            return@LaunchedEffect
        }
        val found = withContext(Dispatchers.IO) {
            ArchiveFormats.listEntries(archive, password.takeIf { it.isNotBlank() }?.toCharArray())
        }
        entries = found
    }

    LaunchedEffect(pendingFiles) {
        if (pendingFiles.isEmpty()) return@LaunchedEffect
        val archives = pendingFiles.filter { file ->
            file.extension.lowercase() in ARCHIVE_EXTENSIONS || file.name.endsWith(".z01", ignoreCase = true)
        }
        if (archives.isNotEmpty()) {
            sourceArchive = archives.first()
            mode = "Extract"
            message = "Selected ${archives.first().name}"
        } else {
            val existing = selectedFiles.map { it.absolutePath }.toSet()
            selectedFiles = selectedFiles + pendingFiles.filterNot { it.absolutePath in existing }
            mode = "Create"
            message = "Added ${pendingFiles.size} file(s)"
        }
        onPendingConsumed()
    }

    val currentTempFiles = rememberUpdatedState(tempFiles)
    DisposableEffect(Unit) {
        onDispose { currentTempFiles.value.forEach { runCatching { it.delete() } } }
    }

    fun destFile(): File = File(destination)

    fun runDestinationPick() {
        runCatching { pickDestinationLauncher.launch(null) }.onFailure {
            message = "Could not open folder picker"
        }
    }

    fun showToast(value: String) {
        Toast.makeText(context, value, Toast.LENGTH_SHORT).show()
    }

    fun extract() {
        val archive = sourceArchive
        if (archive == null) {
            message = "Choose an archive first"
            return
        }
        if (archive.extension.equals("rar", ignoreCase = true)) {
            message = "RAR extraction is not supported"
            showToast(message)
            return
        }
        cancel.set(false)
        busy = true
        progress = null
        scope.launch {
            val staged = withContext(Dispatchers.IO) {
                ArchiveFormats.stageSplitArchive(archive, context.cacheDir)
            }
            val input = staged.first
            val error = withContext(Dispatchers.IO) {
                ArchiveFormats.extract(
                    file = input,
                    destDir = destFile(),
                    password = password.takeIf { it.isNotBlank() }?.toCharArray(),
                    collision = collision,
                    onProgress = { value ->
                        scope.launch(Dispatchers.Main) { progress = value }
                    },
                    isCancelled = { cancel.get() }
                )
            }
            if (staged.second) runCatching { input.delete() }
            withContext(Dispatchers.Main) {
                busy = false
                message = error ?: "Extracted to $destination"
                showToast(message)
            }
        }
    }

    fun create() {
        if (selectedFiles.isEmpty()) {
            message = "Add files to compress first"
            return
        }
        val destDir = destFile()
        val base = archiveName.trim().ifBlank { "Archive" }
        val ext = if (format == "7Z") "7z" else "zip"
        val desired = "$base.$ext"
        val output = when (collision) {
            StorageHelper.Collision.KEEP_BOTH -> StorageHelper.uniqueNameIn(destDir, desired)
            StorageHelper.Collision.SKIP -> File(destDir, desired).also {
                if (it.exists()) {
                    message = "Archive already exists"
                    return
                }
            }
            StorageHelper.Collision.OVERWRITE -> File(destDir, desired)
        }
        cancel.set(false)
        busy = true
        progress = null
        scope.launch {
            val error = withContext(Dispatchers.IO) {
                ArchiveFormats.create(
                    files = selectedFiles,
                    output = output,
                    format = format,
                    password = password.takeIf { it.isNotBlank() && format == "7Z" }?.toCharArray(),
                    level = level,
                    onProgress = { value ->
                        scope.launch(Dispatchers.Main) { progress = value }
                    },
                    isCancelled = { cancel.get() }
                )
            }
            withContext(Dispatchers.Main) {
                busy = false
                message = error ?: "Created ${output.absolutePath}"
                showToast(message)
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
        topBar = {
            FloatingTopBar(useGlass = useGlass) {
                Text(
                    text = "Archive Manager",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(onClick = onOpenFiles) {
                    Icon(AppIcons.FolderOpen, contentDescription = "Open in File Manager")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .landscapeContentWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PillTabRow(
                tabs = listOf("Extract", "Create"),
                selected = mode,
                onSelect = { mode = it },
                modifier = Modifier.fillMaxWidth()
            )

            if (mode == "Extract") {
                Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Archive to extract", style = MaterialTheme.typography.titleSmall)
                        Text(
                            sourceArchive?.absolutePath ?: "No archive selected",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Button(
                            onClick = { pickArchiveLauncher.launch(arrayOf("application/zip", "application/x-tar", "application/gzip", "application/x-7z-compressed", "*/*")) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Choose archive")
                        }
                        if (entries.isNotEmpty()) {
                            Text("Contents (${entries.size})", style = MaterialTheme.typography.labelMedium)
                            entries.take(40).forEach { entry ->
                                Text(
                                    entry,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (entries.size > 40) {
                                Text("+ ${entries.size - 40} more", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            } else {
                Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Files to compress", style = MaterialTheme.typography.titleSmall)
                        if (selectedFiles.isEmpty()) {
                            Text(
                                "No files selected. Use the File Manager's multi-select Archive action or add documents here.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            selectedFiles.forEach { file ->
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(AppIcons.InsertDriveFile, contentDescription = null)
                                    Text(
                                        file.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    IconButton(onClick = { selectedFiles = selectedFiles.filterNot { it.absolutePath == file.absolutePath } }) {
                                        Icon(AppIcons.Close, contentDescription = "Remove")
                                    }
                                }
                            }
                        }
                        Button(
                            onClick = { pickFilesLauncher.launch(arrayOf("*/*")) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Add files")
                        }
                        OutlinedTextField(
                            value = archiveName,
                            onValueChange = { archiveName = it },
                            label = { Text("Archive name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = format == "ZIP", onClick = { format = "ZIP" }, label = { Text("ZIP") })
                            FilterChip(selected = format == "7Z", onClick = { format = "7Z" }, label = { Text("7z") })
                        }
                        Text("Compression level: $level", style = MaterialTheme.typography.labelMedium)
                        Slider(
                            value = level.toFloat(),
                            onValueChange = { level = it.toInt().coerceIn(1, 9) },
                            valueRange = 1f..9f,
                            steps = 7
                        )
                        Text(
                            "Password protection is available for 7z archives.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Options", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Archive password") },
                        singleLine = true,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(AppIcons.Visibility, contentDescription = if (showPassword) "Hide password" else "Show password")
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Name conflict", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = collision == StorageHelper.Collision.KEEP_BOTH,
                            onClick = { collision = StorageHelper.Collision.KEEP_BOTH },
                            label = { Text("Keep both") }
                        )
                        FilterChip(
                            selected = collision == StorageHelper.Collision.OVERWRITE,
                            onClick = { collision = StorageHelper.Collision.OVERWRITE },
                            label = { Text("Overwrite") }
                        )
                        FilterChip(
                            selected = collision == StorageHelper.Collision.SKIP,
                            onClick = { collision = StorageHelper.Collision.SKIP },
                            label = { Text("Skip") }
                        )
                    }
                }
            }

            Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Destination folder", style = MaterialTheme.typography.titleSmall)
                    Text(
                        destination,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(onClick = ::runDestinationPick, modifier = Modifier.fillMaxWidth()) {
                        Text("Change folder")
                    }
                }
            }

            if (progress != null) {
                val value = progress!!
                Text(
                    "${value.current}: ${value.entriesDone}/${value.entriesTotal}",
                    style = MaterialTheme.typography.bodySmall
                )
                LinearProgressIndicator(
                    progress = {
                        if (value.bytesTotal > 0L) value.bytesDone.toFloat() / value.bytesTotal.toFloat()
                        else if (value.entriesTotal > 0) value.entriesDone.toFloat() / value.entriesTotal.toFloat()
                        else 0f
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { if (mode == "Extract") extract() else create() },
                    enabled = !busy && (if (mode == "Extract") sourceArchive != null else selectedFiles.isNotEmpty()),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (busy) "Working…" else if (mode == "Extract") "Extract archive" else "Create ${format.lowercase()}")
                }
                OutlinedButton(onClick = { cancel.set(true) }, enabled = busy) {
                    Text("Cancel")
                }
            }

            if (message.isNotBlank()) {
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
