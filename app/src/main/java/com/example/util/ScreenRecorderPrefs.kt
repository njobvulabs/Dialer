package com.example.util

import android.content.Context

enum class ScreenRecorderQuality(
    val label: String,
    val maxDimension: Int,
    val bitrate: Int,
    val fps: Int
) {
    LOW("Low", 720, 2_000_000, 24),
    STANDARD("Standard", 1080, 4_000_000, 30),
    HIGH("High", 1920, 8_000_000, 30)
}

data class ScreenRecorderOptions(
    val quality: ScreenRecorderQuality = ScreenRecorderQuality.STANDARD,
    val micEnabled: Boolean = false,
    val systemAudioEnabled: Boolean = false,
    val durationSec: Int = 0
)

object ScreenRecorderPrefs {
    private const val PREFS = "screen_recorder"
    private const val QUALITY = "quality"
    private const val MIC = "mic"
    private const val SYSTEM_AUDIO = "system_audio"
    private const val DURATION = "duration_sec"

    fun load(context: Context): ScreenRecorderOptions {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return ScreenRecorderOptions(
            quality = runCatching {
                ScreenRecorderQuality.values()[prefs.getInt(QUALITY, ScreenRecorderQuality.STANDARD.ordinal)]
            }.getOrDefault(ScreenRecorderQuality.STANDARD),
            micEnabled = prefs.getBoolean(MIC, false),
            systemAudioEnabled = prefs.getBoolean(SYSTEM_AUDIO, false),
            durationSec = prefs.getInt(DURATION, 0).coerceAtLeast(0)
        )
    }

    fun save(context: Context, options: ScreenRecorderOptions) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(QUALITY, options.quality.ordinal)
            .putBoolean(MIC, options.micEnabled)
            .putBoolean(SYSTEM_AUDIO, options.systemAudioEnabled)
            .putInt(DURATION, options.durationSec)
            .apply()
    }
}
