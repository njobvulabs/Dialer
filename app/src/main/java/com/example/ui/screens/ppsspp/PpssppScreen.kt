package com.example.ui.screens.ppsspp

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.media.AudioAttributes
import android.media.AudioFormat
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.example.util.PpssppRom
import com.example.util.PpssppRomStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun PpssppScreen(viewModel: MainViewModel, onGameplayChanged: (Boolean) -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val scope = rememberCoroutineScope()
    val session = remember { AtomicInteger(0) }
    val emuActive = remember { AtomicBoolean(false) }
    val emuPaused = remember { AtomicBoolean(false) }
    val useGlass by viewModel.useGlassmorphism.collectAsState()

    val emuExecutor = remember {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "ppsspp-emu-worker").apply { isDaemon = true }
        }
    }

    var roms by remember { mutableStateOf(emptyList<PpssppRom>()) }
    var activeRom by remember { mutableStateOf<PpssppRom?>(null) }
    var avInfo by remember { mutableStateOf(intArrayOf(480, 272, 60, 44100)) }
    var running by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var gameMenuOpen by remember { mutableStateOf(false) }
    var fpsLabel by remember { mutableStateOf("") }
    var lastInputHex by remember { mutableStateOf("0") }

    var soundEnabled by remember { mutableStateOf(PpssppPrefs.sound(context)) }
    var showFps by remember { mutableStateOf(PpssppPrefs.showFps(context)) }
    var immersiveMode by remember { mutableStateOf(PpssppPrefs.immersiveMode(context)) }
    var showAnalog by remember { mutableStateOf(PpssppPrefs.showAnalog(context)) }
    var autoSave by remember { mutableStateOf(PpssppPrefs.autoSave(context)) }
    var orientationPref by remember { mutableStateOf(PpssppPrefs.orientation(context)) }
    var cpuCore by remember { mutableStateOf(PpssppPrefs.cpuCore(context)) }
    var resolution by remember { mutableStateOf(PpssppPrefs.resolution(context)) }
    var controlAlpha by remember { mutableStateOf(PpssppPrefs.controlAlpha(context)) }
    var controlScale by remember { mutableStateOf(PpssppPrefs.controlScale(context)) }
    var controlOffset by remember { mutableStateOf(PpssppPrefs.controlOffset(context).dp) }
    var audioTrack by remember { mutableStateOf<AudioTrack?>(null) }

    val showFpsState = remember { mutableStateOf(showFps) }
    showFpsState.value = showFps

    val surfaceView = remember(context) {
        SurfaceView(context).apply {
            setZOrderOnTop(false)
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    val surface = holder.surface
                    if (surface != null && surface.isValid) {
                        runCatching { PpssppRetroNative.setSurface(surface) }
                    }
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    val surface = holder.surface
                    if (surface != null && surface.isValid) {
                        runCatching { PpssppRetroNative.setSurface(surface) }
                    }
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    runCatching { PpssppRetroNative.setSurface(null) }
                }
            })
        }
    }

    fun applyGameplaySystemUi(inGame: Boolean) {
        val act = activity ?: return
        act.requestedOrientation = if (inGame) {
            when (orientationPref) {
                PpssppOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                PpssppOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                PpssppOrientation.AUTO -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
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
        val currentTrack = audioTrack
        audioTrack = null
        emuExecutor.execute {
            runCatching { PpssppRetroNative.setAudioTrack(null, 0, 0) }
            runCatching {
                currentTrack?.pause()
                currentTrack?.flush()
                currentTrack?.release()
            }
        }
    }

    fun configureAudio(info: IntArray) {
        releaseAudio()
        if (!soundEnabled) return
        val sampleRate = info.getOrElse(3) { 44100 }.coerceIn(8000, 192000)
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) return
        val frames = maxOf(2048, minBuffer / 2)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(frames * 8)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()
        audioTrack = track
        emuExecutor.execute {
            runCatching {
                PpssppRetroNative.setAudioTrack(track, frames, AudioTrack.WRITE_NON_BLOCKING)
            }
        }
    }

    fun startEmulationLoop(token: Int, targetFps: Int) {
        val fps = targetFps.coerceIn(1, 120)
        val frameBudgetNs = 1_000_000_000L / fps
        emuExecutor.execute {
            var frameCount = 0
            var lastFpsTime = System.currentTimeMillis()
            while (token == session.get() && emuActive.get()) {
                if (emuPaused.get()) {
                    try {
                        Thread.sleep(25L)
                    } catch (_: InterruptedException) {
                        break
                    }
                    continue
                }
                val startNs = System.nanoTime()
                val ok = runCatching { PpssppRetroNative.runFrame() }.getOrDefault(false)
                if (!ok) {
                    emuActive.set(false)
                    scope.launch(Dispatchers.Main) {
                        if (token == session.get()) {
                            running = false
                            Toast.makeText(context, "Game core stopped unexpectedly", Toast.LENGTH_SHORT).show()
                        }
                    }
                    break
                }
                frameCount++
                if (showFpsState.value) {
                    val now = System.currentTimeMillis()
                    if (now - lastFpsTime >= 1000L) {
                        val measured = frameCount
                        frameCount = 0
                        lastFpsTime = now
                        scope.launch(Dispatchers.Main) {
                            if (token == session.get()) fpsLabel = "$measured FPS"
                        }
                    }
                } else if (fpsLabel.isNotEmpty()) {
                    scope.launch(Dispatchers.Main) {
                        if (token == session.get()) fpsLabel = ""
                    }
                }
                val elapsedNs = System.nanoTime() - startNs
                val sleepMs = (frameBudgetNs - elapsedNs) / 1_000_000L
                if (sleepMs > 1L) {
                    try {
                        Thread.sleep(sleepMs.coerceAtMost(50L))
                    } catch (_: InterruptedException) {
                        break
                    }
                } else {
                    Thread.yield()
                }
            }
        }
    }

    fun startRom(rom: PpssppRom) {
        val token = session.incrementAndGet()
        emuActive.set(false)
        emuPaused.set(false)
        running = false
        paused = false
        loading = true
        gameMenuOpen = false
        lastInputHex = "0"
        releaseAudio()

        emuExecutor.execute {
            runCatching {
                PpssppRetroNative.setButtons(0L)
                PpssppRetroNative.setAnalog(0, 0)
            }
            val dirs = PpssppRomStore.dirs(context)
            runCatching {
                PpssppRetroNative.setPaths(
                    dirs.system.absolutePath,
                    dirs.saves.absolutePath,
                    dirs.roms.absolutePath
                )
                PpssppRetroNative.setOption("ppsspp_backend", "none")
                PpssppRetroNative.setOption("ppsspp_cpu_core", cpuCore)
                PpssppRetroNative.setOption("ppsspp_internal_resolution", resolution)
                PpssppRetroNative.setOption("ppsspp_frameskip", "disabled")
                PpssppRetroNative.setOption("ppsspp_auto_frameskip", "disabled")
                PpssppRetroNative.setOption("ppsspp_button_preference", "Cross")
                PpssppRetroNative.setOption("ppsspp_analog_is_circular", "enabled")
                PpssppRetroNative.setOption("ppsspp_software_rendering", "enabled")
                PpssppRetroNative.init()
            }
            val loaded = runCatching { PpssppRetroNative.loadRom(rom.path) }.getOrDefault(false)
            val info = if (loaded) {
                runCatching { PpssppRetroNative.avInfo() }.getOrDefault(intArrayOf(480, 272, 60, 44100))
            } else {
                intArrayOf(480, 272, 60, 44100)
            }
            val updated = if (loaded) {
                PpssppRomStore.touch(context, rom.id)
            } else {
                PpssppRomStore.load(context)
            }

            scope.launch(Dispatchers.Main) {
                if (token != session.get()) return@launch
                loading = false
                if (!loaded) {
                    Toast.makeText(context, "Could not load this game image", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                activeRom = rom
                roms = updated
                avInfo = info
                PpssppPrefs.setLastRom(context, rom.id)
                configureAudio(info)
                emuActive.set(true)
                emuPaused.set(false)
                running = true
                paused = false
                startEmulationLoop(token, info.getOrElse(2) { 60 })
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val imported = PpssppRomStore.import(context, uri)
            val updated = PpssppRomStore.load(context)
            withContext(Dispatchers.Main) {
                roms = updated
                if (imported == null) {
                    Toast.makeText(context, "Import failed. Use an ISO, CSO, PBP or CHD file.", Toast.LENGTH_SHORT).show()
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
        emuActive.set(false)
        emuPaused.set(false)
        running = false
        paused = false
        loading = false
        activeRom = null
        gameMenuOpen = false
        lastInputHex = "0"
        releaseAudio()
        applyGameplaySystemUi(false)

        emuExecutor.execute {
            if (token != session.get()) return@execute
            runCatching {
                PpssppRetroNative.setButtons(0L)
                PpssppRetroNative.setAnalog(0, 0)
            }
            if (current != null && saveSram && PpssppPrefs.autoSave(context)) {
                runCatching {
                    val savesDir = PpssppRomStore.dirs(context).saves
                    val sramPath = File(savesDir, "${current.id}.srm").absolutePath
                    val method = PpssppRetroNative.javaClass.methods.firstOrNull {
                        it.name == "saveSram" && it.parameterCount == 1
                    }
                    method?.invoke(PpssppRetroNative, sramPath)
                }
            }
            runCatching { PpssppRetroNative.unload() }
        }
    }

    fun saveState(slot: Int) {
        val rom = activeRom ?: return
        val wasPaused = emuPaused.get()
        emuPaused.set(true)
        emuExecutor.execute {
            val ok = runCatching {
                PpssppRetroNative.saveState(PpssppRomStore.stateFile(context, rom, slot).absolutePath)
            }.getOrDefault(false)
            emuPaused.set(wasPaused)
            scope.launch(Dispatchers.Main) {
                Toast.makeText(context, if (ok) "Saved state $slot" else "Save state failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun loadState(slot: Int) {
        val rom = activeRom ?: return
        emuPaused.set(true)
        emuExecutor.execute {
            val ok = runCatching {
                PpssppRetroNative.loadState(PpssppRomStore.stateFile(context, rom, slot).absolutePath)
            }.getOrDefault(false)
            emuPaused.set(!ok && paused)
            scope.launch(Dispatchers.Main) {
                if (ok) {
                    paused = false
                    emuPaused.set(false)
                    Toast.makeText(context, "Loaded state $slot", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Load state failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        roms = withContext(Dispatchers.IO) { PpssppRomStore.load(context) }
    }

    DisposableEffect(Unit) {
        onDispose {
            session.incrementAndGet()
            emuActive.set(false)
            emuPaused.set(false)
            releaseAudio()
            applyGameplaySystemUi(false)
            onGameplayChanged(false)
            emuExecutor.execute {
                runCatching {
                    PpssppRetroNative.setButtons(0L)
                    PpssppRetroNative.setAnalog(0, 0)
                    PpssppRetroNative.unload()
                }
            }
            emuExecutor.shutdown()
        }
    }

    val game = activeRom
    LaunchedEffect(game != null, orientationPref, immersiveMode) {
        onGameplayChanged(game != null)
        applyGameplaySystemUi(game != null)
    }

    val ratio = if (avInfo.size >= 2 && avInfo[0] > 0 && avInfo[1] > 0) {
        avInfo[0].toFloat() / avInfo[1].toFloat()
    } else {
        16f / 9f
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (game == null) {
            FloatingTopBar(useGlass = useGlass) {
                Text(
                    text = "PPSSPP Emulator",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = {
                    soundEnabled = !soundEnabled
                    PpssppPrefs.setSound(context, soundEnabled)
                }) {
                    Icon(
                        imageVector = if (soundEnabled) AppIcons.VolumeUp else AppIcons.VolumeOff,
                        contentDescription = if (soundEnabled) "Sound on" else "Sound off"
                    )
                }
                IconButton(onClick = { settingsOpen = true }) {
                    Icon(AppIcons.Settings, contentDescription = "Settings")
                }
                IconButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                    Icon(AppIcons.Add, contentDescription = "Import game")
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
                            Text("Import PSP game images (ISO, CSO, PBP or CHD)", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Tuned for Tecno Spark 40 (KM5) with default Landscape & Immersive mode. No PSP BIOS or games are bundled.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                            Spacer(Modifier.height(10.dp))
                            Button(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                                Icon(AppIcons.Add, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Import game")
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
                                    val ok = PpssppRomStore.delete(context, rom)
                                    val updated = PpssppRomStore.load(context)
                                    withContext(Dispatchers.Main) {
                                        roms = updated
                                        if (!ok) Toast.makeText(context, "Could not delete game", Toast.LENGTH_SHORT).show()
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
                val screenMaxWidth = maxWidth
                val screenMaxHeight = maxHeight
                val isLandscape = screenMaxWidth > screenMaxHeight
                val topInsetModifier = if (immersiveMode) Modifier else Modifier.statusBarsPadding()

                val viewportModifier = if (isLandscape) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier
                        .align(Alignment.TopCenter)
                        .then(topInsetModifier)
                        .padding(top = 48.dp, start = 4.dp, end = 4.dp)
                        .fillMaxWidth()
                        .fillMaxHeight(0.44f)
                }

                Box(
                    modifier = viewportModifier,
                    contentAlignment = Alignment.Center
                ) {
                    val availW = if (isLandscape) screenMaxWidth else (screenMaxWidth - 8.dp).coerceAtLeast(1.dp)
                    val availH = if (isLandscape) screenMaxHeight else (screenMaxHeight * 0.44f).coerceAtLeast(1.dp)
                    val width = minOf(availW, availH * ratio)
                    val height = width / ratio
                    Surface(
                        color = Color.Black,
                        shape = if (isLandscape) RectangleShape else MaterialTheme.shapes.medium,
                        modifier = Modifier.size(width, height)
                    ) {
                        AndroidView(
                            factory = { surfaceView },
                            update = { view ->
                                val valid = view.holder.surface?.takeIf { it.isValid }
                                if (valid != null) {
                                    runCatching { PpssppRetroNative.setSurface(valid) }
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
                        .padding(start = if (isLandscape) 26.dp else 10.dp, top = if (isLandscape) 10.dp else 8.dp)
                ) {
                    Text(
                        text = listOfNotNull(
                            game.name,
                            resolution,
                            if (showFps && fpsLabel.isNotBlank()) fpsLabel else null,
                            if (showFps && lastInputHex != "0") "PAD:$lastInputHex" else null
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
                            "Loading PSP game…",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                }

                PpssppGamepad(
                    alpha = controlAlpha,
                    scale = controlScale,
                    verticalOffset = controlOffset,
                    showAnalog = showAnalog,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(if (isLandscape) 1f else 0.56f),
                    onButtons = { mask ->
                        lastInputHex = java.lang.Long.toHexString(mask).uppercase()
                        emuExecutor.execute {
                            runCatching { PpssppRetroNative.setButtons(mask) }
                        }
                    },
                    onAnalog = { x, y ->
                        val sx = x.toInt().coerceIn(-32767, 32767).toShort()
                        val sy = y.toInt().coerceIn(-32767, 32767).toShort()
                        emuExecutor.execute {
                            runCatching { PpssppRetroNative.setAnalog(sx, sy) }
                        }
                    }
                )

                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .then(topInsetModifier)
                        .padding(end = if (isLandscape) 20.dp else 6.dp, top = if (isLandscape) 6.dp else 4.dp)
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
                            onClick = {
                                gameMenuOpen = false
                                val nextPaused = !paused
                                paused = nextPaused
                                emuPaused.set(nextPaused)
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(if (isLandscape) "Switch to Portrait" else "Switch to Landscape")
                            },
                            onClick = {
                                gameMenuOpen = false
                                val next = if (isLandscape) PpssppOrientation.PORTRAIT else PpssppOrientation.LANDSCAPE
                                orientationPref = next
                                PpssppPrefs.setOrientation(context, next)
                                applyGameplaySystemUi(true)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Reset game") },
                            onClick = {
                                gameMenuOpen = false
                                emuExecutor.execute {
                                    runCatching { PpssppRetroNative.reset() }
                                }
                            }
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
                            text = { Text("PSP settings") },
                            onClick = { gameMenuOpen = false; settingsOpen = true }
                        )
                        DropdownMenuItem(
                            text = { Text("Stop game") },
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
            title = { Text("PSP settings") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Screen orientation", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PpssppOrientation.entries.forEach { mode ->
                            FilterChip(
                                selected = orientationPref == mode,
                                onClick = {
                                    orientationPref = mode
                                    PpssppPrefs.setOrientation(context, mode)
                                    if (activeRom != null) applyGameplaySystemUi(true)
                                },
                                label = { Text(mode.label, maxLines = 1) }
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("CPU core", style = MaterialTheme.typography.bodySmall)
                    PpssppPrefs.CPU_CORES.forEach { value ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = {
                                cpuCore = value
                                PpssppPrefs.setCpuCore(context, value)
                                emuExecutor.execute {
                                    runCatching { PpssppRetroNative.setOption("ppsspp_cpu_core", value) }
                                }
                            }) { Text(if (cpuCore == value) "On" else "Set") }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("Internal resolution", style = MaterialTheme.typography.bodySmall)
                    PpssppPrefs.RESOLUTIONS.forEach { value ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = {
                                resolution = value
                                PpssppPrefs.setResolution(context, value)
                                emuExecutor.execute {
                                    runCatching { PpssppRetroNative.setOption("ppsspp_internal_resolution", value) }
                                }
                            }) { Text(if (resolution == value) "On" else "Set") }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("Immersive fullscreen", modifier = Modifier.weight(1f))
                        Switch(checked = immersiveMode, onCheckedChange = {
                            immersiveMode = it
                            PpssppPrefs.setImmersiveMode(context, it)
                            if (activeRom != null) applyGameplaySystemUi(true)
                        })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("On-screen analog stick", modifier = Modifier.weight(1f))
                        Switch(checked = showAnalog, onCheckedChange = {
                            showAnalog = it
                            PpssppPrefs.setShowAnalog(context, it)
                        })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("Show FPS", modifier = Modifier.weight(1f))
                        Switch(checked = showFps, onCheckedChange = {
                            showFps = it
                            PpssppPrefs.setShowFps(context, it)
                        })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("Sound", modifier = Modifier.weight(1f))
                        Switch(checked = soundEnabled, onCheckedChange = {
                            soundEnabled = it
                            PpssppPrefs.setSound(context, it)
                            if (activeRom != null) configureAudio(avInfo)
                        })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("Auto-save SRAM on stop", modifier = Modifier.weight(1f))
                        Switch(checked = autoSave, onCheckedChange = {
                            autoSave = it
                            PpssppPrefs.setAutoSave(context, it)
                        })
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Control opacity: ${(controlAlpha * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = controlAlpha,
                        onValueChange = {
                            controlAlpha = it
                            PpssppPrefs.setControlAlpha(context, it)
                        },
                        valueRange = 0.3f..1f
                    )
                    Text("Control size: ${(controlScale * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = controlScale,
                        onValueChange = {
                            controlScale = it
                            PpssppPrefs.setControlScale(context, it)
                        },
                        valueRange = 0.8f..1.4f
                    )
                    Text("Control height: ${controlOffset.value.toInt()} dp", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = controlOffset.value,
                        onValueChange = {
                            controlOffset = it.dp
                            PpssppPrefs.setControlOffset(context, it)
                        },
                        valueRange = -40f..40f
                    )
                    TextButton(onClick = {
                        PpssppPrefs.resetControls(context)
                        controlAlpha = 0.78f
                        controlScale = 1.0f
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
