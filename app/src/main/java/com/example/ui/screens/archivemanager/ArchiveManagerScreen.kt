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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ui.MainViewModel
import com.example.ui.components.AppIcons
import com.example.ui.components.FloatingTopBar
import com.example.ui.components.PillTabRow
import com.example.ui.components.landscapeContentWidth
import com.example.util.StorageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

private val ARCHIVE_EXTENSIONS = setOf("zip", "tar", "gz", "tgz", "tbz", "rar", "7z")

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

private fun copyUriToCache(context: Context, uri: Uri): File? {
    val safeName = nameForUri(context, uri)
        .replace('\\', '_')
        .replace('/', '_')
        .ifBlank { "file" }
    val target = File(context.cacheDir, "archive_pick_${System.currentTimeMillis()}_$safeName")
    return runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
        target.takeIf { it.exists() && it.length() > 0L }
    }.getOrNull()
}

private fun defaultArchiveDir(): String =
    File(Environment.getExternalStorageDirectory(), "Download/NJ-Dialer/Archives").absolutePath

@Composable
fun ArchiveManagerScreen(
    viewModel: MainViewModel? = null,
    pendingFiles: List<File> = emptyList(),
    onPendingConsumed: () -> Unit = {},
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val useGlass = viewModel?.useGlassmorphism?.collectAsState()?.value ?: false
    var mode by remember { mutableStateOf("Extract") }
    var sourceArchive by remember { mutableStateOf<File?>(null) }
    var selectedFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var destination by remember { mutableStateOf(defaultArchiveDir()) }
    var archiveName by remember { mutableStateOf("Archive_${System.currentTimeMillis()}.zip") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    val pickArchiveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val copied = uris.mapNotNull { uri -> copyUriToCache(context, uri) }
        if (copied.isNotEmpty()) {
            sourceArchive = copied.first()
            mode = "Extract"
            message = "Selected ${copied.first().name}"
        }
    }
    val pickFilesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val copied = uris.mapNotNull { uri -> copyUriToCache(context, uri) }
        if (copied.isNotEmpty()) {
            val existing = selectedFiles.map { it.absolutePath }.toSet()
            selectedFiles = selectedFiles + copied.filterNot { it.absolutePath in existing }
            mode = "Create"
            message = "Added ${copied.size} file(s)"
        }
    }
    val pickDestinationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val path = uri.path
        if (path != null) {
            destination = path.substringAfter("/tree/", path)
            message = "Destination updated"
        }
    }

    LaunchedEffect(pendingFiles) {
        if (pendingFiles.isEmpty()) return@LaunchedEffect
        val archives = pendingFiles.filter { it.extension.lowercase() in ARCHIVE_EXTENSIONS }
        if (archives.isNotEmpty()) {
            sourceArchive = archives.first()
            mode = "Extract"
        } else {
            val existing = selectedFiles.map { it.absolutePath }.toSet()
            selectedFiles = selectedFiles + pendingFiles.filterNot { it.absolutePath in existing }
            mode = "Create"
        }
        onPendingConsumed()
    }

    fun runDestinationPick() {
        runCatching { pickDestinationLauncher.launch(null) }.onFailure {
            message = "Could not open folder picker"
        }
    }

    fun extract() {
        val archive = sourceArchive
        if (archive == null) {
            message = "Choose an archive first"
            return
        }
        busy = true
        scope.launch {
            val error = withContext(Dispatchers.IO) {
                val dest = File(destination)
                runCatching {
                    if (!dest.exists()) dest.mkdirs()
                    StorageHelper.extractArchive(archive, dest)
                }.getOrElse { it.message ?: "Extraction failed" }
            }
            busy = false
            message = error ?: "Extracted to $destination"
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    fun create() {
        if (selectedFiles.isEmpty()) {
            message = "Add files to compress first"
            return
        }
        val destDir = File(destination)
        val base = archiveName.substringBeforeLast('.', "").ifBlank { "Archive_${System.currentTimeMillis()}" }
        val output = File(destDir, "$base.zip")
        busy = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    if (!destDir.exists()) destDir.mkdirs()
                    StorageHelper.zipFiles(selectedFiles, output)
                }.getOrDefault(false)
            }
            busy = false
            message = if (ok) "Created ${output.absolutePath}" else "Could not create archive"
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
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
                            onClick = { pickArchiveLauncher.launch(arrayOf("application/zip", "application/x-tar", "application/gzip", "*/*")) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Choose archive")
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

            Button(
                onClick = { if (mode == "Extract") extract() else create() },
                enabled = !busy && (if (mode == "Extract") sourceArchive != null else selectedFiles.isNotEmpty()),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (busy) "Working…" else if (mode == "Extract") "Extract archive" else "Create ZIP")
            }

            if (message.isNotBlank()) {
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
