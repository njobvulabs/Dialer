package com.example.ui.screens.files

import android.text.format.DateUtils
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.AppIcons
import com.example.ui.components.FloatingTopBar
import com.example.ui.MainViewModel
import com.example.util.RecycleBin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * B10: the Recycle bin.
 *
 * Deliberately read-mostly: the screen never holds the file list in Compose state
 * (walking a bin of multi-GB folders on the UI thread is how the old file manager
 * froze). `refreshKey` forces a re-read off the main thread after every mutation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecycleBinScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshKey by remember { mutableStateOf(0) }
    var entries by remember { mutableStateOf<List<RecycleBin.Entry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var totalBytes by remember { mutableStateOf(0L) }
    var quotaBytes by remember { mutableStateOf(0L) }
    var pendingDelete by remember { mutableStateOf<RecycleBin.Entry?>(null) }
    var confirmEmpty by remember { mutableStateOf(false) }

    LaunchedEffect(refreshKey) {
        loading = true
        val loaded = withContext(Dispatchers.IO) {
            Triple(RecycleBin.entries(), RecycleBin.totalBytes(), RecycleBin.quotaBytes(context))
        }
        entries = loaded.first
        totalBytes = loaded.second
        quotaBytes = loaded.third
        loading = false
    }

    fun afterMutation() {
        refreshKey++
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
        topBar = {
            val useGlassmorphism by viewModel.useGlassmorphism.collectAsState()
            FloatingTopBar(useGlass = useGlassmorphism) {
                Text(
                    "Recycle bin",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    modifier = Modifier.weight(1f)
                )
                if (entries.isNotEmpty()) {
                    TextButton(onClick = { confirmEmpty = true }) {
                        Icon(
                            AppIcons.DeleteForever, null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "Empty",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Usage line: how full the bin is against its quota, so "why did my
            // file disappear on its own" is answerable without a settings screen.
            if (quotaBytes > 0) {
                Text(
                    text = "${Formatter.formatFileSize(context, totalBytes)} of " +
                        "${Formatter.formatFileSize(context, quotaBytes)} used · " +
                        "oldest files are removed first when full",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }

            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (entries.isEmpty()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        AppIcons.Delete, null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("Bin is empty", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Files you delete are moved here first, so a mis-tap can be undone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(entries, key = { it.id }) { entry ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (entry.isDirectory) AppIcons.Folder else AppIcons.Delete,
                                null,
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    entry.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    buildString {
                                        if (!entry.isDirectory) {
                                            append(
                                                Formatter.formatFileSize(context, entry.displaySize)
                                            )
                                            append(" · ")
                                        }
                                        append(
                                            DateUtils.getRelativeTimeSpanString(
                                                entry.deletedAt,
                                                System.currentTimeMillis(),
                                                DateUtils.MINUTE_IN_MILLIS
                                            )
                                        )
                                        append(" · ")
                                        append(
                                            entry.originPath.substringAfterLast('/')
                                                .ifBlank { "root" }
                                        )
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = {
                                scope.launch {
                                    val ok = withContext(Dispatchers.IO) {
                                        RecycleBin.restore(entry)
                                    }
                                    afterMutation()
                                    Toast.makeText(
                                        context,
                                        if (ok) "Restored to its original folder"
                                        else "Could not restore — it may have been emptied",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }) {
                                Icon(
                                    AppIcons.Restore, "Restore",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("Restore", fontSize = 13.sp)
                            }
                            IconButton(onClick = { pendingDelete = entry }) {
                                Icon(
                                    AppIcons.DeleteForever, "Delete forever",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Delete one item from the bin, forever.
    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete forever?") },
            text = {
                Text("'${entry.name}' will be removed from the Recycle bin for good.")
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { RecycleBin.deleteForever(entry) }
                        pendingDelete = null
                        afterMutation()
                    }
                }) { Text("Delete forever", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }

    // Empty the whole bin.
    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text("Empty Recycle bin?") },
            text = {
                Text(
                    "${entries.size} item(s) will be permanently deleted. " +
                        "This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val n = withContext(Dispatchers.IO) { RecycleBin.emptyBin() }
                        confirmEmpty = false
                        afterMutation()
                        Toast.makeText(
                            context,
                            if (n > 0) "Emptied $n item(s)" else "Bin was already empty",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }) { Text("Empty", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmEmpty = false }) { Text("Cancel") }
            }
        )
    }
}
