package com.example.ui.screens.media

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.Build
import android.util.Rational
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.MimeTypeMap
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.ui.components.AppIcons
import com.example.ui.components.AppSearchField
import com.example.ui.theme.AppCornerRadius
import com.example.ui.components.FloatingTopBar
import com.example.util.EmbeddedSubtitles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

// -----------------------------------------------------------------------------
// Data Models & Logic
// -----------------------------------------------------------------------------

data class MediaFolder(
    val name: String,
    val path: String,
    val videos: List<File>
)

data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

data class AudioTrackOption(
    val index: Int,
    val name: String,
    val language: String
)

enum class VideoAspectMode(val label: String) {
    FIT("Fit"),
    CROP("Crop / Fill"),
    STRETCH("Stretch"),
    RATIO_16_9("16:9")
}

private enum class RepeatMode(val label: String) {
    OFF("Off"),
    ONE("One"),
    ALL("All")
}

private fun isVideoFile(file: File): Boolean {
    val extensions = setOf("mp4", "mkv", "webm", "avi", "3gp", "mov", "flv", "m4v", "ts")
    return file.isFile && file.extension.lowercase() in extensions
}

fun groupVideosByFolder(rootDirectory: File): List<MediaFolder> {
    val videoFiles = mutableListOf<File>()

    fun scanRecursively(dir: File) {
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) {
                scanRecursively(child)
            } else if (isVideoFile(child)) {
                videoFiles.add(child)
            }
        }
    }

    scanRecursively(rootDirectory)

    return videoFiles
        .groupBy { it.parentFile }
        .mapNotNull { (parentDir, files) ->
            if (parentDir != null && files.isNotEmpty()) {
                MediaFolder(
                    name = parentDir.name.ifEmpty { "Root" },
                    path = parentDir.absolutePath,
                    videos = files.sortedBy { it.name.lowercase() }
                )
            } else null
        }
        .sortedBy { it.name.lowercase() }
}

// -----------------------------------------------------------------------------
// Custom Aspect Video View
// -----------------------------------------------------------------------------

class AspectVideoView(context: Context) : FrameLayout(context) {
    private val playerView = PlayerView(context).apply {
        useController = false
        layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true
        )
        .setHandleAudioBecomingNoisy(true)
        .setSeekParameters(SeekParameters.EXACT)
        .build()

    var mode: VideoAspectMode = VideoAspectMode.FIT
        set(value) {
            field = value
            applyResizeMode()
        }
    var videoWidth: Int = 0
        private set
    var videoHeight: Int = 0
        private set

    var onPreparedListener: ((ExoPlayer) -> Unit)? = null
    var onCompletionListener: (() -> Unit)? = null
    var onErrorListener: ((PlaybackException) -> Boolean)? = null
    var onBufferingChanged: ((Boolean) -> Unit)? = null
    var onRenderingStarted: (() -> Unit)? = null
    var onSeekCompleteListener: (() -> Unit)? = null
    var onTracksChangedListener: ((List<AudioTrackOption>, Int) -> Unit)? = null

    private var preparedNotified = false

    init {
        addView(playerView)
        playerView.setPlayer(player)
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> onBufferingChanged?.invoke(true)
                    Player.STATE_READY -> {
                        onBufferingChanged?.invoke(false)
                        if (!preparedNotified) {
                            preparedNotified = true
                            onPreparedListener?.invoke(player)
                        }
                    }
                    Player.STATE_ENDED -> onCompletionListener?.invoke()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                onBufferingChanged?.invoke(false)
                onErrorListener?.invoke(error)
            }

            override fun onVideoSizeChanged(size: VideoSize) {
                videoWidth = size.width
                videoHeight = size.height
                applyResizeMode()
                requestLayout()
            }

            override fun onRenderedFirstFrame() {
                onRenderingStarted?.invoke()
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                discontinuityReason: Int
            ) {
                if (discontinuityReason == Player.DISCONTINUITY_REASON_SEEK) {
                    onSeekCompleteListener?.invoke()
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                val options = mutableListOf<AudioTrackOption>()
                var selected = -1
                tracks.groups.forEachIndexed { groupIndex, group ->
                    if (group.type != C.TRACK_TYPE_AUDIO) return@forEachIndexed
                    for (trackIndex in 0 until group.length) {
                        if (!group.isTrackSupported(trackIndex)) continue
                        val format = group.getTrackFormat(trackIndex)
                        val encoded = (groupIndex shl 16) or trackIndex
                        val label = format.label?.takeIf { it.isNotBlank() }
                            ?: "Audio Track #${options.size + 1}"
                        val language = format.language?.takeIf { it.isNotBlank() } ?: "Unknown"
                        options.add(AudioTrackOption(encoded, label, language))
                        if (group.isTrackSelected(trackIndex)) selected = encoded
                    }
                }
                onTracksChangedListener?.invoke(options, selected)
            }
        })
        applyResizeMode()
    }

    val currentPosition: Int
        get() = player.currentPosition.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    val duration: Int
        get() = if (player.duration == C.TIME_UNSET) 0 else player.duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    val isPlaying: Boolean
        get() = player.isPlaying

    val playWhenReady: Boolean
        get() = player.playWhenReady

    val audioSessionId: Int
        get() = player.audioSessionId

    fun setVideoPath(path: String) {
        if (path.isBlank()) return
        val uri = if (path.contains("://")) Uri.parse(path) else Uri.fromFile(File(path))
        preparedNotified = false
        player.setMediaItem(MediaItem.fromUri(uri))
        player.playWhenReady = false
        player.prepare()
    }

    fun setAudioFocusRequest(focusType: Int) = Unit

    fun setVideoDimensions(width: Int, height: Int) {
        if (videoWidth == width && videoHeight == height) return
        videoWidth = width
        videoHeight = height
        applyResizeMode()
        requestLayout()
    }

    fun start() {
        player.playWhenReady = true
    }

    fun pause() {
        player.playWhenReady = false
    }

    fun seekTo(positionMs: Int) {
        player.seekTo(positionMs.toLong())
    }

    fun release() {
        player.release()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val specW = View.MeasureSpec.getSize(widthMeasureSpec)
        val specH = View.MeasureSpec.getSize(heightMeasureSpec)

        if (mode == VideoAspectMode.RATIO_16_9) {
            val targetRatio = 16f / 9f
            val w: Int
            val h: Int
            if (specW.toFloat() / specH.toFloat() > targetRatio) {
                w = (specH * targetRatio).toInt()
                h = specH
            } else {
                w = specW
                h = (specW / targetRatio).toInt()
            }
            setMeasuredDimension(w, h)
            return
        }

        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun applyResizeMode() {
        playerView.setResizeMode(
            when (mode) {
                VideoAspectMode.FIT, VideoAspectMode.RATIO_16_9 -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                VideoAspectMode.CROP -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                VideoAspectMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            }
        )
    }
}

// -----------------------------------------------------------------------------
// App Navigation Entry Point
// -----------------------------------------------------------------------------

@Composable
fun VLCStyleVideoApp(
    rootStorageDir: File = File("/sdcard")
) {
    var currentFolder by remember { mutableStateOf<MediaFolder?>(null) }
    var currentPlayingVideo by remember { mutableStateOf<File?>(null) }

    when {
        currentPlayingVideo != null -> {
            VideoPlayerScreen(
                videoFile = currentPlayingVideo!!,
                onBack = { currentPlayingVideo = null }
            )
        }
        currentFolder != null -> {
            FolderVideosScreen(
                folder = currentFolder!!,
                onBack = { currentFolder = null },
                onSelectVideo = { video -> currentPlayingVideo = video }
            )
        }
        else -> {
            FolderGroupingLibraryScreen(
                rootDirectory = rootStorageDir,
                onSelectFolder = { folder -> currentFolder = folder }
            )
        }
    }
}

