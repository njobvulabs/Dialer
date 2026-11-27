package com.example.ui.screens.retro

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.ui.MainViewModel
import com.example.ui.components.AppIcons
import com.example.ui.components.FloatingTopBar
import com.example.ui.components.formatFileSize
import com.example.ui.components.landscapeContentWidth
import com.example.util.RetroRom
import com.example.util.RetroRomStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun RetroScreen(viewModel: MainViewModel, onGameplayChanged: (Boolean) -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val scope = rememberCoroutineScope()
    val session = remember { AtomicInteger(0) }
    val useGlass by viewModel.useGlassmorphism.collectAsState()

    var roms by remember { mutableStateOf(emptyList<RetroRom>()) }
    var activeRom by remember { mutableStateOf<RetroRom?>(null) }
    var avInfo by remember { mutableStateOf(intArrayOf(480, 272, 60, 44100)) }
    var running by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var gameMenuOpen by remember { mutableStateOf(false) }
    var fpsLabel by remember { mutableStateOf("") }

    var showFps by remember { mutableStateOf(RetroPrefs.showFps(context)) }
    var soundEnabled by remember { mutableStateOf(RetroPrefs.sound(context)) }
    var autoSave by remember { mutableStateOf(RetroPrefs.autoSave(context)) }
    var immersiveMode by remember { mutableStateOf(RetroPrefs.immersiveMode(context)) }
    var showAnalog by remember { mutableStateOf(RetroPrefs.showAnalog(context)) }
    var orientationPref by remember { mutableStateOf(RetroPrefs.orientation(context)) }
    var controlAlpha by remember { mutableStateOf(RetroPrefs.controlAlpha(context)) }
    var controlScale by remember { mutableStateOf(RetroPrefs.controlScale(context)) }
    var controlOffset by remember { mutableStateOf(RetroPrefs.controlOffset(context).dp) }
    var audioTrack by remember { mutableStateOf<AudioTrack?>(null) }

    val surfaceView = remember(context) {
        SurfaceView(context).apply {
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    RetroNative.setSurface(holder.surface)
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    RetroNative.setSurface(holder.surface)
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    RetroNative.setSurface(null)
                }
            })
        }
    }

    fun applyGameplaySystemUi(inGame: Boolean) {
        val act = activity ?: return
        act.requestedOrientation = if (inGame) {
            when (orientationPref) {
                RetroOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                RetroOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                RetroOrientation.AUTO -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            }
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

        val controller = WindowCompat.getInsetsController(act.window, act.window.decorView)
        if (inGame && immersiveMode) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    fun releaseAudio() {
        audioTrack?.release()
        audioTrack = null
        RetroNative.setAudioTrack(null, 0, 0)
    }

    fun startRom(rom: RetroRom) {
        val token = session.incrementAndGet()
        running = false
        paused = false
        loading = true
        gameMenuOpen = false
        RetroNative.setButtons(0L)
        releaseAudio()
        scope.launch(Dispatchers.IO) {
            val dirs = RetroRomStore.dirs(context)
            RetroNative.setPaths(dirs.system.absolutePath, dirs.saves.absolutePath)
            val ok = runCatching { RetroNative.loadRom(rom.path) }.getOrDefault(false)
            val info = if (ok) RetroNative.avInfo() else intArrayOf(480, 272, 60, 44100)
            val updated = RetroRomStore.touch(context, rom.id)
            withContext(Dispatchers.Main) {
                if (token != session.get()) return@withContext
                loading = false
                if (!ok) {
                    Toast.makeText(context, "Could not load this ROM", Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                activeRom = rom
                roms = updated
                avInfo = info
                if (soundEnabled) {
                    val sampleRate = info.getOrElse(3) { 44100 }.coerceIn(8000, 192000)
                    val minBuffer = AudioTrack.getMinBufferSize(
                        sampleRate,
                        AudioFormat.CHANNEL_OUT_STEREO,
                        AudioFormat.ENCODING_PCM_16BIT
                    )
                    if (minBuffer > 0) {
                        val frames = maxOf(512, minBuffer / 4)
                        val track = AudioTrack(
                            AudioManager.STREAM_MUSIC,
                            sampleRate,
                            AudioFormat.CHANNEL_OUT_STEREO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            frames * 4,
                            AudioTrack.MODE_STREAM
                        )
                        track.play()
                        audioTrack = track
                        RetroNative.setAudioTrack(track, frames, AudioTrack.WRITE_NON_BLOCKING)
                    }
                }
                running = true
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val imported = RetroRomStore.import(context, uri)
            val updated = RetroRomStore.load(context)
            withContext(Dispatchers.Main) {
                roms = updated
                if (imported == null) {
                    Toast.makeText(context, "Import failed. Check ROM format.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Added ${imported.name}", Toast.LENGTH_SHORT).show()
                    startRom(imported)
                }
            }
        }
    }

    fun stopRom(saveSram: Boolean = true) {
        val token = session.incrementAndGet()
        val current = activeRom
        running = false
        paused = false
        activeRom = null
        gameMenuOpen = false
        RetroNative.setButtons(0L)
        releaseAudio()
        applyGameplaySystemUi(false)
        scope.launch(Dispatchers.IO) {
            delay(60)
            if (token != session.get()) return@launch
            if (current != null && saveSram && RetroPrefs.autoSave(context)) {
                runCatching { RetroNative.saveSram(RetroRomStore.sramFile(context, current).absolutePath) }
            }
            runCatching { RetroNative.unload() }
        }
    }

    fun saveState(slot: Int) {
        val rom = activeRom ?: return
        scope.launch(Dispatchers.IO) {
            val ok = runCatching {
                RetroNative.saveState(RetroRomStore.stateFile(context, rom, slot).absolutePath)
            }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, if (ok) "Saved state $slot" else "Save state failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun loadState(slot: Int) {
        val rom = activeRom ?: return
        scope.launch(Dispatchers.IO) {
            val ok = runCatching {
                RetroNative.loadState(RetroRomStore.stateFile(context, rom, slot).absolutePath)
            }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, if (ok) "Loaded state $slot" else "Load state failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(Unit) {
        RetroNative.init()
        roms = withContext(Dispatchers.IO) { RetroRomStore.load(context) }
    }

    DisposableEffect(Unit) {
        onDispose {
            session.incrementAndGet()
            runCatching { RetroNative.setButtons(0L) }
            runCatching { RetroNative.unload() }
            audioTrack?.release()
            RetroNative.setAudioTrack(null, 0, 0)
            applyGameplaySystemUi(false)
            onGameplayChanged(false)
        }
    }

    LaunchedEffect(running, paused, showFps) {
        if (!running || paused) return@LaunchedEffect
        val fps = avInfo.getOrElse(2) { 60 }.coerceIn(1, 240)
        val frameTime = 1_000_000_000L / fps
        var frames = 0
        var lastFps = System.currentTimeMillis()
        while (isActive && running && !paused) {
            val start = System.nanoTime()
            if (!RetroNative.runFrame()) {
                running = false
                break
            }
            frames++
            if (showFps) {
                val now = System.currentTimeMillis()
                if (now - lastFps >= 1000) {
                    fpsLabel = "$frames FPS"
                    frames = 0
                    lastFps = now
                }
            } else if (fpsLabel.isNotEmpty()) {
                fpsLabel = ""
            }
            val spent = System.nanoTime() - start
            if (spent < frameTime) delay((frameTime - spent) / 1_000_000L)
        }
    }

    val game = activeRom
    LaunchedEffect(game != null, orientationPref, immersiveMode) {
        onGameplayChanged(game != null)
        applyGameplaySystemUi(game != null)
    }

    val ratio = if (game != null && avInfo[0] > 0 && avInfo[1] > 0) {
        avInfo[0].toFloat() / avInfo[1].toFloat()
    } else {
        16f / 9f
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (game == null) {
            FloatingTopBar(useGlass = useGlass) {
                Text(
                    text = "Retro Games",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { soundEnabled = !soundEnabled; RetroPrefs.setSound(context, soundEnabled) }) {
                    Icon(
                        imageVector = if (soundEnabled) AppIcons.VolumeUp else AppIcons.VolumeOff,
                        contentDescription = if (soundEnabled) "Sound on" else "Sound off"
                    )
                }
                IconButton(onClick = { settingsOpen = true }) {
                    Icon(AppIcons.Settings, contentDescription = "Settings")
                }
                IconButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                    Icon(AppIcons.Add, contentDescription = "Import ROM")
                }
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .landscapeContentWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text("Import GB/GBC/GBA ROMs", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Defaults to Landscape & Immersive mode on Tecno Spark 40 (KM5). No BIOS or game files are bundled.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                            Spacer(Modifier.height(10.dp))
                            Button(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                                Icon(AppIcons.Add, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Import Game")
                            }
                        }
                    }
                }
                if (roms.isEmpty()) {
                    item {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(AppIcons.SportsEsports, contentDescription = null, modifier = Modifier.size(42.dp))
                                Spacer(Modifier.height(10.dp))
                                Text("No games imported yet", style = MaterialTheme.typography.titleSmall)
                            }
                        }
                    }
                }
                items(roms, key = { it.id }) { rom ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(AppIcons.SportsEsports, contentDescription = null, modifier = Modifier.size(30.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(rom.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${formatFileSize(rom.size)} · ${rom.path.substringAfterLast('.', "").uppercase()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                            }
                            IconButton(onClick = {
                                scope.launch(Dispatchers.IO) {
                                    val ok = RetroRomStore.delete(context, rom)
                                    val updated = RetroRomStore.load(context)
                                    withContext(Dispatchers.Main) {
                                        roms = updated
                                        if (!ok) Toast.makeText(context, "Could not delete ROM", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }) {
                                Icon(AppIcons.Delete, contentDescription = "Delete ${rom.name}")
                            }
                            Button(onClick = { startRom(rom) }) { Text("Play") }
                        }
                    }
                }
            }
        } else {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val isLandscape = maxWidth > maxHeight
                val containerMaxWidth = maxWidth
                val containerMaxHeight = maxHeight
                val topInsetModifier = if (immersiveMode) Modifier else Modifier.statusBarsPadding()

                val viewportBoxModifier = if (isLandscape) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier
                        .align(Alignment.TopCenter)
                        .then(topInsetModifier)
                        .padding(top = 48.dp, start = 4.dp, end = 4.dp)
                        .fillMaxWidth()
                        .fillMaxHeight(0.42f)
                }

                Box(
                    modifier = viewportBoxModifier,
                    contentAlignment = Alignment.Center
                ) {
                    val width = minOf(containerMaxWidth, containerMaxHeight * ratio)
                    val height = width / ratio
                    Surface(
                        color = Color.Black,
                        shape = if (isLandscape) RectangleShape else MaterialTheme.shapes.medium,
                        modifier = Modifier.size(width, height)
                    ) {
                        AndroidView(
                            factory = { surfaceView },
                            update = { view ->
                                view.holder.surface?.takeIf { it.isValid }?.let { validSurface ->
                                    RetroNative.setSurface(validSurface)
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.78f),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .then(topInsetModifier)
                        .padding(start = if (isLandscape) 30.dp else 10.dp, top = if (isLandscape) 12.dp else 8.dp)
                ) {
                    Text(
                        text = listOfNotNull(
                            game.name,
                            "${avInfo[0]}x${avInfo[1]}",
                            fpsLabel.takeIf { showFps && fpsLabel.isNotBlank() }
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }

                if (loading) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.9f),
                        modifier = Modifier.align(Alignment.Center)
                    ) {
                        Text(
                            "Loading ROM…",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                }

                RetroGamepad(
                    alpha = controlAlpha,
                    scale = controlScale,
                    verticalOffset = controlOffset,
                    showAnalog = showAnalog,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(if (isLandscape) 1f else 0.54f),
                    onButtons = { mask -> RetroNative.setButtons(mask) }
                )

                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .then(topInsetModifier)
                        .padding(end = if (isLandscape) 24.dp else 6.dp, top = if (isLandscape) 8.dp else 4.dp)
                ) {
                    IconButton(onClick = { gameMenuOpen = true }) {
                        Icon(AppIcons.MoreVert, contentDescription = "Game menu")
                    }
                    DropdownMenu(
                        expanded = gameMenuOpen,
                        onDismissRequest = { gameMenuOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(if (paused) "Resume" else "Pause") },
                            onClick = { gameMenuOpen = false; paused = !paused }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (isLandscape) "Switch to Portrait" else "Switch to Landscape"
                                )
                            },
                            onClick = {
                                gameMenuOpen = false
                                val next = if (isLandscape) RetroOrientation.PORTRAIT else RetroOrientation.LANDSCAPE
                                orientationPref = next
                                RetroPrefs.setOrientation(context, next)
                                applyGameplaySystemUi(true)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Reset game") },
                            onClick = { gameMenuOpen = false; RetroNative.reset() }
                        )
                        listOf(1, 2, 3).forEach { slot ->
                            DropdownMenuItem(
                                text = { Text("Save state $slot") },
                                onClick = { gameMenuOpen = false; saveState(slot) }
                            )
                            DropdownMenuItem(
                                text = { Text("Load state $slot") },
                                onClick = { gameMenuOpen = false; loadState(slot) }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Emulator settings") },
                            onClick = { gameMenuOpen = false; settingsOpen = true }
                        )
                        DropdownMenuItem(
                            text = { Text("Exit to menu") },
                            onClick = { gameMenuOpen = false; stopRom() }
                        )
                    }
                }
            }
        }
    }

    if (settingsOpen) {
        AlertDialog(
            onDismissRequest = { settingsOpen = false },
            title = { Text("Emulator settings") },
            text = {
                Column {
                    Text("Screen orientation", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        RetroOrientation.entries.forEach { mode ->
                            FilterChip(
                                selected = orientationPref == mode,
                                onClick = {
                                    orientationPref = mode
                                    RetroPrefs.setOrientation(context, mode)
                                    if (activeRom != null) applyGameplaySystemUi(true)
                                },
                                label = { Text(mode.label, maxLines = 1) }
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("Immersive fullscreen", modifier = Modifier.weight(1f))
                        Switch(checked = immersiveMode, onCheckedChange = {
                            immersiveMode = it
                            RetroPrefs.setImmersiveMode(context, it)
                            if (activeRom != null) applyGameplaySystemUi(true)
                        })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("On-screen analog stick", modifier = Modifier.weight(1f))
                        Switch(checked = showAnalog, onCheckedChange = {
                            showAnalog = it
                            RetroPrefs.setShowAnalog(context, it)
                        })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("Show FPS", modifier = Modifier.weight(1f))
                        Switch(checked = showFps, onCheckedChange = {
                            showFps = it
                            RetroPrefs.setShowFps(context, it)
                        })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("Sound", modifier = Modifier.weight(1f))
                        Switch(checked = soundEnabled, onCheckedChange = {
                            soundEnabled = it
                            RetroPrefs.setSound(context, it)
                        })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("Save SRAM on exit", modifier = Modifier.weight(1f))
                        Switch(checked = autoSave, onCheckedChange = {
                            autoSave = it
                            RetroPrefs.setAutoSave(context, it)
                        })
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Control opacity: ${(controlAlpha * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = controlAlpha,
                        onValueChange = {
                            controlAlpha = it
                            RetroPrefs.setControlAlpha(context, it)
                        },
                        valueRange = 0.35f..1f
                    )
                    Text("Control size: ${(controlScale * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = controlScale,
                        onValueChange = {
                            controlScale = it
                            RetroPrefs.setControlScale(context, it)
                        },
                        valueRange = 0.8f..1.3f
                    )
                    Text("Control height: ${controlOffset.value.toInt()} dp", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = controlOffset.value,
                        onValueChange = {
                            controlOffset = it.dp
                            RetroPrefs.setControlOffset(context, it)
                        },
                        valueRange = -48f..48f
                    )
                    TextButton(onClick = {
                        RetroPrefs.resetControls(context)
                        controlAlpha = 0.78f
                        controlScale = 1f
                        controlOffset = 0.dp
                        showAnalog = true
                    }) {
                        Text("Reset controls")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { settingsOpen = false }) { Text("Done") } }
        )
    }
}
