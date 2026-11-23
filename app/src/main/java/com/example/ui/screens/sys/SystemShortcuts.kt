package com.example.ui.screens.sys

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import com.example.ui.components.AppIcons
import com.example.ui.screens.apps.AppItem

/**
 * The phone-config shortcuts that used to be the only contents of the System Tools
 * screen. They are plain `Intent`/toggle launches with no app state, so the same
 * list now backs both the standalone System Tools screen and the launcher's
 * swipe-left System Tools page. Ids are `system:`-prefixed and never collide with
 * an `immersiveAppId` route, because [launch] handles them directly.
 */
object SystemShortcuts {

    /** One section of shortcuts. Order is the display order on the system page. */
    val items: List<AppItem> = listOf(
        AppItem(
            "system:settings", "Phone Settings", AppIcons.Settings,
            "Open the system settings app", "config,device", "Device"
        ),
        AppItem(
            "system:sim", "SIM Card Manager", AppIcons.SimCard,
            "Dual SIM, mobile data & preferred network", "sim,network,carrier", "Device"
        ),
        AppItem(
            "system:data", "Data Connection", AppIcons.NetworkCheck,
            "Mobile data & network settings", "mobile,internet", "Device"
        ),
        AppItem(
            "system:hotspot", "Hotspot", AppIcons.WifiTethering,
            "Share mobile data with other devices", "tether,share", "Device"
        ),
        AppItem(
            "system:display", "Display", AppIcons.Brightness6,
            "Brightness, sleep, font size, dark mode", "screen,brightness", "Device"
        ),
        AppItem(
            "system:rotation", "Screen Rotation", AppIcons.ScreenRotation,
            "Toggle auto-rotate", "rotate,orientation", "Device"
        ),
        AppItem(
            "system:apps", "App Management", AppIcons.Apps,
            "Manage installed apps, force stop, uninstall", "uninstall,force stop", "Apps & Stores"
        ),
        AppItem(
            "system:play", "Google Play Store", AppIcons.Store,
            "Open the Play Store", "apps,download", "Apps & Stores"
        ),
        AppItem(
            "system:palm", "Palm Store", AppIcons.ShoppingBag,
            "Open Palm Store (Transsion)", "store,transsion", "Apps & Stores"
        ),
        AppItem(
            "system:phonemaster", "Phone Master", AppIcons.PhoneAndroid,
            "Transsion phone manager & cleaner", "cleaner,boost", "Apps & Stores"
        ),
        AppItem(
            "system:clock", "Clock", AppIcons.AccessTime,
            "Open the clock / alarm app", "alarm,timer", "Device"
        )
    )

