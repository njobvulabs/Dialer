package com.example.ui.screens.ppsspp

import android.content.Context

enum class PpssppOrientation(val label: String) {
    LANDSCAPE("Landscape"),
    AUTO("Auto"),
    PORTRAIT("Portrait")
}

object PpssppPrefs {
    private const val FILE = "ppsspp"
    private const val CPU_CORE = "cpu_core"
    private const val RESOLUTION = "resolution"
    private const val SOUND = "sound"
    private const val SHOW_FPS = "show_fps"
    private const val IMMERSIVE = "immersive"
    private const val SHOW_ANALOG = "show_analog"
    private const val AUTO_SAVE = "auto_save"
    private const val ORIENTATION = "orientation"
    private const val CONTROL_ALPHA = "control_alpha"
    private const val CONTROL_SCALE = "control_scale"
    private const val CONTROL_OFFSET = "control_offset"
    private const val LAST_ROM = "last_rom"

    val CPU_CORES = listOf("JIT", "IR JIT", "Interpreter")
    val RESOLUTIONS = listOf(
        "480x272",
        "960x544",
        "1440x816",
        "1920x1088",
        "2400x1360",
        "2880x1632",
        "3360x1904",
        "3840x2176",
        "4320x2448",
        "4800x2720"
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun cpuCore(context: Context): String =
        prefs(context).getString(CPU_CORE, "JIT")?.takeIf { it in CPU_CORES } ?: "JIT"

    fun setCpuCore(context: Context, value: String) {
        prefs(context).edit()
            .putString(CPU_CORE, value.takeIf { it in CPU_CORES } ?: "JIT")
            .apply()
    }

    fun resolution(context: Context): String =
        prefs(context).getString(RESOLUTION, "480x272")?.takeIf { it in RESOLUTIONS } ?: "480x272"

    fun setResolution(context: Context, value: String) {
        prefs(context).edit()
            .putString(RESOLUTION, value.takeIf { it in RESOLUTIONS } ?: "480x272")
            .apply()
    }

    fun sound(context: Context): Boolean = prefs(context).getBoolean(SOUND, true)

    fun setSound(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(SOUND, value).apply()
    }

    fun showFps(context: Context): Boolean = prefs(context).getBoolean(SHOW_FPS, false)

    fun setShowFps(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(SHOW_FPS, value).apply()
    }

    fun immersiveMode(context: Context): Boolean = prefs(context).getBoolean(IMMERSIVE, true)

    fun setImmersiveMode(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(IMMERSIVE, value).apply()
    }

    fun showAnalog(context: Context): Boolean = prefs(context).getBoolean(SHOW_ANALOG, true)

    fun setShowAnalog(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(SHOW_ANALOG, value).apply()
    }

    fun autoSave(context: Context): Boolean = prefs(context).getBoolean(AUTO_SAVE, true)

    fun setAutoSave(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(AUTO_SAVE, value).apply()
    }

    fun orientation(context: Context): PpssppOrientation {
        val raw = prefs(context).getString(ORIENTATION, PpssppOrientation.LANDSCAPE.name)
        return runCatching { PpssppOrientation.valueOf(raw ?: "") }
            .getOrDefault(PpssppOrientation.LANDSCAPE)
    }

    fun setOrientation(context: Context, value: PpssppOrientation) {
        prefs(context).edit().putString(ORIENTATION, value.name).apply()
    }

    fun controlAlpha(context: Context): Float =
        prefs(context).getFloat(CONTROL_ALPHA, 0.78f).coerceIn(0.3f, 1f)

    fun setControlAlpha(context: Context, value: Float) {
        prefs(context).edit().putFloat(CONTROL_ALPHA, value.coerceIn(0.3f, 1f)).apply()
    }

    fun controlScale(context: Context): Float =
        prefs(context).getFloat(CONTROL_SCALE, 1.0f).coerceIn(0.8f, 1.4f)

    fun setControlScale(context: Context, value: Float) {
        prefs(context).edit().putFloat(CONTROL_SCALE, value.coerceIn(0.8f, 1.4f)).apply()
    }

    fun controlOffset(context: Context): Float =
        prefs(context).getFloat(CONTROL_OFFSET, 0f).coerceIn(-40f, 40f)

    fun setControlOffset(context: Context, value: Float) {
        prefs(context).edit().putFloat(CONTROL_OFFSET, value.coerceIn(-40f, 40f)).apply()
    }

    fun resetControls(context: Context) {
        prefs(context).edit()
            .putFloat(CONTROL_ALPHA, 0.78f)
            .putFloat(CONTROL_SCALE, 1.0f)
            .putFloat(CONTROL_OFFSET, 0f)
            .putBoolean(SHOW_ANALOG, true)
            .apply()
    }

    fun lastRom(context: Context): String? = prefs(context).getString(LAST_ROM, null)

    fun setLastRom(context: Context, id: String?) {
        prefs(context).edit().putString(LAST_ROM, id).apply()
    }
}
