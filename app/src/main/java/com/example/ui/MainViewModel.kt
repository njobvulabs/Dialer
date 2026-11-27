package com.example.ui

import android.Manifest
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.db.*
import com.example.data.repository.AppRepository
import com.example.service.SmsNotificationListener
import com.example.ui.components.AppIcons
import com.example.ui.screens.browser.NTP_URL
import com.example.ui.theme.AppAccent
import com.example.ui.theme.AppThemeMode
import com.example.util.ContactNumbers
import com.example.util.DeviceContacts
import com.example.util.FolderStats
import com.example.util.KeepAliveTimeout
import com.example.util.LauncherWallpapers
import com.example.util.MediaItem
import com.example.util.StorageHelper
import com.example.util.StorageRoot
import com.example.util.SystemCallLogImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = AppRepository(AppDatabase.getDatabase(application))

    private val prefs = application.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    // Theme & Preferences
    private val _themeMode = MutableStateFlow(
        runCatching { AppThemeMode.valueOf(prefs.getString("theme_mode", AppThemeMode.SYSTEM.name)!!) }
            .getOrDefault(AppThemeMode.SYSTEM)
    )
    val themeMode: StateFlow<AppThemeMode> = _themeMode.asStateFlow()

    private val _accent = MutableStateFlow(
        runCatching { AppAccent.valueOf(prefs.getString("accent", AppAccent.INDIGO.name)!!) }
            .getOrDefault(AppAccent.INDIGO)
    )
    val accent: StateFlow<AppAccent> = _accent.asStateFlow()

    fun setThemeMode(mode: AppThemeMode) {
        _themeMode.value = mode
        prefs.edit().putString("theme_mode", mode.name).apply()
    }

    fun setAccent(accent: AppAccent) {
        _accent.value = accent
        prefs.edit().putString("accent", accent.name).apply()
    }

    private val _dynamicColors = MutableStateFlow(prefs.getBoolean("dynamic_colors", true))
    val dynamicColors: StateFlow<Boolean> = _dynamicColors.asStateFlow()

    fun setDynamicColors(on: Boolean) {
        _dynamicColors.value = on
        prefs.edit().putBoolean("dynamic_colors", on).apply()
    }

    private val _amoledBlack = MutableStateFlow(prefs.getBoolean("amoled_black", false))
    val amoledBlack: StateFlow<Boolean> = _amoledBlack.asStateFlow()

    fun setAmoledBlack(on: Boolean) {
        _amoledBlack.value = on
        prefs.edit().putBoolean("amoled_black", on).apply()
    }

    // Root Back behaviour: ON shows the exit dialog at the home screen; OFF consumes
    // Back silently so the app is only left via the Home gesture. Default ON keeps
    // the pre-setting behaviour.
    private val _confirmExitOnBack = MutableStateFlow(prefs.getBoolean("confirm_exit_on_back", true))
    val confirmExitOnBack: StateFlow<Boolean> = _confirmExitOnBack.asStateFlow()

    fun setConfirmExitOnBack(on: Boolean) {
        _confirmExitOnBack.value = on
        prefs.edit().putBoolean("confirm_exit_on_back", on).apply()
    }

    private val _useGlassmorphism = MutableStateFlow(prefs.getBoolean("ui_glassmorphism", false))
    val useGlassmorphism: StateFlow<Boolean> = _useGlassmorphism.asStateFlow()

    fun setUseGlassmorphism(on: Boolean) {
        _useGlassmorphism.value = on
        prefs.edit().putBoolean("ui_glassmorphism", on).apply()
    }

    // 0 = kept-alive services run until the user closes them.
    private val _keepAliveTimeoutMinutes = MutableStateFlow(KeepAliveTimeout.read(application))
    val keepAliveTimeoutMinutes: StateFlow<Int> = _keepAliveTimeoutMinutes.asStateFlow()

    fun setKeepAliveTimeoutMinutes(minutes: Int) {
        val v = KeepAliveTimeout.normalize(minutes)
        _keepAliveTimeoutMinutes.value = v
        KeepAliveTimeout.write(getApplication(), v)
    }

    private val _fontScale = MutableStateFlow(prefs.getInt("font_scale", 100))
    val fontScale: StateFlow<Int> = _fontScale.asStateFlow()

    fun setFontScale(percent: Int) {
        val v = percent.coerceIn(80, 130)
        _fontScale.value = v
        prefs.edit().putInt("font_scale", v).apply()
    }

    // Last page state (restored on next app launch)
    private val _lastAppId = MutableStateFlow(prefs.getString("last_app_id", null))
    val lastAppId: StateFlow<String?> = _lastAppId.asStateFlow()

    private val _lastVideoFile = MutableStateFlow(prefs.getString("last_video_file", null))
    val lastVideoFile: StateFlow<String?> = _lastVideoFile.asStateFlow()

    private val _lastVideoFolderPath = MutableStateFlow(prefs.getString("last_video_folder", null))
    val lastVideoFolderPath: StateFlow<String?> = _lastVideoFolderPath.asStateFlow()

    private val _lastImageFile = MutableStateFlow(prefs.getString("last_image_file", null))
    val lastImageFile: StateFlow<String?> = _lastImageFile.asStateFlow()

    private val _lastDocFile = MutableStateFlow(prefs.getString("last_doc_file", null))
    val lastDocFile: StateFlow<String?> = _lastDocFile.asStateFlow()

    private val _lastAudioFile = MutableStateFlow(prefs.getString("last_audio_file", null))
    val lastAudioFile: StateFlow<String?> = _lastAudioFile.asStateFlow()

    // New main-screen model: 0 = launcher Home, 1 = Phone. On the first run under
    // this model, migrate the old pager's last_tab (0=Phone, 1/2/3=hubs): the old
    // Phone tab stays Phone, and the hub tabs now collapse into the Home grid.
    // A fresh install (no prefs at all) lands on Home.
    private val _lastScreen = MutableStateFlow(
        when {
            prefs.contains("last_screen") -> prefs.getInt("last_screen", 0)
            prefs.contains("last_tab") -> if (prefs.getInt("last_tab", 0) == 0) 1 else 0
            else -> 0
        }
    )
    val lastScreen: StateFlow<Int> = _lastScreen.asStateFlow()

    fun setLastScreen(screen: Int) {
        val v = screen.coerceIn(0, 1)
        if (_lastScreen.value == v) return
        _lastScreen.value = v
        prefs.edit().putInt("last_screen", v).apply()
    }

    private val _lastPhoneTab = MutableStateFlow(prefs.getInt("last_phone_tab", 0))
    val lastPhoneTab: StateFlow<Int> = _lastPhoneTab.asStateFlow()

    fun setLastPhoneTab(tab: Int) {
        val v = tab.coerceIn(0, 2)
        if (_lastPhoneTab.value == v) return
        _lastPhoneTab.value = v
        prefs.edit().putInt("last_phone_tab", v).apply()
    }

    // --- Launcher preferences ---
    // -1 = automatic (Adaptive(72.dp)), 4 or 5 = fixed columns.
    private val _launcherColumns = MutableStateFlow(
        prefs.getInt("launcher_columns", 4).let { if (it == -1 || it in 4..6) it else 4 }
    )
    val launcherColumns: StateFlow<Int> = _launcherColumns.asStateFlow()

    fun setLauncherColumns(columns: Int) {
        val clamped = when {
            columns == -1 -> -1
            columns == 6 -> 6
            columns == 5 -> 5
            columns == 4 -> 4
            else -> if (columns > 4) columns.coerceIn(4, 6) else 4
        }
        if (_launcherColumns.value == clamped) return
        _launcherColumns.value = clamped
        prefs.edit().putInt("launcher_columns", clamped).apply()
    }

    private val _launcherShowClock = MutableStateFlow(prefs.getBoolean("launcher_show_clock", true))
    val launcherShowClock: StateFlow<Boolean> = _launcherShowClock.asStateFlow()

    fun setLauncherShowClock(show: Boolean) {
        if (_launcherShowClock.value == show) return
        _launcherShowClock.value = show
        prefs.edit().putBoolean("launcher_show_clock", show).apply()
    }

    private val _pinnedAppIds = MutableStateFlow(
        prefs.getString("launcher_pinned", "")
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            ?: emptyList()
    )
    val pinnedAppIds: StateFlow<List<String>> = _pinnedAppIds.asStateFlow()

    fun togglePinnedApp(id: String) {
        val current = _pinnedAppIds.value
        val next = if (current.contains(id)) current - id else current + id
        _pinnedAppIds.value = next
        prefs.edit().putString("launcher_pinned", next.joinToString(",")).apply()
    }

    // Recently launched app ids, most-recent first, used by the launcher's "Recent"
    // row. Order-preserving CSV; unknown ids are ignored when rendering.
    private val _recentAppIds = MutableStateFlow(
        prefs.getString("launcher_recent", "")
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            ?.take(RECENT_APPS_MAX)
            ?: emptyList()
    )
    val recentAppIds: StateFlow<List<String>> = _recentAppIds.asStateFlow()

    fun recordRecentApp(id: String) {
        if (id.isBlank()) return
        val next = (listOf(id) + _recentAppIds.value.filter { it != id }).take(RECENT_APPS_MAX)
        if (next == _recentAppIds.value) return
        _recentAppIds.value = next
        prefs.edit().putString("launcher_recent", next.joinToString(",")).apply()
    }

    // Tile scale: 0 = compact, 1 = default, 2 = large. Scales the tile swatch.
    private val _launcherTileSize = MutableStateFlow(prefs.getInt("launcher_tile_size", 1).coerceIn(0, 2))
    val launcherTileSize: StateFlow<Int> = _launcherTileSize.asStateFlow()

    fun setLauncherTileSize(size: Int) {
        val v = size.coerceIn(0, 2)
        if (_launcherTileSize.value == v) return
        _launcherTileSize.value = v
        prefs.edit().putInt("launcher_tile_size", v).apply()
    }

    // Launcher background image: absolute path to a copy inside filesDir, or null
    // for the plain themed background.
    private val _launcherWallpaper = MutableStateFlow(
        prefs.getString("launcher_wallpaper", null)?.takeIf { it.isNotBlank() }
    )
    val launcherWallpaper: StateFlow<String?> = _launcherWallpaper.asStateFlow()

    fun setLauncherWallpaper(path: String?) {
        val v = path?.takeIf { it.isNotBlank() }
        if (_launcherWallpaper.value == v) return
        _launcherWallpaper.value = v
        prefs.edit().putString("launcher_wallpaper", v).apply()
    }

    // Slideshow backgrounds: mode 0 uses the single image above, mode 1 rotates
    // the ordered paths below. The files live in filesDir/launcher_slideshow.
    private val _launcherWallpaperMode = MutableStateFlow(
        prefs.getInt("launcher_wallpaper_mode", 0).coerceIn(0, 1)
    )
    val launcherWallpaperMode: StateFlow<Int> = _launcherWallpaperMode.asStateFlow()

    fun setLauncherWallpaperMode(mode: Int) {
        val v = if (mode == 1) 1 else 0
        if (_launcherWallpaperMode.value == v) return
        _launcherWallpaperMode.value = v
        prefs.edit().putInt("launcher_wallpaper_mode", v).apply()
    }

    private val _launcherWallpaperPaths = MutableStateFlow(
        parseWallpaperPaths(prefs.getString("launcher_wallpaper_paths", null))
    )
    val launcherWallpaperPaths: StateFlow<List<String>> = _launcherWallpaperPaths.asStateFlow()

    fun setLauncherWallpaperPaths(paths: List<String>) {
        val v = paths.filter { it.isNotBlank() }.distinct()
        if (_launcherWallpaperPaths.value == v) return
        _launcherWallpaperPaths.value = v
        prefs.edit().putString("launcher_wallpaper_paths", JSONArray(v).toString()).apply()
    }

    private val _launcherSlideshowIntervalSec = MutableStateFlow(
        prefs.getInt("launcher_slideshow_interval", 60).coerceAtLeast(15)
    )
    val launcherSlideshowIntervalSec: StateFlow<Int> = _launcherSlideshowIntervalSec.asStateFlow()

    fun setLauncherSlideshowIntervalSec(seconds: Int) {
        val v = seconds.coerceAtLeast(15)
        if (_launcherSlideshowIntervalSec.value == v) return
        _launcherSlideshowIntervalSec.value = v
        prefs.edit().putInt("launcher_slideshow_interval", v).apply()
    }

    private val _launcherSlideshowShuffle = MutableStateFlow(
        prefs.getBoolean("launcher_slideshow_shuffle", false)
    )
    val launcherSlideshowShuffle: StateFlow<Boolean> = _launcherSlideshowShuffle.asStateFlow()

    fun setLauncherSlideshowShuffle(enabled: Boolean) {
        if (_launcherSlideshowShuffle.value == enabled) return
        _launcherSlideshowShuffle.value = enabled
        prefs.edit().putBoolean("launcher_slideshow_shuffle", enabled).apply()
    }

    private fun parseWallpaperPaths(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val path = array.optString(i)
                    if (path.isNotBlank()) add(path)
                }
            }
        }.getOrDefault(emptyList())
    }

    // Wallpaper opacity, 0.1 (barely visible) .. 1f (fully opaque image). Lower
    // values let the themed background show through behind the image.
    private val _launcherBackgroundOpacity = MutableStateFlow(
        prefs.getFloat("launcher_bg_opacity", 1f).coerceIn(0.1f, 1f)
    )
    val launcherBackgroundOpacity: StateFlow<Float> = _launcherBackgroundOpacity.asStateFlow()

    fun setLauncherBackgroundOpacity(value: Float) {
        val v = value.coerceIn(0.1f, 1f)
        if (_launcherBackgroundOpacity.value == v) return
        _launcherBackgroundOpacity.value = v
        prefs.edit().putFloat("launcher_bg_opacity", v).apply()
    }

    // Whether the launcher's swipe-right Tools/Dashboard page is present. Default true for backward compat but now combined into Dashboard; kept for migration.
    private val _launcherShowSystemPage =
        MutableStateFlow(prefs.getBoolean("launcher_show_system_page", true))
    val launcherShowSystemPage: StateFlow<Boolean> = _launcherShowSystemPage.asStateFlow()

    fun setLauncherShowSystemPage(show: Boolean) {
        if (_launcherShowSystemPage.value == show) return
        _launcherShowSystemPage.value = show
        prefs.edit().putBoolean("launcher_show_system_page", show).apply()
    }

    // Swipe page: Dashboard (right of Home by default).
    private val _launcherShowDashboardPage =
        MutableStateFlow(prefs.getBoolean("launcher_show_dashboard_page", false))
    val launcherShowDashboardPage: StateFlow<Boolean> = _launcherShowDashboardPage.asStateFlow()

    fun setLauncherShowDashboardPage(show: Boolean) {
        if (_launcherShowDashboardPage.value == show) return
        _launcherShowDashboardPage.value = show
        prefs.edit().putBoolean("launcher_show_dashboard_page", show).apply()
    }

    // 0 = grid (the original launcher), 1 = one full-width row per app.
    private val _launcherLayout =
        MutableStateFlow(prefs.getInt("launcher_layout", LAUNCHER_LAYOUT_GRID).coerceIn(0, 1))
    val launcherLayout: StateFlow<Int> = _launcherLayout.asStateFlow()

    fun setLauncherLayout(layout: Int) {
        val v = layout.coerceIn(0, 1)
        if (_launcherLayout.value == v) return
        _launcherLayout.value = v
        prefs.edit().putInt("launcher_layout", v).apply()
    }

    // Whether the Home page's "Recent" shelf is shown. Default true.
    private val _launcherShowRecent = MutableStateFlow(prefs.getBoolean("launcher_show_recent", true))
    val launcherShowRecent: StateFlow<Boolean> = _launcherShowRecent.asStateFlow()

    fun setLauncherShowRecent(show: Boolean) {
        if (_launcherShowRecent.value == show) return
        _launcherShowRecent.value = show
        prefs.edit().putBoolean("launcher_show_recent", show).apply()
    }

    // Whether the Comms/Apps/Tools/Web apps/System headers are drawn on the launcher.
    private val _launcherShowSections =
        MutableStateFlow(prefs.getBoolean("launcher_show_sections", true))
    val launcherShowSections: StateFlow<Boolean> = _launcherShowSections.asStateFlow()

    fun setLauncherShowSections(show: Boolean) {
        if (_launcherShowSections.value == show) return
        _launcherShowSections.value = show
        prefs.edit().putBoolean("launcher_show_sections", show).apply()
    }

    // Whether grid tiles show their app name. List rows always show names.
    private val _launcherShowLabels = MutableStateFlow(prefs.getBoolean("launcher_show_labels", true))
    val launcherShowLabels: StateFlow<Boolean> = _launcherShowLabels.asStateFlow()

    fun setLauncherShowLabels(show: Boolean) {
        if (_launcherShowLabels.value == show) return
        _launcherShowLabels.value = show
        prefs.edit().putBoolean("launcher_show_labels", show).apply()
    }

    private val _launcherIconShape = MutableStateFlow(prefs.getInt("launcher_icon_shape", 0).coerceIn(0, 3))
    val launcherIconShape: StateFlow<Int> = _launcherIconShape.asStateFlow()

    fun setLauncherIconShape(shape: Int) {
        val s = shape.coerceIn(0, 3)
        if (_launcherIconShape.value == s) return
        _launcherIconShape.value = s
        prefs.edit().putInt("launcher_icon_shape", s).apply()
    }

    private val _launcherSearchAll = MutableStateFlow(prefs.getBoolean("launcher_search_all", true))
    val launcherSearchAll: StateFlow<Boolean> = _launcherSearchAll.asStateFlow()

    fun setLauncherSearchAll(enabled: Boolean) {
        if (_launcherSearchAll.value == enabled) return
        _launcherSearchAll.value = enabled
        prefs.edit().putBoolean("launcher_search_all", enabled).apply()
    }

    private val _launcherScrim = MutableStateFlow(
        prefs.getFloat("launcher_scrim", LAUNCHER_DEFAULT_SCRIM).coerceIn(0f, 0.85f)
    )
    val launcherScrim: StateFlow<Float> = _launcherScrim.asStateFlow()

    fun setLauncherScrim(value: Float) {
        val v = value.coerceIn(0f, 0.85f)
        if (_launcherScrim.value == v) return
        _launcherScrim.value = v
        prefs.edit().putFloat("launcher_scrim", v).apply()
    }

    // 0 = follow the system 12/24-hour setting, 1 = force 12-hour, 2 = force 24-hour.
    private val _launcherClockFormat = MutableStateFlow(
        prefs.getInt("launcher_clock_format", LAUNCHER_CLOCK_SYSTEM).coerceIn(0, 2)
    )
    val launcherClockFormat: StateFlow<Int> = _launcherClockFormat.asStateFlow()

    fun setLauncherClockFormat(format: Int) {
        val v = format.coerceIn(0, 2)
        if (_launcherClockFormat.value == v) return
        _launcherClockFormat.value = v
        prefs.edit().putInt("launcher_clock_format", v).apply()
    }

    // Launcher glance: a compact Now Playing card in the Home header.
    private val _launcherShowNowPlaying =
        MutableStateFlow(prefs.getBoolean("launcher_show_now_playing", true))
    val launcherShowNowPlaying: StateFlow<Boolean> = _launcherShowNowPlaying.asStateFlow()

    fun setLauncherShowNowPlaying(show: Boolean) {
        if (_launcherShowNowPlaying.value == show) return
        _launcherShowNowPlaying.value = show
        prefs.edit().putBoolean("launcher_show_now_playing", show).apply()
    }

    // Launcher badges: counts from the notification listener for installed app results.
    private val _launcherShowNotificationBadges =
        MutableStateFlow(prefs.getBoolean("launcher_show_notification_badges", true))
    val launcherShowNotificationBadges: StateFlow<Boolean> = _launcherShowNotificationBadges.asStateFlow()

    fun setLauncherShowNotificationBadges(show: Boolean) {
        if (_launcherShowNotificationBadges.value == show) return
        _launcherShowNotificationBadges.value = show
        prefs.edit().putBoolean("launcher_show_notification_badges", show).apply()
    }

    // Launcher gesture: double-tap the Home clock to lock the screen. Requires the
    // device-admin force-lock policy, which is only ever enabled from this gesture.
    private val _launcherDoubleTapLock =
        MutableStateFlow(prefs.getBoolean("launcher_double_tap_lock", false))
    val launcherDoubleTapLock: StateFlow<Boolean> = _launcherDoubleTapLock.asStateFlow()

    fun setLauncherDoubleTapLock(enabled: Boolean) {
        if (_launcherDoubleTapLock.value == enabled) return
        _launcherDoubleTapLock.value = enabled
        prefs.edit().putBoolean("launcher_double_tap_lock", enabled).apply()
    }

    private fun sanitizeLauncherQsTiles(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return LAUNCHER_QS_DEFAULT_TILES
        val ids = raw.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it in LAUNCHER_QS_KNOWN_TILES }
        return ids.distinct()
            .take(LAUNCHER_QS_MAX_TILES)
            .ifEmpty { LAUNCHER_QS_DEFAULT_TILES }
    }

    private val _launcherQuickShadeEnabled =
        MutableStateFlow(prefs.getBoolean("launcher_quick_shade", true))
    val launcherQuickShadeEnabled: StateFlow<Boolean> = _launcherQuickShadeEnabled.asStateFlow()

    fun setLauncherQuickShadeEnabled(enabled: Boolean) {
        if (_launcherQuickShadeEnabled.value == enabled) return
        _launcherQuickShadeEnabled.value = enabled
        prefs.edit().putBoolean("launcher_quick_shade", enabled).apply()
    }

    private val _launcherQsTiles =
        MutableStateFlow(sanitizeLauncherQsTiles(prefs.getString("launcher_qs_tiles", null)))
    val launcherQsTiles: StateFlow<List<String>> = _launcherQsTiles.asStateFlow()

    fun setLauncherQsTiles(ids: List<String>) {
        val clean = ids.distinct()
            .filter { it in LAUNCHER_QS_KNOWN_TILES }
            .take(LAUNCHER_QS_MAX_TILES)
            .ifEmpty { LAUNCHER_QS_DEFAULT_TILES }
        if (_launcherQsTiles.value == clean) return
        _launcherQsTiles.value = clean
        prefs.edit().putString("launcher_qs_tiles", clean.joinToString(",")).apply()
    }

    private val _confirmBeforeCall = MutableStateFlow(prefs.getBoolean("confirm_before_call", false))
    val confirmBeforeCall: StateFlow<Boolean> = _confirmBeforeCall.asStateFlow()
    fun setConfirmBeforeCall(on: Boolean) {
        if (_confirmBeforeCall.value == on) return
        _confirmBeforeCall.value = on
        prefs.edit().putBoolean("confirm_before_call", on).apply()
    }

    private val _reduceMotion = MutableStateFlow(prefs.getBoolean("reduce_motion", false))
    val reduceMotion: StateFlow<Boolean> = _reduceMotion.asStateFlow()
    fun setReduceMotion(on: Boolean) { _reduceMotion.value = on; prefs.edit().putBoolean("reduce_motion", on).apply() }

    private val _largerTouchTargets = MutableStateFlow(prefs.getBoolean("larger_touch_targets", false))
    val largerTouchTargets: StateFlow<Boolean> = _largerTouchTargets.asStateFlow()
    fun setLargerTouchTargets(on: Boolean) { _largerTouchTargets.value = on; prefs.edit().putBoolean("larger_touch_targets", on).apply() }

    // Reset the launcher back to its shipped defaults (layout, columns, clock, pinned, tile size).
    fun resetLauncherDefaults() {
        _launcherLayout.value = LAUNCHER_LAYOUT_GRID
        _launcherColumns.value = 4
        _launcherShowClock.value = true
        _launcherClockFormat.value = LAUNCHER_CLOCK_SYSTEM
        _launcherTileSize.value = 1
        _launcherShowSections.value = true
        _launcherShowRecent.value = true
        _launcherShowLabels.value = true
        _launcherScrim.value = LAUNCHER_DEFAULT_SCRIM
        _pinnedAppIds.value = emptyList()
        _recentAppIds.value = emptyList()
        _launcherWallpaper.value = null
        _launcherWallpaperMode.value = 0
        _launcherWallpaperPaths.value = emptyList()
        _launcherSlideshowIntervalSec.value = 60
        _launcherSlideshowShuffle.value = false
        _launcherBackgroundOpacity.value = 1f
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { LauncherWallpapers.clear(getApplication<Application>()) }
        }
        _launcherShowSystemPage.value = true
        _launcherShowDashboardPage.value = false
        _launcherShowNowPlaying.value = true
        _launcherShowNotificationBadges.value = true
        _launcherDoubleTapLock.value = false
        _launcherQuickShadeEnabled.value = true
        _launcherQsTiles.value = LAUNCHER_QS_DEFAULT_TILES
        prefs.edit()
            .putInt("launcher_layout", LAUNCHER_LAYOUT_GRID)
            .putInt("launcher_columns", 4)
            .putBoolean("launcher_show_clock", true)
            .putInt("launcher_clock_format", LAUNCHER_CLOCK_SYSTEM)
            .putInt("launcher_tile_size", 1)
            .putBoolean("launcher_show_sections", true)
            .putBoolean("launcher_show_recent", true)
            .putBoolean("launcher_show_labels", true)
            .remove("launcher_scrim")
            .putString("launcher_pinned", "")
            .putString("launcher_recent", "")
            .remove("launcher_wallpaper")
            .remove("launcher_wallpaper_mode")
            .remove("launcher_wallpaper_paths")
            .remove("launcher_slideshow_interval")
            .remove("launcher_slideshow_shuffle")
            .remove("launcher_bg_opacity")
            .putBoolean("launcher_show_system_page", true)
            .putBoolean("launcher_show_dashboard_page", false)
            .putBoolean("launcher_show_now_playing", true)
            .putBoolean("launcher_show_notification_badges", true)
            .putBoolean("launcher_double_tap_lock", false)
            .putBoolean("launcher_quick_shade", true)
            .putString("launcher_qs_tiles", LAUNCHER_QS_DEFAULT_TILES.joinToString(","))
            .remove("launcher_hidden_ids")
            .apply()
    }

    // --- Unread inbox SMS count for the launcher dock badge ---
    private val _smsUnreadCount = MutableStateFlow(0)
    val smsUnreadCount: StateFlow<Int> = _smsUnreadCount.asStateFlow()

    private var smsObserver: ContentObserver? = null
    private var smsRefreshJob: Job? = null

    // --- Per-package notification counts and in-app shade snapshots ---
    private val _notificationCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val notificationCounts: StateFlow<Map<String, Int>> = _notificationCounts.asStateFlow()

    private val _launcherNotifications =
        MutableStateFlow<List<com.example.service.LauncherNotification>>(emptyList())
    val launcherNotifications: StateFlow<List<com.example.service.LauncherNotification>> =
        _launcherNotifications.asStateFlow()

    private var notificationReceiver: BroadcastReceiver? = null

    private fun ensureNotificationObserver() {
        if (notificationReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                _notificationCounts.value = SmsNotificationListener.snapshotCounts()
                _launcherNotifications.value = SmsNotificationListener.snapshotNotifications()
            }
        }
        notificationReceiver = receiver
        runCatching {
            ContextCompat.registerReceiver(
                getApplication(),
                receiver,
                IntentFilter(SmsNotificationListener.ACTION_NOTIFICATIONS_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
    }

    fun refreshLauncherNotifications() {
        _notificationCounts.value = SmsNotificationListener.snapshotCounts()
        _launcherNotifications.value = SmsNotificationListener.snapshotNotifications()
    }

    fun dismissLauncherNotification(key: String) {
        SmsNotificationListener.dismiss(key)
        refreshLauncherNotifications()
    }

    fun actionLauncherNotification(key: String, index: Int, input: String?): Boolean {
        val sent = SmsNotificationListener.performAction(key, index, input)
        if (sent) refreshLauncherNotifications()
        return sent
    }

    init {
        ensureSmsObserver()
        refreshSmsUnread()
        _notificationCounts.value = SmsNotificationListener.snapshotCounts()
        _launcherNotifications.value = SmsNotificationListener.snapshotNotifications()
        ensureNotificationObserver()
        // Seed the slideshow list from a pre-slideshow single wallpaper so the
        // user can switch modes without re-picking their image.
        if (_launcherWallpaperPaths.value.isEmpty()) {
            _launcherWallpaper.value?.let { setLauncherWallpaperPaths(listOf(it)) }
        }
    }

    fun ensureSmsObserver() {
        if (smsObserver != null) return
        if (ContextCompat.checkSelfPermission(
                getApplication(), Manifest.permission.READ_SMS
            ) != PackageManager.PERMISSION_GRANTED
        ) return
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                smsRefreshJob?.cancel()
                smsRefreshJob = viewModelScope.launch {
                    delay(300)
                    refreshSmsUnread()
                }
            }
        }
        runCatching {
            getApplication<Application>().contentResolver.registerContentObserver(
                Uri.parse("content://sms"), true, observer
            )
            smsObserver = observer
        }
    }

    fun refreshSmsUnread() {
        ensureSmsObserver()
        if (ContextCompat.checkSelfPermission(
                getApplication(), Manifest.permission.READ_SMS
            ) != PackageManager.PERMISSION_GRANTED
        ) return
        viewModelScope.launch(Dispatchers.IO) {
            val count = runCatching {
                getApplication<Application>().contentResolver.query(
                    Uri.parse("content://sms"),
                    arrayOf("_id"),
                    "read = 0 AND type = 1",
                    null,
                    null
                )?.use { it.count } ?: 0
            }.getOrDefault(_smsUnreadCount.value)
            _smsUnreadCount.value = count
        }
    }

    override fun onCleared() {
        smsObserver?.let {
            runCatching {
                getApplication<Application>().contentResolver.unregisterContentObserver(it)
            }
        }
        smsObserver = null
        smsRefreshJob?.cancel()
        contactsObserver?.let {
            runCatching {
                getApplication<Application>().contentResolver.unregisterContentObserver(it)
            }
        }
        contactsObserver = null
        contactsObserverHandler.removeCallbacks(contactsObserverRefresh)
        contactsRefreshJob?.cancel()
        callLogSyncJob?.cancel()
        notificationReceiver?.let {
            runCatching { getApplication<Application>().unregisterReceiver(it) }
        }
        notificationReceiver = null
        super.onCleared()
    }

    fun setLastAppId(appId: String?) {
        if (_lastAppId.value == appId) return
        _lastAppId.value = appId
        prefs.edit().putString("last_app_id", appId).apply()
    }

    fun setLastVideoFile(path: String?) {
        if (_lastVideoFile.value == path) return
        _lastVideoFile.value = path
        prefs.edit().putString("last_video_file", path).apply()
    }

    fun setLastVideoFolderPath(path: String?) {
        if (_lastVideoFolderPath.value == path) return
        _lastVideoFolderPath.value = path
        prefs.edit().putString("last_video_folder", path).apply()
    }

    fun setLastImageFile(path: String?) {
        if (_lastImageFile.value == path) return
        _lastImageFile.value = path
        prefs.edit().putString("last_image_file", path).apply()
    }

    fun setLastDocFile(path: String?) {
        if (_lastDocFile.value == path) return
        _lastDocFile.value = path
        prefs.edit().putString("last_doc_file", path).apply()
    }

    fun setLastAudioFile(path: String?) {
        if (_lastAudioFile.value == path) return
        _lastAudioFile.value = path
        prefs.edit().putString("last_audio_file", path).apply()
    }

    // Scroll position persistence so screens reopen where the user left off.
    fun getScrollPosition(key: String): Int = prefs.getInt("scroll_$key", 0)

    fun setScrollPosition(key: String, index: Int) {
        if (index <= 0) return
        prefs.edit().putInt("scroll_$key", index).apply()
        if (key.startsWith(SCROLL_KEY_SCOPED_PREFIX)) touchScopedScrollKey(key)
    }
    // ---- Folder stats cache (File Manager "12 items • 3.4 MB" labels) ----
    // Recursive folder walks are expensive, so each result is memoised per folder
    // and stamped with that folder's mtime. A directory's mtime changes when its
    // DIRECT children are added, removed or renamed, so a download landing in the
    // folder invalidates it correctly. Editing a file deeper down does NOT bubble
    // up, so a nested change can leave a total briefly stale until the folder's
    // own mtime moves; that is an acceptable trade for a display label, and the
    // alternative (re-walking on every resume) is not affordable on this hardware.
    // The map is capped like the scroll keys above — unbounded growth on a phone
    // that browses / is the failure mode that matters.
    private data class StatsEntry(val mtime: Long, val stats: FolderStats)

    private val _folderStats = MutableStateFlow<Map<String, StatsEntry>>(emptyMap())
    val folderStats: StateFlow<Map<String, FolderStats>> =
        _folderStats.map { m -> m.mapValues { it.value.stats } }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                emptyMap()
            )

    private var folderStatsJob: Job? = null

    /**
     * Walk the child folders of the directory just listed and publish their totals.
     *
     * One batch at a time: a new listing cancels the previous walk, so scrolling
     * through a deep tree doesn't leave a queue of stale recursions competing for
     * the same low-RAM device. Folders already cached at their current mtime are
     * skipped, so re-entering a folder you have already measured is instant.
     */
    fun requestFolderStats(items: List<MediaItem>) {
        folderStatsJob?.cancel()
        val folders = items.filter { it.isDirectory }
        if (folders.isEmpty()) return
        folderStatsJob = viewModelScope.launch(Dispatchers.IO) {
            val ctx = currentCoroutineContext()
            for (item in folders) {
                if (!ctx.isActive) return@launch
                if (folderStatsFor(item) != null) continue
                val stats = StorageHelper.folderStats(item.file) { !ctx.isActive }
                if (!ctx.isActive || stats.cancelled) return@launch
                publishFolderStats(item.path, item.lastModified, stats)
            }
        }
    }

    /** Cached stats for [path] if it is still valid, else null. */
    fun folderStatsFor(item: MediaItem): FolderStats? {
        val hit = _folderStats.value[item.path] ?: return null
        return if (hit.mtime == item.lastModified) hit.stats else null
    }

    /** Publish one completed walk, evicting the least-recently-used over the cap. */
    private fun publishFolderStats(path: String, mtime: Long, stats: FolderStats) {
        val next = LinkedHashMap<String, StatsEntry>(_folderStats.value)
        next.remove(path)
        next[path] = StatsEntry(mtime, stats)
        while (next.size > FOLDER_STATS_MAX) {
            val oldest = next.keys.firstOrNull() ?: break
            next.remove(oldest)
        }
        _folderStats.value = next
    }

    // Per-folder scroll keys (the File Manager) would otherwise write one
    // SharedPreferences entry per directory ever visited, which never shrinks.
    // Track them in a comma-separated LRU list and drop the oldest beyond the cap.
    private fun scopedScrollKeys(): List<String> =
        prefs.getString(SCROLL_KEY_INDEX, null).orEmpty()
            .split(',')
            .filter { it.isNotBlank() }

    private fun touchScopedScrollKey(key: String) {
        val keys = scopedScrollKeys().toMutableList()
        keys.remove(key)
        keys.add(key)
        val kept = keys.takeLast(SCROLL_KEY_SCOPED_MAX)
        val dropped = keys.dropLast(SCROLL_KEY_SCOPED_MAX)
        if (dropped.isEmpty() && kept.size == keys.size) return
        prefs.edit().apply {
            putString(SCROLL_KEY_INDEX, kept.joinToString(","))
            dropped.forEach { remove("scroll_$it") }
        }.apply()
    }

    // Call & Security Preferences
    private val _vibrateOnConnect = MutableStateFlow(prefs.getBoolean("vibrate_on_connect", true))
    val vibrateOnConnect: StateFlow<Boolean> = _vibrateOnConnect.asStateFlow()

    private val _flashOnCall = MutableStateFlow(prefs.getBoolean("flash_on_call", false))
    val flashOnCall: StateFlow<Boolean> = _flashOnCall.asStateFlow()

    private val _blockUnknownCallers = MutableStateFlow(prefs.getBoolean("block_unknown_callers", false))
    val blockUnknownCallers: StateFlow<Boolean> = _blockUnknownCallers.asStateFlow()

    fun setVibrateOnConnect(on: Boolean) {
        _vibrateOnConnect.value = on
        prefs.edit().putBoolean("vibrate_on_connect", on).apply()
    }

    fun setFlashOnCall(on: Boolean) {
        _flashOnCall.value = on
        prefs.edit().putBoolean("flash_on_call", on).apply()
    }

    fun setBlockUnknownCallers(on: Boolean) {
        _blockUnknownCallers.value = on
        prefs.edit().putBoolean("block_unknown_callers", on).apply()
    }

    // SIM slot shared by the keypad and in-call UI
    private val _selectedSimSlot = MutableStateFlow(1) // 1 or 2
    val selectedSimSlot: StateFlow<Int> = _selectedSimSlot.asStateFlow()

    fun setSelectedSimSlot(slot: Int) {
        _selectedSimSlot.value = slot
    }

    // Contacts
    val allContacts: StateFlow<List<ContactEntity>> = repository.allContacts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val publicContacts: StateFlow<List<ContactEntity>> = repository.publicContacts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val privateContacts: StateFlow<List<ContactEntity>> = repository.privateContacts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val favoriteContacts: StateFlow<List<ContactEntity>> = repository.favoriteContacts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Device (system) contacts, read-only. Uses synthetic negative ids to avoid
    // clashing with Room auto-generated ids.
    private val _deviceContacts = MutableStateFlow<List<ContactEntity>>(emptyList())
    val deviceContacts: StateFlow<List<ContactEntity>> = _deviceContacts.asStateFlow()

    private var contactsRefreshJob: Job? = null
    private var contactsLastRefreshMs = 0L
    private val contactsRefreshMaxAgeMs = 10L * 60 * 1000
    private var contactsObserver: ContentObserver? = null
    private val contactsObserverHandler by lazy { Handler(Looper.getMainLooper()) }
    private val contactsObserverRefresh = Runnable { refreshDeviceContacts(force = true) }

    fun refreshDeviceContacts(force: Boolean = false) {
        val context = getApplication<Application>()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        ensureContactsObserver()
        val now = System.currentTimeMillis()
        if (!force && contactsLastRefreshMs > 0 && now - contactsLastRefreshMs < contactsRefreshMaxAgeMs) {
            return
        }
        if (contactsRefreshJob?.isActive == true) return
        contactsRefreshJob = viewModelScope.launch(Dispatchers.IO) {
            // Grouped per person, not per number: `Phone.CONTENT_URI` yields one row
            // per phone number, so a contact with four numbers used to become four
            // separate rows in the Contacts list. All of a person's numbers are now
            // folded into one entity using the same comma-joined convention
            // `ContactTools.mergeGroup` already writes, so no Room migration is
            // needed. Every consumer goes through `ContactNumbers` to read the
            // field back apart into individual numbers.
            val grouped = runCatching { DeviceContacts.queryGrouped(context) }
                .getOrNull() ?: return@launch
            val entities = grouped.mapIndexed { idx, contact ->
                val seed = contact.lookupKey.ifBlank { contact.name }
                ContactEntity(
                    id = -((seed.hashCode().toLong() and 0x7FFFFFFFL) + 1L) - idx * 1000000L,
                    name = contact.name,
                    phoneNumber = contact.numbers.joinToString(", ")
                )
            }
            _deviceContacts.value = entities
            contactsLastRefreshMs = System.currentTimeMillis()
        }
    }

    private fun ensureContactsObserver() {
        if (contactsObserver != null) return
        if (ContextCompat.checkSelfPermission(
                getApplication(), Manifest.permission.READ_CONTACTS
            ) != PackageManager.PERMISSION_GRANTED
        ) return
        val observer = object : ContentObserver(contactsObserverHandler) {
            override fun onChange(selfChange: Boolean) {
                contactsObserverHandler.removeCallbacks(contactsObserverRefresh)
                contactsObserverHandler.postDelayed(contactsObserverRefresh, 300)
            }
        }
        runCatching {
            getApplication<Application>().contentResolver.registerContentObserver(
                ContactsContract.Contacts.CONTENT_URI, true, observer
            )
            contactsObserver = observer
        }
    }

    val combinedContacts: StateFlow<List<ContactEntity>> = combine(allContacts, deviceContacts) { local, device ->
        val localNumbers = local.flatMap { ContactNumbers.keys(it.phoneNumber) }.toSet()
        val merged = local.toMutableList()
        for (dc in device) {
            val keys = ContactNumbers.keys(dc.phoneNumber)
            // A device contact is dropped entirely when ANY of its numbers is
            // already saved locally — same rule as before, but it now has to
            // consider every number the person has, not just one.
            if (keys.isNotEmpty() && keys.none { it in localNumbers }) {
                merged.add(dc)
            }
        }
        merged.sortedWith(compareByDescending<ContactEntity> { it.isFavorite }.thenBy { it.name.lowercase() })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun saveContact(contact: ContactEntity) = viewModelScope.launch {
        if (contact.id == 0L) {
            repository.insertContact(contact)
        } else {
            repository.updateContact(contact)
        }
    }

    fun deleteContact(contact: ContactEntity) = viewModelScope.launch {
        repository.deleteContact(contact)
    }

    // Resolves a phone number to a contact name from the merged (local + device)
    // address book, so calls show a real name instead of the bare number.
    fun resolveCallerName(number: String): String? {
        val normalized = ContactNumbers.normalize(number) ?: return null
        val pool = allContacts.value + _deviceContacts.value
        // Compare per number: a contact's field holds every number the person has,
        // so filtering the whole joined string to digits and taking the last 10
        // would only ever match whichever number happens to be last.
        return pool.firstOrNull { contact ->
            ContactNumbers.keys(contact.phoneNumber).contains(normalized)
        }?.name?.takeIf { it.isNotBlank() && it != "Unknown" }
    }

    // Call Logs
    val callLogs: StateFlow<List<CallLogEntity>> = repository.allCallLogs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val missedCallCount: StateFlow<Int> = repository.callLogCount(CallType.MISSED)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private var callLogSyncJob: Job? = null

    fun addCallLog(number: String, name: String?, type: CallType, duration: Int, simSlot: Int, isSpam: Boolean = false) {
        viewModelScope.launch {
            repository.insertCallLog(
                CallLogEntity(
                    number = number,
                    callerName = name,
                    callType = type,
                    durationSeconds = duration,
                    simSlot = simSlot,
                    isSpam = isSpam
                )
            )
        }
    }

    fun deleteCallLog(id: Long) = viewModelScope.launch {
        val log = repository.getCallLogById(id) ?: return@launch
        SystemCallLogImporter.deleteOne(repository, getApplication(), log)
    }

    /**
     * Deletes a whole contact's call history (a grouped row in call history). Must go
     * through [SystemCallLogImporter.deleteOne] once per id — a bulk Room delete would
     * leave no tombstones and the whole history would be re-imported from the system
     * call log on the next ON_RESUME.
     */
    fun deleteCallLogs(ids: List<Long>) = viewModelScope.launch {
        val app = getApplication<Application>()
        for (id in ids) {
            val log = repository.getCallLogById(id) ?: continue
            SystemCallLogImporter.deleteOne(repository, app, log)
        }
    }

    fun updateCallLogNote(id: Long, note: String?) = viewModelScope.launch {
        repository.updateCallLogNote(id, note)
    }

    fun updateLatestCallNote(note: String) = viewModelScope.launch {
        repository.getLatestCallLog()?.let {
            repository.updateCallLogNote(it.id, note)
        }
    }

    fun updateLatestCallDuration(duration: Int) = viewModelScope.launch {
        repository.updateLatestCallDuration(duration)
    }

    fun syncSystemCallLog() {
        if (callLogSyncJob?.isActive == true) return
        callLogSyncJob = viewModelScope.launch {
            withContext(Dispatchers.IO) {
                SystemCallLogImporter.sync(repository, getApplication())
            }
        }
    }

    fun clearAllCallLogs() = viewModelScope.launch {
        SystemCallLogImporter.clearAll(repository, getApplication())
    }

    // Blocked Numbers
    val blockedNumbers: StateFlow<List<BlockedNumberEntity>> = repository.allBlockedNumbers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun blockNumber(number: String, reason: String = "User Blocked") = viewModelScope.launch {
        repository.blockNumber(number, reason)
    }

    fun unblockNumber(number: String) = viewModelScope.launch {
        repository.unblockNumber(number)
    }

    // Speed Dials
    val speedDials: StateFlow<List<SpeedDialEntity>> = repository.allSpeedDials
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setSpeedDial(digit: Int, number: String, label: String) = viewModelScope.launch {
        repository.setSpeedDial(digit, number, label)
    }

    fun removeSpeedDial(digit: Int) = viewModelScope.launch {
        repository.removeSpeedDial(digit)
    }

    // Browser Settings & History
    private val _homePageUrl = MutableStateFlow(
        prefs.getString("home_page_url", "https://www.google.com") ?: "https://www.google.com"
    )
    val homePageUrl: StateFlow<String> = _homePageUrl.asStateFlow()

    private val _browserDarkMode = MutableStateFlow(prefs.getBoolean("browser_dark_mode", false))
    val browserDarkMode: StateFlow<Boolean> = _browserDarkMode.asStateFlow()

    private val _desktopMode = MutableStateFlow(prefs.getBoolean("desktop_mode", false))
    val desktopMode: StateFlow<Boolean> = _desktopMode.asStateFlow()

    val browserHistory: StateFlow<List<HistoryEntity>> = repository.allHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setHomePageUrl(url: String) {
        var formatted = url.trim()
        if (formatted == "about:blank" || formatted == NTP_URL) {
            // "Blank" homepage opens the New Tab page (speed dial), not a dead
            // white page; about:blank is accepted for backward compatibility.
            _homePageUrl.value = NTP_URL
            prefs.edit().putString("home_page_url", NTP_URL).apply()
            return
        }
        if (formatted.isNotEmpty() && !formatted.startsWith("http://") && !formatted.startsWith("https://")) {
            formatted = "https://$formatted"
        }
        _homePageUrl.value = formatted.ifEmpty { "https://www.google.com" }
        prefs.edit().putString("home_page_url", _homePageUrl.value).apply()
    }

    fun toggleBrowserDarkMode() {
        _browserDarkMode.value = !_browserDarkMode.value
        prefs.edit().putBoolean("browser_dark_mode", _browserDarkMode.value).apply()
    }

    fun toggleDesktopMode() {
        _desktopMode.value = !_desktopMode.value
        prefs.edit().putBoolean("desktop_mode", _desktopMode.value).apply()
    }

    fun addBrowserHistory(title: String, url: String) = viewModelScope.launch {
        repository.insertHistory(title, url)
    }

    /**
     * B8: the page-open hook. One row per URL with a visit counter, which is what
     * the new-tab page's "Top sites" ranks on. Private browsing and the new-tab
     * page itself are filtered out by the caller.
     */
    fun recordVisit(title: String, url: String) = viewModelScope.launch {
        repository.recordVisit(title, url)
    }

    /** Most-visited URLs for the new-tab page tiles. */
    fun topSites(limit: Int = 8) = repository.topVisited(limit)

    fun deleteHistoryItem(id: Long) = viewModelScope.launch {
        repository.deleteHistory(id)
    }

    fun clearBrowserHistory() = viewModelScope.launch {
        repository.clearHistory()
    }

    // Bookmarks
    val bookmarks: StateFlow<List<BookmarkEntity>> = repository.allBookmarks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addBookmark(title: String, url: String) = viewModelScope.launch {
        repository.insertBookmark(title, url)
    }

    fun deleteBookmark(bookmark: BookmarkEntity) = viewModelScope.launch {
        repository.deleteBookmark(bookmark)
    }

    // Notes
    val notes: StateFlow<List<NoteEntity>> = repository.allNotes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val upcomingCalendarEvents: StateFlow<List<CalendarEventEntity>> =
        repository.upcomingCalendarEvents(System.currentTimeMillis())
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun insertCalendarEvent(event: CalendarEventEntity) = viewModelScope.launch(Dispatchers.IO) {
        repository.insertCalendarEvent(event)
    }

    fun updateCalendarEvent(event: CalendarEventEntity) = viewModelScope.launch(Dispatchers.IO) {
        repository.updateCalendarEvent(event)
    }

    fun deleteCalendarEvent(event: CalendarEventEntity) = viewModelScope.launch(Dispatchers.IO) {
        repository.deleteCalendarEvent(event)
    }

    // Playlists
    val playlists: StateFlow<List<PlaylistEntity>> = repository.allPlaylists
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun createPlaylist(name: String, onResult: (Long) -> Unit = {}) = viewModelScope.launch {
        onResult(repository.createPlaylist(name))
    }

    fun getPlaylistItems(playlistId: Long): Flow<List<PlaylistItemEntity>> = repository.getPlaylistItems(playlistId)

    fun addPlaylistItem(item: PlaylistItemEntity) = viewModelScope.launch {
        repository.addPlaylistItem(item)
    }

    fun deletePlaylistItem(id: Long) = viewModelScope.launch {
        repository.deletePlaylistItem(id)
    }

    fun deletePlaylist(playlist: PlaylistEntity) = viewModelScope.launch {
        repository.deletePlaylist(playlist)
    }

    fun renamePlaylist(id: Long, name: String) = viewModelScope.launch {
        if (name.isNotBlank()) repository.renamePlaylist(id, name.trim())
    }

    fun movePlaylistItem(playlistId: Long, itemId: Long, direction: Int) = viewModelScope.launch {
        repository.movePlaylistItem(playlistId, itemId, direction)
    }

    fun saveNote(
        title: String,
        content: String,
        id: Long = 0,
        colorHex: String = "#3B82F6",
        isPinned: Boolean = false,
        category: String = "General",
        isPrivate: Boolean = false,
        isTodo: Boolean = false,
        done: Boolean = false
    ) = viewModelScope.launch {
        repository.insertNote(
            NoteEntity(
                id = id,
                title = title,
                content = content,
                colorHex = colorHex,
                isPinned = isPinned,
                category = category,
                isPrivate = isPrivate,
                isTodo = isTodo,
                done = done
            )
        )
    }

    fun toggleNotePin(note: NoteEntity) = viewModelScope.launch {
        repository.insertNote(note.copy(isPinned = !note.isPinned))
    }

    fun toggleNoteDone(note: NoteEntity) = viewModelScope.launch {
        repository.updateNoteDone(note.id, !note.done)
    }

    fun deleteNote(note: NoteEntity) = viewModelScope.launch {
        repository.deleteNote(note)
    }

    // One-time migration of the legacy shared-prefs to-do list ("todo_prefs":
    // the TodoScreen was fused into Notes). Runs on first Notes open after the
    // upgrade; afterwards the todo_prefs file is no longer needed.
    fun importLegacyTodos() = viewModelScope.launch {
        val context = getApplication<Application>()
        val prefs = context.getSharedPreferences("todo_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("todos_imported", false)) return@launch
        val raw = prefs.getString("todos", null)
        prefs.edit().putBoolean("todos_imported", true).apply()
        if (raw.isNullOrBlank()) return@launch
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val text = o.optString("text", "").trim()
                if (text.isEmpty()) continue
                repository.insertNote(
                    NoteEntity(
                        title = text,
                        content = o.optString("note", ""),
                        colorHex = "#10B981",
                        category = "Tasks",
                        isTodo = true,
                        done = o.optBoolean("done", false),
                        timestamp = o.optLong("ts", System.currentTimeMillis())
                    )
                )
            }
        }
    }

    // Local Sent SMS
    suspend fun getLocalSmsForThread(threadId: Long): List<LocalSmsEntity> =
        repository.getLocalSmsForThread(threadId)

    suspend fun getLocalSmsAll(): List<LocalSmsEntity> = repository.getLocalSmsAll()

    suspend fun insertLocalSmsSuspend(
        threadId: Long,
        address: String,
        body: String,
        simSlot: Int,
        status: String = "SENT"
    ): Long = repository.insertLocalSms(
        LocalSmsEntity(
            threadId = threadId,
            address = address,
            body = body,
            simSlot = simSlot,
            status = status
        )
    )

    fun insertLocalSms(
        threadId: Long,
        address: String,
        body: String,
        simSlot: Int,
        status: String = "SENT",
        onResult: (Long) -> Unit = {}
    ) = viewModelScope.launch {
        val id = repository.insertLocalSms(
            LocalSmsEntity(
                threadId = threadId,
                address = address,
                body = body,
                simSlot = simSlot,
                status = status
            )
        )
        onResult(id)
    }

    fun updateLocalSmsStatus(id: Long, status: String) = viewModelScope.launch {
        repository.updateLocalSmsStatus(id, status)
    }

    fun deleteLocalSms(entity: LocalSmsEntity) = viewModelScope.launch {
        repository.deleteLocalSms(entity)
    }

    suspend fun deleteLocalSmsByIdSuspend(id: Long) = repository.deleteLocalSmsById(id)

    /** "Delete conversation": drop every locally-echoed message of a thread. */
    suspend fun deleteLocalSmsForThreadSuspend(threadId: Long): Int =
        repository.deleteLocalSmsForThread(threadId)

    /**
     * Re-parents the echoes of a synthetic thread onto the real provider thread id
     * the OS created after the message was sent. Keeps a first conversation to a
     * new number from splitting into two rows in the conversation list.
     */
    suspend fun migrateLocalSmsThreadSuspend(oldThreadId: Long, newThreadId: Long): Int =
        repository.migrateLocalSmsThread(oldThreadId, newThreadId)

    // File Manager State
    private val _currentDirectory = MutableStateFlow(StorageHelper.getRootDirectory(application))
    val currentDirectory: StateFlow<File> = _currentDirectory.asStateFlow()

    // Storage volumes (internal + any SD card) the File Manager can browse. The
    // list is cached; call [refreshStorageRoots] when the Storage-locations
    // dialog opens so a card mounted after launch shows up.
    private val _storageRoots = MutableStateFlow(StorageHelper.getStorageRoots(application))
    val storageRoots: StateFlow<List<StorageRoot>> = _storageRoots.asStateFlow()

    fun refreshStorageRoots() {
        runCatching {
            _storageRoots.value = StorageHelper.getStorageRoots(getApplication<Application>())
        }
    }

    private fun isVolumeRootPath(path: String): Boolean =
        _storageRoots.value.any { it.root.absolutePath == path }

    /**
     * Where the File Manager has been, so system Back walks the user's actual path
     * instead of jumping straight to the parent. Before this, going 0 → DCIM →
     * Download → Movies and pressing Back landed in 0, losing every step.
     *
     * [stack] doubles as the breadcrumb: it always runs root → current through the
     * visited ancestors, so the trail shown and the trail Back follows cannot drift.
     */
    private val _nav = MutableStateFlow(
        FileNavState(listOf(StorageHelper.getRootDirectory(application)), emptyList())
    )
    val nav: StateFlow<FileNavState> = _nav.asStateFlow()

    private fun applyNav(next: FileNavState) {
        _nav.value = next
        _currentDirectory.value = next.current
    }

    private val _showHiddenFiles = MutableStateFlow(false)
    val showHiddenFiles: StateFlow<Boolean> = _showHiddenFiles.asStateFlow()

    /** Enter [file], recording it so Back can retrace the step. */
    fun navigateToDirectory(file: File) {
        if (!file.isDirectory) return
        val st = _nav.value
        if (st.current == file) return
        applyNav(FileNavState((st.stack + file).takeLast(NAV_STACK_MAX), emptyList()))
    }

    /** The top-bar arrow: one level towards the root, still recorded in the trail. */
    fun navigateUp() {
        val cur = _nav.value.current
        // Stop at a volume root (internal storage / SD card); climbing further
        // would reach /storage/emulated or /storage, which is not browsable.
        if (isVolumeRootPath(cur.absolutePath)) return
        val parent = cur.parentFile ?: return
        if (!parent.canRead()) return
        navigateToDirectory(parent)
    }

    /**
     * Switch the File Manager to a different storage volume root (SD card picker).
     * Reseeds the trail so the breadcrumb and Back start fresh at that root.
     */
    fun openRoot(root: File) {
        if (!root.isDirectory) return
        applyNav(FileNavState(listOf(root), emptyList()))
    }

    /** System Back. False when there is nowhere left to go, so the caller can close. */
    fun navigateBack(): Boolean {
        val st = _nav.value
        if (!st.canGoBack) return false
        applyNav(FileNavState(st.stack.dropLast(1), st.forward + st.current))
        return true
    }

    fun navigateForward() {
        val st = _nav.value
        val next = st.forward.lastOrNull() ?: return
        applyNav(FileNavState(st.stack + next, st.forward.dropLast(1)))
    }

    /**
     * Breadcrumb tap. Truncates the trail at [dir] instead of pushing onto it, so
     * Back from a crumb continues to that crumb's parent rather than bouncing back
     * through every folder the user drilled past on the way there.
     */
    fun jumpToDirectory(dir: File) {
        if (!dir.isDirectory) return
        val st = _nav.value
        if (st.current == dir) return
        val idx = st.stack.indexOfLast { it.absolutePath == dir.absolutePath }
        if (idx < 0) {
            navigateToDirectory(dir)
            return
        }
        applyNav(FileNavState(st.stack.subList(0, idx + 1).toList(), emptyList()))
    }

    fun toggleShowHiddenFiles() {
        _showHiddenFiles.value = !_showHiddenFiles.value
    }

    // Shared media index scanned OFF the main thread (audio/video/document
    // screens all consume this). Built once, cached, and reused for five
    // minutes. Screens ask for a freshness check on entry/resume; only the
    // explicit Refresh buttons force a full walk.
    private val _mediaIndex = MutableStateFlow<List<MediaItem>>(emptyList())
    val mediaIndex: StateFlow<List<MediaItem>> = _mediaIndex.asStateFlow()

    private var mediaScanRunning = false
    private var lastMediaScanMs = 0L
    private val mediaIndexMaxAgeMs = 5L * 60 * 1000

    fun refreshMediaIndex(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (mediaScanRunning) return
        if (!force && lastMediaScanMs > 0 && now - lastMediaScanMs < mediaIndexMaxAgeMs) {
            return
        }
        mediaScanRunning = true
        val context = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val items = runCatching { StorageHelper.getAllAccessibleMediaItems(context) }
            // A failed scan must never replace a good index with an empty one --
            // that presents as "the Video tab lost all my files", which is far
            // worse than a stale index and almost always self-heals on the next
            // refresh. There is no on-device log to read, so stay quiet and keep
            // what worked. Only successful scans move the freshness timestamp.
            items.getOrNull()?.let {
                _mediaIndex.value = it
                lastMediaScanMs = System.currentTimeMillis()
            }
            mediaScanRunning = false
        }
    }

    fun ensureMediaIndexFresh() = refreshMediaIndex(force = false)

    // Backup & Restore
    fun exportBackup(context: Context): File? {
        return try {
            val json = JSONObject()
            // Export Contacts
            val contactsArr = JSONArray()
            allContacts.value.forEach {
                val c = JSONObject()
                c.put("name", it.name)
                c.put("number", it.phoneNumber)
                c.put("email", it.email)
                c.put("isFavorite", it.isFavorite)
                c.put("isPrivate", it.isPrivate)
                contactsArr.put(c)
            }
            json.put("contacts", contactsArr)

            // Export Notes
            val notesArr = JSONArray()
            notes.value.forEach {
                val n = JSONObject()
                n.put("title", it.title)
                n.put("content", it.content)
                n.put("colorHex", it.colorHex)
                notesArr.put(n)
            }
            json.put("notes", notesArr)

            val backupFile = File(context.getExternalFilesDir(null), "dialer_backup_${System.currentTimeMillis()}.json")
            backupFile.writeText(json.toString())
            backupFile
        } catch (e: Exception) {
            null
        }
    }

    fun importBackup(backupFile: File): Boolean {
        return try {
            val text = backupFile.readText()
            val json = JSONObject(text)
            if (json.has("contacts")) {
                val contactsArr = json.getJSONArray("contacts")
                for (i in 0 until contactsArr.length()) {
                    val obj = contactsArr.getJSONObject(i)
                    saveContact(
                        ContactEntity(
                            name = obj.getString("name"),
                            phoneNumber = obj.getString("number"),
                            email = obj.optString("email", ""),
                            isFavorite = obj.optBoolean("isFavorite", false),
                            isPrivate = obj.optBoolean("isPrivate", false)
                        )
                    )
                }
            }
            if (json.has("notes")) {
                val notesArr = json.getJSONArray("notes")
                for (i in 0 until notesArr.length()) {
                    val obj = notesArr.getJSONObject(i)
                    saveNote(
                        title = obj.getString("title"),
                        content = obj.getString("content"),
                        colorHex = obj.optString("colorHex", "#3B82F6")
                    )
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private companion object {
        // Cap on the launcher's "Recent" row so its prefs entry stays bounded.
        const val RECENT_APPS_MAX = 8
        // Scroll keys starting with this are per-folder (the File Manager) and
        // are capped via SCROLL_KEY_INDEX so prefs cannot grow without bound.
        const val SCROLL_KEY_SCOPED_PREFIX = "files_"
        const val SCROLL_KEY_SCOPED_MAX = 40
        const val SCROLL_KEY_INDEX = "scroll_scoped_keys"
        // Cap on memoised folder size/count walks. Small on purpose: this is a
        // convenience label, and re-walking a folder is far cheaper than holding
        // thousands of entries on a low-memory device.
        const val FOLDER_STATS_MAX = 200
        // Cap on the File Manager's back/forward trail. 50 steps is far deeper than
        // a hand-held session; past that the oldest steps are dropped, which only
        // means Back unwinds a little less far.
        const val NAV_STACK_MAX = 50
    }
}

/** Launcher layout values: `launcher_layout`. */
const val LAUNCHER_LAYOUT_GRID = 0
const val LAUNCHER_LAYOUT_LIST = 1

/** Launcher clock values: `launcher_clock_format`. */
const val LAUNCHER_CLOCK_SYSTEM = 0
const val LAUNCHER_CLOCK_12H = 1
const val LAUNCHER_CLOCK_24H = 2

/** Default wallpaper readability scrim: `launcher_scrim`. */
const val LAUNCHER_DEFAULT_SCRIM = 0.62f

const val LAUNCHER_QS_MAX_TILES = 12

val LAUNCHER_QS_DEFAULT_TILES = listOf(
    "flashlight",
    "rotation",
    "system:settings",
    "system:sim",
    "system:clock",
    "wifi",
    "bluetooth",
    "data",
    "hotspot",
    "sound",
    "display"
)

val LAUNCHER_QS_KNOWN_TILES = LAUNCHER_QS_DEFAULT_TILES + listOf(
    "system:battery",
    "system:location",
    "system:accessibility",
    "system:apps",
    "system:date",
    "system:developer"
)

/**
 * File Manager navigation trail. [stack] runs root → current and doubles as the
 * breadcrumb; [forward] is a LIFO of places Back has left.
 */
data class FileNavState(
    val stack: List<File>,
    val forward: List<File>
) {
    val current: File get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1
    val canGoForward: Boolean get() = forward.isNotEmpty()
}
