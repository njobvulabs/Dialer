package com.example.ui.screens.calendar

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.db.CalendarEventEntity
import com.example.ui.MainViewModel
import com.example.ui.components.AppIcons
import com.example.ui.components.FloatingTopBar
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CalendarScreen(viewModel: MainViewModel) {
    val events by viewModel.upcomingCalendarEvents.collectAsState()
    val useGlassmorphism by viewModel.useGlassmorphism.collectAsState()
    var editorEvent by remember { mutableStateOf<CalendarEventEntity?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<CalendarEventEntity?>(null) }

    val dateState = rememberDatePickerState(initialSelectedDateMillis = System.currentTimeMillis())
    val selectedMillis = dateState.selectedDateMillis ?: System.currentTimeMillis()
    val dayFormat = remember { SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()) }
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }

    val dayEvents = remember(events, selectedMillis) {
        val start = dayStart(selectedMillis)
        val end = start + 86_400_000L
        events.filter { it.startAt in start until end }.sortedBy { it.startAt }
    }

    Scaffold(
        floatingActionButton = {
            if (!showEditor) {
                FloatingActionButton(onClick = { editorEvent = null; showEditor = true }) {
                    Icon(AppIcons.Add, "Add event")
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
            FloatingTopBar(useGlass = useGlassmorphism) {
                Icon(AppIcons.CalendarToday, null)
                Spacer(Modifier.width(10.dp))
                Text("Calendar", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }

            if (showEditor) {
                EventEditor(
                    initial = editorEvent,
                    selectedDateMillis = selectedMillis,
                    onCancel = { showEditor = false; editorEvent = null },
                    onSave = { event ->
                        if (editorEvent != null) viewModel.updateCalendarEvent(event) else viewModel.insertCalendarEvent(event)
                        showEditor = false; editorEvent = null
                    }
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 96.dp)
                ) {
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            shape = MaterialTheme.shapes.extraLarge
                        ) {
                            DatePicker(
                                state = dateState,
                                showModeToggle = false,
                                title = null,
                                headline = null
                            )
                        }
                    }
                    item {
                        Text(
                            text = dayFormat.format(Date(selectedMillis)),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    if (dayEvents.isEmpty()) {
                        item {
                            Text(
                                "No events for this day",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    } else {
                        items(dayEvents, key = { it.id }) { event ->
                            ElevatedCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 5.dp)
                                    .combinedClickable(
                                        onClick = { editorEvent = event; showEditor = true },
                                        onLongClick = { deleting = event }
                                    )
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(event.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                        Text(timeFormat.format(Date(event.startAt)), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                                        if (event.details.isNotBlank()) {
                                            Text(event.details, Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    IconButton(onClick = { editorEvent = event; showEditor = true }) { Icon(AppIcons.Edit, "Edit") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    deleting?.let { event ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete event?") },
            text = { Text(event.title) },
            confirmButton = { TextButton(onClick = { viewModel.deleteCalendarEvent(event); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EventEditor(
    initial: CalendarEventEntity?,
    selectedDateMillis: Long,
    onCancel: () -> Unit,
    onSave: (CalendarEventEntity) -> Unit
) {
    var title by remember(initial?.id) { mutableStateOf(initial?.title.orEmpty()) }
    var details by remember(initial?.id) { mutableStateOf(initial?.details.orEmpty()) }
    val dateState = rememberDatePickerState(initialSelectedDateMillis = initial?.startAt ?: selectedDateMillis)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                if (initial == null) "New event" else "Edit event",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
        item {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            OutlinedTextField(
                value = details,
                onValueChange = { details = it },
                label = { Text("Details (optional)") },
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge
            ) {
                DatePicker(
                    state = dateState,
                    showModeToggle = false,
                    title = null,
                    headline = null
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(
                    onClick = {
                        val chosen = dateState.selectedDateMillis ?: selectedDateMillis
                        val startCal = Calendar.getInstance().apply { timeInMillis = chosen }
                        if (initial != null) {
                            val src = Calendar.getInstance().apply { timeInMillis = initial.startAt }
                            startCal.set(Calendar.HOUR_OF_DAY, src.get(Calendar.HOUR_OF_DAY))
                            startCal.set(Calendar.MINUTE, src.get(Calendar.MINUTE))
                            startCal.set(Calendar.SECOND, 0)
                            startCal.set(Calendar.MILLISECOND, 0)
                        } else {
                            startCal.set(Calendar.HOUR_OF_DAY, 9)
                            startCal.set(Calendar.MINUTE, 0)
                            startCal.set(Calendar.SECOND, 0)
                            startCal.set(Calendar.MILLISECOND, 0)
                        }
                        val startAt = startCal.timeInMillis
                        val base = initial ?: CalendarEventEntity(title = "", details = "", startAt = 0, endAt = 0)
                        onSave(base.copy(title = title.trim(), details = details.trim(), startAt = startAt, endAt = startAt + 3_600_000L, reminderMinutes = 0))
                    },
                    enabled = title.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) { Text("Save") }
            }
        }
    }
}

private fun dayStart(millis: Long): Long = Calendar.getInstance().apply {
    timeInMillis = millis
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

