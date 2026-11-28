package com.example.ui.screens.sms

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.Settings
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.data.db.LocalSmsEntity
import com.example.service.SmsNotificationListener
import com.example.ui.MainViewModel
import com.example.ui.components.AppIcons
import com.example.util.SmsTemplates
import com.example.ui.components.AppSearchField
import com.example.ui.components.FloatingTopBar
import com.example.ui.components.landscapeContentWidth
import com.example.util.DeviceContacts
import com.example.util.SmsConversationDeletes
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class Conversation(
    val threadId: Long,
    val address: String,
    val body: String,
    val date: Long,
    val read: Int,
    val simSlot: Int,
    val unreadCount: Int = 0
)

private data class SmsMessage(
    val id: Long,
    val address: String,
    val body: String,
    val date: Long,
    val type: Int,
    val simSlot: Int,
    val status: String? = null, // only for local sent messages
    val isLocal: Boolean = false
)

const val SMS_RECEIVED_ACTION = "com.example.SMS_RECEIVED"
const val SMS_SENT_ACTION = "com.example.SMS_SENT"
const val SMS_DELIVERED_ACTION = "com.example.SMS_DELIVERED"
const val EXTRA_LOCAL_ID = "com.example.EXTRA_LOCAL_ID"
const val SMS_INCOMING_NOTIFICATION_ID = 2002

/** Provider `type` values that mean "this row is an outgoing message":
 *  `Telephony.Sms.TYPE_SENT`=2, `TYPE_OUTBOX`=4, `TYPE_FAILED`=5,
 *  `TYPE_QUEUED`=6. Not every ROM records an outgoing row as exactly 2, so the
 *  Room/provider merge must never test `type == 2` on its own. */
private val OUTGOING_SMS_TYPES = setOf(2, 4, 5, 6)

/** How long after a send the OS may still persist its own copy of the row.
 *
 *  The previous matcher compared `date / 2000L`, which is an *epoch-aligned
 *  2-second bucket*, not a window: it only matched when both timestamps landed
 *  in the same bucket, so the effective tolerance oscillated between 0 ms and
 *  2000 ms and was 0 for anything written more than 2s later. Because the Room
 *  echo is never deleted and every reload re-ran the identical deterministic
 *  comparison, one miss was permanent — the twin reappeared on every
 *  ON_RESUME, re-entry and restart. A minute comfortably covers RIL/send-report
 *  latency while staying far away from the next message in the thread. */
private const val DEDUP_WINDOW_MS = 60_000L

/** Tolerance in the other direction. The OS row should never predate our echo
 *  (we insert the echo *before* calling `SmsManager`), so allow a little clock
 *  skew but not more — this also stops an echo from claiming an older,
 *  unrelated outgoing row in the same thread. */
private const val DEDUP_SKEW_MS = 5_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmsScreen(
    viewModel: MainViewModel,
    onUnreadCount: (Int) -> Unit = {},
    initialNumber: String = "",
    onInitialNumberConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var currentThreadId by remember { mutableStateOf<Long?>(null) }
    var composingNew by remember { mutableStateOf(initialNumber.isNotBlank()) }
    var smsPrefill by remember { mutableStateOf(initialNumber) }
    val refreshKey = remember { mutableStateOf(0) }
    var localStatuses by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }

    LaunchedEffect(initialNumber) {
        if (initialNumber.isNotBlank()) {
            smsPrefill = initialNumber
            composingNew = true
        }
    }

    // Refresh on our SMS_RECEIVED broadcast (posted by SmsNotificationListener) and on
    // the SENT / DELIVERED broadcasts from sendMessage's PendingIntents.
    val receiver = remember {
        object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent?) {
                when (intent?.action) {
                    SMS_RECEIVED_ACTION -> {
                        refreshKey.value++
                    }
                    SMS_SENT_ACTION, SMS_DELIVERED_ACTION -> {
                        val localId = intent.getLongExtra(EXTRA_LOCAL_ID, -1L)
                        if (localId <= 0) return
                        val isDelivered = intent.action == SMS_DELIVERED_ACTION &&
                            resultCode == Activity.RESULT_OK
                        val status = if (isDelivered) "DELIVERED"
                            else if (resultCode == Activity.RESULT_OK) "SENT"
                            else "FAILED"
                        localStatuses = localStatuses + (localId to status)
                        viewModel.updateLocalSmsStatus(localId, status)
                        if (status == "FAILED") {
                            Toast.makeText(
                                context,
                                "SMS not sent: ${smsResultText(resultCode)}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        refreshKey.value++
                    }
                }
            }
        }
    }
    DisposableEffect(Unit) {
        val filter = android.content.IntentFilter().apply {
            addAction(SMS_RECEIVED_ACTION)
            addAction(SMS_SENT_ACTION)
            addAction(SMS_DELIVERED_ACTION)
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { context.unregisterReceiver(receiver) }
    }

    // Live incoming messages while this screen is already open.
    //
    // The broadcast receiver above only helps when SmsNotificationListener manages
    // to post SMS_RECEIVED_ACTION — that needs the notification-listener role, which
    // the user can decline. Without a ContentObserver an inbound message would only
    // ever show up on the next ON_RESUME, so an SMS sitting in the conversation
    // list (or an open thread) could stay stale indefinitely. Watching the provider
    // directly covers both the default-SMS-app case and the notification case, and
    // it is debounced because the provider fires several times per message (the
    // row insert, then the status column update).
    DisposableEffect(hasReadSms(context)) {
        if (!hasReadSms(context)) {
            onDispose { }
        } else {
            val resolver = context.contentResolver
            val observer = object : android.database.ContentObserver(null) {
                override fun onChange(selfChange: Boolean) {
                    scope.launch {
                        delay(250)
                        refreshKey.value++
                    }
                }
            }
            resolver.registerContentObserver(
                Uri.parse("content://sms"),
                true,
                observer
            )
            onDispose { resolver.unregisterContentObserver(observer) }
        }
    }

    when {
        composingNew -> ComposeNew(
            viewModel = viewModel,
            initialNumber = smsPrefill,
            onBack = {
                composingNew = false
                smsPrefill = ""
                onInitialNumberConsumed()
            },
            onSent = {
                composingNew = false
                smsPrefill = ""
                onInitialNumberConsumed()
                refreshKey.value++
            }
        )
        currentThreadId != null -> MessageThread(
            viewModel = viewModel,
            threadId = currentThreadId!!,
            localStatuses = localStatuses,
            onRefresh = { refreshKey.value++ },
            onBack = {
                currentThreadId = null
                refreshKey.value++ // reload the conversation list so unread badges clear
            },
            refreshKey = refreshKey.value
        )
        else -> ConversationList(
            viewModel = viewModel,
            onOpenThread = { currentThreadId = it },
            onComposeNew = { composingNew = true },
            onUnreadCount = onUnreadCount,
            refreshKey = refreshKey.value
        )
    }
}

