package com.example.ui.screens.settings

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import java.io.File
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions


import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.AdminReceiver
import com.example.service.AssistantOverlayService
import com.example.util.AssistantActions
import com.example.ui.MainViewModel
import com.example.ui.components.AppIcons
import com.example.ui.components.FloatingTopBar
import com.example.ui.components.landscapeContentWidth
import com.example.ui.theme.AppAccent
import com.example.ui.theme.AppThemeMode

import com.example.util.AppBackupManager
import com.example.util.CallScreening
import com.example.util.KeepAliveTimeout
import com.example.util.SystemCallHelper
import android.os.PowerManager
import com.example.util.copyUriToCache
import com.example.data.db.AppDatabase
import com.example.data.repository.AppRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    // ViewModel state collection
    val currentThemeMode by viewModel.themeMode.collectAsState()
    val currentAccent by viewModel.accent.collectAsState()
    val blockedNumbers by viewModel.blockedNumbers.collectAsState()
    val vibrateOnConnect by viewModel.vibrateOnConnect.collectAsState()
    val flashOnCall by viewModel.flashOnCall.collectAsState()
    val blockUnknownCallers by viewModel.blockUnknownCallers.collectAsState()

    // Dialog state management
    var showBlockedDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var showDeactivateAdminDialog by remember { mutableStateOf(false) }
    var newBlockNumberInput by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    // Extended call features
    val appPrefs = remember { context.getSharedPreferences("app_settings", Context.MODE_PRIVATE) }
    val repository = remember { AppRepository(AppDatabase.getDatabase(context)) }
    var announceCaller by remember { mutableStateOf(appPrefs.getBoolean("announce_caller", false)) }
    var autoRecordCalls by remember { mutableStateOf(appPrefs.getBoolean("auto_record_calls", false)) }
    var blockPrivate by remember { mutableStateOf(appPrefs.getBoolean("block_private_callers", false)) }
    var blockInternational by remember { mutableStateOf(appPrefs.getBoolean("block_international", false)) }
    var silentFirstTime by remember { mutableStateOf(appPrefs.getBoolean("silent_first_time", false)) }
    var prefixesDraft by remember {
        mutableStateOf(CallScreening.blockedPrefixes(context).sorted().joinToString(", "))
    }
    var missedReminderEnabled by remember { mutableStateOf(appPrefs.getBoolean("missed_reminder_enabled", false)) }
    var missedReminderInterval by remember { mutableStateOf(appPrefs.getString("missed_reminder_interval", "10") ?: "10") }
    var showScreeningDialog by remember { mutableStateOf(false) }
    var showMissedDialog by remember { mutableStateOf(false) }
    var showContactTools by remember { mutableStateOf(false) }
    var showBatteryDialog by remember { mutableStateOf(false) }
    var showKeepAliveDialog by remember { mutableStateOf(false) }
    var showBackupDialog by remember { mutableStateOf(false) }
    var backupStatus by remember { mutableStateOf("") }

    val dynamicColors by viewModel.dynamicColors.collectAsState()
    val amoledBlack by viewModel.amoledBlack.collectAsState()
    val fontScalePercent by viewModel.fontScale.collectAsState()
    val confirmExitOnBack by viewModel.confirmExitOnBack.collectAsState()
    val useGlassmorphism by viewModel.useGlassmorphism.collectAsState()
    val keepAliveTimeoutMinutes by viewModel.keepAliveTimeoutMinutes.collectAsState()
    val powerManager = remember { context.getSystemService(Context.POWER_SERVICE) as PowerManager }
    var batteryIgnoring by remember { mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName)) }

    val pickBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK || result.data == null) return@rememberLauncherForActivityResult
        val uri = result.data!!.clipData?.getItemAt(0)?.uri ?: result.data!!.data ?: return@rememberLauncherForActivityResult
        val file = copyUriToCache(context, uri, "json") ?: run {
            Toast.makeText(context, "Could not read backup file", Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            backupStatus = "Restoring..."
            val res = AppBackupManager.import(context, file, repository)
            backupStatus = if (res != null) {
                "Restored: ${res.notes} notes, ${res.bookmarks} bookmarks, " +
                    "${res.history} history, ${res.contacts} contacts, " +
                    "${res.calendarEvents} calendar events" +
                    if (res.todos) ", todos" else ""
            } else "Restore failed - invalid backup file"
            Toast.makeText(context, backupStatus, Toast.LENGTH_LONG).show()
        }
    }

    // System Status
    var isDefault by remember { mutableStateOf(SystemCallHelper.isDefaultDialer(context)) }
    val dpm = remember { context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager }
    val adminComponent = remember { ComponentName(context, AdminReceiver::class.java) }
    var isDeviceAdmin by remember { mutableStateOf(dpm.isAdminActive(adminComponent)) }
    var isStorageAccessGranted by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    var isOverlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var assistantRunning by remember { mutableStateOf(AssistantOverlayService.isRunning()) }
    var canWriteSettings by remember { mutableStateOf(Settings.System.canWrite(context)) }

    val adminActivateIntent = remember {
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Prevents accidental uninstallation of nj-dialer."
            )
        }
    }

    val adminLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        isDeviceAdmin = dpm.isAdminActive(adminComponent)
    }

    val roleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        isDefault = SystemCallHelper.isDefaultDialer(context)
    }

    // Refresh state when coming back to foreground
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isDefault = SystemCallHelper.isDefaultDialer(context)
                isDeviceAdmin = dpm.isAdminActive(adminComponent)
                isStorageAccessGranted = Environment.isExternalStorageManager()
                isOverlayGranted = Settings.canDrawOverlays(context)
                assistantRunning = AssistantOverlayService.isRunning()
                canWriteSettings = Settings.System.canWrite(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        FloatingTopBar(useGlass = useGlassmorphism) {
            Text(
                "Settings",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .landscapeContentWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
        // --- SECTION: System & Permissions ---
        item {
            Text(
                text = "System & Permissions",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Default Dialer Status Card
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isDefault) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = if (isDefault) AppIcons.CheckCircle else AppIcons.Warning,
                                contentDescription = null,
                                tint = if (isDefault) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                            )
                            Column {
                                Text(
                                    text = if (isDefault) "Default Phone App Active" else "Not Default Dialer",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = if (isDefault) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    text = if (isDefault) "Handles calls, T9 search, and InCall UI" else "Set as default dialer to manage incoming & outgoing calls",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isDefault) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                                )
                            }
                        }
                        if (!isDefault) {
                            Button(
                                onClick = {
                                    try {
                                        val intent = SystemCallHelper.requestDefaultDialerIntent(context)
                                        if (intent != null) {
                                            roleLauncher.launch(intent)
                                        } else {
                                            SystemCallHelper.openDefaultDialerSettings(context)
                                        }
                                    } catch (e: Exception) {
                                        SystemCallHelper.openDefaultDialerSettings(context)
                                    }
                                }
                            ) {
                                Text("Set Default")
                            }
                        }
                    }
                }

                // Device Admin / Uninstall Protection Card
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = AppIcons.Security,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column {
                                Text(
                                    text = if (isDeviceAdmin) "Uninstall Protection Enabled" else "Device Admin Inactive",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (isDeviceAdmin) "App is protected against accidental removal" else "Activate admin privileges to prevent uninstallation",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (isDeviceAdmin) {
                            OutlinedButton(onClick = { showDeactivateAdminDialog = true }) {
                                Text("Deactivate")
                            }
                        } else {
                            Button(onClick = { adminLauncher.launch(adminActivateIntent) }) {
                                Text("Activate")
                            }
                        }
                    }
                }

                // Storage Access Card
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = AppIcons.FolderSpecial,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column {
                                Text(
                                    text = if (isStorageAccessGranted) "All Files Access Granted" else "Storage Access Restricted",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Required for Vault and File Manager functions",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Button(onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                )
                            }
                        }) {
                            Text(if (isStorageAccessGranted) "Manage" else "Grant")
                        }
                    }
                }

                // Display Over Other Apps Card
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = AppIcons.Layers,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column {
                                Text(
                                    text = if (isOverlayGranted) "Overlay Permission Granted" else "Overlay Permission Denied",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Allows the app to draw over other apps",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Button(onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                )
                            }
                        }) {
                            Text(if (isOverlayGranted) "Manage" else "Grant")
                        }
                    }
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = AppIcons.SmartToy,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column {
                                Text(
                                    text = "Floating Assistant",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (assistantRunning) "Running over other apps" else "Start a bubble with quick actions",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Button(onClick = {
                                if (assistantRunning) {
                                    AssistantOverlayService.stop(context)
                                    assistantRunning = false
                                } else if (!isOverlayGranted) {
                                    Toast.makeText(context, "Grant overlay permission first", Toast.LENGTH_SHORT).show()
                                } else {
                                    AssistantOverlayService.start(context)
                                    assistantRunning = true
                                }
                            }) {
                                Text(if (assistantRunning) "Stop" else "Start")
                            }
                            TextButton(onClick = { AssistantActions.open(context, "assistantsettings") }) {
                                Text("Manage")
                            }
                        }
                    }
                }

                // Modify System Settings Card
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = AppIcons.Settings,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column {
                                Text(
                                    text = if (canWriteSettings) "System Settings Write Enabled" else "System Settings Write Denied",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Allows the app to modify system settings",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Button(onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_WRITE_SETTINGS,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                )
                            }
                        }) {
                            Text(if (canWriteSettings) "Manage" else "Grant")
                        }
                    }
                }
            }
        }

        item {
            Text(text = "HiOS / background reliability", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text("Battery optimization") },
                    supportingContent = { Text("If calls, audio, or reminders stop on Tecno/HiOS, allow unrestricted battery/background activity for NJ-Dialer.") },
                    leadingContent = { Icon(AppIcons.BatteryChargingFull, contentDescription = null) },
                    modifier = Modifier.clickable {
                        runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                            .onFailure { Toast.makeText(context, "Battery settings unavailable", Toast.LENGTH_SHORT).show() }
                    },
                    trailingContent = { Icon(AppIcons.ChevronRight, contentDescription = null) }
                )

            }
        }

        // --- SECTION: Call & Security Preferences ---
        item {
            Text(
                text = "Call & Security Preferences",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    ListItem(
                        headlineContent = { Text("Blocked Numbers (${blockedNumbers.size})") },
                        supportingContent = { Text("Manage numbers blocked from calling") },
                        leadingContent = { Icon(AppIcons.Block, contentDescription = null) },
                        modifier = Modifier.clickable { showBlockedDialog = true }.padding(vertical = 2.dp),
                        trailingContent = { Icon(AppIcons.ChevronRight, contentDescription = null) }
                    )
                    HorizontalDivider()
                    ListItem(
                        headlineContent = { Text("Vibrate on Call Connect") },
                        supportingContent = { Text("Provide haptic feedback when call is answered") },
                        leadingContent = { Icon(AppIcons.Vibration, contentDescription = null) },
                        trailingContent = {
                            Switch(
                                checked = vibrateOnConnect,
                                onCheckedChange = { viewModel.setVibrateOnConnect(it) }
                            )
                        }
                    )
                    HorizontalDivider()
                    ListItem(
                        headlineContent = { Text("Flashlight Alert on Call") },
                        supportingContent = { Text("Blink LED camera flash for incoming calls") },
                        leadingContent = { Icon(AppIcons.FlashOn, contentDescription = null) },
                        trailingContent = {
                            Switch(
                                checked = flashOnCall,
                                onCheckedChange = { viewModel.setFlashOnCall(it) }
                            )
                        }
                    )
                    HorizontalDivider()
                    ListItem(
                        headlineContent = { Text("Block Unknown Callers") },
                        supportingContent = { Text("Automatically reject numbers not in your contacts") },
                        leadingContent = { Icon(AppIcons.PersonOff, contentDescription = null) },
                        trailingContent = {
                            Switch(
                                checked = blockUnknownCallers,
                                onCheckedChange = { viewModel.setBlockUnknownCallers(it) }
                            )
                        }
                    )
                    HorizontalDivider()
                    ListItem(
                        headlineContent = { Text("Announce Caller Name") },
                        supportingContent = { Text("Speak the caller's name over the ringtone") },
                        leadingContent = { Icon(AppIcons.RecordVoiceOver, contentDescription = null) },
                        trailingContent = {
                            Switch(
                                checked = announceCaller,
                                onCheckedChange = {
                                    announceCaller = it
                                    appPrefs.edit().putBoolean("announce_caller", it).apply()
                                    Toast.makeText(context,
                                        if (it) "Caller announce ON" else "Caller announce OFF",
                                        Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    )
                    HorizontalDivider()
                    ListItem(
                        headlineContent = { Text("Auto Record Calls") },
                        supportingContent = { Text("Start recording automatically when a call connects (saved to Recordings/NJ-Dialer Calls)") },
                        leadingContent = { Icon(AppIcons.Radio, contentDescription = null) },
                        trailingContent = {
                            Switch(
                                checked = autoRecordCalls,
                                onCheckedChange = {
                                    autoRecordCalls = it
                                    appPrefs.edit().putBoolean("auto_record_calls", it).apply()
                                    Toast.makeText(context,
                                        if (it) "Auto record ON" else "Auto record OFF",
                                        Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    )
                    HorizontalDivider()
                    ListItem(
                        headlineContent = { Text("Advanced Call Screening") },
                        supportingContent = { Text("Private numbers, international calls, prefixes & first-time silent mode") },
                        leadingContent = { Icon(AppIcons.Shield, contentDescription = null) },
                        modifier = Modifier.clickable { showScreeningDialog = true },
                        trailingContent = {
                            IconButton(onClick = { showScreeningDialog = true }) {
                                Icon(AppIcons.ChevronRight, contentDescription = "Configure Screening")
                            }
                        }
                    )
                    HorizontalDivider()
                    ListItem(
                        headlineContent = { Text("Missed Call Reminders") },
                        supportingContent = {
                            Text(
                                if (missedReminderEnabled)
                                    "Remind every $missedReminderInterval min until dismissed"
                                else "Notify once with Call back / Message actions"
                            )
                        },
                        leadingContent = { Icon(AppIcons.NotificationsActive, contentDescription = null) },
                        modifier = Modifier.clickable { showMissedDialog = true },
                        trailingContent = {
                            IconButton(onClick = { showMissedDialog = true }) {
                                Icon(AppIcons.ChevronRight, contentDescription = "Configure Reminders")
                            }
                        }
                    )
                }
            }
        }

        // --- SECTION: App Behavior ---
        item {
            Text(
                text = "App Behavior",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                ListItem(
                    headlineContent = { Text("Confirm before exiting") },
                    supportingContent = {
                        Text(
                            if (confirmExitOnBack)
                                "Back at the home screen asks before exiting"
                            else "Back never leaves the app — use the Home gesture to background it"
                        )
                    },
                    leadingContent = { Icon(AppIcons.Home, contentDescription = null) },
                    trailingContent = {
                        Switch(
                            checked = confirmExitOnBack,
                            onCheckedChange = { viewModel.setConfirmExitOnBack(it) }
                        )
                    }
                )
                HorizontalDivider()
                run {
                    val confirmBeforeCall by viewModel.confirmBeforeCall.collectAsState()
                    ListItem(
                        headlineContent = { Text("Confirm before calling") },
                        supportingContent = { Text("Ask before placing a call from speed dial, recents, or contacts") },
                        leadingContent = { Icon(AppIcons.Phone, contentDescription = null) },
                        trailingContent = {
                            Switch(
                                checked = confirmBeforeCall,
                                onCheckedChange = { viewModel.setConfirmBeforeCall(it) }
                            )
                        }
                    )
                }
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("App notifications") },
                    supportingContent = { Text("Open system notification settings and channels") },
                    leadingContent = { Icon(AppIcons.NotificationsActive, contentDescription = null) },
                    modifier = Modifier.clickable {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            })
                        }
                    },
                    trailingContent = { Icon(AppIcons.ChevronRight, contentDescription = null) }
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("Keep-alive timeout") },
                    supportingContent = {
                        Text(
                            if (keepAliveTimeoutMinutes <= 0)
                                "Kept-alive web apps and terminal run until you close them"
                            else "Kept-alive web apps and terminal stop after " +
                                KeepAliveTimeout.label(keepAliveTimeoutMinutes)
                        )
                    },
                    leadingContent = { Icon(AppIcons.Timer, contentDescription = null) },
                    modifier = Modifier.clickable { showKeepAliveDialog = true },
                    trailingContent = {
                        TextButton(onClick = { showKeepAliveDialog = true }) {
                            Text(KeepAliveTimeout.label(keepAliveTimeoutMinutes))
                        }
                    }
                )
            }
        }

        // --- SECTION: Appearance & Themes ---
        item {
            Text(
                text = "Appearance & Interface",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Theme Mode", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(AppThemeMode.entries.toTypedArray()) { mode ->
                            FilterChip(
                                selected = currentThemeMode == mode,
                                onClick = { viewModel.setThemeMode(mode) },
                                label = { Text(mode.displayName, style = MaterialTheme.typography.labelMedium) },
                                leadingIcon = if (currentThemeMode == mode) {
                                    { Icon(AppIcons.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                } else null
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text("Accent Color Palette", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(AppAccent.entries.toTypedArray()) { accent ->
                            FilterChip(
                                selected = currentAccent == accent,
                                onClick = { viewModel.setAccent(accent) },
                                label = { Text(accent.displayName, style = MaterialTheme.typography.labelMedium) },
                                leadingIcon = {
                                    Surface(
                                        shape = MaterialTheme.shapes.extraSmall,
                                        color = accent.primary,
                                        modifier = Modifier.size(14.dp)
                                    ) {}
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Material You Colors", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                            Text("Use wallpaper-based dynamic colors (Android 12+)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = dynamicColors, onCheckedChange = {
                            viewModel.setDynamicColors(it)
                            Toast.makeText(context,
                                if (it) "Material You colors ON (Android 12+)"
                                else "Material You colors OFF",
                                Toast.LENGTH_SHORT).show()
                        })
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("AMOLED Pure Black", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                            Text("True black backgrounds in dark mode",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = amoledBlack, onCheckedChange = {
                            viewModel.setAmoledBlack(it)
                            Toast.makeText(context,
                                if (it) "AMOLED black ON - visible in dark mode"
                                else "AMOLED black OFF",
                                Toast.LENGTH_SHORT).show()
                        })
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Glassmorphism", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                            Text("Faux-glass look for top bars and app surfaces (opt-in)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = useGlassmorphism, onCheckedChange = {
                            viewModel.setUseGlassmorphism(it)
                            Toast.makeText(context,
                                if (it) "Glassmorphism ON"
                                else "Glassmorphism OFF",
                                Toast.LENGTH_SHORT).show()
                        })
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Larger touch targets", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                            Text("Use larger minimum tap areas where supported", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val largerTargets by viewModel.largerTouchTargets.collectAsState()
                        Switch(checked = largerTargets, onCheckedChange = { viewModel.setLargerTouchTargets(it) })
                    }
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Reduce motion", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                            Text("Calmer transitions preference (applied by supported screens)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val reduceMotion by viewModel.reduceMotion.collectAsState()
                        Switch(checked = reduceMotion, onCheckedChange = { viewModel.setReduceMotion(it) })
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Font Size", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                    Slider(
                        value = fontScalePercent.toFloat(),
                        onValueChange = { viewModel.setFontScale(it.toInt()) },
                        valueRange = 80f..130f,
                        steps = 9
                    )
                    Text("$fontScalePercent%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.End))
                }
            }
        }

        item {
            Text(
                text = "Maintenance & Data",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    ListItem(
                        headlineContent = { Text("Battery Optimization") },
                        supportingContent = {
                            Text(
                                if (batteryIgnoring)
                                    "Unrestricted - background features work fully"
                                else "Allow background running for calls, radio & reminders"
                            )
                        },
                        leadingContent = { Icon(AppIcons.BatterySaver, contentDescription = null) },
                        modifier = Modifier.clickable { showBatteryDialog = true },
                        trailingContent = {
                            Button(onClick = { showBatteryDialog = true }) {
                                Text(if (batteryIgnoring) "Info" else "Fix")
                            }
                        }
                    )
                    HorizontalDivider()
                    ListItem(
                        headlineContent = { Text("Backup & Restore") },
                        supportingContent = { Text("One file with notes, to-dos, bookmarks, history & settings") },
                        leadingContent = { Icon(AppIcons.Backup, contentDescription = null) },
                        modifier = Modifier.clickable { showBackupDialog = true; backupStatus = "" },
                        trailingContent = {
                            Button(onClick = { showBackupDialog = true; backupStatus = "" }) {
                                Text("Open")
                            }
                        }
                    )
                    HorizontalDivider()
                    ListItem(
                        headlineContent = { Text("Reset Preferences") },
                        supportingContent = { Text("Restore theme, dialer & app defaults") },
                        leadingContent = { Icon(AppIcons.RestartAlt, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        modifier = Modifier.padding(vertical = 2.dp),
                        trailingContent = {
                            OutlinedButton(
                                onClick = { showResetDialog = true },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("Reset")
                            }
                        }
                    )
                    HorizontalDivider()
                }
            }
        }

        // --- SECTION: About Application ---
        item {
            Text(
                text = "About Application",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = AppIcons.PhoneInTalk,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                        Column {
                            val appVersion = runCatching {
                                context.packageManager.getPackageInfo(context.packageName, 0).versionName
                            }.getOrNull() ?: "1.0"
                            val appCode = runCatching {
                                context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
                            }.getOrNull() ?: 1L
                            val appLabel = runCatching {
                                context.applicationInfo.loadLabel(context.packageManager).toString()
                            }.getOrNull() ?: "nj-dialer"
                            Text(
                                text = appLabel,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "Version $appVersion ($appCode)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Developer",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Alfred Njobvu",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Application ID",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = context.packageName,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Build",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "${com.example.BuildConfig.BUILD_TYPE} (SDK ${android.os.Build.VERSION.SDK_INT})",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Device",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "© 2026 Alfred Njobvu. All rights reserved. Built with Kotlin & Jetpack Compose.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }

    // --- DIALOGS ---

    // 1. Blocked Numbers Manager Dialog
    if (showBlockedDialog) {
        AlertDialog(
            onDismissRequest = { showBlockedDialog = false },
            title = { Text("Blocked Numbers") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newBlockNumberInput,
                            onValueChange = { newBlockNumberInput = it },
                            placeholder = { Text("Enter number...") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = {
                                val trimmed = newBlockNumberInput.trim()
                                if (trimmed.isNotEmpty()) {
                                    viewModel.blockNumber(trimmed)
                                    newBlockNumberInput = ""
                                }
                            }
                        ) {
                            Icon(AppIcons.AddCircle, contentDescription = "Add Blocked Number", tint = MaterialTheme.colorScheme.primary)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (blockedNumbers.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("No numbers currently blocked", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                            items(blockedNumbers, key = { it.id }) { item ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(AppIcons.Block, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                                        Text(item.number, style = MaterialTheme.typography.bodyMedium)
                                    }
                                    IconButton(onClick = { viewModel.unblockNumber(item.number) }) {
                                        Icon(AppIcons.Delete, contentDescription = "Unblock", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBlockedDialog = false }) {
                    Text("Close")
                }
            }
        )
    }



    // 3. Admin Deactivation Confirmation Dialog
    if (showDeactivateAdminDialog) {
        AlertDialog(
            onDismissRequest = { showDeactivateAdminDialog = false },
            title = { Text("Deactivate Device Admin?") },
            text = { Text("Uninstall protection will be disabled and the application can be uninstalled from system settings.") },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        dpm.removeActiveAdmin(adminComponent)
                        isDeviceAdmin = false
                        Toast.makeText(context, "Device admin deactivated", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(context, "Failed to deactivate: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                    showDeactivateAdminDialog = false
                }) {
                    Text("Deactivate", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeactivateAdminDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // 4. Reset Preferences Dialog
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset Preferences?") },
            text = { Text("This will reset your theme mode, accent color and call preferences back to default values.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setThemeMode(AppThemeMode.SYSTEM)
                    viewModel.setAccent(AppAccent.TEAL)
                    viewModel.setVibrateOnConnect(true)
                    viewModel.setFlashOnCall(false)
                    viewModel.setBlockUnknownCallers(false)
                    Toast.makeText(context, "Preferences reset to default", Toast.LENGTH_SHORT).show()
                    showResetDialog = false
                }) {
                    Text("Reset Defaults", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Advanced Call Screening dialog
    if (showScreeningDialog) {
            AlertDialog(
                onDismissRequest = { showScreeningDialog = false },
                title = { Text("Advanced Call Screening") },
                text = {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Block private/hidden numbers")
                                Text("Reject calls with no caller ID",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = blockPrivate, onCheckedChange = {
                                blockPrivate = it
                                appPrefs.edit().putBoolean(CallScreening.PREF_BLOCK_PRIVATE, it).apply()
                            })
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Block international numbers")
                                Text("Anything not starting with +${CallScreening.localCountryCode(context)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = blockInternational, onCheckedChange = {
                                blockInternational = it
                                appPrefs.edit().putBoolean(CallScreening.PREF_BLOCK_INTERNATIONAL, it).apply()
                            })
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Silence first-time callers")
                                Text("Phone stays silent for numbers never seen before",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = silentFirstTime, onCheckedChange = {
                                silentFirstTime = it
                                appPrefs.edit().putBoolean(CallScreening.PREF_SILENT_FIRST_TIME, it).apply()
                            })
                        }
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = prefixesDraft,
                            onValueChange = { prefixesDraft = it },
                            label = { Text("Blocked number prefixes") },
                            placeholder = { Text("e.g. 800, 0700, +44") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        val set = prefixesDraft.split(",")
                            .map { it.trim() }.filter { it.isNotBlank() }.toSet()
                        CallScreening.setBlockedPrefixes(context, set)
                        showScreeningDialog = false
                        Toast.makeText(context, "Screening rules saved", Toast.LENGTH_SHORT).show()
                    }) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = { showScreeningDialog = false }) { Text("Cancel") }
                }
            )
        }

        // Missed call reminders dialog
        if (showMissedDialog) {
            val intervals = listOf("5", "10", "30", "60")
            AlertDialog(
                onDismissRequest = { showMissedDialog = false },
                title = { Text("Missed Call Reminders") },
                text = {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Repeat reminder until dismissed", Modifier.weight(1f))
                            Switch(checked = missedReminderEnabled, onCheckedChange = {
                                missedReminderEnabled = it
                                appPrefs.edit().putBoolean("missed_reminder_enabled", it).apply()
                                com.example.service.MissedCallReceiver.scheduleReminder(context)
                            })
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("Remind me every",
                            style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            intervals.forEach { mins ->
                                FilterChip(
                                    selected = missedReminderInterval == mins,
                                    onClick = {
                                        missedReminderInterval = mins
                                        appPrefs.edit().putString("missed_reminder_interval", mins).apply()
                                        com.example.service.MissedCallReceiver.scheduleReminder(context)
                                    },
                                    label = {
                                        Text(if (mins == "60") "1 hour" else "$mins min",
                                            style = MaterialTheme.typography.labelMedium)
                                    }
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("Every missed call posts a notification with Call back / Message / Dismiss actions.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        com.example.service.MissedCallReceiver.scheduleReminder(context)
                        showMissedDialog = false
                    }) { Text("Done") }
                }
            )
        }

        // Keep-alive timeout dialog
        if (showKeepAliveDialog) {
            AlertDialog(
                onDismissRequest = { showKeepAliveDialog = false },
                title = { Text("Keep-alive timeout") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Choose how long a backgrounded web app or terminal may stay kept alive. The timeout applies the next time one is kept running.")
                        KeepAliveTimeout.OPTIONS.forEach { option ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.setKeepAliveTimeoutMinutes(option)
                                        showKeepAliveDialog = false
                                    },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = keepAliveTimeoutMinutes == option,
                                    onClick = {
                                        viewModel.setKeepAliveTimeoutMinutes(option)
                                        showKeepAliveDialog = false
                                    }
                                )
                                Text(
                                    if (option == 0) "Never (until closed)" else "${option} minutes",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = { showKeepAliveDialog = false }) { Text("Done") }
                }
            )
        }

        // Battery optimization dialog
        if (showBatteryDialog) {
            AlertDialog(
                onDismissRequest = { showBatteryDialog = false },
                title = { Text("Battery Optimization") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            if (batteryIgnoring)
                                "nj-dialer is exempt from battery optimization. Calls, radio streaming, recordings and reminders will run reliably in the background."
                            else
                                "Tecno/Infinix and other aggressive systems kill background apps. Exempt nj-dialer so incoming call alerts, radio playback, call recording and missed-call reminders keep working."
                        )
                        Text(
                            "Extra tips for Transsion phones (Tecno/Infinix/itel):\n" +
                                "1. Settings > Battery > NJ-Dialer > Allow background\n" +
                                "2. Recent apps > lock NJ-Dialer\n" +
                                "3. Disable 'Deep sleep' for this app if available",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {
                    if (!batteryIgnoring) {
                        Button(onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                        Uri.parse("package:${context.packageName}")
                                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }.onFailure {
                                runCatching {
                                    context.startActivity(
                                        Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            }
                            showBatteryDialog = false
                        }) { Text("Allow Background") }
                    } else {
                        Button(onClick = { showBatteryDialog = false }) { Text("OK") }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showBatteryDialog = false }) { Text("Close") }
                }
            )
        }

        // Backup & restore dialog
        if (showBackupDialog) {
            AlertDialog(
                onDismissRequest = { showBackupDialog = false },
                title = { Text("Backup & Restore") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Saves notes, to-dos, bookmarks, browser history, contacts, blocked numbers and key settings into one JSON file in Download/NJ-Dialer.")
                        if (backupStatus.isNotBlank()) {
                            Text(backupStatus,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        scope.launch {
                            backupStatus = "Backing up..."
                            val f = AppBackupManager.export(context, repository)
                            backupStatus = if (f != null) "Saved: ${f.name}" else "Backup failed"
                            Toast.makeText(context, backupStatus, Toast.LENGTH_LONG).show()
                        }
                    }) { Text("Export Now") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        pickBackupLauncher.launch(
                            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = "*/*"
                            }
                        )
                    }) { Text("Restore From File") }
                }
            )
        }
    }

        }
}
