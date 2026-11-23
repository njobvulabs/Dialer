package com.example.ui.screens.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.service.AudioEngine
import com.example.service.AudioPlaybackService
import com.example.ui.components.AppIcons
import com.example.ui.components.AppSearchField
import com.example.ui.components.SearchIconButton
import com.example.ui.screens.hid.clipboardText
import com.example.ui.theme.AppCornerRadius
import com.example.util.AssistantActions
import com.example.util.AssistantConversation
import com.example.util.AssistantPrefs
import com.example.util.AssistantRecorderController
import com.example.util.AssistantSms
import com.example.util.AssistantSmsMessage
import com.example.util.AssistantTorchController
import com.example.util.ExpressionEval
import com.example.util.RecycleBin
import com.example.util.SmsTemplates
import com.example.ui.screens.sms.EXTRA_LOCAL_ID
import com.example.ui.screens.sms.SMS_DELIVERED_ACTION
import com.example.ui.screens.sms.SMS_RECEIVED_ACTION
import com.example.ui.screens.sms.SMS_SENT_ACTION
import java.io.File
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class AssistantTile(
    val id: String,
    val label: String,
    val icon: ImageVector
)

private val QUICK_TILES = listOf(
    AssistantTile("assistant_torch", "Flashlight", AppIcons.FlashlightOn),
    AssistantTile("calculator", "Calculator", AppIcons.Calculate),
    AssistantTile("assistant_camera", "Camera", AppIcons.PhotoCamera),
    AssistantTile("screenrecorder", "Screen Rec", AppIcons.FiberManualRecord),
    AssistantTile("data_connection", "Data", AppIcons.SignalCellularAlt),
    AssistantTile("sms", "SMS", AppIcons.Sms),
    AssistantTile("contacts", "Contacts", AppIcons.Person),
    AssistantTile("clipboard", "Snippets", AppIcons.ContentCopy)
)

@Composable
fun AssistantOverlayContent(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit
) {
    val context = LocalContext.current
    if (!expanded) {
        val bubbleDp = AssistantPrefs.bubbleDp(context)
        val iconDp = (bubbleDp * 0.52f).dp
        val fadeEnabled = AssistantPrefs.bubbleIdleFade(context)
        val idleDelay = AssistantPrefs.bubbleIdleDelayMs(context)
        val normalAlpha = AssistantPrefs.bubbleOpacity(context)
        val idleAlpha = AssistantPrefs.bubbleIdleOpacity(context)
        var idle by remember { mutableStateOf(false) }
        var interactionTick by remember { mutableIntStateOf(0) }

        LaunchedEffect(fadeEnabled, idleDelay, interactionTick) {
            idle = false
            if (!fadeEnabled) return@LaunchedEffect
            delay(idleDelay)
            idle = true
        }

        val bubbleAlpha by animateFloatAsState(
            targetValue = if (fadeEnabled && idle) idleAlpha else normalAlpha,
            animationSpec = tween(220),
            label = "assistant_bubble_alpha"
        )

        fun markInteraction() {
            interactionTick++
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape)
                .alpha(bubbleAlpha)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { markInteraction() },
                        onDragEnd = {
                            markInteraction()
                            onDragEnd()
                        },
                        onDragCancel = {
                            markInteraction()
                            onDragEnd()
                        }
                    ) { _, amount ->
                        markInteraction()
                        onDrag(amount.x, amount.y)
                    }
                }
                .clickable {
                    markInteraction()
                    onExpandedChange(true)
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = AppIcons.SmartToy,
                contentDescription = "Open assistant",
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(iconDp)
            )
        }
        return
    }

    var tab by remember { mutableIntStateOf(AssistantPrefs.defaultTab(context)) }
    var snippets by remember { mutableStateOf(AssistantPrefs.snippets(context)) }
    LaunchedEffect(tab) {
        snippets = AssistantPrefs.snippets(context)
    }
    Surface(
        shape = AppCornerRadius.xl,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(
            alpha = AssistantPrefs.panelOpacity(context)
        ),
        tonalElevation = 6.dp,
        modifier = Modifier
            .fillMaxSize()
            .padding(2.dp)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragEnd = { onDragEnd() },
                            onDragCancel = { onDragEnd() }
                        ) { _, amount -> onDrag(amount.x, amount.y) }
                    }
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = AppIcons.SmartToy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Assistant",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onOpenSettings, modifier = Modifier.size(34.dp)) {
                    Icon(AppIcons.Settings, contentDescription = "Assistant settings", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = { onExpandedChange(false) }, modifier = Modifier.size(34.dp)) {
                    Icon(AppIcons.ExpandLess, contentDescription = "Collapse", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onClose, modifier = Modifier.size(34.dp)) {
                    Icon(AppIcons.Close, contentDescription = "Close assistant", modifier = Modifier.size(18.dp))
                }
            }
            HorizontalDivider()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                listOf("Quick", "Media", "Notes", "SMS").forEachIndexed { index, label ->
                    Button(
                        onClick = { tab = index },
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (tab == index) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceContainer
                            },
                            contentColor = if (tab == index) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                    ) {
                        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                }
            }

            when (tab) {
                0 -> AssistantQuickTab(
                    snippets = snippets,
                    onOpenTab = { tab = it }
                )
                1 -> AssistantMediaTab()
                3 -> AssistantSmsTab()
                else -> AssistantNotesTab(
                    snippets = snippets,
                    onSnippetsChanged = { snippets = it },
                    onCollapse = { onExpandedChange(false) }
                )
            }
        }
    }
}

