package com.example.util

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.example.service.ScreenRecorderService
import com.example.ui.screens.screenrecorder.ScreenRecorderConsentActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

object ScreenRecorderController {
    data class State(
        val isStarting: Boolean = false,
        val isRecording: Boolean = false,
        val isPaused: Boolean = false,
        val elapsedMs: Long = 0L,
        val outputPath: String? = null,
        val options: ScreenRecorderOptions = ScreenRecorderOptions(),
        val countdownSec: Int = 0,
        val error: String? = null
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val countdownHandler = Handler(Looper.getMainLooper())
    private val countdownToken = AtomicInteger(0)

    fun ensureLoaded(context: Context) {
        if (_state.value.outputPath == null && !_state.value.isRecording && !_state.value.isStarting) {
            _state.value = _state.value.copy(options = ScreenRecorderPrefs.load(context))
        }
    }

    fun setState(state: State) {
        _state.value = state
    }

    fun reset() {
        countdownToken.incrementAndGet()
        _state.value = State()
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    fun fail(message: String) {
        countdownToken.incrementAndGet()
        _state.value = _state.value.copy(
            isStarting = false,
            countdownSec = 0,
            error = message
        )
    }

    fun canceled() {
        countdownToken.incrementAndGet()
        _state.value = _state.value.copy(isStarting = false, countdownSec = 0, error = null)
    }

    fun updateOptions(context: Context, options: ScreenRecorderOptions) {
        ScreenRecorderPrefs.save(context, options)
        _state.value = _state.value.copy(options = options)
    }

    fun begin(context: Context, options: ScreenRecorderOptions) {
        ensureLoaded(context)
        ScreenRecorderPrefs.save(context, options)
        countdownToken.incrementAndGet()
        _state.value = State(options = options, isStarting = true, countdownSec = 3)
        val intent = Intent(context, ScreenRecorderConsentActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            .putExtra(ScreenRecorderConsentActivity.EXTRA_QUALITY, options.quality.name)
            .putExtra(ScreenRecorderConsentActivity.EXTRA_MIC_ENABLED, options.micEnabled)
            .putExtra(ScreenRecorderConsentActivity.EXTRA_SYSTEM_AUDIO_ENABLED, options.systemAudioEnabled)
            .putExtra(ScreenRecorderConsentActivity.EXTRA_DURATION_SEC, options.durationSec)
        runCatching { context.startActivity(intent) }
            .onFailure { fail(it.message ?: "Could not start screen recorder") }
    }

    fun startAfterCountdown(
        context: Context,
        resultCode: Int,
        data: Intent?,
        options: ScreenRecorderOptions
    ) {
        val token = countdownToken.incrementAndGet()
        val app = context.applicationContext
        for (second in 3 downTo 1) {
            countdownHandler.postDelayed({
                if (countdownToken.get() != token) return@postDelayed
                _state.value = _state.value.copy(countdownSec = second)
            }, (3 - second) * 1000L)
        }
        countdownHandler.postDelayed({
            if (countdownToken.get() != token) return@postDelayed
            start(app, resultCode, data, options)
        }, 3000L)
    }

    fun start(context: Context, resultCode: Int, data: Intent?, options: ScreenRecorderOptions) {
        if (data == null) {
            _state.value = State(options = options, error = "Screen capture permission was not returned")
            return
        }
        _state.value = State(isStarting = true, options = options)
        val intent = Intent(context, ScreenRecorderService::class.java)
            .setAction(ScreenRecorderService.ACTION_START)
            .putExtra(ScreenRecorderService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(ScreenRecorderService.EXTRA_RESULT_DATA, data)
            .putExtra(ScreenRecorderService.EXTRA_OPTIONS, options.quality.name)
            .putExtra(ScreenRecorderService.EXTRA_MIC_ENABLED, options.micEnabled)
            .putExtra(ScreenRecorderService.EXTRA_SYSTEM_AUDIO_ENABLED, options.systemAudioEnabled)
            .putExtra(ScreenRecorderService.EXTRA_DURATION_SEC, options.durationSec)
        runCatching { ContextCompat.startForegroundService(context, intent) }
            .onFailure { _state.value = State(options = options, error = it.message ?: "Could not start recorder") }
    }

    fun stop(context: Context) {
        countdownToken.incrementAndGet()
        val intent = Intent(context, ScreenRecorderService::class.java)
            .setAction(ScreenRecorderService.ACTION_STOP)
        runCatching { context.startService(intent) }
            .onFailure { _state.value = _state.value.copy(error = it.message ?: "Could not stop recorder") }
    }

    fun pause(context: Context) {
        runCatching {
            context.startService(
                Intent(context, ScreenRecorderService::class.java).setAction(ScreenRecorderService.ACTION_PAUSE)
            )
        }
    }

    fun resume(context: Context) {
        runCatching {
            context.startService(
                Intent(context, ScreenRecorderService::class.java).setAction(ScreenRecorderService.ACTION_RESUME)
            )
        }
    }

    fun createOutputFile(context: Context): File {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val name = "Screen_$stamp.mp4"
        val fallback = File(context.filesDir, "Screen Recordings")
        runCatching { fallback.mkdirs() }
        val dir: File = runCatching {
            val publicDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES)
            val target = File(publicDir, "NJ-Dialer/Screen Recordings")
            if (target.exists() || target.mkdirs()) target else fallback
        }.getOrDefault(fallback)
        return File(dir, name)
    }
}
