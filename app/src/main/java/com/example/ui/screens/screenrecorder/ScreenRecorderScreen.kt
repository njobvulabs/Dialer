package com.example.ui.screens.screenrecorder

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.ui.MainViewModel
import com.example.ui.components.AppIcons
import com.example.ui.components.FloatingTopBar
import com.example.ui.components.landscapeContentWidth
import com.example.util.InternalAudioCapture
import com.example.util.ScreenRecorderController
import com.example.util.ScreenRecorderQuality
import com.example.util.ScreenRecorderStore
import java.io.File
import java.util.Locale

@Composable
fun ScreenRecorderScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val state by ScreenRecorderController.state.collectAsState()
    val useGlassmorphism by viewModel.useGlassmorphism.collectAsState()
    var recordings by remember { mutableStateOf<List<File>>(emptyList()) }
    val active = state.isStarting || state.isRecording || state.countdownSec > 0
    val options = state.options
    val systemAudioSupported = InternalAudioCapture.supported()

    LaunchedEffect(Unit) {
        ScreenRecorderController.ensureLoaded(context)
    }
    LaunchedEffect(state.outputPath) {
        recordings = ScreenRecorderStore.list(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                if (useGlassmorphism) {
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                } else {
                    MaterialTheme.colorScheme.background
                }
            )
            .verticalScroll(rememberScrollState())
            .landscapeContentWidth()
    ) {
        FloatingTopBar(useGlass = useGlassmorphism) {
            Icon(
                AppIcons.FiberManualRecord,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Screen Recorder",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
        }

        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                "Records an MP4 to Movies/NJ-Dialer/Screen Recordings.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Surface(
                shape = MaterialTheme.shapes.large,
                color = if (useGlassmorphism) {
                    MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.72f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                },
                tonalElevation = 3.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        when {
                            state.countdownSec > 0 -> "Starting in ${state.countdownSec}…"
                            state.isStarting -> "Preparing screen capture…"
                            state.isRecording && state.isPaused -> "Paused at ${formatElapsed(state.elapsedMs)}"
                            state.isRecording -> "Recording ${formatElapsed(state.elapsedMs)}${durationSuffix(options.durationSec, state.elapsedMs)}"
                            else -> "Ready"
                        },
                        style = MaterialTheme.typography.titleMedium
                    )

                    Text("Quality", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ScreenRecorderQuality.values().forEach { quality ->
                            FilterChip(
                                selected = options.quality == quality,
                                enabled = !active,
                                onClick = {
                                    ScreenRecorderController.updateOptions(
                                        context,
                                        options.copy(quality = quality)
                                    )
                                },
                                label = { Text(quality.label) }
                            )
                        }
                    }

                    Text("Timer", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0, 30, 60, 180).forEach { seconds ->
                            FilterChip(
                                selected = options.durationSec == seconds,
                                enabled = !active,
                                onClick = {
                                    ScreenRecorderController.updateOptions(
                                        context,
                                        options.copy(durationSec = seconds)
                                    )
                                },
                                label = { Text(durationLabel(seconds)) }
                            )
                        }
                    }

                    ToggleRow(
                        icon = if (options.micEnabled) AppIcons.Mic else AppIcons.MicOff,
                        title = "Record microphone",
                        checked = options.micEnabled,
                        enabled = !active,
                        onCheckedChange = {
                            ScreenRecorderController.updateOptions(context, options.copy(micEnabled = it))
                        }
                    )
                    ToggleRow(
                        icon = AppIcons.VolumeUp,
                        title = "Capture system playback",
                        checked = options.systemAudioEnabled && systemAudioSupported,
                        enabled = !active && systemAudioSupported,
                        onCheckedChange = {
                            ScreenRecorderController.updateOptions(
                                context,
                                options.copy(systemAudioEnabled = it)
                            )
                        }
                    )
                    Text(
                        when {
                            !systemAudioSupported -> "System playback capture needs Android 10 or newer."
                            options.systemAudioEnabled && options.micEnabled ->
                                "System playback and microphone will be mixed into one audio track."
                            options.systemAudioEnabled -> "System playback audio will be captured."
                            options.micEnabled -> "Microphone audio will be included."
                            else -> "Silent recording; no audio is captured."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (active) {
                                    ScreenRecorderController.stop(context)
                                } else {
                                    ScreenRecorderController.begin(context, options)
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                if (active) AppIcons.Stop else AppIcons.FiberManualRecord,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                when {
                                    state.countdownSec > 0 -> "Starting…"
                                    state.isStarting -> "Starting…"
                                    state.isRecording -> "Stop recording"
                                    else -> "Start recording"
                                }
                            )
                        }
                        if (state.isRecording) {
                            OutlinedButton(
                                onClick = {
                                    if (state.isPaused) {
                                        ScreenRecorderController.resume(context)
                                    } else {
                                        ScreenRecorderController.pause(context)
                                    }
                                }
                            ) {
                                Icon(
                                    if (state.isPaused) AppIcons.PlayArrow else AppIcons.Pause,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(if (state.isPaused) "Resume" else "Pause")
                            }
                        }
                    }
                }
            }

            state.error?.let { error ->
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Text("Recordings", style = MaterialTheme.typography.titleMedium)
            if (recordings.isEmpty()) {
                Text(
                    "No recordings yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                recordings.forEach { file ->
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        tonalElevation = 2.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { ScreenRecorderStore.open(context, file) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                AppIcons.FiberManualRecord,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                Text(
                                    "${formatFileSize(file.length())} · ${java.text.DateFormat.getDateTimeInstance().format(file.lastModified())}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = {
                                android.app.AlertDialog.Builder(context)
                                    .setTitle("Delete recording?")
                                    .setMessage("Move ${file.name} to the Recycle Bin?")
                                    .setNegativeButton("Cancel", null)
                                    .setPositiveButton("Delete") { _, _ ->
                                        val ok = ScreenRecorderStore.delete(context, file)
                                        Toast.makeText(
                                            context,
                                            if (ok) "Moved to Recycle Bin" else "Could not delete recording",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        recordings = ScreenRecorderStore.list(context)
                                    }
                                    .show()
                            }) {
                                Icon(AppIcons.Delete, contentDescription = "Delete")
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ToggleRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

private fun durationSuffix(durationSec: Int, elapsedMs: Long): String {
    if (durationSec <= 0) return ""
    val remaining = ((durationSec * 1000L - elapsedMs) / 1000L).coerceAtLeast(0L)
    return " · ${remaining}s left"
}

private fun durationLabel(seconds: Int): String = when (seconds) {
    0 -> "Off"
    30 -> "30s"
    60 -> "1m"
    180 -> "3m"
    else -> "${seconds}s"
}

private fun formatElapsed(ms: Long): String {
    val seconds = ms / 1000
    return String.format(Locale.getDefault(), "%02d:%02d", seconds / 60, seconds % 60)
}

private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.getDefault(), "%.1f KB", kb)
    val mb = kb / 1024.0
    return String.format(Locale.getDefault(), "%.1f MB", mb)
}
