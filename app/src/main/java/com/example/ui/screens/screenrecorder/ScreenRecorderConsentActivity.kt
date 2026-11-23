package com.example.ui.screens.screenrecorder

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.util.ScreenRecorderController
import com.example.util.ScreenRecorderOptions
import com.example.util.ScreenRecorderQuality

class ScreenRecorderConsentActivity : Activity() {
    private var options = ScreenRecorderOptions()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        options = ScreenRecorderOptions(
            quality = runCatching {
                ScreenRecorderQuality.valueOf(
                    savedInstanceState?.getString(EXTRA_QUALITY)
                        ?: intent.getStringExtra(EXTRA_QUALITY)
                        ?: ScreenRecorderQuality.STANDARD.name
                )
            }.getOrDefault(ScreenRecorderQuality.STANDARD),
            micEnabled = savedInstanceState?.getBoolean(EXTRA_MIC_ENABLED)
                ?: intent.getBooleanExtra(EXTRA_MIC_ENABLED, false),
            systemAudioEnabled = savedInstanceState?.getBoolean(EXTRA_SYSTEM_AUDIO_ENABLED)
                ?: intent.getBooleanExtra(EXTRA_SYSTEM_AUDIO_ENABLED, false),
            durationSec = savedInstanceState?.getInt(EXTRA_DURATION_SEC)
                ?: intent.getIntExtra(EXTRA_DURATION_SEC, 0)
        )
        ScreenRecorderController.setState(
            ScreenRecorderController.State(options = options, isStarting = true, countdownSec = 3)
        )
        val needsMic = options.micEnabled || options.systemAudioEnabled
        if (needsMic && !hasMicPermission()) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
        } else {
            requestProjection()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(EXTRA_QUALITY, options.quality.name)
        outState.putBoolean(EXTRA_MIC_ENABLED, options.micEnabled)
        outState.putBoolean(EXTRA_SYSTEM_AUDIO_ENABLED, options.systemAudioEnabled)
        outState.putInt(EXTRA_DURATION_SEC, options.durationSec)
        super.onSaveInstanceState(outState)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_MIC) return
        if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            options = options.copy(micEnabled = false, systemAudioEnabled = false)
            Toast.makeText(this, "Audio permission denied; recording silently", Toast.LENGTH_SHORT).show()
        }
        requestProjection()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PROJECTION) return
        if (resultCode == RESULT_OK) {
            ScreenRecorderController.startAfterCountdown(applicationContext, resultCode, data, options)
        } else {
            ScreenRecorderController.canceled()
        }
        finish()
    }

    private fun requestProjection() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            ScreenRecorderController.fail("Screen capture unavailable")
            finish()
            return
        }
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_PROJECTION)
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        const val EXTRA_QUALITY = "screen_recorder_quality"
        const val EXTRA_MIC_ENABLED = "screen_recorder_mic_enabled"
        const val EXTRA_SYSTEM_AUDIO_ENABLED = "screen_recorder_system_audio_enabled"
        const val EXTRA_DURATION_SEC = "screen_recorder_duration_sec"
        private const val REQUEST_MIC = 4311
        private const val REQUEST_PROJECTION = 4312
    }
}
