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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.MainViewModel
import com.example.ui.components.AppConfirmDialog
import com.example.ui.components.AppIcons
import com.example.ui.components.AppSearchField
import com.example.ui.components.FloatingTopBar
import com.example.util.RecycleBin
import com.example.util.StorageHelper
import com.example.util.NewlineStyle
import com.example.util.TextEncoding
import com.example.util.TextFileCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private data class EditorDoc(
    val id: String,
    val displayName: String,
    val file: File?,
    val uri: Uri?,
    val text: TextFieldValue,
    val savedText: String,
    val encoding: TextEncoding,
    val hadBom: Boolean,
    val newline: NewlineStyle
)

private enum class PendingSaveAction { NONE, CLOSE, NEW }

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

private fun readBytes(context: Context, file: File?, uri: Uri?): ByteArray? = runCatching {
    when {
        file != null -> file.readBytes()
        uri != null -> context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        else -> null
    }
}.getOrNull()

private fun writeBytes(context: Context, file: File?, uri: Uri?, bytes: ByteArray): Boolean = runCatching {
    when {
        file != null -> {
            file.writeBytes(bytes)
            true
        }
        uri != null -> context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            out.write(bytes)
            true
        } ?: false
        else -> false
    }
}.getOrDefault(false)

private fun suggestedName(doc: EditorDoc): String {
    val base = doc.displayName.ifBlank { "Untitled" }.substringBeforeLast('.').ifBlank { "Untitled" }
    return "$base.txt"
}

private fun findRanges(source: String, query: String, matchCase: Boolean): List<IntRange> {
    if (query.isEmpty()) return emptyList()
    val out = mutableListOf<IntRange>()
    var start = 0
    while (start <= source.length) {
        val found = source.indexOf(query, start, ignoreCase = !matchCase)
        if (found < 0) break
        out += found until (found + query.length)
        start = found + query.length
    }
    return out
}

private class CodeHighlighter(
    private val extension: String,
    private val keywordColor: Color,
    private val stringColor: Color,
    private val commentColor: Color,
    private val numberColor: Color
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val builder = AnnotatedString.Builder(text)
        val source = text.text
        val keywords = when (extension.lowercase(Locale.getDefault())) {
            "json" -> setOf("true", "false", "null")
            "xml", "html" -> setOf("true", "false", "null")
            "sql" -> setOf("select", "from", "where", "insert", "update", "delete", "create", "table", "null")
            else -> setOf(
                "val", "var", "fun", "class", "object", "if", "else", "when", "for", "while", "return",
                "import", "package", "data", "sealed", "override", "private", "public", "true", "false", "null",
                "def", "let", "const", "int", "str", "float", "bool", "function", "export", "default", "new"
            )
        }
        fun apply(pattern: Regex, color: Color) {
            pattern.findAll(source).forEach { match ->
                if (!match.value.isBlank()) {
                    builder.addStyle(SpanStyle(color = color), match.range.first, match.range.last + 1)
                }
            }
        }
        apply(Regex("\"([^\"\\\\]|\\\\.)*\"|'([^'\\\\]|\\\\.)*'"), stringColor)
        apply(Regex("\\b\\d+(\\.\\d+)?\\b"), numberColor)
        apply(Regex("\\b(${keywords.joinToString("|")})\\b", RegexOption.IGNORE_CASE), keywordColor)
        apply(Regex("//[^\\n]*|#[^\\n]*|/\\*[\\s\\S]*?\\*/|<!--[\\s\\S]*?-->"), commentColor)
        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }
}

