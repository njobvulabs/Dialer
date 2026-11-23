package com.example.ui.screens.sandbox

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.ui.MainViewModel
import com.example.ui.components.AppIcons
import com.example.ui.components.AppPrimaryButton
import com.example.ui.components.AppSearchField
import com.example.ui.components.AppSecondaryButton
import com.example.ui.components.FloatingTopBar
import com.example.ui.components.SearchIconButton
import com.example.ui.components.SectionCard
import com.example.ui.components.landscapeContentWidth
import com.example.util.AssistantActions
import com.example.util.CrashLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.niunaijun.blackbox.BlackBoxCore
import java.io.File
import java.text.DateFormat
import java.util.Date

private data class SandboxApp(
    val packageName: String,
    val label: String,
    val version: String,
    val sharedStorage: Boolean = false
)

private enum class SandboxSortMode(val label: String) {
    Name("Name"),
    Package("Package"),
    Version("Version")
}

private enum class SandboxStatusTone {
    Info,
    Success,
    Error
}

private data class SandboxStatus(
    val message: String,
    val tone: SandboxStatusTone = SandboxStatusTone.Info,
    val time: String? = null
)

private fun sandboxNow(): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())

@Composable
fun SandboxScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val useGlass by viewModel.useGlassmorphism.collectAsState()

    var apps by remember { mutableStateOf<List<SandboxApp>>(emptyList()) }
    var status by remember { mutableStateOf(SandboxStatus("Ready.")) }
    var busy by remember { mutableStateOf(false) }
    var allFilesGranted by remember { mutableStateOf(runCatching { BlackBoxCore.get().hasAllFilesAccess() }.getOrDefault(false)) }
    var pendingLaunchAfterGrant by remember { mutableStateOf<String?>(null) }
    var pendingStorageToggle by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var sortMode by remember { mutableStateOf(SandboxSortMode.Name) }
    var showSortMenu by remember { mutableStateOf(false) }
    var actionMenuFor by remember { mutableStateOf<String?>(null) }
    var confirmUninstall by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf<String?>(null) }
    var diagnostics by remember { mutableStateOf<String?>(null) }
    var diagnosticsTitle by remember { mutableStateOf("Sandbox diagnostics") }
    var diagnosticsApp by remember { mutableStateOf<SandboxApp?>(null) }
    var diagnosticsSaveStatus by remember { mutableStateOf<String?>(null) }

    fun setStatus(message: String, tone: SandboxStatusTone = SandboxStatusTone.Info) {
        status = SandboxStatus(message, tone, sandboxNow())
    }

    fun errorText(error: Throwable): String =
        error.message ?: error.javaClass.simpleName

    fun refreshApps() {
        scope.launch {
            var listError: String? = null
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    BlackBoxCore.get().getInstalledPackages(0, 0).orEmpty().map { info ->
                        val label = runCatching {
                            info.applicationInfo?.loadLabel(context.packageManager)?.toString()
                        }.getOrNull()?.takeIf { it.isNotBlank() } ?: info.packageName
                        val version = listOfNotNull(info.versionName, info.versionCode.toString())
                            .joinToString(" (")
                            .plus(if (info.versionName != null) ")" else "")
                        val sharedStorage = runCatching {
                            BlackBoxCore.get().hasSharedInternalStorageAccess(info.packageName, 0)
                        }.getOrDefault(false)
                        SandboxApp(info.packageName, label, version, sharedStorage)
                    }
                }.getOrElse { e ->
                    listError = "Could not list sandbox apps: ${errorText(e)}"
                    emptyList()
                }
            }
            val granted = withContext(Dispatchers.IO) {
                runCatching { BlackBoxCore.get().hasAllFilesAccess() }.getOrDefault(false)
            }
            apps = loaded
            allFilesGranted = granted
            listError?.let { setStatus(it, SandboxStatusTone.Error) }
        }
    }

    fun installApk(uri: Uri) {
        scope.launch {
            busy = true
            setStatus("Copying APK…")
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val temp = File(context.cacheDir, "sandbox_${System.currentTimeMillis()}.apk")
                    val input = context.contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("Could not open the selected APK")
                    input.use { source -> temp.outputStream().use { source.copyTo(it) } }
                    val installResult = BlackBoxCore.get().installPackageAsUser(temp, 0)
                    runCatching { temp.delete() }
                    installResult
                }
            }
            result.onSuccess { install ->
                if (install.success) {
                    setStatus("Installed ${install.packageName ?: "APK"} in the sandbox.", SandboxStatusTone.Success)
                } else {
                    setStatus("Install failed: ${install.msg ?: "unknown error"}", SandboxStatusTone.Error)
                }
            }.onFailure { e ->
                setStatus("Install failed: ${errorText(e)}", SandboxStatusTone.Error)
            }
            busy = false
            refreshApps()
        }
    }

    val apkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) installApk(uri)
    }

    fun launchApp(app: SandboxApp) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !allFilesGranted) {
            pendingLaunchAfterGrant = app.packageName
            setStatus("All files access is not granted. Grant access to launch ${app.label}.", SandboxStatusTone.Error)
            return
        }
        pendingLaunchAfterGrant = null
        scope.launch {
            busy = true
            setStatus("Launching ${app.label}…")
            val launched = withContext(Dispatchers.IO) {
                runCatching { BlackBoxCore.get().launchApk(app.packageName, 0) }
            }
            val ok = launched.getOrDefault(false)
            val launchError = BlackBoxCore.get().lastLaunchError
            when {
                launched.isFailure -> setStatus(
                    "Launch failed: ${errorText(launched.exceptionOrNull()!!)}",
                    SandboxStatusTone.Error
                )
                ok -> setStatus("Launched ${app.label}.", SandboxStatusTone.Success)
                launchError?.contains("no_all_files_access") == true -> {
                    pendingLaunchAfterGrant = app.packageName
                    setStatus("All files access is required. Grant access and retry.", SandboxStatusTone.Error)
                }
                else -> setStatus("Launch failed: ${launchError ?: "unknown error"}", SandboxStatusTone.Error)
            }
            busy = false
        }
    }

    fun stopApp(app: SandboxApp) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { BlackBoxCore.get().stopPackage(app.packageName, 0) }
            }
            result.fold(
                onSuccess = { setStatus("Stop requested for ${app.label}.", SandboxStatusTone.Info) },
                onFailure = { setStatus("Could not request stop: ${errorText(it)}", SandboxStatusTone.Error) }
            )
        }
    }

    fun clearApp(app: SandboxApp) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { BlackBoxCore.get().clearPackage(app.packageName, 0) }
            }
            result.fold(
                onSuccess = { setStatus("Clear requested for ${app.label}.", SandboxStatusTone.Info) },
                onFailure = { setStatus("Could not request clear: ${errorText(it)}", SandboxStatusTone.Error) }
            )
        }
    }

    fun uninstallApp(app: SandboxApp) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    BlackBoxCore.get().uninstallPackageAsUser(app.packageName, 0)
                    BlackBoxCore.get().setSharedInternalStorageAccess(app.packageName, 0, false)
                }
            }
            result.fold(
                onSuccess = {
                    setStatus("Removal requested for ${app.label}.", SandboxStatusTone.Info)
                    refreshApps()
                },
                onFailure = { setStatus("Could not request removal: ${errorText(it)}", SandboxStatusTone.Error) }
            )
        }
    }

    fun openAllFilesAccess(retryPackage: String? = null) {
        if (retryPackage != null) pendingLaunchAfterGrant = retryPackage
        if (activity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { BlackBoxCore.get().requestAllFilesAccess(activity) }
                .onFailure { setStatus("Could not open All files access settings: ${errorText(it)}", SandboxStatusTone.Error) }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            runCatching { context.startActivity(intent) }
                .onFailure { setStatus("Could not open All files access settings: ${errorText(it)}", SandboxStatusTone.Error) }
        } else {
            setStatus("All files access is not required on this Android version.")
        }
    }

    fun setSharedStorage(app: SandboxApp, enabled: Boolean) {
        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !allFilesGranted) {
            pendingStorageToggle = app.packageName to enabled
            setStatus("Grant all files access before enabling shared folders.", SandboxStatusTone.Error)
            openAllFilesAccess(app.packageName)
            return
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    BlackBoxCore.get().setSharedInternalStorageAccess(app.packageName, 0, enabled)
                    BlackBoxCore.get().stopPackage(app.packageName, 0)
                    BlackBoxCore.get().hasSharedInternalStorageAccess(app.packageName, 0)
                }
            }
            result.fold(
                onSuccess = { active ->
                    apps = apps.map {
                        if (it.packageName == app.packageName) it.copy(sharedStorage = active) else it
                    }
                    setStatus(
                        if (active) "Shared folders enabled for ${app.label}. Relaunch it to apply."
                        else "Virtual storage restored for ${app.label}. Relaunch it to apply.",
                        SandboxStatusTone.Success
                    )
                },
                onFailure = { setStatus("Could not change storage access: ${errorText(it)}", SandboxStatusTone.Error) }
            )
        }
    }

    fun showDiagnostics(app: SandboxApp?) {
        diagnosticsApp = app
        diagnosticsTitle = if (app == null) "Sandbox environment" else "Sandbox diagnostics"
        diagnosticsSaveStatus = null
        diagnostics = "Loading…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                if (app == null) {
                    runCatching { BlackBoxCore.get().getEnvironmentReport() }
                } else {
                    runCatching { BlackBoxCore.get().getDiagnosticReport(app.packageName, 0) }
                }
            }
            result.fold(
                onSuccess = { diagnostics = it },
                onFailure = {
                    diagnostics = "Diagnostics failed: ${errorText(it)}"
                    setStatus("Diagnostics failed: ${errorText(it)}", SandboxStatusTone.Error)
                }
            )
        }
    }

    fun saveDiagnostics() {
        val report = diagnostics ?: return
        val packageName = diagnosticsApp?.packageName ?: "sandbox"
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching { CrashLog.saveSandboxReport(report, packageName).absolutePath }
            }
            val message = saved.fold(
                onSuccess = { path -> "Saved to $path" },
                onFailure = { e -> "Could not save: ${errorText(e)}" }
            )
            diagnosticsSaveStatus = message
            setStatus(
                message,
                if (saved.isSuccess) SandboxStatusTone.Success else SandboxStatusTone.Error
            )
        }
    }

    fun copyDiagnostics() {
        val report = diagnostics ?: return
        AssistantActions.copyText(context, report)
        diagnosticsSaveStatus = "Copied to clipboard"
        setStatus("Diagnostics copied to clipboard.", SandboxStatusTone.Success)
    }

    fun exportCrashLogs() {
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    val report = StringBuilder()
                    fun appendFile(title: String, file: File, maxChars: Int = 30000) {
                        report.append("\n=== ").append(title).append(" ===\n")
                        if (!file.exists()) {
                            report.append("(missing: ").append(file.absolutePath).append(")\n")
                            return
                        }
                        val text = runCatching { file.readText() }.getOrDefault("(unreadable)")
                        if (text.length > maxChars) {
                            report.append("(truncated, last ").append(maxChars).append(" chars)\n")
                            report.append(text.takeLast(maxChars))
                        } else {
                            report.append(text)
                        }
                    }

                    val diagDir = File(context.filesDir, "bb_diag")
                    appendFile("BBDiag crash", File(diagDir, "crash.txt"))
                    appendFile("BBDiag stage", File(diagDir, "stage.txt"), 4000)
                    appendFile("BBDiag trace", File(diagDir, "trace.log"))

                    val crashDir = File(context.filesDir, "crash_logs")
                    val logs = crashDir.listFiles { file ->
                        file.isFile && file.name.endsWith(".log", ignoreCase = true)
                    }.orEmpty().sortedByDescending { it.lastModified() }.take(8)
                    if (logs.isEmpty()) {
                        report.append("\n=== CrashMonitor logs ===\n(no logs in ")
                            .append(crashDir.absolutePath).append(")\n")
                    } else {
                        logs.forEachIndexed { index, file ->
                            appendFile("CrashMonitor ${index + 1}: ${file.name}", file)
                        }
                    }
                    CrashLog.saveSandboxReport(report.toString(), "crashlogs").absolutePath
                }
            }
            val message = saved.fold(
                onSuccess = { path -> "Saved crash logs to $path" },
                onFailure = { e -> "Could not export crash logs: ${errorText(e)}" }
            )
            setStatus(
                message,
                if (saved.isSuccess) SandboxStatusTone.Success else SandboxStatusTone.Error
            )
        }
    }

    fun handleResume() {
        refreshApps()
        val pending = pendingLaunchAfterGrant
        val pendingToggle = pendingStorageToggle
        if (pending == null && pendingToggle == null) return
        val granted = runCatching { BlackBoxCore.get().hasAllFilesAccess() }.getOrDefault(false)
        if (!granted) return
        allFilesGranted = true
        if (pendingToggle != null) {
            pendingStorageToggle = null
            val app = apps.firstOrNull { it.packageName == pendingToggle.first }
            if (app != null) {
                setSharedStorage(app, pendingToggle.second)
                return
            }
        }
        if (pending != null) {
            pendingLaunchAfterGrant = null
            val app = apps.firstOrNull { it.packageName == pending }
            if (app != null) {
                launchApp(app)
                return
            }
        }
        setStatus("All files access granted. Open the app from the list to retry.", SandboxStatusTone.Success)
    }

    val refreshUpdater = rememberUpdatedState { refreshApps() }
    val resumeUpdater = rememberUpdatedState { handleResume() }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeUpdater.value()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val searchFocusRequester = remember { FocusRequester() }
    LaunchedEffect(showSearch) {
        if (showSearch) runCatching { searchFocusRequester.requestFocus() }
    }

    val filteredApps = remember(apps, searchQuery, sortMode) {
        val query = searchQuery.trim()
        val matched = if (query.isBlank()) {
            apps
        } else {
            apps.filter { app ->
                app.label.contains(query, ignoreCase = true) ||
                    app.packageName.contains(query, ignoreCase = true) ||
                    app.version.contains(query, ignoreCase = true)
            }
        }
        when (sortMode) {
            SandboxSortMode.Name -> matched.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            SandboxSortMode.Package -> matched.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.packageName })
            SandboxSortMode.Version -> matched.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.version })
        }
    }

    val statusColor = when (status.tone) {
        SandboxStatusTone.Info -> MaterialTheme.colorScheme.onSurface
        SandboxStatusTone.Success -> MaterialTheme.colorScheme.primary
        SandboxStatusTone.Error -> MaterialTheme.colorScheme.error
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            FloatingTopBar(useGlass = useGlass) {
                Icon(AppIcons.Android, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text(
                    "APK Sandbox",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { refreshUpdater.value() }) {
                    Icon(AppIcons.Refresh, contentDescription = "Refresh")
                }
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .landscapeContentWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp,
                    vertical = 12.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    SectionCard(title = "Install an APK") {
                        Text(
                            "Runs simple utility APKs in a virtual Android environment. " +
                                "Banking apps, Play Integrity apps, Google services and 32-bit-only apps are likely to fail.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        AppPrimaryButton(
                            text = "Choose APK",
                            icon = AppIcons.FolderOpen,
                            enabled = !busy,
                            onClick = { apkPicker.launch(arrayOf("application/vnd.android.package-archive")) }
                        )
                        if (busy) {
                            Spacer(Modifier.height(10.dp))
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        }
                    }
                }

                item {
                    SectionCard(title = "Environment") {
                        Text(
                            if (allFilesGranted) "All files access: granted"
                            else "All files access: not granted — launching cloned apps may be blocked.",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (allFilesGranted) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.error
                        )
                        if (!allFilesGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            Spacer(Modifier.height(10.dp))
                            AppSecondaryButton(
                                text = if (pendingLaunchAfterGrant != null) "Grant and retry" else "Grant all files access",
                                icon = AppIcons.LockOpen,
                                onClick = { openAllFilesAccess(pendingLaunchAfterGrant) }
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        AppSecondaryButton(
                            text = "Environment report",
                            icon = AppIcons.Info,
                            onClick = { showDiagnostics(null) }
                        )
                        Spacer(Modifier.height(8.dp))
                        AppSecondaryButton(
                            text = "Export crash logs",
                            icon = AppIcons.SaveAlt,
                            onClick = { exportCrashLogs() }
                        )
                    }
                }

                item {
                    SectionCard(title = "Last action") {
                        Text(
                            if (status.time != null) "${status.time}  ${status.message}" else status.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = statusColor
                        )
                        val pending = pendingLaunchAfterGrant
                        if (pending != null && allFilesGranted) {
                            Spacer(Modifier.height(10.dp))
                            val pendingApp = apps.firstOrNull { it.packageName == pending }
                            if (pendingApp != null) {
                                AppSecondaryButton(
                                    text = "Retry launch",
                                    icon = AppIcons.PlayArrow,
                                    onClick = {
                                        pendingLaunchAfterGrant = null
                                        launchApp(pendingApp)
                                    }
                                )
                            }
                        }
                    }
                }

                item {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Sandboxed apps (${filteredApps.size}/${apps.size})",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f)
                            )
                            SearchIconButton(
                                isActive = showSearch || searchQuery.isNotEmpty(),
                                onClick = { showSearch = !showSearch }
                            )
                            Box {
                                IconButton(onClick = { showSortMenu = true }) {
                                    Icon(AppIcons.Sort, contentDescription = "Sort")
                                }
                                DropdownMenu(
                                    expanded = showSortMenu,
                                    onDismissRequest = { showSortMenu = false }
                                ) {
                                    SandboxSortMode.entries.forEach { mode ->
                                        DropdownMenuItem(
                                            text = { Text(mode.label) },
                                            onClick = {
                                                sortMode = mode
                                                showSortMenu = false
                                            },
                                            trailingIcon = if (sortMode == mode) {
                                                {
                                                    Icon(
                                                        AppIcons.CheckCircle,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                            } else null
                                        )
                                    }
                                }
                            }
                        }
                        AnimatedVisibility(visible = showSearch) {
                            AppSearchField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                placeholder = "Search sandboxed apps",
                                onClose = {
                                    searchQuery = ""
                                    showSearch = false
                                },
                                focusRequester = searchFocusRequester
                            )
                        }
                    }
                }

                if (filteredApps.isEmpty()) {
                    item {
                        Text(
                            if (searchQuery.isBlank()) "No APKs installed yet."
                            else "No sandboxed apps match “${searchQuery.trim()}”.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                items(filteredApps, key = { it.packageName }) { app ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    app.label,
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                Box {
                                    IconButton(onClick = { actionMenuFor = app.packageName }) {
                                        Icon(AppIcons.MoreVert, contentDescription = "App actions")
                                    }
                                    DropdownMenu(
                                        expanded = actionMenuFor == app.packageName,
                                        onDismissRequest = { actionMenuFor = null }
                                    ) {
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    if (app.sharedStorage) "Storage: Shared folders"
                                                    else "Storage: Virtual"
                                                )
                                            },
                                            leadingIcon = {
                                                Icon(AppIcons.Storage, contentDescription = null)
                                            },
                                            onClick = {
                                                actionMenuFor = null
                                                setSharedStorage(app, !app.sharedStorage)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Stop") },
                                            leadingIcon = { Icon(AppIcons.Stop, contentDescription = null) },
                                            onClick = {
                                                actionMenuFor = null
                                                stopApp(app)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Clear data") },
                                            leadingIcon = { Icon(AppIcons.Delete, contentDescription = null) },
                                            onClick = {
                                                actionMenuFor = null
                                                confirmClear = app.packageName
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Info") },
                                            leadingIcon = { Icon(AppIcons.Info, contentDescription = null) },
                                            onClick = {
                                                actionMenuFor = null
                                                showDiagnostics(app)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Remove") },
                                            leadingIcon = { Icon(AppIcons.Delete, contentDescription = null) },
                                            onClick = {
                                                actionMenuFor = null
                                                confirmUninstall = app.packageName
                                            }
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "${app.packageName}\n${app.version}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Storage: ${if (app.sharedStorage) "Shared folders" else "Virtual"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (app.sharedStorage) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(10.dp))
                            AppSecondaryButton(
                                text = "Launch",
                                icon = AppIcons.PlayArrow,
                                enabled = !busy,
                                onClick = { launchApp(app) }
                            )
                        }
                    }
                }
            }
        }
    }

    confirmClear?.let { pkg ->
        val app = apps.firstOrNull { it.packageName == pkg }
        AlertDialog(
            onDismissRequest = { confirmClear = null },
            title = { Text("Clear app data?") },
            text = { Text("Clear all sandbox data for ${app?.label ?: pkg}?") },
            confirmButton = {
                TextButton(onClick = {
                    app?.let { clearApp(it) }
                    confirmClear = null
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = null }) { Text("Cancel") } }
        )
    }

    confirmUninstall?.let { pkg ->
        val app = apps.firstOrNull { it.packageName == pkg }
        AlertDialog(
            onDismissRequest = { confirmUninstall = null },
            title = { Text("Remove sandboxed app?") },
            text = { Text("Remove ${app?.label ?: pkg} and its sandbox data?") },
            confirmButton = {
                TextButton(onClick = {
                    app?.let { uninstallApp(it) }
                    confirmUninstall = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmUninstall = null }) { Text("Cancel") } }
        )
    }

    diagnostics?.let { report ->
        AlertDialog(
            onDismissRequest = {
                diagnostics = null
                diagnosticsApp = null
                diagnosticsSaveStatus = null
            },
            title = { Text(diagnosticsTitle) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    SelectionContainer {
                        Text(
                            report,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                        )
                    }
                    diagnosticsSaveStatus?.let { message ->
                        Spacer(Modifier.height(10.dp))
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { saveDiagnostics() }) { Text("Save to NJCrash") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { copyDiagnostics() }) { Text("Copy") }
                    TextButton(onClick = {
                        diagnostics = null
                        diagnosticsApp = null
                        diagnosticsSaveStatus = null
                    }) { Text("Close") }
                }
            }
        )
    }
}
