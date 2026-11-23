package com.example.ui.screens.hid

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.ui.components.AppIcons
import com.example.util.BtHidController
import com.example.util.HidKeymap
import com.example.util.HidMacro
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

@Composable
fun HidTrackpad(
    connected: Boolean,
    sensitivity: Float,
    dragLock: Boolean,
    modifier: Modifier = Modifier,
    tapToClick: Boolean = true,
    naturalScroll: Boolean = false,
    haptics: Boolean = true,
    onMove: (dx: Int, dy: Int, buttons: Int) -> Unit,
    onScroll: (wheel: Int, pan: Int, buttons: Int) -> Unit,
    onClick: (button: Int) -> Unit,
    onToggleDrag: () -> Unit,
    onThreeFingerSwipe: (left: Boolean) -> Unit = {},
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val sensitivityState = rememberUpdatedState(sensitivity)
    val dragState = rememberUpdatedState(dragLock)
    val tapState = rememberUpdatedState(tapToClick)
    val naturalState = rememberUpdatedState(naturalScroll)
    val hapticState = rememberUpdatedState(haptics)
    val moveState = rememberUpdatedState(onMove)
    val scrollState = rememberUpdatedState(onScroll)
    val clickState = rememberUpdatedState(onClick)
    val toggleDragState = rememberUpdatedState(onToggleDrag)
    val swipeState = rememberUpdatedState(onThreeFingerSwipe)

    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(
                width = 1.dp,
                color = if (connected) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                else MaterialTheme.colorScheme.outlineVariant,
                shape = MaterialTheme.shapes.large
            )
            .pointerInput(connected) {
                if (!connected) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var lastX = down.position.x
                    var lastY = down.position.y
                    var lastTwoX = 0f
                    var lastTwoY = 0f
                    var travel = 0f
                    var maxPointers = 1
                    var active = true
                    var longPressFired = false
                    var accX = 0f
                    var accY = 0f
                    var scrollAccX = 0f
                    var scrollAccY = 0f
                    var lastCount = 1
                    var swipeStartX = down.position.x
                    var swipeStartY = down.position.y
                    val longPressJob = scope.launch {
                        delay(550)
                        if (active && travel < 16f && maxPointers == 1) {
                            longPressFired = true
                            if (hapticState.value) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                            toggleDragState.value()
                        }
                    }
                    while (active) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        maxPointers = maxOf(maxPointers, pressed.size)
                        val avgX = pressed.map { it.position.x }.average().toFloat()
                        val avgY = pressed.map { it.position.y }.average().toFloat()
                        if (pressed.size != lastCount) {
                            lastCount = pressed.size
                            lastTwoX = if (pressed.size >= 2) avgX else 0f
                            lastTwoY = if (pressed.size >= 2) avgY else 0f
                            lastX = pressed.first().position.x
                            lastY = pressed.first().position.y
                            swipeStartX = avgX
                            swipeStartY = avgY
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                            continue
                        }
                        if (pressed.size == 2) {
                            if (lastTwoX != 0f || lastTwoY != 0f) {
                                val dx = avgX - lastTwoX
                                val dy = avgY - lastTwoY
                                travel += abs(dx) + abs(dy)
                                scrollAccX += dx
                                scrollAccY += dy
                                var wheel = 0
                                var pan = 0
                                val step = 26f
                                while (scrollAccY <= -step) {
                                    scrollAccY += step
                                    wheel += 1
                                }
                                while (scrollAccY >= step) {
                                    scrollAccY -= step
                                    wheel -= 1
                                }
                                while (scrollAccX <= -step) {
                                    scrollAccX += step
                                    pan -= 1
                                }
                                while (scrollAccX >= step) {
                                    scrollAccX -= step
                                    pan += 1
                                }
                                if (naturalState.value) {
                                    wheel = -wheel
                                    pan = -pan
                                }
                                if (wheel != 0 || pan != 0) {
                                    scrollState.value(
                                        wheel.coerceIn(-8, 8),
                                        pan.coerceIn(-8, 8),
                                        if (dragState.value) 1 else 0
                                    )
                                }
                            }
                            lastTwoX = avgX
                            lastTwoY = avgY
                            lastX = pressed.first().position.x
                            lastY = pressed.first().position.y
                        } else if (pressed.size == 1) {
                            val position = pressed.first().position
                            val dx = position.x - lastX
                            val dy = position.y - lastY
                            lastX = position.x
                            lastY = position.y
                            lastTwoX = 0f
                            lastTwoY = 0f
                            travel += abs(dx) + abs(dy)
                            accX += dx * sensitivityState.value
                            accY += dy * sensitivityState.value
                            val ix = accX.toInt()
                            val iy = accY.toInt()
                            if (ix != 0 || iy != 0) {
                                accX -= ix
                                accY -= iy
                                moveState.value(ix.coerceIn(-127, 127), iy.coerceIn(-127, 127), if (dragState.value) 1 else 0)
                            }
                        } else {
                            travel += abs(avgX - lastX) + abs(avgY - lastY)
                            lastX = avgX
                            lastY = avgY
                        }
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                    active = false
                    longPressJob?.cancel()
                    if (travel < 16f && !longPressFired) {
                        if (dragState.value) {
                            toggleDragState.value()
                        } else if (tapState.value) {
                            when {
                                maxPointers >= 3 -> clickState.value(4)
                                maxPointers == 2 -> clickState.value(2)
                                else -> clickState.value(1)
                            }
                        }
                    } else if (maxPointers >= 3) {
                        val dx = lastX - swipeStartX
                        val dy = lastY - swipeStartY
                        if (abs(dx) > 80f && abs(dx) > abs(dy) * 1.6f) {
                            swipeState.value(dx < 0f)
                        }
                    }
                }
            }
    ) {
        if (!connected) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = AppIcons.Mouse,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Connect a computer to use the trackpad",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Text(
                text = "TRACKPAD",
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 10.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = if (dragLock) "Drag locked — tap to release"
                else "Tap left · 2-finger tap right · 3-finger tap middle · long-press drag",
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun HidMouseButtons(
    connected: Boolean,
    dragLock: Boolean,
    onLeft: () -> Unit,
    onMiddle: () -> Unit,
    onRight: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        HidMouseButton("Left", null, { onLeft() }, Modifier.weight(1f), connected && !dragLock)
        HidMouseButton("Mid", null, { onMiddle() }, Modifier.weight(1f), connected)
        HidMouseButton("Right", null, { onRight() }, Modifier.weight(1f), connected)
        HidMouseButton("Back", AppIcons.ArrowBack, { onBack() }, Modifier.weight(1f), connected)
        HidMouseButton("Fwd", AppIcons.ArrowForward, { onForward() }, Modifier.weight(1f), connected)
    }
}