@Composable
fun TextEditorScreen(
    viewModel: MainViewModel? = null,
    pendingFile: File? = null,
    onPendingConsumed: () -> Unit = {},
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val useGlass = viewModel?.useGlassmorphism?.collectAsState()?.value ?: false
    val prefs = remember { context.getSharedPreferences("texteditor", Context.MODE_PRIVATE) }

    var fontSize by remember { mutableFloatStateOf(prefs.getFloat("font_size", 14f)) }
    var wordWrap by remember { mutableStateOf(prefs.getBoolean("word_wrap", true)) }
    var showLineNumbers by remember { mutableStateOf(prefs.getBoolean("line_numbers", false)) }
    var tabSize by remember { mutableIntStateOf(prefs.getInt("tab_size", 4)) }
    var autoIndent by remember { mutableStateOf(prefs.getBoolean("auto_indent", true)) }
    var syntaxHighlight by remember { mutableStateOf(prefs.getBoolean("syntax_highlight", true)) }
    var encoding by remember {
        mutableStateOf(
            runCatching { TextEncoding.valueOf(prefs.getString("encoding", TextEncoding.AUTO.name)!!) }
                .getOrDefault(TextEncoding.AUTO)
        )
    }
    var recentIds by remember {
        mutableStateOf(prefs.getString("recent", "").orEmpty().split(',').filter { it.isNotBlank() }.take(12))
    }

    val initialDraft = remember { prefs.getString("draft", "").orEmpty() }
    var docs by remember {
        mutableStateOf(
            listOf(
                EditorDoc(
                    id = "draft",
                    displayName = "Untitled.txt",
                    file = null,
                    uri = null,
                    text = TextFieldValue(initialDraft),
                    savedText = initialDraft,
                    encoding = TextEncoding.UTF8,
                    hadBom = false,
                    newline = NewlineStyle.LF
                )
            )
        )
    }
    var activeIndex by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("") }
    var loadSeq by remember { mutableIntStateOf(0) }

    var showFind by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var replaceText by remember { mutableStateOf("") }
    var matchCase by remember { mutableStateOf(false) }
    var currentMatch by remember { mutableIntStateOf(0) }
    var showGoToLine by remember { mutableStateOf(false) }
    var goToLineValue by remember { mutableStateOf("") }
    var showMenu by remember { mutableStateOf(false) }
    var showRecentMenu by remember { mutableStateOf(false) }
    var showEncodingMenu by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var renameValue by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showExitConfirm by remember { mutableStateOf(false) }
    var showNewConfirm by remember { mutableStateOf(false) }
    var pendingSaveAction by remember { mutableStateOf(PendingSaveAction.NONE) }

    val scrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState()
    val safeIndex = activeIndex.coerceIn(0, docs.lastIndex)
    val active = docs[safeIndex]
    val dirty = active.text.text != active.savedText
    val cursor = active.text.selection.start.coerceAtMost(active.text.text.length)
    val beforeCursor = active.text.text.substring(0, cursor)
    val line = beforeCursor.count { it == '\n' } + 1
    val column = cursor - beforeCursor.lastIndexOf('\n')
    val wordCount = active.text.text.split(Regex("\\s+")).count { it.isNotBlank() }
    val selectionCount = active.text.selection.end - active.text.selection.start
    val matches = remember(active.text.text, findQuery, matchCase) {
        findRanges(active.text.text, findQuery, matchCase)
    }
    val lineCount = active.text.text.count { it == '\n' } + 1

    LaunchedEffect(fontSize, wordWrap, showLineNumbers, tabSize, autoIndent, syntaxHighlight, encoding) {
        prefs.edit()
            .putFloat("font_size", fontSize)
            .putBoolean("word_wrap", wordWrap)
            .putBoolean("line_numbers", showLineNumbers)
            .putInt("tab_size", tabSize)
            .putBoolean("auto_indent", autoIndent)
            .putBoolean("syntax_highlight", syntaxHighlight)
            .putString("encoding", encoding.name)
            .apply()
    }

    LaunchedEffect(recentIds) {
        prefs.edit().putString("recent", recentIds.joinToString(",")).apply()
    }

    LaunchedEffect(active.id, active.text.text) {
        if (active.file == null && active.uri == null) {
            delay(400)
            prefs.edit().putString("draft", active.text.text).apply()
        }
    }

    LaunchedEffect(findQuery, matchCase) { currentMatch = 0 }

    fun replaceDoc(index: Int, doc: EditorDoc) {
        docs = docs.toMutableList().also { it[index.coerceIn(0, docs.lastIndex)] = doc }
    }

    fun recordRecent(id: String) {
        recentIds = (listOf(id) + recentIds.filterNot { it == id }).distinct().take(12)
    }

    fun updateActive(transform: (EditorDoc) -> EditorDoc) {
        replaceDoc(safeIndex, transform(docs[safeIndex]))
    }

    fun openDecoded(
        id: String,
        displayName: String,
        file: File?,
        uri: Uri?,
        bytes: ByteArray?,
        seq: Int
    ) {
        if (seq != loadSeq) return
        val decoded = bytes?.let { TextFileCodec.decode(it, encoding) }
        if (decoded == null) {
            status = "Could not read $displayName as text"
            return
        }
        val doc = EditorDoc(
            id = id,
            displayName = displayName,
            file = file,
            uri = uri,
            text = TextFieldValue(decoded.text),
            savedText = decoded.text,
            encoding = decoded.encoding,
            hadBom = decoded.hadBom,
            newline = decoded.newline
        )
        val existing = docs.indexOfFirst { it.id == id }
        if (existing >= 0) {
            replaceDoc(existing, doc)
            activeIndex = existing
        } else if (docs.size == 1 && docs[0].id == "draft" && docs[0].text.text.isBlank()) {
            replaceDoc(0, doc)
        } else {
            docs = docs + doc
            activeIndex = docs.lastIndex
        }
        recordRecent(id)
        status = "Opened $displayName"
    }

    fun loadFile(file: File) {
        val seq = ++loadSeq
        scope.launch {
            val bytes = withContext(Dispatchers.IO) { readBytes(context, file, null) }
            openDecoded(file.absolutePath, file.name, file, null, bytes, seq)
        }
    }

    fun loadUri(uri: Uri) {
        val seq = ++loadSeq
        scope.launch {
            val name = displayNameForUri(context, uri)
            val bytes = withContext(Dispatchers.IO) { readBytes(context, null, uri) }
            openDecoded(uri.toString(), name, null, uri, bytes, seq)
        }
    }

    fun saveDocument(index: Int, doc: EditorDoc, after: (Boolean) -> Unit) {
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                TextFileCodec.encode(doc.text.text, doc.encoding, doc.hadBom, doc.newline)
            }
            if (bytes == null) {
                withContext(Dispatchers.Main) { status = "Unsupported encoding" }
                after(false)
                return@launch
            }
            val ok = withContext(Dispatchers.IO) { writeBytes(context, doc.file, doc.uri, bytes) }
            if (ok) {
                replaceDoc(index, doc.copy(savedText = doc.text.text))
                recordRecent(doc.id)
            }
            withContext(Dispatchers.Main) { status = if (ok) "Saved ${doc.displayName}" else "Save failed" }
            after(ok)
        }
    }

    fun performPending(action: PendingSaveAction) {
        pendingSaveAction = PendingSaveAction.NONE
        when (action) {
            PendingSaveAction.CLOSE -> onClose()
            PendingSaveAction.NEW -> {
                val fresh = EditorDoc(
                    id = "draft_${System.currentTimeMillis()}",
                    displayName = "Untitled.txt",
                    file = null,
                    uri = null,
                    text = TextFieldValue(""),
                    savedText = "",
                    encoding = TextEncoding.UTF8,
                    hadBom = false,
                    newline = NewlineStyle.LF
                )
                replaceDoc(safeIndex, fresh)
                status = "New document"
            }
            PendingSaveAction.NONE -> Unit
        }
    }

    val saveAsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) {
            pendingSaveAction = PendingSaveAction.NONE
            return@rememberLauncherForActivityResult
        }
        val index = safeIndex
        val doc = docs[index]
        val updated = doc.copy(
            id = uri.toString(),
            displayName = displayNameForUri(context, uri),
            file = null,
            uri = uri
        )
        saveDocument(index, updated) { ok -> if (ok) performPending(pendingSaveAction) }
    }

    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadUri(uri)
    }

    fun requestSave(action: PendingSaveAction) {
        val index = safeIndex
        val doc = docs[index]
        if (doc.file == null && doc.uri == null) {
            pendingSaveAction = action
            saveAsLauncher.launch(suggestedName(doc))
        } else {
            saveDocument(index, doc) { ok -> if (ok) performPending(action) }
        }
    }

    LaunchedEffect(pendingFile) {
        if (pendingFile != null) {
            loadFile(pendingFile)
            onPendingConsumed()
        }
    }

    fun applyAutoIndent(value: TextFieldValue): TextFieldValue {
        if (!autoIndent) return value
        val old = docs[safeIndex].text.text
        if (value.text.length != old.length + 1) return value
        val caret = value.selection.start
        if (caret <= 0 || caret > value.text.length || value.text[caret - 1] != '\n') return value
        val lineStart = old.lastIndexOf('\n', (caret - 2).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        val previous = old.substring(lineStart, caret - 1)
        val indent = previous.takeWhile { it == ' ' || it == '\t' }.ifEmpty { " ".repeat(tabSize) }
        val inserted = value.text.substring(0, caret) + indent + value.text.substring(caret)
        return value.copy(text = inserted, selection = TextRange(caret + indent.length))
    }

    fun goToMatch(index: Int) {
        val range = matches.getOrNull(index) ?: return
        currentMatch = index
        updateActive { it.copy(text = it.text.copy(selection = TextRange(range.first, range.last + 1))) }
    }

    fun replaceCurrent() {
        val range = matches.getOrNull(currentMatch) ?: return
        updateActive { doc ->
            val replaced = doc.text.text.replaceRange(range, replaceText)
            doc.copy(text = doc.text.copy(text = replaced, selection = TextRange(range.first + replaceText.length)))
        }
        currentMatch = currentMatch.coerceAtMost(matches.lastIndex)
    }

    fun replaceAll() {
        if (matches.isEmpty()) return
        updateActive { doc ->
            var value = doc.text.text
            matches.asReversed().forEach { value = value.replaceRange(it, replaceText) }
            doc.copy(text = doc.text.copy(text = value, selection = TextRange(value.length)))
        }
    }

    fun changeEncoding(value: TextEncoding) {
        showEncodingMenu = false
        val index = safeIndex
        val doc = docs[index]
        if (dirty) {
            status = "Save changes before changing encoding"
            return
        }
        if (doc.file == null && doc.uri == null) {
            replaceDoc(index, doc.copy(encoding = value))
            return
        }
        scope.launch {
            val bytes = withContext(Dispatchers.IO) { readBytes(context, doc.file, doc.uri) }
            val decoded = bytes?.let { TextFileCodec.decode(it, value) }
            if (decoded == null) {
                withContext(Dispatchers.Main) { status = "Could not decode with ${value.label}" }
                return@launch
            }
            replaceDoc(
                index,
                doc.copy(
                    text = TextFieldValue(decoded.text),
                    savedText = decoded.text,
                    encoding = decoded.encoding,
                    hadBom = decoded.hadBom
                )
            )
            withContext(Dispatchers.Main) { status = "Encoding: ${decoded.encoding.label}" }
        }
    }

    fun renameActive() {
        val doc = docs[safeIndex]
        val file = doc.file
        if (file == null) {
            status = "Rename is available for files opened from storage"
            showRename = false
            return
        }
        val name = renameValue.trim()
        if (name.isBlank() || name.contains('/') || name.contains('\\') || name.contains('\u0000')) {
            status = "Invalid file name"
            return
        }
        val ok = StorageHelper.renameFile(file, name)
        if (ok) {
            val renamed = File(file.parentFile, name)
            replaceDoc(safeIndex, doc.copy(id = renamed.absolutePath, file = renamed, displayName = name))
            recordRecent(renamed.absolutePath)
            status = "Renamed to $name"
        } else {
            status = "Rename failed"
        }
        showRename = false
    }

    fun deleteActive() {
        val doc = docs[safeIndex]
        val file = doc.file
        if (file == null) {
            status = "Delete is available for files opened from storage"
            return
        }
        val ok = runCatching { RecycleBin.trash(context, file) != null }.getOrDefault(false)
        if (ok) {
            val remaining = docs.toMutableList().also { it.removeAt(safeIndex) }
            docs = if (remaining.isEmpty()) {
                listOf(
                    EditorDoc(
                        id = "draft",
                        displayName = "Untitled.txt",
                        file = null,
                        uri = null,
                        text = TextFieldValue(""),
                        savedText = "",
                        encoding = TextEncoding.UTF8,
                        hadBom = false,
                        newline = NewlineStyle.LF
                    )
                )
            } else remaining
            activeIndex = 0
            status = "Moved ${doc.displayName} to Recycle Bin"
        } else {
            status = "Could not move file to Recycle Bin"
        }
        showDeleteConfirm = false
    }

    BackHandler {
        when {
            showFind -> {
                showFind = false
                keyboard?.hide()
            }
            showMenu || showRecentMenu || showEncodingMenu -> {
                showMenu = false
                showRecentMenu = false
                showEncodingMenu = false
            }
            dirty -> showExitConfirm = true
            else -> onClose()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
        topBar = {
            FloatingTopBar(useGlass = useGlass) {
                Text(
                    text = active.displayName + if (dirty) " *" else "",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(onClick = { showFind = !showFind }) {
                    Icon(AppIcons.Search, contentDescription = "Find and replace")
                }
                IconButton(onClick = { openLauncher.launch(arrayOf("text/*", "application/json", "application/xml", "application/javascript", "application/x-shellscript", "application/sql", "application/yaml", "application/toml")) }) {
                    Icon(AppIcons.FolderOpen, contentDescription = "Open file")
                }
                IconButton(onClick = { requestSave(PendingSaveAction.NONE) }) {
                    Icon(AppIcons.Save, contentDescription = "Save")
                }
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(AppIcons.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("New tab") },
                            onClick = {
                                showMenu = false
                                docs = docs + EditorDoc(
                                    id = "draft_${System.currentTimeMillis()}",
                                    displayName = "Untitled.txt",
                                    file = null,
                                    uri = null,
                                    text = TextFieldValue(""),
                                    savedText = "",
                                    encoding = TextEncoding.UTF8,
                                    hadBom = false,
                                    newline = NewlineStyle.LF
                                )
                                activeIndex = docs.lastIndex
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Close tab") },
                            onClick = {
                                showMenu = false
                                if (docs.size == 1) {
                                    if (dirty) showExitConfirm = true else onClose()
                                } else {
                                    docs = docs.toMutableList().also { it.removeAt(safeIndex) }
                                    activeIndex = (safeIndex - 1).coerceAtLeast(0)
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Recent") },
                            onClick = { showMenu = false; showRecentMenu = true }
                        )
                        DropdownMenuItem(
                            text = { Text("Encoding: ${active.encoding.label}") },
                            onClick = { showMenu = false; showEncodingMenu = true }
                        )
                        DropdownMenuItem(
                            text = { Text("Go to line") },
                            onClick = {
                                showMenu = false
                                goToLineValue = line.toString()
                                showGoToLine = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            onClick = {
                                showMenu = false
                                renameValue = active.displayName
                                showRename = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete to Recycle Bin") },
                            onClick = { showMenu = false; showDeleteConfirm = true }
                        )
                    }
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                docs.forEachIndexed { index, doc ->
                    FilterChip(
                        selected = index == safeIndex,
                        onClick = { activeIndex = index },
                        label = { Text(doc.displayName, maxLines = 1) }
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                TextButton(onClick = { if (dirty) showNewConfirm = true else performPending(PendingSaveAction.NEW) }) {
                    Text("New")
                }
                FilterChip(selected = wordWrap, onClick = { wordWrap = !wordWrap }, label = { Text("Wrap") })
                FilterChip(
                    selected = showLineNumbers && !wordWrap,
                    enabled = !wordWrap,
                    onClick = { showLineNumbers = !showLineNumbers },
                    label = { Text("Numbers") }
                )
                FilterChip(selected = autoIndent, onClick = { autoIndent = !autoIndent }, label = { Text("Indent") })
                FilterChip(selected = syntaxHighlight, onClick = { syntaxHighlight = !syntaxHighlight }, label = { Text("Color") })
                IconButton(onClick = { fontSize = (fontSize - 1f).coerceAtLeast(10f) }) {
                    Icon(AppIcons.ZoomOut, contentDescription = "Smaller text")
                }
                Text("${fontSize.toInt()}sp", style = MaterialTheme.typography.labelMedium)
                IconButton(onClick = { fontSize = (fontSize + 1f).coerceAtMost(30f) }) {
                    Icon(AppIcons.ZoomIn, contentDescription = "Larger text")
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Ln $line, Col $column", style = MaterialTheme.typography.labelSmall)
                Text("$wordCount words", style = MaterialTheme.typography.labelSmall)
                Text("${active.text.text.length} chars", style = MaterialTheme.typography.labelSmall)
                if (selectionCount > 0) {
                    Text("$selectionCount selected", style = MaterialTheme.typography.labelSmall)
                }
                Text("Tab $tabSize", style = MaterialTheme.typography.labelSmall)
                Text(
                    active.encoding.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
            }

            if (showFind) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AppSearchField(
                        value = findQuery,
                        onValueChange = { findQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "Find",
                        onClose = { showFind = false; keyboard?.hide() }
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = replaceText,
                            onValueChange = { replaceText = it },
                            label = { Text("Replace") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(selected = matchCase, onClick = { matchCase = !matchCase }, label = { Text("Aa") })
                        TextButton(onClick = { if (matches.isNotEmpty()) goToMatch((currentMatch - 1).let { if (it < 0) matches.lastIndex else it }) }) {
                            Text("Prev")
                        }
                        TextButton(onClick = { if (matches.isNotEmpty()) goToMatch((currentMatch + 1) % matches.size) }) {
                            Text("Next")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${matches.size} matches", style = MaterialTheme.typography.labelSmall)
                        TextButton(onClick = ::replaceCurrent, enabled = matches.isNotEmpty()) { Text("Replace") }
                        TextButton(onClick = ::replaceAll, enabled = matches.isNotEmpty()) { Text("Replace all") }
                    }
                }
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
                    Row {
                        if (showLineNumbers && !wordWrap) {
                            Column(modifier = Modifier.padding(end = 8.dp)) {
                                repeat(lineCount) { index ->
                                    Text(
                                        text = (index + 1).toString(),
                                        fontSize = fontSize.sp,
                                        lineHeight = (fontSize * 1.45f).sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        BasicTextField(
                            value = active.text,
                            onValueChange = { updateActive { doc -> doc.copy(text = applyAutoIndent(it)) } },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp),
                            textStyle = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = fontSize.sp,
                                lineHeight = (fontSize * 1.45f).sp,
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.None,
                                autoCorrectEnabled = false
                            ),
                            visualTransformation = if (syntaxHighlight) {
                                CodeHighlighter(
                                    extension = active.displayName.substringAfterLast('.', ""),
                                    keywordColor = MaterialTheme.colorScheme.primary,
                                    stringColor = MaterialTheme.colorScheme.tertiary,
                                    commentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    numberColor = MaterialTheme.colorScheme.secondary
                                )
                            } else VisualTransformation.None
                        )
                    }
                }
            }

            Text(
                text = status.ifBlank { "Tip: open a text file from the File Manager per-file More menu." },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showRecentMenu) {
        AlertDialog(
            onDismissRequest = { showRecentMenu = false },
            title = { Text("Recent files") },
            text = {
                Column {
                    if (recentIds.isEmpty()) Text("No recent files")
                    recentIds.forEach { id ->
                        TextButton(
                            onClick = {
                                showRecentMenu = false
                                if (id.startsWith("content://")) loadUri(Uri.parse(id)) else loadFile(File(id))
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(id.substringAfterLast('/'), maxLines = 1)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showRecentMenu = false }) { Text("Close") } }
        )
    }

    if (showEncodingMenu) {
        AlertDialog(
            onDismissRequest = { showEncodingMenu = false },
            title = { Text("Encoding") },
            text = {
                Column {
                    TextEncoding.entries.forEach { value ->
                        TextButton(onClick = { changeEncoding(value) }, modifier = Modifier.fillMaxWidth()) {
                            Text(value.label)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showEncodingMenu = false }) { Text("Cancel") } }
        )
    }

    if (showGoToLine) {
        AlertDialog(
            onDismissRequest = { showGoToLine = false },
            title = { Text("Go to line") },
            text = {
                OutlinedTextField(
                    value = goToLineValue,
                    onValueChange = { goToLineValue = it },
                    label = { Text("Line number") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = goToLineValue.toIntOrNull()?.coerceIn(1, lineCount)
                    if (target != null) {
                        val offset = active.text.text.split('\n')
                            .take(target - 1)
                            .sumOf { it.length + 1 }
                            .coerceAtMost(active.text.text.length)
                        updateActive { it.copy(text = it.text.copy(selection = TextRange(offset))) }
                    }
                    showGoToLine = false
                }) { Text("Go") }
            },
            dismissButton = { TextButton(onClick = { showGoToLine = false }) { Text("Cancel") } }
        )
    }

    if (showRename) {
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Rename file") },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    label = { Text("New name") },
                    singleLine = true
                )
            },
            confirmButton = { TextButton(onClick = ::renameActive) { Text("Rename") } },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("Cancel") } }
        )
    }

    if (showDeleteConfirm) {
        AppConfirmDialog(
            title = "Delete file?",
            message = "Move ${active.displayName} to the Recycle Bin?",
            confirmLabel = "Delete",
            onConfirm = ::deleteActive,
            onDismiss = { showDeleteConfirm = false }
        )
    }

    if (showExitConfirm) {
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            title = { Text("Unsaved changes") },
            text = { Text("Save changes to ${active.displayName} before closing?") },
            confirmButton = {
                TextButton(onClick = {
                    showExitConfirm = false
                    requestSave(PendingSaveAction.CLOSE)
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
            text = { Text("Save changes to ${active.displayName} before starting a new document?") },
            confirmButton = {
                TextButton(onClick = {
                    showNewConfirm = false
                    requestSave(PendingSaveAction.NEW)
                }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        showNewConfirm = false
                        performPending(PendingSaveAction.NEW)
                    }) { Text("Discard", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { showNewConfirm = false }) { Text("Cancel") }
                }
            }
        )
    }
}
