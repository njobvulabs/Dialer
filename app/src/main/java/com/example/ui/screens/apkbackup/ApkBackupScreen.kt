package com.example.ui.screens.apkbackup

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import com.example.ui.components.landscapeContentWidth
import com.example.ui.components.AppIcons
import com.example.ui.components.AppSearchField
import com.example.ui.theme.AppCornerRadius
import com.example.ui.MainViewModel
import com.example.util.rememberSavedListState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class InstalledApp(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val sizeBytes: Long,
    val sourceDirs: List<String>,
    val system: Boolean,
    val installTime: Long,
    val icon: ImageBitmap?
)

// Backups live in a user-visible public Downloads folder when it is writable
// (All-Files access granted), otherwise they fall back to an always-writable
// app-external dir so backups still work on every device (Android 10+ scoped
// storage blocks writing to public Downloads without that permission).
private fun backupRoot(context: Context): File {
    val publicDir: File? = runCatching {
        val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dir = File(base, "NJ-Dialer/APK-Backup")
        if (dir.exists() || dir.mkdirs()) {
            if (dir.canWrite()) dir else null
        } else null
    }.getOrNull()
    return publicDir ?: runCatching {
        val ext = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        val dir = if (ext != null) File(ext, "APK-Backup") else null
        dir?.takeIf { it.exists() || it.mkdirs() } ?: File(context.filesDir, "APK-Backup").also { runCatching { it.mkdirs() } }
    }.getOrDefault(File(context.filesDir, "APK-Backup").also { runCatching { it.mkdirs() } })
}

private fun backupFolderName(app: InstalledApp): String {
    val label = app.label.replace(Regex("[^A-Za-z0-9 _.-]"), "_").trim().take(80)
    val version = app.versionName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(20)
    return "${label}_v${version}_${app.packageName}"
}

private fun getAppBackupFolder(app: InstalledApp, context: Context): File = File(backupRoot(context), backupFolderName(app))

private fun isBackedUp(app: InstalledApp, context: Context): Boolean = getAppBackupFolder(app, context).exists()

private fun getAvailableStorageBytes(): Long {
    return runCatching {
        val stat = StatFs(Environment.getExternalStorageDirectory().path)
        stat.availableBlocksLong * stat.blockSizeLong
    }.getOrDefault(0L)
}

private fun fmtSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private fun loadInstalledApps(context: Context): List<InstalledApp> {
    val pm = context.packageManager
    return runCatching {
        pm.getInstalledPackages(0).orEmpty().mapNotNull { pi ->
            val ai = pi.applicationInfo ?: return@mapNotNull null
            val src = ai.sourceDir ?: return@mapNotNull null
            if (ai.packageName.isEmpty() || src.isEmpty()) return@mapNotNull null
            val splits = listOfNotNull(src) + (ai.splitSourceDirs?.toList() ?: emptyList())
            val total = splits.sumOf { runCatching { File(it).length() }.getOrDefault(0L) }
            val label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrElse { ai.packageName }
            val icon = runCatching {
                val d = pm.getApplicationIcon(ai)
                if (d is BitmapDrawable) d.bitmap.asImageBitmap() else d.toBitmap(80, 80).asImageBitmap()
            }.getOrNull()
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode else pi.versionCode.toLong()
            InstalledApp(
                packageName = ai.packageName,
                label = label,
                versionName = pi.versionName ?: "",
                versionCode = versionCode,
                sizeBytes = total,
                sourceDirs = splits,
                system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                installTime = pi.firstInstallTime,
                icon = icon
            )
        }.sortedBy { it.label.lowercase(Locale.ROOT) }
    }.getOrDefault(emptyList())
}

private fun backupApp(app: InstalledApp, context: Context): Boolean = runCatching {
    val folder = getAppBackupFolder(app, context)
    folder.mkdirs()
    val apkFiles = app.sourceDirs.mapIndexed { idx, src ->
        val outName = if (idx == 0) "base.apk" else "split_${idx}_${File(src).name}"
        val out = File(folder, outName)
        out.parentFile?.mkdirs()
        File(src).copyTo(out, overwrite = true)
        out
    }
    if (apkFiles.size > 1) {
        ZipArchiveOutputStream(BufferedOutputStream(FileOutputStream(File(folder, "split_config.apks")))).use { zos ->
            apkFiles.forEach { f ->
                zos.putArchiveEntry(ZipArchiveEntry(f.name))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeArchiveEntry()
            }
        }
    }
    true
}.getOrElse { false }

/**
 * Installs a split APK set (base + splits) via PackageInstaller so that apps
 * split across multiple APKs install fully. Requires MANAGE_EXTERNAL_STORAGE
 * (or readable backup files), which these backups already rely on.
 */
