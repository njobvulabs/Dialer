package com.example

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.example.ui.screens.webapp.GeckoWebAppScreen
import com.example.ui.theme.AppAccent
import com.example.ui.theme.AppThemeMode
import com.example.ui.theme.MyApplicationTheme

/**
 * Hosts the 14 GeckoView web apps (Telegram / Facebook / ... / NotebookLM) in
 * their own OS process (`:webapps`) on their own Gecko profile ([com.example.ui.screens.webapp.WebAppEngine]),
 * so the in-app browser's "Clear Browsing Data" (a global wipe of the
 * main-process default profile) can never log the web apps out. The process is
 * pinned by the per-process keep-alive foreground service when the screen is
 * backgrounded via "Exit & keep running", which also makes sign-ins durable on
 * aggressive OEM ROMs (the WhatsApp 1/2 pattern).
 *
 * Reentry (both via the hub and via this activity's "Exit & keep running")
 * reaches the still-alive `:webapps` process, whose [com.example.ui.screens.webapp.GeckoWebAppHolder]
 * still holds each app's live GeckoSession, so the page resumes without reloading.
 */
open class GeckoWebAppActivity : ComponentActivity() {

    private var appId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        appId = intent.getStringExtra(EXTRA_APP_ID)
        if (appId == null) {
            finish()
            return
        }
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContent {
            val theme = rememberThemePrefs()
            MyApplicationTheme(
                themeMode = theme.mode,
                accent = theme.accent,
                dynamicColor = theme.dynamicColors,
                amoledBlack = theme.amoledBlack
            ) {
                // Mirror MainActivity's font-scale override: this process reads the same
                // "font_scale" pref so the chrome bar matches the rest of the app instead
                // of rendering at the raw system font scale (which the OEM ROM often
                // sets high, making the bar's chips overflow).
                val density = LocalDensity.current
                val fontScale = theme.fontScalePercent / 100f
                CompositionLocalProvider(
                    LocalDensity provides Density(
                        density = density.density,
                        fontScale = density.fontScale * fontScale
                    )
                ) {
                    val id = appId ?: return@CompositionLocalProvider
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .imePadding()
                    ) {
                        GeckoWebAppScreen(appId = id, onExit = ::exitToNjDialer)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        appId = intent.getStringExtra(EXTRA_APP_ID)
    }

    /** Return to NJ-Dialer; the app's live session stays in this process's
     *  GeckoWebAppHolder (pinned by the keep-alive service when kept running). */
    private fun exitToNjDialer() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK
                            or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    )
            )
        }
        finish()
    }

    companion object {
        const val EXTRA_APP_ID = "web_app_id"

        fun intent(context: Context, appId: String): Intent =
            Intent(context, GeckoWebAppActivity::class.java).apply {
                putExtra(EXTRA_APP_ID, appId)
            }
    }
}

private data class AppThemePrefs(
    val mode: AppThemeMode,
    val accent: AppAccent,
    val dynamicColors: Boolean,
    val amoledBlack: Boolean,
    /** Same "font_scale" percent (80..130) MainViewModel writes; applied as a LocalDensity override. */
    val fontScalePercent: Int
)

/** Read the saved app theme (same `app_settings` keys the Compose screens use). */
@androidx.compose.runtime.Composable
private fun rememberThemePrefs(): AppThemePrefs {
    val context = androidx.compose.ui.platform.LocalContext.current
    return androidx.compose.runtime.remember {
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val mode = runCatching {
            AppThemeMode.valueOf(prefs.getString("theme_mode", AppThemeMode.SYSTEM.name)!!)
        }.getOrDefault(AppThemeMode.SYSTEM)
        val accent = runCatching {
            AppAccent.valueOf(prefs.getString("accent", AppAccent.INDIGO.name)!!)
        }.getOrDefault(AppAccent.INDIGO)
        AppThemePrefs(
            mode = mode,
            accent = accent,
            dynamicColors = prefs.getBoolean("dynamic_colors", true),
            amoledBlack = prefs.getBoolean("amoled_black", false),
            fontScalePercent = prefs.getInt("font_scale", 100).coerceIn(80, 130)
        )
    }
}