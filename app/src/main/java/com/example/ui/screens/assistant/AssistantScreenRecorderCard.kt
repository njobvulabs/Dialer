package com.example.ui.screens.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.ui.components.AppIcons
import com.example.util.InternalAudioCapture
import com.example.util.ScreenRecorderController
import com.example.util.ScreenRecorderQuality
import java.util.Locale

@Composable
fun AssistantScreenRecorderCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by ScreenRecorderController.state.collectAsState()
    val options = state.options
    val active = state.isStarting || state.isRecording || state.countdownSec > 0
    val systemAudioSupported = InternalAudioCapture.supported()

    LaunchedEffect(Unit) {
        ScreenRecorderController.ensureLoaded(context)
    }

    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = AppIcons.FiberManualRecord,
                    contentDescription = null,
                    tint = if (state.isRecording) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text("Screen recorder", style = MaterialTheme.typography.titleSmall)
                    Text(
                        when {
                            state.countdownSec > 0 -> "Starting in ${state.countdownSec}…"
                            state.isStarting -> "Preparing…"
                            state.isRecording && state.isPaused -> "Paused ${formatElapsed(state.elapsedMs)}"
                            state.isRecording -> "Recording ${formatElapsed(state.elapsedMs)}"
                            else -> "Ready"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ScreenRecorderQuality.values().forEach { quality ->
                    FilterChip(
                        selected = options.quality == quality,
                        enabled = !active,
                        onClick = {
                            ScreenRecorderController.updateOptions(context, options.copy(quality = quality))
                        },
                        label = { Text(quality.label) }
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (options.micEnabled) AppIcons.Mic else AppIcons.MicOff,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text("Mic", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(
                    checked = options.micEnabled,
                    enabled = !active,
                    onCheckedChange = {
                        ScreenRecorderController.updateOptions(context, options.copy(micEnabled = it))
                    }
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = AppIcons.VolumeUp,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text("System audio", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(
                    checked = options.systemAudioEnabled && systemAudioSupported,
                    enabled = !active && systemAudioSupported,
                    onCheckedChange = {
                        ScreenRecorderController.updateOptions(
                            context,
                            options.copy(systemAudioEnabled = it)
                        )
                    }
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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

            Text(
                when {
                    !systemAudioSupported -> "System playback capture needs Android 10 or newer."
                    options.systemAudioEnabled && options.micEnabled ->
                        "System playback and mic will be mixed into one audio track."
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
                        imageVector = if (active) AppIcons.Stop else AppIcons.FiberManualRecord,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when {
                            state.countdownSec > 0 -> "Starting…"
                            state.isStarting -> "Starting…"
                            state.isRecording -> "Stop"
                            else -> "Start"
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
                            imageVector = if (state.isPaused) AppIcons.PlayArrow else AppIcons.Pause,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
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
            state.outputPath?.let { path ->
                Text(
                    "Last file: $path",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.heightIn(max = 40.dp)
                )
            }
        }
    }
}

private fun formatElapsed(ms: Long): String {
    val seconds = ms / 1000
    return String.format(Locale.getDefault(), "%02d:%02d", seconds / 60, seconds % 60)
}

private fun durationLabel(seconds: Int): String = when (seconds) {
    0 -> "Off"
    30 -> "30s"
    60 -> "1m"
    180 -> "3m"
    else -> "${seconds}s"
}
