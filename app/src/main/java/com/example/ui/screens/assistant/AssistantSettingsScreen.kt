package com.example.ui.screens.assistant

import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.service.AssistantOverlayController
import com.example.service.AssistantOverlayService
import com.example.ui.components.AppIcons
import com.example.ui.components.FloatingTopBar
import com.example.ui.components.landscapeContentWidth
import com.example.ui.theme.AppCornerRadius
import com.example.util.AssistantActions
import com.example.util.AssistantPrefs

private data class AssistantAction(
    val id: String,
    val label: String,
    val icon: ImageVector
)

private val IDLE_DELAY_OPTIONS = listOf(5000L, 8000L, 15000L, 30000L)

private val ASSISTANT_ACTIONS = listOf(
    AssistantAction("assistant_torch", "Flashlight", AppIcons.FlashlightOn),
    AssistantAction("calculator", "Calculator", AppIcons.Calculate),
    AssistantAction("assistant_camera", "Inline camera", AppIcons.PhotoCamera),
    AssistantAction("screenrecorder", "Screen recorder", AppIcons.FiberManualRecord),
    AssistantAction("data_connection", "Data connection", AppIcons.SignalCellularAlt),
    AssistantAction("sms", "SMS", AppIcons.Sms),
    AssistantAction("contacts", "Contacts", AppIcons.Person),
    AssistantAction("clipboard", "Clipboard snippets", AppIcons.ContentCopy)
)

