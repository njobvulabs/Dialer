package com.example.ui.screens.media

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaMetadataRetriever
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.material3.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import com.example.ui.components.landscapeContentWidth
import com.example.ui.components.AppIcons
import com.example.ui.components.AppSearchField
import com.example.ui.theme.AppCornerRadius
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.db.PlaylistEntity
import com.example.data.db.PlaylistItemEntity
import com.example.service.AudioEngine
import com.example.service.AudioPlaybackService
import com.example.service.QueueNavigator
import com.example.ui.components.FloatingTopBar
import com.example.ui.MainViewModel
import com.example.util.AudioTagInfo
import com.example.util.AudioTags
import com.example.util.MediaItem
import com.example.util.SortOption
import com.example.util.RecycleBin
import com.example.util.StorageHelper
import com.example.util.rememberSavedGridState
import com.example.util.rememberSavedListState
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// -----------------------------------------------------------------------------
// Enums & Data Models
// -----------------------------------------------------------------------------

enum class AudioRepeatMode { NONE, ONE, ALL }

enum class AudioDisplayMode { TRACKS, FOLDERS, PLAYLISTS }

data class AudioFolderGroup(
    val folder: File,
    val tracks: List<MediaItem>
) {
    val totalSizeBytes: Long get() = tracks.sumOf { it.sizeBytes }
}

// -----------------------------------------------------------------------------
// Helper Playback Functions
// -----------------------------------------------------------------------------

private fun playAudioFile(
    context: Context,
    mediaPlayer: MediaPlayer,
    path: String,
    onStart: (Long) -> Unit,
    onError: () -> Unit,
    onComplete: () -> Unit
) {
    try {
        mediaPlayer.reset()
        mediaPlayer.setDataSource(context, Uri.fromFile(File(path)))
        mediaPlayer.setOnPreparedListener { mp ->
            mp.setOnCompletionListener { onComplete() }
            mp.start()
            onStart(mp.duration.toLong())
        }
        mediaPlayer.setOnErrorListener { _, _, _ ->
            onError()
            true
        }
        mediaPlayer.prepareAsync()
    } catch (_: Exception) {
        onError()
    }
}

private fun applyEqualizerSettings(
    eq: Equalizer?,
    bands: FloatArray
) {
    if (eq == null) return
    try {
        val numBands = eq.numberOfBands.toInt()
        val bandRange = eq.bandLevelRange
        val minLevel = bandRange[0]
        val maxLevel = bandRange[1]

        fun mapToBandLevel(factor: Float): Short {
            val level = minLevel + (factor.coerceIn(0f, 1f) * (maxLevel - minLevel))
            return level.toInt().toShort()
        }

        for (i in 0 until numBands) {
            val factor = if (i < bands.size) bands[i] else 0.5f
            eq.setBandLevel(i.toShort(), mapToBandLevel(factor))
        }
        eq.enabled = true
    } catch (_: Exception) {
        // Equalizer hardware effect unavailable or missing permissions
    }
}

