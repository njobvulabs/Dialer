package com.example.ui.screens.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ui.components.AppIcons
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

private data class TermTab(
    val id: Int,
    val session: TerminalSession,
    val title: String
)

@Composable
fun TerminalScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current

    val tabs = remember { mutableStateListOf<TermTab>() }
    var activeTabId by remember { mutableStateOf(-1) }
    var nextTabId by remember { mutableStateOf(0) }

    var isInstalling by remember { mutableStateOf(false) }
    var installProgress by remember { mutableStateOf(0f) }
    var installStatus by remember { mutableStateOf("") }
    var textSize by remember { mutableStateOf(TerminalManager.getTerminalFontSize(context)) }
    var scaleFactor by remember { mutableStateOf(TerminalManager.getTerminalScale(context)) }

    // Sticky modifier keys
    var isCtrlSticky by remember { mutableStateOf(false) }
    var isAltSticky by remember { mutableStateOf(false) }

    // Extra keys toolbar
    var showExtraKeys by remember { mutableStateOf(TerminalManager.getExtraKeysVisible(context)) }

    // Menus
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showLongPressMenu by remember { mutableStateOf(false) }
    var longPressOffset by remember { mutableStateOf<Offset?>(null) }

    // Teardown guard shared with background session callbacks
    val closing = remember { AtomicBoolean(false) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val terminalViewRef = remember { TerminalViewHolder() }
    val key = remember { object {} }

    val activeTab = tabs.firstOrNull { it.id == activeTabId }
    val activeSession = activeTab?.session

    // Theme state
    var terminalTheme by remember { mutableStateOf(TerminalManager.getTerminalTheme(context)) }
    var bellVibrateEnabled by remember { mutableStateOf(TerminalManager.getTerminalBellVibrate(context)) }

    // Settings dialog
    var showSettingsDialog by remember { mutableStateOf(false) }

    LaunchedEffect(textSize) { TerminalManager.setTerminalFontSize(context, textSize) }
    LaunchedEffect(scaleFactor) { TerminalManager.setTerminalScale(context, scaleFactor) }
    LaunchedEffect(showExtraKeys) { TerminalManager.setExtraKeysVisible(context, showExtraKeys) }
    LaunchedEffect(terminalTheme) {
        TerminalManager.setTerminalTheme(context, terminalTheme)
        val colors = TerminalManager.getThemeColors(terminalTheme)
        terminalViewRef.view?.setThemeColors(colors.background, colors.foreground, colors.cursor, colors.ansi)
    }

    fun applyTextSize() {
        val view = terminalViewRef.view ?: return
        view.setTextSize((textSize * scaleFactor).coerceIn(9f, 32f).toInt())
    }

    fun closeTab(id: Int) {
        val idx = tabs.indexOfFirst { it.id == id }
        if (idx < 0) return
        val removed = tabs.removeAt(idx)
        removed.session.finishIfRunning()
        if (tabs.isEmpty()) {
            onBack()
            return
        }
        if (activeTabId == id) {
            val next = tabs[minOf(idx, tabs.size - 1)]
            activeTabId = next.id
            terminalViewRef.view?.attachSession(next.session)
            applyTextSize()
        }
    }

    fun handleSessionFinished(finished: TerminalSession) {
        if (closing.get()) return
        val tab = tabs.firstOrNull { it.session === finished } ?: return
        closeTab(tab.id)
    }

    fun createSession(): TerminalSession {
        val termManager = TerminalManager
        val prootPath = termManager.prootPath(context)
        val prootArgs = termManager.buildProotArgs(context)
        val args = arrayListOf(prootPath).apply { addAll(prootArgs.split(" ")) }.toTypedArray()
        val env = termManager.buildEnv(context)

        return TerminalSession(
            prootPath,
            context.filesDir.absolutePath,
            args,
            env,
            1000,
            object : TerminalSessionClient {
                override fun onTextChanged(changedSession: TerminalSession) {
                    terminalViewRef.view?.onScreenUpdated()
                }

                override fun onTitleChanged(changedSession: TerminalSession) {}

                override fun onSessionFinished(finishedSession: TerminalSession) {
                    mainHandler.post { handleSessionFinished(finishedSession) }
                }

                override fun onCopyTextToClipboard(session: TerminalSession, text: String) {}

                override fun onPasteTextFromClipboard(session: TerminalSession) {}

                override fun onBell(session: TerminalSession) {
                    val view = terminalViewRef.view ?: return
                    view.post {
                        if (bellVibrateEnabled) {
                            val vibrator = view.context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                            if (vibrator.hasVibrator()) {
                                vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                            }
                        }
                    }
                }

                override fun onColorsChanged(session: TerminalSession) {
                    terminalViewRef.view?.onScreenUpdated()
                }

                override fun onTerminalCursorStateChange(state: Boolean) {}

                override fun getTerminalCursorStyle(): Int? = null

                override fun logError(tag: String, message: String) {}
                override fun logWarn(tag: String, message: String) {}
                override fun logInfo(tag: String, message: String) {}
                override fun logDebug(tag: String, message: String) {}
                override fun logVerbose(tag: String, message: String) {}
                override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
                override fun logStackTrace(tag: String, e: Exception) {}
            }
        )
    }

    fun addNewTab() {
        val id = nextTabId
        nextTabId += 1
        val title = if (id == 0) "Terminal" else "Terminal ${id + 1}"
        val newSession = createSession()
        tabs.add(TermTab(id, newSession, title))
        activeTabId = id
        terminalViewRef.view?.attachSession(newSession)
        applyTextSize()
    }

    fun switchTab(id: Int) {
        val tab = tabs.firstOrNull { it.id == id } ?: return
        activeTabId = id
        terminalViewRef.view?.attachSession(tab.session)
        applyTextSize()
    }

    LaunchedEffect(key) {
        val termManager = TerminalManager
        if (!termManager.isInstalled(context)) {
            isInstalling = true
            val success = termManager.ensureInstalled(
                context,
                onStatus = { status -> mainHandler.post { installStatus = status } },
                onProgress = { progress -> mainHandler.post { installProgress = progress } }
            )
            if (success) {
                isInstalling = false
                if (tabs.isEmpty()) addNewTab()
            } else {
                installStatus = "Installation failed"
            }
        } else {
            if (tabs.isEmpty()) addNewTab()
        }
    }

    DisposableEffect(key) {
        onDispose {
            closing.set(true)
            tabs.forEach { it.session.finishIfRunning() }
            tabs.clear()
        }
    }

    val viewClient = remember {
        object : TerminalViewClient {
            override fun onScale(scale: Float): Float {
                // TerminalView passes the cumulative gesture factor and multiplies it into
                // mScaleFactor before this call. We accumulate into our own persisted factor and
                // return 1f so the view resets, otherwise the factor compounds and pins at 3.0.
                scaleFactor = (scaleFactor * scale).coerceIn(0.5f, 3.0f)
                val view = terminalViewRef.view
                if (view != null) {
                    view.setTextSize((textSize * scaleFactor).coerceIn(9f, 32f).toInt())
                }
                return 1.0f
            }

            override fun onSingleTapUp(e: android.view.MotionEvent) {
                val view = terminalViewRef.view
                if (view?.mEmulator != null) {
                    if (!view.mEmulator.isMouseTrackingActive() && !e.isFromSource(android.view.InputDevice.SOURCE_MOUSE)) {
                        view.requestFocus()
                        val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.showSoftInput(view, 0)
                    }
                }
            }

            override fun shouldBackButtonBeMappedToEscape(): Boolean = true
            override fun shouldEnforceCharBasedInput(): Boolean = false
            override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
            override fun isTerminalViewSelected(): Boolean = true

            override fun copyModeChanged(copyMode: Boolean) {}

            override fun onKeyDown(keyCode: Int, e: android.view.KeyEvent, currentSession: TerminalSession): Boolean {
                val ctrlDown = e.isCtrlPressed() || readControlKey()
                if (ctrlDown) {
                    when (keyCode) {
                        KeyEvent.KEYCODE_C -> { currentSession.writeCodePoint(false, 3); return true }
                        KeyEvent.KEYCODE_D -> { currentSession.writeCodePoint(false, 4); return true }
                        KeyEvent.KEYCODE_L -> { currentSession.writeCodePoint(false, 12); return true }
                        KeyEvent.KEYCODE_X -> { currentSession.writeCodePoint(false, 24); return true }
                        KeyEvent.KEYCODE_U -> { currentSession.writeCodePoint(false, 21); return true }
                        KeyEvent.KEYCODE_K -> { currentSession.writeCodePoint(false, 11); return true }
                        KeyEvent.KEYCODE_A -> { currentSession.writeCodePoint(false, 1); return true }
                        KeyEvent.KEYCODE_E -> { currentSession.writeCodePoint(false, 5); return true }
                        KeyEvent.KEYCODE_W -> { currentSession.writeCodePoint(false, 23); return true }
                    }
                }
                return false
            }

            override fun onKeyUp(keyCode: Int, e: android.view.KeyEvent): Boolean = false

            override fun onLongPress(event: android.view.MotionEvent): Boolean {
                val view = terminalViewRef.view ?: return false
                if (!view.isSelectingText()) {
                    view.startTextSelectionMode(event, false)
                }
                longPressOffset = Offset(event.x, event.y)
                showLongPressMenu = true
                return true
            }

            override fun readControlKey(): Boolean = isCtrlSticky
            override fun readAltKey(): Boolean = isAltSticky
            override fun readShiftKey(): Boolean = false
            override fun readFnKey(): Boolean = false

            override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false

            override fun onEmulatorSet() {
                terminalViewRef.view?.setTerminalCursorBlinkerState(true, true)
            }

            override fun logError(tag: String, message: String) {}
            override fun logWarn(tag: String, message: String) {}
            override fun logInfo(tag: String, message: String) {}
            override fun logDebug(tag: String, message: String) {}
            override fun logVerbose(tag: String, message: String) {}
            override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
            override fun logStackTrace(tag: String, e: Exception) {}
        }
    }

    fun sendSequence(text: String) {
        val ses = activeSession ?: return
        val bytes = text.toByteArray(Charsets.UTF_8)
        ses.write(bytes, 0, bytes.size)
    }

    fun toggleKeyboard() {
        val view = terminalViewRef.view ?: return
        val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        view.requestFocus()
        imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, InputMethodManager.HIDE_IMPLICIT_ONLY)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Top bar
        Surface(
            modifier = Modifier
                .padding(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 0.dp)
                .fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            shape = MaterialTheme.shapes.small,
            tonalElevation = 4.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // Sticky Ctrl (first shortcut)
                TopBarKey(
                    label = "Ctrl",
                    active = isCtrlSticky,
                    onClick = { isCtrlSticky = !isCtrlSticky }
                )
                Text(
                    text = activeTab?.title ?: "Terminal",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 4.dp)
                )
                TopBarKey(
                    label = "Ctrl+C",
                    color = MaterialTheme.colorScheme.error,
                    onClick = { activeSession?.writeCodePoint(false, 3) }
                )
                TopBarKey(
                    label = "Ctrl+L",
                    color = MaterialTheme.colorScheme.primary,
                    onClick = { activeSession?.writeCodePoint(false, 12) }
                )
                TopBarKey(
                    label = "Ctrl+X",
                    color = MaterialTheme.colorScheme.primary,
                    onClick = { activeSession?.writeCodePoint(false, 24) }
                )
                Box {
                    IconButton(
                        onClick = { showOverflowMenu = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(AppIcons.MoreVert, contentDescription = "More", modifier = Modifier.size(18.dp))
                    }
                    OverflowMenu(
                        expanded = showOverflowMenu,
                        onDismiss = { showOverflowMenu = false },
                        tabs = tabs,
                        activeTabId = activeTabId,
                        showExtraKeys = showExtraKeys,
                        onNewTab = { addNewTab() },
                        onSwitchTab = { switchTab(it) },
                        onCloseTab = { closeTab(activeTabId) },
                        onToggleExtraKeys = { showExtraKeys = !showExtraKeys },
                        onOpenSettings = { showSettingsDialog = true },
                        onResetZoom = { scaleFactor = 1f },
                        onClearScreen = { activeSession?.writeCodePoint(false, 12) }
                    )
                }
            }
        }

        // Terminal view + long-press menu
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (isInstalling) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(progress = { installProgress / 100f })
                        Text(text = installStatus, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        Text(text = "${installProgress.toInt()}%", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                AndroidView(
                    factory = { ctx ->
                        val view = TerminalView(ctx, null)
                        view.setTerminalViewClient(viewClient)
                        view.setTextSize((textSize * scaleFactor).coerceIn(9f, 32f).toInt())
                        view.isFocusableInTouchMode = true
                        view.isFocusable = true
                        terminalViewRef.view = view
                        view.post { view.requestFocus() }
                        view
                    },
                    update = { view ->
                        if (!closing.get()) {
                            val active = tabs.firstOrNull { it.id == activeTabId }?.session
                            if (active == null) {
                                if (tabs.isEmpty()) addNewTab()
                            } else if (view.mTermSession !== active) {
                                view.attachSession(active)
                                applyTextSize()
                            }
                            val width = view.width
                            val height = view.height
                            if (width > 0 && height > 0) view.updateSize()
                            if (!view.hasFocus()) view.requestFocus()
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                val longPressDp = longPressOffset?.let {
                    with(density) { DpOffset(it.x.toDp(), it.y.toDp()) }
                } ?: DpOffset.Zero
                DropdownMenu(
                    expanded = showLongPressMenu,
                    onDismissRequest = { showLongPressMenu = false },
                    offset = longPressDp
                ) {
                    val view = terminalViewRef.view
                    DropdownMenuItem(
                        text = { Text("Copy") },
                        enabled = view?.isSelectingText() == true,
                        onClick = {
                            val text = view?.getSelectedText()
                            if (!text.isNullOrEmpty()) {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text))
                            }
                            view?.stopTextSelectionMode()
                            showLongPressMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Paste") },
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = clipboard.primaryClip
                            if (clip != null && clip.itemCount > 0) {
                                val text = clip.getItemAt(0).coerceToText(context).toString()
                                if (text.isNotEmpty()) {
                                    val bytes = text.toByteArray(Charsets.UTF_8)
                                    activeSession?.write(bytes, 0, bytes.size)
                                }
                            }
                            view?.stopTextSelectionMode()
                            showLongPressMenu = false
                        }
                    )
                }
            }
        }

        if (showExtraKeys && !isInstalling) {
            ExtraKeysToolbar(
                isCtrlSticky = isCtrlSticky,
                isAltSticky = isAltSticky,
                onCtrlToggle = { isCtrlSticky = !isCtrlSticky },
                onAltToggle = { isAltSticky = !isAltSticky },
                onKey = { sendSequence(it) },
                onHide = { showExtraKeys = false },
                onToggleKeyboard = { toggleKeyboard() }
            )
        }
    }

    if (showSettingsDialog) {
        TerminalSettingsDialog(
            theme = terminalTheme,
            bellVibrate = bellVibrateEnabled,
            textSize = textSize,
            scaleFactor = scaleFactor,
            onThemeChange = { terminalTheme = it },
            onBellVibrateChange = {
                bellVibrateEnabled = it
                TerminalManager.setTerminalBellVibrate(context, it)
            },
            onTextSizeChange = { textSize = it },
            onScaleChange = { scaleFactor = it },
            onDismiss = { showSettingsDialog = false }
        )
    }
}

