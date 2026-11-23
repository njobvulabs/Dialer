package com.example.ui.screens.dialer

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.Manifest
import android.telecom.Call
import android.telecom.CallAudioState
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.service.ActiveCallState
import com.example.service.CustomInCallService
import com.example.ui.MainViewModel
import com.example.ui.components.AppIcons
import kotlinx.coroutines.delay

@Composable
fun InCallScreen(
    callState: ActiveCallState,
    onEndCall: () -> Unit,
    viewModel: MainViewModel
) {
    var showKeypad by remember { mutableStateOf(false) }
    var showNotesDialog by remember { mutableStateOf(false) }
    var callNoteText by remember { mutableStateOf("") }
    var dtmfDigits by remember { mutableStateOf("") }
    var secondsElapsed by remember { mutableLongStateOf(0L) }
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scrollState = rememberScrollState()

    // Elapsed timer counts while active and not on hold
    LaunchedEffect(callState.hasActiveCall, callState.callState, callState.isHold) {
        if (callState.callState == Call.STATE_ACTIVE && !callState.isHold) {
            while (true) {
                delay(1000L)
                secondsElapsed++
            }
        } else if (callState.callState != Call.STATE_ACTIVE) {
            secondsElapsed = 0L
        }
    }

    val isRinging = callState.callState == Call.STATE_RINGING || callState.callState == Call.STATE_RINGING
    val isDialing = callState.callState == Call.STATE_DIALING || callState.callState == Call.STATE_CONNECTING
    val isActive = callState.callState == Call.STATE_ACTIVE

    val minutes = secondsElapsed / 60
    val secs = secondsElapsed % 60
    val durationFormatted = String.format("%02d:%02d", minutes, secs)

    // Keep the screen awake while a call is in progress
    DisposableEffect(callState.hasActiveCall) {
        val window = (context as? Activity)?.window
        if (callState.hasActiveCall) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Dim the screen when the phone is held to the ear during an active call.
    val proximityCovered = rememberProximityCovered()
    DisposableEffect(proximityCovered, isActive, callState.isHold) {
        val activity = context as? Activity
        val window = activity?.window
        if (window != null) {
            val dim = proximityCovered && isActive && !callState.isHold
            window.attributes = window.attributes.apply {
                screenBrightness = if (dim) 0.001f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                flags = flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            }
        }
        onDispose {
            val activity = context as? Activity
            val window = activity?.window
            if (window != null) {
                window.attributes = window.attributes.apply {
                    screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    flags = flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                }
            }
        }
    }

    fun endCall() {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        if (secondsElapsed > 0) {
            viewModel.updateLatestCallDuration(secondsElapsed.toInt())
        }
        CustomInCallService.disconnectCall()
        onEndCall()
    }

    // Caller identity: prefer the reported name, else a contacts lookup, else the number.
    val resolvedName = remember(callState.callerNumber) {
        if (callState.callerName.isNotBlank()) null else viewModel.resolveCallerName(callState.callerNumber)
    }
    val hasRealName = callState.callerName.isNotBlank() && callState.callerName != callState.callerNumber

    val displayName = when {
        hasRealName -> callState.callerName
        resolvedName != null -> resolvedName
        callState.callerNumber.isNotBlank() -> callState.callerNumber
        else -> "Unknown Caller"
    }

    val displaySubtitle = when {
        hasRealName || resolvedName != null -> callState.callerNumber
        else -> "Mobile"
    }

    val statusText = when {
        callState.isHold -> "On Hold"
        isActive -> durationFormatted
        isDialing -> "Calling..."
        else -> callState.stateText
    }

    val statusColor = when {
        callState.isHold -> Color(0xFFF59E0B)
        isActive -> Color.White
        isRinging || isDialing -> Color(0xFF38BDF8)
        else -> Color(0xFF94A3B8)
    }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(Color(0xFF0F172A), Color(0xFF1E293B)))
            )
    ) {
        if (isLandscape) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center
                ) {
                    CallerHeader(
                        displayName = displayName,
                        displaySubtitle = displaySubtitle,
                        simSlot = callState.simSlot,
                        statusText = statusText,
                        statusColor = statusColor,
                        avatarSize = 88.dp,
                        isPulsing = isRinging || isDialing
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(scrollState),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    AnimatedVisibility(
                        visible = showKeypad,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        DTMFKeypad(
                            digits = dtmfDigits,
                            onDigit = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                dtmfDigits += it
                                CustomInCallService.playDtmf(it)
                            },
                            onBackspace = { dtmfDigits = dtmfDigits.dropLast(1) },
                            onHide = { showKeypad = false }
                        )
                    }
                    if (!showKeypad) {
                        CallActionGrid(
                            callState = callState,
                            showKeypad = showKeypad,
                            onShowKeypad = { showKeypad = it },
                            onOpenNotes = { showNotesDialog = true },
                            context = context
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    PrimaryCallActions(
                        isRinging = isRinging,
                        onEndCall = { endCall() },
                        onAnswer = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            CustomInCallService.answerCall()
                        }
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .verticalScroll(scrollState),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CallerHeader(
                    displayName = displayName,
                    displaySubtitle = displaySubtitle,
                    simSlot = callState.simSlot,
                    statusText = statusText,
                    statusColor = statusColor,
                    avatarSize = 112.dp,
                    isPulsing = isRinging || isDialing
                )
                
                Spacer(modifier = Modifier.height(16.dp))

                AnimatedVisibility(
                    visible = showKeypad,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    DTMFKeypad(
                        digits = dtmfDigits,
                        onDigit = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            dtmfDigits += it
                            CustomInCallService.playDtmf(it)
                        },
                        onBackspace = { dtmfDigits = dtmfDigits.dropLast(1) },
                        onHide = { showKeypad = false }
                    )
                }

                if (!showKeypad) {
                    CallActionGrid(
                        callState = callState,
                        showKeypad = showKeypad,
                        onShowKeypad = { showKeypad = it },
                        onOpenNotes = { showNotesDialog = true },
                        context = context
                    )
                }

                Spacer(modifier = Modifier.weight(1f))
                
                PrimaryCallActions(
                    isRinging = isRinging,
                    onEndCall = { endCall() },
                    onAnswer = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        CustomInCallService.answerCall()
                    }
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        // Proximity Lock Overlay: Intercept accidental touches when close to face
        if (proximityCovered && isActive && !callState.isHold) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { /* Block touches */ }
            )
        }

        // In-Call Quick Notes Dialog
        if (showNotesDialog) {
            AlertDialog(
                onDismissRequest = { showNotesDialog = false },
                title = { Text("Call Note", color = Color.White) },
                text = {
                    OutlinedTextField(
                        value = callNoteText,
                        onValueChange = { callNoteText = it },
                        placeholder = { Text("Type quick notes during call...") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF38BDF8),
                            unfocusedBorderColor = Color(0xFF64748B),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.updateLatestCallNote(callNoteText)
                        showNotesDialog = false
                    }) {
                        Text("Save", color = Color(0xFF38BDF8))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showNotesDialog = false }) {
                        Text("Cancel", color = Color(0xFF94A3B8))
                    }
                },
                containerColor = Color(0xFF1E293B)
            )
        }
    }
}

