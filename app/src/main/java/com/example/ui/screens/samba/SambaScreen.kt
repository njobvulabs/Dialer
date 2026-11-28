package com.example.ui.screens.samba

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ui.components.landscapeContentWidth
import com.example.ui.components.AppIcons
import com.example.ui.components.AppSearchField
import com.example.ui.theme.AppCornerRadius
import com.example.util.copyUriToCache
import com.example.util.CredentialCipher
import com.example.ui.MainViewModel
import com.example.ui.components.FloatingTopBar
import com.example.util.SambaClient
import com.example.util.StorageHelper
import com.example.util.SambaEntry
import com.example.util.SambaServerConfig
import com.example.util.rememberSavedListState
import jcifs.CIFSContext
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

private const val PREFS_NAME = "samba_prefs"
private const val KEY_SERVERS = "servers"

private data class SambaClipboard(val isCopy: Boolean, val urls: List<String>)

private fun loadServers(context: Context): MutableList<SambaServerConfig> {
    val result = mutableListOf<SambaServerConfig>()
    runCatching {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_SERVERS, null)
            ?: return result
        val arr = JSONArray(raw)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            result.add(
                SambaServerConfig(
                    id = o.optString("id", UUID.randomUUID().toString()),
                    name = o.optString("name", ""),
                    host = o.optString("host", ""),
                    port = o.optInt("port", 445),
                    share = o.optString("share", ""),
                    domain = o.optString("domain", ""),
                    username = o.optString("username", ""),
                    password = CredentialCipher.decrypt(o.optString("password", ""))
                )
            )
        }
    }
    return result
}

private fun saveServers(context: Context, servers: List<SambaServerConfig>) {
    runCatching {
        val arr = JSONArray()
        servers.forEach { s ->
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("name", s.name)
                    .put("host", s.host)
                    .put("port", s.port)
                    .put("share", s.share)
                    .put("domain", s.domain)
                    .put("username", s.username)
                    .put("password", CredentialCipher.encrypt(s.password))
            )
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_SERVERS, arr.toString()).apply()
    }
}

private fun sanitizeFileName(name: String): String =
    name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "server" }

