package com.example.ui.screens.vnc

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.ui.components.AppIcons
import com.example.ui.components.AppPrimaryButton
import com.example.ui.components.AppSecondaryButton
import com.example.ui.components.FloatingTopBar
import com.example.ui.MainViewModel
import kotlin.math.roundToInt

@Composable
fun VncScreen(onClose: () -> Unit, viewModel: MainViewModel) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val clipboardManager = remember {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }

    var state by remember { mutableStateOf(VncConnectionState.IDLE) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var frameTick by remember { mutableIntStateOf(0) }
    var hostInput by remember { mutableStateOf("") }
    var portInput by remember { mutableStateOf("5900") }
    var profileName by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var saveConnection by remember { mutableStateOf(true) }
    var savePassword by remember { mutableStateOf(false) }
    var savedHosts by remember { mutableStateOf(VncPrefs.load(context)) }
    var rightClickMode by remember { mutableStateOf(false) }
    var inputMode by remember { mutableStateOf(VncInputMode.DIRECT) }
    var fitMode by remember { mutableStateOf(VncFitMode.FIT) }
    var dragLock by remember { mutableStateOf(false) }
    val useGlassmorphism by viewModel.useGlassmorphism.collectAsState()
    var showKeys by remember { mutableStateOf(false) }
    var showText by remember { mutableStateOf(false) }
    var resetToken by remember { mutableIntStateOf(0) }
    val hardwareKeys = remember { mutableMapOf<Long, Int>() }
    val focusRequester = remember { FocusRequester() }

    val client = remember {
        VncClient(
            object : VncClient.Listener {
                override fun onState(newState: VncConnectionState, message: String?) {
                    state = newState
                    statusMessage = message
                    when (newState) {
                        VncConnectionState.CONNECTED -> {
                            error = null
                        }
                        VncConnectionState.ERROR -> {
                            error = message
                        }
                        VncConnectionState.DISCONNECTED -> {
                            frame = null
                            if (message.isNullOrBlank()) {
                                error = "Connection closed"
                            }
                        }
                        else -> Unit
                    }
                }

                override fun onFrame(bitmap: Bitmap) {
                    frame = bitmap
                    frameTick++
                }

                override fun onClipboard(text: String) {
                    clipboardManager.setPrimaryClip(ClipData.newPlainText("VNC clipboard", text))
                    Toast.makeText(context, "Clipboard received", Toast.LENGTH_SHORT).show()
                }

                override fun onBell() {
                    Toast.makeText(context, "Remote bell", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    DisposableEffect(Unit) {
        onDispose { client.close() }
    }

    BackHandler { onClose() }

    LaunchedEffect(state) {
        if (state == VncConnectionState.CONNECTED) {
            focusRequester.requestFocus()
        }
    }

    fun connect() {
        val target = VncAddress.parse(hostInput, portInput)
        if (target == null) {
            error = "Enter a valid host and port."
            return
        }
        keyboardController?.hide()
        error = null
        frame = null
        state = VncConnectionState.CONNECTING
        statusMessage = "Connecting to ${target.host}:${target.port}"
        if (saveConnection) {
            savedHosts = VncPrefs.upsert(
                context,
                VncSavedHost(
                    name = profileName.trim().ifEmpty { target.host },
                    host = target.host,
                    port = target.port,
                    password = if (savePassword) password else ""
                )
            )
        }
        client.connect(target.host, target.port, password)
    }

    fun disconnect() {
        client.close()
        state = VncConnectionState.IDLE
        statusMessage = null
        error = null
        frame = null
    }

    fun pasteClipboard() {
        val text = clipboardManager.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
        if (text.isNotEmpty()) client.sendClientCutText(text)
    }

    fun handleKeyEvent(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
        if (state != VncConnectionState.CONNECTED) return false
        val native = event.nativeKeyEvent
        val key = event.key
        val down = event.type == KeyEventType.KeyDown
        if (down) {
            val keysym = VncKeys.keysymForHardware(
                key = key,
                unicodeChar = native.unicodeChar,
                ctrlOrMeta = native.isCtrlPressed || native.isMetaPressed
            ) ?: return false
            val firstPress = !hardwareKeys.containsKey(key.keyCode)
            hardwareKeys[key.keyCode] = keysym
            if (firstPress) client.sendKey(keysym, true)
            return true
        }

        val keysym = hardwareKeys.remove(key.keyCode) ?: return false
        client.sendKey(keysym, false)
        return true
    }

    val status = when (state) {
        VncConnectionState.IDLE -> "Not connected"
        VncConnectionState.CONNECTING -> statusMessage ?: "Connecting..."
        VncConnectionState.CONNECTED -> statusMessage ?: "Connected"
        VncConnectionState.ERROR -> error ?: "Connection failed"
        VncConnectionState.DISCONNECTED -> "Disconnected"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { handleKeyEvent(it) }
    ) {
        FloatingTopBar(useGlass = useGlassmorphism) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "VNC Viewer",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
                )
            }
            if (state == VncConnectionState.CONNECTED ||
                state == VncConnectionState.CONNECTING
            ) {
                IconButton(onClick = ::disconnect) {
                    Icon(AppIcons.Stop, contentDescription = "Disconnect")
                }
            }
        }

        when {
            state == VncConnectionState.CONNECTING && frame == null -> {
                ConnectionProgress(
                    title = "Connecting",
                    message = statusMessage ?: "Opening the RFB connection…",
                    onCancel = ::disconnect
                )
            }
            state == VncConnectionState.CONNECTED && frame == null -> {
                ConnectionProgress(
                    title = "Connected",
                    message = "Waiting for the first framebuffer update…",
                    onCancel = ::disconnect
                )
            }
            state == VncConnectionState.CONNECTED && frame != null -> {
                VncViewport(
                    bitmap = frame!!,
                    frameTick = frameTick,
                    rightClickMode = rightClickMode,
                    resetToken = resetToken,
                    onPointer = { mask, x, y -> client.sendPointer(mask, x, y) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
                VncControls(
                    rightClickMode = rightClickMode,
                    onToggleRightClick = { rightClickMode = !rightClickMode },
                    onWheelUp = { client.sendWheel(true) },
                    onWheelDown = { client.sendWheel(false) },
                    onKeys = { showKeys = true },
                    onText = { showText = true },
                    onPaste = ::pasteClipboard,
                    onResetZoom = { resetToken++ },
                    dragLock = dragLock,
                    onToggleDragLock = { dragLock = !dragLock },
                    viewModel = viewModel
                )
            }
            else -> {
                VncConnectForm(
                    host = hostInput,
                    onHostChange = { hostInput = it },
                    port = portInput,
                    onPortChange = { portInput = it.filter(Char::isDigit).take(5) },
                    name = profileName,
                    onNameChange = { profileName = it },
                    password = password,
                    onPasswordChange = { password = it },
                    showPassword = showPassword,
                    onToggleShowPassword = { showPassword = !showPassword },
                    saveConnection = saveConnection,
                    onSaveConnectionChange = { saveConnection = it },
                    savePassword = savePassword,
                    onSavePasswordChange = { savePassword = it },
                    error = error ?: statusMessage.takeIf {
                        state == VncConnectionState.DISCONNECTED
                    },
                    savedHosts = savedHosts,
                    onSelectSaved = { saved ->
                        hostInput = saved.host
                        portInput = saved.port.toString()
                        profileName = saved.name
                        password = saved.password
                        savePassword = saved.password.isNotEmpty()
                        saveConnection = true
                        error = null
                    },
                    onDeleteSaved = { saved ->
                        savedHosts = VncPrefs.remove(context, saved)
                    },
                    onConnect = ::connect
                )
            }
        }
    }

    if (showKeys) {
        VncKeysDialog(
            onDismiss = { showKeys = false },
            onKey = { keysym, down -> client.sendKey(keysym, down) }
        )
    }

    if (showText) {
        VncTextDialog(
            onDismiss = { showText = false },
            onSend = { text ->
                text.forEach { char ->
                    val keysym = VncKeys.keysymForChar(char)
                    client.sendKey(keysym, true)
                    client.sendKey(keysym, false)
                }
            }
        )
    }
}

@Composable
private fun ConnectionProgress(title: String, message: String, onCancel: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        Spacer(Modifier.height(16.dp))
        AppSecondaryButton(text = "Cancel", onClick = onCancel)
    }
}

@Composable
private fun VncConnectForm(
    host: String,
    onHostChange: (String) -> Unit,
    port: String,
    onPortChange: (String) -> Unit,
    name: String,
    onNameChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    showPassword: Boolean,
    onToggleShowPassword: () -> Unit,
    saveConnection: Boolean,
    onSaveConnectionChange: (Boolean) -> Unit,
    savePassword: Boolean,
    onSavePasswordChange: (Boolean) -> Unit,
    error: String?,
    savedHosts: List<VncSavedHost>,
    onSelectSaved: (VncSavedHost) -> Unit,
    onDeleteSaved: (VncSavedHost) -> Unit,
    onConnect: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Remote desktop",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Connect to a VNC server over your local network or the internet. " +
                        "Raw VNC is unencrypted, so use a trusted network.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = onHostChange,
                        label = { Text("Host") },
                        placeholder = { Text("192.168.1.10") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Next
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = onPortChange,
                        label = { Text("Port") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Next
                        ),
                        modifier = Modifier.width(96.dp)
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text("Name (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = if (showPassword) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    trailingIcon = {
                        IconButton(onClick = onToggleShowPassword) {
                            Icon(
                                if (showPassword) AppIcons.VisibilityOff else AppIcons.Visibility,
                                contentDescription = if (showPassword) "Hide password" else "Show password"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                CheckRow(
                    checked = saveConnection,
                    onCheckedChange = onSaveConnectionChange,
                    label = "Save this connection"
                )
                CheckRow(
                    checked = savePassword,
                    onCheckedChange = onSavePasswordChange,
                    label = "Save password (encrypted on this device)"
                )
                if (error != null) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
                AppPrimaryButton(
                    text = "Connect",
                    onClick = onConnect,
                    icon = AppIcons.PlayArrow,
                    enabled = host.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        if (savedHosts.isNotEmpty()) {
            Text(
                "Saved hosts",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            savedHosts.forEach { saved ->
                Surface(
                    onClick = { onSelectSaved(saved) },
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                saved.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "${saved.host}:${saved.port}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f)
                            )
                        }
                        IconButton(onClick = { onDeleteSaved(saved) }) {
                            Icon(AppIcons.Delete, contentDescription = "Delete saved host")
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun CheckRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clipToBounds(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.material3.Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(end = 8.dp)
        )
    }
}

@Composable
private fun VncControls(
    rightClickMode: Boolean,
    onToggleRightClick: () -> Unit,
    onWheelUp: () -> Unit,
    onWheelDown: () -> Unit,
    onKeys: () -> Unit,
    onText: () -> Unit,
    onPaste: () -> Unit,
    onResetZoom: () -> Unit,
    dragLock: Boolean,
    onToggleDragLock: () -> Unit,
    viewModel: MainViewModel
) {
    val useGlassmorphism by viewModel.useGlassmorphism.collectAsState()
    Surface(
        color = if (useGlassmorphism) MaterialTheme.colorScheme.surface.copy(alpha = 0.7f) else MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            FilterChip(
                selected = rightClickMode,
                onClick = onToggleRightClick,
                label = { Text("Right tap") },
                leadingIcon = { Icon(AppIcons.Mouse, contentDescription = null, modifier = Modifier.size(18.dp)) }
            )
            FilterChip(
                selected = useGlassmorphism,
                onClick = { viewModel.setUseGlassmorphism(!useGlassmorphism) },
                label = { Text("Glassmorphism") }
            )
            FilterChip(
                selected = dragLock,
                onClick = onToggleDragLock,
                label = { Text("Drag lock") }
            )
            IconButton(onClick = onWheelUp) {
                Icon(AppIcons.KeyboardArrowUp, contentDescription = "Scroll up")
            }
            IconButton(onClick = onWheelDown) {
                Icon(AppIcons.KeyboardArrowDown, contentDescription = "Scroll down")
            }
            IconButton(onClick = onKeys) {
                Icon(AppIcons.Keyboard, contentDescription = "Special keys")
            }
            IconButton(onClick = onText) {
                Icon(AppIcons.Edit, contentDescription = "Send text")
            }
            IconButton(onClick = onPaste) {
                Icon(AppIcons.ContentPaste, contentDescription = "Paste clipboard")
            }
            IconButton(onClick = onResetZoom) {
                Icon(AppIcons.ZoomOut, contentDescription = "Reset zoom")
            }
        }
    }
}

@Composable
private fun VncViewport(
    bitmap: Bitmap,
    frameTick: Int,
    rightClickMode: Boolean,
    resetToken: Int,
    onPointer: (Int, Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(bitmap.width, bitmap.height, resetToken) {
        zoom = 1f
        pan = Offset.Zero
    }

    val image = remember(bitmap, frameTick) { bitmap.asImageBitmap() }

    Canvas(
        modifier = modifier
            .background(Color.Black)
            .clipToBounds()
            .pointerInput(bitmap.width, bitmap.height) {
                val bmpWidth = bitmap.width.toFloat()
                val bmpHeight = bitmap.height.toFloat()

                fun fitForSize(): Float {
                    return minOf(size.width / bmpWidth, size.height / bmpHeight)
                        .coerceAtLeast(0.0001f)
                }

                fun mapToRemote(position: Offset): IntOffset {
                    val scale = fitForSize() * zoom
                    val drawWidth = bmpWidth * scale
                    val drawHeight = bmpHeight * scale
                    val left = (size.width - drawWidth) / 2f + pan.x
                    val top = (size.height - drawHeight) / 2f + pan.y
                    val x = ((position.x - left) / scale).toInt()
                        .coerceIn(0, bitmap.width - 1)
                    val y = ((position.y - top) / scale).toInt()
                        .coerceIn(0, bitmap.height - 1)
                    return IntOffset(x, y)
                }

                fun clampPan(candidate: Offset): Offset {
                    val scale = fitForSize() * zoom
                    val maxX = maxOf(0f, (bmpWidth * scale - size.width) / 2f)
                    val maxY = maxOf(0f, (bmpHeight * scale - size.height) / 2f)
                    return Offset(
                        candidate.x.coerceIn(-maxX, maxX),
                        candidate.y.coerceIn(-maxY, maxY)
                    )
                }

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var lastPosition = down.position
                    val downPosition = down.position
                    var leftDown = false
                    var multiTouch = false
                    var previousCentroid: Offset? = null
                    var previousDistance = 0f

                    val initial = mapToRemote(down.position)
                    onPointer(0, initial.x, initial.y)

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) {
                            lastPosition = event.changes.firstOrNull()?.position ?: lastPosition
                            break
                        }

                        if (pressed.size >= 2) {
                            if (leftDown) {
                                val release = mapToRemote(lastPosition)
                                onPointer(0, release.x, release.y)
                                leftDown = false
                            }
                            multiTouch = true
                            val first = pressed[0].position
                            val second = pressed[1].position
                            val centroid = (first + second) / 2f
                            val distance = (first - second).getDistance()
                            if (previousDistance > 0f) {
                                zoom = (zoom * (distance / previousDistance)).coerceIn(1f, 6f)
                            }
                            previousCentroid?.let { previous ->
                                pan = clampPan(pan + (centroid - previous))
                            }
                            previousCentroid = centroid
                            previousDistance = distance
                            lastPosition = centroid
                            event.changes.forEach { it.consume() }
                            continue
                        }

                        val only = pressed.first()
                        val position = only.position
                        lastPosition = position
                        previousCentroid = null
                        previousDistance = 0f

                        if (multiTouch) {
                            only.consume()
                            continue
                        }

                        val moved = (position - downPosition).getDistance()
                        when {
                            !leftDown && moved > 12f -> {
                                leftDown = true
                                val remote = mapToRemote(position)
                                onPointer(1, remote.x, remote.y)
                            }
                            leftDown -> {
                                val remote = mapToRemote(position)
                                onPointer(1, remote.x, remote.y)
                            }
                            else -> {
                                val remote = mapToRemote(position)
                                onPointer(0, remote.x, remote.y)
                            }
                        }
                        only.consume()
                    }

                    if (leftDown) {
                        val remote = mapToRemote(lastPosition)
                        onPointer(0, remote.x, remote.y)
                    } else if (!multiTouch) {
                        val remote = mapToRemote(lastPosition)
                        val button = if (rightClickMode) 4 else 1
                        onPointer(button, remote.x, remote.y)
                        onPointer(0, remote.x, remote.y)
                    }
                }
            }
    ) {
        val scale = minOf(size.width / bitmap.width, size.height / bitmap.height)
        val drawWidth = bitmap.width * scale * zoom
        val drawHeight = bitmap.height * scale * zoom
        val left = (size.width - drawWidth) / 2f + pan.x
        val top = (size.height - drawHeight) / 2f + pan.y
        drawImage(
            image = image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(bitmap.width, bitmap.height),
            dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
            dstSize = IntSize(drawWidth.roundToInt(), drawHeight.roundToInt()),
            filterQuality = FilterQuality.Medium
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VncKeysDialog(
    onDismiss: () -> Unit,
    onKey: (Int, Boolean) -> Unit,
) {
    var heldModifiers by remember { mutableStateOf<Set<Int>>(emptySet()) }
    val keyboardKeys = listOf(
        "Esc" to VncKeys.ESCAPE,
        "Tab" to VncKeys.TAB,
        "Enter" to VncKeys.ENTER,
        "Backspace" to VncKeys.BACKSPACE,
        "Delete" to VncKeys.DELETE,
        "Insert" to VncKeys.INSERT,
        "Home" to VncKeys.HOME,
        "End" to VncKeys.END,
        "PgUp" to VncKeys.PAGE_UP,
        "PgDn" to VncKeys.PAGE_DOWN,
        "←" to VncKeys.LEFT,
        "↑" to VncKeys.UP,
        "→" to VncKeys.RIGHT,
        "↓" to VncKeys.DOWN,
        "Caps" to VncKeys.CAPS_LOCK,
        "Num" to VncKeys.NUM_LOCK
    )
    val modifierKeys = listOf(
        "Ctrl" to VncKeys.CTRL_LEFT,
        "Shift" to VncKeys.SHIFT_LEFT,
        "Alt" to VncKeys.ALT_LEFT,
        "Win" to VncKeys.SUPER_LEFT
    )

    fun releaseModifiers() {
        heldModifiers.forEach { onKey(it, false) }
        heldModifiers = emptySet()
    }

    fun dismiss() {
        releaseModifiers()
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = ::dismiss,
        title = { Text("Special keys") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Modifiers stay held until you tap them again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    modifierKeys.forEach { (label, keysym) ->
                        FilterChip(
                            selected = keysym in heldModifiers,
                            onClick = {
                                val isHeld = keysym in heldModifiers
                                onKey(keysym, !isHeld)
                                heldModifiers = if (isHeld) {
                                    heldModifiers - keysym
                                } else {
                                    heldModifiers + keysym
                                }
                            },
                            label = { Text(label) }
                        )
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    keyboardKeys.forEach { (label, keysym) ->
                        FilterChip(
                            selected = false,
                            onClick = {
                                onKey(keysym, true)
                                onKey(keysym, false)
                            },
                            label = { Text(label) }
                        )
                    }
                }
                Text(
                    "Function keys",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    VncKeys.functionKeys.forEach { (label, keysym) ->
                        FilterChip(
                            selected = false,
                            onClick = {
                                onKey(keysym, true)
                                onKey(keysym, false)
                            },
                            label = { Text(label) }
                        )
                    }
                }
                FilterChip(
                    selected = false,
                    onClick = {
                        onKey(VncKeys.CTRL_LEFT, true)
                        onKey(VncKeys.ALT_LEFT, true)
                        onKey(VncKeys.DELETE, true)
                        onKey(VncKeys.DELETE, false)
                        onKey(VncKeys.ALT_LEFT, false)
                        onKey(VncKeys.CTRL_LEFT, false)
                    },
                    label = { Text("Ctrl + Alt + Del") }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = ::dismiss) { Text("Close") }
        }
    )
}

@Composable
private fun VncTextDialog(
    onDismiss: () -> Unit,
    onSend: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send text") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Text") },
                minLines = 3,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (text.isNotEmpty()) onSend(text)
                    onDismiss()
                },
                enabled = text.isNotEmpty()
            ) { Text("Send") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
