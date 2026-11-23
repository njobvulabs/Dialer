package com.example.ui.screens.dialer

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.AppIcons
import com.example.ui.components.AppSearchField
import com.example.ui.components.landscapeContentWidth
import com.example.ui.theme.AppCornerRadius
import com.example.data.db.CallLogEntity
import com.example.data.db.CallType
import com.example.data.db.ContactEntity
import com.example.ui.MainViewModel
import com.example.ui.screens.contacts.ContactDetailsPanel
import com.example.util.ContactNumbers
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CallHistoryScreen(
    viewModel: MainViewModel,
    onMakeCall: (String, Int) -> Unit
) {
    val callLogs by viewModel.callLogs.collectAsState()
    val contacts by viewModel.allContacts.collectAsState()
    val speedDials by viewModel.speedDials.collectAsState()
    
    val favoriteContacts = remember(contacts, speedDials) {
        // Compare on normalized per-number keys: a speed dial holds ONE number
        // while a contact's field holds all of that person's numbers joined, so a
        // raw `phoneNumber in speedDialNumbers` string equality could never match
        // and the chip silently vanished.
        val speedDialKeys = speedDials
            .flatMap { ContactNumbers.keys(it.phoneNumber) }
            .toSet()
        contacts.filter { contact ->
            contact.isFavorite ||
                (speedDialKeys.isNotEmpty() &&
                    ContactNumbers.keys(contact.phoneNumber).any { it in speedDialKeys })
        }
    }
    val missedCount = remember(callLogs) { callLogs.count { it.callType == CallType.MISSED } }

    var selectedFilter by remember { mutableStateOf("All") }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    var showKeypadPopup by remember { mutableStateOf(false) }
    var dialInput by remember { mutableStateOf("") }
    var openedContact by remember { mutableStateOf<ContactEntity?>(null) }
    var logToDelete by remember { mutableStateOf<CallLogEntity?>(null) }
    /** Contact whose whole call history is pending a delete confirmation. */
    var groupToDelete by remember { mutableStateOf<CallGroup?>(null) }
    /** Group key whose individual call entries are currently expanded (one at a time). */
    var expandedGroupKey by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    // Device contacts come from the ViewModel (loaded on Dispatchers.IO and refreshed on
    // every ON_RESUME) rather than a cursor read during composition.
    val deviceContacts by viewModel.deviceContacts.collectAsState()

    // Resolve a call log entry into a ContactEntity. Prefers app contacts, then the
    // device address book, and finally a synthetic entry from the log itself. This now
    // drives BOTH the row's displayed name and the ContactDetailsPanel, so the two can
    // never disagree.
    fun resolveContact(log: CallLogEntity): ContactEntity {
        val logDigits = groupKeyOf(log.number)
        if (logDigits.length >= 3) {
            contacts.firstOrNull { c ->
                c.phoneNumbers().any { it == logDigits }
            }?.let { return it }
            // Device contacts carry ALL of a person's numbers joined, so this has
            // to test every key — feeding the joined field to `groupKeyOf`
            // normalises the whole blob and only ever matched their LAST number.
            deviceContacts.firstOrNull { dc ->
                ContactNumbers.keys(dc.phoneNumber).contains(logDigits)
            }?.let { dc ->
                return ContactEntity(id = -1L, name = dc.name, phoneNumber = dc.phoneNumber)
            }
        }
        return ContactEntity(
            id = -1L,
            name = log.callerName ?: log.number,
            phoneNumber = log.number
        )
    }

    // Resolved once per data change instead of per click / per render. Keeps the
    // NEWEST call's contact per group: a group can span calls whose cached
    // callerName differs, and the top row of a group is its newest call.
    val contactByGroup = remember(callLogs, contacts, deviceContacts) {
        val newestPerGroup = LinkedHashMap<String, CallLogEntity>()
        for (log in callLogs) {
            val key = groupKeyOf(log.number)
            val prev = newestPerGroup[key]
            if (prev == null || log.timestamp >= prev.timestamp) newestPerGroup[key] = log
        }
        newestPerGroup.mapValues { (_, log) -> resolveContact(log) }
    }

    fun nameOf(log: CallLogEntity): String = contactByGroup[groupKeyOf(log.number)]?.name
        ?: log.callerName
        ?: log.number

    // Back closes the contact panel, or collapses an expanded group first.
    BackHandler(enabled = openedContact != null || expandedGroupKey != null) {
        if (openedContact != null) openedContact = null else expandedGroupKey = null
    }

    val filteredLogs = remember(callLogs, selectedFilter, searchQuery, contactByGroup) {
        callLogs.filter { log ->
            val matchesFilter = when (selectedFilter) {
                "Incoming" -> log.callType == CallType.INCOMING
                "Outgoing" -> log.callType == CallType.OUTGOING
                "Missed" -> log.callType == CallType.MISSED
                "Spam" -> log.isSpam
                else -> true
            }
            val matchesSearch = if (searchQuery.isBlank()) {
                true
            } else {
                // Match the resolved contact name and the digits as typed, so
                // "991234567" finds "+265 991 234 567". The digit clause is
                // skipped when the query has no digits, otherwise every log would
                // match (any string contains the empty string).
                val digits = log.number.filter { it.isDigit() }
                val queryDigits = searchQuery.filter { it.isDigit() }
                log.number.contains(searchQuery, ignoreCase = true) ||
                        (log.callerName?.contains(searchQuery, ignoreCase = true) == true) ||
                        nameOf(log).contains(searchQuery, ignoreCase = true) ||
                        (queryDigits.isNotEmpty() && digits.contains(queryDigits))
            }
            matchesFilter && matchesSearch
        }
    }

    // Group the (filtered) logs by contact so one person is ONE row, however their
    // number happens to be spelled in each log. Grouping after filtering keeps the
    // All/Incoming/Outgoing/Missed/Spam chips meaning exactly what they say.
    val groups = remember(filteredLogs, contactByGroup) {
        val byKey = LinkedHashMap<String, CallGroup>()
        for (log in filteredLogs) {
            val key = groupKeyOf(log.number)
            byKey.getOrPut(key) {
                CallGroup(key = key, contact = contactByGroup[key] ?: resolveContact(log))
            }.entries.add(log)
        }
        byKey.values.map { g ->
            g.entries.sortByDescending { it.timestamp }
            g
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Top Action Bar / Search Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isSearchActive) {
                    AppSearchField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = "Search logs or numbers...",
                        onClose = { isSearchActive = false },
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Text(
                        text = "Call History",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { isSearchActive = true }) {
                            Icon(AppIcons.Search, contentDescription = "Search Call Logs")
                        }

                        if (callLogs.isNotEmpty()) {
                            IconButton(onClick = { showClearDialog = true }) {
                                Icon(
                                    AppIcons.DeleteSweep,
                                    contentDescription = "Clear History",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }

            // Speed Dial & Favorites Row
            if (favoriteContacts.isNotEmpty() && !isSearchActive) {
                Text(
                    text = "Speed Dial / Favorites",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    items(favoriteContacts) { contact ->
                        Surface(
                            onClick = { onMakeCall(ContactNumbers.primary(contact.phoneNumber), 1) },
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            tonalElevation = 2.dp
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = contact.name.take(1).uppercase(),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onPrimary
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Column {
                                    Text(
                                        text = contact.name,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = ContactNumbers.display(contact.phoneNumber),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Filter chips horizontal scroll row
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(listOf("All", "Incoming", "Outgoing", "Missed", "Spam")) { filter ->
                    FilterChip(
                        selected = selectedFilter == filter,
                        onClick = { selectedFilter = filter },
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(filter)
                                if (filter == "Missed" && missedCount > 0) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Badge(
                                        containerColor = MaterialTheme.colorScheme.error,
                                        contentColor = MaterialTheme.colorScheme.onError
                                    ) {
                                        Text("$missedCount")
                                    }
                                }
                            }
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (groups.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().landscapeContentWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = AppIcons.History,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (searchQuery.isNotEmpty()) "No matching calls found" else "No recent calls",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().landscapeContentWidth(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 80.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(groups, key = { it.key }) { group ->
                        // One row per CONTACT: the summary line is the most recent call
                        // for that person, and tapping the row expands their individual
                        // call entries underneath.
                        val isOpen = expandedGroupKey == group.key
                        val summary = group.latest
                        val ctx = LocalContext.current
                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = { v ->
                                when (v) {
                                    SwipeToDismissBoxValue.StartToEnd ->
                                        onMakeCall(summary.number, summary.simSlot)
                                    SwipeToDismissBoxValue.EndToStart -> runCatching {
                                        ctx.startActivity(
                                            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${summary.number}"))
                                        )
                                    }
                                    else -> {}
                                }
                                false
                            }
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            SwipeToDismissBox(
                                state = dismissState,
                                backgroundContent = {
                                    val toStart =
                                        dismissState.dismissDirection == SwipeToDismissBoxValue.StartToEnd
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(AppCornerRadius.md)
                                            .background(if (toStart) Color(0xFF16A34A) else Color(0xFF2563EB)),
                                        contentAlignment = if (toStart) Alignment.CenterStart else Alignment.CenterEnd
                                    ) {
                                        Icon(
                                            if (toStart) AppIcons.Call else AppIcons.Message,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.padding(horizontal = 22.dp)
                                        )
                                    }
                                }
                            ) {
                                CallLogRow(
                                    log = summary,
                                    displayName = group.contact.name,
                                    // The number, not a "N calls" tally: one row per
                                    // contact already implies the grouping, and the
                                    // number is what you actually read here. The count
                                    // still appears in the delete confirmation.
                                    subtitle = buildString {
                                        if (summary.number.isNotBlank()) append(summary.number)
                                        if (group.missedCount > 0) {
                                            if (isNotEmpty()) append(" • ")
                                            append("${group.missedCount} missed")
                                        }
                                    },
                                    onRowClick = { expandedGroupKey = if (isOpen) null else group.key },
                                    expanded = isOpen,
                                    onCallClick = { onMakeCall(summary.number, summary.simSlot) },
                                    onNameClick = { openedContact = group.contact },
                                    onDeleteClick = { groupToDelete = group },
                                    deleteMenuLabel = "Delete all calls",
                                    onBlockClick = { viewModel.blockNumber(summary.number) },
                                    onUpdateNote = { note -> viewModel.updateCallLogNote(summary.id, note) },
                                    onLongPress = { groupToDelete = group }
                                )
                            }

                            // Individual call entries. Rendered through the same row
                            // component so per-entry notes / block / delete still work.
                            if (isOpen) {
                                for (entry in group.entries.drop(1)) {
                                    CallLogRow(
                                        log = entry,
                                        displayName = group.contact.name,
                                        onCallClick = { onMakeCall(entry.number, entry.simSlot) },
                                        onNameClick = { openedContact = group.contact },
                                        onDeleteClick = { viewModel.deleteCallLog(entry.id) },
                                        onBlockClick = { viewModel.blockNumber(entry.number) },
                                        onUpdateNote = { note -> viewModel.updateCallLogNote(entry.id, note) },
                                        onLongPress = { logToDelete = entry },
                                        modifier = Modifier.padding(start = 22.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
                    }
        }

        // Floating Action Button to toggle Keypad Popup over Recents
        FloatingActionButton(
            onClick = { showKeypadPopup = true },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
        ) {
            Icon(AppIcons.Dialpad, contentDescription = "Open Keypad")
        }

        // Full-screen contact details panel, opened from tapping a caller name.
        openedContact?.let { contact ->
            ContactDetailsPanel(
                contact = contact,
                isDeviceContact = contact.id < 0,
                onBack = { openedContact = null },
                onCall = { number, slot -> onMakeCall(number, slot) },
                onMessage = { number ->
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(number)}"))
                        )
                    }.onFailure {
                        Toast.makeText(context, "No messaging app available", Toast.LENGTH_SHORT).show()
                    }
                },
                onShare = {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Contact: ${contact.name}")
                        putExtra(Intent.EXTRA_TEXT, "${contact.name}: ${contact.phoneNumber}")
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "Share Contact"))
                },
                onEdit = {
                    if (contact.id < 0) {
                        Toast.makeText(context, "Device contact - edit in system contacts app", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Edit this contact from the Contacts tab", Toast.LENGTH_SHORT).show()
                    }
                },
                onDelete = {
                    if (contact.id < 0) {
                        Toast.makeText(context, "Device contact - delete in system contacts app", Toast.LENGTH_SHORT).show()
                    } else {
                        viewModel.deleteContact(contact)
                        openedContact = null
                    }
                }
            )
        }
    }

    // Keypad Bottom Sheet
    if (showKeypadPopup) {
        val filteredMatchingLogs = remember(dialInput, callLogs) {
            if (dialInput.isEmpty()) emptyList()
            else callLogs.filter { it.number.contains(dialInput) || (it.callerName?.contains(dialInput, ignoreCase = true) == true) }.take(3)
        }

        ModalBottomSheet(
            onDismissRequest = {
                showKeypadPopup = false
                dialInput = ""
            },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Matching contacts / logs preview
                if (filteredMatchingLogs.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        filteredMatchingLogs.forEach { log ->
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = MaterialTheme.shapes.medium,
                                onClick = {
                                    onMakeCall(log.number, log.simSlot)
                                    showKeypadPopup = false
                                    dialInput = ""
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text(
                                            text = nameOf(log),
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                                        )
                                        Text(
                                            text = log.number,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Icon(AppIcons.Call, contentDescription = "Call", tint = Color(0xFF16A34A))
                                }
                            }
                        }
                    }
                }

                // Dial Input Field
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = dialInput.ifEmpty { "Enter phone number" },
                            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                            color = if (dialInput.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (dialInput.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .combinedClickable(
                                    onClick = { dialInput = dialInput.dropLast(1) },
                                    onLongClick = { dialInput = "" }
                                )
                                .padding(8.dp)
                        ) {
                            Icon(
                                AppIcons.Backspace,
                                contentDescription = "Backspace",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Keypad Buttons Grid 3x4 with T9 labels
                val keysData = listOf(
                    listOf("1" to "", "2" to "ABC", "3" to "DEF"),
                    listOf("4" to "GHI", "5" to "JKL", "6" to "MNO"),
                    listOf("7" to "PQRS", "8" to "TUV", "9" to "WXYZ"),
                    listOf("*" to "", "0" to "+", "#" to "")
                )

                keysData.forEach { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        row.forEach { (digit, sub) ->
                            Surface(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(CircleShape)
                                    .combinedClickable(
                                        onClick = { dialInput += digit },
                                        onLongClick = {
                                            if (digit == "0") dialInput += "+"
                                        }
                                    ),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                shape = CircleShape
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = digit,
                                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    if (sub.isNotEmpty()) {
                                        Text(
                                            text = sub,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // SIM 1 & SIM 2 Call Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Button(
                        onClick = {
                            if (dialInput.isNotEmpty()) {
                                onMakeCall(dialInput, 1)
                                showKeypadPopup = false
                                dialInput = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF16A34A),
                            disabledContainerColor = Color(0xFF16A34A).copy(alpha = 0.4f)
                        ),
                        enabled = dialInput.isNotEmpty(),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Icon(AppIcons.Call, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("SIM 1", fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = {
                            if (dialInput.isNotEmpty()) {
                                onMakeCall(dialInput, 2)
                                showKeypadPopup = false
                                dialInput = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF2563EB),
                            disabledContainerColor = Color(0xFF2563EB).copy(alpha = 0.4f)
                        ),
                        enabled = dialInput.isNotEmpty(),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Icon(AppIcons.Call, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("SIM 2", fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear Call History") },
            text = { Text("Delete all call logs? This also removes them from the phone's system call log and they won't be restored.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearAllCallLogs()
                    showClearDialog = false
                }) {
                    Text("Delete All", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    logToDelete?.let { log ->
        AlertDialog(
            onDismissRequest = { logToDelete = null },
            title = { Text("Delete Call Log") },
            text = { Text("Delete this log entry for ${log.callerName ?: log.number}?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteCallLog(log.id)
                    logToDelete = null
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { logToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    groupToDelete?.let { group ->
        AlertDialog(
            onDismissRequest = { groupToDelete = null },
            title = { Text("Delete Call History") },
            text = {
                Text(
                    "Delete ${group.totalCalls} " +
                        (if (group.totalCalls == 1) "call" else "calls") +
                        " with ${group.contact.name}? This also removes " +
                        "them from the phone's system call log and they won't be restored."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    // One deleteCallLog per entry, not a bulk Room delete: each call writes
                    // a SystemCallLogImporter tombstone, otherwise the whole history would
                    // be re-imported from the system log on the next ON_RESUME.
                    viewModel.deleteCallLogs(group.entries.map { it.id })
                    if (expandedGroupKey == group.key) expandedGroupKey = null
                    groupToDelete = null
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { groupToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * Grouping key meaning "same person": the last 10 digits, so `+265 991 234 567`,
 * `0991234567` and `265991234567` collapse into one history row. Short codes, `-1`
 * and `Unknown` can't be matched by digits, so they get a literal per-number key and
 * stay separate rows.
 */
private fun groupKeyOf(number: String): String {
    val digits = number.filter { it.isDigit() }.takeLast(10)
    return if (digits.length >= 3) digits else "raw:${number.trim()}"
}

/** An app contact can hold several comma-joined numbers (ContactTools.mergeGroup). */
private fun ContactEntity.phoneNumbers(): List<String> = ContactNumbers.keys(phoneNumber)

/** All call-log entries for one contact, newest first. */
private class CallGroup(
    val key: String,
    val contact: ContactEntity,
    val entries: MutableList<CallLogEntity> = mutableListOf()
) {
    /** The most recent call — the one the summary row shows. */
    val latest: CallLogEntity get() = entries.first()
    val totalCalls: Int get() = entries.size
    val missedCount: Int get() = entries.count { it.callType == CallType.MISSED }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CallLogRow(
    log: CallLogEntity,
    onCallClick: () -> Unit,
    onNameClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onBlockClick: () -> Unit,
    onUpdateNote: (String?) -> Unit,
    onLongPress: () -> Unit = {},
    modifier: Modifier = Modifier,
    /** Overrides the shown name (grouped rows pass the resolved contact name). */
    displayName: String? = null,
    /** Extra info appended to the meta line, e.g. "4 calls". */
    subtitle: String? = null,
    /** Row-body tap; a no-op by default so the name/call/menu targets stay distinct. */
    onRowClick: () -> Unit = {},
    /** Shows a trailing expand/collapse chevron and rotates it when expanded. */
    expanded: Boolean? = null,
    /** Grouped rows delete the whole contact, so the menu item is relabelled. */
    deleteMenuLabel: String = "Delete Log"
) {
    var expandedMenu by remember { mutableStateOf(false) }
    var showNoteDialog by remember { mutableStateOf(false) }
    var noteInput by remember { mutableStateOf(log.note ?: "") }
    val clipboardManager = LocalClipboardManager.current
    val dateFormat = remember { SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()) }
    val shownName = displayName ?: log.callerName ?: log.number

    val (icon, iconColor) = when (log.callType) {
        CallType.INCOMING -> AppIcons.CallReceived to Color(0xFF22C55E)
        CallType.OUTGOING -> AppIcons.CallMade to Color(0xFF3B82F6)
        CallType.MISSED -> AppIcons.CallMissed to Color(0xFFEF4444)
        CallType.REJECTED -> AppIcons.PhoneDisabled to Color(0xFFF59E0B)
    }

    val isPatternSpam = log.isSpam || log.number.startsWith("1800") || log.number.startsWith("0900") || log.number.startsWith("900") || log.number.startsWith("800")

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onRowClick,
                    onLongClick = onLongPress
                )
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = shownName,
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable(onClick = onNameClick)
                        )
                        if (isPatternSpam) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = MaterialTheme.shapes.extraSmall
                            ) {
                                Text(
                                    text = "SPAM SUSPECT",
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = dateFormat.format(Date(log.timestamp)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "• SIM ${log.simSlot}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (log.durationSeconds > 0) {
                            Text(
                                text = "• ${formatDuration(log.durationSeconds.toLong())}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (!subtitle.isNullOrBlank()) {
                            Text(
                                text = "• $subtitle",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                // Shrinks first so a long number can't push the
                                // SIM/duration chips off the row.
                                modifier = Modifier.weight(1f, fill = false),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                if (expanded != null) {
                    Icon(
                        imageVector = if (expanded) AppIcons.ExpandLess else AppIcons.ExpandMore,
                        contentDescription = if (expanded) "Collapse calls" else "Expand calls",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }

                IconButton(onClick = onCallClick) {
                    Icon(AppIcons.Call, contentDescription = "Call", tint = Color(0xFF16A34A))
                }

                Box {
                    IconButton(onClick = { expandedMenu = true }) {
                        Icon(AppIcons.MoreVert, contentDescription = "More Options")
                    }
                    DropdownMenu(
                        expanded = expandedMenu,
                        onDismissRequest = { expandedMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Copy Number") },
                            onClick = {
                                clipboardManager.setText(AnnotatedString(log.number))
                                expandedMenu = false
                            },
                            leadingIcon = { Icon(AppIcons.ContentCopy, contentDescription = null) }
                        )
                        DropdownMenuItem(
                            text = { Text(if (log.note.isNullOrBlank()) "Attach Call Note" else "Edit Call Note") },
                            onClick = {
                                noteInput = log.note ?: ""
                                showNoteDialog = true
                                expandedMenu = false
                            },
                            leadingIcon = { Icon(AppIcons.NoteAdd, contentDescription = null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Block Number") },
                            onClick = {
                                onBlockClick()
                                expandedMenu = false
                            },
                            leadingIcon = { Icon(AppIcons.Block, contentDescription = null) }
                        )
                        DropdownMenuItem(
                            text = { Text(deleteMenuLabel) },
                            onClick = {
                                onDeleteClick()
                                expandedMenu = false
                            },
                            leadingIcon = { Icon(AppIcons.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
                        )
                    }
                }
            }

            // Attached Call Note Preview
            if (!log.note.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = AppIcons.StickyNote2,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = log.note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    if (showNoteDialog) {
        AlertDialog(
            onDismissRequest = { showNoteDialog = false },
            title = { Text("Call Note") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Attach a quick note to this call entry ($shownName):")
                    OutlinedTextField(
                        value = noteInput,
                        onValueChange = { noteInput = it },
                        label = { Text("Note contents") },
                        placeholder = { Text("e.g. Discussed project quote, follow up on Tuesday") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 4
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    onUpdateNote(noteInput.ifBlank { null })
                    showNoteDialog = false
                }) {
                    Text("Save Note")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNoteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

private fun formatDuration(seconds: Long): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return if (mins > 0) "${mins}m ${secs}s" else "${secs}s"
}
