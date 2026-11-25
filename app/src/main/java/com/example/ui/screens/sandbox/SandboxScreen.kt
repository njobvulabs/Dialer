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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.example.ui.components.AppSearchField
import com.example.ui.components.AppSecondaryButton
import com.example.ui.components.FloatingTopBar
import com.example.ui.components.PillTabRow
import com.example.ui.components.SearchIconButton
import com.example.ui.components.SectionCard
import com.example.ui.components.landscapeContentWidth
import com.example.util.AssistantActions
import kotlinx.coroutines.launch
import top.niunaijun.blackbox.BlackBoxCore
import java.text.DateFormat
import java.util.Date

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

private data class SandboxPendingFolderToggle(
    val packageName: String,
    val folder: String? = null,
    val enabled: Boolean
)

private fun sandboxNow(): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())

@OptIn(ExperimentalMaterial3Api::class)
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
    var pendingStorageToggle by remember { mutableStateOf<SandboxPendingFolderToggle?>(null) }
    var showInstallMenu by remember { mutableStateOf(false) }
    var showManagerSheet by remember { mutableStateOf(false) }
    var managerTab by remember { mutableStateOf("Settings") }
    var defaultSharedFolders by remember { mutableStateOf(SandboxPrefs.defaultSharedFolders(context)) }
    var applyDefaultsOnInstall by remember { mutableStateOf(SandboxPrefs.applyDefaultsOnInstall(context)) }
    var confirmBeforeStop by remember { mutableStateOf(SandboxPrefs.confirmBeforeStop(context)) }
    var keepSafBridge by remember { mutableStateOf(SandboxPrefs.keepSafBridge(context)) }
    var storageFolderApp by remember { mutableStateOf<SandboxApp?>(null) }
    var confirmStopApp by remember { mutableStateOf<SandboxApp?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var sortMode by remember { mutableStateOf(SandboxSortMode.Name) }
    var showSortMenu by remember { mutableStateOf(false) }
    var actionMenuFor by remember { mutableStateOf<String?>(null) }
    var confirmUninstall by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf<String?>(null) }
    var pendingInstall by remember { mutableStateOf<SandboxApkInspection?>(null) }
    var diagnostics by remember { mutableStateOf<String?>(null) }
    var diagnosticsTitle by remember { mutableStateOf("Sandbox diagnostics") }
    var diagnosticsApp by remember { mutableStateOf<SandboxApp?>(null) }
    var diagnosticsSaveStatus by remember { mutableStateOf<String?>(null) }
    var currentUserId by remember { mutableStateOf(SandboxPrefs.selectedUser(context)) }
    var users by remember { mutableStateOf<List<SandboxUser>>(emptyList()) }
    var showCloneInstalled by remember { mutableStateOf(false) }
    var hostApps by remember { mutableStateOf<List<SandboxHostApp>>(emptyList()) }
    var hostAppQuery by remember { mutableStateOf("") }
    var cloneTargetFor by remember { mutableStateOf<SandboxApp?>(null) }
    var runningPackages by remember { mutableStateOf<Set<String>>(emptySet()) }
    var stopAppsOnLeave by remember { mutableStateOf(SandboxPrefs.stopAppsOnLeave(context)) }
    var fakeLocationFor by remember { mutableStateOf<SandboxApp?>(null) }
    var fakeLocationMode by remember { mutableStateOf("Off") }
    var fakeLocationLat by remember { mutableStateOf("") }
    var fakeLocationLng by remember { mutableStateOf("") }
    var showGlobalLocation by remember { mutableStateOf(false) }
    var globalLocationLat by remember { mutableStateOf("") }
    var globalLocationLng by remember { mutableStateOf("") }
    var applyGlobalToAll by remember { mutableStateOf(false) }

    fun setStatus(message: String, tone: SandboxStatusTone = SandboxStatusTone.Info) {
        status = SandboxStatus(message, tone, sandboxNow())
    }

    fun errorText(error: Throwable): String =
        error.message ?: error.javaClass.simpleName

    fun refreshRunning() {
        scope.launch {
            val loaded = apps
            runningPackages = loaded.filter { app ->
                SandboxActions.isRunning(app.packageName, app.userId)
            }.map { it.packageName }.toSet()
        }
    }

    fun refreshApps() {
        scope.launch {
            val loaded = runCatching { SandboxActions.loadApps(context, currentUserId) }
            allFilesGranted = SandboxActions.hasAllFilesAccess()
            loaded.fold(
                onSuccess = {
                    apps = it
                    refreshRunning()
                },
                onFailure = { setStatus("Could not list sandbox apps: ${errorText(it)}", SandboxStatusTone.Error) }
            )
        }
    }

    fun refreshUsers() {
        scope.launch {
            users = SandboxActions.users()
        }
    }

    fun updateAppFolders(packageName: String) {
        apps = apps.map { app ->
            if (app.packageName == packageName) {
                app.copy(
                    sharedFolders = runCatching {
                        BlackBoxCore.get().getSharedFolders(packageName, app.userId)
                    }.getOrDefault(app.sharedFolders)
                )
            } else app
        }
    }

    fun inspectApk(uri: Uri) {
        scope.launch {
            busy = true
            setStatus("Reading APK…")
            val inspection = SandboxActions.inspectApk(context, uri, currentUserId)
            busy = false
            if (inspection.error != null) {
                SandboxActions.discardInspection(inspection)
                setStatus(inspection.error, SandboxStatusTone.Error)
            } else {
                pendingInstall = inspection
            }
        }
    }

    fun installApk(inspection: SandboxApkInspection) {
        scope.launch {
            busy = true
            setStatus("Installing ${inspection.preview?.label ?: "APK"}…")
            val result = SandboxActions.installApk(context, inspection, currentUserId)
            busy = false
            pendingInstall = null
            setStatus(result.message, if (result.ok) SandboxStatusTone.Success else SandboxStatusTone.Error)
            refreshApps()
        }
    }

    val apkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) inspectApk(uri)
    }

    fun openApkPicker() {
        apkPicker.launch(
            arrayOf(
                "application/vnd.android.package-archive",
                "application/zip",
                "application/x-zip-compressed",
                "application/octet-stream"
            )
        )
    }

    fun openCloneInstalled() {
        scope.launch {
            busy = true
            hostApps = SandboxActions.loadHostApps(context)
            busy = false
            hostAppQuery = ""
            showCloneInstalled = true
        }
    }

    fun cloneHostApp(hostApp: SandboxHostApp) {
        scope.launch {
            busy = true
            setStatus("Cloning ${hostApp.label}…")
            val result = SandboxActions.cloneHostApp(context, hostApp.packageName, hostApp.label, currentUserId)
            busy = false
            setStatus(result.message, if (result.ok) SandboxStatusTone.Success else SandboxStatusTone.Error)
            if (result.ok) {
                showCloneInstalled = false
                refreshApps()
            }
        }
    }

    fun cloneAppToUser(app: SandboxApp, targetUserId: Int) {
        scope.launch {
            busy = true
            setStatus("Cloning ${app.label} to user $targetUserId…")
            val result = SandboxActions.cloneToUser(context, app, targetUserId)
            busy = false
            cloneTargetFor = null
            setStatus(result.message, if (result.ok) SandboxStatusTone.Success else SandboxStatusTone.Error)
            if (result.ok && targetUserId == currentUserId) refreshApps()
            refreshUsers()
        }
    }

    fun switchUser(userId: Int) {
        if (userId == currentUserId) return
        if (stopAppsOnLeave) SandboxActions.stopRunningAppsNow(currentUserId)
        currentUserId = userId
        SandboxPrefs.setSelectedUser(context, userId)
        refreshApps()
    }

    fun createUser() {
        scope.launch {
            val result = SandboxActions.createUser()
            setStatus(result.message, if (result.ok) SandboxStatusTone.Success else SandboxStatusTone.Error)
            refreshUsers()
        }
    }

    fun deleteUser(user: SandboxUser) {
        scope.launch {
            val result = SandboxActions.deleteUser(user.id)
            setStatus(result.message, if (result.ok) SandboxStatusTone.Success else SandboxStatusTone.Error)
            if (result.ok && currentUserId == user.id) switchUser(0)
            refreshUsers()
        }
    }

    fun openFakeLocation(app: SandboxApp) {
        val location = SandboxActions.fakeLocation(app.packageName, app.userId)
        fakeLocationFor = app
        fakeLocationMode = when (runCatching {
            top.niunaijun.blackbox.fake.frameworks.BLocationManager.get().getPattern(app.userId, app.packageName)
        }.getOrDefault(0)) {
            1 -> "Global"
            2 -> "Custom"
            else -> "Off"
        }
        fakeLocationLat = location?.latitude?.toString() ?: ""
        fakeLocationLng = location?.longitude?.toString() ?: ""
    }

    fun applyFakeLocation() {
        val app = fakeLocationFor ?: return
        val result = when (fakeLocationMode) {
            "Off" -> SandboxActions.setFakeLocation(app.packageName, app.userId, null)
            "Global" -> SandboxActions.useGlobalFakeLocation(app.packageName, app.userId)
            else -> {
                val lat = fakeLocationLat.toDoubleOrNull()
                val lng = fakeLocationLng.toDoubleOrNull()
                if (lat == null || lng == null) {
                    SandboxActionResult(false, "Enter a valid latitude and longitude.")
                } else {
                    SandboxActions.setFakeLocation(
                        app.packageName,
                        app.userId,
                        SandboxFakeLocation(lat, lng)
                    )
                }
            }
        }
        setStatus(result.message, if (result.ok) SandboxStatusTone.Success else SandboxStatusTone.Error)
        if (result.ok) fakeLocationFor = null
    }

    fun openGlobalLocation() {
        val location = SandboxActions.globalFakeLocation()
        globalLocationLat = location?.latitude?.toString() ?: ""
        globalLocationLng = location?.longitude?.toString() ?: ""
        showGlobalLocation = true
    }

    fun applyGlobalLocation() {
        val lat = globalLocationLat.toDoubleOrNull()
        val lng = globalLocationLng.toDoubleOrNull()
        if (globalLocationLat.isBlank() && globalLocationLng.isBlank()) {
            val result = SandboxActions.setGlobalFakeLocation(null, applyGlobalToAll, apps)
            setStatus(result.message, if (result.ok) SandboxStatusTone.Success else SandboxStatusTone.Error)
            if (result.ok) showGlobalLocation = false
            return
        }
        if (lat == null || lng == null) {
            setStatus("Enter a valid latitude and longitude.", SandboxStatusTone.Error)
            return
        }
        val result = SandboxActions.setGlobalFakeLocation(
            SandboxFakeLocation(lat, lng),
            applyGlobalToAll,
            apps
        )
        setStatus(result.message, if (result.ok) SandboxStatusTone.Success else SandboxStatusTone.Error)
        if (result.ok) showGlobalLocation = false
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

    fun launchApp(app: SandboxApp) {
        if (SandboxActions.needsAllFilesAccessPrompt() && !allFilesGranted) {
            pendingLaunchAfterGrant = app.packageName
            setStatus(SandboxActions.allFilesAccessMessage(), SandboxStatusTone.Error)
            openAllFilesAccess(app.packageName)
            return
        }
        pendingLaunchAfterGrant = null
        scope.launch {
            busy = true
            setStatus("Launching ${app.label}…")
            val result = SandboxActions.launch(app.packageName, app.label, app.userId, allFilesGranted)
            busy = false
            refreshRunning()
            if (result.needsAllFilesAccess) {
                pendingLaunchAfterGrant = app.packageName
                openAllFilesAccess(app.packageName)
            }
            setStatus(result.message, if (result.ok) SandboxStatusTone.Success else SandboxStatusTone.Error)
        }
    }

    fun performStop(app: SandboxApp) {
        scope.launch {
            val result = SandboxActions.stop(app.packageName, app.label, app.userId)
            setStatus(result.message, if (result.ok) SandboxStatusTone.Info else SandboxStatusTone.Error)
            refreshRunning()
        }
    }

    fun stopApp(app: SandboxApp) {
        if (SandboxPrefs.confirmBeforeStop(context)) confirmStopApp = app
        else performStop(app)
    }

    fun clearApp(app: SandboxApp) {
        scope.launch {
            val result = SandboxActions.clear(app.packageName, app.label, app.userId)
            setStatus(result.message, if (result.ok) SandboxStatusTone.Info else SandboxStatusTone.Error)
        }
    }

    fun uninstallApp(app: SandboxApp) {
        scope.launch {
            val result = SandboxActions.remove(app.packageName, app.label, app.userId)
            setStatus(result.message, if (result.ok) SandboxStatusTone.Success else SandboxStatusTone.Error)
            if (result.ok) refreshApps()
        }
    }

    fun openAppSettings(app: SandboxApp) {
        runCatching {
            context.startActivity(
                SandboxAppSettingsActivity.intent(
                    context,
                    app.packageName,
                    userId = app.userId
                )
            )
        }
            .onFailure { setStatus("Could not open app settings: ${errorText(it)}", SandboxStatusTone.Error) }
    }

    fun setSharedFolders(app: SandboxApp, folders: Set<String>) {
        val enabling = folders.isNotEmpty()
        if (enabling && SandboxActions.needsAllFilesAccessPrompt() && !allFilesGranted) {
            pendingStorageToggle = SandboxPendingFolderToggle(app.packageName, null, true)
            setStatus(SandboxActions.allFilesAccessMessage(), SandboxStatusTone.Error)
            openAllFilesAccess(app.packageName)
            return
        }
        scope.launch {
            val result = SandboxActions.setSharedFolders(app.packageName, app.label, app.userId, folders, allFilesGranted)
            if (result.needsAllFilesAccess) {
                pendingStorageToggle = SandboxPendingFolderToggle(app.packageName, null, true)
                openAllFilesAccess(app.packageName)
            }
            if (result.ok) updateAppFolders(app.packageName)
            setStatus(result.message, if (result.ok) SandboxStatusTone.Success else SandboxStatusTone.Error)
        }
    }

    fun setSharedStorage(app: SandboxApp, enabled: Boolean) {
        setSharedFolders(app, if (enabled) SandboxStorageFolders.ALL else emptySet())
    }

    fun setSharedFolder(app: SandboxApp, folder: String, enabled: Boolean) {
        if (enabled && SandboxActions.needsAllFilesAccessPrompt() && !allFilesGranted) {
            pendingStorageToggle = SandboxPendingFolderToggle(app.packageName, folder, true)
            setStatus(SandboxActions.allFilesAccessMessage(), SandboxStatusTone.Error)
            openAllFilesAccess(app.packageName)
            return
        }
        scope.launch {
            val result = SandboxActions.setSharedFolder(app.packageName, app.label, app.userId, folder, enabled, allFilesGranted)
            if (result.needsAllFilesAccess) {
                pendingStorageToggle = SandboxPendingFolderToggle(app.packageName, folder, true)
                openAllFilesAccess(app.packageName)
            }
            if (result.ok) updateAppFolders(app.packageName)
            setStatus(result.message, if (result.ok) SandboxStatusTone.Success else SandboxStatusTone.Error)
        }
    }

    fun showDiagnostics(app: SandboxApp?) {
        diagnosticsApp = app
        diagnosticsTitle = if (app == null) "Sandbox environment" else "Sandbox diagnostics"
        diagnosticsSaveStatus = null
        diagnostics = "Loading…"
        scope.launch {
            val result = if (app == null) {
                SandboxActions.environmentReport()
            } else {
                SandboxActions.diagnosticReport(app.packageName, app.userId)
            }
            diagnostics = result
        }
    }

    fun saveDiagnostics() {
        val report = diagnostics ?: return
        val packageName = diagnosticsApp?.packageName ?: "sandbox"
        scope.launch {
            val message = SandboxActions.saveReport(context, report, packageName)
            diagnosticsSaveStatus = message
            setStatus(message, if (message.startsWith("Saved")) SandboxStatusTone.Success else SandboxStatusTone.Error)
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
            val message = SandboxActions.exportCrashLogs(context, null, 0)
            setStatus(message, if (message.startsWith("Saved")) SandboxStatusTone.Success else SandboxStatusTone.Error)
        }
    }

    fun handleResume() {
        refreshApps()
        val pending = pendingLaunchAfterGrant
        val pendingToggle = pendingStorageToggle
        if (pending == null && pendingToggle == null) return
        val granted = SandboxActions.hasAllFilesAccess()
        if (!granted) return
        allFilesGranted = true
        if (pendingToggle != null) {
            pendingStorageToggle = null
            val app = apps.firstOrNull { it.packageName == pendingToggle.packageName }
            if (app != null) {
                if (pendingToggle.folder == null) {
                    setSharedStorage(app, pendingToggle.enabled)
                } else {
                    setSharedFolder(app, pendingToggle.folder, pendingToggle.enabled)
                }
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

    LaunchedEffect(currentUserId) {
        refreshApps()
        refreshUsers()
    }

    DisposableEffect(currentUserId) {
        onDispose {
            if (SandboxPrefs.stopAppsOnLeave(context)) {
                SandboxActions.stopRunningAppsNow(currentUserId)
            }
        }
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
                Box {
                    IconButton(onClick = { showInstallMenu = true }) {
                        Icon(AppIcons.Add, contentDescription = "Install or clone app")
                    }
                    DropdownMenu(
                        expanded = showInstallMenu,
                        onDismissRequest = { showInstallMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Choose APK") },
                            leadingIcon = { Icon(AppIcons.FolderOpen, contentDescription = null) },
                            enabled = !busy,
                            onClick = {
                                showInstallMenu = false
                                openApkPicker()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Clone installed app") },
                            leadingIcon = { Icon(AppIcons.ContentCopy, contentDescription = null) },
                            enabled = !busy,
                            onClick = {
                                showInstallMenu = false
                                openCloneInstalled()
                            }
                        )
                    }
                }
                IconButton(onClick = { showManagerSheet = true }) {
                    Icon(AppIcons.Tune, contentDescription = "Sandbox manager")
                }
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
                if (busy || status.time != null || !allFilesGranted || pendingLaunchAfterGrant != null) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val statusText = buildString {
                                if (!allFilesGranted) append("All files access off — Manage → Environment. ")
                                if (status.time != null) append("${status.time}  ")
                                append(status.message)
                            }
                            Text(
                                statusText,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (!allFilesGranted) MaterialTheme.colorScheme.error else statusColor,
                                modifier = Modifier.weight(1f)
                            )
                            val pending = pendingLaunchAfterGrant
                            if (pending != null && allFilesGranted) {
                                val pendingApp = apps.firstOrNull { it.packageName == pending }
                                if (pendingApp != null) {
                                    TextButton(
                                        onClick = {
                                            pendingLaunchAfterGrant = null
                                            launchApp(pendingApp)
                                        }
                                    ) {
                                        Text("Retry")
                                    }
                                }
                            }
                            if (busy) {
                                Spacer(Modifier.width(8.dp))
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
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

                items(filteredApps, key = { "${it.userId}:${it.packageName}" }) { app ->
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
                                                    if (app.sharedFolders.isEmpty()) "Manage shared folders"
                                                    else "Shared folders (${app.sharedFolders.size})"
                                                )
                                            },
                                            leadingIcon = {
                                                Icon(AppIcons.Storage, contentDescription = null)
                                            },
                                            onClick = {
                                                actionMenuFor = null
                                                storageFolderApp = app
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Fake location") },
                                            leadingIcon = { Icon(AppIcons.LocationOn, contentDescription = null) },
                                            onClick = {
                                                actionMenuFor = null
                                                openFakeLocation(app)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Clone to another user") },
                                            leadingIcon = { Icon(AppIcons.SwapHoriz, contentDescription = null) },
                                            onClick = {
                                                actionMenuFor = null
                                                cloneTargetFor = app
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("App settings") },
                                            leadingIcon = { Icon(AppIcons.Settings, contentDescription = null) },
                                            onClick = {
                                                actionMenuFor = null
                                                openAppSettings(app)
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
                                "Storage: ${if (app.sharedFolders.isEmpty()) "Virtual" else app.sharedFolders.sorted().joinToString(", ")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (app.sharedFolders.isNotEmpty()) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "User ${app.userId} · ${if (app.packageName in runningPackages) "Running" else "Stopped"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (app.packageName in runningPackages) MaterialTheme.colorScheme.primary
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

    if (showManagerSheet) {
        ModalBottomSheet(
            onDismissRequest = { showManagerSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                PillTabRow(
                    tabs = listOf("Settings", "Users", "Environment"),
                    selected = managerTab,
                    onSelect = { managerTab = it },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    when (managerTab) {
                        "Settings" -> item {
                            SectionCard(title = "Sandbox settings") {
                                Text(
                                    "Runs simple utility APKs in a virtual Android environment. This is not a security boundary: " +
                                        "sandboxed apps share NJ-Dialer's UID and permissions, and shared folders are not private. " +
                                        "Banking apps, Play Integrity apps, Google services and 32-bit-only apps are likely to fail.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(12.dp))
                                SandboxSwitchRow(
                                    label = "Apply default folders on install",
                                    detail = "New sandbox apps start with the folders selected below.",
                                    checked = applyDefaultsOnInstall,
                                    onCheckedChange = { enabled ->
                                        applyDefaultsOnInstall = enabled
                                        SandboxPrefs.setApplyDefaultsOnInstall(context, enabled)
                                    }
                                )
                                SandboxSwitchRow(
                                    label = "Keep SAF bridge with any shared folder",
                                    detail = "Lets guest file pickers use shared folders through the host documents provider.",
                                    checked = keepSafBridge,
                                    onCheckedChange = { enabled ->
                                        keepSafBridge = enabled
                                        SandboxPrefs.setKeepSafBridge(context, enabled)
                                    }
                                )
                                SandboxSwitchRow(
                                    label = "Confirm before stopping apps",
                                    detail = "Ask before a Stop action terminates a sandboxed app process.",
                                    checked = confirmBeforeStop,
                                    onCheckedChange = { enabled ->
                                        confirmBeforeStop = enabled
                                        SandboxPrefs.setConfirmBeforeStop(context, enabled)
                                    }
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Default shared folders",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                SandboxStorageFolders.ALL.forEach { folder ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = folder in defaultSharedFolders,
                                            onCheckedChange = { checked ->
                                                defaultSharedFolders = if (checked) {
                                                    defaultSharedFolders + folder
                                                } else {
                                                    defaultSharedFolders - folder
                                                }
                                                SandboxPrefs.setDefaultSharedFolders(context, defaultSharedFolders)
                                            }
                                        )
                                        Text(folder, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                Text(
                                    "Defaults apply only when all files access is granted.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        "Users" -> item {
                            SectionCard(title = "Virtual users") {
                                Text(
                                    "Each user id has its own sandbox app data and processes. New installs use sandbox user $currentUserId.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(10.dp))
                                users.forEach { user ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            "${user.name} · ${user.appCount} app(s)",
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.weight(1f)
                                        )
                                        TextButton(onClick = { switchUser(user.id) }) {
                                            Text(if (user.id == currentUserId) "Current" else "Switch")
                                        }
                                        if (user.id != 0) {
                                            IconButton(onClick = { deleteUser(user) }) {
                                                Icon(AppIcons.PersonOff, contentDescription = "Delete user")
                                            }
                                        }
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                AppSecondaryButton(
                                    text = "Create user",
                                    icon = AppIcons.Person,
                                    enabled = !busy,
                                    onClick = { createUser() }
                                )
                                Spacer(Modifier.height(12.dp))
                                SandboxSwitchRow(
                                    label = "Stop apps when leaving or switching users",
                                    detail = "Terminates running sandbox apps for the active user.",
                                    checked = stopAppsOnLeave,
                                    onCheckedChange = { enabled ->
                                        stopAppsOnLeave = enabled
                                        SandboxPrefs.setStopAppsOnLeave(context, enabled)
                                    }
                                )
                                Spacer(Modifier.height(8.dp))
                                AppSecondaryButton(
                                    text = "Global fake location",
                                    icon = AppIcons.Public,
                                    onClick = { openGlobalLocation() }
                                )
                            }
                        }
                        "Environment" -> item {
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
                    }
                }
            }
        }
    }

    pendingInstall?.let { inspection ->
        val preview = inspection.preview
        val compatibility = inspection.compatibility
        AlertDialog(
            onDismissRequest = {
                SandboxActions.discardInspection(inspection)
                pendingInstall = null
            },
            title = { Text("Install in sandbox?") },
            text = {
                Column {
                    Text(
                        if (inspection.splitCount > 0) {
                            "Review this split APK bundle before installing it into the virtual environment."
                        } else {
                            "Review this APK before installing it into the virtual environment."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    if (inspection.splitCount > 0) {
                        Text("Splits: ${inspection.splitCount}")
                    }
                    Text("App: ${preview?.label ?: "Unknown"}")
                    Text("Package: ${preview?.packageName ?: "Unknown"}")
                    Text("Version: ${preview?.version ?: "Unknown"}")
                    Text("Min SDK: ${compatibility.minSdk.takeIf { it > 0 }?.toString() ?: "Unknown"}")
                    Text("Target SDK: ${compatibility.targetSdk.takeIf { it > 0 }?.toString() ?: "Unknown"}")
                    if (compatibility.nativeAbis.isNotEmpty()) {
                        Text("Native libs: ${compatibility.nativeAbis.sorted().joinToString(", ")}")
                    }
                    compatibility.warnings.forEach { warning ->
                        Text(
                            warning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Compatibility limit: apps that depend on Google Play services, Play Integrity, " +
                            "banking/anti-tamper protections, or 32-bit-only native code are likely to fail.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { installApk(inspection) },
                    enabled = !busy
                ) { Text(if (busy) "Installing…" else "Install") }
            },
            dismissButton = {
                TextButton(onClick = {
                    SandboxActions.discardInspection(inspection)
                    pendingInstall = null
                }) { Text("Cancel") }
            }
        )
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

    confirmStopApp?.let { app ->
        AlertDialog(
            onDismissRequest = { confirmStopApp = null },
            title = { Text("Stop sandboxed app?") },
            text = { Text("Stop ${app.label}? Any unsaved work in that app will be lost.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmStopApp = null
                    performStop(app)
                }) { Text("Stop") }
            },
            dismissButton = {
                TextButton(onClick = { confirmStopApp = null }) { Text("Cancel") }
            }
        )
    }

    storageFolderApp?.let { selected ->
        val app = apps.firstOrNull { it.packageName == selected.packageName } ?: selected
        AlertDialog(
            onDismissRequest = { storageFolderApp = null },
            title = { Text("Shared folders for ${app.label}") },
            text = {
                Column {
                    Text(
                        "Choose which real folders this app can see. Changes stop the app; relaunch it to apply.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    SandboxStorageFolders.ALL.forEach { folder ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = folder in app.sharedFolders,
                                onCheckedChange = { enabled -> setSharedFolder(app, folder, enabled) }
                            )
                            Text(folder, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text(
                        "Shared folders are not private — the sandbox uses NJ-Dialer's UID and permissions.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { storageFolderApp = null }) { Text("Done") }
            }
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

    if (showCloneInstalled) {
        AlertDialog(
            onDismissRequest = { showCloneInstalled = false },
            title = { Text("Clone installed app") },
            text = {
                Column {
                    Text(
                        "Installs a copy of a host app into sandbox user $currentUserId. Apps with split APKs may need their original installer.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = hostAppQuery,
                        onValueChange = { hostAppQuery = it },
                        label = { Text("Search installed apps") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        hostApps.filter {
                            it.label.contains(hostAppQuery, ignoreCase = true) ||
                                it.packageName.contains(hostAppQuery, ignoreCase = true)
                        }.take(80).forEach { hostApp ->
                            TextButton(
                                onClick = { cloneHostApp(hostApp) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("${hostApp.label} · ${hostApp.packageName}")
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showCloneInstalled = false }) { Text("Close") } }
        )
    }

    cloneTargetFor?.let { app ->
        AlertDialog(
            onDismissRequest = { cloneTargetFor = null },
            title = { Text("Clone ${app.label}") },
            text = {
                Column {
                    users.filter { it.id != app.userId }.forEach { user ->
                        TextButton(
                            onClick = { cloneAppToUser(app, user.id) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(user.name) }
                    }
                    if (users.size <= 1) {
                        Text(
                            "Create another virtual user first.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { cloneTargetFor = null }) { Text("Cancel") } }
        )
    }

    fakeLocationFor?.let { app ->
        AlertDialog(
            onDismissRequest = { fakeLocationFor = null },
            title = { Text("Fake location for ${app.label}") },
            text = {
                Column {
                    listOf("Off", "Global", "Custom").forEach { mode ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = fakeLocationMode == mode,
                                onCheckedChange = { fakeLocationMode = mode }
                            )
                            Text(mode)
                        }
                    }
                    if (fakeLocationMode == "Custom") {
                        OutlinedTextField(
                            value = fakeLocationLat,
                            onValueChange = { fakeLocationLat = it },
                            label = { Text("Latitude") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = fakeLocationLng,
                            onValueChange = { fakeLocationLng = it },
                            label = { Text("Longitude") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Text(
                        "Apps must be relaunched to pick up a new location.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = { TextButton(onClick = { applyFakeLocation() }) { Text("Apply") } },
            dismissButton = { TextButton(onClick = { fakeLocationFor = null }) { Text("Cancel") } }
        )
    }

    if (showGlobalLocation) {
        AlertDialog(
            onDismissRequest = { showGlobalLocation = false },
            title = { Text("Global fake location") },
            text = {
                Column {
                    OutlinedTextField(
                        value = globalLocationLat,
                        onValueChange = { globalLocationLat = it },
                        label = { Text("Latitude") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = globalLocationLng,
                        onValueChange = { globalLocationLng = it },
                        label = { Text("Longitude") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = applyGlobalToAll,
                            onCheckedChange = { applyGlobalToAll = it }
                        )
                        Text("Apply global mode to all sandbox apps")
                    }
                    Text(
                        "Leave both fields empty to clear the global location.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = { TextButton(onClick = { applyGlobalLocation() }) { Text("Apply") } },
            dismissButton = { TextButton(onClick = { showGlobalLocation = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun SandboxSwitchRow(
    label: String,
    detail: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