// -----------------------------------------------------------------------------
// VLC-Style Folder Grouping Library Screen
// -----------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderGroupingLibraryScreen(
    rootDirectory: File,
    onSelectFolder: (MediaFolder) -> Unit
) {
    var folders by remember { mutableStateOf<List<MediaFolder>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var showSearchBar by remember { mutableStateOf(false) }
    var isGridView by remember { mutableStateOf(true) }

    LaunchedEffect(rootDirectory) {
        isLoading = true
        withContext(Dispatchers.IO) {
            folders = groupVideosByFolder(rootDirectory)
        }
        isLoading = false
    }

    val filteredFolders = remember(folders, searchQuery) {
        if (searchQuery.isBlank()) folders
        else folders.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
                    it.videos.any { v -> v.name.contains(searchQuery, ignoreCase = true) }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            FloatingTopBar {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Video Directories", fontWeight = FontWeight.Bold)
                    Text(
                        text = "${folders.size} folders detected",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { showSearchBar = !showSearchBar }) {
                    Icon(
                        imageVector = if (showSearchBar) AppIcons.Close else AppIcons.Search,
                        contentDescription = "Search"
                    )
                }
                IconButton(onClick = { isGridView = !isGridView }) {
                    Icon(
                        imageVector = if (isGridView) AppIcons.ViewList else AppIcons.GridView,
                        contentDescription = "Toggle Layout"
                    )
                }
            }

            AnimatedVisibility(visible = showSearchBar) {
                AppSearchField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = "Search folders or videos...",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Scanning videos & grouping directories...")
                    }
                }
            } else if (filteredFolders.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            AppIcons.FolderOff,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("No video folders found", style = MaterialTheme.typography.titleMedium)
                    }
                }
            } else {
                if (isGridView) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(filteredFolders) { folder ->
                            FolderGridCard(folder = folder, onClick = { onSelectFolder(folder) })
                        }
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(filteredFolders) { folder ->
                            FolderListRow(folder = folder, onClick = { onSelectFolder(folder) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FolderGridCard(folder: MediaFolder, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(Color(0xFFFF9800).copy(alpha = 0.2f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = AppIcons.Folder,
                    contentDescription = null,
                    tint = Color(0xFFFF9800),
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = folder.name,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${folder.videos.size} videos",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun FolderListRow(folder: MediaFolder, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(Color(0xFFFF9800).copy(alpha = 0.2f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = AppIcons.Folder,
                    contentDescription = null,
                    tint = Color(0xFFFF9800),
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = folder.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = folder.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = CircleShape
            ) {
                Text(
                    text = "${folder.videos.size}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Folder Videos View
// -----------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderVideosScreen(
    folder: MediaFolder,
    onBack: () -> Unit,
    onSelectVideo: (File) -> Unit
) {
    BackHandler { onBack() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            FloatingTopBar {
                IconButton(onClick = onBack) {
                    Icon(AppIcons.ArrowBack, contentDescription = "Back")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(folder.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        text = "${folder.videos.size} items",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
            items(folder.videos) { videoFile ->
                Card(
                    onClick = { onSelectVideo(videoFile) },
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), MaterialTheme.shapes.small),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                AppIcons.Movie,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = videoFile.name,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "${formatFileSize(videoFile.length())} • ${videoFile.extension.uppercase()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            AppIcons.PlayCircle,
                            contentDescription = "Play",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }
        }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> String.format("%.2f GB", gb)
        mb >= 1.0 -> String.format("%.1f MB", mb)
        else -> String.format("%.0f KB", kb)
    }
}

// -----------------------------------------------------------------------------
// Comprehensive Video Player Screen
// -----------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoPlayerScreen(
    videoFile: File? = null,
    videoUrl: String? = null,
    playlist: List<File> = emptyList(),
    onBack: () -> Unit
) {
    // Playlist navigation is only meaningful for a real local playlist
    // (multiple files); never for a single file or a stream URL.
    val canNavigate = videoUrl == null && playlist.size > 1

    // Track index within the playlist, resolved by path so currentFile drives playback.
    var playlistIndex by remember(playlist, videoFile, videoUrl) {
        mutableIntStateOf(
            if (videoUrl != null || playlist.isEmpty()) 0
            else playlist.indexOfFirst { it.absolutePath == videoFile?.absolutePath }
                .let { if (it < 0) playlist.indexOfFirst { p -> p.name == videoFile?.name } else it }
                .let { if (it < 0) 0 else it }
        )
    }

    // The currently active file (either the explicitly-opened one or the playlist item).
    val currentFile = if (videoUrl != null) null else playlist.getOrNull(playlistIndex) ?: videoFile

    // A stream (e.g. a Samba HTTP proxy URL) has no local File, so derive a
    // stable source path/key for the player and skip local-file-only logic.
    val sourcePath = videoUrl ?: currentFile?.absolutePath ?: ""
    val sourceKey = videoUrl ?: currentFile?.absolutePath ?: ""
    val displayName = currentFile?.name ?: videoUrl?.substringAfterLast('/') ?: "Stream"

    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val playerPrefs = remember {
        context.getSharedPreferences("video_player", Context.MODE_PRIVATE)
    }

    // Fullscreen immersive video is BACK (user decision 2026-10-06): every
    // system bar hides while this screen is open, so the picture runs edge to
    // edge. That is owned by MainActivity's shouldBeImmersive flag +
    // SystemBarsImmersiveUtility (see the comment there) — this screen must
    // NOT hide/show bars itself, or it would undo the flag when it disposes.
    // Its own control rows keep statusBarsPadding()/navigationBarsPadding(),
    // which collapse to 0 while the bars are hidden.
    // The orientation reset on dispose is unrelated and MUST stay: a landscape
    // lock would otherwise outlive the player and leave the whole app sideways.
    DisposableEffect(Unit) {
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    var isPlaying by remember { mutableStateOf(true) }
    var isPrepared by remember { mutableStateOf(false) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var playbackErrorDetail by remember { mutableStateOf<String?>(null) }
    var currentSpeed by remember {
        mutableFloatStateOf(playerPrefs.getFloat("speed", 1.0f).coerceIn(0.25f, 3.0f))
    }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }
    var isBuffering by remember { mutableStateOf(false) }
    var reloadToken by remember { mutableIntStateOf(0) }
    var playbackPausedByBackground by remember { mutableStateOf(false) }
    var pendingSeekMs by remember { mutableStateOf<Int?>(null) }
    var seekRecoveryTried by remember { mutableStateOf(false) }
    var seekInProgress by remember { mutableStateOf(false) }
    var seekDragStartMs by remember { mutableIntStateOf(0) }
    var seekDragDeltaMs by remember { mutableIntStateOf(0) }

    var currentPositionMs by remember { mutableIntStateOf(0) }
    var durationMs by remember { mutableIntStateOf(1) }
    var exoPlayerRef by remember { mutableStateOf<ExoPlayer?>(null) }
    var videoViewRef by remember { mutableStateOf<AspectVideoView?>(null) }

    // --- Resume position persistence ---
    val videoPrefs = remember {
        context.getSharedPreferences("video_resume", Context.MODE_PRIVATE)
    }

    var subtitles by remember { mutableStateOf<List<SubtitleCue>>(emptyList()) }
    var subtitleFile by remember { mutableStateOf<File?>(null) }
    var subtitleSourceLabel by remember { mutableStateOf<String?>(null) }
    var subtitlesEnabled by remember { mutableStateOf(true) }
    var showSubtitleDialog by remember { mutableStateOf(false) }

    val subtitlePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { selectedUri ->
            scope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openInputStream(selectedUri)?.use { stream ->
                        val content = stream.bufferedReader().use { r -> r.readText() }
                        val parsed = parseSrtOrVtt(content)
                        withContext(Dispatchers.Main) {
                            subtitles = parsed
                            subtitlesEnabled = true
                            subtitleFile = File(selectedUri.path ?: "External Subtitle")
                            subtitleSourceLabel = null
                            Toast.makeText(context, "Loaded ${parsed.size} subtitle cues", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Failed to read subtitle file", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    var hardwareEq by remember { mutableStateOf<Equalizer?>(null) }
    var hardwareBassBoost by remember { mutableStateOf<BassBoost?>(null) }
    var activeEqPreset by remember {
        mutableStateOf(playerPrefs.getString("eq_preset", "Flat") ?: "Flat")
    }
    var bassBoostStrength by remember {
        mutableIntStateOf(playerPrefs.getInt("bass_strength", 0).coerceIn(0, 1000))
    }
    var showEqDialog by remember { mutableStateOf(false) }

    var audioTracks by remember { mutableStateOf<List<AudioTrackOption>>(emptyList()) }
    var selectedAudioTrackIndex by remember { mutableIntStateOf(-1) }
    var showAudioTrackDialog by remember { mutableStateOf(false) }

    var isScreenLocked by remember { mutableStateOf(false) }
    var aspectMode by remember {
        mutableStateOf(
            VideoAspectMode.entries.firstOrNull {
                it.name == playerPrefs.getString("aspect", VideoAspectMode.FIT.name)
            } ?: VideoAspectMode.FIT
        )
    }
    var showAspectDialog by remember { mutableStateOf(false) }
    var subtitleDelayMs by remember { mutableLongStateOf(0L) }
    var abLoopA by remember { mutableStateOf<Long?>(null) }
    var abLoopB by remember { mutableStateOf<Long?>(null) }
    var showMpvToolsDialog by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showPlaylistSheet by remember { mutableStateOf(false) }
    var showDetailsDialog by remember { mutableStateOf(false) }
    var repeatMode by remember {
        mutableStateOf(
            RepeatMode.entries.firstOrNull {
                it.name == playerPrefs.getString("repeat", RepeatMode.OFF.name)
            } ?: RepeatMode.OFF
        )
    }
    var sleepTimerEndMs by remember { mutableStateOf<Long?>(null) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }

    // Popups/dialogs must not trigger the 4s auto-hide underneath them.
    val controlsBlocked = showMoreMenu || showPlaylistSheet || showAspectDialog ||
        showSpeedDialog || showSubtitleDialog || showAudioTrackDialog || showEqDialog ||
        showMpvToolsDialog || showDetailsDialog || showSleepTimerDialog

    DisposableEffect(exoPlayerRef) {
        val player = exoPlayerRef
        if (player != null) {
            runCatching {
                val sessionId = player.audioSessionId
                if (sessionId != 0) {
                    val eq = Equalizer(0, sessionId).apply {
                        enabled = activeEqPreset != "Flat"
                    }
                    applyHardwareEqPreset(eq, activeEqPreset)
                    val bb = BassBoost(0, sessionId).apply {
                        setStrength(bassBoostStrength.toShort())
                        enabled = bassBoostStrength > 0
                    }
                    hardwareEq = eq
                    hardwareBassBoost = bb
                }
            }
        }
        onDispose {
            runCatching {
                hardwareEq?.release()
                hardwareBassBoost?.release()
                hardwareEq = null
                hardwareBassBoost = null
            }
        }
    }

    // Subtitle auto-load, in priority order:
    //  1. a sidecar <name>.srt / <name>.vtt next to the video (unchanged behaviour),
    //  2. the subtitle track *inside* the file. The player can list an
    //     internal subtitle track but never draws it, so EmbeddedSubtitles decodes it
    //     into the same cue list the overlay already renders. A sidecar that parses to
    //     nothing is treated as absent so a broken file cannot hide a good embedded one.
    LaunchedEffect(currentFile) {
        val current = currentFile
        if (current == null) return@LaunchedEffect
        val parent = current.parentFile
        var cues: List<SubtitleCue>? = null
        var file: File? = null
        var label: String? = null

        if (parent != null && parent.exists()) {
            val baseName = current.nameWithoutExtension
            val matchingSrt = parent.listFiles()?.firstOrNull {
                it.nameWithoutExtension.equals(baseName, ignoreCase = true) &&
                        (it.extension.equals("srt", ignoreCase = true) || it.extension.equals("vtt", ignoreCase = true))
            }
            if (matchingSrt != null) {
                val parsed = withContext(Dispatchers.IO) {
                    runCatching { parseSrtOrVtt(matchingSrt.readText()) }.getOrNull()
                }
                if (!parsed.isNullOrEmpty()) {
                    cues = parsed
                    file = matchingSrt
                }
            }
        }

        if (cues == null) {
            val embedded = withContext(Dispatchers.IO) { EmbeddedSubtitles.load(current) }
            if (embedded != null && embedded.cues.isNotEmpty()) {
                cues = embedded.cues.map { SubtitleCue(it.startMs, it.endMs, it.text) }
                label = embedded.label
            }
        }

        if (cues != null) {
            subtitles = cues
            subtitlesEnabled = true
            subtitleFile = file
            subtitleSourceLabel = label
        }
    }

    var seekBadgeText by remember { mutableStateOf<String?>(null) }
    var gestureOverlayType by remember { mutableStateOf<String?>(null) }
    var gestureValuePercentage by remember { mutableFloatStateOf(0f) }

    fun seekPlayback(targetMs: Int, badge: String? = null) {
        val vv = videoViewRef
        if (!isPrepared || playbackError != null || vv == null) return
        val maxMs = (vv.duration - 1).coerceAtLeast(0)
        if (maxMs <= 0) return
        val clamped = targetMs.coerceIn(0, maxMs)
        seekInProgress = true
        pendingSeekMs = clamped
        if (badge != null) seekBadgeText = badge
        val seekStarted = runCatching { vv.seekTo(clamped) }.isSuccess
        currentPositionMs = clamped
        if (!seekStarted) {
            seekInProgress = false
            if (!seekRecoveryTried) {
                seekRecoveryTried = true
                playbackPausedByBackground = false
                isPrepared = false
                isPlaying = true
                showControls = true
                reloadToken++
            } else {
                pendingSeekMs = null
                playbackError = "Unable to seek this video"
                playbackErrorDetail = "The player could not move to that position."
            }
        }
    }

    fun selectAudioTrack(encodedIndex: Int) {
        val player = exoPlayerRef ?: return
        val groupIndex = encodedIndex shr 16
        val trackIndex = encodedIndex and 0xffff
        val group = player.currentTracks.groups.getOrNull(groupIndex) ?: return
        runCatching {
            val params = TrackSelectionParameters.Builder()
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
                .build()
            player.trackSelectionParameters = params
        }
    }

    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat().coerceAtLeast(1f) }
    var currentVolume by remember { mutableFloatStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()) }
    var currentBrightness by remember {
        mutableFloatStateOf(
            activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0f } ?: 0.5f
        )
    }

    val lifecycleOwner = LocalLifecycleOwner.current

    fun persistPosition(file: File?, position: Int, duration: Int) {
        if (file == null) return
        if (duration > 0 && position > 15_000 && position < duration * 0.95) {
            videoPrefs.edit().putInt(file.absolutePath, position).apply()
        } else {
            videoPrefs.edit().remove(file.absolutePath).apply()
        }
    }

    fun savePosition() {
        val file = if (videoUrl != null) null else playlist.getOrNull(playlistIndex) ?: videoFile
        persistPosition(
            file,
            videoViewRef?.currentPosition ?: currentPositionMs,
            videoViewRef?.duration ?: durationMs
        )
    }

    fun exitPlayer() {
        savePosition()
        onBack()
    }

    BackHandler {
        if (isScreenLocked) {
            isScreenLocked = false
            showControls = true
            Toast.makeText(context, "Screen unlocked", Toast.LENGTH_SHORT).show()
        } else {
            exitPlayer()
        }
    }

    DisposableEffect(Unit) {
        onDispose { savePosition() }
    }

    DisposableEffect(Unit) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    activity?.window?.attributes = activity?.window?.attributes?.apply {
                        screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    }
                    savePosition()
                    if (activity?.isInPictureInPictureMode != true) {
                        playbackPausedByBackground = true
                        val vv = videoViewRef
                        if (vv?.isPlaying == true) {
                            vv.pause()
                            isPlaying = false
                            showControls = true
                        }
                    }
                }
                Lifecycle.Event.ON_RESUME -> {
                    activity?.window?.attributes = activity?.window?.attributes?.apply {
                        screenBrightness = currentBrightness
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            runCatching {
                activity?.window?.attributes = activity?.window?.attributes?.apply {
                    screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            }
        }
    }

    val noisyReceiver = remember {
        object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                    val vv = videoViewRef
                    if (vv?.isPlaying == true) {
                        vv.pause()
                        isPlaying = false
                        showControls = true
                    }
                }
            }
        }
    }
    DisposableEffect(Unit) {
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        ContextCompat.registerReceiver(
            context,
            noisyReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose {
            runCatching { context.unregisterReceiver(noisyReceiver) }
        }
    }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val isInPipMode = remember(configuration) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activity?.isInPictureInPictureMode == true
        } else false
    }

    // System-bar hiding is NOT done here — see the note above the orientation
    // DisposableEffect: MainActivity's shouldBeImmersive flag owns it for the
    // whole time this screen is open (entering a video hides all bars, leaving
    // it shows them again). isInPipMode is still consumed by the control
    // auto-hide and the orientation effects below.

    LaunchedEffect(showControls, isPlaying, isPrepared, playbackError, isInPipMode, controlsBlocked) {
        if (showControls && isPlaying && isPrepared && playbackError == null &&
            !isInPipMode && !controlsBlocked
        ) {
            delay(4000)
            if (!controlsBlocked) showControls = false
        }
    }

    LaunchedEffect(videoViewRef, isPlaying, abLoopA, abLoopB, lifecycleOwner) {
        var tickCount = 0
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                videoViewRef?.let { vv ->
                    if (isPrepared && vv.playWhenReady != isPlaying) isPlaying = vv.playWhenReady
                    if (vv.isPlaying) {
                        currentPositionMs = vv.currentPosition
                        if (vv.duration > 0) durationMs = vv.duration
                        // Save position every ~2s (8 x 250 ms) while playing.
                        if (++tickCount % 8 == 0 && vv.duration > 0) {
                            persistPosition(
                                if (videoUrl != null) null else playlist.getOrNull(playlistIndex) ?: videoFile,
                                vv.currentPosition,
                                vv.duration
                            )
                        }

                        val loopA = abLoopA
                        val loopB = abLoopB
                        if (loopA != null && loopB != null && currentPositionMs >= loopB) {
                            seekPlayback(loopA.toInt())
                        }
                    }
                }
                delay(if (isPlaying) 250L else 1_000L)
            }
        }
    }

    LaunchedEffect(sleepTimerEndMs) {
        val end = sleepTimerEndMs ?: return@LaunchedEffect
        while (true) {
            val remaining = end - System.currentTimeMillis()
            if (remaining <= 0) {
                playbackPausedByBackground = true
                videoViewRef?.pause()
                isPlaying = false
                showControls = true
                sleepTimerEndMs = null
                Toast.makeText(context, "Sleep timer ended", Toast.LENGTH_SHORT).show()
                break
            }
            delay(1000)
        }
    }

    LaunchedEffect(seekBadgeText) {
        if (seekBadgeText != null) {
            delay(1000)
            seekBadgeText = null
        }
    }

    LaunchedEffect(gestureOverlayType) {
        if (gestureOverlayType != null) {
            delay(1000)
            gestureOverlayType = null
        }
    }

    fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity != null) {
            try {
                val vw = videoViewRef?.videoWidth ?: 0
                val vh = videoViewRef?.videoHeight ?: 0
                val rawAspect = if (vw > 0 && vh > 0) vw.toFloat() / vh else 16f / 9f
                val aspect = rawAspect.coerceIn(1f / 2.39f, 2.39f)
                val ratio = if (aspect >= 1f) {
                    Rational((aspect * 1000).toInt().coerceAtLeast(1), 1000)
                } else {
                    Rational(1000, (1000 / aspect).toInt().coerceAtLeast(1))
                }
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(ratio)
                    .build()
                activity.enterPictureInPictureMode(params)
            } catch (e: Exception) {
                Toast.makeText(context, "PiP Mode not supported", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun restartCurrent() {
        playbackPausedByBackground = false
        val vv = videoViewRef
        if (vv != null) {
            seekPlayback(0)
            vv.start()
        } else {
            reloadToken++
        }
        currentPositionMs = 0
        isPlaying = true
        showControls = true
        videoPrefs.edit().remove(sourceKey).apply()
    }

    fun retryPlayback() {
        playbackPausedByBackground = false
        exoPlayerRef = null
        videoViewRef = null
        isPrepared = false
        playbackError = null
        playbackErrorDetail = null
        pendingSeekMs = null
        seekRecoveryTried = false
        seekInProgress = false
        seekDragStartMs = 0
        seekDragDeltaMs = 0
        isPlaying = true
        isBuffering = false
        currentPositionMs = 0
        durationMs = 1
        showControls = true
        reloadToken++
    }

    // Jump to a playlist position. Resets per-track state so the re-keyed
    // AndroidView prepares the new file cleanly; the next video starts fresh.
    fun stepTo(index: Int) {
        if (!canNavigate) return
        val clamped = ((index % playlist.size) + playlist.size) % playlist.size
        if (clamped == playlistIndex) return
        savePosition()
        val nextFile = playlist[clamped]
        // Start fresh — don't resume a middle position from a previous watch.
        runCatching { videoPrefs.edit().remove(nextFile.absolutePath).apply() }
        playbackPausedByBackground = false
        playlistIndex = clamped
        currentPositionMs = 0
        durationMs = 1
        seekBadgeText = null
        exoPlayerRef = null
        videoViewRef = null
        isPlaying = true
        isPrepared = false
        isBuffering = false
        playbackError = null
        playbackErrorDetail = null
        pendingSeekMs = null
        seekRecoveryTried = false
        seekInProgress = false
        seekDragStartMs = 0
        seekDragDeltaMs = 0
        showControls = true
        subtitles = emptyList()
        subtitlesEnabled = true
        subtitleFile = null
        subtitleSourceLabel = null
        subtitleDelayMs = 0L
        abLoopA = null
        abLoopB = null
        audioTracks = emptyList()
        selectedAudioTrackIndex = -1
    }

    fun toggleOrientation() {
        activity?.let { act ->
            act.requestedOrientation = if (isLandscape) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        if (!isInPipMode && !isScreenLocked && playbackError == null) {
                            showControls = !showControls
                        }
                    },
                    onDoubleTap = { offset ->
                        if (isInPipMode || isScreenLocked || playbackError != null || !isPrepared) return@detectTapGestures
                        val width = size.width
                        val x = offset.x
                        val vv = videoViewRef ?: return@detectTapGestures
                        val currentPos = vv.currentPosition

                        if (x < width * 0.4f) {
                            seekPlayback(currentPos - 10000, "-10s")
                        } else if (x > width * 0.6f) {
                            seekPlayback(currentPos + 10000, "+10s")
                        } else {
                            if (vv.isPlaying) {
                                vv.pause()
                                isPlaying = false
                            } else {
                                playbackPausedByBackground = false
                                vv.start()
                                isPlaying = true
                            }
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        seekDragStartMs = videoViewRef?.currentPosition ?: currentPositionMs
                        seekDragDeltaMs = 0
                    },
                    onHorizontalDrag = { _, dragAmount ->
                        if (isInPipMode || isScreenLocked || playbackError != null || !isPrepared) {
                            return@detectHorizontalDragGestures
                        }
                        seekDragDeltaMs += (dragAmount * 150).toInt()
                        val preview = (seekDragStartMs + seekDragDeltaMs)
                            .coerceIn(0, durationMs.coerceAtLeast(1))
                        val seekSec = (preview - seekDragStartMs) / 1000
                        seekBadgeText = if (seekSec >= 0) "+${seekSec}s" else "${seekSec}s"
                    },
                    onDragEnd = {
                        if (!isInPipMode && !isScreenLocked && playbackError == null && isPrepared &&
                            seekDragDeltaMs != 0
                        ) {
                            seekPlayback(seekDragStartMs + seekDragDeltaMs)
                        }
                        seekDragDeltaMs = 0
                    },
                    onDragCancel = {
                        seekDragDeltaMs = 0
                    }
                )
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onVerticalDrag = { change, dragAmount ->
                        if (isInPipMode || isScreenLocked || playbackError != null) return@detectVerticalDragGestures
                        val width = size.width
                        val x = change.position.x
                        val sensitivity = 0.004f

                        if (x < width * 0.5f) {
                            currentBrightness = (currentBrightness - dragAmount * sensitivity).coerceIn(0.05f, 1.0f)
                            activity?.let { act ->
                                val lp = act.window.attributes
                                lp.screenBrightness = currentBrightness
                                act.window.attributes = lp
                            }
                            gestureOverlayType = "brightness"
                            gestureValuePercentage = currentBrightness
                        } else {
                            currentVolume = (currentVolume - dragAmount * sensitivity * maxVolume).coerceIn(0f, maxVolume)
                            audioManager.setStreamVolume(
                                AudioManager.STREAM_MUSIC,
                                currentVolume.toInt(),
                                0
                            )
                            gestureOverlayType = "volume"
                            gestureValuePercentage = currentVolume / maxVolume
                        }
                    }
                )
            }
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            // Re-key the view per source (and on retry) so changing the playlist
            // track or retrying a failed source re-prepares cleanly.
            key(sourceKey, reloadToken) {
            AndroidView(
                factory = { ctx ->
                    AspectVideoView(ctx).apply {
                        val view = this
                        videoViewRef = view
                        setAudioFocusRequest(AudioManager.AUDIOFOCUS_GAIN)
                        keepScreenOn = isPlaying && !isInPipMode
                        onBufferingChanged = { buffering -> isBuffering = buffering }
                        onRenderingStarted = {
                            playbackError = null
                            playbackErrorDetail = null
                            isBuffering = false
                        }
                        onSeekCompleteListener = {
                            seekInProgress = false
                            pendingSeekMs = null
                            seekRecoveryTried = false
                            currentPositionMs = view.currentPosition
                        }
                        onTracksChangedListener = { tracks, selected ->
                            audioTracks = tracks
                            selectedAudioTrackIndex = selected
                        }
                        onPreparedListener = { player ->
                            exoPlayerRef = player
                            player.setPlaybackSpeed(currentSpeed)
                            durationMs = view.duration.coerceAtLeast(1)
                            view.setVideoDimensions(view.videoWidth, view.videoHeight)
                            view.mode = aspectMode

                            val savedMs = videoPrefs.getInt(sourceKey, 0)
                            val resumeMs = pendingSeekMs ?: savedMs.takeIf {
                                it > 15_000 && it < view.duration * 0.95f
                            }
                            if (resumeMs != null && (resumeMs > 0 || pendingSeekMs == 0)) {
                                val retrySeek = pendingSeekMs != null
                                seekInProgress = true
                                val seekSucceeded = runCatching { view.seekTo(resumeMs) }.isSuccess
                                currentPositionMs = resumeMs
                                if (!seekSucceeded) {
                                    seekInProgress = false
                                    pendingSeekMs = null
                                    playbackError = "Unable to seek this video"
                                    playbackErrorDetail = "The player could not move to that position."
                                } else if (!retrySeek) {
                                    Toast.makeText(context, "Resumed at ${formatMs(resumeMs.toLong())}", Toast.LENGTH_SHORT).show()
                                }
                            }

                            if (playbackPausedByBackground) {
                                isPlaying = false
                            } else {
                                view.start()
                                isPlaying = true
                            }
                            isPrepared = true
                            isBuffering = false
                            playbackError = null
                            playbackErrorDetail = null
                            showControls = true
                        }
                        onCompletionListener = {
                            videoPrefs.edit().remove(sourceKey).apply()
                            when {
                                repeatMode == RepeatMode.ONE -> restartCurrent()
                                repeatMode == RepeatMode.ALL -> {
                                    if (canNavigate && playlist.size > 1) {
                                        stepTo(playlistIndex + 1)
                                    } else {
                                        restartCurrent()
                                    }
                                }
                                canNavigate && playlistIndex < playlist.lastIndex ->
                                    stepTo(playlistIndex + 1)
                                else -> {
                                    isPlaying = false
                                    showControls = true
                                }
                            }
                        }
                        onErrorListener = { error ->
                            isPrepared = false
                            isPlaying = false
                            isBuffering = false
                            if (seekInProgress && !seekRecoveryTried) {
                                seekRecoveryTried = true
                                seekInProgress = false
                                pendingSeekMs = null
                                seekDragDeltaMs = 0
                                playbackPausedByBackground = false
                                playbackError = null
                                playbackErrorDetail = null
                                isPlaying = true
                                showControls = true
                                exoPlayerRef = null
                                videoViewRef = null
                                videoPrefs.edit().remove(sourceKey).apply()
                                reloadToken++
                            } else {
                                playbackError = when (error.errorCode) {
                                    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                                    PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "Cannot read this file"
                                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> "Damaged or malformed video"
                                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                                    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "Unsupported format or codec"
                                    PlaybackException.ERROR_CODE_TIMEOUT -> "Playback timed out"
                                    else -> "Unable to play this file"
                                }
                                playbackErrorDetail = "${error.getErrorCodeName()}: ${error.message ?: ""}"
                            }
                            true
                        }
                        setVideoPath(sourcePath)
                    }
                },
                update = { videoView ->
                    videoViewRef = videoView
                    exoPlayerRef = videoView.player
                    videoView.mode = aspectMode
                    videoView.player.setPlaybackSpeed(currentSpeed)
                    videoView.keepScreenOn = isPlaying && isPrepared && !isInPipMode
                    if (isPrepared && isPlaying && !videoView.playWhenReady) {
                        videoView.start()
                    } else if (!isPlaying && videoView.playWhenReady) {
                        videoView.pause()
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            DisposableEffect(Unit) {
                val view = videoViewRef
                onDispose {
                    if (videoViewRef === view) videoViewRef = null
                    if (exoPlayerRef === view?.player) exoPlayerRef = null
                    view?.release()
                }
            }
            } // end key(sourceKey, reloadToken)
        }

        if (!isPrepared && !isInPipMode) {
            Surface(
                color = Color.Black.copy(alpha = 0.75f),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.align(Alignment.Center)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 36.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (playbackError != null) {
                        Icon(
                            imageVector = AppIcons.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(48.dp)
                        )
                    } else {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = playbackError ?: "Loading video...",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (playbackError != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = playbackErrorDetail ?: "The player could not open this source.",
                            color = Color.White.copy(alpha = 0.65f),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { retryPlayback() }) {
                                Text("Retry")
                            }
                            OutlinedButton(
                                onClick = { exitPlayer() },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                            ) {
                                Text("Close")
                            }
                        }
                    }
                }
            }
        }

        if (isBuffering && isPrepared && !isInPipMode) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 2.dp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 72.dp)
                    .size(28.dp)
            )
        }

        VideoSubtitleOverlay(
            modifier = Modifier.align(Alignment.BottomCenter),
            subtitles = subtitles,
            enabled = subtitlesEnabled,
            delayMs = subtitleDelayMs,
            positionMs = { currentPositionMs },
            showControls = showControls,
            isInPipMode = isInPipMode
        )

        AnimatedVisibility(
            visible = seekBadgeText != null,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.8f),
                shape = CircleShape,
                modifier = Modifier.size(92.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = if (seekBadgeText?.startsWith("+") == true) AppIcons.FastForward else AppIcons.FastRewind,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                        Text(
                            text = seekBadgeText ?: "",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = gestureOverlayType != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.85f),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.padding(24.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = if (gestureOverlayType == "brightness") AppIcons.BrightnessHigh else if (gestureValuePercentage > 0) AppIcons.VolumeUp else AppIcons.VolumeMute,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                    Column(horizontalAlignment = Alignment.Start) {
                        Text(
                            text = if (gestureOverlayType == "brightness") "Brightness" else "Volume",
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { gestureValuePercentage },
                            modifier = Modifier
                                .width(120.dp)
                                .height(6.dp)
                                .clip(AppCornerRadius.xs),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = Color.White.copy(alpha = 0.3f)
                        )
                    }
                    Text(
                        text = "${(gestureValuePercentage * 100).toInt()}%",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }
        }

        if (isScreenLocked) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
            ) {
                IconButton(
                    onClick = {
                        isScreenLocked = false
                        showControls = true
                        Toast.makeText(context, "Screen Unlocked", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .size(52.dp)
                        .background(Color.Black.copy(alpha = 0.75f), CircleShape)
                ) {
                    Icon(AppIcons.Lock, contentDescription = "Unlock Screen", tint = Color(0xFFFFD600), modifier = Modifier.size(28.dp))
                }
            }
        }

        if (!isInPipMode && !isScreenLocked && isPrepared && playbackError == null) {
            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter)
                            // The player is outside MainActivity's Scaffold, so no
                            // innerPadding reaches it. Without this the title bar
                            // sits under the status bar.
                            .statusBarsPadding()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { exitPlayer() }) {
                                Icon(AppIcons.ArrowBack, contentDescription = "Back", tint = Color.White)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = displayName,
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (subtitles.isNotEmpty()) {
                                IconButton(onClick = {
                                    subtitlesEnabled = !subtitlesEnabled
                                    Toast.makeText(
                                        context,
                                        if (subtitlesEnabled) "Subtitles on" else "Subtitles off",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }) {
                                    Icon(
                                        if (subtitlesEnabled) AppIcons.Subtitles else AppIcons.SubtitlesOff,
                                        contentDescription = if (subtitlesEnabled) "Hide subtitles" else "Show subtitles",
                                        tint = if (subtitlesEnabled) Color(0xFF22C55E) else Color.White
                                    )
                                }
                            }

                            IconButton(onClick = {
                                isScreenLocked = true
                                showControls = false
                                Toast.makeText(context, "Screen Locked. Tap Lock icon to unlock.", Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(AppIcons.LockOpen, contentDescription = "Lock Screen", tint = Color.White)
                            }

                            if (canNavigate) {
                                IconButton(onClick = { showPlaylistSheet = true }) {
                                    Icon(AppIcons.QueueMusic, contentDescription = "Playlist", tint = Color.White)
                                }
                            }

                            Box {
                                IconButton(onClick = { showMoreMenu = true }) {
                                    Icon(AppIcons.MoreVert, contentDescription = "More settings", tint = Color.White)
                                }
                                DropdownMenu(
                                    expanded = showMoreMenu,
                                    onDismissRequest = { showMoreMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(if (isLandscape) "Rotate to Portrait" else "Rotate to Landscape") },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = if (isLandscape) AppIcons.ScreenLockLandscape else AppIcons.ScreenLockPortrait,
                                                contentDescription = null
                                            )
                                        },
                                        onClick = { showMoreMenu = false; toggleOrientation() }
                                    )
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        DropdownMenuItem(
                                            text = { Text("Picture-in-Picture") },
                                            leadingIcon = { Icon(AppIcons.PictureInPictureAlt, contentDescription = null) },
                                            onClick = { showMoreMenu = false; enterPipMode() }
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text("Aspect Ratio") },
                                        leadingIcon = { Icon(AppIcons.AspectRatio, contentDescription = null) },
                                        onClick = { showMoreMenu = false; showAspectDialog = true }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Playback Speed (${currentSpeed}x)") },
                                        leadingIcon = { Icon(AppIcons.Speed, contentDescription = null) },
                                        onClick = { showMoreMenu = false; showSpeedDialog = true }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Repeat: ${repeatMode.label}") },
                                        leadingIcon = {
                                            Icon(
                                                if (repeatMode == RepeatMode.ONE) AppIcons.RepeatOne else AppIcons.Repeat,
                                                contentDescription = null,
                                                tint = if (repeatMode == RepeatMode.OFF) LocalContentColor.current else Color(0xFF22C55E)
                                            )
                                        },
                                        onClick = {
                                            val next = RepeatMode.entries[
                                                (repeatMode.ordinal + 1) % RepeatMode.entries.size
                                            ]
                                            repeatMode = next
                                            playerPrefs.edit().putString("repeat", next.name).apply()
                                            Toast.makeText(context, "Repeat: ${next.label}", Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(if (sleepTimerEndMs != null) "Sleep Timer (on)" else "Sleep Timer") },
                                        leadingIcon = {
                                            Icon(
                                                AppIcons.Timer,
                                                contentDescription = null,
                                                tint = if (sleepTimerEndMs != null) Color(0xFFF59E0B) else LocalContentColor.current
                                            )
                                        },
                                        onClick = { showMoreMenu = false; showSleepTimerDialog = true }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(if (subtitles.isNotEmpty() && subtitlesEnabled) "Subtitles (on)" else "Subtitles") },
                                        leadingIcon = {
                                            Icon(
                                                if (subtitlesEnabled && subtitles.isNotEmpty()) AppIcons.Subtitles else AppIcons.SubtitlesOff,
                                                contentDescription = null,
                                                tint = if (subtitles.isNotEmpty() && subtitlesEnabled) Color(0xFF22C55E) else LocalContentColor.current
                                            )
                                        },
                                        onClick = { showMoreMenu = false; showSubtitleDialog = true }
                                    )
                                    if (audioTracks.size > 1) {
                                        DropdownMenuItem(
                                            text = { Text("Audio Tracks") },
                                            leadingIcon = { Icon(AppIcons.Audiotrack, contentDescription = null, tint = Color(0xFFFFD600)) },
                                            onClick = { showMoreMenu = false; showAudioTrackDialog = true }
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                if (activeEqPreset != "Flat" || bassBoostStrength > 0) "Equalizer (on)" else "Equalizer"
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                AppIcons.Equalizer,
                                                contentDescription = null,
                                                tint = if (activeEqPreset != "Flat" || bassBoostStrength > 0) Color(0xFF3B82F6) else LocalContentColor.current
                                            )
                                        },
                                        onClick = { showMoreMenu = false; showEqDialog = true }
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                if (abLoopA != null || subtitleDelayMs != 0L) "Playback tools (active)" else "Playback tools"
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                AppIcons.Tune,
                                                contentDescription = null,
                                                tint = if (abLoopA != null || subtitleDelayMs != 0L) Color(0xFFA855F7) else LocalContentColor.current
                                            )
                                        },
                                        onClick = { showMoreMenu = false; showMpvToolsDialog = true }
                                    )
                                    if (currentFile != null) {
                                        DropdownMenuItem(
                                            text = { Text("Details") },
                                            leadingIcon = { Icon(AppIcons.Info, contentDescription = null) },
                                            onClick = { showMoreMenu = false; showDetailsDialog = true }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        if (canNavigate) {
                            IconButton(
                                onClick = { stepTo(playlistIndex - 1) },
                                modifier = Modifier
                                    .size(50.dp)
                                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                            ) {
                                Icon(AppIcons.SkipPrevious, contentDescription = "Previous video", tint = Color.White)
                            }
                        }

                        IconButton(
                            onClick = {
                                videoViewRef?.let { vv ->
                                    seekPlayback(vv.currentPosition - 10000, "-10s")
                                }
                            },
                            modifier = Modifier
                                .size(50.dp)
                                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                        ) {
                            Icon(AppIcons.Replay10, contentDescription = "-10s", tint = Color.White)
                        }

                        IconButton(
                            onClick = {
                                videoViewRef?.let { vv ->
                                    if (vv.isPlaying) {
                                        vv.pause()
                                        isPlaying = false
                                    } else {
                                        playbackPausedByBackground = false
                                        vv.start()
                                        isPlaying = true
                                    }
                                }
                            },
                            modifier = Modifier
                                .size(68.dp)
                                .background(Color.Black.copy(alpha = 0.65f), CircleShape)
                        ) {
                            Icon(
                                imageVector = if (isPlaying) AppIcons.Pause else AppIcons.PlayArrow,
                                contentDescription = "Play/Pause",
                                tint = Color.White,
                                modifier = Modifier.size(38.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                videoViewRef?.let { vv ->
                                    seekPlayback(vv.currentPosition + 10000, "+10s")
                                }
                            },
                            modifier = Modifier
                                .size(50.dp)
                                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                        ) {
                            Icon(AppIcons.Forward10, contentDescription = "+10s", tint = Color.White)
                        }

                        if (canNavigate) {
                            IconButton(
                                onClick = { stepTo(playlistIndex + 1) },
                                modifier = Modifier
                                    .size(50.dp)
                                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                            ) {
                                Icon(AppIcons.SkipNext, contentDescription = "Next video", tint = Color.White)
                            }
                        }
                    }

                    PlayerTimeline(
                        modifier = Modifier.align(Alignment.BottomCenter),
                        positionMs = { currentPositionMs },
                        durationMs = durationMs,
                        abLoopA = abLoopA,
                        abLoopB = abLoopB,
                        subtitles = subtitles,
                        subtitlesEnabled = subtitlesEnabled,
                        subtitleDelayMs = subtitleDelayMs,
                        currentSpeed = currentSpeed,
                        onSeek = { newPos ->
                            seekPlayback(newPos)
                        },
                        onOpenSpeed = { showSpeedDialog = true }
                    )
                }
            }
        }

        if (showSubtitleDialog) {
            val parentDir = currentFile?.parentFile
            val availableSubFiles = remember(parentDir) {
                parentDir?.listFiles()?.filter {
                    it.extension.equals("srt", ignoreCase = true) || it.extension.equals("vtt", ignoreCase = true)
                } ?: emptyList()
            }

            AlertDialog(
                onDismissRequest = { showSubtitleDialog = false },
                title = { Text("Subtitles (.srt / .vtt / embedded)") },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .heightIn(max = 400.dp)
                    ) {
                        Button(
                            onClick = {
                                subtitlePickerLauncher.launch(arrayOf("text/*", "application/x-subrip", "*/*"))
                                showSubtitleDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(AppIcons.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Open External Subtitle File...")
                        }

                        if (subtitles.isNotEmpty()) {
                            Text(
                                text = "Showing ${subtitles.size} cues from " +
                                    (subtitleFile?.name ?: subtitleSourceLabel?.let { "$it (in this video)" }
                                        ?: "this video"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedButton(
                                onClick = {
                                    subtitlesEnabled = !subtitlesEnabled
                                    Toast.makeText(
                                        context,
                                        if (subtitlesEnabled) "Subtitles enabled" else "Subtitles disabled",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    showSubtitleDialog = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    if (subtitlesEnabled) AppIcons.SubtitlesOff else AppIcons.Subtitles,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(if (subtitlesEnabled) "Hide Subtitles" else "Show Subtitles")
                            }
                        }

                        if (availableSubFiles.isNotEmpty()) {
                            Text("Local Directory Subtitles:", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                            LazyColumn(modifier = Modifier.heightIn(max = 180.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(availableSubFiles) { file ->
                                    val isSelected = subtitleFile?.absolutePath == file.absolutePath
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                runCatching {
                                                    subtitleFile = file
                                                    subtitles = parseSrtOrVtt(file.readText())
                                                    subtitlesEnabled = true
                                                    Toast.makeText(context, "Loaded ${subtitles.size} cues", Toast.LENGTH_SHORT).show()
                                                }.onFailure {
                                                    Toast.makeText(context, "Error parsing subtitle file", Toast.LENGTH_SHORT).show()
                                                }
                                                showSubtitleDialog = false
                                            },
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                                        )
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(AppIcons.ClosedCaption, contentDescription = null)
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(file.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSubtitleDialog = false }) { Text("Close") }
                }
            )
        }

        if (showAudioTrackDialog) {
            AlertDialog(
                onDismissRequest = { showAudioTrackDialog = false },
                title = { Text("Select Audio Stream") },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .heightIn(max = 400.dp)
                    ) {
                        audioTracks.forEach { track ->
                            FilterChip(
                                selected = selectedAudioTrackIndex == track.index,
                                onClick = {
                                    selectedAudioTrackIndex = track.index
                                    runCatching {
                                        selectAudioTrack(track.index)
                                        Toast.makeText(context, "Switched to ${track.name}", Toast.LENGTH_SHORT).show()
                                    }
                                    showAudioTrackDialog = false
                                },
                                label = { Text("${track.name} (${track.language})") },
                                leadingIcon = {
                                    if (selectedAudioTrackIndex == track.index) {
                                        Icon(AppIcons.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showAudioTrackDialog = false }) { Text("Close") }
                }
            )
        }

        if (showEqDialog) {
            AlertDialog(
                onDismissRequest = { showEqDialog = false },
                title = { Text("Hardware Equalizer & Audio Profile") },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .heightIn(max = 400.dp)
                    ) {
                        Text("Equalizer Presets:", fontWeight = FontWeight.Bold)
                        listOf("Flat", "Bass Boost", "Treble Boost", "Vocal Clarity", "Rock/Pop").forEach { preset ->
                            FilterChip(
                                selected = activeEqPreset == preset,
                                onClick = {
                                    activeEqPreset = preset
                                    playerPrefs.edit().putString("eq_preset", preset).apply()
                                    applyHardwareEqPreset(hardwareEq, preset)
                                    hardwareEq?.runCatching { enabled = preset != "Flat" }
                                    Toast.makeText(context, "Audio Preset: $preset", Toast.LENGTH_SHORT).show()
                                },
                                label = { Text(preset) },
                                leadingIcon = {
                                    if (activeEqPreset == preset) {
                                        Icon(AppIcons.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        HorizontalDivider()

                        Column {
                            Text("Bass Boost Strength: ${(bassBoostStrength / 10)}%", fontWeight = FontWeight.Bold)
                            Slider(
                                value = bassBoostStrength.toFloat(),
                                onValueChange = { value ->
                                    bassBoostStrength = value.toInt()
                                    playerPrefs.edit()
                                        .putInt("bass_strength", bassBoostStrength)
                                        .apply()
                                    hardwareBassBoost?.runCatching {
                                        setStrength(bassBoostStrength.toShort())
                                        enabled = bassBoostStrength > 0
                                    }
                                },
                                valueRange = 0f..1000f
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showEqDialog = false }) { Text("Close") }
                }
            )
        }

        if (showAspectDialog) {
            AlertDialog(
                onDismissRequest = { showAspectDialog = false },
                title = { Text("Aspect Ratio") },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .heightIn(max = 400.dp)
                    ) {
                        VideoAspectMode.values().forEach { mode ->
                            FilterChip(
                                selected = aspectMode == mode,
                                onClick = {
                                    aspectMode = mode
                                    playerPrefs.edit().putString("aspect", mode.name).apply()
                                    videoViewRef?.mode = mode
                                    Toast.makeText(context, "Aspect Ratio: ${mode.label}", Toast.LENGTH_SHORT).show()
                                    showAspectDialog = false
                                },
                                label = { Text(mode.label) },
                                leadingIcon = {
                                    if (aspectMode == mode) {
                                        Icon(AppIcons.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showAspectDialog = false }) { Text("Close") }
                }
            )
        }

        if (showMpvToolsDialog) {
            AlertDialog(
                onDismissRequest = { showMpvToolsDialog = false },
                title = { Text("Playback Tools") },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .heightIn(max = 400.dp)
                    ) {
                        Column {
                            Text("A-B Segment Loop", fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Point A: ${if (abLoopA != null) formatMs(abLoopA!!) else "Not Set"}  |  Point B: ${if (abLoopB != null) formatMs(abLoopB!!) else "Not Set"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        abLoopA = currentPositionMs.toLong()
                                        Toast.makeText(context, "Point A set to ${formatMs(abLoopA!!)}", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Set A")
                                }
                                Button(
                                    onClick = {
                                        if (abLoopA != null && currentPositionMs.toLong() > abLoopA!!) {
                                            abLoopB = currentPositionMs.toLong()
                                            Toast.makeText(context, "Point B set to ${formatMs(abLoopB!!)}", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "Point B must be after Point A", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    enabled = abLoopA != null
                                ) {
                                    Text("Set B")
                                }
                                OutlinedButton(
                                    onClick = {
                                        abLoopA = null
                                        abLoopB = null
                                        Toast.makeText(context, "Loop cleared", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Clear")
                                }
                            }
                        }

                        HorizontalDivider()

                        Column {
                            Text("Subtitle Sync Offset Delay", fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Current Offset: ${subtitleDelayMs} ms", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                                FilterChip(
                                    selected = false,
                                    onClick = { subtitleDelayMs -= 500 },
                                    label = { Text("-0.5s") }
                                )
                                FilterChip(
                                    selected = false,
                                    onClick = { subtitleDelayMs -= 250 },
                                    label = { Text("-0.25s") }
                                )
                                FilterChip(
                                    selected = false,
                                    onClick = { subtitleDelayMs = 0 },
                                    label = { Text("Reset") }
                                )
                                FilterChip(
                                    selected = false,
                                    onClick = { subtitleDelayMs += 250 },
                                    label = { Text("+0.25s") }
                                )
                                FilterChip(
                                    selected = false,
                                    onClick = { subtitleDelayMs += 500 },
                                    label = { Text("+0.5s") }
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showMpvToolsDialog = false }) { Text("Close") }
                }
            )
        }

        if (showSpeedDialog) {
            AlertDialog(
                onDismissRequest = { showSpeedDialog = false },
                title = { Text("Playback Speed") },
                text = {
                    Column(
                        modifier = Modifier.height(260.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f).forEach { speed ->
                            val isSel = currentSpeed == speed
                            TextButton(
                                onClick = {
                                    currentSpeed = speed
                                    playerPrefs.edit().putFloat("speed", speed).apply()
                                    runCatching {
                                        exoPlayerRef?.setPlaybackSpeed(speed)
                                    }
                                    showSpeedDialog = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = if (isSel) "✓ ${speed}x" else "${speed}x",
                                    color = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                                )
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSpeedDialog = false }) { Text("Close") }
                }
            )
        }

        if (showDetailsDialog && currentFile != null) {
            val file = currentFile
            val mime = MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(file.extension.lowercase())
                ?: file.extension.uppercase().ifEmpty { "Unknown" }
            AlertDialog(
                onDismissRequest = { showDetailsDialog = false },
                title = { Text("Video Details") },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .heightIn(max = 420.dp)
                    ) {
                        MediaDetailRow("Name", file.name)
                        MediaDetailRow("Path", file.absolutePath)
                        MediaDetailRow("Size", formatFileSize(file.length()))
                        MediaDetailRow("Type", mime)
                        MediaDetailRow(
                            "Resolution",
                            videoViewRef?.let { view ->
                                if (view.videoWidth > 0 && view.videoHeight > 0) {
                                    "${view.videoWidth} × ${view.videoHeight}"
                                } else "Unknown"
                            } ?: "Unknown"
                        )
                        MediaDetailRow(
                            "Duration",
                            if (durationMs > 0) formatMs(durationMs.toLong()) else "Unknown"
                        )
                        MediaDetailRow(
                            "Playback",
                            playbackError ?: if (isPrepared) "Ready" else "Loading"
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showDetailsDialog = false }) { Text("Close") }
                }
            )
        }

        if (showSleepTimerDialog) {
            AlertDialog(
                onDismissRequest = { showSleepTimerDialog = false },
                title = { Text("Sleep Timer") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "Playback will pause when the timer ends.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        listOf(15, 30, 45, 60).forEach { minutes ->
                            TextButton(
                                onClick = {
                                    sleepTimerEndMs = System.currentTimeMillis() + minutes * 60_000L
                                    showSleepTimerDialog = false
                                    Toast.makeText(
                                        context,
                                        "Sleep timer set for $minutes minutes",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("$minutes minutes")
                            }
                        }
                        if (sleepTimerEndMs != null) {
                            TextButton(
                                onClick = {
                                    sleepTimerEndMs = null
                                    showSleepTimerDialog = false
                                    Toast.makeText(context, "Sleep timer cancelled", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Turn off timer", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSleepTimerDialog = false }) { Text("Close") }
                }
            )
        }

        if (showPlaylistSheet) {
            ModalBottomSheet(onDismissRequest = { showPlaylistSheet = false }) {
                Text(
                    "Playlist (${playlist.size} videos)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
                LazyColumn(
                    modifier = Modifier.heightIn(max = 420.dp),
                    contentPadding = PaddingValues(bottom = 32.dp)
                ) {
                    itemsIndexed(playlist) { idx, file ->
                        val selected = idx == playlistIndex
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showPlaylistSheet = false
                                    stepTo(idx)
                                }
                                .background(
                                    if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent
                                )
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (selected) AppIcons.PlayArrow else AppIcons.Movie,
                                contentDescription = null,
                                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = file.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "${idx + 1} of ${playlist.size} • ${videoFileSize(file)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerTimeline(
    modifier: Modifier = Modifier,
    positionMs: () -> Int,
    durationMs: Int,
    abLoopA: Long?,
    abLoopB: Long?,
    subtitles: List<SubtitleCue>,
    subtitlesEnabled: Boolean,
    subtitleDelayMs: Long,
    currentSpeed: Float,
    onSeek: (Int) -> Unit,
    onOpenSpeed: () -> Unit
) {
    var scrubPosition by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(durationMs) {
        scrubPosition = null
    }
    val position = scrubPosition ?: positionMs()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = formatMs(position.toLong()),
                color = Color.White,
                style = MaterialTheme.typography.bodySmall
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (abLoopA != null) {
                    Surface(
                        color = Color(0xFFA855F7).copy(alpha = 0.35f),
                        shape = MaterialTheme.shapes.extraSmall
                    ) {
                        Text(
                            text = "Loop A: ${formatMs(abLoopA)} -> B: ${if (abLoopB != null) formatMs(abLoopB) else "..."}",
                            color = Color(0xFFE9D5FF),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                if (subtitles.isNotEmpty() && subtitlesEnabled) {
                    Surface(
                        color = Color(0xFF22C55E).copy(alpha = 0.25f),
                        shape = MaterialTheme.shapes.extraSmall
                    ) {
                        Text(
                            text = "CC (${subtitleDelayMs}ms)",
                            color = Color(0xFF22C55E),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatMs(durationMs.toLong()),
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "${currentSpeed}x",
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.extraSmall)
                        .clickable { onOpenSpeed() }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }

        Slider(
            value = position.toFloat().coerceIn(0f, durationMs.toFloat().coerceAtLeast(1f)),
            onValueChange = {
                scrubPosition = it.toInt().coerceIn(0, durationMs.coerceAtLeast(1))
            },
            onValueChangeFinished = {
                scrubPosition?.let(onSeek)
                scrubPosition = null
            },
            valueRange = 0f..durationMs.toFloat().coerceAtLeast(1f),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
            )
        )
    }
}

@Composable
private fun VideoSubtitleOverlay(
    modifier: Modifier = Modifier,
    subtitles: List<SubtitleCue>,
    enabled: Boolean,
    delayMs: Long,
    positionMs: () -> Int,
    showControls: Boolean,
    isInPipMode: Boolean
) {
    if (!enabled || subtitles.isEmpty()) return
    val pos = positionMs().toLong() - delayMs
    val cue = subtitles.find { pos in it.startMs..it.endMs } ?: return
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                bottom = if (showControls && !isInPipMode) 96.dp else 36.dp,
                start = 20.dp,
                end = 20.dp
            )
    ) {
        Surface(
            color = Color.Black.copy(alpha = 0.82f),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.align(Alignment.Center)
        ) {
            Text(
                text = cue.text,
                color = Color(0xFFFFD600),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun MediaDetailRow(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun applyHardwareEqPreset(eq: Equalizer?, presetName: String) {
    if (eq == null) return
    runCatching {
        val numberOfBands = eq.numberOfBands
        val maxLevel = eq.bandLevelRange[1]
        val midLevel = 0.toShort()

        when (presetName) {
            "Bass Boost" -> {
                if (numberOfBands > 0) eq.setBandLevel(0.toShort(), maxLevel)
                if (numberOfBands > 1) eq.setBandLevel(1.toShort(), (maxLevel / 2).toShort())
            }
            "Treble Boost" -> {
                if (numberOfBands >= 4) eq.setBandLevel((numberOfBands - 2).toShort(), (maxLevel / 2).toShort())
                if (numberOfBands >= 5) eq.setBandLevel((numberOfBands - 1).toShort(), maxLevel)
            }
            "Vocal Clarity" -> {
                for (i in 0 until numberOfBands) {
                    if (i == numberOfBands / 2) eq.setBandLevel(i.toShort(), maxLevel)
                    else eq.setBandLevel(i.toShort(), midLevel)
                }
            }
            else -> {
                for (i in 0 until numberOfBands) {
                    eq.setBandLevel(i.toShort(), midLevel)
                }
            }
        }
    }
}

private fun parseSrtOrVtt(content: String): List<SubtitleCue> {
    val cues = mutableListOf<SubtitleCue>()
    val blocks = content.replace("\r\n", "\n").split("\n\n")
    for (block in blocks) {
        val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size >= 2) {
            val timeLine = lines.find { it.contains("-->") } ?: continue
            val parts = timeLine.split("-->").map { it.trim() }
            if (parts.size == 2) {
                val startMs = parseTimestampToMs(parts[0])
                val endMs = parseTimestampToMs(parts[1])
                val timeIndex = lines.indexOf(timeLine)
                if (timeIndex < lines.lastIndex) {
                    val textLines = lines.subList(timeIndex + 1, lines.size)
                    val text = textLines.joinToString("\n")
                    if (startMs >= 0 && endMs > startMs && text.isNotEmpty()) {
                        cues.add(SubtitleCue(startMs, endMs, text))
                    }
                }
            }
        }
    }
    return cues
}

private fun parseTimestampToMs(timeStr: String): Long {
    return try {
        val clean = timeStr.replace(",", ".").trim().split(" ")[0]
        val parts = clean.split(":")
        if (parts.size == 3) {
            val hours = parts[0].toLong()
            val minutes = parts[1].toLong()
            val secAndMs = parts[2].split(".")
            val seconds = secAndMs[0].toLong()
            val ms = if (secAndMs.size > 1) secAndMs[1].padEnd(3, '0').take(3).toLong() else 0L
            hours * 3600000 + minutes * 60000 + seconds * 1000 + ms
        } else if (parts.size == 2) {
            val minutes = parts[0].toLong()
            val secAndMs = parts[1].split(".")
            val seconds = secAndMs[0].toLong()
            val ms = if (secAndMs.size > 1) secAndMs[1].padEnd(3, '0').take(3).toLong() else 0L
            minutes * 60000 + seconds * 1000 + ms
        } else {
            -1L
        }
    } catch (e: Exception) {
        -1L
    }
}

private fun videoFileSize(file: File): String = try {
    val bytes = file.length()
    when {
        bytes >= 1_073_741_824L -> String.format("%.1f GB", bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> String.format("%.1f MB", bytes / 1_048_576.0)
        bytes >= 1024L -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
} catch (_: Exception) {
    "?"
}

private fun formatMs(ms: Long): String {
    val hours = TimeUnit.MILLISECONDS.toHours(ms)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
    return if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}
