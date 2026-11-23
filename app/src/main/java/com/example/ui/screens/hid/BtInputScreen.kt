package com.example.ui.screens.hid

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import com.example.ui.MainViewModel
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.service.HidOverlayController
import com.example.service.HidSessionService
import com.example.HidLockPadActivity
import com.example.ui.components.AppIcons
import com.example.ui.components.FloatingTopBar
import com.example.ui.components.PillTabRow
import com.example.util.BtHidController
import com.example.util.HidHost
import com.example.util.HidKeymap
import com.example.util.HidPrefs
import com.example.util.HidState
import com.example.util.HidStatus
import com.example.util.HidMacros
import com.example.util.HidMacro
import com.example.util.HidMacroStep
import com.example.util.HidTextDiff
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private const val KEY_SENSITIVITY = "sensitivity"

@Composable
fun BtInputScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val state by BtHidController.state.collectAsState()
    val mediaKeys by BtHidController.mediaKeys.collectAsState()

    val prefs = remember { HidPrefs.get(context) }
    var sensitivity by remember { mutableFloatStateOf(prefs.getFloat(HidPrefs.SENSITIVITY, 1.1f)) }
    var keepScreenOn by remember { mutableStateOf(prefs.getBoolean(HidPrefs.KEEP_SCREEN_ON, true)) }
    var leftHanded by remember { mutableStateOf(prefs.getBoolean(HidPrefs.LEFT_HANDED, false)) }
    var naturalScroll by remember { mutableStateOf(prefs.getBoolean(HidPrefs.NATURAL_SCROLL, false)) }
    var tapToClick by remember { mutableStateOf(prefs.getBoolean(HidPrefs.TAP_TO_CLICK, true)) }
    var haptics by remember { mutableStateOf(prefs.getBoolean(HidPrefs.HAPTICS, true)) }
    var showConnect by remember { mutableStateOf(false) }
    var showKeyboard by remember { mutableStateOf(false) }
    var showHidOverlay by remember { mutableStateOf(false) }
    var showHidLockPad by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var keyboardTab by remember { mutableStateOf("Keyboard") }
    var modifiers by remember { mutableIntStateOf(0) }
    var dragLock by remember { mutableStateOf(false) }
    var typedText by remember { mutableStateOf("") }
    var sentText by remember { mutableStateOf("") }
    var hosts by remember { mutableStateOf<List<HidHost>>(emptyList()) }
    var hostsTick by remember { mutableIntStateOf(0) }
    val focusRequester = remember { FocusRequester() }
    var macros by remember { mutableStateOf(HidMacros.load(prefs)) }
    var showMacroManager by remember { mutableStateOf(false) }
    var newMacroLabel by remember { mutableStateOf("") }
    var newMacroText by remember { mutableStateOf("") }
    val useGlassmorphism by viewModel.useGlassmorphism.collectAsState()

    val dragLockState = rememberUpdatedState(dragLock)
    val sensitivityState = rememberUpdatedState(sensitivity)
    val view = androidx.compose.ui.platform.LocalView.current

    DisposableEffect(keepScreenOn) {
        if (keepScreenOn) view.keepScreenOn = true else view.keepScreenOn = false
        onDispose { view.keepScreenOn = false }
    }

    LaunchedEffect(sensitivity) {
        prefs.edit().putFloat(HidPrefs.SENSITIVITY, sensitivity).apply()
    }
    LaunchedEffect(keepScreenOn) {
        prefs.edit().putBoolean(HidPrefs.KEEP_SCREEN_ON, keepScreenOn).apply()
    }
    LaunchedEffect(leftHanded) { prefs.edit().putBoolean(HidPrefs.LEFT_HANDED, leftHanded).apply() }
    LaunchedEffect(naturalScroll) { prefs.edit().putBoolean(HidPrefs.NATURAL_SCROLL, naturalScroll).apply() }
    LaunchedEffect(tapToClick) { prefs.edit().putBoolean(HidPrefs.TAP_TO_CLICK, tapToClick).apply() }
    LaunchedEffect(haptics) { prefs.edit().putBoolean(HidPrefs.HAPTICS, haptics).apply() }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = BtHidController.requiredPermissions().all { result[it] == true }
        if (granted) {
            BtHidController.start(context)
        } else {
            toast(context, "Bluetooth permission is required")
        }
    }

    fun requestPermissionsOrStart() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            BtHidController.start(context)
            return
        }
        if (!BtHidController.hasPermissions(context)) {
            val permissions = BtHidController.requiredPermissions().toMutableList()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            permissionLauncher.launch(permissions.toTypedArray())
        } else {
            BtHidController.start(context)
        }
    }

    fun openHidOverlay() {
        showHidOverlay = true
        HidOverlayController.show(context)
    }

    fun closeHidOverlay() {
        showHidOverlay = false
        HidOverlayController.hide(context)
    }

    fun openHidLockPad() {
        showHidLockPad = true
        HidLockPadActivity.start(context)
    }

    fun closeHidLockPad() {
        showHidLockPad = false
    }

    fun saveMacro() {
        val label = newMacroLabel.trim()
        val text = newMacroText
        if (label.isBlank() || text.isBlank()) return
        val macro = HidMacro(
            id = HidMacros.newId(),
            label = label,
            steps = listOf(HidMacroStep.Text(text)),
            builtIn = false,
        )
        macros = macros + macro
        HidMacros.save(prefs, macros)
        newMacroLabel = ""
        newMacroText = ""
    }

    fun deleteMacro(macro: HidMacro) {
        macros = macros.filterNot { it.id == macro.id }
        HidMacros.save(prefs, macros)
    }

    fun setDrag(on: Boolean) {
        dragLock = on
        BtHidController.setButtons(if (on) 1 else 0)
    }

    fun toggleModifier(mask: Int) {
        modifiers = modifiers xor mask
    }

    fun sendKey(usage: Int) {
        BtHidController.tapKey(usage, modifiers)
        modifiers = 0
    }

    fun sendTextDiff(old: String, new: String) {
        var common = 0
        val limit = minOf(old.length, new.length)
        while (common < limit && old[common] == new[common]) common++
        repeat(old.length - common) {
            BtHidController.tapKey(HidKeymap.KEY_BACKSPACE)
        }
        val added = new.substring(common)
        if (added.isNotEmpty()) {
            BtHidController.typeText(added, modifiers)
        }
        if (added.isNotEmpty() || old.length != common) modifiers = 0
        sentText = new
    }

    LaunchedEffect(Unit) {
        BtHidController.start(context)
    }

    LaunchedEffect(showConnect, hostsTick, state.registered) {
        if (showConnect) hosts = BtHidController.bondedHosts(context)
    }

    LaunchedEffect(state.registered) {
        if (state.registered) HidSessionService.start(context)
    }

    LaunchedEffect(state.connected) {
        if (!state.connected) {
            dragLock = false
            modifiers = 0
            showKeyboard = false
            keyboardController?.hide()
        }
    }

    LaunchedEffect(showKeyboard, state.connected) {
        if (showKeyboard && state.connected) {
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val status = BtHidController.state.value.status
                hostsTick++
                if ((status == HidStatus.BLUETOOTH_OFF || status == HidStatus.NEEDS_PERMISSION) &&
                    BtHidController.hasPermissions(context)
                ) {
                    BtHidController.start(context)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (showConnect) {
        ConnectionDialog(
            state = state,
            hosts = hosts,
            sensitivity = sensitivity,
            onSensitivityChange = { sensitivity = it },
            onDismiss = { showConnect = false },
            onPrepare = {
                requestPermissionsOrStart()
                BtHidController.register()
            },
            onStop = { BtHidController.unregister() },
            onDiscoverable = { BtHidController.requestDiscoverable(context) },
            onEnableBluetooth = {
                if (BtHidController.hasPermissions(context)) {
                    BtHidController.requestEnableBluetooth(context)
                } else {
                    requestPermissionsOrStart()
                }
            },
            onGrantPermission = { requestPermissionsOrStart() },
            onBluetoothSettings = {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            },
            onRefreshHosts = { hostsTick++ },
            onConnect = { host -> BtHidController.connect(host.address) },
            onDisconnect = { BtHidController.disconnect() },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(if (useGlassmorphism) MaterialTheme.colorScheme.surface.copy(alpha = 0.7f) else MaterialTheme.colorScheme.background)
    ) {
        FloatingTopBar(modifier = Modifier.fillMaxWidth(), useGlass = useGlassmorphism) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(
                        if (state.connected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline
                    )
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "BT Mouse & Keyboard",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = statusLabel(state),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = {
                hostsTick++
                showConnect = true
            }) {
                Icon(
                    imageVector = if (state.connected) AppIcons.BluetoothConnected else AppIcons.Bluetooth,
                    contentDescription = "Connection settings",
                    tint = if (state.connected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
                )
            }
            IconButton(onClick = { openHidOverlay() }) {
                Icon(
                    imageVector = AppIcons.Mouse,
                    contentDescription = "Show BT input overlay",
                    tint = if (showHidOverlay) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
                )
            }
            if (showHidOverlay) {
                IconButton(onClick = { closeHidOverlay() }) {
                    Icon(
                        imageVector = AppIcons.Close,
                        contentDescription = "Hide BT input overlay",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            IconButton(onClick = { openHidLockPad() }) {
                Icon(
                    imageVector = AppIcons.Lock,
                    contentDescription = "Show lock-screen BT pad",
                    tint = if (showHidLockPad) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
                )
            }
            IconButton(onClick = { showKeyboard = !showKeyboard }) {
                Icon(
                    imageVector = AppIcons.Keyboard,
                    contentDescription = if (showKeyboard) "Hide keyboard" else "Show keyboard",
                    tint = if (showKeyboard) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
                )
            }
            IconButton(onClick = { showSettings = true }) {
                Icon(
                    imageVector = AppIcons.Settings,
                    contentDescription = "BT input settings",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        if (!showKeyboard) {
            StatusCard(
                state = state,
                onEnableBluetooth = {
                    if (BtHidController.hasPermissions(context)) {
                        BtHidController.requestEnableBluetooth(context)
                    } else {
                        requestPermissionsOrStart()
                    }
                },
                onGrantPermission = { requestPermissionsOrStart() },
            )
        }

        if (showKeyboard) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.extraLarge
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = { showMacroManager = true },
                        enabled = state.connected
                    ) {
                        Icon(
                            imageVector = AppIcons.MoreVert,
                            contentDescription = "HID macros"
                        )
                    }
                    HidMacroBar(
                        macros = macros,
                        enabled = state.connected,
                        onRun = { BtHidController.runMacro(it) },
                        modifier = Modifier.fillMaxWidth()
                    )
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
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text("Media keys are off", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "Enable them in BT input settings. The HID descriptor changes, so the computer must be re-paired once.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Button(onClick = { BtHidController.setMediaKeys(true) }) {
                                        Text("Enable media keys")
                                    }
                                }
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            HidModifierChips(
                                modifiers = modifiers,
                                onToggle = { modifiers = modifiers xor it }
                            )
                            Spacer(Modifier.width(4.dp))
                            IconButton(
                                onClick = {
                                    val text = clipboardText(context)
                                    if (text != null) {
                                        BtHidController.typeText(text)
                                        val unsupported = HidKeymap.unsupportedChars(text)
                                        if (unsupported.isNotEmpty()) {
                                            showHidToast(context, "Typed closest match; unsupported: ${unsupported.take(8)}")
                                        }
                                    }
                                },
                                enabled = state.connected
                            ) {
                                Icon(
                                    imageVector = AppIcons.ContentPaste,
                                    contentDescription = "Paste clipboard"
                                )
                            }
                            HidDictationButton(
                                enabled = state.connected,
                                onText = { text ->
                                    if (text.isNotBlank()) {
                                        val edit = HidTextDiff.compute(sentText, text)
                                        repeat(edit.backspaces) {
                                            BtHidController.tapKey(HidKeymap.KEY_BACKSPACE)
                                        }
                                        if (edit.insert.isNotEmpty()) {
                                            BtHidController.typeText(edit.insert, modifiers)
                                        }
                                        modifiers = 0
                                        sentText = text
                                        typedText = text
                                    }
                                }
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
                            onDone = { sendKey(HidKeymap.KEY_ENTER) }
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
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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
            }
        }
    if (showSettings) {
        HidSettingsDialog(
            keepScreenOn = keepScreenOn,
            onKeepScreenOnChange = { keepScreenOn = it },
            leftHanded = leftHanded,
            onLeftHandedChange = { leftHanded = it },
            naturalScroll = naturalScroll,
            onNaturalScrollChange = { naturalScroll = it },
            tapToClick = tapToClick,
            onTapToClickChange = { tapToClick = it },
            haptics = haptics,
            onHapticsChange = { haptics = it },
            mediaKeys = mediaKeys,
            onMediaKeysChange = { BtHidController.setMediaKeys(it) },
            onDismiss = { showSettings = false },
        )
    }
    if (showMacroManager) {
        AlertDialog(
            onDismissRequest = { showMacroManager = false },
            title = { Text("HID macros") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = newMacroLabel,
                        onValueChange = { newMacroLabel = it },
                        label = { Text("Macro name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newMacroText,
                        onValueChange = { newMacroText = it },
                        label = { Text("Text") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    val custom = macros.filterNot { it.builtIn }
                    if (custom.isNotEmpty()) {
                        Text("Custom macros", style = MaterialTheme.typography.titleSmall)
                        custom.forEach { macro ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = macro.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { deleteMacro(macro) }) {
                                    Icon(AppIcons.Delete, contentDescription = "Delete ${macro.label}")
                                }
                            }
                        }
                    }
                    Text(
                        text = "Custom macros send their text as keystrokes. Built-in chord macros remain available.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { saveMacro() }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showMacroManager = false }) { Text("Close") }
            }
        )
    }
    }
}



@Composable
private fun HidSettingsDialog(
    keepScreenOn: Boolean,
    onKeepScreenOnChange: (Boolean) -> Unit,
    leftHanded: Boolean,
    onLeftHandedChange: (Boolean) -> Unit,
    naturalScroll: Boolean,
    onNaturalScrollChange: (Boolean) -> Unit,
    tapToClick: Boolean,
    onTapToClickChange: (Boolean) -> Unit,
    haptics: Boolean,
    onHapticsChange: (Boolean) -> Unit,
    mediaKeys: Boolean,
    onMediaKeysChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("BT input settings") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                HidSettingSwitch(
                    label = "Keep screen on",
                    description = "Prevent the display from sleeping while this screen is open.",
                    checked = keepScreenOn,
                    onCheckedChange = onKeepScreenOnChange,
                )
                HidSettingSwitch(
                    label = "Left-handed buttons",
                    description = "Swap the primary and secondary mouse buttons.",
                    checked = leftHanded,
                    onCheckedChange = onLeftHandedChange,
                )
                HidSettingSwitch(
                    label = "Natural scrolling",
                    description = "Move content in the same direction as your fingers.",
                    checked = naturalScroll,
                    onCheckedChange = onNaturalScrollChange,
                )
                HidSettingSwitch(
                    label = "Tap to click",
                    description = "A short tap on the trackpad sends a left click.",
                    checked = tapToClick,
                    onCheckedChange = onTapToClickChange,
                )
                HidSettingSwitch(
                    label = "Haptics",
                    description = "Vibrate for taps, drag lock, and modifier changes.",
                    checked = haptics,
                    onCheckedChange = onHapticsChange,
                )
                HidSettingSwitch(
                    label = "Media keys",
                    description = "Adds play, volume and browser keys. Changing this requires re-pairing the computer.",
                    checked = mediaKeys,
                    onCheckedChange = onMediaKeysChange,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

@Composable
private fun HidSettingSwitch(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun StatusCard(
    state: HidState,
    onEnableBluetooth: () -> Unit,
    onGrantPermission: () -> Unit,
) {
    val isError = state.status == HidStatus.ERROR || state.status == HidStatus.UNSUPPORTED
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
        color = if (isError) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (state.connected) AppIcons.BluetoothConnected else AppIcons.Bluetooth,
                contentDescription = null,
                tint = if (isError) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = statusLabel(state),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isError) MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.onSurface
                )
                if (state.status == HidStatus.ERROR && state.message != null) {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            when (state.status) {
                HidStatus.BLUETOOTH_OFF -> TextButton(onClick = onEnableBluetooth) { Text("Turn on") }
                HidStatus.NEEDS_PERMISSION -> TextButton(onClick = onGrantPermission) { Text("Allow") }
                else -> Unit
            }
        }
    }
}

@Composable
private fun ConnectionDialog(
    state: HidState,
    hosts: List<HidHost>,
    sensitivity: Float,
    onSensitivityChange: (Float) -> Unit,
    onDismiss: () -> Unit,
    onPrepare: () -> Unit,
    onStop: () -> Unit,
    onDiscoverable: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onGrantPermission: () -> Unit,
    onBluetoothSettings: () -> Unit,
    onRefreshHosts: () -> Unit,
    onConnect: (HidHost) -> Unit,
    onDisconnect: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bluetooth connection") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Prepare the phone as a Bluetooth mouse and keyboard, then pair it from your computer. Once connected you can switch apps or lock the phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Text(statusLabel(state), style = MaterialTheme.typography.bodyMedium)
                        if (state.message != null) {
                            Text(
                                text = state.message,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onPrepare,
                        modifier = Modifier.weight(1f),
                        enabled = state.status != HidStatus.UNSUPPORTED && !state.connected
                    ) {
                        Text(if (state.registered) "Re-register" else "Prepare HID")
                    }
                    OutlinedButton(
                        onClick = onStop,
                        modifier = Modifier.weight(1f),
                        enabled = state.registered
                    ) {
                        Text("Stop HID")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onDiscoverable,
                        modifier = Modifier.weight(1f),
                        enabled = state.registered
                    ) {
                        Text("Make discoverable")
                    }
                    OutlinedButton(
                        onClick = onBluetoothSettings,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("BT settings")
                    }
                }
                if (state.status == HidStatus.BLUETOOTH_OFF) {
                    Button(onClick = onEnableBluetooth, modifier = Modifier.fillMaxWidth()) {
                        Text("Turn on Bluetooth")
                    }
                }
                if (state.status == HidStatus.NEEDS_PERMISSION) {
                    Button(onClick = onGrantPermission, modifier = Modifier.fillMaxWidth()) {
                        Text("Allow Nearby devices")
                    }
                }
                if (state.connected) {
                    OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) {
                        Text("Disconnect ${state.hostName ?: "computer"}")
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Paired computers",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onRefreshHosts) {
                        Icon(AppIcons.Refresh, contentDescription = "Refresh paired computers")
                    }
                }
                if (hosts.isEmpty()) {
                    Text(
                        text = "No paired computers found. Pair from Bluetooth settings, or make this phone discoverable and add it on your PC.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    hosts.forEach { host ->
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceContainer
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = host.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = host.address,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                TextButton(
                                    onClick = { onConnect(host) },
                                    enabled = state.registered && !state.connected
                                ) {
                                    Text("Connect")
                                }
                            }
                        }
                    }
                }
                Text(
                    text = "Trackpad speed",
                    style = MaterialTheme.typography.titleSmall
                )
                Slider(
                    value = sensitivity,
                    onValueChange = onSensitivityChange,
                    valueRange = 0.5f..2.5f
                )
                Text(
                    text = "${(sensitivity * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Bluetooth HID requires Android 9 or newer. If this phone's Bluetooth stack rejects the HID role, the status above will say so.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

@Composable
private fun ModifierChip(
    label: String,
    mask: Int,
    modifiers: Int,
    onToggle: (Int) -> Unit,
) {
    FilterChip(
        selected = modifiers and mask != 0,
        onClick = { onToggle(mask) },
        label = { Text(label) }
    )
}

@Composable
private fun KeyButton(
    label: String,
    usage: Int,
    onSend: (Int) -> Unit,
) {
    TextButton(
        onClick = { onSend(usage) },
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        modifier = Modifier.height(36.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable
private fun MouseActionButton(
    label: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 8.dp),
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
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun statusLabel(state: HidState): String = when (state.status) {
    HidStatus.UNSUPPORTED -> "Android 9 or newer required"
    HidStatus.BLUETOOTH_OFF -> "Bluetooth is off"
    HidStatus.NEEDS_PERMISSION -> "Bluetooth permission needed"
    HidStatus.IDLE -> "Not connected"
    HidStatus.REGISTERING -> "Preparing Bluetooth HID…"
    HidStatus.READY -> "Ready — connect a computer"
    HidStatus.CONNECTING -> "Connecting…"
    HidStatus.CONNECTED -> state.hostName ?: "Connected"
    HidStatus.ERROR -> "Bluetooth HID unavailable"
}

private fun toast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
