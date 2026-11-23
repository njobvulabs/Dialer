package com.example.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat

data class AssistantTorchState(
    val supported: Boolean = false,
    val on: Boolean = false,
    val message: String = "Checking torch…"
)

object AssistantTorchController {
    private val _status = mutableStateOf(AssistantTorchState())
    val status: State<AssistantTorchState> get() = _status

    private var cameraId: String? = null
    private var callbackRegistered = false
    private var knownOn = false

    private val callback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(id: String, enabled: Boolean) {
            if (id != cameraId) return
            knownOn = enabled
            _status.value = AssistantTorchState(
                supported = true,
                on = enabled,
                message = if (enabled) "Torch on" else "Torch off"
            )
        }

        override fun onTorchModeUnavailable(id: String) {
            if (id != cameraId) return
            _status.value = AssistantTorchState(false, false, "Torch unavailable")
        }
    }

    @SuppressLint("MissingPermission")
    fun refresh(context: Context): AssistantTorchState {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            cameraId = null
            return setState(AssistantTorchState(false, false, "Camera permission needed"))
        }
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return setState(AssistantTorchState(false, false, "Camera unavailable"))
        registerCallback(manager)
        val resolved = findCameraId(manager)
        cameraId = resolved
        if (resolved == null) {
            return setState(AssistantTorchState(false, false, "No flashlight on this device"))
        }
        return setState(
            AssistantTorchState(
                supported = true,
                on = knownOn,
                message = if (knownOn) "Torch on" else "Torch ready"
            )
        )
    }

    @SuppressLint("MissingPermission")
    fun toggle(context: Context): AssistantTorchState {
        val current = refresh(context)
        if (!current.supported) return current
        val id = cameraId ?: return current
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return setState(AssistantTorchState(false, false, "Camera unavailable"))
        val target = !knownOn
        return runCatching {
            manager.setTorchMode(id, target)
            knownOn = target
            setState(AssistantTorchState(true, target, if (target) "Torch on" else "Torch off"))
        }.getOrElse {
            setState(AssistantTorchState(false, false, "Torch busy or unavailable"))
        }
    }

    private fun setState(value: AssistantTorchState): AssistantTorchState {
        _status.value = value
        return value
    }

    private fun registerCallback(manager: CameraManager) {
        if (callbackRegistered) return
        runCatching {
            manager.registerTorchCallback(callback, Handler(Looper.getMainLooper()))
            callbackRegistered = true
        }
    }

    private fun findCameraId(manager: CameraManager): String? {
        val ids = runCatching { manager.cameraIdList }.getOrNull() ?: return null
        fun hasFlash(id: String): Boolean = runCatching {
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }.getOrDefault(false)
        val back = ids.firstOrNull { id ->
            hasFlash(id) && runCatching {
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            }.getOrDefault(false)
        }
        return back ?: ids.firstOrNull(::hasFlash)
    }
}
