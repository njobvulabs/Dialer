package com.example.ui.screens.bible

import com.example.ui.components.landscapeContentWidth
import com.example.ui.components.AppIcons
import com.example.ui.components.AppSearchField
import com.example.ui.components.FloatingTopBar
import com.example.ui.theme.AppCornerRadius
import com.example.ui.MainViewModel
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap

data class BibleBook(val name: String, val abbrev: String, val chapters: List<List<String>>)

object BibleData {
    private val cache = ConcurrentHashMap<String, List<BibleBook>>()

    fun load(context: Context, translation: String = "kjv"): List<BibleBook> {
        cache[translation]?.let { return it }
        synchronized(this) {
            cache[translation]?.let { return it }
            val raw = context.assets.open("bible_$translation.json").bufferedReader().use { it.readText() }
            val arr = JSONArray(raw)
            val books = ArrayList<BibleBook>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val chArr = o.getJSONArray("chapters")
                val chapters = ArrayList<List<String>>(chArr.length())
                for (c in 0 until chArr.length()) {
                    val vArr = chArr.getJSONArray(c)
                    val verses = ArrayList<String>(vArr.length())
                    for (v in 0 until vArr.length()) verses.add(vArr.getString(v))
                    chapters.add(verses)
                }
                books.add(BibleBook(o.getString("name"), o.optString("abbrev"), chapters))
            }
            cache[translation] = books
            return books
        }
    }
}

private data class TranslationOption(val id: String, val label: String)

private val translationOptions = listOf(
    TranslationOption("kjv", "English (KJV)"),
    TranslationOption("nya", "Chichewa (Mawu a Mulungu)")
)

private fun translationTitle(id: String): String = when (id) {
    "nya" -> "Mawu a Mulungu (Chichewa)"
    else -> "Holy Bible (KJV)"
}