@Composable
private fun AssistantQuickTab(
    snippets: List<String>,
    onOpenTab: (Int) -> Unit
) {
    val context = LocalContext.current
    var contacts by remember { mutableStateOf<List<AssistantActions.Contact>>(emptyList()) }
    var showCalculator by remember { mutableStateOf(false) }
    var showCamera by remember { mutableStateOf(false) }
    var showScreenRecorder by remember { mutableStateOf(false) }
    var showData by remember { mutableStateOf(false) }
    val torchState by AssistantTorchController.status

    LaunchedEffect(Unit) {
        AssistantTorchController.refresh(context)
        contacts = AssistantActions.contacts(context)
    }

    val visibleTiles = QUICK_TILES.filter {
        AssistantPrefs.actionMode(context, it.id) != AssistantPrefs.ACTION_MODE_HIDDEN
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { SectionLabel("Quick actions") }
        items(visibleTiles.chunked(3)) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                row.forEach { tile ->
                    AssistantActionTile(
                        tile = tile,
                        modifier = Modifier.weight(1f),
                        subtitle = when (tile.id) {
                            "assistant_torch" -> torchState.message
                            else -> null
                        }
                    ) {
                        when (tile.id) {
                            "assistant_torch" -> AssistantTorchController.toggle(context)
                            "calculator" -> showCalculator = !showCalculator
                            "assistant_camera" -> showCamera = !showCamera
                            "screenrecorder" -> showScreenRecorder = !showScreenRecorder
                            "data_connection" -> showData = !showData
                            "sms" -> onOpenTab(3)
                            "clipboard" -> onOpenTab(2)
                            "contacts" -> Toast.makeText(context, "Choose a contact below", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

        if (showCalculator) {
            item { AssistantMiniCalculator() }
        }

        if (showCamera && AssistantPrefs.actionMode(context, "assistant_camera") != AssistantPrefs.ACTION_MODE_HIDDEN) {
            item { AssistantInlineCameraCard() }
        }

        if (showScreenRecorder && AssistantPrefs.actionMode(context, "screenrecorder") != AssistantPrefs.ACTION_MODE_HIDDEN) {
            item { AssistantScreenRecorderCard() }
        }

        if (showData && AssistantPrefs.actionMode(context, "data_connection") != AssistantPrefs.ACTION_MODE_HIDDEN) {
            item { AssistantDataCard() }
        }

        if (AssistantPrefs.actionMode(context, "clipboard") != AssistantPrefs.ACTION_MODE_HIDDEN) {
            item { SectionLabel("Saved snippets") }
            item {
                if (snippets.isEmpty()) {
                    Text(
                        "Save clipboard text from the Notes tab to reuse it here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    AssistantSnippetList(context, snippets.take(5), Modifier.heightIn(max = 180.dp))
                }
            }
        }

        if (AssistantPrefs.actionMode(context, "contacts") != AssistantPrefs.ACTION_MODE_HIDDEN) {
            item { SectionLabel("Contacts") }
            val visibleContacts = contacts.filter {
                (AssistantPrefs.showFavourites(context) && it.favorite) ||
                    (AssistantPrefs.showRecent(context) && !it.favorite)
            }
            if (visibleContacts.isEmpty()) {
                item {
                    Text(
                        "No favourites or recent contacts yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(visibleContacts) { contact ->
                    AssistantContactRow(context, contact)
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun AssistantMediaTab() {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        AssistantRecorderController.refresh(context)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { SectionLabel("Now playing") }
        item { AssistantAudioCard() }
        item { SectionLabel("Recorder") }
        item { AssistantMiniRecorderCard() }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun AssistantSmsTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var conversations by remember { mutableStateOf<List<AssistantConversation>>(emptyList()) }
    var messages by remember { mutableStateOf<List<AssistantSmsMessage>>(emptyList()) }
    var selected by remember { mutableStateOf<AssistantConversation?>(null) }
    val selectedThreadId = rememberUpdatedState(selected?.threadId)
    var address by remember { mutableStateOf("") }
    var newAddress by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var contactNames by remember { mutableStateOf(emptyMap<String, String>()) }
    var templates by remember { mutableStateOf(SmsTemplates.load(context)) }
    var showNew by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }

    suspend fun refreshConversations() {
        val result = AssistantSms.loadConversations(context)
        error = result.error
        conversations = result.data
    }

    suspend fun refreshThread(threadId: Long) {
        val result = AssistantSms.loadThread(context, threadId)
        error = result.error
        messages = result.data
    }

    fun openConversation(conversation: AssistantConversation) {
        selected = conversation
        address = conversation.address
        body = ""
        query = ""
        showSearch = false
        error = null
        scope.launch {
            AssistantSms.markThreadRead(context, conversation.threadId)
            refreshThread(conversation.threadId)
            refreshConversations()
        }
    }

    LaunchedEffect(Unit) {
        contactNames = AssistantSms.contactNameMap(context)
        templates = SmsTemplates.load(context)
        refreshConversations()
    }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    SMS_RECEIVED_ACTION -> scope.launch {
                        refreshConversations()
                        selectedThreadId.value?.let { refreshThread(it) }
                    }
                    SMS_SENT_ACTION, SMS_DELIVERED_ACTION -> {
                        val localId = intent.getLongExtra(EXTRA_LOCAL_ID, -1L)
                        if (localId <= 0) return
                        val status = AssistantSms.statusForResult(intent.action, resultCode)
                        scope.launch {
                            com.example.data.db.AppDatabase
                                .getDatabase(context.applicationContext)
                                .localSmsDao()
                                .updateStatus(localId, status)
                            refreshConversations()
                            selectedThreadId.value?.let { refreshThread(it) }
                        }
                    }
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter().apply {
                addAction(SMS_RECEIVED_ACTION)
                addAction(SMS_SENT_ACTION)
                addAction(SMS_DELIVERED_ACTION)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        var observer: ContentObserver? = null
        if (AssistantSms.hasReadSms(context)) {
            observer = object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) {
                    scope.launch {
                        delay(250)
                        refreshConversations()
                        selectedThreadId.value?.let { refreshThread(it) }
                    }
                }
            }
            runCatching {
                context.contentResolver.registerContentObserver(
                    Uri.parse("content://sms"),
                    true,
                    observer
                )
            }
        }
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
            observer?.let { runCatching { context.contentResolver.unregisterContentObserver(it) } }
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    LaunchedEffect(showSearch) {
        if (showSearch) runCatching { searchFocusRequester.requestFocus() }
    }

    val visibleConversations = conversations.filter { conversation ->
        if (query.isBlank()) true else {
            val name = AssistantSms.displayName(contactNames, conversation.address)
            name.contains(query, true) ||
                conversation.address.contains(query, true) ||
                conversation.body.contains(query, true)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selected != null) {
                IconButton(onClick = { selected = null }) {
                    Icon(AppIcons.ArrowBack, contentDescription = "Back to conversations", modifier = Modifier.size(18.dp))
                }
            }
            Text(
                text = selected?.let { AssistantSms.displayName(contactNames, it.address) } ?: "Messages",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 1
            )
            if (selected == null) {
                SearchIconButton(
                    isActive = showSearch || query.isNotEmpty(),
                    onClick = {
                        showSearch = !showSearch
                        if (!showSearch) query = ""
                    }
                )
            }
            IconButton(onClick = {
                refreshing = true
                scope.launch {
                    refreshConversations()
                    selectedThreadId.value?.let { refreshThread(it) }
                    refreshing = false
                }
            }) {
                Icon(AppIcons.Refresh, contentDescription = "Refresh messages", modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = { showNew = !showNew }) {
                Icon(AppIcons.Add, contentDescription = "New message", modifier = Modifier.size(18.dp))
            }
        }

        if (showSearch && selected == null) {
            AppSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Search messages",
                focusRequester = searchFocusRequester,
                onClose = {
                    query = ""
                    showSearch = false
                },
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (!AssistantSms.hasReadSms(context)) {
            AssistantSmsPermissionCard("Grant SMS access to read conversations.") {
                runCatching {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}")
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        }
        if (!AssistantSms.hasSendSms(context)) {
            AssistantSmsPermissionCard("Grant SMS access to send messages.") {
                runCatching {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}")
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        }
        error?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }

        if (showNew && selected == null) {
            OutlinedTextField(
                value = newAddress,
                onValueChange = { newAddress = it },
                label = { Text("Phone number") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    scope.launch {
                        val threadId = AssistantSms.resolveThreadIdForAddress(context, newAddress)
                        selected = AssistantConversation(
                            threadId = threadId,
                            address = newAddress.trim(),
                            body = "",
                            date = System.currentTimeMillis()
                        )
                        address = newAddress.trim()
                        body = ""
                        error = null
                        showNew = false
                        if (threadId >= 0) refreshThread(threadId)
                    }
                },
                enabled = newAddress.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Start message", style = MaterialTheme.typography.labelMedium) }
        }

        val conversation = selected
        if (conversation != null) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(messages) { message ->
                    val outgoing = message.type == 2 || message.isLocal
                    Box(Modifier.fillMaxWidth(), contentAlignment = if (outgoing) Alignment.CenterEnd else Alignment.CenterStart) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth(0.86f)
                                .clip(AppCornerRadius.md)
                                .background(
                                    if (outgoing) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainer
                                )
                                .padding(horizontal = 9.dp, vertical = 6.dp)
                        ) {
                            Text(message.body, style = MaterialTheme.typography.bodySmall)
                            val status = AssistantSms.statusLabel(message.status)
                            Text(
                                text = listOfNotNull(
                                    AssistantSms.formatTime(message.date),
                                    status.ifBlank { null }
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                label = { Text("Message") },
                maxLines = 3,
                modifier = Modifier.fillMaxWidth()
            )
            val segmentCount = if (body.isBlank()) 0 else AssistantSms.segments(context, body)
            if (segmentCount > 0) {
                Text(
                    "$segmentCount segment${if (segmentCount == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (templates.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(templates.take(6)) { template ->
                        AssistChip(onClick = { body = template }, label = {
                            Text(template.take(24), style = MaterialTheme.typography.labelSmall)
                        })
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = AssistantSms.displayName(contactNames, address),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1
                )
                IconButton(
                    onClick = {
                        scope.launch {
                            val sendError = AssistantSms.send(
                                context,
                                conversation.threadId,
                                address,
                                body,
                                conversation.simSlot
                            )
                            if (sendError != null) {
                                error = sendError
                            } else {
                                body = ""
                                val resolvedThreadId = AssistantSms.resolveThreadIdForAddress(context, address)
                                val effectiveThreadId = if (resolvedThreadId > 0L) resolvedThreadId else conversation.threadId
                                if (effectiveThreadId != conversation.threadId) {
                                    selected = conversation.copy(threadId = effectiveThreadId)
                                }
                                refreshThread(effectiveThreadId)
                                refreshConversations()
                            }
                        }
                    },
                    enabled = body.isNotBlank() && address.isNotBlank() && AssistantSms.hasSendSms(context)
                ) {
                    Icon(AppIcons.Send, contentDescription = "Send message", modifier = Modifier.size(20.dp))
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(visibleConversations) { conversation ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(AppCornerRadius.md)
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .clickable { openConversation(conversation) }
                            .padding(horizontal = 9.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                AssistantSms.displayName(contactNames, conversation.address),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1
                            )
                            Text(
                                conversation.body.ifBlank { "(no text)" },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                AssistantSms.formatDate(conversation.date),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (conversation.unreadCount > 0) {
                                Text(
                                    conversation.unreadCount.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
                if (visibleConversations.isEmpty()) {
                    item {
                        Text(
                            if (refreshing) "Loading messages…" else "No conversations yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantSmsPermissionCard(text: String, onOpen: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Row(
            modifier = Modifier.padding(9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onOpen) { Text("Open", style = MaterialTheme.typography.labelMedium) }
        }
    }
}

@Composable
private fun AssistantMiniRecorderCard() {
    val context = LocalContext.current
    val state by AssistantRecorderController.status
    var confirmDelete by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = AppIcons.Mic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(state.message, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    Text(
                        formatRecorderTime(state.elapsedMs),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (state.recording) {
                LinearProgressIndicator(
                    progress = { state.amplitude.coerceIn(0, 32_767) / 32_767f },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (!state.hasPermission) {
                TextButton(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:${context.packageName}")
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Open app permissions", style = MaterialTheme.typography.labelMedium) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Button(
                    onClick = {
                        if (state.recording) {
                            AssistantRecorderController.stop(context)
                        } else {
                            AssistantRecorderController.start(context)
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        if (state.recording) "Stop & save" else "Record",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                if (state.recording) {
                    OutlinedButton(
                        onClick = {
                            if (state.paused) AssistantRecorderController.resume()
                            else AssistantRecorderController.pause()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            if (state.paused) "Resume" else "Pause",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
            state.lastFile?.let { file ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        file.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { AssistantActions.copyText(context, file.absolutePath) }) {
                        Icon(AppIcons.ContentCopy, contentDescription = "Copy path", modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(AppIcons.Delete, contentDescription = "Delete recording", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete recording?") },
            text = { Text("This moves the recording to the Recycle Bin.") },
            confirmButton = {
                TextButton(onClick = {
                    state.lastFile?.let { file ->
                        val entry = RecycleBin.trash(context, file)
                        if (entry != null) AssistantRecorderController.clearLastFile()
                        else Toast.makeText(context, "Could not move recording", Toast.LENGTH_SHORT).show()
                    }
                    confirmDelete = false
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            }
        )
    }
}

private fun openMobileDataSettings(context: Context) {
    val candidates = listOf(
        Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY),
        Intent(Settings.ACTION_WIRELESS_SETTINGS)
    )
    for (intent in candidates) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
            return
        } catch (_: Exception) {
            continue
        }
    }
    Toast.makeText(context, "Data settings not available", Toast.LENGTH_SHORT).show()
}

private data class AssistantDataStatus(
    val mobileData: String,
    val network: String,
    val detail: String
)

@Composable
private fun AssistantDataCard() {
    val context = LocalContext.current
    var status by remember { mutableStateOf(readAssistantDataStatus(context)) }

    LaunchedEffect(Unit) {
        while (true) {
            status = readAssistantDataStatus(context)
            delay(3_000)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = AppIcons.SignalCellularAlt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(status.mobileData, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                Text(status.network, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                Text(
                    status.detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
            TextButton(onClick = { openMobileDataSettings(context) }) {
                Text("Change")
            }
        }
    }
}

private fun readAssistantDataStatus(context: android.content.Context): AssistantDataStatus {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val active = manager?.activeNetwork
    val capabilities = active?.let { manager?.getNetworkCapabilities(it) }
    val cellular = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
    val wifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    val internet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    val mobileData = runCatching {
        Settings.Global.getInt(context.contentResolver, "mobile_data", 1) == 1
    }.getOrDefault(true)
    val network = when {
        cellular -> "Active network: Mobile data"
        wifi -> "Active network: Wi-Fi"
        active != null -> "Active network: Other"
        else -> "Active network: None"
    }
    val detail = when {
        cellular && internet -> "Mobile data connected to the internet"
        cellular -> "Mobile data connected, internet limited"
        mobileData -> "Mobile data enabled but not active"
        else -> "Mobile data off. Tap Change to open Android settings."
    }
    return AssistantDataStatus(
        mobileData = if (mobileData) "Mobile data on" else "Mobile data off",
        network = network,
        detail = detail
    )
}

@Composable
private fun AssistantSnippetList(
    context: android.content.Context,
    snippets: List<String>,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        items(snippets) { snippet ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(AppCornerRadius.md)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .clickable { AssistantActions.copyText(context, snippet) }
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = snippet,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { AssistantActions.copyText(context, snippet) }) {
                    Icon(AppIcons.ContentCopy, contentDescription = "Copy snippet", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

private fun formatRecorderTime(ms: Long): String {
    val total = ms.coerceAtLeast(0) / 1_000
    return String.format(Locale.US, "%02d:%02d", total / 60, total % 60)
}

@Composable
private fun AssistantMiniCalculator() {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    val result = remember(input) { ExpressionEval.evaluateExpression(input) }
    val keyRows = listOf(
        listOf("7", "8", "9", "÷"),
        listOf("4", "5", "6", "×"),
        listOf("1", "2", "3", "-"),
        listOf("+", "0", ".", "%"),
        listOf("(", ")", "C", "⌫")
    )

    fun appendKey(key: String) {
        if (key == "C") {
            input = ""
            return
        }
        if (key == "⌫") {
            input = input.dropLast(1)
            return
        }
        val operators = setOf("+", "-", "×", "÷")
        if (input.isEmpty()) {
            if (key in operators || key == "%" || key == ")" || key == ".") return
            input = key
            return
        }
        if (input.last().toString() in operators && (key in operators || key == "%" || key == ")")) {
            input = input.dropLast(1) + key
            return
        }
        input += key
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("Expression") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = result ?: "0",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { result?.let { AssistantActions.copyText(context, it) } },
                    enabled = result != null
                ) {
                    Icon(AppIcons.ContentCopy, contentDescription = "Copy result", modifier = Modifier.size(18.dp))
                }
            }
            keyRows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    row.forEach { key ->
                        OutlinedButton(
                            onClick = { appendKey(key) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(0.dp)
                        ) { Text(key, style = MaterialTheme.typography.labelMedium) }
                    }
                }
            }
            Button(
                onClick = { result?.let { input = it } },
                modifier = Modifier.fillMaxWidth()
            ) { Text("=", style = MaterialTheme.typography.labelMedium) }
        }
    }
}

@Composable
private fun AssistantAudioCard() {
    val title by AudioEngine.title
    val artist by AudioEngine.artist
    val playing by AudioEngine.playing
    val active by AudioEngine.active
    val sleepDeadline by AudioEngine.sleepDeadline
    val sleepAtEnd by AudioEngine.sleepAtEndOfQueue
    val equalizerPreset by AudioEngine.equalizerPreset
    val equalizerBands by AudioEngine.equalizerBands
    val duration = AudioEngine.durationMs.value
    var position by remember { mutableLongStateOf(0L) }
    var sliderPosition by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var showExtras by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(AudioEngine.playbackSpeed) }
    var sleepTick by remember { mutableLongStateOf(0L) }

    LaunchedEffect(playing, active) {
        while (playing && active) {
            position = AudioEngine.positionMs()
            if (!dragging) sliderPosition = position
            delay(500)
        }
    }

    LaunchedEffect(showExtras, sleepDeadline, sleepAtEnd) {
        while (showExtras && (sleepDeadline > 0L || sleepAtEnd)) {
            sleepTick = System.currentTimeMillis()
            delay(15_000)
        }
    }

    LaunchedEffect(active, title) {
        AudioEngine.applyEqualizer()
    }

    val upNext = run {
        val current = AudioEngine.currentPath
        val index = if (current != null) {
            AudioEngine.queue.indexOf(current)
        } else {
            AudioEngine.queueIndex
        }
        if (index >= 0) {
            AudioEngine.queue.drop(index + 1).take(3).map { path ->
                runCatching { File(path).nameWithoutExtension }.getOrDefault(path.substringAfterLast('/'))
            }
        } else {
            emptyList()
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (active) title.ifBlank { "Now playing" } else "No track playing",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1
                    )
                    Text(
                        text = if (active) artist else "Start music from the audio player",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                IconButton(onClick = { showExtras = !showExtras }) {
                    Icon(
                        imageVector = if (showExtras) AppIcons.ExpandLess else AppIcons.ExpandMore,
                        contentDescription = if (showExtras) "Hide music options" else "Show music options",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Slider(
                value = sliderPosition.toFloat().coerceAtMost(duration.coerceAtLeast(1).toFloat()),
                onValueChange = {
                    dragging = true
                    sliderPosition = it.toLong()
                },
                onValueChangeFinished = {
                    AudioEngine.seekTo(sliderPosition)
                    dragging = false
                },
                enabled = active && duration > 0,
                valueRange = 0f..duration.coerceAtLeast(1).toFloat()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AudioButton("Prev", AppIcons.SkipPrevious, AudioPlaybackService.ACTION_PREV)
                AudioButton(if (playing) "Pause" else "Play", AppIcons.PlayArrow, AudioPlaybackService.ACTION_TOGGLE)
                AudioButton("Next", AppIcons.SkipNext, AudioPlaybackService.ACTION_NEXT)
                AudioButton("Stop", AppIcons.Stop, AudioPlaybackService.ACTION_STOP)
            }
            if (showExtras) {
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = AppIcons.AccessTime,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        assistantSleepLabel(sleepDeadline, sleepAtEnd, sleepTick),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(15, 30, 60).forEach { minutes ->
                        TextButton(
                            onClick = { AudioEngine.startSleepTimer(minutes) },
                            modifier = Modifier.height(30.dp),
                            contentPadding = PaddingValues(horizontal = 6.dp)
                        ) { Text("${minutes}m", style = MaterialTheme.typography.labelSmall) }
                    }
                    TextButton(
                        onClick = { AudioEngine.startSleepAtEndOfQueue() },
                        modifier = Modifier.height(30.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp)
                    ) { Text("End", style = MaterialTheme.typography.labelSmall) }
                    TextButton(
                        onClick = { AudioEngine.cancelSleepTimer() },
                        modifier = Modifier.height(30.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp)
                    ) { Text("Off", style = MaterialTheme.typography.labelSmall) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = AppIcons.Speed,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Speed", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            val options = listOf(1f, 1.25f, 1.5f, 2f, 0.8f)
                            val next = options.firstOrNull { it > speed + 0.01f } ?: options.first()
                            AudioEngine.setSpeed(next)
                            speed = next
                        },
                        contentPadding = PaddingValues(horizontal = 6.dp)
                    ) { Text(String.format(Locale.US, "%.2fx", speed), style = MaterialTheme.typography.labelSmall) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = AppIcons.Equalizer,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Equalizer", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            val presets = listOf("Flat", "Bass Boost", "Vocal Booster", "Rock", "Pop", "Jazz", "Classical")
                            val index = presets.indexOf(equalizerPreset)
                            AudioEngine.setEqualizerPreset(presets[(index + 1) % presets.size])
                        },
                        contentPadding = PaddingValues(horizontal = 6.dp)
                    ) {
                        Text(equalizerPreset, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val labels = listOf("60Hz", "230Hz", "910Hz", "3.6k", "14k")
                    equalizerBands.forEachIndexed { index, value ->
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(labels[index], style = MaterialTheme.typography.labelSmall)
                            Slider(
                                value = value,
                                onValueChange = { AudioEngine.setEqualizerBand(index, it) },
                                valueRange = 0f..1f,
                                modifier = Modifier.height(24.dp)
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = AppIcons.QueueMusic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    if (upNext.isEmpty()) {
                        Text(
                            "Up next: no queued tracks",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Up next", style = MaterialTheme.typography.labelSmall)
                            upNext.forEach { name ->
                                Text(
                                    "• $name",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun assistantSleepLabel(deadline: Long, atEnd: Boolean, now: Long): String = when {
    atEnd -> "Sleep at end of queue"
    deadline <= 0L -> "Sleep timer off"
    else -> {
        val minutes = ((deadline - now + 59_999L) / 60_000L).coerceAtLeast(0)
        "Sleep in $minutes min"
    }
}

@Composable
private fun AssistantContactRow(
    context: android.content.Context,
    contact: AssistantActions.Contact
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppCornerRadius.md)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (contact.favorite) AppIcons.Star else AppIcons.Person,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(contact.name, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            Text(
                contact.number,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        IconButton(
            onClick = { AssistantActions.copyText(context, contact.number) },
            modifier = Modifier.size(34.dp)
        ) {
            Icon(AppIcons.ContentCopy, contentDescription = "Copy number", modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun AssistantNotesTab(
    snippets: List<String>,
    onSnippetsChanged: (List<String>) -> Unit,
    onCollapse: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("Title") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = body,
            onValueChange = { body = it },
            label = { Text("Quick note") },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Button(
                onClick = {
                    if (body.isBlank()) return@Button
                    scope.launch {
                        AssistantActions.addNote(context, title, body)
                        title = ""
                        body = ""
                        Toast.makeText(context, "Saved to Notes", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Save note", style = MaterialTheme.typography.labelMedium)
            }
            OutlinedButton(
                onClick = {
                    onCollapse()
                    AssistantActions.open(context, "notes")
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Open Notes", style = MaterialTheme.typography.labelMedium)
            }
        }

        SectionLabel("Clipboard snippets")
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            OutlinedButton(
                onClick = {
                    val text = clipboardText(context)
                    if (text.isNullOrBlank()) {
                        Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                    } else {
                        AssistantPrefs.addSnippet(context, text)
                        onSnippetsChanged(AssistantPrefs.snippets(context))
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Save clipboard", style = MaterialTheme.typography.labelMedium)
            }
            OutlinedButton(
                onClick = {
                    AssistantPrefs.clearSnippets(context)
                    onSnippetsChanged(emptyList())
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Clear", style = MaterialTheme.typography.labelMedium)
            }
        }
        AssistantSnippetList(
            context = context,
            snippets = snippets,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun AssistantActionTile(
    tile: AssistantTile,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .height(if (subtitle == null) 68.dp else 76.dp)
            .clip(AppCornerRadius.md)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = tile.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = tile.label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun RowScope.AudioButton(label: String, icon: ImageVector, action: String) {
    val context = LocalContext.current
    OutlinedButton(
        onClick = { AssistantActions.audio(context, action) },
        modifier = Modifier.weight(1f),
        contentPadding = PaddingValues(horizontal = 2.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(3.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}