@Composable
private fun TopBarKey(
    label: String,
    active: Boolean = false,
    color: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(6.dp),
        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        modifier = Modifier.height(30.dp)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                fontSize = 9.sp,
                color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else color,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@Composable
private fun OverflowMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    tabs: List<TermTab>,
    activeTabId: Int,
    showExtraKeys: Boolean,
    onNewTab: () -> Unit,
    onSwitchTab: (Int) -> Unit,
    onCloseTab: () -> Unit,
    onToggleExtraKeys: () -> Unit,
    onOpenSettings: () -> Unit,
    onResetZoom: () -> Unit,
    onClearScreen: () -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("New tab") },
            leadingIcon = { Icon(AppIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp)) },
            onClick = { onNewTab(); onDismiss() }
        )
        DropdownMenuItem(
            text = { Text("Close tab") },
            leadingIcon = { Icon(AppIcons.Close, contentDescription = null, modifier = Modifier.size(18.dp)) },
            onClick = { onCloseTab(); onDismiss() }
        )
        if (tabs.size > 1) {
            HorizontalDivider()
            tabs.forEach { tab ->
                DropdownMenuItem(
                    text = { Text(tab.title) },
                    leadingIcon = {
                        Text(
                            text = if (tab.id == activeTabId) "✓" else " ",
                            modifier = Modifier.width(16.dp),
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.primary
                        )
                    },
                    onClick = { onSwitchTab(tab.id); onDismiss() }
                )
            }
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(if (showExtraKeys) "Hide extra keys" else "Show extra keys") },
            leadingIcon = { Icon(AppIcons.Keyboard, contentDescription = null, modifier = Modifier.size(18.dp)) },
            onClick = { onToggleExtraKeys(); onDismiss() }
        )
        DropdownMenuItem(
            text = { Text("Settings") },
            leadingIcon = { Icon(AppIcons.Settings, contentDescription = null, modifier = Modifier.size(18.dp)) },
            onClick = { onOpenSettings(); onDismiss() }
        )
        DropdownMenuItem(
            text = { Text("Reset zoom") },
            onClick = { onResetZoom(); onDismiss() }
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("Clear screen") },
            onClick = { onClearScreen(); onDismiss() }
        )
    }
}

