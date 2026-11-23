package com.example.ui.screens.sandbox

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.ui.MainViewModel
import com.example.ui.components.AppIcons
import com.example.ui.components.AppPrimaryButton
import com.example.ui.components.AppSecondaryButton
import com.example.ui.components.FloatingTopBar
import com.example.ui.components.SectionCard
import com.example.ui.components.landscapeContentWidth
import com.example.util.CrashLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.niunaijun.blackbox.BlackBoxCore
import java.io.File

private data class SandboxApp(
    val packageName: String,
    val label: String,
    val version: String
)

@Composable
fun SandboxScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val useGlass by viewModel.useGlassmorphism.collectAsState()

    var apps by remember { mutableStateOf<List<SandboxApp>>(emptyList()) }
    var status by remember { mutableStateOf("Ready.") }
    var busy by remember { mutableStateOf(false) }
    var allFilesGranted by remember { mutableStateOf(runCatching { BlackBoxCore.get().hasAllFilesAccess() }.getOrDefault(false)) }
    var confirmUninstall by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf<String?>(null) }
    var diagnostics by remember { mutableStateOf<String?>(null) }
    var diagnosticsApp by remember { mutableStateOf<SandboxApp?>(null) }
    var diagnosticsSaveStatus by remember { mutableStateOf<String?>(null) }

    fun refreshApps() {
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    BlackBoxCore.get().getInstalledPackages(0, 0).orEmpty().map { info ->
                        val label = runCatching {
                            info.applicationInfo?.loadLabel(context.packageManager)?.toString()
                        }.getOrNull()?.takeIf { it.isNotBlank() } ?: info.packageName
                        val version = listOfNotNull(info.versionName, info.versionCode.toString())
                            .joinToString(" (")
                            .plus(if (info.versionName != null) ")" else "")
                        SandboxApp(info.packageName, label, version)
                    }
                }.getOrElse { e ->
                    status = "Could not list sandbox apps: ${e.message ?: e.javaClass.simpleName}"
                    emptyList()
                }
            }
            apps = loaded
            allFilesGranted = runCatching { BlackBoxCore.get().hasAllFilesAccess() }.getOrDefault(false)
        }
    }

    fun installApk(uri: Uri) {
        scope.launch {
            busy = true
            status = "Copying APK…"
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
                status = if (install.success) {
                    "Installed ${install.packageName ?: "APK"} in the sandbox."
                } else {
                    "Install failed: ${install.msg ?: "unknown error"}"
                }
            }.onFailure { e ->
                status = "Install failed: ${e.message ?: e.javaClass.simpleName}"
            }
            busy = false
            refreshApps()
        }
    }

    val apkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) installApk(uri)
    }

    LaunchedEffect(Unit) { refreshApps() }

    fun launchApp(app: SandboxApp) {
        scope.launch {
            status = "Launching ${app.label}…"
            val launched = withContext(Dispatchers.IO) {
                runCatching { BlackBoxCore.get().launchApk(app.packageName, 0) }
            }
            status = launched.fold(
                onSuccess = { ok ->
                    if (ok) "Launched ${app.label}."
                    else "Launch failed: ${BlackBoxCore.get().lastLaunchError ?: "unknown error"}"
                },
                onFailure = { e -> "Launch failed: ${e.message ?: e.javaClass.simpleName}" }
            )
        }
    }

    fun stopApp(app: SandboxApp) {
        runCatching { BlackBoxCore.get().stopPackage(app.packageName, 0) }
        status = "Stopped ${app.label}."
    }

    fun clearApp(app: SandboxApp) {
        runCatching { BlackBoxCore.get().clearPackage(app.packageName, 0) }
        status = "Cleared data for ${app.label}."
    }

    fun uninstallApp(app: SandboxApp) {
        runCatching { BlackBoxCore.get().uninstallPackageAsUser(app.packageName, 0) }
        status = "Removed ${app.label} from the sandbox."
        refreshApps()
    }

    fun openAllFilesAccess() {
        if (activity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { BlackBoxCore.get().requestAllFilesAccess(activity) }
                .onFailure { status = "Could not open All files access settings." }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            runCatching { context.startActivity(intent) }
                .onFailure { status = "Could not open All files access settings." }
        }
    }

    fun showDiagnostics(app: SandboxApp) {
        diagnosticsApp = app
        diagnosticsSaveStatus = null
        scope.launch {
            val report = withContext(Dispatchers.IO) {
                runCatching { BlackBoxCore.get().getDiagnosticReport(app.packageName, 0) }
                    .getOrElse { e -> "Diagnostics failed: ${e.message ?: e.javaClass.simpleName}" }
            }
            diagnostics = report
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
                onFailure = { e -> "Could not save: ${e.message ?: e.javaClass.simpleName}" }
            )
            diagnosticsSaveStatus = message
            status = message
        }
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
                IconButton(onClick = { refreshApps() }) {
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
                                text = "Grant all files access",
                                icon = AppIcons.LockOpen,
                                onClick = { openAllFilesAccess() }
                            )
                        }
                    }
                }

                item {
                    SectionCard(title = "Last action") {
                        Text(status, style = MaterialTheme.typography.bodySmall)
                    }
                }

                item {
                    Text(
                        "Sandboxed apps (${apps.size})",
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                if (apps.isEmpty()) {
                    item {
                        Text(
                            "No APKs installed yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                items(apps, key = { it.packageName }) { app ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(app.label, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "${app.packageName}\n${app.version}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(onClick = { launchApp(app) }) { Text("Launch") }
                                OutlinedButton(onClick = { stopApp(app) }) { Text("Stop") }
                                OutlinedButton(onClick = { confirmClear = app.packageName }) { Text("Clear") }
                                OutlinedButton(onClick = { showDiagnostics(app) }) { Text("Info") }
                                OutlinedButton(onClick = { confirmUninstall = app.packageName }) { Text("Remove") }
                            }
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
            title = { Text("Sandbox diagnostics") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(report, style = MaterialTheme.typography.bodySmall)
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
                TextButton(onClick = {
                    diagnostics = null
                    diagnosticsApp = null
                    diagnosticsSaveStatus = null
                }) { Text("Close") }
            }
        )
    }
}
