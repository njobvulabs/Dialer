package com.example.ui.screens.downloader

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.ui.components.landscapeContentWidth
import com.example.ui.components.AppIcons
import com.example.util.DlJob
import com.example.util.DlState
import com.example.util.DownloadBridge
import com.example.util.DownloadManager
import com.example.util.HistoryStore
import com.example.util.YtDlp
import com.example.util.humanBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun VideoDownloaderScreen(
    initialUrl: String?,
    onClose: () -> Unit,
    onOpenMedia: (File) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var url by remember {
        mutableStateOf(initialUrl ?: DownloadBridge.takePending() ?: "")
    }
    var initMsg by remember { mutableStateOf(YtDlp.lastInitError()) }
    var engineStatus by remember { mutableStateOf("") }
    var updatingEngine by remember { mutableStateOf(false) }
    var quality by remember { mutableStateOf("best") }
    var engineReady by remember { mutableStateOf(YtDlp.isReady()) }

    // Pick up jobs started in another process (a Gecko web app download runs in
    // :webapps and used to be invisible here), and keep re-reading the snapshot
    // while the screen is open so their progress updates too.
    DownloadManager.attach(context)
    val jobs = DownloadManager.jobs
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                DownloadManager.refreshFromSnapshot(context)
                kotlinx.coroutines.delay(2000)
            }
        }
    }
    var history by remember { mutableStateOf(HistoryStore.load(context)) }

    // Refresh history whenever a job lands.
    val doneCount = jobs.count { it.state == DlState.DONE }
    LaunchedEffect(doneCount) {
        if (doneCount > 0) history = HistoryStore.load(context)
    }

    val qualityOptions = listOf(
        "best" to "Best available",
        "1080" to "Up to 1080p",
        "720" to "Up to 720p",
        "480" to "Up to 480p",
        "audio" to "Audio only (M4A)"
    )

    fun refreshEngineStatus() {
        engineStatus = "Engine v${YtDlp.engineVersion(context)}"
    }

    fun runUpdateEngine(auto: Boolean) {
        if (!engineReady || updatingEngine) return
        updatingEngine = true
        engineStatus = if (auto) "Updating yt-dlp engine (first run)…" else "Checking for updates…"
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val err = YtDlp.updateEngine(context)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                updatingEngine = false
                if (err == null) {
                    refreshEngineStatus()
                    Toast.makeText(context, "yt-dlp updated", Toast.LENGTH_SHORT).show()
                } else {
                    engineStatus = "Update failed: ${err.take(60)}"
                    if (!auto) Toast.makeText(
                        context, "Update failed: ${err.take(90)}", Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // Init engine; auto-update it once per install so extractors stay current.
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            YtDlp.ensureInit(context)
        }
        initMsg = YtDlp.lastInitError()
        engineReady = YtDlp.isReady()
        if (engineReady) {
            refreshEngineStatus()
            if (YtDlp.needsAutoUpdate(context)) runUpdateEngine(auto = true)
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Spacer(Modifier.height(10.dp))

            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                placeholder = { Text("Paste video link (YouTube, Facebook, Instagram...)") },
                leadingIcon = { Icon(AppIcons.Download, contentDescription = null) },
                trailingIcon = {
                    if (url.isNotEmpty()) {
                        IconButton(onClick = { url = "" }) {
                            Icon(AppIcons.Clear, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                qualityOptions.forEach { (key, label) ->
                    FilterChip(
                        selected = quality == key,
                        onClick = { quality = key },
                        label = { Text(label, style = MaterialTheme.typography.labelMedium) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    if (url.isBlank()) {
                        Toast.makeText(context, "Enter a link first", Toast.LENGTH_SHORT).show()
                    } else if (!engineReady) {
                        Toast.makeText(context, "Engine still starting…", Toast.LENGTH_SHORT).show()
                    } else {
                        val queued = DownloadManager.jobs.size
                        DownloadManager.startDownload(context, url.trim(), quality)
                        if (DownloadManager.jobs.size == queued) {
                            Toast.makeText(
                                context, "Already in the download queue", Toast.LENGTH_SHORT
                            ).show()
                        }
                        url = ""
                    }
                },
                enabled = engineReady,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(AppIcons.Download, null, Modifier.size(18.dp))
                Text(if (quality == "audio") "  Download Audio" else "  Download Video")
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        initMsg != null -> "Engine error: $initMsg"
                        updatingEngine -> engineStatus
                        engineStatus.isNotEmpty() -> engineStatus
                        else -> ""
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (initMsg != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { runUpdateEngine(auto = false) }, enabled = engineReady && !updatingEngine) {
                    Icon(AppIcons.Refresh, null, Modifier.size(14.dp))
                    Text("  Update Engine")
                }
            }

            Spacer(Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier.fillMaxSize().landscapeContentWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 20.dp)
            ) {
                items(jobs.size) { idx ->
                    val j = jobs[idx]
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                            .padding(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                j.title, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            if (j.state == DlState.CANCELED || j.state == DlState.FAILED || j.state == DlState.DONE) {
                                IconButton(onClick = { DownloadManager.dismiss(j) }) {
                                    Icon(AppIcons.Close, "Dismiss", Modifier.size(16.dp))
                                }
                            }
                        }
                        Text(j.url, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { (j.progress.coerceIn(0f, 100f)) / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            when {
                                j.error != null && j.state == DlState.FAILED -> "Failed: ${j.error}"
                                j.file != null && j.state == DlState.DONE ->
                                    "Saved: ${File(j.file!!).name}" +
                                        (if (j.totalBytes > 0) " • ${humanBytes(j.totalBytes)}" else "")
                                j.state == DlState.PAUSED ->
                                    "Paused at ${j.progress.toInt()}%" +
                                        sizeSuffix(j) + " — Resume continues where it stopped"
                                j.state == DlState.CANCELED -> "Canceled"
                                else -> buildString {
                                    append("Downloading ${j.progress.toInt()}%")
                                    append(sizeSuffix(j))
                                    // Second stream / ffmpeg pass of a merged download.
                                    if (j.phase != null) append(" • ${j.phase}")
                                    if (j.etaSec > 0) append(" • ETA ${j.etaSec}s")
                                }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            when (j.state) {
                                DlState.DOWNLOADING, DlState.QUEUED -> {
                                    OutlinedButton(
                                        onClick = { DownloadManager.pause(j) },
                                        enabled = j.state == DlState.DOWNLOADING
                                    ) {
                                        Icon(AppIcons.Pause, null, Modifier.size(14.dp))
                                        Text(" Pause")
                                    }
                                    OutlinedButton(onClick = { DownloadManager.cancel(j) }) {
                                        Icon(AppIcons.Close, null, Modifier.size(14.dp))
                                        Text(" Cancel")
                                    }
                                }
                                DlState.PAUSED, DlState.FAILED -> {
                                    Button(onClick = { DownloadManager.resumeOrRetry(context, j) }) {
                                        Icon(AppIcons.PlayArrow, null, Modifier.size(14.dp))
                                        Text(if (j.state == DlState.FAILED) " Retry" else " Resume")
                                    }
                                    OutlinedButton(onClick = { DownloadManager.cancel(j) }) {
                                        Icon(AppIcons.Close, null, Modifier.size(14.dp))
                                        Text(" Cancel")
                                    }
                                }
                                DlState.DONE -> {
                                    Button(onClick = { j.file?.let { onOpenMedia(File(it)) } }) {
                                        Icon(AppIcons.PlayArrow, null, Modifier.size(14.dp))
                                        Text(" Play")
                                    }
                                }
                                DlState.CANCELED -> {}
                            }
                        }
                    }
                }

                items(history.size, key = { history[it].file }) { hIdx ->
                    val h = history[hIdx]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenMedia(File(h.file)) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(File(h.file).name, maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 13.sp)
                            Text(
                                java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault())
                                    .format(java.util.Date(h.ts)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { history = HistoryStore.remove(context, h.file) }) {
                            Icon(AppIcons.Close, "Remove from history", Modifier.size(16.dp))
                        }
                    }
                }

                if (jobs.isEmpty() && history.isEmpty()) {
                    item {
                        Text(
                            "Download videos from YouTube, Facebook, Instagram and other sites.\n\n" +
                                "Tips:\n" +
                                "• In any web app or the browser, tap the ⬇ icon or long-press a link → \"Download Video\".\n" +
                                "• Or share a link to NJ-Dialer from any app.\n\n" +
                                "Downloads keep running if you navigate away; resume them here anytime.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * " • 12.4 MB of 35.7 MB" when the total size is known. Uses the real
 * byte counter rather than totalBytes * progress because progress is clamped
 * (monotonic, capped at 99%) and would misreport the size mid-merge.
 */
private fun sizeSuffix(j: DlJob): String {
    if (j.totalBytes <= 0) return ""
    return " • ${humanBytes(j.doneBytes)} of ${humanBytes(j.totalBytes)}"
}
