package com.example.ui.screens.sandbox

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.ui.components.AppConfirmDialog
import com.example.ui.components.AppIcons
import com.example.ui.components.AppPrimaryButton
import com.example.ui.components.AppSecondaryButton
import com.example.ui.components.FloatingTopBar
import com.example.ui.components.SectionCard
import com.example.ui.components.landscapeContentWidth
import com.example.ui.theme.AppAccent
import com.example.ui.theme.AppThemeMode
import com.example.ui.theme.MyApplicationTheme
import com.example.util.AssistantActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.niunaijun.blackbox.BlackBoxCore
import java.text.DateFormat
import java.util.Date

class SandboxAppSettingsActivity : ComponentActivity() {
    private var allFilesGranted = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val virtualPackage = intent.getStringExtra(EXTRA_VIRTUAL_PACKAGE)?.takeIf { it.isNotBlank() }
        if (virtualPackage == null) {
            finish()
            return
        }
        val userId = intent.getIntExtra(EXTRA_VIRTUAL_USER_ID, 0)
        val settingsAction = intent.getStringExtra(EXTRA_SETTINGS_ACTION)
        val settingsData = intent.getStringExtra(EXTRA_SETTINGS_DATA)
        allFilesGranted.value = SandboxActions.hasAllFilesAccess()
        enableEdgeToEdge()
        setContent {
            val theme = rememberSettingsThemePrefs()
            MyApplicationTheme(
                themeMode = theme.mode,
                accent = theme.accent,
                dynamicColor = theme.dynamicColors,
                amoledBlack = theme.amoledBlack
            ) {
                SandboxAppSettingsScreen(
                    packageName = virtualPackage,
                    userId = userId,
                    settingsAction = settingsAction,
                    settingsData = settingsData,
                    allFilesGranted = allFilesGranted.value,
                    onRequestAllFilesAccess = {
                        runCatching { BlackBoxCore.get().requestAllFilesAccess(this) }
                    },
                    onClose = { finish() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        allFilesGranted.value = SandboxActions.hasAllFilesAccess()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == BlackBoxCore.getAllFilesAccessRequestCode()) {
            allFilesGranted.value = SandboxActions.hasAllFilesAccess()
        }
    }

    companion object {
        const val EXTRA_VIRTUAL_PACKAGE = "com.example.sandbox.extra.VIRTUAL_PACKAGE"
        const val EXTRA_VIRTUAL_USER_ID = "com.example.sandbox.extra.VIRTUAL_USER_ID"
        const val EXTRA_SETTINGS_ACTION = "com.example.sandbox.extra.SETTINGS_ACTION"
        const val EXTRA_SETTINGS_DATA = "com.example.sandbox.extra.SETTINGS_DATA"

        fun intent(
            context: Context,
            packageName: String,
            userId: Int = 0,
            settingsAction: String? = null,
            settingsData: String? = null
        ): Intent = Intent().apply {
            component = ComponentName(context.packageName, SandboxAppSettingsActivity::class.java.name)
            putExtra(EXTRA_VIRTUAL_PACKAGE, packageName)
            putExtra(EXTRA_VIRTUAL_USER_ID, userId)
            settingsAction?.let { putExtra(EXTRA_SETTINGS_ACTION, it) }
            settingsData?.let { putExtra(EXTRA_SETTINGS_DATA, it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}

private data class SettingsThemePrefs(
    val mode: AppThemeMode,
    val accent: AppAccent,
    val dynamicColors: Boolean,
    val amoledBlack: Boolean
)

@Composable
private fun rememberSettingsThemePrefs(): SettingsThemePrefs {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember {
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        SettingsThemePrefs(
            mode = runCatching {
                AppThemeMode.valueOf(prefs.getString("theme_mode", AppThemeMode.SYSTEM.name)!!)
            }.getOrDefault(AppThemeMode.SYSTEM),
            accent = runCatching {
                AppAccent.valueOf(prefs.getString("accent", AppAccent.INDIGO.name)!!)
            }.getOrDefault(AppAccent.INDIGO),
            dynamicColors = prefs.getBoolean("dynamic_colors", true),
            amoledBlack = prefs.getBoolean("amoled_black", false)
        )
    }
}

private enum class SettingsStatusTone {
    Info,
    Success,
    Error
}

private data class SettingsStatus(
    val message: String,
    val tone: SettingsStatusTone = SettingsStatusTone.Info,
    val time: String
)

private fun settingsNow(): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())

@Composable
fun SandboxAppSettingsScreen(
    packageName: String,
    userId: Int,
    settingsAction: String?,
    settingsData: String?,
    allFilesGranted: Boolean,
    onRequestAllFilesAccess: () -> Unit,
    onClose: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val useGlass = remember {
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .getBoolean("ui_glassmorphism", false)
    }

    var label by remember(packageName) { mutableStateOf(packageName) }
    var version by remember(packageName) { mutableStateOf("") }
    var permissions by remember(packageName) { mutableStateOf<List<String>>(emptyList()) }
    var sharedStorage by remember(packageName, userId) {
        mutableStateOf(
            runCatching { BlackBoxCore.get().hasSharedInternalStorageAccess(packageName, userId) }
                .getOrDefault(false)
        )
    }
    var status by remember {
        mutableStateOf(SettingsStatus("Ready.", SettingsStatusTone.Info, settingsNow()))
    }
    var busy by remember { mutableStateOf(false) }
    var pendingLaunch by remember { mutableStateOf(false) }
    var pendingStorageToggle by remember { mutableStateOf<Boolean?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var dialogTitle by remember { mutableStateOf<String?>(null) }
    var dialogReport by remember { mutableStateOf<String?>(null) }
    var reportSaveStatus by remember { mutableStateOf<String?>(null) }

    fun setStatus(message: String, tone: SettingsStatusTone = SettingsStatusTone.Info) {
        status = SettingsStatus(message, tone, settingsNow())
    }

    LaunchedEffect(packageName, userId) {
        val info = withContext(Dispatchers.IO) {
            runCatching {
                BlackBoxCore.getBPackageManager()
                    .getPackageInfo(packageName, PackageManager.GET_PERMISSIONS, userId)
            }.getOrNull()
        }
        if (info == null) {
            setStatus("This app is no longer installed in the sandbox.", SettingsStatusTone.Error)
            return@LaunchedEffect
        }
        label = runCatching {
            info.applicationInfo?.loadLabel(context.packageManager)?.toString()
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: packageName
        version = listOfNotNull(
            info.versionName,
            info.versionCode.takeIf { it != 0 }?.toString()
        ).joinToString(" ")
        permissions = info.requestedPermissions?.toList().orEmpty()
    }

    fun launchApp() {
        if (SandboxActions.needsAllFilesAccessPrompt() && !allFilesGranted) {
            pendingLaunch = true
            onRequestAllFilesAccess()
            setStatus(SandboxActions.allFilesAccessMessage(), SettingsStatusTone.Error)
            return
        }
        pendingLaunch = false
        scope.launch {
            busy = true
            setStatus("Launching $label…")
            val result = SandboxActions.launch(packageName, label, userId, allFilesGranted)
            busy = false
            if (result.needsAllFilesAccess) {
                pendingLaunch = true
                onRequestAllFilesAccess()
            }
            setStatus(result.message, if (result.ok) SettingsStatusTone.Success else SettingsStatusTone.Error)
        }
    }

    fun stopApp() {
        scope.launch {
            val result = SandboxActions.stop(packageName, label, userId)
            setStatus(result.message, if (result.ok) SettingsStatusTone.Success else SettingsStatusTone.Error)
        }
    }

    fun clearApp() {
        scope.launch {
            val result = SandboxActions.clear(packageName, label, userId)
            setStatus(result.message, if (result.ok) SettingsStatusTone.Success else SettingsStatusTone.Error)
        }
    }

    fun removeApp() {
        scope.launch {
            val result = SandboxActions.remove(packageName, label, userId)
            setStatus(result.message, if (result.ok) SettingsStatusTone.Success else SettingsStatusTone.Error)
            if (result.ok) onClose()
        }
    }

    fun applySharedStorage(enabled: Boolean) {
        scope.launch {
            val result = SandboxActions.setSharedStorage(packageName, label, userId, enabled, allFilesGranted)
            if (result.needsAllFilesAccess) {
                pendingStorageToggle = enabled
                onRequestAllFilesAccess()
            }
            if (result.ok) {
                sharedStorage = runCatching {
                    BlackBoxCore.get().hasSharedInternalStorageAccess(packageName, userId)
                }.getOrDefault(sharedStorage)
            }
            setStatus(result.message, if (result.ok) SettingsStatusTone.Success else SettingsStatusTone.Error)
        }
    }

    fun setSharedStorage(enabled: Boolean) {
        if (enabled && SandboxActions.needsAllFilesAccessPrompt() && !allFilesGranted) {
            pendingStorageToggle = enabled
            onRequestAllFilesAccess()
            setStatus(SandboxActions.allFilesAccessMessage(), SettingsStatusTone.Error)
            return
        }
        pendingStorageToggle = null
        applySharedStorage(enabled)
    }

    LaunchedEffect(allFilesGranted, pendingLaunch, pendingStorageToggle) {
        if (pendingLaunch && (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || allFilesGranted)) {
            launchApp()
        } else if (pendingStorageToggle == true && (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || allFilesGranted)) {
            setSharedStorage(true)
        }
    }

    fun showDiagnostics() {
        dialogTitle = "Sandbox diagnostics"
        dialogReport = "Loading…"
        reportSaveStatus = null
        scope.launch {
            dialogReport = SandboxActions.diagnosticReport(packageName, userId)
        }
    }

    fun saveReport() {
        val report = dialogReport ?: return
        scope.launch {
            val message = SandboxActions.saveReport(context, report, packageName)
            reportSaveStatus = message
            setStatus(message, if (message.startsWith("Saved")) SettingsStatusTone.Success else SettingsStatusTone.Error)
        }
    }

    fun copyReport() {
        val report = dialogReport ?: return
        AssistantActions.copyText(context, report)
        reportSaveStatus = "Copied to clipboard"
        setStatus("Diagnostics copied to clipboard.", SettingsStatusTone.Success)
    }

    fun exportCrashLogs() {
        scope.launch {
            val message = SandboxActions.exportCrashLogs(context, packageName, userId)
            setStatus(message, if (message.startsWith("Saved")) SettingsStatusTone.Success else SettingsStatusTone.Error)
        }
    }

    val statusColor = when (status.tone) {
        SettingsStatusTone.Info -> MaterialTheme.colorScheme.onSurface
        SettingsStatusTone.Success -> MaterialTheme.colorScheme.primary
        SettingsStatusTone.Error -> MaterialTheme.colorScheme.error
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            FloatingTopBar(useGlass = useGlass) {
                IconButton(onClick = onClose) {
                    Icon(AppIcons.ArrowBack, contentDescription = "Close")
                }
                Spacer(Modifier.width(6.dp))
                Icon(AppIcons.Android, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text(
                    "Sandbox app settings",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .landscapeContentWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp,
                    vertical = 12.dp
                )
            ) {
                item {
                    SectionCard(title = label) {
                        Text(
                            "$packageName\n$version\nVirtual user $userId",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Android Settings cannot manage virtual packages. These controls change this app's sandbox record only.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (settingsAction != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Requested: $settingsAction",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (settingsData != null) {
                            Text(
                                "Data: $settingsData",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                item {
                    SectionCard(title = "Storage") {
                        Text(
                            if (sharedStorage) "Shared folders: on. This app can use Download, Documents, DCIM, Movies, Music and Pictures from the real device."
                            else "Virtual storage: on. This app sees only its sandbox files.",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (sharedStorage) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Shared folders are not private. The sandbox shares NJ-Dialer's UID and permissions, so restarting is required after a change.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(10.dp))
                        AppSecondaryButton(
                            text = if (sharedStorage) "Use virtual storage" else "Enable shared folders",
                            icon = AppIcons.Storage,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { setSharedStorage(!sharedStorage) }
                        )
                    }
                }

                item {
                    SectionCard(title = "Actions") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppPrimaryButton(
                                text = "Launch",
                                icon = AppIcons.PlayArrow,
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { launchApp() }
                            )
                            AppSecondaryButton(
                                text = "Stop",
                                icon = AppIcons.Stop,
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { stopApp() }
                            )
                            AppSecondaryButton(
                                text = "Clear data",
                                icon = AppIcons.Delete,
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { confirmClear = true }
                            )
                            AppSecondaryButton(
                                text = "Info & diagnostics",
                                icon = AppIcons.Info,
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { showDiagnostics() }
                            )
                            AppSecondaryButton(
                                text = "Export crash logs",
                                icon = AppIcons.SaveAlt,
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { exportCrashLogs() }
                            )
                            AppSecondaryButton(
                                text = "Remove app",
                                icon = AppIcons.DeleteForever,
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { confirmRemove = true }
                            )
                        }
                        if (busy) {
                            Spacer(Modifier.height(10.dp))
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        }
                    }
                }

                item {
                    SectionCard(title = "Permissions") {
                        Text(
                            "Audio, storage/media and notification-related permissions are auto-granted by the sandbox. Other permissions cannot be changed from stock Settings for a virtual package.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        if (permissions.isEmpty()) {
                            Text(
                                "No requested permissions reported.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            SelectionContainer {
                                Text(
                                    permissions.joinToString("\n"),
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                                )
                            }
                        }
                    }
                }

                item {
                    SectionCard(title = "Last action") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                status.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = statusColor,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                status.time,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AppConfirmDialog(
            title = "Clear app data?",
            message = "Clear all sandbox data for $label?",
            confirmLabel = "Clear",
            onConfirm = {
                confirmClear = false
                clearApp()
            },
            onDismiss = { confirmClear = false }
        )
    }

    if (confirmRemove) {
        AppConfirmDialog(
            title = "Remove sandboxed app?",
            message = "Remove $label and its sandbox data?",
            confirmLabel = "Remove",
            onConfirm = {
                confirmRemove = false
                removeApp()
            },
            onDismiss = { confirmRemove = false }
        )
    }

    dialogReport?.let { report ->
        AlertDialog(
            onDismissRequest = {
                dialogReport = null
                dialogTitle = null
                reportSaveStatus = null
            },
            title = { Text(dialogTitle ?: "Sandbox diagnostics") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    SelectionContainer {
                        Text(
                            report,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                        )
                    }
                    reportSaveStatus?.let { message ->
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
                TextButton(onClick = { saveReport() }) { Text("Save to NJCrash") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { copyReport() }) { Text("Copy") }
                    TextButton(onClick = {
                        dialogReport = null
                        dialogTitle = null
                        reportSaveStatus = null
                    }) { Text("Close") }
                }
            }
        )
    }
}