@Composable
fun HidPointerExtras(
    connected: Boolean,
    dragLock: Boolean,
    onToggleDrag: () -> Unit,
    onScrollUp: () -> Unit,
    onScrollDown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        HidMouseButton(
            if (dragLock) "Drag on" else "Drag",
            null,
            onToggleDrag,
            Modifier.weight(1f),
            connected,
            active = dragLock,
        )
        HidMouseButton("Scroll ↑", AppIcons.ArrowUpward, onScrollUp, Modifier.weight(1f), connected)
        HidMouseButton("Scroll ↓", AppIcons.ArrowDownward, onScrollDown, Modifier.weight(1f), connected)
    }
}

@Composable
private fun HidMouseButton(
    label: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 4.dp),
        colors = if (active) {
            ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        } else {
            ButtonDefaults.filledTonalButtonColors()
        }
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(3.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun HidModifierChips(modifiers: Int, onToggle: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        ModifierChip("Ctrl", HidKeymap.MOD_CTRL, modifiers, onToggle)
        ModifierChip("Shift", HidKeymap.MOD_SHIFT, modifiers, onToggle)
        ModifierChip("Alt", HidKeymap.MOD_ALT, modifiers, onToggle)
        ModifierChip("Win", HidKeymap.MOD_GUI, modifiers, onToggle)
    }
}

@Composable
private fun ModifierChip(label: String, mask: Int, modifiers: Int, onToggle: (Int) -> Unit) {
    FilterChip(
        selected = modifiers and mask != 0,
        onClick = { onToggle(mask) },
        label = { Text(label, style = MaterialTheme.typography.labelSmall) }
    )
}

private data class PcKey(
    val label: String,
    val usage: Int,
    val modifiers: Int = 0,
    val weight: Float = 1f,
    val repeat: Boolean = false,
    val toggleModifier: Int = 0,
)

