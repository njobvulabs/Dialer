package com.example.ui.screens.tools

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.MainViewModel
import com.example.ui.components.FloatingTopBar
import com.example.ui.components.landscapeContentWidth
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun TimerStopwatchScreen(
    viewModel: MainViewModel? = null,
    onBack: () -> Unit = {}
) {
    val useGlass = viewModel?.useGlassmorphism?.collectAsState()?.value ?: false
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var swRunning by rememberSaveable { mutableStateOf(false) }
    var swBaseElapsed by rememberSaveable { mutableLongStateOf(0L) }
    var swStartedAt by rememberSaveable { mutableLongStateOf(0L) }
    var swNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val laps = remember { mutableStateListOf<Long>() }

    var timerRunning by rememberSaveable { mutableStateOf(false) }
    var timerTotalMs by rememberSaveable { mutableLongStateOf(5L * 60_000L) }
    var timerRemainingMs by rememberSaveable { mutableLongStateOf(5L * 60_000L) }
    var timerLastTick by rememberSaveable { mutableLongStateOf(0L) }

    LaunchedEffect(swRunning) {
        while (swRunning) {
            swNow = System.currentTimeMillis()
            delay(50)
        }
    }
    LaunchedEffect(timerRunning) {
        while (timerRunning && timerRemainingMs > 0L) {
            val now = System.currentTimeMillis()
            val delta = if (timerLastTick == 0L) 0L else (now - timerLastTick).coerceAtLeast(0L)
            timerLastTick = now
            timerRemainingMs = (timerRemainingMs - delta).coerceAtLeast(0L)
            if (timerRemainingMs == 0L) timerRunning = false
            delay(200)
        }
    }

    val swElapsed = swBaseElapsed + if (swRunning) (swNow - swStartedAt).coerceAtLeast(0L) else 0L

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            FloatingTopBar(useGlass = useGlass) {
                Text("Stopwatch & Timer", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 16.dp)
                .landscapeContentWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                SegmentedButton(selected = tab == 0, onClick = { tab = 0 }, shape = SegmentedButtonDefaults.itemShape(0, 2), label = { Text("Stopwatch") })
                SegmentedButton(selected = tab == 1, onClick = { tab = 1 }, shape = SegmentedButtonDefaults.itemShape(1, 2), label = { Text("Timer") })
            }
            Spacer(Modifier.height(20.dp))
            if (tab == 0) {
                Text(formatElapsed(swElapsed), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(onClick = {
                        if (swRunning) {
                            swBaseElapsed = swElapsed
                            swRunning = false
                        } else {
                            swStartedAt = System.currentTimeMillis()
                            swNow = swStartedAt
                            swRunning = true
                        }
                    }) { Text(if (swRunning) "Pause" else if (swBaseElapsed > 0L) "Resume" else "Start") }
                    OutlinedButton(enabled = swElapsed > 0L, onClick = { laps.add(0, swElapsed) }) { Text("Lap") }
                    OutlinedButton(onClick = {
                        swRunning = false; swBaseElapsed = 0L; swStartedAt = 0L; laps.clear()
                    }) { Text("Reset") }
                }
                Spacer(Modifier.height(16.dp))
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(laps) { index, ms ->
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), shape = MaterialTheme.shapes.medium) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Lap ${laps.size - index}", fontWeight = FontWeight.Medium)
                                Text(formatElapsed(ms), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            } else {
                Text(formatElapsed(timerRemainingMs), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
                if (!timerRunning && timerRemainingMs == 0L) {
                    Text("Time's up", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf(1, 3, 5, 10).forEach { min ->
                        FilterChip(selected = timerTotalMs == min * 60_000L && !timerRunning, onClick = {
                            timerRunning = false; timerTotalMs = min * 60_000L; timerRemainingMs = timerTotalMs; timerLastTick = 0L
                        }, label = { Text("${min}m") }, shape = MaterialTheme.shapes.extraLarge)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(enabled = timerRemainingMs > 0L, onClick = {
                        if (timerRunning) { timerRunning = false } else { timerLastTick = System.currentTimeMillis(); timerRunning = true }
                    }) { Text(if (timerRunning) "Pause" else "Start") }
                    OutlinedButton(onClick = {
                        timerRunning = false; timerRemainingMs = timerTotalMs; timerLastTick = 0L
                    }) { Text("Reset") }
                }
                Spacer(Modifier.height(10.dp))
                Text("Timer runs while this screen is open.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun formatElapsed(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    val cs = (ms % 1000) / 10
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d.%02d", h, m, s, cs)
    else String.format(Locale.US, "%02d:%02d.%02d", m, s, cs)
}