@Composable
private fun ExtraKeysToolbar(
    isCtrlSticky: Boolean,
    isAltSticky: Boolean,
    onCtrlToggle: () -> Unit,
    onAltToggle: () -> Unit,
    onKey: (String) -> Unit,
    onHide: () -> Unit,
    onToggleKeyboard: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
            ExtraKey("ESC", Modifier.weight(1f)) { onKey("\u001b") }
            ExtraKey("TAB", Modifier.weight(1f)) { onKey("\t") }
            ExtraKey("CTRL", Modifier.weight(1f), active = isCtrlSticky, onClick = onCtrlToggle)
            ExtraKey("ALT", Modifier.weight(1f), active = isAltSticky, onClick = onAltToggle)
            ExtraKey("HOME", Modifier.weight(1f)) { onKey("\u001b[H") }
            ExtraKey("END", Modifier.weight(1f)) { onKey("\u001b[F") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
            ExtraKey("←", Modifier.weight(1f)) { onKey("\u001b[D") }
            ExtraKey("↑", Modifier.weight(1f)) { onKey("\u001b[A") }
            ExtraKey("↓", Modifier.weight(1f)) { onKey("\u001b[B") }
            ExtraKey("→", Modifier.weight(1f)) { onKey("\u001b[C") }
            ExtraKey("PGUP", Modifier.weight(1f)) { onKey("\u001b[5~") }
            ExtraKey("PGDN", Modifier.weight(1f)) { onKey("\u001b[6~") }
            ExtraKey("⌨", Modifier.weight(1f), onClick = onToggleKeyboard)
        }
    }
}

