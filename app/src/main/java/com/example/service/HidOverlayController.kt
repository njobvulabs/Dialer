package com.example.service

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.ui.screens.hid.HidPadScreen
import com.example.ui.theme.AppAccent
import com.example.ui.theme.AppThemeMode
import com.example.ui.theme.MyApplicationTheme
import com.example.util.BtHidController
import com.example.util.HidPrefs

object HidOverlayController {
    private var view: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null
    private var owner: OverlayOwner? = null
    private var wm: WindowManager? = null

    fun isShowing(): Boolean = view != null

    fun toggle(context: Context) {
        if (isShowing()) hide(context) else show(context)
    }

    fun show(context: Context) {
        if (isShowing()) return
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(app)) {
            requestPermission(app)
            return
        }
        val manager = app.getSystemService(WindowManager::class.java) ?: return
        wm = manager
        val prefs = HidPrefs.get(app)
        val metrics = app.resources.displayMetrics
        val width = minOf((metrics.widthPixels * 0.92f).toInt(), dp(app, 380))
        val height = minOf((metrics.heightPixels * 0.78f).toInt(), dp(app, 600))
        val layout = WindowManager.LayoutParams(
            width,
            height,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(HidPrefs.OVERLAY_X, dp(app, 12))
            y = prefs.getInt(HidPrefs.OVERLAY_Y, metrics.heightPixels / 5)
        }
        params = layout

        val lifecycleOwner = OverlayOwner().also { owner = it }
        lifecycleOwner.attach()
        val composeView = ComposeView(app).apply {
            setViewCompositionStrategy(
                androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnDetachedFromWindow
            )
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setContent {
                HidPadTheme {
                    HidPadScreen(
                        title = "BT input",
                        onClose = { hide(app) },
                        onDrag = { dx, dy ->
                            params?.let { p ->
                                p.x = (p.x + dx.toInt()).coerceAtLeast(0)
                                p.y = (p.y + dy.toInt()).coerceAtLeast(0)
                                runCatching { wm?.updateViewLayout(view, p) }
                                prefs.edit()
                                    .putInt(HidPrefs.OVERLAY_X, p.x)
                                    .putInt(HidPrefs.OVERLAY_Y, p.y)
                                    .apply()
                            }
                        }
                    )
                }
            }
        }
        view = composeView
        runCatching { manager.addView(composeView, layout) }.onFailure {
            owner?.destroy()
            owner = null
            view = null
            params = null
        }
        if (!BtHidController.state.value.registered) {
            runCatching { BtHidController.start(app) }
        }
    }

    fun createComposeView(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        savedStateRegistryOwner: SavedStateRegistryOwner,
        viewModelStoreOwner: ViewModelStoreOwner,
        onClose: () -> Unit,
        onDrag: ((Float, Float) -> Unit)? = null,
        title: String = "BT input"
    ): ComposeView = ComposeView(context).apply {
        setViewCompositionStrategy(
            androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnDetachedFromWindow
        )
        setViewTreeLifecycleOwner(lifecycleOwner)
        setViewTreeSavedStateRegistryOwner(savedStateRegistryOwner)
        setViewTreeViewModelStoreOwner(viewModelStoreOwner)
        setContent {
            HidPadTheme {
                HidPadScreen(
                    title = title,
                    onClose = onClose,
                    onDrag = onDrag
                )
            }
        }
    }

    fun hide(@Suppress("UNUSED_PARAMETER") context: Context) {
        val composeView = view
        view = null
        params = null
        runCatching { wm?.removeView(composeView) }
        wm = null
        owner?.destroy()
        owner = null
    }

    fun requestPermission(context: Context) {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private class OverlayOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val controller = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val viewModelStore = ViewModelStore()
        override val savedStateRegistry: SavedStateRegistry
            get() = controller.savedStateRegistry

        fun attach() {
            controller.performAttach()
            runCatching { controller.performRestore(Bundle()) }
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun destroy() {
            if (registry.currentState == Lifecycle.State.DESTROYED) return
            registry.currentState = Lifecycle.State.DESTROYED
            viewModelStore.clear()
        }
    }
}

@Composable
fun HidPadTheme(content: @Composable () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    val mode = runCatching {
        AppThemeMode.valueOf(prefs.getString("theme_mode", AppThemeMode.SYSTEM.name)!!)
    }.getOrDefault(AppThemeMode.SYSTEM)
    val accent = runCatching {
        AppAccent.valueOf(prefs.getString("accent", AppAccent.INDIGO.name)!!)
    }.getOrDefault(AppAccent.INDIGO)
    val dynamic = prefs.getBoolean("dynamic_colors", true)
    val amoled = prefs.getBoolean("amoled_black", false)
    val scale = prefs.getInt("font_scale", 100).coerceIn(80, 130)
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, density.fontScale * scale / 100f)
    ) {
        MyApplicationTheme(
            themeMode = mode,
            accent = accent,
            dynamicColor = dynamic,
            amoledBlack = amoled,
            content = content
        )
    }
}