    private fun safeLaunch(context: Context, intent: Intent, fallbackLabel: String) {
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, fallbackLabel, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Could not open: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchSettings(context: Context, action: String) {
        safeLaunch(context, Intent(action), "Settings not available")
    }

    private fun launchDataSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                context.startActivity(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
                return
            } catch (e: Exception) {
                // fall through to legacy page
            }
        }
        launchSettings(context, Settings.ACTION_WIRELESS_SETTINGS)
    }

    private fun launchHotspotSettings(context: Context) {
        val candidates = listOf(
            Intent("android.settings.TETHERING_SETTINGS"),
            Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"),
            Intent(Settings.ACTION_WIRELESS_SETTINGS)
        )
        for (intent in candidates) {
            try {
                context.startActivity(intent)
                return
            } catch (e: Exception) {
                continue
            }
        }
        Toast.makeText(context, "Hotspot settings not available", Toast.LENGTH_SHORT).show()
    }

    private fun launchSimSettings(context: Context) {
        val simIntent = Intent("android.settings.SIM_SETTINGS")
        val fallbacks = mutableListOf<Intent>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            fallbacks.add(Intent(Settings.ACTION_MANAGE_ALL_SIM_PROFILES_SETTINGS))
        }
        fallbacks.add(Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS))
        fallbacks.add(Intent(Settings.ACTION_WIRELESS_SETTINGS))
        for (intent in listOf(simIntent) + fallbacks) {
            try {
                context.startActivity(intent)
                return
            } catch (e: Exception) {
                // try the next fallback page
            }
        }
        Toast.makeText(context, "SIM settings not available", Toast.LENGTH_SHORT).show()
    }

    fun isAutoRotateEnabled(context: Context): Boolean {
        return try {
            Settings.System.getInt(
                context.contentResolver,
                Settings.System.ACCELEROMETER_ROTATION
            ) == 1
        } catch (e: Exception) {
            false
        }
    }

    private fun toggleScreenRotation(context: Context) {
        val newValue = if (isAutoRotateEnabled(context)) 0 else 1
        Settings.System.putInt(
            context.contentResolver,
            Settings.System.ACCELEROMETER_ROTATION,
            newValue
        )
        Toast.makeText(
            context,
            "Auto-rotate: ${if (newValue == 1) "On" else "Off"}",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun launchInstalledApp(
        context: Context,
        title: String,
        packages: List<String>,
        labelKeywords: List<String>
    ) {
        val pm = context.packageManager
        for (pkg in packages) {
            val launch = pm.getLaunchIntentForPackage(pkg)
            if (launch != null) {
                safeLaunch(context, launch, "$title not found")
                return
            }
        }
        // Fallback: match installed launcher apps by label
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = runCatching {
            pm.queryIntentActivities(launcherIntent, PackageManager.MATCH_DEFAULT_ONLY)
        }.getOrDefault(emptyList())
        val match = apps.firstOrNull { resolveInfo ->
            runCatching {
                val label = pm.getApplicationLabel(resolveInfo.activityInfo.applicationInfo).toString()
                labelKeywords.any { label.contains(it, ignoreCase = true) }
            }.getOrDefault(false)
        }
        if (match != null) {
            val launch = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            if (launch != null) {
                safeLaunch(context, launch, "$title not found")
                return
            }
        }
        // Last resort: search the Play Store for the app title.
        val marketUri = "market://search?q=$title"
        val webUri = "https://play.google.com/store/search?q=$title"
        val opened = runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(marketUri)))
        }.isSuccess
        if (!opened) {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(webUri)))
            }.onFailure {
                Toast.makeText(context, "$title is not installed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Runs the shortcut named by [id]. Unknown ids are ignored. */
    fun launch(context: Context, id: String) {
        when (id) {
            "system:settings" -> launchSettings(context, Settings.ACTION_SETTINGS)
            "system:sim" -> launchSimSettings(context)
            "system:battery" -> launchSettings(context, Settings.ACTION_BATTERY_SAVER_SETTINGS)
            "system:location" -> launchSettings(context, Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            "system:accessibility" -> launchSettings(context, Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "system:date" -> launchSettings(context, Settings.ACTION_DATE_SETTINGS)
            "system:developer" -> launchSettings(context, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            "system:wifi" -> launchSettings(context, Settings.ACTION_WIFI_SETTINGS)
            "system:bluetooth" -> launchSettings(context, Settings.ACTION_BLUETOOTH_SETTINGS)
            "system:data" -> launchDataSettings(context)
            "system:hotspot" -> launchHotspotSettings(context)
            "system:display" -> launchSettings(context, Settings.ACTION_DISPLAY_SETTINGS)
            "system:sound" -> launchSettings(context, Settings.ACTION_SOUND_SETTINGS)
            "system:rotation" -> toggleScreenRotation(context)
            "system:apps" -> launchSettings(context, Settings.ACTION_APPLICATION_SETTINGS)
            "system:play" -> launchInstalledApp(
                context, "Google Play Store",
                listOf("com.android.vending"), listOf("play store", "google play")
            )
            "system:palm" -> launchInstalledApp(
                context, "Palm Store",
                listOf("com.transsnet.store"), listOf("palm store", "palm")
            )
            "system:phonemaster" -> launchInstalledApp(
                context, "Phone Master",
                listOf(
                    "com.transsion.phonemaster",
                    "com.transsnet.phonemaster",
                    "com.transsion.phone.master"
                ),
                listOf("phone master", "phonemaster")
            )
            "system:clock" -> launchInstalledApp(
                context, "Clock",
                listOf(
                    "com.android.deskclock",
                    "com.google.android.deskclock",
                    "com.transsion.deskclock",
                    "com.transsion.clock",
                    "com.android.alarmclock"
                ),
                listOf("clock", "alarm")
            )
        }
    }

    /** Grouped for the launcher page's section headers, in [items] order. */
    fun sections(): List<Pair<String, List<AppItem>>> =
        items.groupBy { it.section }
            .map { (name, list) -> name to list }
}
