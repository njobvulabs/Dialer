package com.example.ui.screens.hymns

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.AppIcons
import com.example.ui.components.AppSearchField
import com.example.ui.components.FloatingTopBar
import com.example.ui.theme.AppCornerRadius
import com.example.ui.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class Hymn(
    val no: Int,
    val title: String,
    val titleEn: String?,
    val key: String?,
    val tune: String?,
    val stanzas: List<List<String>>
)

object HymnData {
    private var cache: List<Hymn>? = null

    private fun optStr(o: JSONObject, k: String): String? =
        if (o.isNull(k)) null else o.optString(k).takeIf { it.isNotBlank() }

    fun load(context: Context): List<Hymn> {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val raw = context.assets.open("hymns_nyimbo.json")
                .bufferedReader().use { it.readText() }
            val arr = JSONArray(raw)
            val list = ArrayList<Hymn>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val stArr = o.getJSONArray("stanzas")
                val stanzas = ArrayList<List<String>>(stArr.length())
                for (s in 0 until stArr.length()) {
                    val lArr = stArr.getJSONArray(s)
                    val lines = ArrayList<String>(lArr.length())
                    for (l in 0 until lArr.length()) lines.add(lArr.getString(l))
                    stanzas.add(lines)
                }
                list.add(
                    Hymn(
                        no = o.getInt("no"),
                        title = o.getString("title"),
                        titleEn = optStr(o, "titleEn"),
                        key = optStr(o, "key"),
                        tune = optStr(o, "tune"),
                        stanzas = stanzas
                    )
                )
            }
            cache = list
            return list
        }
    }
}

private fun hymnPrefs(context: Context) =
    context.getSharedPreferences("hymns", Context.MODE_PRIVATE)

private fun loadFavorites(context: Context): Set<Int> = runCatching {
    val raw = hymnPrefs(context).getString("favorites", "") ?: ""
    raw.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
}.getOrDefault(emptySet())

private fun saveFavorites(context: Context, set: Set<Int>) {
    hymnPrefs(context).edit()
        .putString("favorites", set.sorted().joinToString(",")).apply()
}

private fun loadFontSize(context: Context): Float =
    hymnPrefs(context).getFloat("font_size", 17f)

private fun saveFontSize(context: Context, size: Float) {
    hymnPrefs(context).edit().putFloat("font_size", size).apply()
}

private fun loadLastNo(context: Context): Int =
    hymnPrefs(context).getInt("last_no", 0)

private fun saveLastNo(context: Context, no: Int) {
    hymnPrefs(context).edit().putInt("last_no", no).apply()
}