@Composable
private fun CallerHeader(
    displayName: String,
    displaySubtitle: String,
    simSlot: Int,
    statusText: String,
    statusColor: Color,
    avatarSize: Dp,
    isPulsing: Boolean = false
) {
    // Pulse animation when ringing or dialing
    val infiniteTransition = rememberInfiniteTransition(label = "pulse_transition")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isPulsing) 1.15f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(modifier = Modifier.height(8.dp))

        Box(contentAlignment = Alignment.Center) {
            if (isPulsing) {
                Box(
                    modifier = Modifier
                        .size(avatarSize * 1.25f)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(Color(0xFF38BDF8).copy(alpha = 0.15f))
                )
            }
            
            Box(
                modifier = Modifier
                    .size(avatarSize)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Color(0xFF4F46E5), Color(0xFF0EA5E9))))
                    .border(3.dp, Color.White.copy(alpha = 0.2f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (displayName.isBlank() || displayName == "Unknown Caller") {
                    Icon(
                        AppIcons.Person,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(avatarSize * 0.45f)
                    )
                } else {
                    Text(
                        text = displayName.take(2).uppercase(),
                        style = MaterialTheme.typography.displayMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = (avatarSize.value * 0.35f).sp
                        ),
                        color = Color.White
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        Text(
            text = displayName,
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )

        Text(
            text = displaySubtitle,
            style = MaterialTheme.typography.titleMedium,
            color = Color(0xFF94A3B8)
        )

        if (simSlot > 0) {
            Surface(
                color = Color(0xFF0EA5E9).copy(alpha = 0.18f),
                shape = RoundedCornerShape(50),
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Text(
                    text = "SIM $simSlot",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF38BDF8),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = statusText,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            color = statusColor
        )
    }
}

@Composable
private fun DTMFKeypad(
    digits: String,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onHide: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .background(Color(0xFF1E293B).copy(alpha = 0.6f), RoundedCornerShape(20.dp))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = digits.ifEmpty { "Enter DTMF" },
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = if (digits.isNotEmpty()) Color.White else Color(0xFF64748B),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            
            if (digits.isNotEmpty()) {
                IconButton(onClick = onBackspace, modifier = Modifier.size(36.dp)) {
                    Icon(AppIcons.Backspace, contentDescription = "Backspace", tint = Color(0xFF94A3B8))
                }
            }

            TextButton(onClick = onHide) {
                Text("Hide", color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold)
            }
        }

        val dtmfKeys = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("*", "0", "#")
        )
        dtmfKeys.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                row.forEach { digit ->
                    OutlinedButton(
                        onClick = { onDigit(digit.first()) },
                        modifier = Modifier.size(52.dp),
                        shape = CircleShape,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(text = digit, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun CallActionGrid(
    callState: ActiveCallState,
    showKeypad: Boolean,
    onShowKeypad: (Boolean) -> Unit,
    onOpenNotes: () -> Unit,
    context: Context
) {
    val haptic = LocalHapticFeedback.current

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            InCallActionButton(
                icon = if (callState.isMuted) AppIcons.MicOff else AppIcons.Mic,
                label = if (callState.isMuted) "Muted" else "Mute",
                isActive = callState.isMuted,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    CustomInCallService.mute(!callState.isMuted)
                }
            )
            InCallActionButton(
                icon = AppIcons.Dialpad,
                label = "Keypad",
                isActive = showKeypad,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onShowKeypad(!showKeypad)
                }
            )
            InCallActionButton(
                icon = if (callState.isSpeakerOn) AppIcons.VolumeUp else AppIcons.VolumeOff,
                label = "Speaker",
                isActive = callState.isSpeakerOn,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    CustomInCallService.toggleSpeaker(context = context, isSpeaker = !callState.isSpeakerOn)
                }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            InCallActionButton(
                icon = if (callState.isHold) AppIcons.PlayArrow else AppIcons.Pause,
                label = if (callState.isHold) "Unhold" else "Hold",
                isActive = callState.isHold,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    CustomInCallService.toggleHold()
                }
            )
            InCallActionButton(
                icon = AppIcons.NoteAdd,
                label = "Note",
                isActive = false,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onOpenNotes()
                }
            )
            InCallActionButton(
                icon = if (callState.audioRoute == CallAudioState.ROUTE_BLUETOOTH) AppIcons.BluetoothConnected else AppIcons.Bluetooth,
                label = "Bluetooth",
                isActive = callState.audioRoute == CallAudioState.ROUTE_BLUETOOTH,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    CustomInCallService.setAudioRoute(CallAudioState.ROUTE_BLUETOOTH)
                }
            )
        }

        val isRecording by com.example.util.CallRecorder.isRecording.collectAsState()
        val recPermLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                val ok = com.example.util.CallRecorder.start(context)
                Toast.makeText(
                    context,
                    if (ok) "Recording started" else "Recording not supported on this device",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(context, "Microphone permission needed to record", Toast.LENGTH_SHORT).show()
            }
        }
        InCallActionButton(
            icon = AppIcons.FiberManualRecord,
            label = if (isRecording) "Stop Rec" else "Record",
            isActive = isRecording,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                if (isRecording) {
                    val path = com.example.util.CallRecorder.stop()
                    Toast.makeText(
                        context,
                        if (path != null) "Saved: ${path.substringAfterLast('/')}" else "Recording failed",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    val granted = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        val ok = com.example.util.CallRecorder.start(context)
                        Toast.makeText(
                            context,
                            if (ok) "Recording started" else "Recording not supported on this device",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        recPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            }
        )
    }
}

