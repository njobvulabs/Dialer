package com.example.ui.screens.home

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ui.MainViewModel
import com.example.ui.components.AppIcons
import com.example.ui.screens.sys.SystemShortcuts
import com.example.ui.theme.AppCornerRadius
import kotlin.math.roundToInt

private enum class TileKind { TOGGLE, SHORTCUT }

object LauncherTorchState {
    var on: Boolean = false
    var cameraId: String? = null
}

private data class QuickTile(
    val id: String,
    val title: String,
    val icon: ImageVector,
    val kind: TileKind
)

private val QUICK_TILES = listOf(
    QuickTile("flashlight", "Flashlight", AppIcons.FlashOn, TileKind.TOGGLE),
    QuickTile("rotation", "Auto-rotate", AppIcons.ScreenRotation, TileKind.TOGGLE),
    QuickTile("system:settings", "Phone Settings", AppIcons.Settings, TileKind.SHORTCUT),
    QuickTile("system:sim", "SIM Card Manager", AppIcons.SimCard, TileKind.SHORTCUT),
    QuickTile("system:clock", "Clock", AppIcons.AccessTime, TileKind.SHORTCUT),
    QuickTile("wifi", "Wi-Fi", AppIcons.Wifi, TileKind.SHORTCUT),
    QuickTile("bluetooth", "Bluetooth", AppIcons.Bluetooth, TileKind.SHORTCUT),
    QuickTile("data", "Mobile data", AppIcons.NetworkCheck, TileKind.SHORTCUT),
    QuickTile("hotspot", "Hotspot", AppIcons.WifiTethering, TileKind.SHORTCUT),
    QuickTile("sound", "Sound", AppIcons.VolumeUp, TileKind.SHORTCUT),
    QuickTile("display", "Display", AppIcons.Brightness6, TileKind.SHORTCUT),
    QuickTile("system:battery", "Battery", AppIcons.BatteryChargingFull, TileKind.SHORTCUT),
    QuickTile("system:location", "Location", AppIcons.LocationOn, TileKind.SHORTCUT),
    QuickTile("system:accessibility", "Accessibility", AppIcons.Accessibility, TileKind.SHORTCUT),
    QuickTile("system:apps", "Apps", AppIcons.Apps, TileKind.SHORTCUT),
    QuickTile("system:date", "Date & time", AppIcons.CalendarToday, TileKind.SHORTCUT),
    QuickTile("system:developer", "Developer options", AppIcons.DeveloperMode, TileKind.SHORTCUT)
)

private val QUICK_TILE_BY_ID = QUICK_TILES.associateBy { it.id }

@Suppress("DEPRECATION")
private fun readWifiState(context: Context): String {
    val manager = context.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
    val enabled = runCatching { manager?.isWifiEnabled == true }.getOrDefault(false)
    val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    val connected = runCatching {
        val network = connectivity?.activeNetwork ?: return@runCatching false
        connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }.getOrDefault(false)
    return when {
        connected -> "Connected"
        enabled -> "On"
        else -> "Off"
    }
}