@Composable
fun HymnScreen(viewModel: MainViewModel, onClose: () -> Unit) {
    val context = LocalContext.current

    var hymns by remember { mutableStateOf<List<Hymn>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }

    var openNo by remember { mutableStateOf<Int?>(null) }
    var showSearch by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var favOnly by remember { mutableStateOf(false) }
    var favorites by remember { mutableStateOf(loadFavorites(context)) }
    var fontSize by remember { mutableFloatStateOf(loadFontSize(context)) }
    var swipeState by remember { mutableStateOf(loadLastNo(context)) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching { HymnData.load(context) }
                .onSuccess { hymns = it }
                .onFailure { loadError = it.message }
        }
    }

    fun toggleFavorite(no: Int) {
        favorites = if (no in favorites) favorites - no else favorites + no
        saveFavorites(context, favorites)
    }

    BackHandler(enabled = openNo != null) { openNo = null }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            val open = openNo?.let { n -> hymns?.firstOrNull { it.no == n } }
            val useGlassmorphism by viewModel.useGlassmorphism.collectAsState()
            FloatingTopBar(useGlass = useGlassmorphism) {
                Text(
                    if (open != null) "Nyimbo ${open.no}" else "Nyimbo za Chitsitsimutso",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (open != null) {
                    IconButton(onClick = { toggleFavorite(open.no) }) {
                        Icon(
                            if (open.no in favorites) AppIcons.Star else AppIcons.StarBorder,
                            contentDescription = "Favorite",
                            tint = if (open.no in favorites) MaterialTheme.colorScheme.primary
                            else LocalContentColor.current
                        )
                    }
                    IconButton(onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("hymn", hymnText(open)))
                        Toast.makeText(context, "Hymn copied", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(AppIcons.ContentCopy, contentDescription = "Copy")
                    }
                } else {
                    IconButton(onClick = { favOnly = !favOnly }) {
                        Icon(
                            AppIcons.Star,
                            contentDescription = "Favorites",
                            tint = if (favOnly) MaterialTheme.colorScheme.primary
                            else LocalContentColor.current
                        )
                    }
                    IconButton(onClick = {
                        showSearch = !showSearch
                        if (!showSearch) query = ""
                    }) {
                        Icon(
                            AppIcons.Search,
                            contentDescription = "Search",
                            tint = if (showSearch) MaterialTheme.colorScheme.primary
                            else LocalContentColor.current
                        )
                    }
                }
            }

            if (loadError != null) {
                Text(
                    "Failed to load hymns: $loadError",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp)
                )
            }

            val all = hymns
            if (all == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(10.dp))
                        Text("Loading hymns...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                return@Column
            }

            if (open != null) {
                HymnDetail(
                    hymn = open,
                    fontSize = fontSize,
                    isFavorite = open.no in favorites,
                    onToggleFavorite = { toggleFavorite(open.no) },
                    onPrev = {
                        val idx = all.indexOfFirst { it.no == open.no }
                        if (idx > 0) {
                            openNo = all[idx - 1].no
                            saveLastNo(context, all[idx - 1].no)
                        }
                    },
                    onNext = {
                        val idx = all.indexOfFirst { it.no == open.no }
                        if (idx in 0 until all.size - 1) {
                            openNo = all[idx + 1].no
                            saveLastNo(context, all[idx + 1].no)
                        }
                    },
                    hasPrev = all.indexOfFirst { it.no == open.no } > 0,
                    hasNext = all.indexOfFirst { it.no == open.no } in 0 until all.size - 1
                )
                return@Column
            }

            val q = query.trim()
            val digits = q.filter { it.isDigit() }
            val filtered = all.filter { h ->
                (!favOnly || h.no in favorites) && (
                    q.isEmpty() ||
                        (digits.isNotEmpty() && h.no.toString().contains(digits)) ||
                        h.title.contains(q, ignoreCase = true) ||
                        (h.titleEn?.contains(q, ignoreCase = true) == true) ||
                        h.stanzas.any { st -> st.any { it.contains(q, ignoreCase = true) } }
                    )
            }

            if (showSearch) {
                AppSearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "Search by number, title or words...",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            LazyColumn(contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("A", fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp))
                        Slider(
                            value = fontSize,
                            onValueChange = { fontSize = it },
                            onValueChangeFinished = { saveFontSize(context, fontSize) },
                            valueRange = 12f..30f,
                            modifier = Modifier.weight(1f)
                        )
                        Text("A", fontSize = 22.sp, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                if (swipeState > 0 && filtered.any { it.no == swipeState }) {
                    item {
                        val last = all.firstOrNull { it.no == swipeState }
                        if (last != null) {
                            Surface(
                                onClick = { openNo = last.no },
                                shape = AppCornerRadius.md,
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                            ) {
                                Column(Modifier.padding(14.dp)) {
                                    Text(
                                        "Continue",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text("${last.no}. ${last.title}", fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
                item {
                    Text(
                        if (favOnly) "Favorites (${favorites.size})" else "All hymns (${filtered.size})",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp)
                    )
                }
                items(filtered, key = { it.no }) { h ->
                    HymnRow(h, isFavorite = h.no in favorites) {
                        openNo = h.no
                        swipeState = h.no
                        saveLastNo(context, h.no)
                    }
                }
            }
        }
    }
}

@Composable
private fun HymnRow(h: Hymn, isFavorite: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(34.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    "${h.no}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                h.title,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val sub = listOfNotNull(h.titleEn, h.key?.let { "• $it" }).joinToString("  ")
            if (sub.isNotBlank()) {
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (isFavorite) {
            Spacer(Modifier.width(8.dp))
            Icon(
                AppIcons.Star,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun HymnDetail(
    hymn: Hymn,
    fontSize: Float,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    hasPrev: Boolean,
    hasNext: Boolean
) {
    Column(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(4.dp))
            Text(
                hymn.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            if (hymn.titleEn != null) {
                Text(
                    hymn.titleEn,
                    style = MaterialTheme.typography.titleSmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val meta = listOfNotNull(
                hymn.key?.let { "Key: $it" },
                hymn.tune?.let { "Tune: $it" }
            ).joinToString("    ")
            if (meta.isNotBlank()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
            hymn.stanzas.forEachIndexed { si, stanza ->
                Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
                    stanza.forEach { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodyLarge,
                            fontSize = fontSize.sp,
                            lineHeight = (fontSize * 1.35f).sp
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(enabled = hasPrev, onClick = onPrev) { Text("← Prev") }
            TextButton(onClick = onToggleFavorite) {
                Icon(
                    if (isFavorite) AppIcons.Star else AppIcons.StarBorder,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(6.dp))
                Text(if (isFavorite) "Favorited" else "Favorite")
            }
            TextButton(enabled = hasNext, onClick = onNext) { Text("Next →") }
        }
    }
}

private fun hymnText(h: Hymn): String = buildString {
    append("${h.no}. ${h.title}")
    h.titleEn?.let { append("\n$it") }
    append("\n")
    h.stanzas.forEach { stanza ->
        append("\n")
        stanza.forEach { append("$it\n") }
    }
}.trim()