@Composable
fun SambaScreen(
    viewModel: MainViewModel,
    onOpenMediaUrl: (String, String) -> Unit = { _, _ -> },
    onOpenMediaFile: (java.io.File, String) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    var servers by remember { mutableStateOf<List<SambaServerConfig>>(loadServers(context)) }
    var activeServer by remember { mutableStateOf<SambaServerConfig?>(null) }
    var editingServer by remember { mutableStateOf<SambaServerConfig?>(null) }
    var showAddServer by remember { mutableStateOf(false) }
    var pendingDeleteServer by remember { mutableStateOf<SambaServerConfig?>(null) }

    val serversState = rememberSavedListState("samba_servers", viewModel)

    val server = activeServer
    if (server != null) {
        SambaBrowserContent(
            server = server,
            onExit = { activeServer = null },
            viewModel = viewModel,
            onOpenMediaUrl = onOpenMediaUrl,
            onOpenMediaFile = onOpenMediaFile
        )
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            val useGlassmorphism by viewModel.useGlassmorphism.collectAsState()
            FloatingTopBar(useGlass = useGlassmorphism) {
                Text(
                    "SMB Servers",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f).padding(start = 8.dp)
                )
            }
            if (servers.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize().landscapeContentWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = AppIcons.Dns,
                    contentDescription = null,
                    modifier = Modifier.size(72.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "No SMB servers saved",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Tap + to add your Samba or Windows shared drive (IP address or hostname).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                state = serversState,
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(servers, key = { it.id }) { s ->
                    ServerCard(
                        server = s,
                        onClick = { activeServer = s },
                        onEdit = { editingServer = s },
                        onDelete = { pendingDeleteServer = s }
                    )
                }
            }
        }
        }

        // Add server button overlay
        FloatingActionButton(
            onClick = { showAddServer = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        ) {
            Icon(AppIcons.Add, contentDescription = "Add server")
        }
    }

    if (showAddServer) {
        ServerFormDialog(
            initialConfig = null,
            onDismiss = { showAddServer = false },
            onSave = { newServer ->
                servers = (servers + newServer)
                saveServers(context, servers)
                showAddServer = false
            }
        )
    }

    editingServer?.let { s ->
        ServerFormDialog(
            initialConfig = s,
            onDismiss = { editingServer = null },
            onSave = { updated ->
                servers = servers.map { if (it.id == updated.id) updated else it }
                saveServers(context, servers)
                editingServer = null
            }
        )
    }

    pendingDeleteServer?.let { s ->
        AlertDialog(
            onDismissRequest = { pendingDeleteServer = null },
            title = { Text("Delete server?") },
            text = { Text("Remove \"${s.name}\" (${s.host})? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    servers = servers.filterNot { it.id == s.id }
                    saveServers(context, servers)
                    pendingDeleteServer = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteServer = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun ServerCard(
    server: SambaServerConfig,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = AppCornerRadius.md,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = AppCornerRadius.sm
            ) {
                Icon(
                    imageVector = AppIcons.Dns,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(8.dp)
                        .size(26.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = server.name.ifBlank { server.host },
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${server.host}:${server.port}${if (server.share.isNotBlank()) "/${server.share}" else ""} · ${if (server.username.isBlank()) "Guest" else server.username}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onEdit) {
                Icon(
                    AppIcons.Edit,
                    contentDescription = "Edit",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    AppIcons.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
private fun ServerFormDialog(
    initialConfig: SambaServerConfig?,
    onDismiss: () -> Unit,
    onSave: (SambaServerConfig) -> Unit
) {
    var name by remember { mutableStateOf(initialConfig?.name ?: "") }
    var host by remember { mutableStateOf(initialConfig?.host ?: "") }
    var port by remember { mutableStateOf(initialConfig?.port?.toString() ?: "445") }
    var share by remember { mutableStateOf(initialConfig?.share ?: "") }
    var domain by remember { mutableStateOf(initialConfig?.domain ?: "") }
    var username by remember { mutableStateOf(initialConfig?.username ?: "") }
    var password by remember { mutableStateOf(initialConfig?.password ?: "") }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val parsedPort = port.toIntOrNull() ?: 445
    val draft = SambaServerConfig(
        id = initialConfig?.id ?: UUID.randomUUID().toString(),
        name = name.ifBlank { host },
        host = host.trim(),
        port = parsedPort,
        share = share.trim(),
        domain = domain.trim(),
        username = username.trim(),
        password = password
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialConfig == null) "Add Samba Server" else "Edit Samba Server") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Display Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("Host / IP address") },
                    placeholder = { Text("192.168.1.10") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = share,
                    onValueChange = { share = it },
                    label = { Text("Share Name (optional)") },
                    placeholder = { Text("e.g. SharedDocs or C$") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
                    label = { Text("Port") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username (blank = Guest)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = domain,
                    onValueChange = { domain = it },
                    label = { Text("Domain / Workgroup (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                testResult?.let { r ->
                    Text(
                        text = r,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (r == "Connected") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(
                    onClick = {
                        if (host.isBlank()) {
                            testResult = "Enter a host first"
                            return@TextButton
                        }
                        testing = true
                        testResult = null
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    val ctx = SambaClient.buildContext(draft)
                                    val err = SambaClient.testConnection(ctx, draft)
                                    err ?: "Connected"
                                }.getOrElse { "Failed: ${it.message}" }
                            }
                            testing = false
                            testResult = result
                        }
                    },
                    enabled = !testing
                ) {
                    if (testing) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(if (testing) "Testing…" else "Test")
                }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(
                    onClick = {
                        if (host.isBlank()) {
                            testResult = "Enter a host first"
                        } else {
                            onSave(draft)
                        }
                    },
                    enabled = !testing
                ) {
                    Text("Save")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun SambaBrowserContent(
    server: SambaServerConfig,
    onExit: () -> Unit,
    viewModel: MainViewModel,
    onOpenMediaUrl: (String, String) -> Unit,
    onOpenMediaFile: (java.io.File, String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val browserState = rememberSavedListState("samba_browser_${server.id}", viewModel)

    var sambaCtx by remember(server) { mutableStateOf<CIFSContext?>(null) }
    var ctxError by remember(server) { mutableStateOf<String?>(null) }
    var pathStack by remember { mutableStateOf<List<String>>(emptyList()) }
    var entries by remember { mutableStateOf<List<SambaEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var multiSelect by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var clipboard by remember { mutableStateOf<SambaClipboard?>(null) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<SambaEntry?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var transferState by remember { mutableStateOf<Pair<String, Float>?>(null) }
    var cancelled by remember { mutableStateOf(false) }
    var toastMsg by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }

    val currentUrl = pathStack.lastOrNull()

    fun refresh() {
        val ctx = sambaCtx
        if (ctx == null) {
            loading = false
            error = ctxError ?: "Samba client failed to initialise"
            return
        }
        loading = true
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (currentUrl == null) {
                        SambaClient.listShares(ctx, server)
                    } else {
                        SambaClient.listEntries(ctx, currentUrl)
                    }
                }
            }
            result.onSuccess {
                entries = it
                loading = false
            }.onFailure { e ->
                entries = emptyList()
                error = e.message ?: "Failed to list directory"
                loading = false
            }
        }
    }

    LaunchedEffect(server) {
        ctxError = null
        sambaCtx = withContext(Dispatchers.IO) {
            runCatching { SambaClient.buildContext(server) }.getOrElse { e ->
                ctxError = e.message ?: "Samba client failed to initialise"
                null
            }
        }
    }

    LaunchedEffect(currentUrl, sambaCtx) { refresh() }

    BackHandler {
        when {
            multiSelect -> {
                multiSelect = false
                selected = emptySet()
            }
            isSearching -> { isSearching = false; searchQuery = "" }
            pathStack.isNotEmpty() -> pathStack = pathStack.dropLast(1)
            else -> onExit()
        }
    }

    fun exitMultiSelect() {
        multiSelect = false
        selected = emptySet()
    }

    fun showToast(msg: String) {
        toastMsg = msg
        scope.launch {
            kotlinx.coroutines.delay(2200)
            toastMsg = null
        }
    }

    fun requireCtx(): CIFSContext? {
        val c = sambaCtx
        if (c == null) showToast(ctxError ?: "Samba not connected")
        return c
    }

    fun mediaKindFor(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "svg", "webp" -> "image"
            "mp4", "mkv", "webm", "avi", "3gp", "mov", "flv", "ts", "m4v", "mpg", "mpeg" -> "video"
            "mp3", "wav", "ogg", "m4a", "flac", "opus", "aac", "amr", "wma" -> "audio"
            else -> "other"
        }
    }

    // Streams a small/medium remote media file (images, audio) into the app cache
    // so it opens in the in-app viewer/player without permanently downloading.
    fun streamToCacheForOpen(url: String, name: String, kind: String) {
        val ctx = requireCtx() ?: return
        transferState = "Opening $name…" to 0.6f
        scope.launch {
            val dest = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = java.io.File(context.cacheDir, "samba_stream").apply { mkdirs() }
                    dir.listFiles()?.forEach { runCatching { it.delete() } }
                    val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    val f = java.io.File(dir, safe)
                    SambaClient.inputStream(ctx, url).use { ins ->
                        f.outputStream().use { os -> ins.copyTo(os, 256 * 1024) }
                    }
                    f
                }.getOrNull()
            }
            transferState = null
            if (dest != null) {
                onOpenMediaFile(dest, "$kind/*")
            } else {
                showToast("Failed to open $name")
            }
        }
    }

    fun copySelected(isCopy: Boolean) {
        if (selected.isEmpty()) return
        clipboard = SambaClipboard(isCopy = isCopy, urls = selected.toList())
        showToast(if (isCopy) "Copied ${selected.size} item(s)" else "Cut ${selected.size} item(s)")
        exitMultiSelect()
    }

    fun downloadSelected() {
        if (selected.isEmpty()) return
        val shareUrl = pathStack.firstOrNull() ?: SambaClient.serverRootUrl(server)
        scope.launch {
            val ctx = requireCtx() ?: return@launch
            val total = selected.size
            var done = 0
            val destRoot = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "NJSamba/${sanitizeFileName(server.name)}"
            )
            transferState = "Downloading…" to 0f
            cancelled = false
            withContext(Dispatchers.IO) {
                runCatching {
                    selected.forEach { url ->
                        if (cancelled) return@forEach
                        val rel = url.removePrefix(shareUrl).trimStart('/')
                        val dest = File(destRoot, rel.ifBlank { "file_${System.currentTimeMillis()}" })
                        copyRemoteToLocal(ctx, url, dest) {
                            transferState = "Downloading… ${it.name}" to (done.toFloat() + 0.5f) / total
                        }
                        done++
                        transferState = "Downloading…" to done.toFloat() / total
                    }
                }.onFailure { e ->
                    showToast("Download failed: ${e.message}")
                }
            }
            transferState = null
            MediaScannerConnection.scanFile(context, arrayOf(destRoot.absolutePath), null, null)
            showToast(if (cancelled) "Download cancelled" else "Saved to Downloads/NJSamba/${server.name}")
            exitMultiSelect()
        }
    }

    fun openMediaEntry(entry: SambaEntry) {
        val kind = mediaKindFor(entry.name)
        when (kind) {
            "video" -> {
                val ctx = requireCtx()
                if (ctx == null) return
                transferState = "Opening ${entry.name}…" to 0.6f
                scope.launch {
                    val url = withContext(Dispatchers.IO) {
                        com.example.util.SambaMediaStream.start(ctx, entry.url)
                    }
                    transferState = null
                    if (url != null) {
                        onOpenMediaUrl(url, entry.name)
                    } else {
                        showToast("Failed to stream ${entry.name}")
                    }
                }
            }
            "image", "audio" -> streamToCacheForOpen(entry.url, entry.name, kind)
            else -> {
                selected = setOf(entry.url)
                downloadSelected()
            }
        }
    }

    fun uploadToCurrent(localPaths: List<String>) {
        val url = currentUrl ?: return
        scope.launch {
            val ctx = requireCtx() ?: return@launch
            transferState = "Uploading…" to 0f
            cancelled = false
            withContext(Dispatchers.IO) {
                runCatching {
                    localPaths.forEachIndexed { idx, p ->
                        if (cancelled) return@forEachIndexed
                        val f = File(p)
                        val outUrl = SambaClient.childUrl(ctx, url, f.name)
                        transferState = "Uploading… ${f.name}" to idx.toFloat() / localPaths.size
                        f.inputStream().use { ins ->
                            SambaClient.outputStream(ctx, outUrl).use { os ->
                                ins.copyTo(os, 256 * 1024)
                            }
                        }
                        transferState = "Uploading…" to (idx + 1).toFloat() / localPaths.size
                    }
                }.onFailure { e ->
                    showToast("Upload failed: ${e.message}")
                }
            }
            transferState = null
            showToast(if (cancelled) "Upload cancelled" else "Upload complete")
            refresh()
        }
    }

    fun pasteClipboard() {
        val cb = clipboard ?: return
        val url = currentUrl ?: return
        scope.launch {
            val ctx = requireCtx() ?: return@launch
            transferState = "Pasting…" to 0f
            cancelled = false
            withContext(Dispatchers.IO) {
                runCatching {
                    cb.urls.forEachIndexed { idx, srcUrl ->
                        if (cancelled) return@forEachIndexed
                        val name = srcUrl.trimEnd('/').substringAfterLast('/')
                        val destUrl = SambaClient.childUrl(ctx, url, name)
                        transferState = "Pasting… $name" to idx.toFloat() / cb.urls.size
                        if (cb.isCopy) {
                            copyRemote(ctx, srcUrl, destUrl)
                        } else {
                            SambaClient.rename(ctx, srcUrl, destUrl)
                        }
                        transferState = "Pasting…" to (idx + 1).toFloat() / cb.urls.size
                    }
                }.onFailure { e ->
                    showToast("Paste failed: ${e.message}")
                }
            }
            transferState = null
            clipboard = null
            showToast(if (cancelled) "Paste cancelled" else "Paste complete")
            refresh()
        }
    }

    fun deleteSelected() {
        if (selected.isEmpty()) return
        scope.launch {
            val ctx = requireCtx() ?: return@launch
            withContext(Dispatchers.IO) {
                runCatching {
                    selected.forEach { SambaClient.delete(ctx, it) }
                }.onFailure { e ->
                    showToast("Delete failed: ${e.message}")
                }
            }
            exitMultiSelect()
            refresh()
        }
    }

    fun createFolder(name: String) {
        val url = currentUrl ?: return
        scope.launch {
            val ctx = requireCtx() ?: return@launch
            withContext(Dispatchers.IO) {
                runCatching { SambaClient.mkdir(ctx, url, name) }
                    .onFailure { showToast("Failed: ${it.message}") }
            }
            refresh()
        }
    }

    fun renameEntry(entry: SambaEntry, newName: String) {
        scope.launch {
            val ctx = requireCtx() ?: return@launch
            withContext(Dispatchers.IO) {
                val cleanUrl = entry.url.trimEnd('/')
                val parentUrl = cleanUrl.substringBeforeLast('/')
                val newUrl = SambaClient.childUrl(ctx, parentUrl, newName)
                runCatching { SambaClient.rename(ctx, entry.url, newUrl) }
                    .onFailure { showToast("Rename failed: ${it.message}") }
            }
            refresh()
        }
    }

    val uploadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK || result.data == null) return@rememberLauncherForActivityResult
        val clipData = result.data!!.clipData
        val uris = mutableListOf<android.net.Uri>()
        if (clipData != null) {
            for (i in 0 until clipData.itemCount) uris.add(clipData.getItemAt(i).uri)
        } else {
            result.data!!.data?.let { uris.add(it) }
        }
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val paths = uris.mapNotNull { copyUriToCache(context, it)?.absolutePath }
        if (paths.isNotEmpty()) uploadToCurrent(paths)
    }

    val filteredEntries = remember(entries, searchQuery) {
        if (searchQuery.isBlank()) entries
        else entries.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            FloatingTopBar(contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isSearching) {
                            AppSearchField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = "Search items…",
                                onClose = { isSearching = false },
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 4.dp)
                            )
                        } else if (multiSelect) {
                            Text(
                                text = "${selected.size} selected",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 12.dp)
                            )
                            IconButton(onClick = { exitMultiSelect() }) {
                                Icon(AppIcons.Close, contentDescription = "Cancel selection")
                            }
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                            IconButton(onClick = { isSearching = true }) {
                                Icon(AppIcons.Search, contentDescription = "Search")
                            }
                            IconButton(onClick = { refresh() }) {
                                Icon(AppIcons.Refresh, contentDescription = "Refresh")
                            }
                        }
                    }

                    // Path Breadcrumbs Navigation Bar
                    if (pathStack.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SuggestionChip(
                                onClick = { pathStack = emptyList() },
                                label = { Text("Root") }
                            )
                            pathStack.forEachIndexed { idx, url ->
                                Text(" / ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                val segmentName = url.trimEnd('/').substringAfterLast('/').ifBlank { "Share" }
                                SuggestionChip(
                                    onClick = { pathStack = pathStack.take(idx + 1) },
                                    label = { Text(segmentName) }
                                )
                            }
                        }
                    }
                }
            }

            // Actions bar
            if (currentUrl != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (multiSelect) {
                        ActionChip("Copy") { copySelected(isCopy = true) }
                        ActionChip("Cut") { copySelected(isCopy = false) }
                        ActionChip("Download") { downloadSelected() }
                        ActionChip("Delete") { confirmDelete = true }
                    } else {
                        ActionChip("Upload") {
                            uploadLauncher.launch(
                                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                    addCategory(Intent.CATEGORY_OPENABLE)
                                    type = "*/*"
                                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                                }
                            )
                        }
                        ActionChip("New Folder") { showNewFolderDialog = true }
                        if (clipboard != null) {
                            ActionChip("Paste") { pasteClipboard() }
                        }
                    }
                }
            }

            when {
                loading -> {
                    Column(
                        modifier = Modifier.fillMaxSize().landscapeContentWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            if (currentUrl == null) "Connecting to ${server.host}…" else "Loading directory…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                error != null -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            AppIcons.CloudOff,
                            contentDescription = null,
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            error ?: "Error connecting",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Tip: Verify target IP, port, credentials, or specify exact Share Name in server settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { refresh() }) {
                            Text("Retry")
                        }
                    }
                }
                filteredEntries.isEmpty() -> {
                    Column(
                        modifier = Modifier.fillMaxSize().landscapeContentWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            if (searchQuery.isNotBlank()) "No matching files" else if (currentUrl == null) "No public shares found" else "Folder is empty",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        state = browserState,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(filteredEntries, key = { it.url }) { entry ->
                            SambaRow(
                                entry = entry,
                                isSelected = selected.contains(entry.url),
                                multiSelect = multiSelect,
                                onClick = {
                                    if (multiSelect) {
                                        selected = if (selected.contains(entry.url)) selected - entry.url else selected + entry.url
                                    } else if (entry.isDirectory) {
                                        pathStack = pathStack + entry.url
                                    } else {
                                        // Play/view media in-app without downloading;
                                        // everything else downloads on click.
                                        openMediaEntry(entry)
                                    }
                                },
                                onLongClick = {
                                    if (!multiSelect) {
                                        multiSelect = true
                                        selected = setOf(entry.url)
                                    }
                                },
                                onMoreClick = {
                                    if (!multiSelect) {
                                        renameTarget = entry
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        renameTarget?.let { entry ->
            var newName by remember(entry) { mutableStateOf(entry.name) }
            AlertDialog(
                onDismissRequest = { renameTarget = null },
                title = { Text("Rename") },
                text = {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("New name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        renameEntry(entry, newName)
                        renameTarget = null
                    }) { Text("Rename") }
                },
                dismissButton = {
                    TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
                }
            )
        }

        if (showNewFolderDialog) {
            var folderName by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showNewFolderDialog = false },
                title = { Text("New Folder") },
                text = {
                    OutlinedTextField(
                        value = folderName,
                        onValueChange = { folderName = it },
                        label = { Text("Folder name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (folderName.isNotBlank()) createFolder(folderName.trim())
                        showNewFolderDialog = false
                    }) { Text("Create") }
                },
                dismissButton = {
                    TextButton(onClick = { showNewFolderDialog = false }) { Text("Cancel") }
                }
            )
        }

        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("Delete") },
                text = { Text("Delete ${selected.size} item(s) from the server? This cannot be undone.") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDelete = false
                        deleteSelected()
                    }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
                }
            )
        }

        transferState?.let { (msg, p) ->
            Surface(
                color = Color.Black.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxSize()
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().landscapeContentWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = AppCornerRadius.md,
                        modifier = Modifier
                            .padding(32.dp)
                            .fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Text(msg, style = MaterialTheme.typography.titleSmall)
                            Spacer(modifier = Modifier.height(12.dp))
                            LinearProgressIndicator(
                                progress = { p },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            TextButton(
                                onClick = { cancelled = true },
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text("Cancel")
                            }
                        }
                    }
                }
            }
        }

        toastMsg?.let { msg ->
            Surface(
                color = MaterialTheme.colorScheme.inverseSurface,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 90.dp)
            ) {
                Text(
                    text = msg,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }
    }
}