// -----------------------------------------------------------------------------
// Audio Player Screen Composable
// -----------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioPlayerScreen(
    viewModel: MainViewModel,
    initialTrackPath: String? = null,
    onInitialTrackConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    AudioEngine.init(context.applicationContext)
    val scope = rememberCoroutineScope()
    val currentDir by viewModel.currentDirectory.collectAsState()
    val mediaIndex by viewModel.mediaIndex.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(Unit) { viewModel.ensureMediaIndexFresh() }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.ensureMediaIndexFresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val playlists by viewModel.playlists.collectAsState()
    val useGlassmorphism by viewModel.useGlassmorphism.collectAsState()

    // Player State
    var mediaPlayer by remember { mutableStateOf(MediaPlayer()) }
    var equalizer by remember { mutableStateOf<Equalizer?>(null) }

    var isPrepared by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPath by remember { mutableStateOf<String?>(null) }
    var currentTitle by remember { mutableStateOf("") }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var isSeeking by remember { mutableStateOf(false) }
    var sliderPositionMs by remember { mutableFloatStateOf(0f) }

    val audioPrefs = remember { context.getSharedPreferences("audio_player", Context.MODE_PRIVATE) }
    var playbackSpeed by remember { mutableFloatStateOf(audioPrefs.getFloat("speed", 1.0f)) }
    var balance by remember { mutableFloatStateOf(audioPrefs.getFloat("balance", 0f)) }
    var bassStrength by remember { mutableFloatStateOf(audioPrefs.getFloat("bass", 0f)) }
    var bassBoost by remember { mutableStateOf<BassBoost?>(null) }

    // Favourites / recently played are path lists in prefs, mirroring the
    // launcher's pinned/recent shelves (no Room migration for a list of files).
    fun readPathCsv(key: String): List<String> =
        audioPrefs.getString(key, "").orEmpty()
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    var favoritePaths by remember { mutableStateOf(readPathCsv("favorites")) }
    var recentPaths by remember { mutableStateOf(readPathCsv("recent")) }

    var showQueueSheet by remember { mutableStateOf(false) }
    var showSleepDialog by remember { mutableStateOf(false) }
    var renamePlaylistTarget by remember { mutableStateOf<PlaylistEntity?>(null) }
    var renamePlaylistText by remember { mutableStateOf("") }
    var repeatMode by remember { mutableStateOf(
        runCatching { AudioRepeatMode.valueOf(audioPrefs.getString("repeat", "NONE") ?: "NONE") }.getOrDefault(AudioRepeatMode.NONE)
    ) }
    var isShuffle by remember { mutableStateOf(audioPrefs.getBoolean("shuffle", false)) }

    // Full-screen now-playing view + delete confirmation
    var showFullPlayer by remember { mutableStateOf(false) }

    // Long-press multi-select delete mode
    var selectMode by remember { mutableStateOf(false) }
    var selectedPaths by remember { mutableStateOf(setOf<String>()) }
    var showDeleteSelectedDialog by remember { mutableStateOf(false) }

    // Keep the background engine's repeat/shuffle in sync so lockscreen /
    // notification / mini-player next-prev behavior matches the on-screen one.
    SideEffect {
        AudioEngine.updateBackgroundMode(
            when (repeatMode) {
                AudioRepeatMode.NONE -> AudioEngine.RepeatMode.NONE
                AudioRepeatMode.ONE -> AudioEngine.RepeatMode.ONE
                AudioRepeatMode.ALL -> AudioEngine.RepeatMode.ALL
            },
            isShuffle
        )
    }

    // Equalizer State
    var eqPreset by remember { mutableStateOf(audioPrefs.getString("eq_preset", "Flat") ?: "Flat") }
    var band60Hz by remember { mutableFloatStateOf(audioPrefs.getFloat("eq_band0", 0.5f)) }
    var band230Hz by remember { mutableFloatStateOf(audioPrefs.getFloat("eq_band1", 0.5f)) }
    var band910Hz by remember { mutableFloatStateOf(audioPrefs.getFloat("eq_band2", 0.5f)) }
    var band3600Hz by remember { mutableFloatStateOf(audioPrefs.getFloat("eq_band3", 0.5f)) }
    var band14000Hz by remember { mutableFloatStateOf(audioPrefs.getFloat("eq_band4", 0.5f)) }
    val eqRevision by AudioEngine.equalizerRevision
    LaunchedEffect(eqRevision) {
        eqPreset = AudioEngine.equalizerPreset.value
        val bands = AudioEngine.equalizerBands.value
        band60Hz = bands.getOrElse(0) { 0.5f }
        band230Hz = bands.getOrElse(1) { 0.5f }
        band910Hz = bands.getOrElse(2) { 0.5f }
        band3600Hz = bands.getOrElse(3) { 0.5f }
        band14000Hz = bands.getOrElse(4) { 0.5f }
    }

    // Navigation & View Modes
    var displayMode by remember { mutableStateOf(AudioDisplayMode.TRACKS) }
    var selectedFolderGroup by remember { mutableStateOf<AudioFolderGroup?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var showSearchBar by remember { mutableStateOf(false) }

    val tracksState = rememberSavedListState("audio_tracks", viewModel)
    val foldersState = rememberSavedGridState("audio_folders", viewModel)
    val folderDetailState = rememberSavedListState("audio_folder_detail", viewModel)

    // Dialog Visibility
    var showEqDialog by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var sortOption by remember { mutableStateOf(SortOption.NAME) }
    var sortAscending by remember { mutableStateOf(true) }

    var showPlaylistsDialog by remember { mutableStateOf(false) }
    var showPlaylistItemsDialog by remember { mutableStateOf(false) }
    var showAddToPlaylistDialog by remember { mutableStateOf(false) }
    var addToPlaylistTarget by remember { mutableStateOf<MediaItem?>(null) }
    var selectedPlaylistId by remember { mutableStateOf<Long?>(null) }

    // Search debounce
    var debouncedSearchQuery by remember { mutableStateOf("") }

    // Shuffle/advance rule. A remembered plain object, not a state list: the
    // played-in-this-pass bag is bookkeeping the UI never reads, and the engine
    // keeps its own instance so lock-screen controls follow the same rule.
    val queueNav = remember { QueueNavigator() }

    fun persistPaths(key: String, paths: List<String>) {
        audioPrefs.edit().putString(key, paths.joinToString("\n")).apply()
    }

    fun toggleFavorite(path: String) {
        favoritePaths = if (path in favoritePaths) favoritePaths - path
        else (listOf(path) + favoritePaths).take(100)
        persistPaths("favorites", favoritePaths)
        Toast.makeText(
            context,
            if (path in favoritePaths) "Added to favourites" else "Removed from favourites",
            Toast.LENGTH_SHORT
        ).show()
    }

    fun recordRecent(path: String) {
        if (path.isBlank()) return
        recentPaths = (listOf(path) + recentPaths.filterNot { it == path }).take(12)
        persistPaths("recent", recentPaths)
    }

    fun applyBalance() {
        if (!isPrepared) return
        val clamped = balance.coerceIn(-1f, 1f)
        val left = if (clamped <= 0f) 1f else 1f - clamped
        val right = if (clamped >= 0f) 1f else 1f + clamped
        runCatching { mediaPlayer.setVolume(left, right) }
    }

    fun applyBass() {
        runCatching {
            bassBoost?.setStrength(bassStrength.coerceIn(0f, 1000f).toInt().toShort())
            bassBoost?.enabled = bassStrength > 0f
        }
    }

    // Counts the sleep timer down on screen instead of freezing the label at the
    // value it had when the timer was set.
    val sleepDeadlineValue = AudioEngine.sleepDeadline.value
    var sleepClock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(sleepDeadlineValue) {
        while (sleepDeadlineValue > 0L) {
            sleepClock = System.currentTimeMillis()
            delay(15_000L)
        }
    }

    // Audio Files Discovery & Aggregation
    val audioFiles = remember(currentDir, debouncedSearchQuery, mediaIndex, sortOption, sortAscending) {
        val currentDirList = StorageHelper.listFiles(currentDir)
        val audioExtensions = setOf("mp3", "wav", "ogg", "m4a", "flac", "aac", "opus", "wma")

        val combined = (currentDirList + mediaIndex)
            .distinctBy { it.file.absolutePath }
            .filter { item ->
                item.file.isFile && (
                    item.mimeType.startsWith("audio/") ||
                    item.file.extension.lowercase() in audioExtensions
                )
            }
            .let { filtered ->
                if (debouncedSearchQuery.isNotBlank()) {
                    filtered.filter {
                        it.name.contains(debouncedSearchQuery, ignoreCase = true) ||
                        it.file.parentFile?.name?.contains(debouncedSearchQuery, ignoreCase = true) == true
                    }
                } else {
                    filtered
                }
            }
            .toMutableList()

        val comparator = when (sortOption) {
            SortOption.NAME -> compareBy<MediaItem> { it.name.lowercase() }
            SortOption.DATE -> compareByDescending<MediaItem> { it.lastModified }
            SortOption.SIZE -> compareByDescending<MediaItem> { it.sizeBytes }
            SortOption.TYPE -> compareBy<MediaItem> { it.file.extension.lowercase() }
                .thenBy { it.name.lowercase() }
        }
        combined.sortWith(if (sortAscending) comparator else comparator.reversed())
        combined
    }

    /**
     * Resolve playlist rows to playable MediaItems. The library list is filtered by
     * the live search query, so a playlist entry that does not match the filter has
     * to be rebuilt from the file on disk instead of being silently dropped.
     */
    fun resolvePlaylistMedia(items: List<PlaylistItemEntity>): List<MediaItem> =
        items.mapNotNull { entry ->
            audioFiles.firstOrNull { it.file.absolutePath == entry.uri }
                ?: runCatching {
                    val f = File(entry.uri)
                    if (!f.exists()) return@runCatching null
                    MediaItem(
                        file = f,
                        name = entry.title.ifBlank { f.name },
                        path = entry.uri,
                        isDirectory = false,
                        sizeBytes = f.length(),
                        lastModified = f.lastModified(),
                        mimeType = "audio/*",
                        isHidden = f.name.startsWith(".")
                    )
                }.getOrNull()
        }

    // The list playback actually advances through. It is the folder's tracks when
    // the user tapped a track inside a folder detail view, otherwise all audio
    // files. Captured (not derived from selectedFolderGroup) so backing out of a
    // folder mid-song does not widen the queue back to the whole library.
    var playQueue by remember { mutableStateOf<List<MediaItem>?>(null) }
    val activeQueue: List<MediaItem> = playQueue ?: audioFiles

    // Debounce search query (150ms)
    LaunchedEffect(searchQuery) {
        delay(150)
        debouncedSearchQuery = searchQuery
    }

    // Auto-Group Audio Tracks by Folder
    val folderGroups = remember(audioFiles, searchQuery, sortOption, sortAscending) {
        val grouped = audioFiles.groupBy { it.file.parentFile }
            .mapNotNull { (parentDir, tracks) ->
                if (parentDir != null && tracks.isNotEmpty()) AudioFolderGroup(parentDir, tracks) else null
            }

        val folderComparator = when (sortOption) {
            SortOption.NAME -> compareBy<AudioFolderGroup> { it.folder.name.lowercase() }
            SortOption.DATE -> compareBy<AudioFolderGroup> { it.folder.lastModified() }
            SortOption.SIZE -> compareBy<AudioFolderGroup> { it.totalSizeBytes }
            SortOption.TYPE -> compareBy<AudioFolderGroup> { it.tracks.size }
        }
        if (sortAscending) grouped.sortedWith(folderComparator) else grouped.sortedWith(folderComparator).reversed()
    }

    /** Index of the playing track, for RENDERING ONLY. A plain val, i.e. a snapshot. */
    val currentIndexForUi = audioFiles.indexOfFirst { it.file.absolutePath == currentPath }

    // The index for TRANSPORT must be resolved AT CALL TIME, never captured.
    // [currentIndexForUi] is a plain val, so any lambda built during this
    // composition freezes its value -- and the track-end listener is built by the
    // row tap, i.e. BEFORE onStart writes the new currentPath. Auto-advance
    // therefore continued from whatever was playing *before* the tap (or from -1,
    // which means "first song"), which is why repeat-all jumped to a random song,
    // or replayed the current one, while Next/Previous looked fine: those run from
    // a fresh composition, so their captured index was correct. currentPath is a
    // MutableState, so reading it inside the handler is always current.
    //
    // Rule: nextTrack/previousTrack/the completion listener call THIS. Only
    // rendering may read currentIndexForUi. It resolves against the play queue
    // (folder tracks when set), not the whole library.
    fun liveIndex(): Int = activeQueue.indexOfFirst { it.file.absolutePath == currentPath }

    val currentTrack = remember(currentIndexForUi, audioFiles) {
        if (currentIndexForUi in audioFiles.indices) audioFiles[currentIndexForUi] else null
    }

    // Synchronize Equalizer Settings
    val currentBands = floatArrayOf(band60Hz, band230Hz, band910Hz, band3600Hz, band14000Hz)
    LaunchedEffect(eqPreset, band60Hz, band230Hz, band910Hz, band3600Hz, band14000Hz, equalizer) {
        applyEqualizerSettings(equalizer, currentBands)
        audioPrefs.edit()
            .putString("eq_preset", eqPreset)
            .putFloat("eq_band0", band60Hz)
            .putFloat("eq_band1", band230Hz)
            .putFloat("eq_band2", band910Hz)
            .putFloat("eq_band3", band3600Hz)
            .putFloat("eq_band4", band14000Hz)
            .apply()
    }

    // Speed is a user preference, not a per-track setting: persist it and keep
    // the background transport (lock screen / mini bar) on the same speed.
    LaunchedEffect(playbackSpeed) {
        audioPrefs.edit().putFloat("speed", playbackSpeed).apply()
        if (AudioEngine.playbackSpeed != playbackSpeed) AudioEngine.setSpeed(playbackSpeed)
        if (isPrepared) AudioEngine.applySpeed(mediaPlayer)
    }

    LaunchedEffect(balance) {
        audioPrefs.edit().putFloat("balance", balance).apply()
        applyBalance()
    }

    LaunchedEffect(bassStrength) {
        audioPrefs.edit().putFloat("bass", bassStrength).apply()
        applyBass()
    }

    // Playback Controller Functions
    fun stopPlayback() {
        runCatching { mediaPlayer.pause() }
        isPlaying = false
        positionMs = 0L
        isPrepared = false
    }

    /** Delete path: also drop the mini bar and the foreground service. */
    fun stopAndClearSession() {
        stopPlayback()
        AudioEngine.stopSession()
    }

    fun playTrackByPath(path: String) {
        // Stop the position ticker BEFORE resetting the player. playAudioFile() calls
        // mediaPlayer.reset() synchronously, which puts it in the Idle state where
        // getCurrentPosition() throws IllegalStateException. Leaving isPrepared=true
        // across the transition let the LaunchedEffect loop read a reset player (the
        // crash_*.txt stack: MediaPlayer.getCurrentPosition -> AudioPlayerScreen:486).
        isPrepared = false
        isPlaying = false

        // Read the state directly: callers can set playQueue and immediately call
        // this (Play next / Add to queue), and the derived `activeQueue` val is
        // only refreshed on the next recomposition.
        val queue = playQueue ?: audioFiles
        val idx = queue.indexOfFirst { it.file.absolutePath == path }
        val title = if (idx >= 0) queue[idx].name else path.substringAfterLast('/')
        playAudioFile(
            context = context,
            mediaPlayer = mediaPlayer,
            path = path,
            onStart = { dur ->
                isPrepared = true
                currentPath = path
                currentTitle = title
                durationMs = dur
                positionMs = 0L
                isPlaying = true

                // Give the engine the PLAY QUEUE (folder tracks when set) so
                // background next/prev advances within the folder, not the library.
                AudioEngine.setQueue(queue.map { it.file.absolutePath }, idx)
                AudioEngine.active.value = true

                // Re-initialize the per-session audio effects. They are bound to
                // the MediaPlayer's audio session id, which changes per track.
                try {
                    equalizer?.release()
                    equalizer = Equalizer(0, mediaPlayer.audioSessionId)
                    applyEqualizerSettings(equalizer, currentBands)
                } catch (_: Exception) {}
                try {
                    bassBoost?.release()
                    bassBoost = BassBoost(0, mediaPlayer.audioSessionId)
                    applyBass()
                } catch (_: Exception) {}
                AudioEngine.playbackSpeed = playbackSpeed
                AudioEngine.applySpeed(mediaPlayer)
                applyBalance()
                recordRecent(path)
            },
            onError = {
                Toast.makeText(context, "Cannot play this audio file", Toast.LENGTH_SHORT).show()
            },
            onComplete = {
                // Track-end, Next and Previous all resolve through the shared
                // QueueNavigator, so shuffle and repeat mean the same thing here
                // and on the lock screen. Repeat ONE replays this track (a track
                // that ended restarts its own file rather than picking a next one);
                // with repeat OFF the queue now runs to the end and *then* stops.
                // The queue is activeQueue (the folder when set), so "loop off"
                // stops at the folder's last track instead of rolling into the
                // rest of the library.
                val live = liveIndex()
                val cur = live.takeIf { it in activeQueue.indices }
                if (repeatMode == AudioRepeatMode.ONE) {
                    if (cur == null) {
                        stopPlayback()
                    } else {
                        playTrackByPath(activeQueue[cur].file.absolutePath)
                    }
                } else {
                    val nxt = queueNav.next(
                        size = activeQueue.size,
                        current = live,
                        shuffle = isShuffle,
                        repeatAll = repeatMode == AudioRepeatMode.ALL,
                        wrapAtEnd = false
                    )
                    if (nxt == null) {
                        stopPlayback()
                        if (!AudioEngine.consumeSleepAtEnd()) {
                            Toast.makeText(context, "Queue ended", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        playTrackByPath(activeQueue[nxt].file.absolutePath)
                    }
                }
            }
        )
    }

    // Auto-play a track opened directly from the File Manager
    LaunchedEffect(initialTrackPath) {
        if (initialTrackPath != null) {
            playTrackByPath(initialTrackPath)
            onInitialTrackConsumed()
            showFullPlayer = true
        }
    }

    fun togglePlayPause() {
        if (isPrepared) {
            if (mediaPlayer.isPlaying) {
                mediaPlayer.pause()
                isPlaying = false
            } else {
                mediaPlayer.start()
                isPlaying = true
            }
        }
    }

    fun seekTo(ms: Long) {
        if (isPrepared) {
            mediaPlayer.seekTo(ms.toInt())
            positionMs = ms
        }
    }

    fun nextTrack() {
        if (activeQueue.isEmpty()) return
        // wrapAtEnd = true: a manual Next must always do something, so with repeat
        // off it wraps to the first track rather than going dead at the last one.
        val nxt = queueNav.next(
            size = activeQueue.size,
            current = liveIndex(),
            shuffle = isShuffle,
            repeatAll = repeatMode == AudioRepeatMode.ALL,
            wrapAtEnd = true
        )
        if (nxt != null) playTrackByPath(activeQueue[nxt].file.absolutePath)
    }

    fun previousTrack() {
        if (activeQueue.isEmpty()) return
        // In a shuffled pass Previous walks back through the bag; with nothing
        // before this track (null) it restarts the current one, the way other
        // players do. Without shuffle it steps back and wraps at the start.
        val prev = queueNav.previous(
            size = activeQueue.size,
            current = liveIndex(),
            shuffle = isShuffle
        )
        val live = liveIndex()
        val target = prev ?: live.takeIf { it in activeQueue.indices } ?: 0
        playTrackByPath(activeQueue[target].file.absolutePath)
    }

    fun setSpeed(speed: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching { mediaPlayer.playbackParams = mediaPlayer.playbackParams.setSpeed(speed) }
        }
        playbackSpeed = speed
    }

    fun addTrackToPlaylist(track: MediaItem, playlistId: Long) {
        var trackDurMs = 0L
        runCatching {
            val mmr = android.media.MediaMetadataRetriever()
            mmr.setDataSource(track.file.absolutePath)
            trackDurMs = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            mmr.release()
        }
        viewModel.addPlaylistItem(
            PlaylistItemEntity(
                playlistId = playlistId,
                title = track.name,
                uri = track.file.absolutePath,
                artist = "Unknown Artist",
                durationMs = trackDurMs.coerceAtLeast(1)
            )
        )
        Toast.makeText(context, "Added \"${track.name}\" to playlist", Toast.LENGTH_SHORT).show()
    }

    fun playNext(item: MediaItem) {
        val path = item.file.absolutePath
        val base = (playQueue ?: audioFiles).toMutableList()
        base.removeAll { it.file.absolutePath == path }
        val live = base.indexOfFirst { it.file.absolutePath == currentPath }
        if (live >= 0) {
            base.add(live + 1, item)
            playQueue = base
            AudioEngine.setQueue(base.map { it.file.absolutePath }, live)
            Toast.makeText(context, "Playing next: ${item.name}", Toast.LENGTH_SHORT).show()
        } else {
            base.add(0, item)
            playQueue = base
            playTrackByPath(path)
        }
    }

    fun addToQueue(item: MediaItem) {
        val path = item.file.absolutePath
        val base = (playQueue ?: audioFiles).toMutableList()
        if (base.none { it.file.absolutePath == path }) base.add(item)
        playQueue = base
        val live = base.indexOfFirst { it.file.absolutePath == currentPath }
        if (live >= 0) AudioEngine.setQueue(base.map { it.file.absolutePath }, live)
        Toast.makeText(context, "Added to queue: ${item.name}", Toast.LENGTH_SHORT).show()
    }

    fun removeFromQueue(item: MediaItem) {
        val path = item.file.absolutePath
        val base = (playQueue ?: audioFiles).toMutableList()
        if (base.size <= 1) return
        base.removeAll { it.file.absolutePath == path }
        playQueue = base
        val live = base.indexOfFirst { it.file.absolutePath == currentPath }
        if (live >= 0) AudioEngine.setQueue(base.map { it.file.absolutePath }, live)
    }

    fun playQueueAt(index: Int) {
        val queue = activeQueue
        if (index in queue.indices) {
            playQueue = queue
            playTrackByPath(queue[index].file.absolutePath)
        }
    }

    // Position Update Loop. Poll only while the screen is started, and let the
    // paused state fall back to a cheap 1 s tick instead of 4 Hz.
    LaunchedEffect(isPrepared, isPlaying, isSeeking, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isPrepared) {
                if (isPlaying && !isSeeking) {
                    // getCurrentPosition() throws IllegalStateException whenever the
                    // player is not in a valid state — mid-reset during a track change,
                    // after an error, or after release(). Guard it: a thrown exception
                    // here escaped as an uncaught coroutine error and crashed the app
                    // (crash_*.txt). 0 is a safe placeholder; onStart re-seeds it.
                    positionMs = runCatching { mediaPlayer.currentPosition.toLong() }.getOrDefault(0L)
                }
                delay(if (isPlaying && !isSeeking) 250L else 1_000L)
            }
        }
    }

    // Resync AudioEngine queue when the play queue changes (folder open/close,
    // sort, new media). Keyed on activeQueue (not audioFiles) so a folder-scoped
    // queue is preserved instead of being overwritten by the whole library.
    LaunchedEffect(activeQueue) {
        val engine = AudioEngine
        if (engine.queue.isNotEmpty() && activeQueue.isNotEmpty()) {
            val snapshot = activeQueue.map { it.file.absolutePath }
            val currentIdx = snapshot.indexOfFirst { it == currentPath }
            if (currentIdx >= 0) {
                engine.setQueue(snapshot, currentIdx)
            }
        }
    }

    // Background playback: keep the engine alive via the foreground service
    // while music plays, even if the user leaves this screen or the app.
    DisposableEffect(Unit) {
        val svcIntent = android.content.Intent(context, AudioPlaybackService::class.java)
        // If the background engine is still mid-session (e.g. the user reopened
        // the full player from the mini bar), ADOPT the live player instead of
        // killing it so playback keeps going seamlessly.
        val liveSession = AudioEngine.player
        val liveActive = liveSession != null && AudioEngine.active.value &&
            runCatching { liveSession.isPlaying || liveSession.duration >= 0 }.getOrDefault(false)
        if (liveSession != null && liveActive) {
            mediaPlayer = liveSession
            isPrepared = true
            isPlaying = AudioEngine.playing.value
            currentPath = AudioEngine.queue.getOrNull(AudioEngine.queueIndex)
            currentTitle = File(currentPath ?: "").nameWithoutExtension
            durationMs = runCatching { liveSession.duration.toLong() }.getOrDefault(0L)
            positionMs = runCatching { liveSession.currentPosition.toLong() }.getOrDefault(0L)
            runCatching { equalizer?.release() }
            equalizer = runCatching { Equalizer(0, liveSession.audioSessionId) }.getOrNull()
            runCatching { applyEqualizerSettings(equalizer, currentBands) }
            runCatching {
                bassBoost = BassBoost(0, liveSession.audioSessionId)
                applyBass()
            }
            AudioEngine.playbackSpeed = playbackSpeed
            AudioEngine.applySpeed(liveSession)
            applyBalance()
            showFullPlayer = true
            AudioEngine.active.value = true
        } else {
            // No live session: kill any stale session from a previous screen visit.
            AudioEngine.player?.let { old ->
                runCatching { old.stop(); old.release() }
            }
            context.stopService(svcIntent)
            AudioEngine.player = mediaPlayer
            AudioEngine.active.value = false
        }

        // Transport hooks so lockscreen / notification / mini player drive the queue.
        AudioEngine.onToggle = { togglePlayPause() }
        AudioEngine.onNext = { nextTrack() }
        AudioEngine.onPrev = { previousTrack() }

        onDispose {
            if (runCatching { mediaPlayer.isPlaying }.getOrDefault(false)) {
                // Still playing: hand transport over to engine defaults and
                // keep going via the foreground service.
                //
                // Re-point the completion listener first. This player is the
                // engine's session now, and leaving the screen's listener attached
                // meant a *disposed* composition kept choosing tracks from its own
                // captured queue index while the engine's live one went unused -- the
                // mini bar then advanced to the wrong song, and nothing refreshed the
                // notification because the screen's LaunchedEffect was cancelled.
                runCatching {
                    mediaPlayer.setOnCompletionListener {
                        AudioEngine.playing.value = false
                        AudioEngine.stepDefault(1, auto = true)
                    }
                }
                AudioEngine.installDefaultHandlers()
                AudioPlaybackService.update(context)
            } else {
                // Fully stopped: drop everything, including the mini bar.
                AudioEngine.clearHandlers()
                equalizer?.release()
                bassBoost?.release()
                bassBoost = null
                mediaPlayer.release()
                AudioEngine.releaseEqualizer()
                AudioEngine.player = null
                context.stopService(svcIntent)
                AudioEngine.serviceLive = false
                AudioEngine.active.value = false
            }
        }
    }

    // Mirror playback state into the engine + service notification.
    LaunchedEffect(isPlaying, currentTitle) {
        AudioEngine.title.value = currentTitle.ifBlank { "NJ-Dialer Audio" }
        // B11: this screen drives its OWN MediaPlayer while in the foreground, so
        // the engine never sees playPath() and would leave duration/artwork blank —
        // which is exactly what the lockscreen scrubber and cover art need. Push
        // them across here, on track change as well as on play/pause.
        val path = currentPath
        if (path != null && path != AudioEngine.currentPath) {
            AudioEngine.currentPath = path
            runCatching {
                AudioEngine.durationMs.value =
                    mediaPlayer.duration.toLong().coerceAtLeast(0L)
            }
            AudioEngine.loadTagsFor(path)
        }
        if (isPlaying || AudioEngine.serviceLive) {
            AudioPlaybackService.update(context)
        }
        AudioEngine.playing.value = isPlaying
    }

    // The engine owns the session in the foreground too (lock-screen controls,
    // focus loss, sleep timer). Mirror its play/pause back into this screen so a
    // remote pause is not immediately overwritten by stale screen state.
    LaunchedEffect(Unit) {
        snapshotFlow { AudioEngine.playing.value }.collect { enginePlaying ->
            when {
                !enginePlaying && isPlaying -> {
                    isPlaying = false
                    isSeeking = false
                }
                enginePlaying && !isPlaying && isPrepared -> isPlaying = true
            }
        }
    }

    fun onRowClicked(item: MediaItem) {
        if (selectMode) {
            val p = item.file.absolutePath
            selectedPaths = if (p in selectedPaths) selectedPaths - p else selectedPaths + p
            if (selectedPaths.isEmpty()) selectMode = false
        } else {
            // Scope playback to the open folder (folder detail rows) or the whole
            // library (all-tracks rows). This is what makes "play a folder" only
            // cycle that folder instead of every song in the queue list.
            playQueue = selectedFolderGroup?.tracks
            playTrackByPath(item.file.absolutePath)
        }
    }

    fun onRowLongPressed(item: MediaItem) {
        val p = item.file.absolutePath
        if (!selectMode) {
            selectMode = true
            selectedPaths = setOf(p)
        } else {
            selectedPaths = if (p in selectedPaths) selectedPaths - p else selectedPaths + p
            if (selectedPaths.isEmpty()) selectMode = false
        }
    }

    fun onDeleteSelectedTracks() {
        val toDelete = audioFiles.filter { it.file.absolutePath in selectedPaths }
        val toDeleteFiles = toDelete.map { it.file }
        // B10: into the Recycle bin, same as the File Manager.
        val binResult = runCatching { RecycleBin.trash(context, toDeleteFiles) }.getOrNull()
        if (binResult != null && binResult.trashed > 0) {
            android.widget.Toast.makeText(
                context,
                "Moved ${binResult.trashed} track(s) to Recycle bin",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
        for (item in toDelete) {
            // trash() MOVES the file into the bin, so there is nothing to unlink
            // here. Stop playback either way: if the move failed the track must
            // not keep playing from a file the user believes they deleted.
            if (currentPath == item.file.absolutePath) {
                stopAndClearSession()
            }
        }
        selectedFolderGroup?.let { group ->
            selectedFolderGroup = group.copy(
                tracks = group.tracks.filterNot { it.file.absolutePath in selectedPaths }
            )
        }
        selectedPaths = emptySet()
        selectMode = false
        viewModel.refreshMediaIndex(force = true)
    }

    // Back button navigation when viewing a folder
    if (selectedFolderGroup != null) {
        BackHandler { selectedFolderGroup = null }
    }

    // Back button exits multi-select mode first
    if (selectMode) {
        BackHandler {
            selectedPaths = emptySet()
            selectMode = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Multi-select action bar (long-press rows to enter)
        if (selectMode) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                tonalElevation = 3.dp,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(top = 8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${selectedPaths.size} selected",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        val visible = selectedFolderGroup?.tracks ?: audioFiles
                        selectedPaths = visible.map { it.file.absolutePath }.toSet()
                    }) { Text("All") }
                    TextButton(
                        onClick = {
                            if (selectedPaths.isNotEmpty()) showDeleteSelectedDialog = true
                        },
                        enabled = selectedPaths.isNotEmpty()
                    ) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = {
                        selectedPaths = emptySet()
                        selectMode = false
                    }) { Text("Cancel") }
                }
            }
        }

        // Main Toolbar
        if (!selectMode) {
        FloatingTopBar(useGlass = useGlassmorphism) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Search toggle (search bar appears on click)
                IconButton(
                    onClick = { showSearchBar = !showSearchBar },
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = if (showSearchBar) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                    )
                ) {
                    Icon(
                        imageVector = if (showSearchBar) AppIcons.Close else AppIcons.Search,
                        contentDescription = "Search"
                    )
                }

                // Sort menu
                Box {
                    IconButton(
                        onClick = { showSortMenu = true },
                        colors = IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    ) {
                        Icon(AppIcons.Sort, contentDescription = "Sort")
                    }
                    DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                        SortOption.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = {
                                    sortOption = option
                                    showSortMenu = false
                                },
                                trailingIcon = {
                                    if (sortOption == option) Icon(AppIcons.Check, contentDescription = null)
                                }
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(if (sortAscending) "Ascending" else "Descending") },
                            onClick = {
                                sortAscending = !sortAscending
                                showSortMenu = false
                            },
                            trailingIcon = {
                                Icon(
                                    imageVector = if (sortAscending) AppIcons.ArrowUpward else AppIcons.ArrowDownward,
                                    contentDescription = null
                                )
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // Mode Selector Chip Switcher
                IconButton(
                    onClick = {
                        displayMode = when (displayMode) {
                            AudioDisplayMode.TRACKS -> AudioDisplayMode.FOLDERS
                            AudioDisplayMode.FOLDERS -> AudioDisplayMode.PLAYLISTS
                            AudioDisplayMode.PLAYLISTS -> AudioDisplayMode.TRACKS
                        }
                    },
                    colors = IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Icon(
                        imageVector = when (displayMode) {
                            AudioDisplayMode.TRACKS -> AppIcons.MusicNote
                            AudioDisplayMode.FOLDERS -> AppIcons.Folder
                            AudioDisplayMode.PLAYLISTS -> AppIcons.QueueMusic
                        },
                        contentDescription = "Switch View",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                IconButton(onClick = { viewModel.refreshMediaIndex(force = true) }) {
                    Icon(AppIcons.Refresh, contentDescription = "Refresh Index")
                }

                IconButton(onClick = { showQueueSheet = true }) {
                    Icon(AppIcons.PlaylistPlay, contentDescription = "Play queue")
                }

                val sleepActive = AudioEngine.sleepDeadline.value > sleepClock ||
                    AudioEngine.sleepAtEndOfQueue.value
                IconButton(onClick = { showSleepDialog = true }) {
                    Icon(
                        AppIcons.Timer,
                        contentDescription = "Sleep timer",
                        tint = if (sleepActive) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(onClick = { showEqDialog = true }) {
                    Icon(AppIcons.Equalizer, contentDescription = "Equalizer", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }

        // Navigation Breadcrumb / Folder Sub-Header
        if (selectedFolderGroup != null) {
        FloatingTopBar(useGlass = useGlassmorphism) {
                IconButton(onClick = { selectedFolderGroup = null }) {
                    Icon(AppIcons.ArrowBack, contentDescription = "Back to Folders")
                }
                Spacer(modifier = Modifier.width(4.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = selectedFolderGroup!!.folder.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${selectedFolderGroup!!.tracks.size} tracks • ${formatFileSize(selectedFolderGroup!!.totalSizeBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        }

        // Search Field (appears when the search icon is clicked)
        AnimatedVisibility(visible = showSearchBar) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppSearchField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = "Search songs or folders...",
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // Dynamic Main Content Area
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                // Folder Details View
                selectedFolderGroup != null -> {
                    val group = selectedFolderGroup!!
                    LazyColumn(
                        state = folderDetailState,
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(group.tracks, key = { it.file.absolutePath }) { item ->
                            AudioTrackRow(
                                item = item,
                                isPlaying = currentPath == item.file.absolutePath && isPlaying,
                                isCurrent = currentPath == item.file.absolutePath,
                                selectMode = selectMode,
                                selected = item.file.absolutePath in selectedPaths,
                                onClick = { onRowClicked(item) },
                                onLongPress = { onRowLongPressed(item) },
                                onAddToPlaylist = {
                                    addToPlaylistTarget = item
                                    selectedPlaylistId = null
                                    showAddToPlaylistDialog = true
                                },
                                isFavorite = item.file.absolutePath in favoritePaths,
                                onToggleFavorite = { toggleFavorite(item.file.absolutePath) },
                                onPlayNext = { playNext(item) },
                                onAddToQueue = { addToQueue(item) }
                            )
                        }
                    }
                }

                // Folder Grouping Mode
                displayMode == AudioDisplayMode.FOLDERS -> {
                    if (folderGroups.isEmpty()) {
                        EmptyStateView("No audio folders found")
                    } else {
                        LazyVerticalGrid(
                            state = foldersState,
                            columns = GridCells.Adaptive(minSize = 160.dp),
                            contentPadding = PaddingValues(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            gridItems(folderGroups, key = { it.folder.absolutePath }) { group ->
                                AudioFolderCard(group = group, onClick = { selectedFolderGroup = group })
                            }
                        }
                    }
                }

                // Playlists Overview Mode
                displayMode == AudioDisplayMode.PLAYLISTS -> {
                    PlaylistsOverviewScreen(
                        playlists = playlists,
                        onCreatePlaylist = { name -> viewModel.createPlaylist(name) },
                        onDeletePlaylist = { playlist -> viewModel.deletePlaylist(playlist) },
                        onRenamePlaylist = { playlist ->
                            renamePlaylistTarget = playlist
                            renamePlaylistText = playlist.name
                        },
                        onSelectPlaylist = { id ->
                            selectedPlaylistId = id
                            showPlaylistItemsDialog = true
                        }
                    )
                }

                // All Tracks Mode (Default)
                else -> {
                    if (audioFiles.isEmpty()) {
                        EmptyStateView("No audio files found")
                    } else {
                        LazyColumn(
                            state = tracksState,
                            modifier = Modifier.fillMaxSize().landscapeContentWidth(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val favoriteTracks = audioFiles.filter {
                                it.file.absolutePath in favoritePaths
                            }
                            val recentTracks = recentPaths.mapNotNull { path ->
                                audioFiles.firstOrNull { it.file.absolutePath == path }
                            }
                            if (debouncedSearchQuery.isBlank() && favoriteTracks.isNotEmpty()) {
                                item(key = "shelf_favorites") {
                                    AudioShelf(
                                        title = "Favourites",
                                        tracks = favoriteTracks,
                                        currentPath = currentPath,
                                        isPlaying = isPlaying,
                                        onOpen = { onRowClicked(it) }
                                    )
                                }
                            }
                            if (debouncedSearchQuery.isBlank() && recentTracks.isNotEmpty()) {
                                item(key = "shelf_recent") {
                                    AudioShelf(
                                        title = "Recently played",
                                        tracks = recentTracks,
                                        currentPath = currentPath,
                                        isPlaying = isPlaying,
                                        onOpen = { onRowClicked(it) }
                                    )
                                }
                            }
                            itemsIndexed(audioFiles, key = { _, item -> item.file.absolutePath }) { _, item ->
                                val isSelected = currentPath == item.file.absolutePath
                                AudioTrackRow(
                                    item = item,
                                    isPlaying = isSelected && isPlaying,
                                    isCurrent = isSelected,
                                    selectMode = selectMode,
                                    selected = item.file.absolutePath in selectedPaths,
                                    onClick = { onRowClicked(item) },
                                    onLongPress = { onRowLongPressed(item) },
                                    onAddToPlaylist = {
                                        addToPlaylistTarget = item
                                        selectedPlaylistId = null
                                        showAddToPlaylistDialog = true
                                    },
                                    isFavorite = item.file.absolutePath in favoritePaths,
                                    onToggleFavorite = { toggleFavorite(item.file.absolutePath) },
                                    onPlayNext = { playNext(item) },
                                    onAddToQueue = { addToQueue(item) }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Bottom Expanding Media Player Sheet Controls
        if (isPrepared) {
            val playingPath = currentPath ?: ""
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 8.dp,
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showFullPlayer = true },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            CurrentTrackArtwork(path = playingPath, isPlaying = isPlaying)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = currentTitle.ifBlank { playingPath.substringAfterLast('/') },
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (isPlaying) "Playing • ${playbackSpeed}x Speed" else "Paused",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // Speed Switcher Chip
                        AssistChip(
                            onClick = {
                                val nextSpeed = when (playbackSpeed) {
                                    1.0f -> 1.25f
                                    1.25f -> 1.5f
                                    1.5f -> 2.0f
                                    2.0f -> 0.8f
                                    else -> 1.0f
                                }
                                setSpeed(nextSpeed)
                            },
                            label = { Text("${playbackSpeed}x") }
                        )
                    }

                    // Seekbar with Dragging Smoothness Fix
                    val currentPos = if (isSeeking) sliderPositionMs.toLong() else positionMs
                    val totalDur = durationMs.coerceAtLeast(1L)

                    Column {
                        Slider(
                            value = currentPos.toFloat().coerceIn(0f, totalDur.toFloat()),
                            onValueChange = { newPos ->
                                isSeeking = true
                                sliderPositionMs = newPos
                            },
                            onValueChangeFinished = {
                                seekTo(sliderPositionMs.toLong())
                                isSeeking = false
                            },
                            valueRange = 0f..totalDur.toFloat(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(formatTimeMs(currentPos), style = MaterialTheme.typography.labelSmall)
                            Text(formatTimeMs(totalDur), style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    // Main Media Playback Control Actions
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Shuffle Toggle
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            IconButton(onClick = {
                                isShuffle = !isShuffle
                                // A new shuffle pass starts from whatever is playing
                                // now; the navigator seeds itself on the first advance.
                                queueNav.reset()
                                context.getSharedPreferences("audio_player", Context.MODE_PRIVATE)
                                    .edit().putBoolean("shuffle", isShuffle).apply()
                            }) {
                                Icon(
                                    AppIcons.Shuffle,
                                    contentDescription = "Shuffle",
                                    tint = if (isShuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                    modifier = Modifier.then(if (isShuffle) Modifier else Modifier.size(20.dp))
                                )
                            }
                            Text("Shuffle", fontSize = 9.sp,
                                color = if (isShuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (isShuffle) FontWeight.Bold else FontWeight.Normal)
                        }

                        // Previous
                        IconButton(onClick = { previousTrack() }) {
                            Icon(AppIcons.SkipPrevious, contentDescription = "Previous Track", modifier = Modifier.size(32.dp))
                        }

                        // Play / Pause Toggle
                        FilledIconButton(
                            onClick = { togglePlayPause() },
                            modifier = Modifier.size(52.dp)
                        ) {
                            Icon(
                                imageVector = if (isPlaying) AppIcons.Pause else AppIcons.PlayArrow,
                                contentDescription = "Play/Pause",
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        // Next
                        IconButton(onClick = { nextTrack() }) {
                            Icon(AppIcons.SkipNext, contentDescription = "Next Track", modifier = Modifier.size(32.dp))
                        }

                        // Repeat Mode Switcher
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            IconButton(onClick = {
                                repeatMode = when (repeatMode) {
                                    AudioRepeatMode.NONE -> AudioRepeatMode.ALL
                                    AudioRepeatMode.ALL -> AudioRepeatMode.ONE
                                    AudioRepeatMode.ONE -> AudioRepeatMode.NONE
                                }
                                context.getSharedPreferences("audio_player", Context.MODE_PRIVATE)
                                    .edit().putString("repeat", repeatMode.name).apply()
                            }) {
                                Icon(
                                    imageVector = when (repeatMode) {
                                        AudioRepeatMode.ONE -> AppIcons.RepeatOne
                                        AudioRepeatMode.ALL -> AppIcons.Repeat
                                        AudioRepeatMode.NONE -> AppIcons.Repeat
                                    },
                                    contentDescription = "Repeat Mode",
                                    tint = when (repeatMode) {
                                        AudioRepeatMode.ONE -> MaterialTheme.colorScheme.primary
                                        AudioRepeatMode.ALL -> MaterialTheme.colorScheme.primary
                                        AudioRepeatMode.NONE -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                    },
                                    modifier = Modifier.then(if (repeatMode != AudioRepeatMode.NONE) Modifier else Modifier.size(20.dp))
                                )
                            }
                            Text(
                                when (repeatMode) {
                                    AudioRepeatMode.ONE -> "One"
                                    AudioRepeatMode.ALL -> "All"
                                    AudioRepeatMode.NONE -> "Off"
                                },
                                fontSize = 9.sp,
                                color = if (repeatMode != AudioRepeatMode.NONE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (repeatMode != AudioRepeatMode.NONE) FontWeight.Bold else FontWeight.Normal
                            )
                        }

                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Interactive Dialog Windows
    // -------------------------------------------------------------------------

    // Interactive 5-Band Equalizer Dialog
    if (showEqDialog) {
        val eqPresets = listOf("Flat", "Bass Boost", "Vocal Booster", "Rock", "Pop", "Jazz", "Classical")
        AlertDialog(
            onDismissRequest = { showEqDialog = false },
            icon = { Icon(AppIcons.Equalizer, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("Sound Equalizer") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Text("Presets", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(eqPresets) { preset ->
                            FilterChip(
                                selected = eqPreset == preset,
                                onClick = {
                                    eqPreset = preset
                                    when (preset) {
                                        "Bass Boost" -> { band60Hz = 0.9f; band230Hz = 0.75f; band910Hz = 0.5f; band3600Hz = 0.4f; band14000Hz = 0.3f }
                                        "Vocal Booster" -> { band60Hz = 0.3f; band230Hz = 0.45f; band910Hz = 0.85f; band3600Hz = 0.9f; band14000Hz = 0.4f }
                                        "Rock" -> { band60Hz = 0.8f; band230Hz = 0.65f; band910Hz = 0.45f; band3600Hz = 0.7f; band14000Hz = 0.85f }
                                        "Pop" -> { band60Hz = 0.4f; band230Hz = 0.55f; band910Hz = 0.75f; band3600Hz = 0.8f; band14000Hz = 0.45f }
                                        "Jazz" -> { band60Hz = 0.7f; band230Hz = 0.6f; band910Hz = 0.45f; band3600Hz = 0.55f; band14000Hz = 0.75f }
                                        "Classical" -> { band60Hz = 0.45f; band230Hz = 0.5f; band910Hz = 0.55f; band3600Hz = 0.65f; band14000Hz = 0.8f }
                                        else -> { band60Hz = 0.5f; band230Hz = 0.5f; band910Hz = 0.5f; band3600Hz = 0.5f; band14000Hz = 0.5f }
                                    }
                                },
                                label = { Text(preset, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text("5-Band Frequency Tuning", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        BandSlider("60Hz", band60Hz) { band60Hz = it }
                        BandSlider("230Hz", band230Hz) { band230Hz = it }
                        BandSlider("910Hz", band910Hz) { band910Hz = it }
                        BandSlider("3.6kHz", band3600Hz) { band3600Hz = it }
                        BandSlider("14kHz", band14000Hz) { band14000Hz = it }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Text("Bass boost", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Slider(
                        value = bassStrength,
                        onValueChange = { bassStrength = it },
                        valueRange = 0f..1000f,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text("Balance (L / R)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Slider(
                        value = balance,
                        onValueChange = { balance = it },
                        valueRange = -1f..1f,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = { showEqDialog = false }) { Text("Apply") }
            }
        )
    }

    // Add Track to Playlist Selection Dialog
    if (showAddToPlaylistDialog) {
        val track = addToPlaylistTarget ?: currentTrack
        AlertDialog(
            onDismissRequest = {
                showAddToPlaylistDialog = false
                addToPlaylistTarget = null
            },
            title = { Text("Add to Playlist") },
            text = {
                if (track == null) {
                    Text("No active track selected")
                } else if (playlists.isEmpty()) {
                    Text("No playlists created yet. Switch to the Playlists view to create one.")
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 260.dp)) {
                        items(playlists, key = { it.id }) { playlist ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        addTrackToPlaylist(track, playlist.id)
                                        showAddToPlaylistDialog = false
                                    }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(AppIcons.QueueMusic, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(playlist.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                Icon(AppIcons.Add, contentDescription = "Add")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAddToPlaylistDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Playlist Items View Dialog
    if (showPlaylistItemsDialog && selectedPlaylistId != null) {
        val playlistId = selectedPlaylistId!!
        val items by viewModel.getPlaylistItems(playlistId).collectAsState(initial = emptyList())
        AlertDialog(
            onDismissRequest = { showPlaylistItemsDialog = false },
            title = { Text(playlists.find { it.id == playlistId }?.name ?: "Playlist Tracks") },
            text = {
                if (items.isEmpty()) {
                    Text("This playlist is currently empty", style = MaterialTheme.typography.bodyMedium)
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                        itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val resolvable = resolvePlaylistMedia(items)
                                        val tapped = audioFiles.firstOrNull { it.file.absolutePath == item.uri }
                                        if (tapped != null) {
                                            playQueue = resolvable
                                            playTrackByPath(item.uri)
                                        } else {
                                            playTrackByPath(item.uri)
                                        }
                                        showPlaylistItemsDialog = false
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(AppIcons.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = {
                                        viewModel.movePlaylistItem(playlistId, item.id, -1)
                                    },
                                    enabled = index > 0
                                ) {
                                    Icon(AppIcons.ArrowUpward, contentDescription = "Move up")
                                }
                                IconButton(
                                    onClick = {
                                        viewModel.movePlaylistItem(playlistId, item.id, 1)
                                    },
                                    enabled = index < items.lastIndex
                                ) {
                                    Icon(AppIcons.ArrowDownward, contentDescription = "Move down")
                                }
                                IconButton(onClick = { viewModel.deletePlaylistItem(item.id) }) {
                                    Icon(AppIcons.Close, contentDescription = "Remove")
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Row {
                    if (items.isNotEmpty()) {
                        TextButton(onClick = {
                            val resolvable = resolvePlaylistMedia(items)
                            if (resolvable.isNotEmpty()) {
                                playQueue = resolvable
                                playTrackByPath(resolvable.first().file.absolutePath)
                                showPlaylistItemsDialog = false
                            }
                        }) { Text("Play all") }
                    }
                    TextButton(onClick = { showPlaylistItemsDialog = false }) { Text("Close") }
                }
            }
        )
    }

    // Play queue / up next
    if (showQueueSheet) {
        val queue = activeQueue
        AlertDialog(
            onDismissRequest = { showQueueSheet = false },
            title = { Text("Up next") },
            text = {
                if (queue.isEmpty()) {
                    Text("The queue is empty")
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 340.dp)) {
                        itemsIndexed(queue, key = { _, item -> item.file.absolutePath }) { index, item ->
                            val current = item.file.absolutePath == currentPath
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        playQueue = queue
                                        playTrackByPath(item.file.absolutePath)
                                        showQueueSheet = false
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${index + 1}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(24.dp)
                                )
                                Text(
                                    text = item.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                                    color = if (current) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                if (current && isPlaying) {
                                    Icon(
                                        AppIcons.VolumeUp,
                                        contentDescription = "Playing",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        removeFromQueue(item)
                                        if (activeQueue.size <= 1) showQueueSheet = false
                                    },
                                    enabled = activeQueue.size > 1
                                ) {
                                    Icon(AppIcons.Close, contentDescription = "Remove from queue")
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showQueueSheet = false }) { Text("Close") }
            }
        )
    }

    // Sleep timer
    if (showSleepDialog) {
        val remaining = AudioEngine.sleepDeadline.value - sleepClock
        val endOfQueue = AudioEngine.sleepAtEndOfQueue.value
        AlertDialog(
            onDismissRequest = { showSleepDialog = false },
            icon = { Icon(AppIcons.Timer, contentDescription = null) },
            title = { Text("Sleep timer") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val summary = when {
                        endOfQueue -> "Stops at the end of the queue"
                        remaining > 0 -> "Stops in ${(remaining + 59_999) / 60_000} min"
                        else -> "Off"
                    }
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(6.dp))
                    listOf(5, 15, 30, 60).forEach { minutes ->
                        TextButton(
                            onClick = {
                                AudioEngine.startSleepTimer(minutes)
                                showSleepDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("$minutes minutes") }
                    }
                    TextButton(
                        onClick = {
                            AudioEngine.startSleepAtEndOfQueue()
                            showSleepDialog = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("End of queue") }
                    TextButton(
                        onClick = {
                            AudioEngine.cancelSleepTimer()
                            showSleepDialog = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Off") }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSleepDialog = false }) { Text("Close") }
            }
        )
    }

    // Rename playlist
    renamePlaylistTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renamePlaylistTarget = null },
            title = { Text("Rename playlist") },
            text = {
                OutlinedTextField(
                    value = renamePlaylistText,
                    onValueChange = { renamePlaylistText = it },
                    label = { Text("Playlist name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.renamePlaylist(target.id, renamePlaylistText)
                        renamePlaylistTarget = null
                    },
                    enabled = renamePlaylistText.isNotBlank()
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renamePlaylistTarget = null }) { Text("Cancel") }
            }
        )
    }

    // Batch delete selected tracks (long-press multi-select)
    if (showDeleteSelectedDialog) {
        val count = selectedPaths.size
        AlertDialog(
            onDismissRequest = { showDeleteSelectedDialog = false },
            title = { Text("Delete $count track${if (count == 1) "" else "s"}?") },
            text = { Text("Delete the selected file${if (count == 1) "" else "s"} from storage? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteSelectedTracks()
                    showDeleteSelectedDialog = false
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteSelectedDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Full-screen now-playing player (tap the mini bar to open it)
    if (showFullPlayer && isPrepared) {
        val playingPath = currentPath.orEmpty()
        val fullPlayerTags by produceState<AudioTagInfo?>(AudioTags.cached(playingPath), playingPath) {
            value = withContext(Dispatchers.IO) { AudioTags.get(playingPath) }
        }
        val totalDur = durationMs.coerceAtLeast(1L)
        val currentPos = if (isSeeking) sliderPositionMs.toLong() else positionMs
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize().landscapeContentWidth()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { showFullPlayer = false }) {
                        Icon(AppIcons.KeyboardArrowDown, contentDescription = "Minimize player", modifier = Modifier.size(32.dp))
                    }
                    Text(
                        "Now Playing",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row {
                        IconButton(onClick = { toggleFavorite(playingPath) }) {
                            Icon(
                                if (playingPath in favoritePaths) AppIcons.Star else AppIcons.StarBorder,
                                contentDescription = "Favourite",
                                tint = if (playingPath in favoritePaths) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { showQueueSheet = true }) {
                            Icon(AppIcons.PlaylistPlay, contentDescription = "Queue")
                        }
                        IconButton(onClick = { showSleepDialog = true }) {
                            Icon(AppIcons.Timer, contentDescription = "Sleep timer")
                        }
                    }
                }

                Spacer(Modifier.weight(0.6f))

                // Large artwork
                Box(
                    modifier = Modifier
                        .size(260.dp)
                        .clip(AppCornerRadius.xl)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    val art = remember(playingPath) { extractArtwork(context, playingPath) }
                    if (art != null) {
                        Image(bitmap = art.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(AppIcons.MusicNote, contentDescription = null, modifier = Modifier.size(96.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(8.dp))
                            PlayingAudioVisualizer(isPlaying = isPlaying)
                        }
                    }
                }

                Spacer(Modifier.height(32.dp))

                Text(
                    text = fullPlayerTags?.title?.takeIf { it.isNotBlank() }
                        ?: currentTitle.ifBlank { playingPath.substringAfterLast('/') },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = buildString {
                        append(if (isPlaying) "Playing" else "Paused")
                        val artistName = fullPlayerTags?.artist?.takeIf { it.isNotBlank() }
                            ?: AudioEngine.artist.value
                        if (artistName.isNotBlank()) append(" • $artistName")
                        fullPlayerTags?.album?.takeIf { it.isNotBlank() }?.let { append(" • $it") }
                        val remaining = AudioEngine.sleepDeadline.value - sleepClock
                        if (remaining > 0) append(" • Sleep in ${(remaining + 59_999) / 60_000}m")
                        else if (AudioEngine.sleepAtEndOfQueue.value) append(" • Sleep at end")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.weight(0.5f))

                // Seekbar
                Slider(
                    value = currentPos.toFloat().coerceIn(0f, totalDur.toFloat()),
                    onValueChange = { newPos ->
                        isSeeking = true
                        sliderPositionMs = newPos
                    },
                    onValueChangeFinished = {
                        seekTo(sliderPositionMs.toLong())
                        isSeeking = false
                    },
                    valueRange = 0f..totalDur.toFloat(),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTimeMs(currentPos), style = MaterialTheme.typography.labelSmall)
                    Text(formatTimeMs(totalDur), style = MaterialTheme.typography.labelSmall)
                }

                Spacer(Modifier.height(12.dp))

                // Bottom controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(onClick = {
                            isShuffle = !isShuffle
                            queueNav.reset()
                            context.getSharedPreferences("audio_player", Context.MODE_PRIVATE).edit().putBoolean("shuffle", isShuffle).apply()
                        }) {
                            Icon(AppIcons.Shuffle, contentDescription = "Shuffle", tint = if (isShuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                        }
                        Text(if (isShuffle) "Shuffle" else "Off", fontSize = 9.sp, color = if (isShuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    IconButton(onClick = { previousTrack() }) {
                        Icon(AppIcons.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(38.dp))
                    }

                    FilledIconButton(onClick = { togglePlayPause() }, modifier = Modifier.size(68.dp)) {
                        Icon(
                            imageVector = if (isPlaying) AppIcons.Pause else AppIcons.PlayArrow,
                            contentDescription = "Play/Pause",
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    IconButton(onClick = { nextTrack() }) {
                        Icon(AppIcons.SkipNext, contentDescription = "Next", modifier = Modifier.size(38.dp))
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(onClick = {
                            repeatMode = when (repeatMode) {
                                AudioRepeatMode.NONE -> AudioRepeatMode.ALL
                                AudioRepeatMode.ALL -> AudioRepeatMode.ONE
                                AudioRepeatMode.ONE -> AudioRepeatMode.NONE
                            }
                            context.getSharedPreferences("audio_player", Context.MODE_PRIVATE).edit().putString("repeat", repeatMode.name).apply()
                        }) {
                            Icon(
                                imageVector = when (repeatMode) {
                                    AudioRepeatMode.ONE -> AppIcons.RepeatOne
                                    else -> AppIcons.Repeat
                                },
                                contentDescription = "Repeat",
                                tint = if (repeatMode != AudioRepeatMode.NONE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            )
                        }
                        Text(
                            when (repeatMode) { AudioRepeatMode.ONE -> "One"; AudioRepeatMode.ALL -> "All"; AudioRepeatMode.NONE -> "Off" },
                            fontSize = 9.sp,
                            color = if (repeatMode != AudioRepeatMode.NONE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { showFullPlayer = false }) {
                    Text("Back to playlist")
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Component Sub-Views & Helper Composables
// -----------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AudioTrackRow(
    item: MediaItem,
    isPlaying: Boolean,
    isCurrent: Boolean,
    selectMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onAddToPlaylist: () -> Unit,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    var tagInfo by remember(item.path) { mutableStateOf(AudioTags.cached(item.path)) }
    LaunchedEffect(item.path) {
        if (tagInfo == null) {
            tagInfo = withContext(Dispatchers.IO) { AudioTags.get(item.path) }
        }
    }
    val title = tagInfo?.title ?: item.name
    val subtitle = buildList {
        tagInfo?.artist?.let { add(it) }
        val duration = tagInfo?.durationMs ?: 0L
        if (duration > 0) add(formatTimeMs(duration))
        add(formatFileSize(item.sizeBytes))
        add(item.file.extension.uppercase())
    }.joinToString(" • ")

    Card(
        colors = CardDefaults.cardColors(
            containerColor = when {
                selected -> MaterialTheme.colorScheme.primaryContainer
                isCurrent -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                else -> MaterialTheme.colorScheme.surface
            }
        ),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                if (isPlaying && !selectMode) {
                    PlayingAudioVisualizer(isPlaying = true, tint = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Icon(
                        imageVector = AppIcons.MusicNote,
                        contentDescription = null,
                        tint = if (isCurrent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isFavorite) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            AppIcons.Star,
                            contentDescription = "Favourite",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (selectMode) {
                Icon(
                    imageVector = if (selected) AppIcons.CheckCircle else AppIcons.RadioButtonUnchecked,
                    contentDescription = if (selected) "Selected" else "Not selected",
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
                Spacer(modifier = Modifier.width(4.dp))
            } else {
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(AppIcons.MoreVert, contentDescription = "Track actions")
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Play next") },
                            leadingIcon = { Icon(AppIcons.PlaylistPlay, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                onPlayNext()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Add to queue") },
                            leadingIcon = { Icon(AppIcons.QueueMusic, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                onAddToQueue()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Add to playlist") },
                            leadingIcon = { Icon(AppIcons.PlaylistAdd, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                onAddToPlaylist()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(if (isFavorite) "Remove from favourites" else "Add to favourites") },
                            leadingIcon = {
                                Icon(
                                    if (isFavorite) AppIcons.Star else AppIcons.StarBorder,
                                    contentDescription = null
                                )
                            },
                            onClick = {
                                showMenu = false
                                onToggleFavorite()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioShelf(
    title: String,
    tracks: List<MediaItem>,
    currentPath: String?,
    isPlaying: Boolean,
    onOpen: (MediaItem) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(tracks, key = { it.file.absolutePath }) { item ->
                val current = item.file.absolutePath == currentPath
                Card(
                    onClick = { onOpen(item) },
                    colors = CardDefaults.cardColors(
                        containerColor = if (current) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.width(150.dp)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                                .clip(AppCornerRadius.sm)
                                .background(MaterialTheme.colorScheme.surface),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (current && isPlaying) AppIcons.VolumeUp else AppIcons.MusicNote,
                                contentDescription = null,
                                tint = if (current) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = item.name,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioFolderCard(
    group: AudioFolderGroup,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .padding(14.dp)
                .fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(86.dp)
                    .clip(AppCornerRadius.sm)
                    .background(Color(0xFF2196F3).copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = AppIcons.Folder,
                    contentDescription = null,
                    tint = Color(0xFF2196F3),
                    modifier = Modifier.size(48.dp)
                )
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = CircleShape,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                ) {
                    Text(
                        text = "${group.tracks.size}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = group.folder.name,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = formatFileSize(group.totalSizeBytes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PlaylistsOverviewScreen(
    playlists: List<com.example.data.db.PlaylistEntity>,
    onCreatePlaylist: (String) -> Unit,
    onDeletePlaylist: (com.example.data.db.PlaylistEntity) -> Unit,
    onRenamePlaylist: (com.example.data.db.PlaylistEntity) -> Unit,
    onSelectPlaylist: (Long) -> Unit
) {
    var newPlaylistName by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = newPlaylistName,
                onValueChange = { newPlaylistName = it },
                label = { Text("New playlist name") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    if (newPlaylistName.isNotBlank()) {
                        onCreatePlaylist(newPlaylistName.trim())
                        newPlaylistName = ""
                    }
                }
            ) {
                Text("Create")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (playlists.isEmpty()) {
            EmptyStateView("No playlists created yet")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(playlists, key = { it.id }) { playlist ->
                    Card(
                        onClick = { onSelectPlaylist(playlist.id) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(12.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(AppIcons.QueueMusic, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = playlist.name,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { onRenamePlaylist(playlist) }) {
                                Icon(AppIcons.Edit, contentDescription = "Rename")
                            }
                            IconButton(onClick = { onDeletePlaylist(playlist) }) {
                                Icon(AppIcons.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayingAudioVisualizer(isPlaying: Boolean, tint: Color = MaterialTheme.colorScheme.primary) {
    val transition = rememberInfiniteTransition(label = "audio_bars")
    val bar1 by transition.animateFloat(
        initialValue = 0.2f, targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(400, easing = LinearEasing), RepeatMode.Reverse), label = "b1"
    )
    val bar2 by transition.animateFloat(
        initialValue = 0.8f, targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(350, easing = LinearEasing), RepeatMode.Reverse), label = "b2"
    )
    val bar3 by transition.animateFloat(
        initialValue = 0.4f, targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(500, easing = LinearEasing), RepeatMode.Reverse), label = "b3"
    )

    Row(
        modifier = Modifier.size(24.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        val heights = if (isPlaying) listOf(bar1, bar2, bar3) else listOf(0.3f, 0.3f, 0.3f)
        heights.forEach { h ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(h)
                    .clip(RoundedCornerShape(2.dp))
                    .background(tint)
            )
        }
    }
}

@Composable
private fun CurrentTrackArtwork(path: String?, isPlaying: Boolean) {
    val artwork by produceState<Bitmap?>(initialValue = null, path) {
        value = if (path != null) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val mmr = MediaMetadataRetriever()
                    try {
                        mmr.setDataSource(path)
                        mmr.embeddedPicture?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                    } finally {
                        runCatching { mmr.release() }
                    }
                }.getOrNull()
            }
        } else null
    }

    if (artwork != null) {
        Image(
            bitmap = artwork!!.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(48.dp)
                .clip(AppCornerRadius.sm)
        )
    } else {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(MaterialTheme.colorScheme.surface, AppCornerRadius.sm),
            contentAlignment = Alignment.Center
        ) {
            PlayingAudioVisualizer(isPlaying = isPlaying)
        }
    }
}

private fun extractArtwork(context: Context, path: String?): Bitmap? {
    if (context == null || path == null) return null
    return runCatching {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(path)
            mmr.embeddedPicture?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
        } finally {
            runCatching { mmr.release() }
        }
    }.getOrNull()
}

@Composable
private fun RowScope.BandSlider(label: String, value: Float, onValueChange: (Float) -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.weight(1f)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Slider(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.padding(horizontal = 2.dp)
        )
    }
}

@Composable
private fun EmptyStateView(message: String) {
    Box(
        modifier = Modifier.fillMaxSize().landscapeContentWidth(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = AppIcons.MusicNote,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatTimeMs(millis: Long): String {
    val mins = TimeUnit.MILLISECONDS.toMinutes(millis)
    val secs = TimeUnit.MILLISECONDS.toSeconds(millis) - TimeUnit.MINUTES.toSeconds(mins)
    return String.format(Locale.getDefault(), "%02d:%02d", mins, secs)
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt().coerceAtMost(5)
    val pre = "KMGTPE"[exp - 1]
    return String.format(Locale.getDefault(), "%.1f %cB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
}