private val functionRow = listOf(
    PcKey("F1", HidKeymap.KEY_F1), PcKey("F2", HidKeymap.KEY_F2), PcKey("F3", HidKeymap.KEY_F3),
    PcKey("F4", HidKeymap.KEY_F4), PcKey("F5", HidKeymap.KEY_F5), PcKey("F6", HidKeymap.KEY_F6),
    PcKey("F7", HidKeymap.KEY_F7), PcKey("F8", HidKeymap.KEY_F8), PcKey("F9", HidKeymap.KEY_F9),
    PcKey("F10", HidKeymap.KEY_F10), PcKey("F11", HidKeymap.KEY_F11), PcKey("F12", HidKeymap.KEY_F12),
)

private val numberRow = listOf(
    PcKey("Esc", HidKeymap.KEY_ESC), PcKey("1", 0x1E), PcKey("2", 0x1F), PcKey("3", 0x20),
    PcKey("4", 0x21), PcKey("5", 0x22), PcKey("6", 0x23), PcKey("7", 0x24),
    PcKey("8", 0x25), PcKey("9", 0x26), PcKey("0", 0x27), PcKey("-", 0x2D),
    PcKey("=", 0x2E), PcKey("⌫", HidKeymap.KEY_BACKSPACE, weight = 1.3f, repeat = true),
)

private val qwertyRow = listOf(
    PcKey("Tab", HidKeymap.KEY_TAB, weight = 1.25f),
    PcKey("q", 0x14), PcKey("w", 0x1A), PcKey("e", 0x08), PcKey("r", 0x15),
    PcKey("t", 0x17), PcKey("y", 0x1C), PcKey("u", 0x18), PcKey("i", 0x0C),
    PcKey("o", 0x12), PcKey("p", 0x13), PcKey("[", 0x2F), PcKey("]", 0x30),
    PcKey("\\", 0x31, weight = 1.1f),
)

private val homeRow = listOf(
    PcKey("Caps", HidKeymap.KEY_CAPS_LOCK, weight = 1.3f),
    PcKey("a", 0x04), PcKey("s", 0x16), PcKey("d", 0x07), PcKey("f", 0x09),
    PcKey("g", 0x0A), PcKey("h", 0x0B), PcKey("j", 0x0D), PcKey("k", 0x0E),
    PcKey("l", 0x0F), PcKey(";", 0x33), PcKey("'", 0x34),
    PcKey("↵", HidKeymap.KEY_ENTER, weight = 1.7f),
)

private val bottomLettersRow = listOf(
    PcKey("Shift", 0, weight = 1.7f, toggleModifier = HidKeymap.MOD_SHIFT),
    PcKey("z", 0x1D), PcKey("x", 0x1B), PcKey("c", 0x06), PcKey("v", 0x19),
    PcKey("b", 0x05), PcKey("n", 0x11), PcKey("m", 0x10),
    PcKey(",", 0x36), PcKey(".", 0x37), PcKey("/", 0x38),
    PcKey("Shift", 0, weight = 1.7f, toggleModifier = HidKeymap.MOD_SHIFT),
)

private val controlRow = listOf(
    PcKey("Ctrl", 0, weight = 1.2f, toggleModifier = HidKeymap.MOD_CTRL),
    PcKey("Win", HidKeymap.KEY_LEFT_GUI, weight = 1.2f),
    PcKey("Alt", 0, weight = 1.2f, toggleModifier = HidKeymap.MOD_ALT),
    PcKey("Space", HidKeymap.KEY_SPACE, weight = 4.5f),
    PcKey("←", HidKeymap.KEY_LEFT, weight = 1f, repeat = true),
    PcKey("↑", HidKeymap.KEY_UP, weight = 1f, repeat = true),
    PcKey("↓", HidKeymap.KEY_DOWN, weight = 1f, repeat = true),
    PcKey("→", HidKeymap.KEY_RIGHT, weight = 1f, repeat = true),
)

@Composable
fun HidPcKeyboard(
    modifiers: Int,
    enabled: Boolean,
    onToggleModifier: (Int) -> Unit,
    onKey: (usage: Int, modifiers: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        PcKeyRow(functionRow, modifiers, enabled, onToggleModifier, onKey)
        PcKeyRow(numberRow, modifiers, enabled, onToggleModifier, onKey)
        PcKeyRow(qwertyRow, modifiers, enabled, onToggleModifier, onKey)
        PcKeyRow(homeRow, modifiers, enabled, onToggleModifier, onKey)
        PcKeyRow(bottomLettersRow, modifiers, enabled, onToggleModifier, onKey)
        PcKeyRow(controlRow, modifiers, enabled, onToggleModifier, onKey)
    }
}

