package com.example.ui.screens.wifishare

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.components.AppIcons
import com.example.ui.components.AppSearchField
import com.example.ui.components.FloatingTopBar
import com.example.ui.theme.AppCornerRadius
import com.example.ui.MainViewModel
import com.example.ui.components.SearchIconButton
import com.example.util.QrCodeGenerator
import com.example.util.SafDocument
import com.example.util.SafPrefs
import com.example.util.StorageHelper
import com.example.util.WifiShareServer
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun fmtSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private fun openFile(context: Context, doc: SafDocument) {
    runCatching {
        val mime = StorageHelper.getMimeType(doc.name ?: "")
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(doc.uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Open file"))
    }.onFailure {
        Toast.makeText(context, "No app found to open this file", Toast.LENGTH_SHORT).show()
    }
}

internal fun getFileIcon(name: String): ImageVector = when (name.substringAfterLast('.').lowercase(Locale.ROOT)) {
    "jpg", "jpeg", "png", "gif", "webp", "bmp" -> AppIcons.Image
    "mp4", "mkv", "avi", "mov", "webm", "3gp" -> AppIcons.Movie
    "mp3", "wav", "ogg", "m4a", "flac" -> AppIcons.MusicNote
    "pdf", "doc", "docx", "txt", "rtf" -> AppIcons.Description
    "zip", "rar", "7z", "tar", "gz" -> AppIcons.FolderZip
    "kt", "java", "py", "js", "html", "css", "json", "xml" -> AppIcons.Code
    else -> AppIcons.InsertDriveFile
}

@Composable
fun QrCodeView(
    url: String,
    modifier: Modifier = Modifier,
    size: Dp = 180.dp
) {
    val matrix = remember(url) { QrCodeGenerator.generateMatrix(url) }
    if (matrix != null) {
        val numModules = matrix.size
        Canvas(modifier = modifier.size(size)) {
            val quietZone = 2
            val totalModules = numModules + quietZone * 2
            val moduleSize = this.size.width / totalModules

            drawRect(color = Color.White)
            for (r in 0 until numModules) {
                for (c in 0 until numModules) {
                    if (matrix[r][c]) {
                        drawRect(
                            color = Color(0xFF0F172A),
                            topLeft = Offset((c + quietZone) * moduleSize, (r + quietZone) * moduleSize),
                            size = Size(moduleSize, moduleSize)
                        )
                    }
                }
            }
        }
    } else {
        Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
            Text("QR code unavailable", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WifiShareScreen(
    viewModel: MainViewModel,
    onOpenBrowser: (String) -> Unit
) {
    val context = LocalContext.current
    var running by remember { mutableStateOf(WifiShareServer.isRunning) }
    var serverAddress by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    var showQrDialog by remember { mutableStateOf(false) }
    // The search field is icon-toggled and the sort options live in a menu, so both
    // controls stay out of the Shared Files line until they are wanted.
    var showSearchBar by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    // Per-file delete is long-press only (there is no delete icon in a row).
    var filePendingDelete by remember { mutableStateOf<SafDocument?>(null) }
    var sortMode by remember { mutableStateOf("date") }
    var sortAsc by remember { mutableStateOf(false) }

    var pickedUri by remember { mutableStateOf<Uri?>(null) }

    var shareFiles by remember {
        mutableStateOf<List<SafDocument>>(
            WifiShareServer.shareRoot?.listChildren()?.filter { !it.isDirectory }.orEmpty()
        )
    }

    fun toast(msg: String) {
        runCatching { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
    }

    fun refreshFiles() {
        val root = WifiShareServer.shareRoot
        shareFiles = root?.listChildren()?.filter { !it.isDirectory }?.sortedByDescending { it.lastModified }.orEmpty()
    }

    fun importUris(uris: List<Uri>) {
        val root = WifiShareServer.shareRoot
            ?: pickedUri?.let { SafDocument.fromTreeUri(context.contentResolver, it) }
        if (root == null || !root.exists || !root.isDirectory) {
            toast("Pick a share folder first, or start the server")
            return
        }
        var importedCount = 0
        uris.forEach { u ->
            runCatching {
                val name = SafDocument.displayName(context.contentResolver, u) ?: return@runCatching
                val target = root.createFile(StorageHelper.getMimeType(name), name) ?: return@runCatching
                val ins = context.contentResolver.openInputStream(u) ?: return@runCatching
                val out = target.openOutputStream("w")
                if (out != null) {
                    ins.use { i -> out.use { o -> i.copyTo(o) } }
                    importedCount++
                }
            }
        }
        refreshFiles()
        toast("Imported $importedCount file(s)")
    }

    fun deleteSharedFile(doc: SafDocument) {
        if (doc.delete()) {
            refreshFiles()
            toast("Deleted ${doc.name ?: ""}")
        }
    }

    fun copyUrl() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("WifiShare URL", serverAddress))
        toast("Link copied")
    }

    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            SafPrefs.save(context, WifiShareServer.PREFS, uri)
            pickedUri = uri
            toast(if (running) "Share folder will be used on next server start" else "Share folder selected")
        }
    }

    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) importUris(uris)
    }

    fun startServer() {
        val uri = pickedUri ?: SafPrefs.load(context, WifiShareServer.PREFS)
        if (uri == null) {
            folderLauncher.launch(null)
            toast("Choose the folder to share")
            return
        }
        val ok = WifiShareServer.start(context, uri)
        running = WifiShareServer.isRunning
        if (ok) {
            serverAddress = WifiShareServer.url(context)
            refreshFiles()
            toast("Server started: $serverAddress")
        } else {
            toast("Could not start server. Check Hotspot or Wi-Fi.")
        }
    }

    fun stopServer() {
        WifiShareServer.stop()
        running = false
        serverAddress = ""
        shareFiles = emptyList()
        showQrDialog = false
    }

    val filteredFiles = remember(shareFiles, searchQuery, sortMode, sortAsc) {
        val base = if (searchQuery.isBlank()) shareFiles
        else shareFiles.filter { it.name?.contains(searchQuery, ignoreCase = true) == true }
        val sorted = when (sortMode) {
            "name" -> base.sortedBy { it.name?.lowercase(Locale.getDefault()).orEmpty() }
            "size" -> base.sortedBy { it.length }
            else -> base.sortedBy { it.lastModified }
        }
        if (sortAsc) sorted else sorted.asReversed()
    }

    val totalStorageSize = remember(shareFiles) {
        shareFiles.sumOf { it.length }
    }

    val pickedLabel = remember(pickedUri) {
        pickedUri?.let { SafDocument.displayName(context.contentResolver, it) } ?: "Not selected — Start will ask you to choose a folder"
    }

    LaunchedEffect(Unit) {
        if (running) serverAddress = WifiShareServer.url(context)
        refreshFiles()
    }

    LaunchedEffect(running) {
        while (running) {
            delay(3000)
            refreshFiles()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        val useGlassmorphism by viewModel.useGlassmorphism.collectAsState()
        FloatingTopBar(modifier = Modifier.fillMaxWidth(), useGlass = useGlassmorphism) {
            Text(
                text = "Wifi Share",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        // --- Server Status Card ---
        Surface(
            color = if (running) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (running) Color(0xFF22C55E) else Color(0xFF64748B))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (running) "Server Running" else "Server Stopped",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    if (running && serverAddress.isNotEmpty()) {
                        IconButton(onClick = { showQrDialog = true }) {
                            Icon(
                                AppIcons.QrCode,
                                contentDescription = "Show QR Code",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                if (running && serverAddress.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Connect devices on your network to this web link:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        onClick = { onOpenBrowser(serverAddress) },
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = serverAddress,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                AppIcons.OpenInNew,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row {
                        OutlinedButton(onClick = { copyUrl() }) {
                            Icon(AppIcons.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Copy")
                        }
                    }
                }
            }
        }

        // --- Hotspot Setup Guidance ---
        if (!running) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
                ),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("How to Connect", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "1. Turn on Mobile Hotspot or connect both devices to the same Wi-Fi.\n" +
                        "2. Tap 'Start Server' below.\n" +
                        "3. Scan QR Code or open link in browser on the other device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // --- Controls: Start/Stop & Folder Select ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { if (running) stopServer() else startServer() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (running) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
            ) {
                Icon(
                    if (running) AppIcons.Stop else AppIcons.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (running) "Stop Server" else "Start Server", fontWeight = FontWeight.Bold)
            }
        }

        // --- File Import & Directory Row ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = { folderLauncher.launch(null) }) {
                Icon(AppIcons.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Choose Folder")
            }
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(onClick = { fileLauncher.launch(arrayOf("*/*")) }) {
                Icon(AppIcons.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Add Files")
            }
        }

        Text(
            text = "Share folder: $pickedLabel",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        Spacer(modifier = Modifier.height(4.dp))

        // --- Shared Files Header: title/total, then search icon, sort menu, direction ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Shared Files (${shareFiles.size})",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
                if (shareFiles.isNotEmpty()) {
                    Text(
                        text = "Total: ${fmtSize(totalStorageSize)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (shareFiles.isNotEmpty()) {
                SearchIconButton(
                    isActive = showSearchBar || searchQuery.isNotEmpty(),
                    onClick = { showSearchBar = !showSearchBar },
                    contentDescription = if (showSearchBar) "Hide search" else "Search shared files"
                )
                Box {
                    IconButton(onClick = { showSortMenu = true }) {
                        Icon(
                            AppIcons.Sort,
                            contentDescription = "Sort shared files",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false }
                    ) {
                        listOf(
                            "name" to "Name",
                            "size" to "Size",
                            "date" to "Date"
                        ).forEach { (value, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    if (sortMode == value) sortAsc = !sortAsc else { sortMode = value; sortAsc = true }
                                    showSortMenu = false
                                },
                                trailingIcon = {
                                    if (sortMode == value) {
                                        Icon(AppIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                    }
                                }
                            )
                        }
                    }
                }
                IconButton(onClick = { sortAsc = !sortAsc }) {
                    Icon(
                        if (sortAsc) AppIcons.ArrowUpward else AppIcons.ArrowDownward,
                        contentDescription = if (sortAsc) "Ascending" else "Descending",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // --- Search field: only shown while the search icon is active ---
        AnimatedVisibility(visible = showSearchBar && shareFiles.isNotEmpty()) {
            AppSearchField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = "Search shared files...",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        // --- File List ---
        if (filteredFiles.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (searchQuery.isNotEmpty()) "No files match '$searchQuery'"
                    else "No shared files. Start server or add files to send.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                items(filteredFiles, key = { it.uri.toString() }) { f ->
                    Surface(
                        shape = AppCornerRadius.md,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.combinedClickable(
                            onClick = { openFile(context, f) },
                            onLongClick = { filePendingDelete = f }
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        getFileIcon(f.name ?: ""),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = f.name ?: "",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${fmtSize(f.length)} • ${SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(f.lastModified))}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // --- QR Code Dialog ---
    if (showQrDialog) {
        AlertDialog(
            onDismissRequest = { showQrDialog = false },
            title = { Text("Scan to Connect", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    QrCodeView(url = serverAddress, size = 180.dp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Scan with camera on another phone or tablet to open the file transfer page.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showQrDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    // --- Long-Pressed File Delete Confirmation ---
    filePendingDelete?.let { doc ->
        AlertDialog(
            onDismissRequest = { filePendingDelete = null },
            title = { Text("Delete File?") },
            text = { Text("\"${doc.name ?: "This file"}\" will be removed from the share folder.") },
            confirmButton = {
                TextButton(onClick = {
                    filePendingDelete = null
                    deleteSharedFile(doc)
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { filePendingDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}