@Composable
private fun ConversationList(
    viewModel: MainViewModel,
    onOpenThread: (Long) -> Unit,
    onComposeNew: () -> Unit,
    onUnreadCount: (Int) -> Unit,
    refreshKey: Int
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hasReadSms = ContextCompat.checkSelfPermission(
        context, Manifest.permission.READ_SMS
    ) == PackageManager.PERMISSION_GRANTED

    var conversations by remember { mutableStateOf<List<Conversation>>(emptyList()) }
    var loading by remember { mutableStateOf(hasReadSms) }
    var error by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    /** Conversation whose long-press action sheet is open. */
    var actionFor by remember { mutableStateOf<Conversation?>(null) }
    /** Conversation waiting for the destructive "delete for real?" confirmation. */
    var confirmDeleteFor by remember { mutableStateOf<Conversation?>(null) }
    /** Local reload trigger (the [refreshKey] param is a value, owned by SmsScreen). */
    var reloadKey by remember { mutableStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }
    val contactNames = rememberContactNameMap()
    val hasNotificationAccess = remember {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.isNotificationListenerAccessGranted(
            ComponentName(context, SmsNotificationListener::class.java)
        )
    }

    val filtered = remember(conversations, searchQuery, contactNames) {
        val q = searchQuery.trim()
        if (q.isEmpty()) {
            conversations
        } else {
            val ql = q.lowercase(Locale.getDefault())
            conversations.filter { conv ->
                conv.body.lowercase(Locale.getDefault()).contains(ql) ||
                    conv.address.lowercase(Locale.getDefault()).contains(ql) ||
                    displayName(contactNames, conv.address).lowercase(Locale.getDefault()).contains(ql)
            }
        }
    }

    LaunchedEffect(hasReadSms, refreshKey, reloadKey) {
        if (hasReadSms) {
            loading = true
            error = null
            val res = withContext(Dispatchers.IO) { loadConversations(context, viewModel) }
            conversations = res.data
            error = res.error
            loading = false
            onUnreadCount(conversations.sumOf { it.unreadCount })
        }
    }

    if (!hasReadSms) {
        PermissionGate(
            text = "NJ-Dialer needs permission to read and send your SMS messages.",
            onRequest = {
                scope.launch {
                    ActivityCompat.requestPermissions(
                        context as Activity,
                        arrayOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS),
                        5001
                    )
                }
            }
        )
        return
    }

    if (!hasNotificationAccess) {
        NotificationAccessBanner {
            context.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            )
        }
    }

    BackHandler(enabled = showSearch) {
        searchQuery = ""
        showSearch = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        } else if (error != null) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    AppIcons.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
                Text(
                    error ?: "Error",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }
        } else if (conversations.isEmpty()) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    AppIcons.Sms,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "No conversations",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                AnimatedVisibility(visible = showSearch) {
                    AppSearchField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = "Search conversations",
                        onClose = { showSearch = false },
                        focusRequester = searchFocus,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
                LaunchedEffect(showSearch) {
                    if (showSearch) searchFocus.requestFocus()
                }
                if (filtered.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "No matches for \"$searchQuery\"",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().landscapeContentWidth(),
                        contentPadding = PaddingValues(bottom = 88.dp)
                    ) {
                        items(filtered, key = { it.threadId }) { conv ->
                            ConversationRow(
                                conv = conv,
                                contactNames = contactNames,
                                onClick = { onOpenThread(conv.threadId) },
                                onLongPress = { actionFor = conv }
                            )
                        }
                    }
                }
                    }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SmallFloatingActionButton(
                onClick = {
                    if (showSearch) {
                        searchQuery = ""
                        showSearch = false
                    } else {
                        showSearch = true
                    }
                },
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            ) {
                Icon(
                    if (showSearch) AppIcons.Close else AppIcons.Search,
                    contentDescription = if (showSearch) "Close search" else "Search conversations"
                )
            }
            FloatingActionButton(onClick = onComposeNew) {
                Icon(AppIcons.Add, contentDescription = "New message")
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 88.dp)
        )
    }

    // Long-press action sheet: mark read (the previous long-press behaviour) plus
    // "Delete conversation", which only ever clears nj-dialer's own copy - see
    // SmsConversationDeletes for why the provider rows survive.
    actionFor?.let { conv ->
        AlertDialog(
            onDismissRequest = { actionFor = null },
            title = { Text(displayName(contactNames, conv.address)) },
            text = { Text(conv.body, maxLines = 3, overflow = TextOverflow.Ellipsis) },
            confirmButton = {
                TextButton(onClick = {
                    actionFor = null
                    scope.launch {
                        withContext(Dispatchers.IO) { markThreadRead(context, conv.threadId) }
                        conversations = conversations.map {
                            if (it.threadId == conv.threadId) it.copy(unreadCount = 0) else it
                        }
                        onUnreadCount(conversations.sumOf { it.unreadCount })
                        Toast.makeText(context, "Marked as read", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Mark as read") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        actionFor = null
                        confirmDeleteFor = conv
                    }) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { actionFor = null }) { Text("Cancel") }
                }
            }
        )
    }

    confirmDeleteFor?.let { conv ->
        AlertDialog(
            onDismissRequest = { confirmDeleteFor = null },
            title = { Text("Delete conversation?") },
            text = {
                Text(
                    "This removes the conversation from NJ-Dialer. Your messages stay in " +
                        "your phone's SMS app, and the conversation will come back here if " +
                        "${displayName(contactNames, conv.address)} texts you again."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteFor = null
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            deleteConversation(context, viewModel, conv)
                        }
                        conversations = conversations.filterNot { it.threadId == conv.threadId }
                        onUnreadCount(conversations.sumOf { it.unreadCount })
                        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        nm.cancel(SMS_INCOMING_NOTIFICATION_ID)
                        val result = snackbarHostState.showSnackbar(
                            message = "Conversation deleted",
                            actionLabel = "Undo",
                            duration = SnackbarDuration.Long
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            // Undo only has to drop the tombstone: the provider rows were
                            // never deleted, so the conversation reappears on the next load.
                            SmsConversationDeletes.clear(context, setOf(conv.address))
                            reloadKey++
                        }
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteFor = null }) { Text("Cancel") }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ConversationRow(
    conv: Conversation,
    contactNames: Map<String, String>,
    onClick: () -> Unit,
    onLongPress: () -> Unit = {}
) {
    val name = displayName(contactNames, conv.address)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = name.take(1).uppercase().ifBlank { "#" },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = if (conv.unreadCount > 0) FontWeight.Bold else FontWeight.SemiBold
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                val simChip = simLabel(conv.simSlot)
                if (simChip != null) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(end = 6.dp)
                    ) {
                        Text(
                            text = simChip,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }
                Text(
                    text = formatDate(conv.date),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 4.dp)
                )
                if (conv.unreadCount > 0) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = conv.unreadCount.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onError
                        )
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = conv.body,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (conv.unreadCount > 0)
                        MaterialTheme.colorScheme.onSurface
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun MessageThread(
    viewModel: MainViewModel,
    threadId: Long,
    localStatuses: Map<Long, String>,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    refreshKey: Int
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var messages by remember { mutableStateOf<List<SmsMessage>>(emptyList()) }
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var simSlot by remember { mutableStateOf(0) }
    var actionFor by remember { mutableStateOf<SmsMessage?>(null) }
    var forwardingMsg by remember { mutableStateOf<SmsMessage?>(null) }
    val listState = rememberLazyListState()
    val contactNames = rememberContactNameMap()
    val clipboardManager = LocalClipboardManager.current
    val address = messages.firstOrNull { it.type == 1 }?.address
        ?: messages.firstOrNull()?.address
        ?: ""

    // Refresh when the app is resumed so incoming SMS appear even without notification access.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) onRefresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(threadId, refreshKey) {
        val res = withContext(Dispatchers.IO) { loadThread(context, viewModel, threadId) }
        messages = res.data
        error = res.error
        if (simSlot == 0) {
            simSlot = messages.firstOrNull { it.simSlot > 0 }?.simSlot ?: defaultSmsSlot(context)
        }
        if (messages.isNotEmpty()) {
            listState.scrollToItem(messages.size - 1)
        }
        // Mark the thread as read and clear the incoming-SMS notification.
        withContext(Dispatchers.IO) { markThreadRead(context, threadId) }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(SMS_INCOMING_NOTIFICATION_ID)
    }

    BackHandler(onBack = onBack)

    actionFor?.let { msg ->
        AlertDialog(
            onDismissRequest = { actionFor = null },
            title = { Text("Message actions") },
            text = {
                Text(
                    msg.body,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboardManager.setText(AnnotatedString(msg.body))
                    actionFor = null
                    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                }) { Text("Copy") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        forwardingMsg = msg
                        actionFor = null
                    }) { Text("Forward") }
                    TextButton(
                        onClick = {
                            actionFor = null
                            scope.launch {
                                deleteMessage(context, viewModel, msg)
                                onRefresh()
                            }
                        }
                    ) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { actionFor = null }) { Text("Cancel") }
                }
            }
        )
    }

    forwardingMsg?.let { msg ->
        var fwdNumber by remember(msg.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { forwardingMsg = null },
            title = { Text("Forward to") },
            text = {
                OutlinedTextField(
                    value = fwdNumber,
                    onValueChange = { fwdNumber = it },
                    label = { Text("Recipient number") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    enabled = fwdNumber.isNotBlank(),
                    onClick = {
                        val to = fwdNumber.trim()
                        val body = msg.body
                        forwardingMsg = null
                        scope.launch {
                            val threadId = resolveThreadIdForAddress(context, to)
                            sendLocalAndSms(viewModel, context, threadId, to, to, body, simSlot)
                            // Forwarding to a new recipient has the same
                            // synthetic-then-real fork as a fresh compose.
                            adoptRealThreadIdAfterSend(viewModel, context, threadId, to)
                            onRefresh()
                        }
                    }
                ) { Text("Send") }
            },
            dismissButton = {
                TextButton(onClick = { forwardingMsg = null }) { Text("Cancel") }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        val useGlassmorphism by viewModel.useGlassmorphism.collectAsState()
        FloatingTopBar(modifier = Modifier.padding(bottom = 4.dp), useGlass = useGlassmorphism) {
            IconButton(onClick = onBack) {
                Icon(AppIcons.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = displayName(contactNames, address),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }

        if (error != null) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    error ?: "Error",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .landscapeContentWidth()
                    .align(Alignment.CenterHorizontally),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(messages, key = { if (it.isLocal) "loc-${it.id}" else "sys-${it.id}" }) { msg ->
                    val isSent = msg.type == 2
                    val liveStatus = if (msg.isLocal && localStatuses.containsKey(msg.id))
                        localStatuses[msg.id] else msg.status
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = {},
                            onLongClick = { actionFor = msg }
                        ),
                    horizontalArrangement = if (isSent) Arrangement.End else Arrangement.Start
                ) {
                    Surface(
                        shape = RoundedCornerShape(
                            topStart = 16.dp,
                            topEnd = 16.dp,
                            bottomStart = if (isSent) 16.dp else 4.dp,
                            bottomEnd = if (isSent) 4.dp else 16.dp
                        ),
                        color = if (isSent)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (isSent)
                            MaterialTheme.colorScheme.onPrimary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(text = msg.body, style = MaterialTheme.typography.bodyMedium)
                            Row(
                                modifier = Modifier.align(Alignment.End),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val mSim = simLabel(msg.simSlot)
                                if (mSim != null) {
                                    Text(
                                        text = mSim,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isSent)
                                            MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                                        else
                                            MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(end = 6.dp)
                                    )
                                }
                                Text(
                                    text = formatTime(msg.date),
                                    style = MaterialTheme.typography.labelSmall
                                )
                                if (isSent && liveStatus != null) {
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = statusLabel(liveStatus),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (liveStatus == "FAILED")
                                            MaterialTheme.colorScheme.error
                                        else
                                            MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .landscapeContentWidth()
                .align(Alignment.CenterHorizontally)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Send via:",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SimSelectorChip("SIM 1", selected = simSlot == 1) { simSlot = 1 }
            SimSelectorChip("SIM 2", selected = simSlot == 2) { simSlot = 2 }
        }

        var templates by remember { mutableStateOf(SmsTemplates.load(context)) }
        var showTemplateAdd by remember { mutableStateOf(false) }
        var templateDraft by remember { mutableStateOf("") }
        if (templates.isNotEmpty() || true) {
            Row(
                modifier = Modifier.fillMaxWidth().landscapeContentWidth().align(Alignment.CenterHorizontally).padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.foundation.lazy.LazyRow(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(templates, key = { it }) { t ->
                        AssistChip(onClick = { input = if (input.isBlank()) t else "$input $t" }, label = { Text(t, maxLines = 1, overflow = TextOverflow.Ellipsis) }, trailingIcon = { IconButton(onClick = { templates = SmsTemplates.remove(context, t) }, modifier = Modifier.size(18.dp)) { Icon(AppIcons.Close, contentDescription = "Remove", modifier = Modifier.size(14.dp)) } })
                    }
                }
                IconButton(onClick = { templateDraft = ""; showTemplateAdd = true }) { Icon(AppIcons.Add, contentDescription = "Add template") }
            }
        }
        if (showTemplateAdd) {
            AlertDialog(
                onDismissRequest = { showTemplateAdd = false },
                title = { Text("Quick reply template") },
                text = { OutlinedTextField(value = templateDraft, onValueChange = { templateDraft = it }, label = { Text("Template text") }, modifier = Modifier.fillMaxWidth()) },
                confirmButton = { TextButton(onClick = { templates = SmsTemplates.add(context, templateDraft); showTemplateAdd = false }) { Text("Save") } },
                dismissButton = { TextButton(onClick = { showTemplateAdd = false }) { Text("Cancel") } }
            )
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .landscapeContentWidth()
                .align(Alignment.CenterHorizontally)
                .padding(horizontal = 12.dp)
                .padding(top = 4.dp, bottom = 8.dp),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 3.dp,
            color = MaterialTheme.colorScheme.surfaceContainer
        ) {
            val segments = if (input.isBlank()) 0
                else remember(input) { SmsManager.getDefault().divideMessage(input).size }
            val charsRemaining = if (input.isBlank()) 0
                else (segments * 160) - input.length
            Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Type a message") },
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (input.isNotBlank()) {
                            val body = input
                            input = ""
                            scope.launch {
                                sendInThread(viewModel, context, messages, threadId, simSlot, body) {
                                    onRefresh()
                                }
                            }
                        }
                    })
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    enabled = input.isNotBlank(),
                    onClick = {
                        val body = input
                        input = ""
                        scope.launch {
                            sendInThread(viewModel, context, messages, threadId, simSlot, body) {
                                onRefresh()
                            }
                        }
                    }
                ) {
                    Icon(
                        AppIcons.Send,
                        contentDescription = "Send",
                        tint = if (input.isNotBlank())
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (input.isNotBlank()) {
                Text(
                    text = "$charsRemaining chars · $segments segment${if (segments == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(end = 12.dp, bottom = 2.dp)
                )
            }
            }
        }
    }
}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComposeNew(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onSent: () -> Unit,
    initialNumber: String = ""
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var number by remember { mutableStateOf(initialNumber) }
    var message by remember { mutableStateOf("") }
    var showContactPicker by remember { mutableStateOf(false) }
    var contactPickerItems by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var simSlot by remember { mutableStateOf(defaultSmsSlot(context)) }

    LaunchedEffect(initialNumber) {
        if (initialNumber.isNotBlank()) number = initialNumber
    }

    BackHandler(onBack = onBack)

    if (showContactPicker) {
        AlertDialog(
            onDismissRequest = { showContactPicker = false },
            title = { Text("Pick a contact") },
            text = {
                LazyColumn(modifier = Modifier.height(300.dp)) {
                    items(contactPickerItems) { (cn, num) ->
                        TextButton(onClick = {
                            number = num
                            showContactPicker = false
                        }) {
                            Column {
                                Text(cn, fontWeight = FontWeight.SemiBold)
                                Text(
                                    num,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showContactPicker = false }) { Text("Cancel") }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        val useGlassmorphism2 by viewModel.useGlassmorphism.collectAsState()
        FloatingTopBar(useGlass = useGlassmorphism2) {
            IconButton(onClick = onBack) {
                Icon(AppIcons.ArrowBack, contentDescription = "Back")
            }
            Text(
                "New message",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Send via:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SimSelectorChip("SIM 1", selected = simSlot == 1) { simSlot = 1 }
                SimSelectorChip("SIM 2", selected = simSlot == 2) { simSlot = 2 }
            }
            OutlinedTextField(
                value = number,
                onValueChange = { number = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Recipient number") },
                placeholder = { Text("e.g. 0991234567") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    focusedLabelColor = MaterialTheme.colorScheme.primary,
                    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                trailingIcon = {
                    IconButton(onClick = {
                        scope.launch {
                            contactPickerItems = withContext(Dispatchers.IO) {
                                DeviceContacts.query(context)
                                    .distinctBy { it.second.filter { c -> c.isDigit() } }
                            }
                            showContactPicker = true
                        }
                    }) {
                        Icon(AppIcons.Contacts, contentDescription = "Pick contact")
                    }
                },
                singleLine = true
            )
            OutlinedTextField(
                value = message,
                onValueChange = { message = it },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                label = { Text("Message") },
                // Mirrors the thread composer, which renders typed text correctly:
                // a placeholder always gives a visible hint, and the text colours
                // are pinned to onSurface so the glyphs can never inherit an
                // invisible LocalContentColor from a parent surface.
                placeholder = { Text("Type a message") },
                maxLines = 6,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    focusedLabelColor = MaterialTheme.colorScheme.primary,
                    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
            Button(
                onClick = {
                    if (number.isNotBlank() && message.isNotBlank()) {
                        val to = number.trim()
                        val body = message
                        message = ""
                        scope.launch {
                            val threadId = resolveThreadIdForAddress(context, to)
                            sendLocalAndSms(viewModel, context, threadId, to, to, body, simSlot)
                            // A first message to a new number starts on a synthetic
                            // thread; once the OS creates the real provider thread we
                            // re-parent the echo onto it so the conversation doesn't
                            // appear twice.
                            adoptRealThreadIdAfterSend(viewModel, context, threadId, to)
                            onSent()
                        }
                    }
                },
                enabled = number.isNotBlank() && message.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(AppIcons.Send, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Send")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimSelectorChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = if (selected)
            MaterialTheme.colorScheme.primary
        else
            MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (selected)
            MaterialTheme.colorScheme.onPrimary
        else
            MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun PermissionGate(text: String, onRequest: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            AppIcons.Sms,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRequest) {
            Text("Grant permission")
        }
    }
}

@Composable
private fun NotificationAccessBanner(onSettings: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(AppIcons.NotificationAdd, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                "Enable notification access to see incoming SMS in real time",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onSettings) {
                Text("Enable")
            }
        }
    }
}

// --- Data helpers ---

private fun hasReadSms(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
        PackageManager.PERMISSION_GRANTED

private class SmsResult<T>(val data: T, val error: String? = null)

private suspend fun loadConversations(context: Context, viewModel: MainViewModel): SmsResult<List<Conversation>> {
    if (!hasReadSms(context)) {
        return SmsResult(emptyList(), "READ_SMS permission not granted")
    }
    val byThread = LinkedHashMap<Long, Conversation>()
    val unreadByThread = HashMap<Long, Int>()
    try {
        // The content://sms/conversations view rejects an 'address' projection on
        // some OEMs (invalid column address). Query the full sms table once and
        // group by thread_id in memory instead — this also gives us the SIM sub_id.
        val cursor: Cursor? = context.contentResolver.query(
            Uri.parse("content://sms"),
            arrayOf("thread_id", "address", "body", "date", "read", "sub_id"),
            null, null, "date DESC"
        )
        cursor?.use { c ->
            val iThread = c.getColumnIndexOrThrow("thread_id")
            val iAddr = c.getColumnIndex("address")
            val iBody = c.getColumnIndex("body")
            val iDate = c.getColumnIndex("date")
            val iRead = c.getColumnIndex("read")
            val iSub = if (c.getColumnIndex("sub_id") >= 0) c.getColumnIndex("sub_id")
                else c.getColumnIndex("subscription_id")
            while (c.moveToNext()) {
                val threadId = c.getLong(iThread)
                val address = if (iAddr >= 0) c.getString(iAddr) ?: "" else ""
                val subId = if (iSub >= 0) c.getLong(iSub) else -1L
                val isRead = if (iRead >= 0) c.getInt(iRead) else 1
                if (isRead == 0) {
                    unreadByThread[threadId] = (unreadByThread[threadId] ?: 0) + 1
                }
                // Since rows are sorted date DESC, the first row per thread is the latest
                if (!byThread.containsKey(threadId)) {
                    byThread[threadId] = Conversation(
                        threadId = threadId,
                        address = address,
                        body = if (iBody >= 0) c.getString(iBody) ?: "" else "",
                        date = if (iDate >= 0) c.getLong(iDate) else 0L,
                        read = isRead,
                        simSlot = subIdToSlot(context, subId)
                    )
                }
            }
        }
        for ((tid, count) in unreadByThread) {
            byThread[tid]?.let { byThread[tid] = it.copy(unreadCount = count) }
        }
        // Merge locally-sent messages (room) into the conversation list so newly
        // sent messages surface even though they aren't in the system SMS DB.
        val local = viewModel.getLocalSmsAll()
        for (le in local) {
            val existing = byThread[le.threadId]
            if (existing == null) {
                byThread[le.threadId] = Conversation(
                    threadId = le.threadId,
                    address = le.address,
                    body = le.body,
                    date = le.date,
                    read = 1,
                    simSlot = le.simSlot
                )
            } else if (le.date >= existing.date) {
                byThread[le.threadId] = existing.copy(
                    body = le.body,
                    date = le.date,
                    simSlot = le.simSlot
                )
            }
        }
    } catch (e: Exception) {
        return SmsResult(emptyList(), "Couldn't read SMS: ${e.message}")
    }
    SmsConversationDeletes.unhideIncoming(context)
    val hidden = SmsConversationDeletes.read(context).keys
    val visible = byThread.values.filter { conv ->
        SmsConversationDeletes.keyFor(conv.address) !in hidden
    }
    return SmsResult(visible.sortedByDescending { it.date })
}

private suspend fun loadThread(context: Context, viewModel: MainViewModel, threadId: Long): SmsResult<List<SmsMessage>> {
    if (!hasReadSms(context)) {
        return SmsResult(emptyList(), "READ_SMS permission not granted")
    }
    val result = mutableListOf<SmsMessage>()
    try {
        val cursor: Cursor? = context.contentResolver.query(
            Uri.parse("content://sms"),
            arrayOf("_id", "address", "body", "date", "type", "sub_id"),
            "thread_id = ?",
            arrayOf(threadId.toString()),
            "date ASC"
        )
        cursor?.use { c ->
            val iId = c.getColumnIndexOrThrow("_id")
            val iAddr = c.getColumnIndex("address")
            val iBody = c.getColumnIndex("body")
            val iDate = c.getColumnIndex("date")
            val iType = c.getColumnIndex("type")
            val iSub = if (c.getColumnIndex("sub_id") >= 0) c.getColumnIndex("sub_id")
                else c.getColumnIndex("subscription_id")
            while (c.moveToNext()) {
                val subId = if (iSub >= 0) c.getLong(iSub) else -1L
                result.add(
                    SmsMessage(
                        id = c.getLong(iId),
                        address = if (iAddr >= 0) c.getString(iAddr) ?: "" else "",
                        body = if (iBody >= 0) c.getString(iBody) ?: "" else "",
                        date = if (iDate >= 0) c.getLong(iDate) else 0L,
                        type = if (iType >= 0) c.getInt(iType) else 1,
                        simSlot = subIdToSlot(context, subId)
                    )
                )
            }
        }
        // Merge locally-sent messages from Room into this thread. The OS itself
        // writes its own sent row into content://sms (type=2) even for a
        // non-default app, so a Room echo and the OS row describe the SAME
        // message and must collapse into exactly one bubble.
        //
        // Matching (see DEDUP_WINDOW_MS for why the old 2-second bucket was
        // broken):
        //   * candidate = a provider row whose type is an OUTGOING one
        //     (2/4/5/6), inside [echo.date - SKEW, echo.date + WINDOW],
        //   * gate 1 — one provider row whose normalised body equals the echo's:
        //     nearest in time wins,
        //   * gate 2 — a run of consecutive part rows whose concatenation equals
        //     the echo body (a multipart send stores one row per part while the
        //     echo keeps the whole text), collapsed into a single bubble,
        //   * gate 3 — loose fallback for a ROM that mangles the text: only when
        //     the window holds exactly one candidate and the two normalised
        //     bodies contain one another,
        //   * a provider row is consumed by at most ONE echo, so sending the
        //     same text twice in a row can never collapse onto a single row.
        //
        // A matched row is re-rendered as type=2 (so it keeps its right-hand
        // alignment even if the ROM stored it as 5/6) and adopts the Room id +
        // status, so the Sent/Delivered tick keeps working and still survives an
        // app restart (the status lives in Room, not in memory).
        //
        // This is recomputed from scratch on every load, which is safe: nothing
        // can delete a provider row without WRITE_SMS, so a reconciled thread
        // stays reconciled — and threads already showing twins heal on the very
        // next load, with no migration.
        val local = viewModel.getLocalSmsForThread(threadId)
        val consumed = HashSet<Int>()            // provider-row indices claimed by an echo
        val merged = HashMap<Int, SmsMessage>()  // provider-row index -> row to render instead
        val looseEchoes = ArrayList<SmsMessage>()// echoes with no provider row to adopt

        fun echoRow(le: LocalSmsEntity) = SmsMessage(
            id = le.id,
            address = le.address,
            body = le.body,
            date = le.date,
            type = 2,
            simSlot = le.simSlot,
            status = le.status,
            isLocal = true
        )

        for (le in local) {
            // A synthetic (negative) thread has no provider rows at all, so the
            // echo is the only record of that message.
            if (le.threadId < 0) {
                looseEchoes += echoRow(le)
                continue
            }
            val candidates = result.indices.filter { i ->
                i !in consumed &&
                    result[i].type in OUTGOING_SMS_TYPES &&
                    result[i].date >= le.date - DEDUP_SKEW_MS &&
                    result[i].date <= le.date + DEDUP_WINDOW_MS
            }
            if (candidates.isEmpty()) {
                looseEchoes += echoRow(le)
                continue
            }
            val norm = normalizeForDedup(le.body)

            // Gate 1: single row holding the whole body, nearest in time.
            val exact = candidates
                .filter { normalizeForDedup(result[it].body) == norm }
                .minByOrNull { abs(result[it].date - le.date) }
            if (exact != null) {
                consumed += exact
                merged[exact] = result[exact].copy(
                    id = le.id,
                    type = 2,
                    status = le.status,
                    isLocal = true
                )
                continue
            }

            // Gate 2: a run of part rows whose concatenation is the whole body.
            val acc = StringBuilder()
            val run = mutableListOf<Int>()
            var multipartHit = false
            for (i in candidates.sortedBy { result[it].date }) {
                if (acc.length > le.body.length) break
                acc.append(result[i].body)
                run += i
                if (normalizeForDedup(acc.toString()) == norm) { multipartHit = true; break }
            }
            if (multipartHit) {
                // Every row in the run is claimed; only the head is rendered and
                // it carries the echo's full text.
                consumed += run
                val head = run.first()
                merged[head] = result[head].copy(
                    id = le.id,
                    body = le.body,
                    type = 2,
                    status = le.status,
                    isLocal = true
                )
                continue
            }

            // Gate 3: exactly one candidate in the window, and one normalised
            // body contains the other (ROM re-encoding / truncated text).
            // Deliberately conservative — with two or more candidates there is
            // no way to tell which one is ours, so show both messages honestly.
            if (candidates.size == 1) {
                val only = candidates[0]
                val other = normalizeForDedup(result[only].body)
                if (other.isNotEmpty() && norm.isNotEmpty() &&
                    (other.contains(norm) || norm.contains(other))
                ) {
                    consumed += only
                    merged[only] = result[only].copy(
                        id = le.id,
                        type = 2,
                        status = le.status,
                        isLocal = true
                    )
                    continue
                }
            }

            looseEchoes += echoRow(le)
        }

        // Rebuild: one rendered row per message. A provider index that is both
        // consumed and merged is the head of a multipart group and is rendered;
        // consumed-but-not-merged are the extra part rows, which are dropped.
        val rebuilt = ArrayList<SmsMessage>(result.size + looseEchoes.size)
        for (i in result.indices) {
            if (i in consumed && !merged.containsKey(i)) continue
            rebuilt += merged[i] ?: result[i]
        }
        rebuilt.addAll(looseEchoes)
        result.clear()
        result.addAll(rebuilt)
        result.sortBy { it.date }
    } catch (e: Exception) {
        return SmsResult(result, "Couldn't read messages: ${e.message}")
    }
    return SmsResult(result)
}

private suspend fun sendLocalAndSms(
    viewModel: MainViewModel,
    context: Context,
    threadId: Long,
    toAddress: String,
    number: String,
    body: String,
    simSlot: Int
) {
    if (number.isBlank() || body.isBlank()) return
    val hasSend = ContextCompat.checkSelfPermission(
        context, Manifest.permission.SEND_SMS
    ) == PackageManager.PERMISSION_GRANTED
    if (!hasSend) {
        Toast.makeText(context, "Send SMS permission not granted", Toast.LENGTH_SHORT).show()
        return
    }
    // Persist a local sent message (status SENDING) so it survives restarts and can
    // be shown in the chat even though this app is not the default SMS handler.
    val localId = viewModel.insertLocalSmsSuspend(
        threadId, toAddress, body, simSlot, "SENDING"
    )
    val pId = buildDeliveredPendingIntent(context, localId)
    try {
        val smsManager = resolveSmsManager(context, simSlot)
        val parts = smsManager.divideMessage(body)
        if (parts.size > 1) {
            val sentPIs = ArrayList<PendingIntent>(parts.size)
            val deliveryPIs = ArrayList<PendingIntent>(parts.size)
            for (i in parts.indices) {
                sentPIs.add(buildSentPendingIntent(context, localId, i))
                pId?.let { deliveryPIs.add(it) }
            }
            smsManager.sendMultipartTextMessage(number, null, parts, sentPIs, deliveryPIs)
        } else {
            smsManager.sendTextMessage(number, null, body, buildSentPendingIntent(context, localId, 0), pId)
        }
        SmsConversationDeletes.clear(context, listOf(toAddress, number))
        // No immediate "Message sent" toast: the SENT broadcast confirms the hand-off.
    } catch (e: Exception) {
        viewModel.updateLocalSmsStatus(localId, "FAILED")
        Toast.makeText(context, "Failed to send: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

// Sends a message from inside an open thread: derives the address, persists the local
// echo, then actually sends. Triggers a refresh when done.
private suspend fun sendInThread(
    viewModel: MainViewModel,
    context: Context,
    messages: List<SmsMessage>,
    threadId: Long,
    simSlot: Int,
    body: String,
    onDone: () -> Unit
) {
    val toAddress = messages.firstOrNull { it.type == 1 }?.address
        ?: messages.firstOrNull()?.address
        ?: ""
    if (toAddress.isBlank() || body.isBlank()) return
    sendLocalAndSms(viewModel, context, threadId, toAddress, toAddress, body, simSlot)
    onDone()
}

// Resolve the thread_id for a given address. Returns the provider thread id when a
// real thread exists, otherwise a stable synthetic (negative) id unique per recipient
// so distinct new conversations don't collapse into one bucket. -1 means "none".
/**
 * The subscriber-number tail used to recognise "the same person" across the
 * different shapes an address is stored in.
 *
 * This is deliberately *not* [normalizeNumber] (which keeps the last 10 digits):
 * a local dialled form ("0712 345 678") and the international form stored by the
 * provider ("+265 712 345 678") share only their last **9** digits, so a 10-digit
 * tail gives the two different values and a first conversation would fork.
 */
private fun significantTail(number: String, digits: Int = 9): String {
    val d = number.filter { it.isDigit() }
    return if (d.length > digits) d.takeLast(digits) else d
}

private fun queryProviderThreadId(context: Context, selection: String, args: Array<String>): Long =
    try {
        val cursor = context.contentResolver.query(
            Uri.parse("content://sms"),
            arrayOf("thread_id"),
            selection,
            args,
            "date DESC"
        )
        cursor?.use { c -> if (c.moveToFirst()) c.getLong(0) else -1L } ?: -1L
    } catch (e: Exception) {
        -1L
    }

private fun resolveThreadIdForAddress(context: Context, number: String): Long {
    // 1. Exact address match — the common case when the number came from a
    //    conversation or the contact picker.
    val exact = queryProviderThreadId(context, "address = ?", arrayOf(number))
    if (exact >= 0) return exact

    // 2. Suffix match on the subscriber number. `address = ?` is a literal string
    //    compare in the SMS provider, so a hand-typed local number never matches
    //    the stored international one and used to fall straight through to a new
    //    synthetic thread — which is half of what produced clone conversations.
    val tail = significantTail(number)
    if (tail.length >= 7) {
        val loose = queryProviderThreadId(context, "address LIKE ?", arrayOf("%$tail"))
        if (loose >= 0) return loose
    }
    return syntheticThreadId(number)
}

/**
 * After sending to a *synthetic* thread, the OS writes its own copy of the
 * message into a brand-new real `content://sms` thread. The Room echo still
 * points at the negative synthetic id, so the conversation list would show the
 * same conversation twice. Poll briefly for the real id and re-parent the echo.
 *
 * Returns the thread id the conversation should now be keyed by, or the original
 * one when the provider never produced a thread (send failed / still pending).
 */
private suspend fun adoptRealThreadIdAfterSend(
    viewModel: MainViewModel,
    context: Context,
    syntheticId: Long,
    address: String
): Long {
    if (syntheticId >= 0) return syntheticId
    val tail = significantTail(address)
    // The provider row lands within a second or so; a handful of short retries is
    // plenty and keeps the send path off the critical UI thread for long.
    repeat(6) {
        val real = if (tail.length >= 7) {
            queryProviderThreadId(context, "address LIKE ?", arrayOf("%$tail"))
        } else {
            queryProviderThreadId(context, "address = ?", arrayOf(address))
        }
        if (real >= 0) {
            viewModel.migrateLocalSmsThreadSuspend(syntheticId, real)
            return real
        }
        delay(400)
    }
    return syntheticId
}

// Deletes a single message. Locally-echoed messages are removed from Room (works fully).
// Provider messages are deleted via content://sms, which only succeeds if this app is the
// default SMS handler — otherwise it fails silently and we inform the user.
private suspend fun deleteMessage(context: Context, viewModel: MainViewModel, msg: SmsMessage) {
    if (msg.isLocal) {
        viewModel.deleteLocalSmsByIdSuspend(msg.id)
        return
    }
    val deleted = try {
        context.contentResolver.delete(
            Uri.parse("content://sms/${msg.id}"),
            null, null
        )
    } catch (e: Exception) {
        0
    }
    if (deleted <= 0) {
        Toast.makeText(
            context,
            "Couldn't delete this system SMS (needs default SMS handler)",
            Toast.LENGTH_SHORT
        ).show()
    }
}

/**
 * Deletes a whole conversation from NJ-Dialer.
 *
 * nj-dialer is NOT the default SMS handler and declares no WRITE_SMS, so a
 * `content://sms` delete silently no-ops here — the messages necessarily stay in the
 * phone's SMS app. A delete therefore does what it *can*: it drops the app's own
 * `local_sms` echoes and adds the conversation's number to a hidden-key block in
 * [SmsConversationDeletes], so old provider rows and delayed writes cannot resurrect
 * the thread. Deliberately no error toast (unlike [deleteMessage]): a failed provider
 * delete is expected here, not a problem the user can act on.
 */
private suspend fun deleteConversation(
    context: Context,
    viewModel: MainViewModel,
    conv: Conversation
) {
    val key = SmsConversationDeletes.keyFor(conv.address)
    val localRows = viewModel.getLocalSmsAll().filter {
        it.threadId == conv.threadId ||
            (key.isNotEmpty() && SmsConversationDeletes.keyFor(it.address) == key)
    }
    if (conv.threadId >= 0) {
        runCatching {
            context.contentResolver.delete(
                Uri.parse("content://sms"),
                "thread_id = ?",
                arrayOf(conv.threadId.toString())
            )
        }
    }
    viewModel.deleteLocalSmsForThreadSuspend(conv.threadId)
    localRows.filter { it.threadId != conv.threadId }.forEach {
        viewModel.deleteLocalSmsByIdSuspend(it.id)
    }
    SmsConversationDeletes.mark(context, conv.address)
}

// Stable negative id derived from a normalized address. Provider thread ids are small
// positive numbers, so these never collide. Used to group brand-new conversations by
// recipient without a real provider thread.
private fun syntheticThreadId(number: String): Long {
    // Hash the subscriber-number tail, not the raw string: "+265712345678" and
    // "0712345678" are one person, and two different hashes for them would create
    // two separate conversations for the same recipient.
    val norm = SmsConversationDeletes.keyFor(number)
    val h = kotlin.math.abs(norm.hashCode())
    return -(1_000_000_000L + h)
}

private fun buildSentPendingIntent(context: Context, localId: Long, partIndex: Int): PendingIntent {
    return PendingIntent.getBroadcast(
        context,
        localId.toInt() + 100 + partIndex,
        Intent(SMS_SENT_ACTION)
            .setPackage(context.packageName)
            .putExtra(EXTRA_LOCAL_ID, localId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

private fun buildDeliveredPendingIntent(context: Context, localId: Long): PendingIntent? {
    return PendingIntent.getBroadcast(
        context,
        localId.toInt() + 1,
        Intent(SMS_DELIVERED_ACTION)
            .setPackage(context.packageName)
            .putExtra(EXTRA_LOCAL_ID, localId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

private fun smsResultText(resultCode: Int): String = when (resultCode) {
    SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "generic failure"
    SmsManager.RESULT_ERROR_NO_SERVICE -> "no service"
    SmsManager.RESULT_ERROR_NULL_PDU -> "null PDU"
    SmsManager.RESULT_ERROR_RADIO_OFF -> "radio off"
    else -> "error ($resultCode)"
}

private fun statusLabel(status: String): String = when (status) {
    "SENDING" -> "Sending\u2026"
    "SENT" -> "✓ Sent"
    "DELIVERED" -> "✓✓ Delivered"
    "FAILED" -> "Failed"
    else -> status
}

private fun sendMessage(context: Context, number: String, message: String, simSlot: Int = 0) {
    if (number.isBlank() || message.isBlank()) return
}

// Maps a message's stored subscription id to a 1-based SIM slot (0 = undetermined).
private fun subIdToSlot(context: Context, subId: Long): Int {
    if (subId <= 0) return 0
    return try {
        val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
        sm?.activeSubscriptionInfoList
            ?.firstOrNull { it.subscriptionId.toLong() == subId }
            ?.let { it.simSlotIndex + 1 } ?: 0
    } catch (e: Exception) {
        0
    }
}

// Resolves the system default SMS subscription id, falling back to the default data
// subscription, then the first active SIM.
private fun defaultSmsSubId(context: Context): Int {
    return try {
        val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
        var id = SubscriptionManager.getDefaultSmsSubscriptionId()
        if (id == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            id = SubscriptionManager.getDefaultDataSubscriptionId()
        }
        if (id == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            id = sm?.activeSubscriptionInfoList?.firstOrNull()?.subscriptionId
                ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
        }
        id
    } catch (e: Exception) {
        SubscriptionManager.INVALID_SUBSCRIPTION_ID
    }
}

// Maps a 1-based SIM slot to its subscription id (0/negative = invalid).
private fun slotToSubId(context: Context, slot: Int): Int {
    if (slot <= 0) return defaultSmsSubId(context)
    return try {
        val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
        sm?.activeSubscriptionInfoList
            ?.firstOrNull { it.simSlotIndex == slot - 1 }
            ?.subscriptionId ?: defaultSmsSubId(context)
    } catch (e: Exception) {
        defaultSmsSubId(context)
    }
}

// Returns an SmsManager for the given slot (falls back to the default SMS sub when invalid).
private fun resolveSmsManager(context: Context, slot: Int): SmsManager {
    val subId = slotToSubId(context, slot)
    return if (subId > 0) {
        SmsManager.getSmsManagerForSubscriptionId(subId)
    } else {
        context.getSystemService(SmsManager::class.java)
    }
}

// Resolves the system default SMS subscription to a 1-based SIM slot (0 if undetermined).
private fun defaultSmsSlot(context: Context): Int {
    val subId = defaultSmsSubId(context)
    return subIdToSlot(context, subId.toLong())
}

private fun simLabel(slot: Int): String? = when (slot) {
    1 -> "SIM 1"
    2 -> "SIM 2"
    else -> null
}

private fun normalizeNumber(number: String): String =
    number.filter { it.isDigit() }.takeLast(10)

// Normalizes a body for dedup comparisons (ignore casing/whitespace so the exact same
// text sent quickly is considered one message rather than twins).
private fun normalizeForDedup(body: String): String =
    body.trim().filterNot { it.isWhitespace() }.lowercase(Locale.getDefault())

// Builds a last-10-digit -> name map from the device contacts once per load.
@Composable
private fun rememberContactNameMap(): Map<String, String> {
    val context = LocalContext.current
    val granted = ContextCompat.checkSelfPermission(
        context, Manifest.permission.READ_CONTACTS
    ) == PackageManager.PERMISSION_GRANTED
    return remember(granted) {
        if (!granted) emptyMap()
        else DeviceContacts.query(context)
            .associate { (name, number) -> normalizeNumber(number) to name }
    }
}

private fun displayName(contactNames: Map<String, String>, number: String): String {
    if (number.isBlank()) return "(Unknown)"
    val byDigits = contactNames[normalizeNumber(number)]
    return byDigits ?: number
}

// Best-effort mark of a thread's messages as read in the provider (only fully works
// for the default SMS app, but is harmless otherwise).
private fun markThreadRead(context: Context, threadId: Long) {
    if (threadId < 0) return // synthetic conversation, nothing in the provider to mark
    try {
        val values = android.content.ContentValues().apply { put("read", 1) }
        context.contentResolver.update(
            Uri.parse("content://sms"),
            values,
            "thread_id = ? AND read = 0",
            arrayOf(threadId.toString())
        )
    } catch (e: Exception) {
        // ignore
    }
}

private fun formatDate(ts: Long): String {
    val now = System.currentTimeMillis()
    val format = if (now - ts < 24 * 3600 * 1000L) "HH:mm" else "MMM d"
    return SimpleDateFormat(format, Locale.getDefault()).format(Date(ts))
}

private fun formatTime(ts: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