private fun hasBluetoothAccess(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

private fun readBluetoothState(context: Context): String {
    if (!hasBluetoothAccess(context)) return "Allow access"
    val adapter = runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull() ?: return "Unavailable"
    return when (adapter.state) {
        BluetoothAdapter.STATE_ON -> "On"
        BluetoothAdapter.STATE_TURNING_ON -> "On…"
        BluetoothAdapter.STATE_TURNING_OFF -> "Off…"
        else -> "Off"
    }
}

private fun readSystemBrightness(context: Context): Float {
    val value = runCatching {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
    }.getOrDefault(128)
    return (value / 255f).coerceIn(0.01f, 1f)
}

private fun isAutoBrightness(context: Context): Boolean = runCatching {
    Settings.System.getInt(
        context.contentResolver,
        Settings.System.SCREEN_BRIGHTNESS_MODE,
        Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
    ) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
}.getOrDefault(true)

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun applyWindowBrightness(context: Context, value: Float): Float? {
    val activity = context.findActivity() ?: return null
    val attrs = activity.window.attributes
    val previous = attrs.screenBrightness
    attrs.screenBrightness = value.coerceIn(0.01f, 1f)
    activity.window.attributes = attrs
    return previous
}

private fun restoreWindowBrightness(context: Context, previous: Float) {
    val activity = context.findActivity() ?: return
    val attrs = activity.window.attributes
    attrs.screenBrightness = previous
    activity.window.attributes = attrs
}

@Composable
fun LauncherQuickSettingsPanel(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val visibleTileIds by viewModel.launcherQsTiles.collectAsState()

    var torchOn by remember { mutableStateOf(LauncherTorchState.on) }
    var torchSupported by remember { mutableStateOf(true) }
    var torchCameraId by remember { mutableStateOf(LauncherTorchState.cameraId) }
    var autoRotate by remember { mutableStateOf(SystemShortcuts.isAutoRotateEnabled(context)) }
    var canWriteSettings by remember { mutableStateOf(Settings.System.canWrite(context)) }
    var wifiState by remember { mutableStateOf(readWifiState(context)) }
    var bluetoothState by remember { mutableStateOf(readBluetoothState(context)) }
    var brightness by remember { mutableStateOf(readSystemBrightness(context)) }
    var autoBrightness by remember { mutableStateOf(isAutoBrightness(context)) }
    var originalWindowBrightness by remember { mutableStateOf<Float?>(null) }
    var showEditor by remember { mutableStateOf(false) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) toggleTorchInternal(context, torchCameraId, onToggle = { torchOn = it })
    }
    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        bluetoothState = if (granted) readBluetoothState(context) else "Allow access"
    }

    fun refreshStates() {
        autoRotate = SystemShortcuts.isAutoRotateEnabled(context)
        canWriteSettings = Settings.System.canWrite(context)
        wifiState = readWifiState(context)
        bluetoothState = readBluetoothState(context)
        autoBrightness = isAutoBrightness(context)
        if (originalWindowBrightness == null) brightness = readSystemBrightness(context)
    }

    fun previewBrightness(value: Float) {
        if (originalWindowBrightness == null) {
            originalWindowBrightness = applyWindowBrightness(context, value)
        } else {
            applyWindowBrightness(context, value)
        }
    }

    fun persistBrightness(value: Float) {
        if (!canWriteSettings) return
        runCatching {
            if (isAutoBrightness(context)) {
                Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
            }
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                (value * 255f).roundToInt().coerceIn(1, 255)
            )
            autoBrightness = false
        }.onFailure {
            Toast.makeText(context, "Could not change brightness", Toast.LENGTH_SHORT).show()
        }
    }

    fun restoreBrightnessPreview() {
        val previous = originalWindowBrightness ?: return
        restoreWindowBrightness(context, previous)
        originalWindowBrightness = null
    }

    fun toggleTorch() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        toggleTorchInternal(
            context = context,
            cameraId = torchCameraId,
            onToggle = { torchOn = it }
        )
    }

    fun toggleRotation() {
        if (!Settings.System.canWrite(context)) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }.onFailure {
                Toast.makeText(context, "Allow \"Modify system settings\" to toggle rotation", Toast.LENGTH_SHORT).show()
            }
            return
        }
        val newValue = if (SystemShortcuts.isAutoRotateEnabled(context)) 0 else 1
        runCatching {
            Settings.System.putInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, newValue)
            autoRotate = newValue == 1
            Toast.makeText(context, "Auto-rotate: ${if (newValue == 1) "On" else "Off"}", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(context, "Could not change rotation", Toast.LENGTH_SHORT).show()
        }
    }

    fun openDisplaySettings() {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Toast.makeText(context, "Settings not available", Toast.LENGTH_SHORT).show() }
    }

    fun performTile(id: String) {
        when (id) {
            "flashlight" -> toggleTorch()
            "rotation" -> toggleRotation()
            "wifi" -> SystemShortcuts.launch(context, "system:wifi")
            "bluetooth" -> {
                if (!hasBluetoothAccess(context)) {
                    bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                } else {
                    SystemShortcuts.launch(context, "system:bluetooth")
                }
            }
            "data" -> SystemShortcuts.launch(context, "system:data")
            "hotspot" -> SystemShortcuts.launch(context, "system:hotspot")
            "sound" -> SystemShortcuts.launch(context, "system:sound")
            "display" -> openDisplaySettings()
            else -> SystemShortcuts.launch(context, id)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshStates()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            restoreBrightnessPreview()
        }
    }

    DisposableEffect(Unit) {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        val cameraId = runCatching {
            cm?.cameraIdList?.firstOrNull { id ->
                val chars = cm.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull()
        torchCameraId = cameraId
        LauncherTorchState.cameraId = cameraId
        torchSupported = cameraId != null && cm != null
        val callback = if (cm != null && cameraId != null) {
            object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(id: String, enabled: Boolean) {
                    if (id == cameraId) {
                        torchOn = enabled
                        LauncherTorchState.on = enabled
                    }
                }

                override fun onTorchModeUnavailable(id: String) {
                    if (id == cameraId) {
                        torchOn = false
                        torchSupported = false
                        LauncherTorchState.on = false
                    }
                }
            }
        } else null
        if (cm != null && callback != null) {
            runCatching { cm.registerTorchCallback(callback, null) }
        }
        onDispose {
            if (cm != null && callback != null) {
                runCatching { cm.unregisterTorchCallback(callback) }
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshStates()
    }

    val tiles = visibleTileIds.mapNotNull { QUICK_TILE_BY_ID[it] }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val columns = when {
            maxWidth < 480.dp -> 3
            maxWidth < 840.dp -> 4
            else -> 5
        }
        val rows = (tiles.size + columns - 1) / columns
        val chromeHeight = 154.dp + if (autoBrightness && !canWriteSettings) 34.dp else 0.dp
        val gridMaxHeight = if (maxHeight == Dp.Infinity || maxHeight <= 0.dp) {
            240.dp
        } else {
            (maxHeight - chromeHeight).coerceAtLeast(72.dp)
        }
        val desiredGridHeight = if (rows == 0) 0.dp else (rows * 96 + (rows - 1) * 8).dp
        val gridHeight = minOf(desiredGridHeight, gridMaxHeight)
        val gridScrolls = desiredGridHeight > gridHeight

        GlassPanel(
            modifier = Modifier.fillMaxWidth(),
            shape = AppCornerRadius.xl,
            elevation = 6.dp,
            contentPadding = PaddingValues(14.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Quick settings",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "Brightness preview, local toggles and system settings shortcuts",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { showEditor = true }) {
                        Icon(AppIcons.Edit, contentDescription = "Edit quick settings tiles")
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        AppIcons.Brightness6,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = brightness,
                        onValueChange = { value ->
                            brightness = value
                            previewBrightness(value)
                        },
                        onValueChangeFinished = { persistBrightness(brightness) },
                        valueRange = 0.01f..1f,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                    )
                    Text(
                        "${(brightness * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (autoBrightness && !canWriteSettings) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "Auto brightness is on; preview only until access is allowed",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { openDisplaySettings() }) { Text("Open") }
                    }
                }
                Spacer(Modifier.height(10.dp))
                if (tiles.isEmpty()) {
                    Text(
                        "No quick tiles selected. Use the edit button to add some.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columns),
                        userScrollEnabled = gridScrolls,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(gridHeight)
                    ) {
                        items(tiles, key = { it.id }) { tile ->
                            val (active, subtitle) = when (tile.id) {
                                "flashlight" -> torchOn to if (!torchSupported) "Unavailable" else if (torchOn) "On" else "Off"
                                "rotation" -> autoRotate to when {
                                    !canWriteSettings -> "Allow access"
                                    autoRotate -> "On"
                                    else -> "Off"
                                }
                                "wifi" -> false to wifiState
                                "bluetooth" -> false to bluetoothState
                                else -> false to "Open"
                            }
                            QuickTileCard(
                                title = tile.title,
                                subtitle = subtitle,
                                icon = tile.icon,
                                active = active,
                                onClick = { performTile(tile.id) }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showEditor) {
        QuickTileEditorDialog(
            visibleIds = visibleTileIds,
            onSave = { ids ->
                viewModel.setLauncherQsTiles(ids)
                showEditor = false
            },
            onReset = {
                viewModel.setLauncherQsTiles(com.example.ui.LAUNCHER_QS_DEFAULT_TILES)
                showEditor = false
            },
            onDismiss = { showEditor = false }
        )
    }
}

fun toggleTorchInternal(
    context: Context,
    cameraId: String?,
    onToggle: (Boolean) -> Unit
) {
    val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    if (cm == null || cameraId == null) {
        Toast.makeText(context, "Flashlight not available", Toast.LENGTH_SHORT).show()
        return
    }
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        Toast.makeText(context, "Camera permission required for flashlight", Toast.LENGTH_SHORT).show()
        return
    }
    val newState = !LauncherTorchState.on
    runCatching { cm.setTorchMode(cameraId, newState) }
        .onSuccess {
            LauncherTorchState.on = newState
            onToggle(newState)
        }
        .onFailure {
            Toast.makeText(context, "Could not toggle flashlight", Toast.LENGTH_SHORT).show()
        }
}

@Composable
private fun QuickTileEditorDialog(
    visibleIds: List<String>,
    onSave: (List<String>) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val draft = remember(visibleIds) {
        mutableStateListOf<String>().apply { addAll(visibleIds) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit quick settings") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "Selected ${draft.size}/${com.example.ui.LAUNCHER_QS_MAX_TILES}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                QUICK_TILES.forEach { tile ->
                    val index = draft.indexOf(tile.id)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = index >= 0,
                            onCheckedChange = { checked ->
                                when {
                                    checked && index < 0 && draft.size >= com.example.ui.LAUNCHER_QS_MAX_TILES ->
                                        Toast.makeText(context, "Maximum ${com.example.ui.LAUNCHER_QS_MAX_TILES} tiles", Toast.LENGTH_SHORT).show()
                                    checked && index < 0 -> draft.add(tile.id)
                                    !checked && index >= 0 && draft.size == 1 ->
                                        Toast.makeText(context, "Keep at least one tile", Toast.LENGTH_SHORT).show()
                                    !checked && index >= 0 -> draft.removeAt(index)
                                }
                            }
                        )
                        Icon(
                            tile.icon,
                            contentDescription = null,
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            tile.title,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                if (index > 0) {
                                    val item = draft.removeAt(index)
                                    draft.add(index - 1, item)
                                }
                            },
                            enabled = index > 0
                        ) {
                            Icon(AppIcons.KeyboardArrowUp, contentDescription = "Move ${tile.title} up")
                        }
                        IconButton(
                            onClick = {
                                if (index >= 0 && index < draft.lastIndex) {
                                    val item = draft.removeAt(index)
                                    draft.add(index + 1, item)
                                }
                            },
                            enabled = index >= 0 && index < draft.lastIndex
                        ) {
                            Icon(AppIcons.KeyboardArrowDown, contentDescription = "Move ${tile.title} down")
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(draft) }) { Text("Save") } },
        dismissButton = {
            Row {
                TextButton(onClick = onReset) { Text("Reset") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

@Composable
private fun QuickTileCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    active: Boolean,
    onClick: () -> Unit
) {
    val container = if (active) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
    val content = if (active) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = onClick,
        color = container,
        contentColor = content,
        shape = AppCornerRadius.md,
        modifier = Modifier
            .height(96.dp)
            .semantics(mergeDescendants = true) { role = Role.Button }
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Column {
                Text(
                    title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