@Composable
private fun ExtraKey(
    label: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        modifier = modifier
            .height(34.dp)
            .clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(
                text = label,
                fontSize = 11.sp,
                color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

class TerminalViewHolder {
    var view: TerminalView? = null
}

@Composable
private fun TerminalSettingsDialog(
    theme: String,
    bellVibrate: Boolean,
    textSize: Float,
    scaleFactor: Float,
    onThemeChange: (String) -> Unit,
    onBellVibrateChange: (Boolean) -> Unit,
    onTextSizeChange: (Float) -> Unit,
    onScaleChange: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Terminal settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Theme", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(listOf("default", "solarized", "dracula", "gruvbox")) { t ->
                            FilterChip(
                                selected = theme == t,
                                onClick = { onThemeChange(t) },
                                label = { Text(t.replaceFirstChar { it.uppercase() }) }
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Vibration on Bell", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                        Text("Vibrate device when bell character is received",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = bellVibrate, onCheckedChange = onBellVibrateChange)
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Font Size: ${textSize.toInt()} dp", style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = textSize,
                        onValueChange = onTextSizeChange,
                        valueRange = 9f..32f,
                        steps = 23
                    )
                    Text("Zoom Scale: ${String.format("%.1f", scaleFactor)}x", style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = scaleFactor,
                        onValueChange = onScaleChange,
                        valueRange = 0.5f..3.0f,
                        steps = 25
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}
