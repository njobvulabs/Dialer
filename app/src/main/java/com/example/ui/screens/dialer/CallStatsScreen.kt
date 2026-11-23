package com.example.ui.screens.dialer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AppCornerRadius
import com.example.data.db.CallLogEntity
import com.example.data.db.CallType
import java.util.Calendar

private fun fmtDuration(totalSeconds: Int): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m"
        else -> "$totalSeconds s"
    }
}

@Composable
fun CallStatsScreen(
    logs: List<CallLogEntity>,
    onMakeCall: (String, Int) -> Unit,
    onClose: () -> Unit
) {
    val cal = Calendar.getInstance()
    fun monthKey(ts: Long): String {
        cal.timeInMillis = ts
        return "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH) + 1}"
    }
    fun weekKey(ts: Long): Long = ts / (7L * 24 * 3600 * 1000)

    val nowMonth = monthKey(System.currentTimeMillis())
    val months = remember(logs) {
        val map = linkedMapOf<String, Pair<Int, Int>>() // key to (count, seconds)
        for (i in 5 downTo 0) {
            cal.timeInMillis = System.currentTimeMillis()
            cal.add(Calendar.MONTH, -i)
            map["${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH) + 1}"] = 0 to 0
        }
        logs.forEach { l ->
            val k = monthKey(l.timestamp)
            if (map.containsKey(k)) {
                val c = map[k]!!
                map[k] = c.first + 1 to c.second + l.durationSeconds
            }
        }
        map
    }

    val weeks = remember(logs) {
        val thisWeek = weekKey(System.currentTimeMillis())
        val map = linkedMapOf<Long, Int>()
        for (i in 7 downTo 0) map[thisWeek - i] = 0
        logs.forEach { l ->
            val k = weekKey(l.timestamp)
            if (map.containsKey(k)) map[k] = map[k]!! + 1
        }
        map
    }

    val byType = remember(logs) {
        logs.groupingBy { it.callType }.eachCount()
    }
    val totalTalk = remember(logs) { logs.sumOf { it.durationSeconds } }
    val topContacts = remember(logs) {
        val acc = mutableMapOf<Triple<String, String, Int>, Pair<Int, Int>>()
        logs.forEach { l ->
            if (l.durationSeconds > 0) {
                val k = Triple(l.number, l.callerName ?: "", l.simSlot)
                val cur = acc[k] ?: (0 to 0)
                acc[k] = (cur.first + l.durationSeconds) to (cur.second + 1)
            }
        }
        acc.entries.sortedByDescending { it.value.first }.take(8).toList()
    }
    val simSplit = remember(logs) {
        logs.groupingBy { it.simSlot }.eachCount()
    }

    BackHandler(onBack = onClose)

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text("Call Statistics", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("Total Calls", "${logs.size}", Modifier.weight(1f))
                StatCard("Talk Time", fmtDuration(totalTalk), Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("Incoming", "${byType[CallType.INCOMING] ?: 0}", Modifier.weight(1f))
                StatCard("Outgoing", "${byType[CallType.OUTGOING] ?: 0}", Modifier.weight(1f))
                StatCard("Missed", "${byType[CallType.MISSED] ?: 0}", Modifier.weight(1f))
            }
            Spacer(Modifier.height(18.dp))

            Text("Last 6 Months", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            BarChart(
                values = months.values.map { it.second / 60f },
                labels = months.keys.map { it.substringAfter('-') },
                barColor = MaterialTheme.colorScheme.primary
            )
            Text("minutes per month", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(18.dp))

            Text("Last 8 Weeks", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            BarChart(
                values = weeks.values.map { it.toFloat() },
                labels = weeks.keys.map { "W" },
                barColor = MaterialTheme.colorScheme.tertiary
            )
            Text("calls per week", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(18.dp))

            if (simSplit.size > 1) {
                Text("Per SIM", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                simSplit.toSortedMap().forEach { (sim, count) ->
                    Text("SIM $sim: $count calls",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(18.dp))
            }

            Text("Top Contacts (talk time)", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            if (topContacts.isEmpty()) {
                Text("No calls with duration yet",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium)
            }
            topContacts.forEach { entry ->
                val (number, name, sim) = entry.key
                val (secs, count) = entry.value
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(name.ifBlank { number }, fontWeight = FontWeight.Medium,
                            maxLines = 1)
                        Text("$number • SIM $sim • $count calls",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1)
                    }
                    TextButton(onClick = { onMakeCall(number, sim) }) { Text("Call") }
                    Text(fmtDuration(secs), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(AppCornerRadius.md)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(12.dp)
    ) {
        Text(value, style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BarChart(values: List<Float>, labels: List<String>, barColor: Color) {
    val maxV = (values.maxOrNull() ?: 0f).coerceAtLeast(1f)
    Column {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
        ) {
            val n = values.size.coerceAtLeast(1)
            val gap = 12.dp.toPx()
            val barW = (size.width - gap * (n - 1)) / n
            values.forEachIndexed { i, v ->
                val h = (v / maxV) * size.height * 0.92f
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(i * (barW + gap), size.height - h),
                    size = Size(barW, h),
                    cornerRadius = CornerRadius(8f, 8f)
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            labels.forEach {
                Text(
                    it,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