private fun translationLabel(id: String): String =
    translationOptions.firstOrNull { it.id == id }?.label ?: "KJV"

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BibleScreen(onClose: () -> Unit, viewModel: MainViewModel) {
    val context = LocalContext.current

    val useGlassmorphism by viewModel.useGlassmorphism.collectAsState()

    var books by remember { mutableStateOf<List<BibleBook>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }

    // Active translation: "kjv" or "nya". Persisted; the screen reloads on change.
    var translation by remember { mutableStateOf(loadTranslation(context)) }
    var showTranslationMenu by remember { mutableStateOf(false) }

    // navigation: null book = home; chapter = -1 shows chapter picker
    var openBook by remember { mutableStateOf<BibleBook?>(null) }
    var openChapter by remember { mutableIntStateOf(-1) }
    var searchQuery by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<Triple<BibleBook, Int, Int>>>(emptyList()) }
    var showSearch by remember { mutableStateOf(false) }
    var bookmarks by remember { mutableStateOf(loadBookmarks(context, translation)) }
    var selectedVerse by remember { mutableStateOf<Pair<Int, Int>?>(null) } // chapter to verse index
    var fontSize by remember { mutableFloatStateOf(loadFontSize(context)) }
    var showTextSize by remember { mutableStateOf(false) }
    var expandedFootnote by remember { mutableStateOf<Int?>(null) }
    var popupVerse by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(translation) {
        books = null
        loadError = null
        bookmarks = loadBookmarks(context, translation)
        withContext(Dispatchers.IO) {
            runCatching { BibleData.load(context, translation) }.onSuccess { books = it }
                .onFailure { loadError = it.message }
        }
    }

    // Only intercept Back once something is open; at Home this is disabled so
    // MainActivity's immersive BackHandler closes the Bible back to the pager.
    BackHandler(enabled = showSearch || openBook != null || openChapter >= 0) {
        when {
            showSearch && openBook == null -> {
                showSearch = false; results = emptyList(); searchQuery = ""
            }
            openChapter >= 0 -> { openChapter = -1; selectedVerse = null }
            openBook != null -> openBook = null
        }
    }

    // Live search: debounce keystrokes so typing does not rescan the whole text
    // on every character. Capped at 200 matches.
    LaunchedEffect(searchQuery, showSearch) {
        val current = books
        val q = searchQuery.trim().lowercase()
        if (!showSearch || current == null || q.length < 2) {
            results = emptyList()
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(300)
        results = withContext(Dispatchers.Default) {
            val out = mutableListOf<Triple<BibleBook, Int, Int>>()
            current.forEach { b ->
                b.chapters.forEachIndexed { ci, ch ->
                    ch.forEachIndexed { vi, verse ->
                        if (verse.lowercase().contains(q)) {
                            if (out.size < 200) out.add(Triple(b, ci, vi))
                        }
                    }
                }
            }
            out
        }
        searching = false
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            FloatingTopBar(useGlass = useGlassmorphism) {
                Text(
                    when {
                        openChapter >= 0 && openBook != null ->
                            "${openBook!!.name} ${openChapter + 1}"
                        openBook != null -> openBook!!.name
                        else -> translationTitle(translation)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { showTextSize = true }) {
                    Icon(AppIcons.FormatSize, contentDescription = "Text size")
                }
                if (openBook == null) {
                    Box {
                        IconButton(onClick = { showTranslationMenu = true }) {
                            Icon(
                                AppIcons.Translate,
                                contentDescription = "Change translation",
                                tint = if (translation != "kjv") MaterialTheme.colorScheme.primary
                                else LocalContentColor.current
                            )
                        }
                        DropdownMenu(
                            expanded = showTranslationMenu,
                            onDismissRequest = { showTranslationMenu = false }
                        ) {
                            translationOptions.forEach { opt ->
                                DropdownMenuItem(
                                    text = { Text(opt.label) },
                                    leadingIcon = {
                                        Icon(
                                            if (translation == opt.id) AppIcons.CheckCircle
                                            else AppIcons.RadioButtonUnchecked,
                                            contentDescription = null,
                                            tint = if (translation == opt.id) MaterialTheme.colorScheme.primary
                                            else LocalContentColor.current
                                        )
                                    },
                                    onClick = {
                                        showTranslationMenu = false
                                        if (opt.id != translation) {
                                            translation = opt.id
                                            saveTranslation(context, opt.id)
                                            openBook = null
                                            openChapter = -1
                                            selectedVerse = null
                                            results = emptyList()
                                            searchQuery = ""
                                        }
                                    }
                                )
                            }
                        }
                    }
                    IconButton(onClick = {
                        showSearch = !showSearch
                        if (!showSearch) { results = emptyList(); searchQuery = "" }
                    }) {
                        Icon(
                            AppIcons.Search, contentDescription = "Search",
                            tint = if (showSearch) MaterialTheme.colorScheme.primary else LocalContentColor.current
                        )
                    }
                }
            }

            if (loadError != null) {
                Text(
                    "Failed to load bible: $loadError",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            if (showTextSize) {
                TextSizeDialog(
                    fontSize = fontSize,
                    onFontSize = { fontSize = it },
                    onDismiss = { saveFontSize(context, fontSize); showTextSize = false }
                )
            }

            // ---- SEARCH ----
            if (showSearch && openBook == null) {
                AppSearchField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = "Search words or phrases...",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                )
                if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (!searching && searchQuery.trim().length >= 2) {
                    Text(
                        if (results.isEmpty()) "No matches" else "${results.size} matches",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                    )
                }
                LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                    items(results, key = { "${it.first.abbrev}-${it.second}-${it.third}" }) { r ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    openBook = r.first
                                    openChapter = r.second
                                    selectedVerse = r.third to r.third
                                    showSearch = false
                                }
                                .padding(vertical = 6.dp)
                        ) {
                            Text(
                                "${r.first.name} ${r.second + 1}:${r.third + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                r.first.chapters[r.second][r.third],
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2
                            )
                        }
                    }
                }
                return@Column
            }

            val currentBooks = books
            if (currentBooks == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(10.dp))
                        Text("Loading bible...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                return@Column
            }

            // ---- CHAPTER VIEW ----
            if (openBook != null && openChapter >= 0) {
                val b = openBook!!
                val verses = b.chapters[openChapter]
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(enabled = openChapter > 0, onClick = {
                        openChapter--; selectedVerse = null
                        saveLastRead(context, translation, b.abbrev, openChapter)
                    }) { Text("← Prev") }
                    TextButton(enabled = openChapter < b.chapters.size - 1, onClick = {
                        openChapter++; selectedVerse = null
                        saveLastRead(context, translation, b.abbrev, openChapter)
                    }) { Text("Next →") }
                }
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 90.dp)
                ) {
                    items((verses.indices).toList()) { vi ->
                        val isSel = selectedVerse?.first == vi || selectedVerse?.second == vi
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(AppCornerRadius.sm)
                                .background(
                                    if (isSel) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                    else Color.Transparent
                                )
                                .combinedClickable(
                                    onClick = {},
                                    onLongClick = { popupVerse = vi }
                                )
                                .padding(8.dp)
                        ) {
                            Row {
                                Text(
                                    "${vi + 1} ",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                val verseText = verses[vi]
                                val footnoteRegex = Regex("\\{([^}]*)\\}")
                                val hasFootnotes = footnoteRegex.containsMatchIn(verseText)
                                if (hasFootnotes) {
                                    val annotated = buildAnnotatedString {
                                        var lastEnd = 0
                                        footnoteRegex.findAll(verseText).forEach { match ->
                                            append(verseText.substring(lastEnd, match.range.first))
                                            pushStringAnnotation("FOOTNOTE", match.groupValues[1])
                                            withStyle(SpanStyle(
                                                fontSize = (fontSize * 0.7).sp,
                                                color = MaterialTheme.colorScheme.primary,
                                                fontWeight = FontWeight.Bold
                                            )) { append("†") }
                                            pop()
                                            lastEnd = match.range.last + 1
                                        }
                                        if (lastEnd < verseText.length) {
                                            append(verseText.substring(lastEnd))
                                        }
                                    }
                                    Text(
                                        annotated,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontSize = fontSize.sp
                                    )
                                    if (expandedFootnote == vi) {
                                        val footnotes = footnoteRegex.findAll(verseText)
                                            .map { it.groupValues[1] }.toList()
                                        if (footnotes.isNotEmpty()) {
                                            Surface(
                                                color = MaterialTheme.colorScheme.secondaryContainer,
                                                shape = MaterialTheme.shapes.small,
                                                modifier = Modifier.padding(top = 4.dp)
                                            ) {
                                                Column(Modifier.padding(8.dp)) {
                                                    footnotes.forEachIndexed { i, fn ->
                                                        if (i > 0) Spacer(Modifier.height(4.dp))
                                                        Text(
                                                            "† $fn",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onSecondaryContainer
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    Text(
                                        verseText,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontSize = fontSize.sp
                                    )
                                }
                            }
                        }
                    }
                }

                val versePopup = popupVerse
                if (versePopup != null) {
                    val sel = selectedVerse
                    val marked = Triple(b.abbrev, openChapter, versePopup) in bookmarks
                    AlertDialog(
                        onDismissRequest = { popupVerse = null },
                        title = {
                            Text(
                                "${b.name} ${openChapter + 1}:${versePopup + 1}",
                                fontWeight = FontWeight.Bold
                            )
                        },
                        text = {
                            Text(
                                verses.getOrElse(versePopup) { "" },
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        confirmButton = {
                            Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 8.dp)) {
                                val isSel = sel?.first == versePopup || sel?.second == versePopup
                                TextButton(
                                    onClick = {
                                        selectedVerse = if (isSel) null else versePopup to versePopup
                                        popupVerse = null
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(
                                        if (isSel) AppIcons.CheckCircle else AppIcons.RadioButtonUnchecked,
                                        null,
                                        Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(if (isSel) "Selection off" else "Toggle selection", fontSize = 14.sp)
                                }
                                TextButton(
                                    onClick = {
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                            as android.content.ClipboardManager
                                        cm.setPrimaryClip(
                                            android.content.ClipData.newPlainText(
                                                "verse",
                                                "\"${verses.getOrElse(versePopup) { "" }}\" — ${b.name} ${openChapter + 1}:${versePopup + 1}"
                                            )
                                        )
                                        Toast.makeText(context, "Verse copied", Toast.LENGTH_SHORT).show()
                                        popupVerse = null
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(AppIcons.ContentCopy, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Copy", fontSize = 14.sp)
                                }
                                TextButton(
                                    onClick = {
                                        val key = Triple(b.abbrev, openChapter, versePopup)
                                        bookmarks = if (key in bookmarks) bookmarks - key else bookmarks + key
                                        saveBookmarks(context, translation, bookmarks)
                                        popupVerse = null
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(
                                        if (marked) AppIcons.Bookmark else AppIcons.BookmarkBorder,
                                        null,
                                        Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(if (marked) "Remove bookmark" else "Bookmark verse", fontSize = 14.sp)
                                }
                                if (verses.getOrNull(versePopup)?.contains("{") == true) {
                                    TextButton(
                                        onClick = {
                                            expandedFootnote = if (expandedFootnote == versePopup) null else versePopup
                                            popupVerse = null
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(AppIcons.Info, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                        Spacer(Modifier.width(8.dp))
                                        Text(if (expandedFootnote == versePopup) "Hide footnotes" else "Show footnotes", fontSize = 14.sp)
                                    }
                                }
                                TextButton(
                                    onClick = { popupVerse = null },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Cancel", fontSize = 14.sp)
                                }
                            }
                        }
                    )
                }
                return@Column
            }

            // ---- BOOK / CHAPTER PICKER ----
            if (openBook != null) {
                val b = openBook!!
                LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize().landscapeContentWidth()
                ) {
                    items((b.chapters.indices).toList()) { ci ->
                        Surface(
                            onClick = {
                                openChapter = ci
                                saveLastRead(context, translation, b.abbrev, ci)
                            },
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        ) {
                            Box(Modifier.padding(10.dp), contentAlignment = Alignment.Center) {
                                Text("${ci + 1}", fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
                return@Column
            }

            // ---- HOME: continue reading + all books + bookmarks ----
            val last = remember(translation) { loadLastRead(context, translation) }
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 20.dp)) {
                last?.let { (abbrev, chIdx) ->
                    val lb = currentBooks.find { it.abbrev == abbrev }
                    if (lb != null) {
                        item {
                            Surface(
                                onClick = { openBook = lb; openChapter = chIdx },
                                shape = AppCornerRadius.md,
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                            ) {
                                Column(Modifier.padding(14.dp)) {
                                    Text("Continue reading", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary)
                                    Text("${lb.name} ${chIdx + 1}", fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
                item {
                    Text("Old Testament", style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                }
                items(currentBooks.take(39)) { b ->
                    BookRow(b) { openBook = b; openChapter = -1 }
                }
                item {
                    Text("New Testament", style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                }
                items(currentBooks.drop(39)) { b ->
                    BookRow(b) { openBook = b; openChapter = -1 }
                }
                if (bookmarks.isNotEmpty()) {
                    item {
                        Text("Bookmarked verses (${bookmarks.size})",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                    }
                    items(bookmarks.toList()) { key ->
                        val b = currentBooks.find { it.abbrev == key.first }
                        if (b != null) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        openBook = b; openChapter = key.second
                                        selectedVerse = key.third to key.third
                                    }
                                    .padding(vertical = 4.dp)
                            ) {
                                Text("${b.name} ${key.second + 1}:${key.third + 1}",
                                    fontWeight = FontWeight.Medium,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary)
                                Text(
                                    b.chapters.getOrNull(key.second)?.getOrNull(key.third) ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontStyle = FontStyle.Italic,
                                    maxLines = 2
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
private fun TextSizeDialog(
    fontSize: Float,
    onFontSize: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Text size", fontWeight = FontWeight.Bold) },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("A", fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp))
                Slider(
                    value = fontSize,
                    onValueChange = onFontSize,
                    valueRange = 12f..28f,
                    modifier = Modifier.weight(1f)
                )
                Text("A", fontSize = 22.sp, modifier = Modifier.padding(start = 8.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

@Composable
private fun BookRow(b: BibleBook, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(b.name, modifier = Modifier.weight(1f))
        Text("${b.chapters.size} ch", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun loadBookmarks(context: Context, translation: String): Set<Triple<String, Int, Int>> = runCatching {
    val prefs = context.getSharedPreferences("bible", Context.MODE_PRIVATE)
    // Legacy prefs key holds KJV bookmarks from before translations existed.
    val key = if (translation == "kjv") {
        if (prefs.contains("bookmarks_$translation")) "bookmarks_$translation" else "bookmarks"
    } else {
        "bookmarks_$translation"
    }
    val arr = JSONArray(prefs.getString(key, "[]") ?: "[]")
    (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        Triple(o.getString("b"), o.getInt("c"), o.getInt("v"))
    }.toSet()
}.getOrDefault(emptySet())

private fun saveBookmarks(context: Context, translation: String, set: Set<Triple<String, Int, Int>>) {
    val arr = JSONArray()
    set.forEach { (b, c, v) -> arr.put(org.json.JSONObject().put("b", b).put("c", c).put("v", v)) }
    context.getSharedPreferences("bible", Context.MODE_PRIVATE)
        .edit().putString("bookmarks_$translation", arr.toString()).apply()
}

private fun loadLastRead(context: Context, translation: String): Pair<String, Int>? = runCatching {
    val prefs = context.getSharedPreferences("bible", Context.MODE_PRIVATE)
    // Legacy unprefixed keys hold the KJV position from before translations existed.
    val bk = if (translation == "kjv") {
        prefs.getString("last_book_$translation", null) ?: prefs.getString("last_book", null)
    } else {
        prefs.getString("last_book_$translation", null)
    } ?: return null
    val chKey = if (prefs.contains("last_chapter_$translation")) "last_chapter_$translation" else "last_chapter"
    bk to prefs.getInt(chKey, 0)
}.getOrNull()

private fun saveLastRead(context: Context, translation: String, abbrev: String, chapter: Int) {
    context.getSharedPreferences("bible", Context.MODE_PRIVATE).edit()
        .putString("last_book_$translation", abbrev)
        .putInt("last_chapter_$translation", chapter).apply()
}

private fun loadTranslation(context: Context): String = runCatching {
    context.getSharedPreferences("bible", Context.MODE_PRIVATE)
        .getString("translation", "kjv") ?: "kjv"
}.getOrDefault("kjv")

private fun saveTranslation(context: Context, id: String) {
    context.getSharedPreferences("bible", Context.MODE_PRIVATE)
        .edit().putString("translation", id).apply()
}

private fun loadFontSize(context: Context): Float = runCatching {
    context.getSharedPreferences("bible", Context.MODE_PRIVATE)
        .getFloat("font_size", 16f)
}.getOrDefault(16f)

private fun saveFontSize(context: Context, size: Float) {
    context.getSharedPreferences("bible", Context.MODE_PRIVATE)
        .edit().putFloat("font_size", size).apply()
}
