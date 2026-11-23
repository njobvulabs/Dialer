package com.example.ui.screens.flashlight

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import com.example.ui.components.AppIcons

private enum class TorchSource(val label: String, val hint: String) {
    BACK("Back LED", "Rear flash"),
    FRONT("Front Screen", "Screen light")
}

private enum class TorchMode(val label: String) {
    STEADY("Steady"),
    STROBE("Strobe"),
    SOS("SOS")
}

private fun cameraManager(context: Context): CameraManager =
    context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

private fun backCameraId(context: Context): String? = runCatching {
    val manager = cameraManager(context)
    manager.cameraIdList.firstOrNull { id ->
        runCatching {
            manager.getCameraCharacteristics(id)[CameraCharacteristics.LENS_FACING] ==
                CameraCharacteristics.LENS_FACING_BACK
        }.getOrDefault(false)
    }
}.getOrNull()

// Standard SOS in Morse: dot=200ms, dash=600ms, letter gap=600ms, word gap=1400ms.
private fun sosPattern(): List<Pair<Long, Boolean>> = listOf(
    200L to true, 200L to false, 200L to true, 200L to false, 200L to true, 600L to false,
    600L to true, 200L to false, 600L to true, 200L to false, 600L to true, 600L to false,
    200L to true, 200L to false, 200L to true, 200L to false, 200L to true, 1400L to false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlashlightScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    val hasCamera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
    val backId = remember { backCameraId(context) }
    val noFlash = backId == null

    var on by remember { mutableStateOf(false) }
    var blinkState by remember { mutableStateOf(true) }
    var source by remember { mutableStateOf(TorchSource.BACK) }
    var mode by remember { mutableStateOf(TorchMode.STEADY) }
    var strobeMs by remember { mutableIntStateOf(220) }

    fun toast(msg: String) {
        runCatching { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
    }

    fun setLed(value: Boolean) {
        val id = backId ?: return
        mainHandler.post {
            runCatching { cameraManager(context).setTorchMode(id, value) }
                .onFailure {
                    on = false
                    toast("Flashlight unavailable: ${it.message}")
                }
        }
    }

    val window = (context as? Activity)?.window
    val originalBrightness = remember { window?.attributes?.screenBrightness }

    // Boost screen brightness for the front (screen) flashlight.
    DisposableEffect(source, on) {
        if (source == TorchSource.FRONT && on) {
            runCatching {
                window?.attributes = window?.attributes?.apply { screenBrightness = 1f }
            }
        }
        onDispose {
            runCatching {
                window?.attributes = window?.attributes?.apply {
                    screenBrightness = originalBrightness ?: -1f
                }
            }
        }
    }

    // Steady / Strobe / SOS driver for the LED + blink overlay.
    LaunchedEffect(on, source, mode, strobeMs) {
        if (!on || mode == TorchMode.STEADY) {
            blinkState = true
            if (source == TorchSource.BACK) setLed(on)
            return@LaunchedEffect
        }
        val pattern = if (mode == TorchMode.STROBE) {
            listOf(strobeMs.toLong() to true, strobeMs.toLong() to false)
        } else {
            sosPattern()
        }
        while (true) {
            pattern.forEach { (dur, value) ->
                blinkState = value
                if (source == TorchSource.BACK) setLed(value)
                delay(dur)
            }
        }
    }

    // Keep the screen awake and always switch the hardware off on exit.
    DisposableEffect(Unit) {
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setLed(false)
        }
    }

    // Auto-off when the app goes to the background.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                on = false
                blinkState = true
                setLed(false)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun toggle() {
        if (!hasCamera) {
            toast("Camera permission needed for the flashlight")
            return
        }
        on = !on
    }

    fun selectSource(s: TorchSource) {
        if (s == source) return
        on = false
        blinkState = true
        setLed(false)
        source = s
    }

    BackHandler {
        setLed(false)
        on = false
        onBack()
    }

    val frontLight = source == TorchSource.FRONT &&
        (if (mode == TorchMode.STEADY) on else blinkState)
    val fg = if (frontLight) Color.Black else Color.White
    val accent = Color(0xFFFFD600)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0B0D))
    ) {
        // Front screen-light overlay (drawn below the controls).
        if (frontLight) {
            Box(modifier = Modifier.fillMaxSize().background(Color.White))
        }

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val ringColor by animateColorAsState(
                targetValue = if (on) accent else fg.copy(alpha = 0.6f),
                label = "ring"
            )
            Surface(
                onClick = { toggle() },
                shape = CircleShape,
                color = if (on) accent else Color(0xFF1E1E22),
                border = BorderStroke(3.dp, ringColor),
                modifier = Modifier.size(168.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (on) AppIcons.FlashlightOn else AppIcons.FlashlightOff,
                        contentDescription = "Torch",
                        tint = if (on) Color.Black else fg,
                        modifier = Modifier.size(80.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = when {
                    on && mode == TorchMode.STEADY -> "ON"
                    on && mode == TorchMode.STROBE -> "STROBE"
                    on && mode == TorchMode.SOS -> "SOS"
                    else -> "TAP TO TURN ON"
                },
                color = fg,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "${source.label} • ${source.hint}" + if (noFlash && source == TorchSource.BACK) {
                    " (no flash detected)"
                } else {
                    ""
                },
                color = fg.copy(alpha = 0.6f),
                fontSize = 13.sp
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Light source selector
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TorchSource.values().forEach { s ->
                    val selected = s == source
                    Surface(
                        onClick = { selectSource(s) },
                        shape = RoundedCornerShape(22.dp),
                        color = if (selected) accent else fg.copy(alpha = 0.08f),
                        border = BorderStroke(
                            1.dp,
                            if (selected) accent else fg.copy(alpha = 0.35f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = if (s == TorchSource.BACK) {
                                    AppIcons.FlashlightOn
                                } else {
                                    AppIcons.Smartphone
                                },
                                contentDescription = null,
                                tint = if (selected) Color.Black else fg,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = s.label,
                                color = if (selected) Color.Black else fg,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Mode selector
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TorchMode.values().forEach { m ->
                    val selected = m == mode
                    Surface(
                        onClick = { mode = m },
                        shape = RoundedCornerShape(22.dp),
                        color = if (selected) accent else fg.copy(alpha = 0.08f),
                        border = BorderStroke(
                            1.dp,
                            if (selected) accent else fg.copy(alpha = 0.35f)
                        )
                    ) {
                        Text(
                            text = m.label,
                            color = if (selected) Color.Black else fg,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)
                        )
                    }
                }
            }

            if (mode == TorchMode.STROBE) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Slow", color = fg.copy(alpha = 0.7f), fontSize = 12.sp)
                    Slider(
                        value = strobeMs.toFloat(),
                        onValueChange = { strobeMs = it.toInt() },
                        valueRange = 80f..600f,
                        colors = SliderDefaults.colors(
                            thumbColor = accent,
                            activeTrackColor = accent,
                            inactiveTrackColor = fg.copy(alpha = 0.2f)
                        ),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                    )
                    Text("Fast", color = fg.copy(alpha = 0.7f), fontSize = 12.sp)
                }
                Text(
                    "Rapid flashing can strain the flash unit",
                    color = fg.copy(alpha = 0.5f),
                    fontSize = 11.sp
                )
            }
        }
    }

    if (!hasCamera) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0B0B0D))
                .clickable(enabled = false) {},
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(32.dp)
            ) {
                Icon(
                    AppIcons.FlashlightOff,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "Camera permission required",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "The flashlight uses the camera flash unit. Please allow the camera permission.",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    }
                }) {
                    Text("Open App Settings")
                }
                TextButton(onClick = onBack) { Text("Back", color = Color.White) }
            }
        }
    }
}
