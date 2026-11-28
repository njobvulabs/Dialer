package com.example.ui.screens.hid

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ui.components.AppIcons
import com.example.ui.components.PillTabRow
import com.example.util.BtHidController
import com.example.util.HidKeymap
import com.example.util.HidMacros
import com.example.util.HidPrefs
import com.example.util.HidTextDiff

@Composable
fun HidPadScreen(
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
    onDrag: ((Float, Float) -> Unit)? = null,
    title: String = "HID pad",
) {
    val context = LocalContext.current
    val state by BtHidController.state.collectAsState()
    val mediaKeys by BtHidController.mediaKeys.collectAsState()
    val prefs = remember { HidPrefs.get(context) }
    var sensitivity by remember { mutableFloatStateOf(prefs.getFloat(HidPrefs.SENSITIVITY, 1.1f)) }
    val naturalScroll = prefs.getBoolean(HidPrefs.NATURAL_SCROLL, false)
    val tapToClick = prefs.getBoolean(HidPrefs.TAP_TO_CLICK, true)
    val haptics = prefs.getBoolean(HidPrefs.HAPTICS, true)
    val leftHanded = prefs.getBoolean(HidPrefs.LEFT_HANDED, false)
    var dragLock by remember { mutableStateOf(false) }
    var modifiers by remember { mutableIntStateOf(0) }
    var showKeyboard by remember { mutableStateOf(false) }
    var keyboardTab by remember { mutableStateOf("Keyboard") }
    var typedText by remember { mutableStateOf("") }
    var sentText by remember { mutableStateOf("") }
    val macros = remember { HidMacros.load(prefs) }

    fun setDrag(on: Boolean) {
        dragLock = on
        BtHidController.setButtons(if (on) 1 else 0)
    }

    fun sendKey(usage: Int) {
        BtHidController.tapKey(usage, modifiers)
        modifiers = 0
    }

    fun typeNow(text: String) {
        if (text.isBlank()) return
        BtHidController.typeText(text)
        typedText = text
        sentText = text
    }

    fun pasteClipboard() {
        val text = clipboardText(context)
        if (text == null) {
            showHidToast(context, "Clipboard is empty")
            return
        }
        BtHidController.typeText(text)
        val unsupported = HidKeymap.unsupportedChars(text)
        if (unsupported.isNotEmpty()) {
            showHidToast(context, "Typed closest match; unsupported: ${unsupported.take(8)}")
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.background,
        tonalElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onDrag != null) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .pointerInput(Unit) {
                                detectDragGestures { change, drag ->
                                    change.consume()
                                    onDrag.invoke(drag.x, drag.y)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = AppIcons.MoreVert,
                            contentDescription = "Drag pad",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                }
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (state.connected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline
                        )
                )
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (state.connected) "Connected · tap, drag, or type"
                        else "Connect from NJ-Dialer → BT Mouse & Keyboard",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = { showKeyboard = !showKeyboard }) {
                    Icon(
                        imageVector = AppIcons.Keyboard,
                        contentDescription = if (showKeyboard) "Hide keys" else "Show keys",
                        tint = if (showKeyboard) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface
                    )
                }
                if (onClose != null) {
                    IconButton(onClick = onClose) {
                        Icon(AppIcons.Close, contentDescription = "Close pad")
                    }
                }
            }

            HidTrackpad(
                connected = state.connected,
                sensitivity = sensitivity,
                dragLock = dragLock,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .heightIn(min = 120.dp),
                tapToClick = tapToClick,
                naturalScroll = naturalScroll,
                haptics = haptics,
                onMove = { dx, dy, buttons -> BtHidController.moveMouse(dx, dy, buttons) },
                onScroll = { wheel, pan, buttons -> BtHidController.scroll(wheel, pan, buttons) },
                onClick = { BtHidController.click(it) },
                onToggleDrag = { setDrag(!dragLock) },
                onThreeFingerSwipe = { left ->
                    if (left) BtHidController.tapKey(HidKeymap.KEY_LEFT, HidKeymap.MOD_ALT)
                    else BtHidController.tapKey(HidKeymap.KEY_RIGHT, HidKeymap.MOD_ALT)
                }
            )

            HidMouseButtons(
                connected = state.connected,
                dragLock = dragLock,
                onLeft = { BtHidController.click(if (leftHanded) 2 else 1) },
                onMiddle = { BtHidController.click(4) },
                onRight = { BtHidController.click(if (leftHanded) 1 else 2) },
                onBack = { BtHidController.click(8) },
                onForward = { BtHidController.click(16) },
            )
            HidPointerExtras(
                connected = state.connected,
                dragLock = dragLock,
                onToggleDrag = { setDrag(!dragLock) },
                onScrollUp = { BtHidController.scroll(3) },
                onScrollDown = { BtHidController.scroll(-3) },
            )

            HidMacroBar(
                macros = macros,
                enabled = state.connected,
                onRun = { BtHidController.runMacro(it) },
            )

            if (showKeyboard) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 340.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    PillTabRow(
                        tabs = listOf("Keyboard", "Media"),
                        selected = keyboardTab,
                        onSelect = { keyboardTab = it },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (keyboardTab == "Media") {
                        if (mediaKeys) {
                            HidMediaKeys(enabled = state.connected)
                        } else {
                            Surface(
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surfaceContainerHigh
                            ) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text("Media keys are off", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "Enable them from BT Mouse & Keyboard → Settings. The computer must be re-paired once.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    TextButton(onClick = { BtHidController.setMediaKeys(true) }) {
                                        Text("Enable media keys")
                                    }
                                }
                            }
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            HidModifierChips(modifiers = modifiers, onToggle = { modifiers = modifiers xor it })
                            Spacer(Modifier.width(4.dp))
                            IconButton(onClick = { pasteClipboard() }, enabled = state.connected) {
                                Icon(
                                    imageVector = AppIcons.ContentPaste,
                                    contentDescription = "Paste clipboard"
                                )
                            }
                            HidDictationButton(
                                enabled = state.connected,
                                onText = { typeNow(it) },
                            )
                        }
                        HidTextEntry(
                            value = typedText,
                            onValueChange = { new ->
                                if (new != typedText) {
                                    val edit = HidTextDiff.compute(sentText, new)
                                    repeat(edit.backspaces) {
                                        BtHidController.tapKey(HidKeymap.KEY_BACKSPACE)
                                    }
                                    if (edit.insert.isNotEmpty()) {
                                        BtHidController.typeText(edit.insert, modifiers)
                                    }
                                    modifiers = 0
                                    sentText = new
                                    typedText = new
                                }
                            },
                            enabled = state.connected,
                            onDone = {
                                sendKey(HidKeymap.KEY_ENTER)
                                typedText = ""
                                sentText = ""
                            }
                        )
                        HidSpecialKeys(enabled = state.connected, onKey = { sendKey(it) })
                        HidPcKeyboard(
                            modifiers = modifiers,
                            enabled = state.connected,
                            onToggleModifier = { modifiers = modifiers xor it },
                            onKey = { usage, mods ->
                                BtHidController.tapKey(usage, modifiers or mods)
                                modifiers = 0
                            }
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = if (mediaKeys) "Media keys on" else "Media keys off",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${(sensitivity * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(
                    onClick = {
                        sensitivity = (sensitivity + 0.1f).let { if (it > 2.5f) 0.5f else it }
                        prefs.edit().putFloat(HidPrefs.SENSITIVITY, sensitivity).apply()
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = AppIcons.Refresh,
                        contentDescription = "Cycle trackpad speed",
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
