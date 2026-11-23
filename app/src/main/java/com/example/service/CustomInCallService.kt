package com.example.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import com.example.data.db.AppDatabase
import com.example.data.db.CallLogEntity
import com.example.data.db.CallType
import com.example.data.repository.AppRepository
import com.example.ui.components.AppIcons
import com.example.util.CallRecorder
import com.example.util.CallScreening
import com.example.util.ContactNumbers
import com.example.util.DeviceContacts
import com.example.util.ScreeningDecision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ActiveCallState(
    val hasActiveCall: Boolean = false,
    val callerNumber: String = "",
    val callerName: String = "",
    val stateText: String = "Idle",
    val callState: Int = Call.STATE_DISCONNECTED,
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = false,
    val isHold: Boolean = false,
    val callDurationSeconds: Long = 0,
    val simSlot: Int = 1,
    val audioRoute: Int = CallAudioState.ROUTE_WIRED_OR_EARPIECE
)

class CustomInCallService : InCallService() {

    companion object {
        private var activeCall: Call? = null
        private val _callState = MutableStateFlow(ActiveCallState())
        val callState: StateFlow<ActiveCallState> = _callState.asStateFlow()

        @Volatile
        var userDeclinedFlag: Boolean = false
            private set

        @Volatile
        private var serviceInstance: CustomInCallService? = null

        fun mute(isMuted: Boolean) {
            activeCall?.let {
                // Adjust audio state via InCallService static reference if available
            }
            _callState.value = _callState.value.copy(isMuted = isMuted)
        }

        fun toggleSpeaker(context: Context, isSpeaker: Boolean) {
            _callState.value = _callState.value.copy(isSpeakerOn = isSpeaker)
        }

        fun setAudioRoute(route: Int) {
            serviceInstance?.setAudioRoute(route)
            _callState.value = _callState.value.copy(audioRoute = route)
        }

        fun toggleHold() {
            activeCall?.let { call ->
                if (_callState.value.isHold) {
                    call.unhold()
                } else {
                    call.hold()
                }
                _callState.value = _callState.value.copy(isHold = !_callState.value.isHold)
            }
        }

        fun answerCall() {
            activeCall?.answer(0)
        }

        fun disconnectCall() {
            if (_callState.value.callState == Call.STATE_RINGING) {
                userDeclinedFlag = true
            }
            activeCall?.disconnect()
            activeCall = null
            _callState.value = ActiveCallState()
        }

        fun playDtmf(digit: Char) {
            activeCall?.playDtmfTone(digit)
            activeCall?.stopDtmfTone()
        }

        fun startSimulatedCall(number: String, simSlot: Int = 1) {
            _callState.value = ActiveCallState(
                hasActiveCall = true,
                callerNumber = number,
                callerName = if (number.contains("@")) number else "Call with $number",
                stateText = "Dialing...",
                callState = Call.STATE_DIALING,
                simSlot = simSlot
            )
        }

        fun connectSimulatedCall() {
            val cur = _callState.value
            if (cur.hasActiveCall && cur.callState == Call.STATE_DIALING) {
                _callState.value = cur.copy(
                    callState = Call.STATE_ACTIVE,
                    stateText = "Connected (SIM ${cur.simSlot})"
                )
            }
        }

        fun startSimulatedIncomingCall(number: String = "+1 (555) 019-2831", name: String = "John Doe (Incoming)", simSlot: Int = 1) {
            _callState.value = ActiveCallState(
                hasActiveCall = true,
                callerNumber = number,
                callerName = name,
                stateText = "Incoming Call...",
                callState = Call.STATE_RINGING,
                simSlot = simSlot
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        serviceInstance = this
    }

    private val prefs get() = getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    private val mainHandler = Handler(Looper.getMainLooper())
    private var torchBlinkOn = false
    private var flashBlinking = false
    private val torchBlinkRunnable = object : Runnable {
        override fun run() {
            torchBlinkOn = !torchBlinkOn
            setTorch(torchBlinkOn)
            mainHandler.postDelayed(this, 600L)
        }
    }

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repository: AppRepository by lazy {
        AppRepository(AppDatabase.getDatabase(applicationContext))
    }

    // Per-call tracking (only for real Telecom calls)
    private var wasIncoming = false
    private var wasActive = false
    private var connectedElapsed: Long? = null
    private var loggedCallId: Long? = null
    private var finalized = false
    private var silencedRingerVolume: Int? = null
    private var announcedForNumber: String? = null

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            super.onStateChanged(call, state)
            when (state) {
                Call.STATE_ACTIVE -> {
                    if (!wasActive) {
                        wasActive = true
                        connectedElapsed = SystemClock.elapsedRealtime()
                        stopIncomingFlash()
                        restoreRingerIfNeeded()
                        vibrateOnConnect()
                        maybeAutoRecord()
                    }
                }
                Call.STATE_RINGING -> {
                    startIncomingFlash()
                    announceCallerIfNeeded(call)
                }
                Call.STATE_DISCONNECTED -> {
                    stopIncomingFlash()
                    restoreRingerIfNeeded()
                    updateCallState(call, state)
                    finalizeCall()
                    return
                }
            }
            updateCallState(call, state)
        }
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        activeCall = call
        call.registerCallback(callCallback)

        val number = call.details?.handle?.schemeSpecificPart ?: "Unknown"
        wasIncoming = call.state == Call.STATE_RINGING
        wasActive = call.state == Call.STATE_ACTIVE
        connectedElapsed = if (wasActive) SystemClock.elapsedRealtime() else null
        loggedCallId = null
        finalized = false
        userDeclinedFlag = false

        updateCallState(call, call.state)

        if (wasIncoming) {
            if (call.state == Call.STATE_RINGING) startIncomingFlash()
            ioScope.launch {
                try {
                    val known = isKnownNumber(number)
                    val decision = CallScreening.evaluate(applicationContext, number, repository, known)
                    if (decision is ScreeningDecision.Block) {
                        userDeclinedFlag = true
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            runCatching { call.reject(false, "") }
                        }
                    } else if (decision == ScreeningDecision.Silence) {
                        silenceRinger()
                    }
                    loggedCallId = repository.insertCallLog(
                        CallLogEntity(
                            number = number,
                            callerName = null,
                            callType = CallType.INCOMING,
                            simSlot = 1
                        )
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        stopIncomingFlash()
        call.unregisterCallback(callCallback)
        if (activeCall == call) {
            finalizeCall()
            activeCall = null
            _callState.value = ActiveCallState()
        }
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        super.onCallAudioStateChanged(audioState)
        audioState?.let {
            _callState.value = _callState.value.copy(
                isMuted = it.isMuted,
                isSpeakerOn = it.route == CallAudioState.ROUTE_SPEAKER,
                audioRoute = it.route
            )
        }
    }

    override fun onDestroy() {
        if (serviceInstance === this) serviceInstance = null
        stopIncomingFlash()
        restoreRingerIfNeeded()
        runCatching { if (CallRecorder.isRecording.value) CallRecorder.stop() }
        CallAnnouncer.shutdown()
        ioScope.cancel()
        super.onDestroy()
    }

    private fun vibrateOnConnect() {
        if (!prefs.getBoolean("vibrate_on_connect", true)) return
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    @SuppressLint("MissingPermission")
    private fun setTorch(on: Boolean) {
        runCatching {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
            val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = manager.cameraIdList.firstOrNull { cid ->
                manager.getCameraCharacteristics(cid).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            } ?: return
            manager.setTorchMode(id, on)
        }
    }

    private fun startIncomingFlash() {
        if (!prefs.getBoolean("flash_on_call", false)) return
        if (flashBlinking) return
        flashBlinking = true
        torchBlinkOn = false
        setTorch(true)
        torchBlinkOn = true
        mainHandler.postDelayed(torchBlinkRunnable, 600L)
    }

    private fun stopIncomingFlash() {
        if (!flashBlinking) return
        flashBlinking = false
        mainHandler.removeCallbacks(torchBlinkRunnable)
        if (torchBlinkOn) {
            setTorch(false)
            torchBlinkOn = false
        }
    }

    private suspend fun isKnownNumber(number: String): Boolean {
        val digits = ContactNumbers.normalize(number) ?: return false
        if (digits.length < 3) return false
        val inDb = repository.getContactsOnce().any {
            ContactNumbers.keys(it.phoneNumber).contains(digits)
        }
        if (inDb) return true
        return DeviceContacts.query(this).any {
            ContactNumbers.normalize(it.second) == digits
        }
    }

    private fun announceCallerIfNeeded(call: Call) {
        if (!prefs.getBoolean("announce_caller", false)) return
        val number = call.details?.handle?.schemeSpecificPart ?: return
        if (announcedForNumber == number) return
        announcedForNumber = number
        CallAnnouncer.ensureInit(applicationContext)
        ioScope.launch {
            try {
                val digits = ContactNumbers.normalize(number)
                val name = digits?.let { wanted ->
                    repository.getContactsOnce().firstOrNull {
                        ContactNumbers.keys(it.phoneNumber).contains(wanted)
                    }?.name
                        ?: DeviceContacts.query(this@CustomInCallService)
                            .firstOrNull { ContactNumbers.normalize(it.second) == wanted }
                            ?.first
                }
                CallAnnouncer.speak(name ?: "Call from $number")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun silenceRinger() {
        runCatching {
            val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            silencedRingerVolume = am.getStreamVolume(android.media.AudioManager.STREAM_RING)
            am.setStreamVolume(android.media.AudioManager.STREAM_RING, 0, 0)
        }
    }

    private fun restoreRingerIfNeeded() {
        val vol = silencedRingerVolume ?: return
        silencedRingerVolume = null
        runCatching {
            val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            am.setStreamVolume(android.media.AudioManager.STREAM_RING, vol, 0)
        }
    }

    private fun maybeAutoRecord() {
        if (!prefs.getBoolean("auto_record_calls", false)) return
        runCatching {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                CallRecorder.start(applicationContext)
            }
        }
    }

    private fun finalizeCall() {
        if (finalized) return
        finalized = true

        if (CallRecorder.isRecording.value) {
            CallRecorder.stop()
        }

        val duration = if (wasActive && connectedElapsed != null) {
            ((SystemClock.elapsedRealtime() - connectedElapsed!!) / 1000).coerceAtLeast(0).toInt()
        } else 0

        val id = loggedCallId
        val declined = userDeclinedFlag
        val number = _callState.value.callerNumber
        ioScope.launch {
            try {
                var finalType: CallType? = null
                if (id != null) {
                    finalType = when {
                        wasActive -> CallType.INCOMING
                        declined -> CallType.REJECTED
                        else -> CallType.MISSED
                    }
                    repository.updateCallLogType(id, finalType)
                    if (wasActive && duration > 0) {
                        repository.updateCallLogDuration(id, duration)
                    }
                } else if (wasActive && duration > 0) {
                    repository.updateLatestCallDuration(duration)
                }
                if (finalType == CallType.MISSED && !declined &&
                    !CallScreening.isPrivateNumber(number)
                ) {
                    val digits = ContactNumbers.normalize(number)
                    val name = digits?.let { wanted ->
                        repository.getContactsOnce().firstOrNull {
                            ContactNumbers.keys(it.phoneNumber).contains(wanted)
                        }?.name
                    } ?: ""
                    MissedCallReceiver.post(applicationContext, number, name)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        userDeclinedFlag = false
        announcedForNumber = null
    }

    private fun updateCallState(call: Call, state: Int) {
        val number = call.details?.handle?.schemeSpecificPart ?: "Unknown"
        val stateName = when (state) {
            Call.STATE_RINGING -> "Incoming Call"
            Call.STATE_DIALING -> "Dialing..."
            Call.STATE_CONNECTING -> "Connecting..."
            Call.STATE_ACTIVE -> "Active Call"
            Call.STATE_HOLDING -> "On Hold"
            Call.STATE_DISCONNECTED -> "Call Ended"
            else -> "In Call"
        }

        _callState.value = _callState.value.copy(
            hasActiveCall = state != Call.STATE_DISCONNECTED,
            callerNumber = number,
            stateText = stateName,
            callState = state,
            isHold = state == Call.STATE_HOLDING
        )
    }
}