private fun installSplitApk(context: Context, packageName: String, apkFiles: List<File>): Boolean {
    val installer = context.packageManager.packageInstaller
    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
        setAppPackageName(packageName)
    }
    val sessionId = installer.createSession(params)
    val session = installer.openSession(sessionId)
    return try {
        apkFiles.forEach { apk ->
            val stream = session.openWrite(apk.name, 0, apk.length())
            try {
                FileInputStream(apk).use { input -> input.copyTo(stream) }
                session.fsync(stream)
            } finally {
                stream.close()
            }
        }
        val callbackIntent = Intent(context, ApkInstallerReceiver::class.java)
        val pendingIntent = android.app.PendingIntent.getBroadcast(
            context,
            0,
            callbackIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        session.commit(pendingIntent.intentSender)
        true
    } catch (e: IOException) {
        runCatching { session.abandon() }
        false
    } finally {
        runCatching { session.close() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstalledAppsSection(viewModel: MainViewModel, modifier: Modifier = Modifier.fillMaxWidth()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val listState = rememberSavedListState("apkbackup", viewModel)

    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var backedUpPackages by remember { mutableStateOf<Set<String>>(emptySet()) }
    var loading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(searchQuery.isNotBlank()) }
    var hideSystem by remember { mutableStateOf(true) }
    var sortKey by remember { mutableStateOf("name") }
    var sortAsc by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var backingUp by remember { mutableStateOf(false) }
    var progressText by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf(0f) }
    var detailApp by remember { mutableStateOf<InstalledApp?>(null) }
    var freeStorageSpace by remember { mutableLongStateOf(0L) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showHideMenu by remember { mutableStateOf(false) }
    var showSelectMenu by remember { mutableStateOf(false) }

    fun toast(msg: String) {
        runCatching { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
    }

    fun refreshBackedUpState() {
        scope.launch(Dispatchers.IO) {
            val backedUpSet = apps.filter { isBackedUp(it, context) }.map { it.packageName }.toSet()
            val freeSpace = getAvailableStorageBytes()
            withContext(Dispatchers.Main) {
                backedUpPackages = backedUpSet
                freeStorageSpace = freeSpace
            }
        }
    }

    fun loadApps() {
        loading = true
        scope.launch(Dispatchers.IO) {
            val list = loadInstalledApps(context)
            val backedUpSet = list.filter { isBackedUp(it, context) }.map { it.packageName }.toSet()
            val freeSpace = getAvailableStorageBytes()
            withContext(Dispatchers.Main) {
                apps = list
                backedUpPackages = backedUpSet
                freeStorageSpace = freeSpace
                loading = false
            }
        }
    }

    fun startBackup(targets: List<InstalledApp>) {
        if (backingUp) return
        backingUp = true
        scope.launch(Dispatchers.IO) {
            runCatching { backupRoot(context).mkdirs() }
            var done = 0
            targets.forEach { app ->
                if (backupApp(app, context)) {
                    done++
                    withContext(Dispatchers.Main) {
                        progress = done.toFloat() / targets.size
                        progressText = "Backed up ${app.label} ($done/${targets.size})"
                    }
                }
            }
            withContext(Dispatchers.Main) {
                backingUp = false
                selected = emptySet()
                refreshBackedUpState()
                toast("Backed up $done of ${targets.size} app(s)")
            }
        }
    }

    fun openFolder() {
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", backupRoot(context))
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "resource/folder")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Open backup folder"))
        }.onFailure {
            toast("Open Downloads/NJ-Dialer/APK-Backup in your file manager")
        }
    }

    fun prepareSharableApk(app: InstalledApp): File? {
        val folder = getAppBackupFolder(app, context)
        val backupBase = File(folder, "base.apk")
        if (backupBase.exists()) return backupBase

        val sourceFile = File(app.sourceDirs.firstOrNull() ?: return null)
        val tempShareDir = File(context.cacheDir, "shared_apks")
        tempShareDir.mkdirs()
        val tempFile = File(tempShareDir, "${app.packageName}.apk")
        return runCatching {
            sourceFile.copyTo(tempFile, overwrite = true)
            tempFile
        }.getOrNull()
    }

    fun installApk(app: InstalledApp) {
        scope.launch(Dispatchers.IO) {
            // For split APK apps use a backed-up split set + PackageInstaller so
            // the whole app (base + splits) installs instead of just base.apk.
            val folder = getAppBackupFolder(app, context)
            val splitFiles = folder.listFiles { f ->
                f.isFile && f.extension == "apk"
            }?.sortedBy { it.name }?.toList().orEmpty()
            val hasSplits = splitFiles.size > 1
            if (hasSplits) {
                val installed = withContext(Dispatchers.IO) {
                    runCatching { installSplitApk(context, app.packageName, splitFiles) }.getOrNull() != null
                }
                withContext(Dispatchers.Main) {
                    toast(if (installed) "Installing ${app.label}…" else "Split APK install failed")
                }
                return@launch
            }

            val targetFile = prepareSharableApk(app)
            withContext(Dispatchers.Main) {
                if (targetFile == null || !targetFile.exists()) {
                    toast("Could not locate APK for installation")
                    return@withContext
                }
                runCatching {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", targetFile)
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(intent)
                }.onFailure { toast("Could not start installer") }
            }
        }
    }

    fun openAppInStore(app: InstalledApp) {
        runCatching {
            context.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=${app.packageName}")
                )
            )
        }.onFailure { toast("No Play Store found") }
    }

    fun deleteSelectedBackups() {
        scope.launch(Dispatchers.IO) {
            val targets = apps.filter { selected.contains(it.packageName) }
            var deleted = 0
            targets.forEach { app ->
                val folder = getAppBackupFolder(app, context)
                if (folder.exists() && folder.deleteRecursively()) {
                    deleted++
                }
            }
            withContext(Dispatchers.Main) {
                selected = emptySet()
                refreshBackedUpState()
                toast("Deleted $deleted backup(s)")
            }
        }
    }

    LaunchedEffect(Unit) { loadApps() }

    val filtered = remember(apps, searchQuery, hideSystem, sortKey, sortAsc) {
        var list = apps.filter { !hideSystem || !it.system }

        if (searchQuery.isNotBlank()) {
            val q = searchQuery.lowercase(Locale.ROOT)
            list = list.filter { it.label.lowercase(Locale.ROOT).contains(q) || it.packageName.lowercase(Locale.ROOT).contains(q) }
        }

        list.sortedWith { a, b ->
            val cmp = when (sortKey) {
                "size" -> a.sizeBytes.compareTo(b.sizeBytes)
                "date" -> a.installTime.compareTo(b.installTime)
                "version" -> a.versionCode.compareTo(b.versionCode)
                else -> a.label.lowercase(Locale.ROOT).compareTo(b.label.lowercase(Locale.ROOT))
            }
            if (sortAsc) cmp else -cmp
        }
    }

    val totalSize = remember(apps) { apps.sumOf { it.sizeBytes } }
    val allVisibleSelected = filtered.isNotEmpty() && filtered.all { selected.contains(it.packageName) }

    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.background)
    ) {
            // --- Summary Card ---
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(AppIcons.Sort, contentDescription = "Sort")
                            }
                            DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                                listOf("name" to "Sort by Name", "size" to "Sort by Size", "date" to "Sort by Date", "version" to "Sort by Version").forEach { (key, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label, fontSize = 14.sp) },
                                        leadingIcon = {
                                            if (sortKey == key) Icon(AppIcons.Check, contentDescription = null)
                                            else Icon(AppIcons.Sort, contentDescription = null, modifier = Modifier.size(16.dp))
                                        },
                                        onClick = {
                                            sortKey = key
                                            showSortMenu = false
                                        }
                                    )
                                }
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text(if (sortAsc) "Ascending" else "Descending", fontSize = 14.sp) },
                                    leadingIcon = { Icon(if (sortAsc) AppIcons.ArrowUpward else AppIcons.ArrowDownward, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                    onClick = {
                                        sortAsc = !sortAsc
                                        showSortMenu = false
                                    }
                                )
                            }
                        }
                        Box {
                            IconButton(onClick = { showHideMenu = true }) {
                                Icon(
                                    AppIcons.VisibilityOff,
                                    contentDescription = "Hide system apps",
                                    tint = if (hideSystem) MaterialTheme.colorScheme.primary else LocalContentColor.current
                                )
                            }
                            DropdownMenu(expanded = showHideMenu, onDismissRequest = { showHideMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Hide system apps", fontSize = 14.sp) },
                                    leadingIcon = {
                                        if (hideSystem) Icon(AppIcons.Check, contentDescription = null)
                                        else Icon(AppIcons.VisibilityOff, contentDescription = null, modifier = Modifier.size(16.dp))
                                    },
                                    onClick = {
                                        hideSystem = !hideSystem
                                        showHideMenu = false
                                    }
                                )
                            }
                        }
                        Box {
                            IconButton(onClick = { showSelectMenu = true }) {
                                Icon(AppIcons.DoneAll, contentDescription = "Select all")
                            }
                            DropdownMenu(expanded = showSelectMenu, onDismissRequest = { showSelectMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Select all visible", fontSize = 14.sp) },
                                    leadingIcon = { Icon(AppIcons.CheckCircleOutline, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                    enabled = filtered.isNotEmpty(),
                                    onClick = {
                                        selected = filtered.map { it.packageName }.toSet()
                                        showSelectMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Clear selection", fontSize = 14.sp) },
                                    leadingIcon = { Icon(AppIcons.Clear, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                    enabled = selected.isNotEmpty(),
                                    onClick = {
                                        selected = emptySet()
                                        showSelectMenu = false
                                    }
                                )
                            }
                        }
                        IconButton(onClick = { loadApps() }, enabled = !loading && !backingUp) {
                            Icon(AppIcons.Refresh, contentDescription = "Refresh")
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("${apps.size} apps", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text("${backedUpPackages.size} backed up", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(fmtSize(totalSize), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    }
                    if (freeStorageSpace > 0L) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Free storage: ${fmtSize(freeStorageSpace)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (backingUp) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .height(6.dp)
                        .clip(MaterialTheme.shapes.extraSmall)
                )
                Text(
                    text = progressText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            if (searchOpen) {
                AnimatedVisibility(visible = searchOpen, enter = fadeIn(), exit = fadeOut()) {
                    AppSearchField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = "Search apps...",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            }

            // --- Action Row ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        val targets = filtered.filter { selected.contains(it.packageName) }
                        if (targets.isEmpty()) toast("Select apps to back up") else startBackup(targets)
                    },
                    enabled = !backingUp && selected.isNotEmpty(),
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                ) {
                    Icon(AppIcons.Archive, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Backup (${selected.size})", fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.width(6.dp))
                IconButton(onClick = { searchOpen = !searchOpen }) {
                    Icon(
                        AppIcons.Search,
                        contentDescription = "Search",
                        tint = if (searchOpen || searchQuery.isNotBlank()) MaterialTheme.colorScheme.primary
                        else LocalContentColor.current
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // --- App List ---
            when {
                loading -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 240.dp, max = 520.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                apps.isEmpty() -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 240.dp, max = 520.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No apps found", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                filtered.isEmpty() -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 240.dp, max = 520.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No apps match current filters", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                else -> LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    items(filtered, key = { it.packageName }) { app ->
                        val checked = selected.contains(app.packageName)
                        val backedUp = backedUpPackages.contains(app.packageName)
                        Surface(
                            shape = AppCornerRadius.md,
                            color = if (backedUp) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.clickable { detailApp = app }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(checked = checked, onCheckedChange = { on ->
                                    selected = if (on) selected + app.packageName else selected - app.packageName
                                })
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surface,
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        if (app.icon != null) {
                                            Image(
                                                bitmap = app.icon,
                                                contentDescription = app.label,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.size(40.dp)
                                            )
                                        } else {
                                            Icon(
                                                AppIcons.Android,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = app.label,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        if (app.system) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Surface(
                                                shape = MaterialTheme.shapes.extraSmall,
                                                color = MaterialTheme.colorScheme.surfaceVariant
                                            ) {
                                                Text(
                                                    "System",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                        if (backedUp) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Icon(
                                                AppIcons.CheckCircleOutline,
                                                contentDescription = "Backed up",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        text = app.packageName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "v${app.versionName} • ${fmtSize(app.sizeBytes)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(
                                    onClick = { startBackup(listOf(app)) },
                                    enabled = !backingUp,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = if (backedUp) AppIcons.Replay else AppIcons.FileDownload,
                                        contentDescription = "Backup app",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                    }
                }
            }
        }
    }

    // --- Details Sheet ---
    val app = detailApp
    if (app != null) {
        val isBk = backedUpPackages.contains(app.packageName)
        ModalBottomSheet(onDismissRequest = { detailApp = null }) {
            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(48.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            if (app.icon != null) {
                                Image(
                                    bitmap = app.icon,
                                    contentDescription = app.label,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(48.dp)
                                )
                            } else {
                                Icon(AppIcons.Android, contentDescription = null, modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Text(app.label, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                        Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Version ${app.versionName} • ${fmtSize(app.sizeBytes)}" +
                        if (app.system) " • System app" else "" +
                        "\nInstalled: ${SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(app.installTime))}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        startBackup(listOf(app))
                        detailApp = null
                    },
                    enabled = !backingUp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Icon(AppIcons.Archive, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (isBk) "Re-Backup App" else "Backup This App", fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row {
                    OutlinedButton(onClick = { openFolder() }, modifier = Modifier.weight(1f)) {
                        Icon(AppIcons.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Open Folder")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row {
                    OutlinedButton(onClick = { installApk(app) }, modifier = Modifier.weight(1f)) {
                        Icon(AppIcons.InstallMobile, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Install")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(onClick = { openAppInStore(app) }, modifier = Modifier.weight(1f)) {
                        Icon(AppIcons.Store, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Play Store")
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }


}
