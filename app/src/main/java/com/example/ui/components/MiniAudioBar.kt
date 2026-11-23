package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.service.AudioEngine
import com.example.service.AudioPlaybackService
import kotlinx.coroutines.delay

/**
 * Compact playback bar shown above the bottom navigation whenever music is
 * playing outside the Audio Player app. Tap the title to reopen the player;
 * tap the progress line to seek without opening the full player.
 */
@Composable
fun MiniAudioBar(
    visible: Boolean,
    onOpenPlayer: () -> Unit
) {
    val context = LocalContext.current
    val playing by AudioEngine.playing
    var positionMs by remember { mutableLongStateOf(0L) }
    var barWidth by remember { mutableIntStateOf(1) }

    // The bar only exists while the app is foregrounded, so a 500 ms poll here is
    // cheap (and it stops as soon as playback pauses).
    LaunchedEffect(playing) {
        while (true) {
            positionMs = AudioEngine.positionMs()
            delay(if (playing) 500L else 2_000L)
        }
    }

    if (!visible) return

    val duration = AudioEngine.durationMs.value
    val progress = if (duration > 0L) {
        (positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    } else 0f

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 4.dp),
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    AppIcons.MusicNote, null,
                    Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onOpenPlayer() }
                        .padding(horizontal = 8.dp)
                ) {
                    Text(
                        AudioEngine.title.value.ifBlank { "NJ-Dialer Audio" },
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        if (playing) "Playing" else "Paused",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(onClick = { AudioEngine.onPrev?.invoke() }) {
                    Icon(AppIcons.SkipPrevious, "Previous", Modifier.size(22.dp))
                }
                IconButton(onClick = {
                    val engineToggle = AudioEngine.onToggle
                    val p = AudioEngine.player
                    if (engineToggle != null) {
                        engineToggle()
                    } else if (p != null) {
                        runCatching {
                            if (p.isPlaying) p.pause() else p.start()
                            AudioEngine.playing.value = p.isPlaying
                        }
                        AudioPlaybackService.update(context)
                    }
                }) {
                    Icon(
                        if (playing) AppIcons.Pause else AppIcons.PlayArrow,
                        if (playing) "Pause" else "Play",
                        Modifier.size(24.dp)
                    )
                }
                IconButton(onClick = { AudioEngine.onNext?.invoke() }) {
                    Icon(AppIcons.SkipNext, "Next", Modifier.size(22.dp))
                }
                IconButton(onClick = {
                    context.startService(
                        AudioPlaybackService.newIntent(context, AudioPlaybackService.ACTION_STOP)
                    )
                }) {
                    Icon(AppIcons.Close, "Stop", Modifier.size(20.dp))
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(18.dp)
                    .onSizeChanged { barWidth = it.width.coerceAtLeast(1) }
                    .pointerInput(duration) {
                        detectTapGestures { offset ->
                            if (duration > 0L) {
                                val fraction = (offset.x / barWidth).coerceIn(0f, 1f)
                                AudioEngine.seekTo((duration * fraction).toLong())
                                positionMs = AudioEngine.positionMs()
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(MaterialTheme.colorScheme.surface),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }
    }
}
