package com.example.ui.screens.recorder

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.ui.components.landscapeContentWidth
import com.example.ui.components.AppIcons
import com.example.ui.components.FloatingTopBar
import com.example.ui.components.AppSearchField
import com.example.ui.theme.AppCornerRadius
import com.example.ui.MainViewModel
import com.example.util.InternalAudioCapture
import com.example.util.InternalAudioRecordController
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RecorderStore {
    fun dir(context: Context): File {
        // With All-Files access the public Music dir is writable and immediately
        // visible to every file manager and to MediaStore; otherwise fall back to
        // app-external storage (always writable, but hidden under Android/data).
        val allFiles = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            Environment.isExternalStorageManager()
        if (allFiles) {
            val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            val publicDir = File(base, "NJ-Dialer/Recordings")
            if (publicDir.exists() || publicDir.mkdirs()) return publicDir
        }

        val ext = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
        val extDir = if (ext != null) File(ext, "Recordings") else null
        if (extDir != null && (extDir.exists() || extDir.mkdirs())) return extDir

        return File(context.filesDir, "Recordings").also { runCatching { it.mkdirs() } }
    }

    fun scan(context: Context, file: File) {
        runCatching {
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
        }
    }

    fun list(context: Context): List<File> = runCatching {
        dir(context).listFiles { f -> f.isFile && (f.extension == "m4a" || f.extension == "mp3" || f.extension == "3gp") }
            .orEmpty()
            .sortedByDescending { it.lastModified() }
    }.getOrDefault(emptyList())

    fun newFile(context: Context, prefix: String = "REC"): File = runCatching {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        File(dir(context), "${prefix}_$stamp.m4a")
    }.getOrDefault(File(context.cacheDir, "${prefix}_${System.currentTimeMillis()}.m4a"))

    fun durationMs(path: String): Long = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            runCatching { retriever.release() }
        }
    }.getOrDefault(0L)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun RecorderScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var isRecording by remember { mutableStateOf(false) }
    var isPaused by remember { mutableStateOf(false) }
    var recordMillis by remember { mutableLongStateOf(0L) }
    var recordingName by remember { mutableStateOf("") }
    var amplitudeLevel by remember { mutableFloatStateOf(0f) }

    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playingFile by remember { mutableStateOf<File?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var progressMs by remember { mutableLongStateOf(0L) }
    var totalDurationMs by remember { mutableLongStateOf(0L) }

    var searchQuery by remember { mutableStateOf("") }
    var renameDialogFile by remember { mutableStateOf<File?>(null) }
    var newFileNameText by remember { mutableStateOf("") }
    var menuFile by remember { mutableStateOf<File?>(null) }
    var deleteTarget by remember { mutableStateOf<File?>(null) }

    var recordings by remember { mutableStateOf(RecorderStore.list(context)) }
    val useGlassmorphism by viewModel.useGlassmorphism.collectAsState()
    val internalState by InternalAudioRecordController.state.collectAsState()
    var useSystemAudio by remember { mutableStateOf(false) }
    val systemRecording = internalState.isStarting || internalState.isRecording
    val recordingActive = isRecording || systemRecording

    val hasRecordPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

    fun toast(msg: String) {
        runCatching { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
    }

    fun triggerHaptic() {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    fun fmtClock(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }

    fun fmtDuration(ms: Long): String = String.format(
        Locale.US, "%02d:%02d", ms / 60000, (ms % 60000) / 1000
    )

    fun fmtSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    fun stopPlayback() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        playingFile = null
        isPlaying = false
        progressMs = 0L
        totalDurationMs = 0L
    }

    fun startRecording() {
        if (recordingActive) return
        triggerHaptic()
        if (useSystemAudio) {
            if (!InternalAudioCapture.supported()) {
                toast("System audio capture needs Android 10 or newer")
                return
            }
            InternalAudioRecordController.begin(context)
            return
        }
        val file = RecorderStore.newFile(context, "REC")
        try {
            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioSamplingRate(44100)
            rec.setAudioEncodingBitRate(128000)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
            recorder = rec
            recordingName = file.name
            recordMillis = 0L
            isPaused = false
            isRecording = true
            stopPlayback()
        } catch (e: SecurityException) {
            toast("Recording blocked by system policy")
        } catch (e: Exception) {
            toast("Could not start recording: ${e.message}")
        }
    }

    fun pauseRecording() {
        triggerHaptic()
        runCatching { recorder?.pause() }
        isPaused = true
    }

    fun resumeRecording() {
        triggerHaptic()
        runCatching { recorder?.resume() }
        isPaused = false
    }

    fun stopRecording() {
        if (systemRecording) {
            InternalAudioRecordController.stop(context)
            return
        }
        if (!isRecording) return
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        runCatching { recorder?.stop() }
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        recorder = null
        isRecording = false
        isPaused = false
        amplitudeLevel = 0f
        if (recordingName.isNotEmpty()) {
            RecorderStore.scan(context, File(RecorderStore.dir(context), recordingName))
        }
        recordings = RecorderStore.list(context)
        toast("Recording saved")
    }

    fun togglePlayback(file: File) {
        triggerHaptic()
        if (playingFile == file && isPlaying) {
            runCatching { player?.pause() }
            isPlaying = false
            return
        }
        if (playingFile == file) {
            runCatching { player?.start() }
            isPlaying = true
            return
        }
        stopPlayback()
        try {
            val p = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    playingFile = null
                    isPlaying = false
                    progressMs = 0L
                }
                setOnPreparedListener {
                    it.start()
                    playingFile = file
                    isPlaying = true
                    totalDurationMs = it.duration.toLong()
                }
                setOnErrorListener { _, _, _ ->
                    playingFile = null
                    isPlaying = false
                    true
                }
                prepareAsync()
            }
            player = p
        } catch (e: Exception) {
            toast("Cannot play this recording")
        }
    }

    fun seekPlayback(targetMs: Float) {
        player?.let {
            it.seekTo(targetMs.toInt())
            progressMs = targetMs.toLong()
        }
    }

    fun deleteRecording(file: File) {
        triggerHaptic()
        if (playingFile == file) stopPlayback()
        val entry = com.example.util.RecycleBin.trash(context, file)
        if (entry == null) {
            toast("Could not delete ${file.name}")
        } else {
            recordings = RecorderStore.list(context)
        }
    }

    fun shareRecording(file: File) {
        triggerHaptic()
        try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share Recording"))
        } catch (e: Exception) {
            toast("Unable to share file")
        }
    }

    fun renameRecording(file: File, newName: String) {
        if (newName.isBlank()) return
        val sanitized = if (newName.endsWith(".m4a")) newName else "$newName.m4a"
        val newFile = File(file.parent, sanitized)
        if (file.renameTo(newFile)) {
            if (playingFile == file) playingFile = newFile
            RecorderStore.scan(context, newFile)
            recordings = RecorderStore.list(context)
            toast("Renamed successfully")
        } else {
            toast("Failed to rename file")
        }
    }

    LaunchedEffect(internalState.isRecording, internalState.outputPath) {
        if (!internalState.isRecording && internalState.outputPath != null) {
            recordings = RecorderStore.list(context)
        }
    }

    // Recording timer & Live Audio Visualizer Amplitude
    LaunchedEffect(isRecording, isPaused) {
        if (isRecording && !isPaused) {
            val startTime = System.currentTimeMillis() - recordMillis
            while (true) {
                delay(100L)
                recordMillis = System.currentTimeMillis() - startTime
                runCatching {
                    val maxAmp = recorder?.maxAmplitude ?: 0
                    amplitudeLevel = (maxAmp / 32767f).coerceIn(0f, 1f)
                }
            }
        }
    }

    // Playback progress ticker
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            delay(300L)
            progressMs = player?.currentPosition?.toLong() ?: 0L
        }
    }

    val currentSystemRecording = rememberUpdatedState(systemRecording)
    DisposableEffect(Unit) {
        onDispose {
            if (currentSystemRecording.value) {
                InternalAudioRecordController.stop(context)
            }
            if (isRecording) {
                runCatching { recorder?.stop() }
            }
            runCatching { recorder?.release() }
            runCatching { player?.release() }
        }
    }

    val filteredRecordings = remember(recordings, searchQuery) {
        if (searchQuery.isBlank()) recordings
        else recordings.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }

    if (!hasRecordPermission) {
        Box(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(32.dp)
            ) {
                Icon(
                    AppIcons.MicOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "Microphone Permission Required",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Allow microphone access to record audio.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                        )
                    }
                }) {
                    Text("Open App Settings")
                }
            }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // --- Recording Status Card & Visualizer ---
        Surface(
            color = if (useGlassmorphism) MaterialTheme.colorScheme.surface.copy(alpha = 0.7f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(vertical = 18.dp)
            ) {
                Text(
                    text = when {
                        internalState.isStarting -> "Preparing system audio…"
                        systemRecording -> "Recording system audio"
                        isRecording && isPaused -> "Paused"
                        isRecording -> "Recording..."
                        else -> "Ready to Record"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (recordingActive) {
                        if (isPaused) Color(0xFFF59E0B) else Color(0xFFEF4444)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (recordingActive) {
                        fmtClock(if (systemRecording) internalState.elapsedMs else recordMillis)
                    } else "00:00",
                    style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )

                // Dynamic Amplitude Visualizer Bar
                if (recordingActive) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.height(24.dp)
                    ) {
                        repeat(12) { index ->
                            val heightFactor = (amplitudeLevel * (1f + (index % 3) * 0.3f)).coerceIn(0.15f, 1f)
                            Box(
                                modifier = Modifier
                                    .width(6.dp)
                                    .fillMaxHeight(heightFactor)
                                    .clip(AppCornerRadius.xs)
                                    .background(if (isPaused) Color(0xFFF59E0B) else Color(0xFFEF4444))
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (systemRecording) {
                            internalState.outputPath?.let { File(it).name } ?: ""
                        } else recordingName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = !useSystemAudio,
                onClick = { if (!recordingActive) useSystemAudio = false },
                enabled = !recordingActive,
                label = { Text("Mic") },
                leadingIcon = { Icon(AppIcons.Mic, contentDescription = null, modifier = Modifier.size(16.dp)) }
            )
            FilterChip(
                selected = useSystemAudio,
                onClick = { if (!recordingActive) useSystemAudio = true },
                enabled = !recordingActive && InternalAudioCapture.supported(),
                label = { Text("System playback") },
                leadingIcon = { Icon(AppIcons.VolumeUp, contentDescription = null, modifier = Modifier.size(16.dp)) }
            )
        }

        // --- Controls ---
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isRecording) {
                Surface(
                    onClick = { if (isPaused) resumeRecording() else pauseRecording() },
                    shape = CircleShape,
                    color = if (isPaused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = if (isPaused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(56.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (isPaused) AppIcons.PlayArrow else AppIcons.Pause,
                            contentDescription = if (isPaused) "Resume" else "Pause",
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(28.dp))
            }

            Surface(
                onClick = { if (recordingActive) stopRecording() else startRecording() },
                shape = CircleShape,
                color = if (recordingActive) Color(0xFFEF4444) else MaterialTheme.colorScheme.primary,
                contentColor = Color.White,
                shadowElevation = 6.dp,
                modifier = Modifier.size(80.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (recordingActive) {
                            AppIcons.Stop
                        } else if (useSystemAudio) {
                            AppIcons.VolumeUp
                        } else {
                            AppIcons.Mic
                        },
                        contentDescription = if (recordingActive) "Stop" else "Record",
                        modifier = Modifier.size(34.dp)
                    )
                }
            }

            if (isRecording) {
                Spacer(modifier = Modifier.width(28.dp))
                Surface(
                    onClick = { stopRecording() },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(56.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(AppIcons.Check, contentDescription = "Save", modifier = Modifier.size(26.dp))
                    }
                }
            }
        }

        internalState.error?.let { error ->
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // --- Search Bar & Header ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Recordings (${filteredRecordings.size})",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        }

        AppSearchField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = "Search recordings...",
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        )

        // --- Recordings List ---
        if (filteredRecordings.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    if (searchQuery.isNotEmpty()) "No matching recordings found." else "No recordings yet. Tap the mic to start.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().weight(1f)
            ) {
                items(filteredRecordings, key = { it.absolutePath }) { file ->
                    val isThisPlaying = playingFile == file && isPlaying
                    val durationMs = remember(file.absolutePath) { RecorderStore.durationMs(file.absolutePath) }

                    Box {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { togglePlayback(file) },
                                onLongClick = {
                                    triggerHaptic()
                                    menuFile = file
                                }
                            ),
                        shape = AppCornerRadius.md,
                        color = if (isThisPlaying) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = if (isThisPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = if (isThisPlaying) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(42.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            if (isThisPlaying) AppIcons.Pause else AppIcons.PlayArrow,
                                            contentDescription = if (isThisPlaying) "Pause" else "Play",
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = file.name,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${fmtDuration(durationMs)}  •  ${fmtSize(file.length())}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = {
                                    renameDialogFile = file
                                    newFileNameText = file.nameWithoutExtension
                                }) {
                                    Icon(
                                        AppIcons.Edit,
                                        contentDescription = "Rename",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            // Interactive Seek Bar during active playback
                            if (isThisPlaying && totalDurationMs > 0) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Column {
                                    Slider(
                                        value = progressMs.toFloat(),
                                        onValueChange = { seekPlayback(it) },
                                        valueRange = 0f..totalDurationMs.toFloat(),
                                        modifier = Modifier.fillMaxWidth().height(24.dp)
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = fmtDuration(progressMs),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = fmtDuration(totalDurationMs),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                    DropdownMenu(
                        expanded = menuFile?.absolutePath == file.absolutePath,
                        onDismissRequest = { menuFile = null }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Share") },
                            leadingIcon = { Icon(AppIcons.Share, contentDescription = null) },
                            onClick = {
                                menuFile = null
                                shareRecording(file)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            leadingIcon = {
                                Icon(
                                    AppIcons.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            },
                            onClick = {
                                menuFile = null
                                deleteTarget = file
                            }
                        )
                    }
                    }
                }
            }
        }

        // Delete Confirmation Dialog
        deleteTarget?.let { file ->
            AlertDialog(
                onDismissRequest = { deleteTarget = null },
                title = { Text("Delete recording?") },
                text = { Text("${file.name} will be moved to the Recycle bin.") },
                confirmButton = {
                    TextButton(onClick = {
                        deleteRecording(file)
                        deleteTarget = null
                    }) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
                }
            )
        }

        // Rename File Dialog
        renameDialogFile?.let { file ->
            AlertDialog(
                onDismissRequest = { renameDialogFile = null },
                title = { Text("Rename Recording") },
                text = {
                    OutlinedTextField(
                        value = newFileNameText,
                        onValueChange = { newFileNameText = it },
                        label = { Text("File Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        renameRecording(file, newFileNameText)
                        renameDialogFile = null
                    }) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { renameDialogFile = null }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}