@Composable
private fun ActionChip(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SambaRow(
    entry: SambaEntry,
    isSelected: Boolean,
    multiSelect: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMoreClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
        ),
        shape = AppCornerRadius.sm,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (entry.isDirectory) AppIcons.Folder else AppIcons.InsertDriveFile,
                contentDescription = null,
                tint = if (entry.isDirectory) Color(0xFFF59E0B) else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(26.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!entry.isDirectory) {
                    Text(
                        text = formatSize(entry.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (!entry.isDirectory) {
                IconButton(onClick = onMoreClick, modifier = Modifier.size(28.dp)) {
                    Icon(AppIcons.MoreVert, contentDescription = "More", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String =
    if (bytes <= 0) "0 B" else com.example.ui.components.formatFileSize(bytes)

private fun copyRemoteToLocal(
    context: CIFSContext,
    remoteUrl: String,
    dest: File,
    onFile: (File) -> Unit
) {
    val src = SmbFile(remoteUrl, context)
    if (src.isDirectory) {
        val children = runCatching { src.listFiles() }.getOrNull() ?: emptyArray()
        children.forEach { child ->
            // Same reasoning as the B9 local paste: a download must never destroy a
            // file already sitting at the destination, so resolve a unique name
            // instead of writing straight over it.
            val childDest = StorageHelper.uniqueNameIn(dest, child.name)
            copyRemoteToLocal(context, child.locator.canonicalURL, childDest, onFile)
        }
    } else {
        dest.parentFile?.mkdirs()
        src.inputStream.use { ins ->
            dest.outputStream().use { os -> ins.copyTo(os, 256 * 1024) }
        }
        onFile(dest)
    }
}

private fun copyRemote(context: CIFSContext, srcUrl: String, destUrl: String) {
    val src = SmbFile(srcUrl, context)
    if (src.isDirectory) {
        val dst = SmbFile(destUrl, context)
        dst.mkdirs()
        val children = runCatching { src.listFiles() }.getOrNull() ?: emptyArray()
        children.forEach { child ->
            copyRemote(context, child.locator.canonicalURL, SambaClient.childUrl(context, destUrl, child.name))
        }
    } else {
        src.inputStream.use { ins ->
            SambaClient.outputStream(context, destUrl).use { os -> ins.copyTo(os, 256 * 1024) }
        }
    }
}
