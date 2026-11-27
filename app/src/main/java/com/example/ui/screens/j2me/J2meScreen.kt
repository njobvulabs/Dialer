package com.example.ui.screens.j2me

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.j2me.J2meApp
import com.example.j2me.J2meRuntime
import com.example.ui.MainViewModel
import com.example.ui.components.AppIcons
import com.example.ui.components.FloatingTopBar
import com.example.ui.components.landscapeContentWidth
import com.example.util.J2meRomStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun J2meScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val useGlass by viewModel.useGlassmorphism.collectAsState()
    var apps by remember { mutableStateOf(emptyList<J2meApp>()) }
    var busy by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<J2meApp?>(null) }

    suspend fun reload(): List<J2meApp> = withContext(Dispatchers.IO) { J2meRomStore.load(context) }

    LaunchedEffect(Unit) {
        apps = reload()
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { J2meRomStore.import(context, uris) }
            }
            apps = reload()
            busy = false
            val imported = result.getOrNull()
            if (imported == null) {
                val error = result.exceptionOrNull()
                val detail = error?.message?.takeIf { it.isNotBlank() }
                    ?: error?.javaClass?.simpleName
                    ?: "Use a J2ME .jar, .kjx, or a .jad with its .jar."
                Toast.makeText(context, "Import failed: $detail", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, "Added ${imported.name}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun launchApp(app: J2meApp) {
        J2meRomStore.touch(context, app.id)
        context.startActivity(J2meRuntime.launchIntent(context, app.id))
    }

    Column(modifier = Modifier.fillMaxSize()) {
        FloatingTopBar(useGlass = useGlass) {
            Text(
                text = "J2ME Games",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { importLauncher.launch(arrayOf("*/*")) }, enabled = !busy) {
                Icon(AppIcons.Add, contentDescription = "Import J2ME app")
            }
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .landscapeContentWidth()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Import Java ME games", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Supports .jar, .kjx, and .jad bundles. Select a .jad and its .jar together. No games are bundled.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = { importLauncher.launch(arrayOf("*/*")) }, enabled = !busy) {
                            Icon(AppIcons.Add, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (busy) "Importing..." else "Import game")
                        }
                    }
                }
            }
            if (apps.isEmpty()) {
                item {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(AppIcons.SportsEsports, contentDescription = null, modifier = Modifier.size(42.dp))
                            Spacer(Modifier.height(10.dp))
                            Text("No J2ME games imported yet", style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }
            }
            items(apps, key = { it.id }) { app ->
                Card(
                    onClick = { launchApp(app) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(AppIcons.Memory, contentDescription = null, modifier = Modifier.size(30.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(app.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val subtitle = listOf(app.vendor, app.version).filter { it.isNotBlank() }.joinToString(" · ")
                            Text(
                                subtitle.ifBlank { "Java ME app" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = { pendingDelete = app }) {
                            Icon(AppIcons.Delete, contentDescription = "Delete ${app.name}")
                        }
                        Button(onClick = { launchApp(app) }) { Text("Play") }
                    }
                }
            }
        }
    }

    pendingDelete?.let { app ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${app.name}?") },
            text = { Text("The app is moved to the Recycle Bin. Its saved data and settings are removed.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { J2meRomStore.delete(context, app) }
                        apps = reload()
                        if (!ok) Toast.makeText(context, "Could not move app to Recycle Bin", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }
}
