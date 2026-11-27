package com.example.ui.screens.retro

import android.content.Context

enum class RetroOrientation(val label: String) {
    LANDSCAPE("Landscape"),
    AUTO("Auto"),
    PORTRAIT("Portrait")
}

object RetroPrefs {
    private const val FILE = "retro"
    private const val SHOW_FPS = "show_fps"
    private const val SOUND = "sound"
    private const val AUTO_SAVE = "auto_save"
    private const val IMMERSIVE = "immersive"
    private const val SHOW_ANALOG = "show_analog"
    private const val ORIENTATION = "orientation"
    private const val CONTROL_ALPHA = "control_alpha"
    private const val CONTROL_SCALE = "control_scale"
    private const val CONTROL_OFFSET = "control_offset"
    private const val LAST_ROM = "last_rom"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun showFps(context: Context): Boolean = prefs(context).getBoolean(SHOW_FPS, false)

    fun setShowFps(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(SHOW_FPS, value).apply()
    }

    fun sound(context: Context): Boolean = prefs(context).getBoolean(SOUND, true)

    fun setSound(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(SOUND, value).apply()
    }

    fun autoSave(context: Context): Boolean = prefs(context).getBoolean(AUTO_SAVE, true)

    fun setAutoSave(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(AUTO_SAVE, value).apply()
    }

    fun immersiveMode(context: Context): Boolean = prefs(context).getBoolean(IMMERSIVE, true)

    fun setImmersiveMode(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(IMMERSIVE, value).apply()
    }

    fun showAnalog(context: Context): Boolean = prefs(context).getBoolean(SHOW_ANALOG, true)

    fun setShowAnalog(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(SHOW_ANALOG, value).apply()
    }

    fun orientation(context: Context): RetroOrientation {
        val raw = prefs(context).getString(ORIENTATION, RetroOrientation.LANDSCAPE.name)
        return runCatching { RetroOrientation.valueOf(raw ?: "") }
            .getOrDefault(RetroOrientation.LANDSCAPE)
    }

    fun setOrientation(context: Context, value: RetroOrientation) {
        prefs(context).edit().putString(ORIENTATION, value.name).apply()
    }

    fun controlAlpha(context: Context): Float = prefs(context).getFloat(CONTROL_ALPHA, 0.78f).coerceIn(0.35f, 1f)

    fun setControlAlpha(context: Context, value: Float) {
        prefs(context).edit().putFloat(CONTROL_ALPHA, value.coerceIn(0.35f, 1f)).apply()
    }

    fun controlScale(context: Context): Float = prefs(context).getFloat(CONTROL_SCALE, 1f).coerceIn(0.8f, 1.3f)

    fun setControlScale(context: Context, value: Float) {
        prefs(context).edit().putFloat(CONTROL_SCALE, value.coerceIn(0.8f, 1.3f)).apply()
    }

    fun controlOffset(context: Context): Float = prefs(context).getFloat(CONTROL_OFFSET, 0f).coerceIn(-48f, 48f)

    fun setControlOffset(context: Context, value: Float) {
        prefs(context).edit().putFloat(CONTROL_OFFSET, value.coerceIn(-48f, 48f)).apply()
    }

    fun resetControls(context: Context) {
        prefs(context).edit()
            .putFloat(CONTROL_ALPHA, 0.78f)
            .putFloat(CONTROL_SCALE, 1f)
            .putFloat(CONTROL_OFFSET, 0f)
            .putBoolean(SHOW_ANALOG, true)
            .apply()
    }

    fun lastRom(context: Context): String? = prefs(context).getString(LAST_ROM, null)

    fun setLastRom(context: Context, id: String?) {
        prefs(context).edit().putString(LAST_ROM, id).apply()
    }
}
