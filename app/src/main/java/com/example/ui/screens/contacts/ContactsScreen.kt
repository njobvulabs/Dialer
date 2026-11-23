package com.example.ui.screens.contacts

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.db.ContactEntity
import com.example.ui.MainViewModel
import com.example.ui.components.AppIcons
import com.example.ui.components.AppSearchField
import com.example.ui.components.landscapeContentWidth
import com.example.util.ContactNumbers
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A contact plus the specific number an action should act on. */
private data class CallTarget(val contact: ContactEntity, val number: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    viewModel: MainViewModel,
    onMakeCall: (String, Int) -> Unit
) {
    val allContacts by viewModel.combinedContacts.collectAsState()
    var query by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingContact by remember { mutableStateOf<ContactEntity?>(null) }
    var selectedContact by remember { mutableStateOf<ContactEntity?>(null) }
    var simSelectorTarget by remember { mutableStateOf<CallTarget?>(null) }
    // Which multi-number person has its number list expanded. One at a time,
    // matching Call History / Notes / Gallery.
    var expandedContactId by remember { mutableStateOf<Long?>(null) }
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val favoriteContacts = remember(allContacts) {
        allContacts.filter { it.isFavorite }.sortedBy { it.name.lowercase() }
    }

    val filteredContacts = remember(allContacts, query) {
        val q = query.trim()
        if (q.isEmpty()) {
            allContacts.sortedBy { it.name.lowercase() }
        } else {
            // Match the name, OR any one of the contact's numbers compared on
            // digits only — so typing `991234567` finds `+265 991 234 567`, and
            // a match can never straddle the `", "` that joins a multi-number
            // contact's field.
            val qDigits = q.filter { it.isDigit() }
            allContacts.filter { contact ->
                contact.name.contains(q, ignoreCase = true) ||
                    (qDigits.isNotEmpty() && ContactNumbers.keys(contact.phoneNumber)
                        .any { it.contains(qDigits) }) ||
                    (q.any { it.isDigit() }.not() &&
                        contact.phoneNumber.contains(q, ignoreCase = true))
            }.sortedBy { it.name.lowercase() }
        }
    }

    val groupedContacts = remember(filteredContacts) {
        filteredContacts.groupBy { contact ->
            val firstChar = contact.name.trim().firstOrNull()?.uppercaseChar() ?: '#'
            if (firstChar.isLetter()) firstChar else '#'
        }
    }

    BackHandler(enabled = selectedContact != null) { selectedContact = null }

    Box(modifier = Modifier.fillMaxSize()) {
        if (selectedContact == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                // Top Bar Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Contacts",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "${filteredContacts.size} contacts",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    IconButton(onClick = { showSearch = !showSearch }) {
                        Icon(
                            imageVector = AppIcons.Search,
                            contentDescription = "Search contacts",
                            tint = if (showSearch) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { viewModel.refreshDeviceContacts(force = true) }) {
                        Icon(
                            imageVector = AppIcons.Refresh,
                            contentDescription = "Refresh contacts",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // Search Bar (toggleable via the search icon)
                if (showSearch) {
                    AppSearchField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = "Search contacts or numbers...",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }

                // Favorites Horizontal Row (When no search query active)
                if (query.isEmpty() && favoriteContacts.isNotEmpty()) {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        Text(
                            text = "Favorites",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                        )
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(favoriteContacts, key = { "fav_${it.id}" }) { fav ->
                                Surface(
                                    onClick = { selectedContact = fav },
                                    shape = MaterialTheme.shapes.medium,
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    tonalElevation = 2.dp
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(28.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.primary),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = fav.name.take(1).uppercase(),
                                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                                color = MaterialTheme.colorScheme.onPrimary
                                            )
                                        }
                                        Text(
                                            text = fav.name,
                                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (filteredContacts.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize().landscapeContentWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = AppIcons.PersonOff,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = if (query.isEmpty()) "No contacts yet" else "No matching contacts found",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().landscapeContentWidth(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        groupedContacts.forEach { (initial, contactsInGroup) ->
                            item(key = "header_$initial") {
                                Text(
                                    text = initial.toString(),
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp)
                                )
                            }
                            items(contactsInGroup, key = { it.id }) { contact ->
                                val numbers = remember(contact.phoneNumber) {
                                    ContactNumbers.list(contact.phoneNumber)
                                }
                                ContactRow(
                                    contact = contact,
                                    isDeviceContact = contact.id < 0,
                                    numbers = numbers,
                                    expanded = expandedContactId == contact.id,
                                    onToggleExpand = {
                                        expandedContactId =
                                            if (expandedContactId == contact.id) null else contact.id
                                    },
                                    onContactClick = {
                                        selectedContact = contact
                                        expandedContactId = null
                                    },
                                    onCallClick = {
                                        simSelectorTarget = CallTarget(contact, numbers.firstOrNull().orEmpty())
                                    },
                                    onNumberCall = { number ->
                                        simSelectorTarget = CallTarget(contact, number)
                                    },
                                    onNumberCopy = { number ->
                                        clipboardManager.setText(AnnotatedString(number))
                                        Toast.makeText(context, "$number copied", Toast.LENGTH_SHORT).show()
                                    },
                                    onCopyClick = {
                                        val primary = numbers.firstOrNull().orEmpty()
                                        clipboardManager.setText(AnnotatedString(primary))
                                        Toast.makeText(context, "Number copied to clipboard", Toast.LENGTH_SHORT).show()
                                    },
                                    onEditClick = {
                                        if (contact.id < 0) {
                                            Toast.makeText(context, "Device contact - edit in system contacts app", Toast.LENGTH_SHORT).show()
                                        } else {
                                            editingContact = contact
                                        }
                                    },
                                    onDeleteClick = {
                                        if (contact.id < 0) {
                                            Toast.makeText(context, "Device contact - delete in system contacts app", Toast.LENGTH_SHORT).show()
                                        } else {
                                            viewModel.deleteContact(contact)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
                    }
            }

            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(20.dp)
            ) {
                Icon(AppIcons.Add, contentDescription = "Add Contact")
            }
        } else {
            selectedContact?.let { contact ->
                val numbers = remember(contact.phoneNumber) {
                    ContactNumbers.list(contact.phoneNumber)
                }
                ContactDetailsPanel(
                    contact = contact,
                    isDeviceContact = contact.id < 0,
                    numbers = numbers,
                    onBack = { selectedContact = null },
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
                            editingContact = contact
                            selectedContact = null
                        }
                    },
                    onDelete = {
                        if (contact.id < 0) {
                            Toast.makeText(context, "Device contact - delete in system contacts app", Toast.LENGTH_SHORT).show()
                        } else {
                            viewModel.deleteContact(contact)
                            selectedContact = null
                        }
                    }
                )
            }
        }
    }

    // --- SIM Choice Dialog for Calling ---
    simSelectorTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { simSelectorTarget = null },
            title = { Text("Call ${target.contact.name}") },
            text = {
                Column {
                    Text(target.number, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Choose SIM card slot to place the call:")
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            onMakeCall(target.number, 1)
                            simSelectorTarget = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A))
                    ) {
                        Text("SIM 1")
                    }
                    Button(
                        onClick = {
                            onMakeCall(target.number, 2)
                            simSelectorTarget = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                    ) {
                        Text("SIM 2")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { simSelectorTarget = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // --- Add New Contact Dialog ---
    if (showAddDialog) {
        ContactEditDialog(
            contact = null,
            onDismiss = { showAddDialog = false },
            onSave = { name, phone, email, isFavorite, isPrivate ->
                viewModel.saveContact(
                    ContactEntity(
                        name = name,
                        phoneNumber = phone,
                        email = email,
                        isFavorite = isFavorite,
                        isPrivate = isPrivate
                    )
                )
                showAddDialog = false
            }
        )
    }

    // --- Edit Contact Dialog ---
    editingContact?.let { contact ->
        ContactEditDialog(
            contact = contact,
            onDismiss = { editingContact = null },
            onSave = { name, phone, email, isFavorite, isPrivate ->
                viewModel.saveContact(
                    contact.copy(
                        name = name,
                        phoneNumber = phone,
                        email = email,
                        isFavorite = isFavorite,
                        isPrivate = isPrivate
                    )
                )
                editingContact = null
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContactRow(
    contact: ContactEntity,
    isDeviceContact: Boolean = false,
    numbers: List<String> = ContactNumbers.list(contact.phoneNumber),
    expanded: Boolean = false,
    onToggleExpand: () -> Unit = {},
    onContactClick: () -> Unit,
    onCallClick: () -> Unit,
    onNumberCall: (String) -> Unit = {},
    onNumberCopy: (String) -> Unit = {},
    onCopyClick: () -> Unit,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(
                onClick = onContactClick,
                onLongClick = { showMenu = true }
            )
    ) {
        Column {
            Row(
                modifier = Modifier
                    .padding(12.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            if (contact.isFavorite) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.primaryContainer
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = contact.name.take(1).uppercase(),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = if (contact.isFavorite) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = contact.name,
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (contact.isFavorite) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = AppIcons.Star,
                                contentDescription = "Favorite",
                                tint = Color(0xFFF59E0B),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        if (isDeviceContact) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = "DEVICE",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = ContactNumbers.display(contact.phoneNumber),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (numbers.size > 1) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = "${numbers.size} numbers",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                }

                // The expander is deliberately a `Box` outside the header Row's
                // clickable area rather than a child of the Card's
                // `combinedClickable` — a nested clickable inside one gets
                // swallowed and the toggle never fires (same trap as the gallery
                // folder card).
                if (numbers.size > 1) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .clickable { onToggleExpand() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (expanded) AppIcons.ExpandLess else AppIcons.ExpandMore,
                            contentDescription = if (expanded) "Hide numbers" else "Show all ${numbers.size} numbers",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                IconButton(onClick = onCallClick) {
                    Icon(AppIcons.Call, contentDescription = "Call Options", tint = Color(0xFF16A34A))
                }

                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(AppIcons.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Copy Number") },
                            onClick = {
                                showMenu = false
                                onCopyClick()
                            },
                            leadingIcon = { Icon(AppIcons.ContentCopy, contentDescription = null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Edit Contact") },
                            onClick = {
                                showMenu = false
                                onEditClick()
                            },
                            leadingIcon = { Icon(AppIcons.Edit, contentDescription = null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete Contact") },
                            onClick = {
                                showMenu = false
                                onDeleteClick()
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = AppIcons.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        )
                    }
                }
            }

            // Expanded: one row per number, each independently callable and
            // copyable, so the user picks WHICH number instead of getting a
            // joined "0991, 0888" string handed to the dialer.
            if (expanded && numbers.size > 1) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                Column(modifier = Modifier.padding(start = 68.dp, end = 8.dp, bottom = 8.dp)) {
                    numbers.forEachIndexed { index, number ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = number,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = if (index == 0) "primary" else "alt ${index}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            IconButton(
                                onClick = { onNumberCopy(number) },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = AppIcons.ContentCopy,
                                    contentDescription = "Copy $number",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            IconButton(
                                onClick = { onNumberCall(number) },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = AppIcons.Call,
                                    contentDescription = "Call $number",
                                    tint = Color(0xFF16A34A),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ContactEditDialog(
    contact: ContactEntity?,
    onDismiss: () -> Unit,
    onSave: (name: String, phone: String, email: String, isFavorite: Boolean, isPrivate: Boolean) -> Unit
) {
    var name by remember { mutableStateOf(contact?.name ?: "") }
    var phone by remember { mutableStateOf(contact?.phoneNumber ?: "") }
    var email by remember { mutableStateOf(contact?.email ?: "") }
    var isFavorite by remember { mutableStateOf(contact?.isFavorite ?: false) }
    var isPrivate by remember { mutableStateOf(contact?.isPrivate ?: false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (contact == null) "New Contact" else "Edit Contact") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("Phone Number") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = isFavorite,
                        onClick = { isFavorite = !isFavorite },
                        label = { Text("Favorite") },
                        leadingIcon = {
                            Icon(
                                imageVector = if (isFavorite) AppIcons.Star else AppIcons.StarBorder,
                                contentDescription = null
                            )
                        }
                    )
                    FilterChip(
                        selected = isPrivate,
                        onClick = { isPrivate = !isPrivate },
                        label = { Text("Private") },
                        leadingIcon = {
                            Icon(
                                imageVector = if (isPrivate) AppIcons.Lock else AppIcons.LockOpen,
                                contentDescription = null
                            )
                        }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val cleanName = name.trim()
                    val cleanPhone = phone.trim()
                    if (cleanName.isNotEmpty() && cleanPhone.isNotEmpty()) {
                        onSave(cleanName, cleanPhone, email.trim(), isFavorite, isPrivate)
                    }
                },
                enabled = name.trim().isNotEmpty() && phone.trim().isNotEmpty()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun ContactDetailsPanel(
    contact: ContactEntity,
    isDeviceContact: Boolean,
    numbers: List<String> = ContactNumbers.list(contact.phoneNumber),
    onBack: () -> Unit,
    onCall: (String, Int) -> Unit,
    onMessage: (String) -> Unit,
    onShare: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    // Fall back to the raw field so a malformed value is still shown/dialable
    // rather than silently dropping the contact's only number.
    val dialableNumbers = numbers.ifEmpty {
        listOf(contact.phoneNumber.trim()).filter { it.isNotEmpty() }
    }
    val primaryNumber = dialableNumbers.first()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(AppIcons.ArrowBack, contentDescription = "Back")
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Contact Details",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onShare) {
                    Icon(AppIcons.Share, contentDescription = "Share Contact")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(
                        if (contact.isFavorite) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.primaryContainer
                    )
                    .align(Alignment.CenterHorizontally),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = contact.name.take(1).uppercase(),
                    style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                    color = if (contact.isFavorite) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.align(Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = contact.name,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground
                )
                if (contact.isFavorite) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = AppIcons.Star,
                        contentDescription = "Favorite",
                        tint = Color(0xFFF59E0B),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            if (isDeviceContact) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text(
                        text = "DEVICE CONTACT",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // One row per number. A contact with four numbers must be able to
            // call/message/copy any one of them, so nothing here ever passes the
            // joined "a, b, c" field to a dialer or an `smsto:` URI.
            dialableNumbers.forEachIndexed { index, number ->
                if (index > 0) Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        ContactInfoRow(
                            icon = AppIcons.Phone,
                            label = if (dialableNumbers.size > 1) {
                                if (index == 0) "Phone (primary)" else "Phone ${index + 1}"
                            } else {
                                "Phone"
                            },
                            value = number,
                            onClick = {
                                clipboardManager.setText(AnnotatedString(number))
                                Toast.makeText(context, "$number copied", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                    IconButton(
                        onClick = { onMessage(number) },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = AppIcons.Sms,
                            contentDescription = "Message $number",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(
                        onClick = { onCall(number, 1) },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = AppIcons.Call,
                            contentDescription = "Call $number on SIM 1",
                            tint = Color(0xFF16A34A),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            if (contact.email.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                ContactInfoRow(
                    icon = AppIcons.Email,
                    label = "Email",
                    value = contact.email,
                    onClick = {
                        clipboardManager.setText(AnnotatedString(contact.email))
                        Toast.makeText(context, "Email copied", Toast.LENGTH_SHORT).show()
                    }
                )
            }
            if (contact.groupName.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                ContactInfoRow(
                    icon = AppIcons.Group,
                    label = "Group",
                    value = contact.groupName,
                    onClick = null
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // SIM 1 and SIM 2 Call Buttons (primary number)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { onCall(primaryNumber, 1) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Icon(AppIcons.Call, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("SIM 1")
                }

                Button(
                    onClick = { onCall(primaryNumber, 2) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Icon(AppIcons.Call, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("SIM 2")
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedButton(
                onClick = { onMessage(primaryNumber) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Icon(AppIcons.Sms, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Send Message")
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onEdit,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(AppIcons.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Edit")
                }
                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(AppIcons.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            }

            if (!isDeviceContact) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Added ${SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(contact.createdAt))}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        }
    }
}

@Composable
private fun ContactInfoRow(
    icon: ImageVector,
    label: String,
    value: String,
    onClick: (() -> Unit)?
) {
    Card(
        onClick = { onClick?.invoke() },
        colors = CardDefaults.cardColors(
            containerColor = if (onClick != null) MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (onClick != null) Color(0xFF16A34A) else MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (onClick != null) {
                Icon(
                    imageVector = AppIcons.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
