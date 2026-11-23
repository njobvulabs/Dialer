package com.example.util

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.example.service.InternalAudioRecordService
import com.example.ui.screens.recorder.AudioCaptureConsentActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object InternalAudioRecordController {
    data class State(
        val isStarting: Boolean = false,
        val isRecording: Boolean = false,
        val elapsedMs: Long = 0L,
        val outputPath: String? = null,
        val error: String? = null
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun setState(state: State) {
        _state.value = state
    }

    fun fail(message: String) {
        _state.value = _state.value.copy(isStarting = false, error = message)
    }

    fun canceled() {
        _state.value = _state.value.copy(isStarting = false, error = null)
    }

    fun begin(context: Context) {
        _state.value = State(isStarting = true)
        runCatching {
            context.startActivity(
                Intent(context, AudioCaptureConsentActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
        }.onFailure { fail(it.message ?: "Could not start audio capture") }
    }

    fun start(context: Context, resultCode: Int, data: Intent?) {
        if (data == null) {
            _state.value = State(error = "Screen capture permission was not returned")
            return
        }
        _state.value = State(isStarting = true)
        val intent = Intent(context, InternalAudioRecordService::class.java)
            .setAction(InternalAudioRecordService.ACTION_START)
            .putExtra(InternalAudioRecordService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(InternalAudioRecordService.EXTRA_RESULT_DATA, data)
        runCatching { ContextCompat.startForegroundService(context, intent) }
            .onFailure { _state.value = State(error = it.message ?: "Could not start audio capture") }
    }

    fun stop(context: Context) {
        runCatching {
            context.startService(
                Intent(context, InternalAudioRecordService::class.java)
                    .setAction(InternalAudioRecordService.ACTION_STOP)
            )
        }.onFailure { fail(it.message ?: "Could not stop audio capture") }
    }
}