@Composable
private fun PcKeyRow(
    keys: List<PcKey>,
    modifiers: Int,
    enabled: Boolean,
    onToggleModifier: (Int) -> Unit,
    onKey: (usage: Int, modifiers: Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp),
        horizontalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        keys.forEach { key ->
            PcKeyButton(
                key = key,
                selected = key.toggleModifier != 0 && modifiers and key.toggleModifier != 0,
                enabled = enabled,
                modifier = Modifier.weight(key.weight),
                onToggleModifier = onToggleModifier,
                onKey = onKey,
            )
        }
    }
}

@Composable
private fun PcKeyButton(
    key: PcKey,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onToggleModifier: (Int) -> Unit,
    onKey: (usage: Int, modifiers: Int) -> Unit,
) {
    val send = {
        if (key.toggleModifier != 0) {
            onToggleModifier(key.toggleModifier)
        } else {
            onKey(key.usage, key.modifiers)
        }
    }
    val sendState = rememberUpdatedState(send)
    val scope = rememberCoroutineScope()
    val base = modifier
        .height(44.dp)
        .clip(RoundedCornerShape(5.dp))
        .background(
            if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh
        )
        .border(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
            else MaterialTheme.colorScheme.outlineVariant,
            RoundedCornerShape(5.dp)
        )
        .semantics {
            role = Role.Button
            contentDescription = key.label
            onClick {
                if (enabled) sendState.value()
                true
            }
        }
    Box(
        modifier = if (enabled && key.repeat) {
            base.pointerInput(key.label, enabled) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    sendState.value()
                    var pressed = true
                    val repeatJob = scope.launch {
                        delay(420L)
                        while (pressed) {
                            sendState.value()
                            delay(70L)
                        }
                    }
                    while (pressed) {
                        val event = awaitPointerEvent()
                        if (event.changes.none { it.pressed }) pressed = false
                    }
                    repeatJob.cancel()
                }
            }
        } else {
            base.pointerInput(key.label, enabled) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (enabled) sendState.value()
                    var active = true
                    while (active) {
                        val event = awaitPointerEvent()
                        if (event.changes.none { it.pressed }) active = false
                    }
                }
            }
        },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = key.label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 13.sp),
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            maxLines = 1,
            overflow = TextOverflow.Clip
        )
    }
}

@Composable
fun HidSpecialKeys(
    enabled: Boolean,
    onKey: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SpecialKey("Esc", HidKeymap.KEY_ESC, enabled, onKey)
        SpecialKey("Tab", HidKeymap.KEY_TAB, enabled, onKey)
        SpecialKey("↵", HidKeymap.KEY_ENTER, enabled, onKey)
        SpecialKey("⌫", HidKeymap.KEY_BACKSPACE, enabled, onKey)
        SpecialKey("Del", HidKeymap.KEY_DELETE, enabled, onKey)
        SpecialKey("Ins", HidKeymap.KEY_INSERT, enabled, onKey)
        SpecialKey("Home", HidKeymap.KEY_HOME, enabled, onKey)
        SpecialKey("End", HidKeymap.KEY_END, enabled, onKey)
        SpecialKey("PgUp", HidKeymap.KEY_PAGE_UP, enabled, onKey)
        SpecialKey("PgDn", HidKeymap.KEY_PAGE_DOWN, enabled, onKey)
        SpecialKey("PrtSc", HidKeymap.KEY_PRINT_SCREEN, enabled, onKey)
    }
}

