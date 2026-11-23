package com.example.util

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.example.ui.components.AppIcons
import com.example.ui.theme.AppAccent
import com.example.ui.theme.AppThemeMode
import com.example.ui.theme.BlueOnPrimaryContainer

/**
 * Non-Compose helper so View-based UI (e.g. the GeckoView WhatsApp web-app top bar) can
 * follow the app's saved theme (accent + light/dark) just like the Compose screens do.
 */
data class ViewThemeColors(
    val surface: Int,
    val background: Int,
    val onSurface: Int,
    val primary: Int,
    val primaryContainer: Int,
    val onPrimaryContainer: Int,
    val secondaryContainer: Int,
    val onSecondaryContainer: Int
)

fun contextViewTheme(context: Context): ViewThemeColors {
    val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    val mode = runCatching {
        AppThemeMode.valueOf(prefs.getString("theme_mode", AppThemeMode.SYSTEM.name)!!)
    }.getOrDefault(AppThemeMode.SYSTEM)
    val accent = runCatching {
        AppAccent.valueOf(prefs.getString("accent", AppAccent.INDIGO.name)!!)
    }.getOrDefault(AppAccent.INDIGO)
    val amoled = prefs.getBoolean("amoled_black", false)

    val isSystemDark = (context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val isDark = when (mode) {
        AppThemeMode.SYSTEM -> isSystemDark
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }

    fun c(color: Color): Int = color.toArgb()

    return if (isDark) {
        ViewThemeColors(
            surface = c(if (amoled) Color(0xFF0A0A0A) else Color(0xFF1B1E26)),
            background = c(if (amoled) Color.Black else Color(0xFF10131A)),
            onSurface = c(Color(0xFFE2E2E9)),
            primary = c(accent.primary),
            primaryContainer = c(Color(0xFF004878)),
            onPrimaryContainer = c(Color(0xFFD3E3FD)),
            secondaryContainer = c(Color(0xFF3B355E)),
            onSecondaryContainer = c(Color(0xFFE7DEFF))
        )
    } else {
        ViewThemeColors(
            surface = c(Color(0xFFFFFFFF)),
            background = c(Color(0xFFF8F9FF)),
            onSurface = c(Color(0xFF1C1B1F)),
            primary = c(accent.primary),
            primaryContainer = c(Color(0xFFD3E3FD)),
            onPrimaryContainer = c(BlueOnPrimaryContainer),
            secondaryContainer = c(Color(0xFFE3DDFB)),
            onSecondaryContainer = c(Color(0xFF1D1B3B))
        )
    }
}

@Suppress("unused")
fun withAlpha(color: Int, alpha: Float): Int {
    val a = (255 * alpha.coerceIn(0f, 1f)).toInt()
    return AndroidColor.argb(a, AndroidColor.red(color), AndroidColor.green(color), AndroidColor.blue(color))
}
