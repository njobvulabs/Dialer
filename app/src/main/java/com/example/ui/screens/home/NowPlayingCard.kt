package com.example.ui.screens.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.service.AudioEngine
import com.example.service.AudioPlaybackService
import com.example.ui.components.AppIcons
import com.example.ui.theme.AppCornerRadius
import kotlinx.coroutines.delay

@Composable
fun NowPlayingCard(
    modifier: Modifier = Modifier,
    onOpen: () -> Unit
) {
    val context = LocalContext.current
    val title = AudioEngine.title.value
    val artist = AudioEngine.artist.value
    val playing = AudioEngine.playing.value
    val artwork = AudioEngine.artworkState.value
    val duration = AudioEngine.durationMs.value
    val image = remember(artwork) { artwork?.asImageBitmap() }
    val onPrev = AudioEngine.onPrev ?: { AudioEngine.stepDefault(-1) }
    val onNext = AudioEngine.onNext ?: { AudioEngine.stepDefault(1) }
    val onToggle = AudioEngine.onToggle ?: { AudioEngine.toggleDefault() }
    var positionMs by remember { mutableLongStateOf(0L) }
    var barWidth by remember { mutableIntStateOf(1) }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(playing, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                positionMs = AudioEngine.positionMs()
                delay(if (playing) 500L else 2_000L)
            }
        }
    }

    val progress = if (duration > 0L) {
        (positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    } else 0f

    GlassPanel(
        modifier = modifier.fillMaxWidth(),
        shape = AppCornerRadius.md,
        elevation = 3.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 4.dp, top = 10.dp)
                    .clickable(onClick = onOpen),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(AppCornerRadius.sm)
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    if (image != null) {
                        Image(
                            bitmap = image,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            AppIcons.MusicNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title.ifBlank { "Now playing" },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (artist.isNotBlank()) {
                        Text(
                            artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(onClick = onPrev, modifier = Modifier.size(36.dp)) {
                    Icon(AppIcons.SkipPrevious, contentDescription = "Previous track")
                }
                IconButton(onClick = onToggle, modifier = Modifier.size(40.dp)) {
                    Icon(
                        if (playing) AppIcons.Pause else AppIcons.PlayArrow,
                        contentDescription = if (playing) "Pause" else "Play"
                    )
                }
                IconButton(onClick = onNext, modifier = Modifier.size(36.dp)) {
                    Icon(AppIcons.SkipNext, contentDescription = "Next track")
                }
                IconButton(
                    onClick = {
                        context.startService(
                            AudioPlaybackService.newIntent(
                                context,
                                AudioPlaybackService.ACTION_STOP
                            )
                        )
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(AppIcons.Close, contentDescription = "Stop playback")
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
                        .height(2.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }
    }
}
