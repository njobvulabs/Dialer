package com.example.ui.screens.webapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.content.Context
import com.example.ui.MainViewModel
import com.example.ui.components.AppIcons
import com.example.ui.components.FloatingTopBar

/**
 * Web App Creator: manage the fixed isolated web-app slots
 * (`customweb1`..`customweb4`) the user can point at their own sites.
 */
@Composable
fun WebAppCreatorScreen(
    viewModel: MainViewModel,
    onLaunchApp: (String) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val useGlass by viewModel.useGlassmorphism.collectAsState()
    var customApps by remember { mutableStateOf(CustomWebApps.list(context)) }
    var showForm by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CustomWebApp?>(null) }
    var deleting by remember { mutableStateOf<CustomWebApp?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        FloatingTopBar(
            modifier = Modifier.fillMaxWidth(),
            useGlass = useGlass
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = AppIcons.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    text = "Web App Creator",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = "Your own web apps run in isolated browser profiles. Create up to ${CustomWebApps.SLOT_COUNT}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            for (n in 1..CustomWebApps.SLOT_COUNT) {
                val app = customApps.firstOrNull { it.id == CustomWebApps.slotId(n) }
                WebAppCreatorCard(
                    app = app,
                    onCreate = { showForm = true },
                    onEdit = { editing = it; showForm = true },
                    onClearSiteData = { target ->
                        GeckoWebAppHolder.release(target.id)
                        CustomWebApps.clearProfile(context, target.id)
                        customApps = CustomWebApps.list(context)
                    },
                    onDelete = { deleting = it },
                    onOpen = { if (app != null) onLaunchApp(app.id) }
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showForm) {
        WebAppFormDialog(
            initial = editing,
            context = context,
            slot = if (editing == null) {
                (1..CustomWebApps.SLOT_COUNT).firstOrNull { n ->
                    customApps.none { it.id == CustomWebApps.slotId(n) }
                } ?: CustomWebApps.SLOT_COUNT
            } else {
                CustomWebApps.slotIndex(editing!!.id) ?: CustomWebApps.SLOT_COUNT
            },
            onDismiss = { showForm = false },
            onSave = { app ->
                CustomWebApps.save(context, app)
                customApps = CustomWebApps.list(context)
                showForm = false
            }
        )
    }

    if (deleting != null) {
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(deleting?.name ?: "Delete web app") },
            text = {
                Text(
                    "Delete this web app? Its browser profile (site data / logins) will be removed. The app itself cannot be uninstalled from here."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleting?.let { app ->
                            CustomWebApps.delete(context, app.id)
                            CustomWebApps.profileDirFor(app.id)?.let { dir ->
                                java.io.File(context.filesDir, dir).delete()
                            }
                            customApps = CustomWebApps.list(context)
                        }
                        deleting = null
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun WebAppCreatorCard(
    app: CustomWebApp?,
    onCreate: () -> Unit,
    onEdit: (CustomWebApp) -> Unit,
    onClearSiteData: (CustomWebApp) -> Unit,
    onDelete: (CustomWebApp) -> Unit,
    onOpen: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = app?.name ?: "Empty slot ${app?.let { it.id }}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = app?.url ?: "Not created yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Row {
                if (app != null) {
                    TextButton(onClick = onOpen) { Text("Open") }
                    TextButton(onClick = { onEdit(app) }) { Text("Edit") }
                    TextButton(onClick = { onClearSiteData(app) }) { Text("Clear site data") }
                    TextButton(onClick = { onDelete(app) }) { Text("Delete") }
                } else {
                    TextButton(onClick = onCreate) { Text("Create") }
                }
            }
        }
    }
}

@Composable
private fun WebAppFormDialog(
    initial: CustomWebApp?,
    context: Context,
    slot: Int,
    onDismiss: () -> Unit,
    onSave: (CustomWebApp) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var url by remember { mutableStateOf(initial?.url ?: "") }
    var desktop by remember { mutableStateOf(initial?.desktop ?: true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New web app" else "Edit web app") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("URL") },
                    singleLine = true
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (desktop) "Desktop site" else "Mobile site",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (desktop) "Use a desktop user agent" else "Use a mobile user agent",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = desktop,
                        onCheckedChange = { desktop = it }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val nUrl = CustomWebApps.normalizeUrl(url)
                    if (nUrl != null) {
                        onSave(
                            CustomWebApp(
                                id = CustomWebApps.slotId(slot),
                                name = name.trim(),
                                url = nUrl,
                                desktop = desktop,
                                createdAt = initial?.createdAt ?: System.currentTimeMillis(),
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                    }
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
