package com.example.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import com.example.service.AssistantOverlayService
import com.example.ui.screens.recorder.RecorderStore
import java.io.File

data class AssistantRecorderState(
    val recording: Boolean = false,
    val paused: Boolean = false,
    val elapsedMs: Long = 0L,
    val amplitude: Int = 0,
    val lastFile: File? = null,
    val message: String = "Recorder ready",
    val hasPermission: Boolean = false
)

object AssistantRecorderController {
    private val _status = mutableStateOf(AssistantRecorderState())
    val status: State<AssistantRecorderState> get() = _status

    private val handler = Handler(Looper.getMainLooper())
    private var recorder: MediaRecorder? = null
    private var partFile: File? = null
    private var startedAt = 0L
    private var elapsedBeforePause = 0L
    private var lastFile: File? = null

    private val tick = object : Runnable {
        override fun run() {
            val current = _status.value
            if (!current.recording) return
            val elapsed = if (current.paused) {
                elapsedBeforePause
            } else {
                elapsedBeforePause + (System.currentTimeMillis() - startedAt)
            }
            val amplitude = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
            _status.value = current.copy(elapsedMs = elapsed, amplitude = amplitude)
            handler.postDelayed(this, 200L)
        }
    }

    @Synchronized
    fun refresh(context: Context): AssistantRecorderState {
        val permitted = hasPermission(context)
        if (!_status.value.recording) {
            _status.value = _status.value.copy(
                hasPermission = permitted,
                message = if (permitted) {
                    if (lastFile == null) "Recorder ready" else "Last recording saved"
                } else {
                    "Microphone permission needed"
                }
            )
        }
        return _status.value
    }

    @Synchronized
    fun start(context: Context): AssistantRecorderState {
        val app = context.applicationContext
        if (_status.value.recording) return _status.value
        if (!hasPermission(app)) {
            _status.value = _status.value.copy(
                hasPermission = false,
                message = "Microphone permission needed"
            )
            return _status.value
        }
        val target = RecorderStore.newFile(app, "ASST")
        val parent = target.parentFile ?: app.cacheDir
        val part = File(parent, ".${target.name}.part")
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(app)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        val started = runCatching {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioSamplingRate(44_100)
            rec.setAudioEncodingBitRate(128_000)
            rec.setOutputFile(part.absolutePath)
            rec.prepare()
            rec.start()
            recorder = rec
            partFile = part
            startedAt = System.currentTimeMillis()
            elapsedBeforePause = 0L
            handler.removeCallbacks(tick)
            handler.post(tick)
            true
        }.getOrElse {
            runCatching { rec.release() }
            part.delete()
            false
        }
        _status.value = if (started) {
            AssistantRecorderState(
                recording = true,
                paused = false,
                hasPermission = true,
                lastFile = lastFile,
                message = "Recording…"
            )
        } else {
            _status.value.copy(message = "Could not start recording")
        }
        if (started) notifyService(app, true)
        return _status.value
    }

    @Synchronized
    fun pause(): AssistantRecorderState {
        val current = _status.value
        if (!current.recording || current.paused) return current
        elapsedBeforePause += System.currentTimeMillis() - startedAt
        runCatching { recorder?.pause() }
        _status.value = current.copy(paused = true, message = "Recording paused")
        return _status.value
    }

    @Synchronized
    fun resume(): AssistantRecorderState {
        val current = _status.value
        if (!current.recording || !current.paused) return current
        startedAt = System.currentTimeMillis()
        runCatching { recorder?.resume() }
        _status.value = current.copy(paused = false, message = "Recording…")
        return _status.value
    }

    @Synchronized
    fun stop(context: Context, notifyService: Boolean = true): AssistantRecorderState {
        val app = context.applicationContext
        val current = _status.value
        val rec = recorder
        if (rec != null) {
            runCatching { rec.stop() }
            runCatching { rec.release() }
        }
        recorder = null
        handler.removeCallbacks(tick)
        val saved = finalizePart(app)
        partFile = null
        if (saved != null) lastFile = saved
        _status.value = AssistantRecorderState(
            recording = false,
            paused = false,
            elapsedMs = if (current.recording) current.elapsedMs else 0L,
            lastFile = lastFile,
            hasPermission = hasPermission(app),
            message = if (saved != null) "Saved ${saved.name}" else "Recording stopped"
        )
        if (notifyService && current.recording) notifyService(app, false)
        return _status.value
    }

    @Synchronized
    fun cancel(context: Context, notifyService: Boolean = true): AssistantRecorderState {
        val app = context.applicationContext
        val current = _status.value
        val rec = recorder
        if (rec != null) {
            runCatching { rec.stop() }
            runCatching { rec.release() }
        }
        recorder = null
        handler.removeCallbacks(tick)
        runCatching { partFile?.delete() }
        partFile = null
        _status.value = AssistantRecorderState(
            lastFile = lastFile,
            hasPermission = hasPermission(app),
            message = "Recording discarded"
        )
        if (notifyService && current.recording) notifyService(app, false)
        return _status.value
    }

    @Synchronized
    fun clearLastFile() {
        lastFile = null
        _status.value = _status.value.copy(lastFile = null)
    }

    fun stopIfRecording(context: Context, notifyService: Boolean = true) {
        if (_status.value.recording || recorder != null) stop(context, notifyService)
    }

    private fun finalizePart(context: Context): File? {
        val part = partFile ?: return null
        if (!part.exists()) return null
        val parent = part.parentFile ?: context.cacheDir
        val baseName = part.name.removePrefix(".").removeSuffix(".part")
            .ifBlank { "assistant_recording.m4a" }
        var target = File(parent, baseName)
        var suffix = 1
        while (target.exists()) {
            val name = baseName.substringBeforeLast('.')
            val ext = baseName.substringAfterLast('.', "m4a")
            target = File(parent, "$name ($suffix).$ext")
            suffix++
        }
        val saved = if (part.renameTo(target)) target else part
        runCatching { RecorderStore.scan(context, saved) }
        return saved
    }

    private fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun notifyService(context: Context, active: Boolean) {
        runCatching {
            context.startService(
                AssistantOverlayService.recordingIntent(context, active)
            )
        }
    }
}