@Composable
private fun PrimaryCallActions(
    isRinging: Boolean,
    onEndCall: () -> Unit,
    onAnswer: () -> Unit
) {
    if (isRinging) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            CallCircleButton(
                icon = AppIcons.CallEnd,
                label = "Decline",
                color = Color(0xFFDC2626),
                onClick = onEndCall
            )
            CallCircleButton(
                icon = AppIcons.Call,
                label = "Answer",
                color = Color(0xFF16A34A),
                onClick = onAnswer
            )
        }
    } else {
        CallCircleButton(
            icon = AppIcons.CallEnd,
            label = "End Call",
            color = Color(0xFFDC2626),
            onClick = onEndCall,
            size = 72.dp
        )
    }
}

@Composable
private fun CallCircleButton(
    icon: ImageVector,
    label: String,
    color: Color,
    onClick: () -> Unit,
    size: Dp = 64.dp
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = color,
            contentColor = Color.White,
            shadowElevation = 8.dp,
            modifier = Modifier.size(size)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, modifier = Modifier.size(size * 0.48f))
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFFCBD5E1)
        )
    }
}

@Composable
fun InCallActionButton(
    icon: ImageVector,
    label: String,
    isActive: Boolean,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = if (isActive) Color.White else Color(0xFF334155),
            contentColor = if (isActive) Color.Black else Color(0xFFFFFFFF),
            shadowElevation = if (isActive) 6.dp else 0.dp,
            modifier = Modifier.size(56.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, modifier = Modifier.size(26.dp))
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF94A3B8)
        )
    }
}

@Composable
private fun rememberProximityCovered(): Boolean {
    val context = LocalContext.current
    var covered by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val max = sensor?.maximumRange ?: 0f
                covered = max > 0f && event.values[0] < max
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (sensor != null) {
            sensorManager?.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        onDispose {
            sensorManager?.unregisterListener(listener)
        }
    }
    return covered
}
