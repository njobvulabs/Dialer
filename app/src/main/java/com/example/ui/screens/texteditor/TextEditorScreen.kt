package com.example.ui.screens.texteditor

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.MainViewModel
import com.example.ui.components.AppIcons
import com.example.ui.components.FloatingTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private fun displayNameForUri(context: Context, uri: Uri): String {
    val cursor = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) return it.getString(index).ifBlank { "Document.txt" }
        }
    }
    return "Document.txt"
}

private fun readUriText(context: Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
    }.getOrNull()

private fun writeUriText(context: Context, uri: Uri, text: String): Boolean =
    runCatching {
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: return false
        out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        true
    }.getOrDefault(false)

@Composable
fun TextEditorScreen(
    viewModel: MainViewModel? = null,
    pendingFile: File? = null,
    onPendingConsumed: () -> Unit = {},
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val useGlass = viewModel?.useGlassmorphism?.collectAsState()?.value ?: false
    var text by remember { mutableStateOf(TextFieldValue("")) }
    var savedText by remember { mutableStateOf("") }
    var currentFile by remember { mutableStateOf<File?>(null) }
    var currentUri by remember { mutableStateOf<Uri?>(null) }
    var displayName by remember { mutableStateOf("Untitled.txt") }
    var status by remember { mutableStateOf("") }
    var fontSize by remember { mutableFloatStateOf(14f) }
    var wordWrap by remember { mutableStateOf(true) }
    var showExitConfirm by remember { mutableStateOf(false) }
    var showNewConfirm by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState()

    val dirty = text.text != savedText
    val cursor = text.selection.start.coerceAtMost(text.text.length)
    val beforeCursor = text.text.substring(0, cursor)
    val line = beforeCursor.count { it == '\n' } + 1
    val column = cursor - beforeCursor.lastIndexOf('\n')

    fun loadFile(file: File) {
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
            }
            if (loaded != null) {
                text = TextFieldValue(loaded)
                savedText = loaded
                currentFile = file
                currentUri = null
                displayName = file.name
                status = "Opened ${file.name}"
            } else {
                status = "Could not open ${file.name}"
            }
        }
    }

    fun loadUri(uri: Uri) {
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { readUriText(context, uri) }
            if (loaded != null) {
                text = TextFieldValue(loaded)
                savedText = loaded
                currentFile = null
                currentUri = uri
                displayName = displayNameForUri(context, uri)
                status = "Opened $displayName"
            } else {
                status = "Could not open document"
            }
        }
    }

    fun saveToUri(uri: Uri) {
        scope.launch {
            val value = text.text
            val ok = withContext(Dispatchers.IO) { writeUriText(context, uri, value) }
            if (ok) {
                currentUri = uri
                currentFile = null
                displayName = displayNameForUri(context, uri)
                savedText = value
                status = "Saved $displayName"
            } else {
                status = "Save failed"
            }
        }
    }

    val saveAsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) saveToUri(uri)
    }

    fun save() {
        val file = currentFile
        val uri = currentUri
        if (file == null && uri == null) {
            saveAsLauncher.launch(displayName.ifBlank { "Untitled.txt" })
            return
        }
        scope.launch {
            val value = text.text
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    if (file != null) {
                        file.writeText(value, Charsets.UTF_8)
                        true
                    } else if (uri != null) {
                        writeUriText(context, uri, value)
                    } else {
                        false
                    }
                }.getOrDefault(false)
            }
            if (ok) {
                savedText = value
                status = "Saved $displayName"
            } else {
                status = "Save failed"
            }
        }
    }

    fun resetNew() {
        currentFile = null
        currentUri = null
        displayName = "Untitled.txt"
        text = TextFieldValue("")
        savedText = ""
        status = "New document"
    }

    fun requestClose() {
        if (dirty) showExitConfirm = true else onClose()
    }

    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadUri(uri)
    }

    LaunchedEffect(pendingFile) {
        if (pendingFile != null) {
            loadFile(pendingFile)
            onPendingConsumed()
        }
    }

    BackHandler { requestClose() }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
        topBar = {
            FloatingTopBar(useGlass = useGlass) {
                Text(
                    text = displayName + if (dirty) " *" else "",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1
                )
                IconButton(onClick = { openLauncher.launch(arrayOf("text/*", "application/json", "application/xml", "*/*")) }) {
                    Icon(AppIcons.FolderOpen, contentDescription = "Open file")
                }
                IconButton(onClick = ::save) {
                    Icon(AppIcons.Save, contentDescription = "Save")
                }
                IconButton(onClick = { saveAsLauncher.launch(displayName.ifBlank { "Untitled.txt" }) }) {
                    Icon(AppIcons.SaveAlt, contentDescription = "Save as")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { if (dirty) showNewConfirm = true else resetNew() }) {
                    Text("New")
                }
                FilterChip(selected = wordWrap, onClick = { wordWrap = !wordWrap }, label = { Text("Wrap") })
                IconButton(onClick = { fontSize = (fontSize - 1f).coerceAtLeast(10f) }) {
                    Icon(AppIcons.ZoomOut, contentDescription = "Smaller text")
                }
                Text("${fontSize.toInt()}sp", style = MaterialTheme.typography.labelMedium)
                IconButton(onClick = { fontSize = (fontSize + 1f).coerceAtMost(30f) }) {
                    Icon(AppIcons.ZoomIn, contentDescription = "Larger text")
                }
                Text(
                    "Ln $line, Col $column",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            }

            Surface(
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth().weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                        .verticalScroll(scrollState)
                        .then(if (wordWrap) Modifier else Modifier.horizontalScroll(horizontalScrollState))
                ) {
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp),
                        textStyle = LocalTextStyle.current.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = fontSize.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false
                        )
                    )
                }
            }

            Text(
                text = status.ifBlank { "Tip: open a file from the launcher or File Manager ⋮ menu." },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showExitConfirm) {
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            title = { Text("Unsaved changes") },
            text = { Text("Save changes to $displayName before closing?") },
            confirmButton = {
                TextButton(onClick = {
                    showExitConfirm = false
                    save()
                }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        showExitConfirm = false
                        onClose()
                    }) { Text("Discard", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { showExitConfirm = false }) { Text("Cancel") }
                }
            }
        )
    }

    if (showNewConfirm) {
        AlertDialog(
            onDismissRequest = { showNewConfirm = false },
            title = { Text("Unsaved changes") },
            text = { Text("Save changes to $displayName before starting a new document?") },
            confirmButton = {
                TextButton(onClick = {
                    showNewConfirm = false
                    save()
                }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        showNewConfirm = false
                        resetNew()
                    }) { Text("Discard", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { showNewConfirm = false }) { Text("Cancel") }
                }
            }
        )
    }
}