@Composable
private fun SpecialKey(label: String, usage: Int, enabled: Boolean, onKey: (Int) -> Unit) {
    TextButton(
        onClick = { onKey(usage) },
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        modifier = Modifier.height(32.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
fun HidMediaKeys(
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Playback", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MediaKey("⏮ Previous", HidKeymap.CONSUMER_PREVIOUS, enabled, Modifier.weight(1f))
            MediaKey("⏯ Play/Pause", HidKeymap.CONSUMER_PLAY_PAUSE, enabled, Modifier.weight(1f))
            MediaKey("Next ⏭", HidKeymap.CONSUMER_NEXT, enabled, Modifier.weight(1f))
        }
        MediaKey("⏹ Stop", HidKeymap.CONSUMER_STOP, enabled, Modifier.fillMaxWidth())
        Text("Volume", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MediaKey("Vol−", HidKeymap.CONSUMER_VOLUME_DOWN, enabled, Modifier.weight(1f))
            MediaKey("Mute", HidKeymap.CONSUMER_MUTE, enabled, Modifier.weight(1f))
            MediaKey("Vol+", HidKeymap.CONSUMER_VOLUME_UP, enabled, Modifier.weight(1f))
        }
        Text("Browser", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MediaKey("Web", HidKeymap.CONSUMER_BROWSER_HOME, enabled, Modifier.weight(1f))
            MediaKey("Back", HidKeymap.CONSUMER_BROWSER_BACK, enabled, Modifier.weight(1f))
            MediaKey("Forward", HidKeymap.CONSUMER_BROWSER_FORWARD, enabled, Modifier.weight(1f))
        }
        MediaKey("Reload", HidKeymap.CONSUMER_BROWSER_REFRESH, enabled, Modifier.fillMaxWidth())
    }
}

@Composable
private fun MediaKey(label: String, usage: Int, enabled: Boolean, modifier: Modifier = Modifier) {
    Surface(
        onClick = { BtHidController.sendConsumer(usage) },
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier.height(52.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
        }
    }
}

@Composable
fun HidMacroBar(
    macros: List<HidMacro>,
    enabled: Boolean,
    onRun: (HidMacro) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        macros.forEach { macro ->
            Surface(
                onClick = { onRun(macro) },
                enabled = enabled,
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            ) {
                Text(
                    text = macro.label,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
fun HidDictationButton(
    enabled: Boolean,
    onText: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val onTextState = rememberUpdatedState(onText)
    var listening by remember { mutableStateOf(false) }
    var lastError by remember { mutableStateOf<String?>(null) }
    val recognizer = remember {
        runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
    }
    val listener = remember {
        object : RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { listening = false }
            override fun onError(error: Int) {
                listening = false
                lastError = speechError(error)
            }
            override fun onResults(results: android.os.Bundle?) {
                listening = false
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim().orEmpty()
                if (text.isNotEmpty()) onTextState.value(text)
            }
            override fun onPartialResults(partialResults: android.os.Bundle?) {}
            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
        }
    }

    DisposableEffect(recognizer) {
        recognizer?.setRecognitionListener(listener)
        onDispose {
            runCatching { recognizer?.cancel() }
            runCatching { recognizer?.destroy() }
        }
    }

    fun toggle() {
        if (!enabled) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            showHidToast(context, "Microphone permission is required for dictation")
            return
        }
        val speech = recognizer
        if (speech == null) {
            showHidToast(context, "Speech recognition is not available on this device")
            return
        }
        lastError = null
        if (listening) {
            runCatching { speech.stopListening() }
            listening = false
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        listening = true
        runCatching { speech.startListening(intent) }.onFailure {
            listening = false
            lastError = it.message
        }
    }

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = { toggle() }, enabled = enabled) {
            Icon(
                imageVector = AppIcons.Mic,
                contentDescription = if (listening) "Stop dictation" else "Dictate text",
                tint = when {
                    !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    listening -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                }
            )
        }
        if (lastError != null) {
            Text(
                text = lastError!!,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun HidTextEntry(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onDone: () -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        enabled = enabled,
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            color = MaterialTheme.colorScheme.onSurface
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(
            onDone = {
                onDone()
                keyboardController?.show()
            }
        ),
        decorationBox = { inner ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant,
                        MaterialTheme.shapes.medium
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = if (enabled) "Type here…" else "Connect a computer first",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                inner()
            }
        }
    )
}

fun clipboardText(context: Context): String? = runCatching {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = manager.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    clip.getItemAt(0).coerceToText(context)?.toString()?.takeIf { it.isNotBlank() }
}.getOrNull()

internal fun showHidToast(context: Context, message: String) {
    runCatching { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
}

private fun speechError(error: Int): String = when (error) {
    SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
    SpeechRecognizer.ERROR_CLIENT -> "Recognizer client error"
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission needed"
    SpeechRecognizer.ERROR_NETWORK -> "Network error"
    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
    SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognised"
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer is busy"
    SpeechRecognizer.ERROR_SERVER -> "Recognizer server error"
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
    else -> "Speech recognition failed"
}

