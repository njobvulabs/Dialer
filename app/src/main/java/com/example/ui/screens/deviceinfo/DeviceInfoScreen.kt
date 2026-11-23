package com.example.ui.screens.deviceinfo

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.components.landscapeContentWidth
import com.example.ui.components.AppIcons
import com.example.ui.theme.AppCornerRadius
import com.example.ui.MainViewModel
import com.example.ui.components.FloatingTopBar
import com.example.ui.screens.apkbackup.InstalledAppsSection
import com.example.util.rememberSavedListState
import java.net.NetworkInterface
import java.text.DecimalFormat
import java.util.Collections
import java.util.concurrent.TimeUnit

private data class InfoItem(
    val label: String,
    val value: String,
    val icon: ImageVector
)

private data class InfoSection(
    val title: String,
    val icon: ImageVector,
    val items: List<InfoItem>
)

private data class BatteryState(
    val level: Int = -1,
    val status: String = "Unknown",
    val health: String = "Unknown",
    val temp: String = "Unknown",
    val voltage: String = "Unknown",
    val plugged: String = "Discharging",
    val tech: String = "Unknown"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceInfoScreen(viewModel: MainViewModel, initialTab: String = "info") {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var batteryState by remember { mutableStateOf(BatteryState()) }

    // Register real-time system battery broadcast receiver
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    val pct = if (level >= 0 && scale > 0) (level * 100) / scale else -1

                    val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
                    val formattedTemp = "${DecimalFormat("#.#").format(temp / 10.0)}°C"

                    val rawVoltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
                    val formattedVoltage = "${DecimalFormat("#.##").format(rawVoltage / 1000.0)} V"

                    val statusInt = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    val statusStr = when (statusInt) {
                        BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
                        BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
                        BatteryManager.BATTERY_STATUS_FULL -> "Full"
                        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not Charging"
                        else -> "Unknown"
                    }

                    val healthInt = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)
                    val healthStr = when (healthInt) {
                        BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
                        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
                        BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
                        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over Voltage"
                        else -> "Normal"
                    }

                    val pluggedInt = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
                    val pluggedStr = when (pluggedInt) {
                        BatteryManager.BATTERY_PLUGGED_AC -> "AC Adapter"
                        BatteryManager.BATTERY_PLUGGED_USB -> "USB Port"
                        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
                        else -> "Unplugged"
                    }

                    val tech = intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "Li-ion"

                    batteryState = BatteryState(
                        level = pct,
                        status = statusStr,
                        health = healthStr,
                        temp = formattedTemp,
                        voltage = formattedVoltage,
                        plugged = pluggedStr,
                        tech = tech
                    )
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        context.registerReceiver(receiver, filter)
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    val sections = remember(context) { buildSections(context) }
    val (ramPct, storagePct) = remember(context) { getUsagePercentages(context) }
    val listState = rememberSavedListState("deviceinfo", viewModel)
    // Phone Info and APK Backup are now TWO separate screens (one tile each on
    // the Tools tab): the pill switcher that used to sit in this top bar is
    // gone. initialTab picks the content once and never changes while open.
    val showApps = initialTab == "apps"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        val useGlassmorphism by viewModel.useGlassmorphism.collectAsState()
        FloatingTopBar(modifier = Modifier.fillMaxWidth(), useGlass = useGlassmorphism) {
            Text(
                text = if (showApps) "APK Backup" else "Phone Info",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        if (showApps) {
            InstalledAppsSection(viewModel, modifier = Modifier.fillMaxSize())
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().landscapeContentWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
        // Copy All button (top bar no longer hosts actions)
        item {
            OutlinedButton(
                onClick = {
                    val fullSummary = buildClipboardText(sections, batteryState)
                    clipboardManager.setText(AnnotatedString(fullSummary))
                    Toast.makeText(context, "Copied all info to clipboard", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(AppIcons.ContentCopy, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Copy All Info")
            }
        }

        // Live Battery Card
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)),
                    shape = AppCornerRadius.md
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (batteryState.plugged != "Unplugged") AppIcons.BatteryChargingFull else AppIcons.BatteryStd,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Battery Status",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                                )
                                Text(
                                    text = if (batteryState.level >= 0) "${batteryState.level}% • ${batteryState.status} (${batteryState.plugged})" else "Reading...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (batteryState.level >= 0) {
                            Spacer(Modifier.height(10.dp))
                            LinearProgressIndicator(
                                progress = { batteryState.level / 100f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(MaterialTheme.shapes.extraSmall),
                                color = when {
                                    batteryState.level <= 15 -> MaterialTheme.colorScheme.error
                                    batteryState.level <= 40 -> MaterialTheme.colorScheme.tertiary
                                    else -> MaterialTheme.colorScheme.primary
                                }
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Temp: ${batteryState.temp}", style = MaterialTheme.typography.bodySmall)
                                Text("Voltage: ${batteryState.voltage}", style = MaterialTheme.typography.bodySmall)
                                Text("Health: ${batteryState.health}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            // System Memory Gauges Summary Card
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    shape = AppCornerRadius.md
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Usage Overview", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                        Spacer(Modifier.height(10.dp))

                        Text("RAM Usage ($ramPct%)", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { ramPct / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(AppCornerRadius.xs),
                            color = MaterialTheme.colorScheme.secondary
                        )

                        Spacer(Modifier.height(10.dp))

                        Text("Storage Usage ($storagePct%)", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { storagePct / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(AppCornerRadius.xs),
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
            }

            // Expandable/Detailed Category Cards
            items(sections) { section ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = AppCornerRadius.md
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(section.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(section.title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                        }
                        Spacer(Modifier.height(10.dp))
                        section.items.forEach { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        clipboardManager.setText(AnnotatedString("${item.label}: ${item.value}"))
                                        Toast.makeText(context, "Copied ${item.label}", Toast.LENGTH_SHORT).show()
                                    }
                                    .padding(vertical = 6.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(item.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = item.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = item.value,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
            }
        }

            }
        }
    }
}

private fun getUsagePercentages(context: Context): Pair<Int, Int> {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val mem = ActivityManager.MemoryInfo()
    am.getMemoryInfo(mem)
    val ramPct = (((mem.totalMem - mem.availMem).toDouble() / mem.totalMem) * 100).toInt()

    val stats = StatFs(Environment.getDataDirectory().absolutePath)
    val totalStorage = stats.totalBytes
    val freeStorage = stats.availableBytes
    val storagePct = if (totalStorage > 0) (((totalStorage - freeStorage).toDouble() / totalStorage) * 100).toInt() else 0

    return Pair(ramPct, storagePct)
}

private fun getIPAddress(): String {
    runCatching {
        val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
        for (intf in interfaces) {
            val addrs = Collections.list(intf.inetAddresses)
            for (addr in addrs) {
                if (!addr.isLoopbackAddress) {
                    val sAddr = addr.hostAddress ?: ""
                    val isIPv4 = sAddr.indexOf(':') < 0
                    if (isIPv4) return sAddr
                }
            }
        }
    }
    return "Not Connected"
}

private fun getNetworkType(context: Context): String {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val nw = cm.activeNetwork ?: return "Disconnected"
    val actNw = cm.getNetworkCapabilities(nw) ?: return "Disconnected"
    return when {
        actNw.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        actNw.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular Data"
        actNw.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        else -> "Connected"
    }
}

private fun getSystemUptime(): String {
    val uptimeMs = SystemClock.elapsedRealtime()
    val hours = TimeUnit.MILLISECONDS.toHours(uptimeMs)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(uptimeMs) % 60
    return "${hours}h ${minutes}m"
}

private fun buildSections(context: Context): List<InfoSection> {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val mem = ActivityManager.MemoryInfo()
    am.getMemoryInfo(mem)

    val totalRam = mem.totalMem
    val freeRam = mem.availMem
    val usedRam = totalRam - freeRam

    val storagePair = runCatching {
        val stats = StatFs(Environment.getDataDirectory().absolutePath)
        Pair(stats.totalBytes, stats.availableBytes)
    }.getOrElse { Pair(0L, 0L) }

    val totalStorage = storagePair.first
    val usedStorage = storagePair.first - storagePair.second

    val sensors = runCatching {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sm.getSensorList(Sensor.TYPE_ALL)
    }.getOrElse { emptyList() }

    val dm = context.resources.displayMetrics
    val densityDpi = dm.densityDpi
    val screenW = dm.widthPixels
    val screenH = dm.heightPixels

    val model = "${Build.MANUFACTURER} ${Build.MODEL}"
    val androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    val kernel = System.getProperty("os.version") ?: "Unknown"

    val abis = Build.SUPPORTED_ABIS.joinToString(", ")
    val cores = Runtime.getRuntime().availableProcessors()

    return listOf(
        InfoSection(
            "Device",
            AppIcons.PhoneAndroid,
            listOf(
                InfoItem("Model", model, AppIcons.Phone),
                InfoItem("Manufacturer", Build.MANUFACTURER, AppIcons.Build),
                InfoItem("Brand", Build.BRAND, AppIcons.Star),
                InfoItem("Board", Build.BOARD, AppIcons.Memory),
                InfoItem("Hardware", Build.HARDWARE, AppIcons.Memory),
                InfoItem("Uptime", getSystemUptime(), AppIcons.Timer)
            )
        ),
        InfoSection(
            "CPU & System",
            AppIcons.Memory,
            listOf(
                InfoItem("CPU Architecture", abis, AppIcons.Architecture),
                InfoItem("CPU Cores", "$cores Cores", AppIcons.Memory),
                InfoItem("Kernel Version", kernel, AppIcons.Terminal)
            )
        ),
        InfoSection(
            "Android OS",
            AppIcons.Android,
            listOf(
                InfoItem("Version", androidVersion, AppIcons.Android),
                InfoItem("API Level", Build.VERSION.SDK_INT.toString(), AppIcons.Code),
                InfoItem("Security Patch", Build.VERSION.SECURITY_PATCH ?: "N/A", AppIcons.Security),
                InfoItem("Build ID", Build.ID, AppIcons.Layers)
            )
        ),
        InfoSection(
            "Display",
            AppIcons.DisplaySettings,
            listOf(
                InfoItem("Resolution", "${screenW} x ${screenH} px", AppIcons.DisplaySettings),
                InfoItem("Density", "$densityDpi dpi (${dm.density}x)", AppIcons.ZoomIn),
                InfoItem("Viewport Size", "${context.resources.configuration.screenWidthDp} x ${context.resources.configuration.screenHeightDp} dp", AppIcons.AspectRatio)
            )
        ),
        InfoSection(
            "Memory & Storage",
            AppIcons.Storage,
            listOf(
                InfoItem("RAM Used", Formatter.formatFileSize(context, usedRam), AppIcons.Memory),
                InfoItem("RAM Total", Formatter.formatFileSize(context, totalRam), AppIcons.Storage),
                InfoItem("Storage Used", Formatter.formatFileSize(context, usedStorage), AppIcons.SdStorage),
                InfoItem("Storage Total", Formatter.formatFileSize(context, totalStorage), AppIcons.Folder)
            )
        ),
        InfoSection(
            "Network State",
            AppIcons.Wifi,
            listOf(
                InfoItem("Connection Type", getNetworkType(context), AppIcons.SignalCellularAlt),
                InfoItem("IP Address", getIPAddress(), AppIcons.Public)
            )
        ),
        InfoSection(
            "Hardware Sensors",
            AppIcons.Sensors,
            listOf(
                InfoItem("Total Sensors", sensors.size.toString(), AppIcons.Sensors),
                InfoItem("Unique Types", "${sensors.distinctBy { it.type }.size} categories", AppIcons.Explore)
            )
        )
    )
}

private fun buildClipboardText(sections: List<InfoSection>, battery: BatteryState): String {
    val sb = StringBuilder()
    sb.appendLine("=== DEVICE INFORMATION ===")
    sb.appendLine("Battery: ${battery.level}% | Status: ${battery.status} | Temp: ${battery.temp} | Health: ${battery.health}")
    sb.appendLine()
    for (sec in sections) {
        sb.appendLine("[ ${sec.title} ]")
        for (item in sec.items) {
            sb.appendLine("${item.label}: ${item.value}")
        }
        sb.appendLine()
    }
    return sb.toString().trim()
}