@Composable
fun AssistantSettingsScreen() {
    val context = LocalContext.current
    var running by remember { mutableStateOf(AssistantOverlayService.isRunning()) }
    var overlayGranted by remember {
        mutableStateOf(
            android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M ||
                Settings.canDrawOverlays(context)
        )
    }
    var bubbleSize by remember { mutableIntStateOf(AssistantPrefs.bubbleSize(context)) }
    var bubbleOpacity by remember { mutableFloatStateOf(AssistantPrefs.bubbleOpacity(context)) }
    var idleFade by remember { mutableStateOf(AssistantPrefs.bubbleIdleFade(context)) }
    var idleDelayMs by remember { mutableLongStateOf(AssistantPrefs.bubbleIdleDelayMs(context)) }
    var idleOpacity by remember { mutableFloatStateOf(AssistantPrefs.bubbleIdleOpacity(context)) }
    var panelSize by remember { mutableIntStateOf(AssistantPrefs.panelSize(context)) }
    var opacity by remember { mutableFloatStateOf(AssistantPrefs.panelOpacity(context)) }
    var defaultTab by remember { mutableIntStateOf(AssistantPrefs.defaultTab(context)) }
    var snapToEdge by remember { mutableStateOf(AssistantPrefs.snapToEdge(context)) }
    var collapseOnCall by remember { mutableStateOf(AssistantPrefs.collapseOnCall(context)) }
    var collapseOnScreenOff by remember { mutableStateOf(AssistantPrefs.collapseOnScreenOff(context)) }
    var showFavourites by remember { mutableStateOf(AssistantPrefs.showFavourites(context)) }
    var showRecent by remember { mutableStateOf(AssistantPrefs.showRecent(context)) }
    var expandedAction by remember { mutableStateOf<String?>(null) }
    var actionModes by remember {
        mutableStateOf(ASSISTANT_ACTIONS.associate { it.id to AssistantPrefs.actionMode(context, it.id) })
    }
    var showResetConfirm by remember { mutableStateOf(false) }

    fun refreshLayout() = AssistantOverlayController.refreshLayout(context)

    Scaffold(
        topBar = {
            FloatingTopBar {
                IconButton(onClick = { AssistantActions.open(context, "assistantsettings") }) {
                    Icon(AppIcons.Tune, contentDescription = null)
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Floating Assistant",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { AssistantActions.open(context, "assistant") }) {
                    Icon(AppIcons.SmartToy, contentDescription = "Start assistant")
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .landscapeContentWidth(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SettingsCard(title = "Service") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Status", style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (running) "Floating bubble is active" else "Not running",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(onClick = {
                            if (!overlayGranted) {
                                AssistantActions.requestOverlayPermission(context)
                                Toast.makeText(context, "Grant overlay permission first", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            if (running) {
                                AssistantOverlayService.stop(context)
                                running = false
                            } else {
                                AssistantActions.start(context)
                                running = true
                            }
                        }) {
                            Text(if (running) "Stop" else "Start")
                        }
                    }
                    if (!overlayGranted) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { AssistantActions.requestOverlayPermission(context) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Grant overlay permission") }
                    }
                }
            }

            item {
                SettingsCard(title = "Bubble & panel") {
                    ChoiceRow(
                        label = "Bubble size",
                        options = listOf("Small", "Default", "Large"),
                        selected = bubbleSize
                    ) {
                        bubbleSize = it
                        AssistantPrefs.setBubbleSize(context, it)
                        refreshLayout()
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Bubble opacity: ${(bubbleOpacity * 100).toInt()}%",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Slider(
                        value = bubbleOpacity,
                        onValueChange = {
                            bubbleOpacity = it
                            AssistantPrefs.setBubbleOpacity(context, it)
                            idleOpacity = AssistantPrefs.bubbleIdleOpacity(context)
                            refreshLayout()
                        },
                        valueRange = 0.35f..1f
                    )
                    SwitchRow("Fade bubble when idle", idleFade) {
                        idleFade = it
                        AssistantPrefs.setBubbleIdleFade(context, it)
                        refreshLayout()
                    }
                    if (idleFade) {
                        Spacer(Modifier.height(8.dp))
                        ChoiceRow(
                            label = "Idle delay",
                            options = IDLE_DELAY_OPTIONS.map { delayMs ->
                                when (delayMs) {
                                    5000L -> "5 sec"
                                    8000L -> "8 sec"
                                    15000L -> "15 sec"
                                    else -> "30 sec"
                                }
                            },
                            selected = IDLE_DELAY_OPTIONS.indexOf(idleDelayMs).takeIf { it >= 0 } ?: 1
                        ) {
                            idleDelayMs = IDLE_DELAY_OPTIONS[it]
                            AssistantPrefs.setBubbleIdleDelayMs(context, idleDelayMs)
                            refreshLayout()
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Faded opacity: ${(idleOpacity * 100).toInt()}%",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Slider(
                            value = idleOpacity,
                            onValueChange = {
                                idleOpacity = it
                                AssistantPrefs.setBubbleIdleOpacity(context, it)
                                refreshLayout()
                            },
                            valueRange = 0.15f..bubbleOpacity
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    ChoiceRow(
                        label = "Panel size",
                        options = listOf("Compact", "Default", "Large"),
                        selected = panelSize
                    ) {
                        panelSize = it
                        AssistantPrefs.setPanelSize(context, it)
                        refreshLayout()
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Panel opacity: ${(opacity * 100).toInt()}%",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Slider(
                        value = opacity,
                        onValueChange = {
                            opacity = it
                            AssistantPrefs.setPanelOpacity(context, it)
                            refreshLayout()
                        },
                        valueRange = 0.6f..1f
                    )
                    Spacer(Modifier.height(8.dp))
                    ChoiceRow(
                        label = "Default tab",
                        options = listOf("Quick", "Media", "Notes", "SMS"),
                        selected = defaultTab
                    ) {
                        defaultTab = it
                        AssistantPrefs.setDefaultTab(context, it)
                    }
                    SwitchRow("Dock bubble half-offscreen at edges", snapToEdge) {
                        snapToEdge = it
                        AssistantPrefs.setSnapToEdge(context, it)
                    }
                }
            }

            item {
                SettingsCard(title = "Behavior") {
                    SwitchRow("Collapse on incoming or active call", collapseOnCall) {
                        collapseOnCall = it
                        AssistantPrefs.setCollapseOnCall(context, it)
                    }
                    SwitchRow("Collapse when screen turns off", collapseOnScreenOff) {
                        collapseOnScreenOff = it
                        AssistantPrefs.setCollapseOnScreenOff(context, it)
                    }
                    SwitchRow("Show favourite contacts", showFavourites) {
                        showFavourites = it
                        AssistantPrefs.setShowFavourites(context, it)
                        refreshLayout()
                    }
                    SwitchRow("Show recent contacts", showRecent) {
                        showRecent = it
                        AssistantPrefs.setShowRecent(context, it)
                        refreshLayout()
                    }
                }
            }

            item {
                SettingsCard(title = "Quick actions") {
                    Text(
                        "Quick actions always run inside the assistant. Hidden actions are removed from the panel.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    ASSISTANT_ACTIONS.forEach { action ->
                        val mode = actionModes[action.id] ?: AssistantPrefs.ACTION_MODE_FLOATING
                        Box {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(AppCornerRadius.md)
                                    .background(MaterialTheme.colorScheme.surfaceContainer)
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(action.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(10.dp))
                                Text(action.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                TextButton(onClick = { expandedAction = action.id }) {
                                    Text(
                                        when (mode) {
                                            AssistantPrefs.ACTION_MODE_HIDDEN -> "Hidden"
                                            else -> "Floating"
                                        }
                                    )
                                }
                            }
                            DropdownMenu(
                                expanded = expandedAction == action.id,
                                onDismissRequest = { expandedAction = null }
                            ) {
                                listOf(
                                    AssistantPrefs.ACTION_MODE_HIDDEN to "Hidden",
                                    AssistantPrefs.ACTION_MODE_FLOATING to "Floating"
                                ).forEach { (value, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = {
                                            AssistantPrefs.setActionMode(context, action.id, value)
                                            actionModes = actionModes + (action.id to value)
                                            expandedAction = null
                                            Toast.makeText(
                                                context,
                                                when (value) {
                                                    AssistantPrefs.ACTION_MODE_HIDDEN -> "Hidden from assistant"
                                                    else -> "Runs inside assistant"
                                                },
                                                Toast.LENGTH_SHORT
                                            ).show()
                                            refreshLayout()
                                        },
                                        trailingIcon = {
                                            if (mode == value) Icon(AppIcons.Check, contentDescription = null)
                                        }
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }

            item {
                SettingsCard(title = "Reset") {
                    OutlinedButton(
                        onClick = { showResetConfirm = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(AppIcons.RestartAlt, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Reset assistant settings")
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Clears saved position, bubble/panel sizes, opacity, idle fade and action modes. Notes and snippets are kept.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("Reset assistant settings?") },
            text = { Text("This restores the small bubble, default panel and all action modes.") },
            confirmButton = {
                TextButton(onClick = {
                    AssistantPrefs.resetLayout(context)
                    bubbleSize = AssistantPrefs.bubbleSize(context)
                    bubbleOpacity = AssistantPrefs.bubbleOpacity(context)
                    idleFade = AssistantPrefs.bubbleIdleFade(context)
                    idleDelayMs = AssistantPrefs.bubbleIdleDelayMs(context)
                    idleOpacity = AssistantPrefs.bubbleIdleOpacity(context)
                    panelSize = AssistantPrefs.panelSize(context)
                    opacity = AssistantPrefs.panelOpacity(context)
                    defaultTab = AssistantPrefs.defaultTab(context)
                    snapToEdge = AssistantPrefs.snapToEdge(context)
                    collapseOnCall = AssistantPrefs.collapseOnCall(context)
                    collapseOnScreenOff = AssistantPrefs.collapseOnScreenOff(context)
                    showFavourites = AssistantPrefs.showFavourites(context)
                    showRecent = AssistantPrefs.showRecent(context)
                    actionModes = ASSISTANT_ACTIONS.associate {
                        it.id to AssistantPrefs.actionMode(context, it.id)
                    }
                    refreshLayout()
                    showResetConfirm = false
                }) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun ChoiceRow(
    label: String,
    options: List<String>,
    selected: Int,
    onSelected: (Int) -> Unit
) {
    Text(label, style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEachIndexed { index, option ->
            FilterChip(
                selected = selected == index,
                onClick = { onSelected(index) },
                label = { Text(option, style = MaterialTheme.typography.labelMedium) }
            )
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
