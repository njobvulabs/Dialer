package com.example.ui.screens.files

import android.content.Context
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.ui.components.AppIcons
import com.example.ui.components.AppSearchField
import com.example.ui.theme.AppCornerRadius
import com.example.ui.components.FloatingTopBar
import com.example.ui.MainViewModel
import com.example.util.OdrDocumentRenderer
import com.example.util.SortOption
import com.example.util.StorageHelper
import kotlinx.coroutines.withContext
import java.util.zip.ZipFile
import kotlinx.coroutines.launch
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.ParcelFileDescriptor
import android.text.StaticLayout
import android.text.TextPaint
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import kotlinx.coroutines.Dispatchers
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.unit.IntSize

enum class ReaderTheme(val label: String, val bgColor: Color, val textColor: Color) {
    LIGHT("Clean Light", Color(0xFFF8FAFC), Color(0xFF0F172A)),
    SEPIA("Sepia Warm", Color(0xFFFDF6E3), Color(0xFF433422)),
    DARK("Slate Dark", Color(0xFF1E293B), Color(0xFFF1F5F9)),
    OLED("OLED Pure Black", Color(0xFF000000), Color(0xFFE2E8F0))
}

private fun clampPdfOffset(offset: Offset, viewportSize: IntSize, zoom: Float): Offset {
    if (zoom <= 1f) return Offset.Zero
    val maxPanX = viewportSize.width.toFloat() * (zoom - 1f) / 2f
    val maxPanY = viewportSize.height.toFloat() * (zoom - 1f) / 2f
    return Offset(
        offset.x.coerceIn(-maxPanX, maxPanX),
        offset.y.coerceIn(-maxPanY, maxPanY)
    )
}

@Composable
private fun PdfPageItem(
    file: File,
    pageIndex: Int,
    zoom: Float,
    rotation: Int,
    onZoomChange: (Float) -> Unit
) {
    var bitmap by remember(file, pageIndex, rotation) { mutableStateOf<Bitmap?>(null) }
    var offset by remember(file, pageIndex) { mutableStateOf(Offset.Zero) }
    var viewportSize by remember(file, pageIndex) { mutableStateOf(IntSize.Zero) }
    val context = LocalContext.current
    val currentZoom by rememberUpdatedState(zoom)

    LaunchedEffect(file, pageIndex, rotation) {
        withContext(Dispatchers.IO) {
            try {
                val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(pfd)
                val page = renderer.openPage(pageIndex)
                val displayMetrics = context.resources.displayMetrics
                var targetWidth = displayMetrics.widthPixels.coerceAtLeast(1080)
                var targetHeight = (targetWidth.toFloat() / page.width * page.height).toInt().coerceAtLeast(100)
                if (rotation % 180 != 0) {
                    val tmp = targetWidth
                    targetWidth = targetHeight
                    targetHeight = tmp
                }
                val b = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(b)
                canvas.drawColor(android.graphics.Color.WHITE)
                canvas.save()
                if (rotation != 0) {
                    canvas.rotate(rotation.toFloat(), targetWidth / 2f, targetHeight / 2f)
                }
                val renderWidth = if (rotation % 180 != 0) targetHeight else targetWidth
                val renderHeight = if (rotation % 180 != 0) targetWidth else targetHeight
                val renderBitmap = Bitmap.createBitmap(renderWidth, renderHeight, Bitmap.Config.ARGB_8888)
                val renderCanvas = Canvas(renderBitmap)
                renderCanvas.drawColor(android.graphics.Color.WHITE)
                page.render(renderBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                canvas.drawBitmap(renderBitmap, (targetWidth - renderWidth) / 2f, (targetHeight - renderHeight) / 2f, null)
                renderBitmap.recycle()
                canvas.restore()
                page.close()
                renderer.close()
                pfd.close()
                withContext(Dispatchers.Main) {
                    bitmap = b
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewportSize = it }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var gestureZoom = currentZoom
                    var gestureOffset = offset
                    var lastDistance = 0f
                    var multiTouch = false

                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break

                        if (pressed.size >= 2) {
                            multiTouch = true
                            val centroid = pressed.fold(Offset.Zero) { acc, c -> acc + c.position } / pressed.size.toFloat()
                            val distance = (pressed[0].position - pressed[1].position).getDistance()
                            if (lastDistance > 0f) {
                                val zoomChange = distance / lastDistance
                                val newZoom = (gestureZoom * zoomChange).coerceIn(1f, 5f)
                                if (newZoom != gestureZoom) {
                                    val factor = newZoom / gestureZoom
                                    val newOffset = Offset(
                                        centroid.x - (centroid.x - gestureOffset.x) * factor,
                                        centroid.y - (centroid.y - gestureOffset.y) * factor
                                    )
                                    gestureZoom = newZoom
                                    gestureOffset = clampPdfOffset(newOffset, viewportSize, newZoom)
                                    onZoomChange(newZoom)
                                    offset = gestureOffset
                                }
                            }
                            lastDistance = distance
                            pressed.forEach { it.consume() }
                        } else {
                            val change = pressed[0]
                            if (gestureZoom > 1f) {
                                val pan = change.positionChange()
                                gestureOffset = clampPdfOffset(
                                    Offset(gestureOffset.x + pan.x, gestureOffset.y + pan.y),
                                    viewportSize,
                                    gestureZoom
                                )
                                offset = gestureOffset
                                change.consume()
                            }
                            lastDistance = 0f
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = offset.x
                    translationY = offset.y
                    transformOrigin = TransformOrigin(0f, 0f)
                },
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = "PDF Page ${pageIndex + 1}",
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.75f)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

@Composable
fun PdfDocumentViewer(
    file: File,
    modifier: Modifier = Modifier,
    showControls: Boolean = true
) {
    val context = LocalContext.current
    var pageCount by remember(file) { mutableIntStateOf(0) }
    var loadingError by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    var zoom by remember(file) { mutableFloatStateOf(1f) }
    var rotation by remember(file) { mutableIntStateOf(0) }
    var showThumbnails by remember(file) { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(file) {
        isLoading = true
        loadingError = null
        withContext(Dispatchers.IO) {
            try {
                val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(pfd)
                pageCount = renderer.pageCount
                renderer.close()
                pfd.close()
                withContext(Dispatchers.Main) {
                    isLoading = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    loadingError = e.localizedMessage ?: "Failed to open PDF document."
                    isLoading = false
                }
            }
        }
    }

    val prefs = remember(file) { context.getSharedPreferences("reader_prefs", Context.MODE_PRIVATE) }
    val savedPage = remember(file) { prefs.getInt("pdf_page_${file.absolutePath}", 0) }

    val pagerState = rememberPagerState(
        initialPage = savedPage.coerceAtLeast(0),
        pageCount = { pageCount.coerceAtLeast(1) }
    )

    // Save reading position when page changes
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { index ->
            prefs.edit().putInt("pdf_page_${file.absolutePath}", index).apply()
        }
    }

    if (isLoading) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(12.dp))
                Text("Opening PDF...", style = MaterialTheme.typography.bodyMedium)
            }
        }
    } else if (loadingError != null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp)
            ) {
                Icon(AppIcons.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
                Spacer(modifier = Modifier.height(8.dp))
                Text("Unable to open PDF", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                Spacer(modifier = Modifier.height(4.dp))
                Text(loadingError!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } else {
        Box(modifier = modifier.fillMaxSize()) {
            VerticalPager(
                state = pagerState,
                userScrollEnabled = zoom <= 1f,
                modifier = Modifier.fillMaxSize()
            ) { pageIndex ->
                PdfPageItem(
                    file = file,
                    pageIndex = pageIndex,
                    zoom = zoom,
                    rotation = rotation,
                    onZoomChange = { zoom = it }
                )
            }

            // Bottom control bar
            if (showControls) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 8.dp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Zoom controls
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { zoom = (zoom / 1.25f).coerceAtLeast(1f) }) {
                                    Icon(AppIcons.ZoomOut, contentDescription = "Zoom Out")
                                }
                                Text(
                                    text = "${(zoom * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.width(48.dp),
                                    textAlign = TextAlign.Center
                                )
                                IconButton(onClick = { zoom = (zoom * 1.25f).coerceAtMost(5f) }) {
                                    Icon(AppIcons.ZoomIn, contentDescription = "Zoom In")
                                }
                                IconButton(onClick = { zoom = 1f }) {
                                    Icon(AppIcons.RestartAlt, contentDescription = "Reset Zoom")
                                }
                            }

                            // Page navigation
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = {
                                        if (pagerState.currentPage > 0) {
                                            coroutineScope.launch {
                                                pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                            }
                                        }
                                    }
                                ) {
                                    Icon(AppIcons.KeyboardArrowLeft, contentDescription = "Previous Page")
                                }
                                Text(
                                    text = "${pagerState.currentPage + 1} / $pageCount",
                                    style = MaterialTheme.typography.labelSmall
                                )
                                IconButton(
                                    onClick = {
                                        if (pagerState.currentPage < pageCount - 1) {
                                            coroutineScope.launch {
                                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                            }
                                        }
                                    }
                                ) {
                                    Icon(AppIcons.KeyboardArrowRight, contentDescription = "Next Page")
                                }
                            }

                            // Thumbnails & rotate
                            Row {
                                IconButton(onClick = { rotation = (rotation + 90) % 360 }) {
                                    Icon(AppIcons.RotateRight, contentDescription = "Rotate Page")
                                }
                                IconButton(onClick = { showThumbnails = !showThumbnails }) {
                                    Icon(
                                        imageVector = if (showThumbnails) AppIcons.ViewAgenda else AppIcons.GridView,
                                        contentDescription = "Page Thumbnails"
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Thumbnails overlay
            AnimatedVisibility(
                visible = showThumbnails,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = if (showControls) 64.dp else 0.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 4.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 90.dp),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.height(220.dp)
                    ) {
                        gridItems((0 until pageCount).toList(), key = { it }) { pageIdx ->
                            PdfThumbnailItem(
                                file = file,
                                pageIndex = pageIdx,
                                isCurrent = pageIdx == pagerState.currentPage,
                                onClick = {
                                    coroutineScope.launch {
                                        pagerState.animateScrollToPage(pageIdx)
                                    }
                                    showThumbnails = false
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfThumbnailItem(
    file: File,
    pageIndex: Int,
    isCurrent: Boolean,
    onClick: () -> Unit
) {
    var bitmap by remember(file, pageIndex) { mutableStateOf<Bitmap?>(null) }
    val context = LocalContext.current

    LaunchedEffect(file, pageIndex) {
        withContext(Dispatchers.IO) {
            try {
                val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(pfd)
                val page = renderer.openPage(pageIndex)
                val targetWidth = 200
                val width = targetWidth
                val height = (targetWidth.toFloat() / page.width * page.height).toInt().coerceAtLeast(50)
                val b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(b)
                canvas.drawColor(android.graphics.Color.WHITE)
                page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                renderer.close()
                pfd.close()
                withContext(Dispatchers.Main) {
                    bitmap = b
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .border(
                width = 2.dp,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = MaterialTheme.shapes.small
            )
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = "PDF Page ${pageIndex + 1}",
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.75f)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            }
        }
        Text(
            text = "${pageIndex + 1}",
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// Bounded LRU cache of per-directory deep document scans (avoids re-scanning
// on return visits and keeps repeated navigation fast).
private val deepDocumentCache = object : LinkedHashMap<String, List<File>>(32, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<File>>?): Boolean = size > 24
}
private val deepDocumentCacheLock = Any()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentViewerScreen(
    viewModel: MainViewModel,
    onOpenDocument: (File) -> Unit
) {
    val context = LocalContext.current
    val currentDir by viewModel.currentDirectory.collectAsState()
    val mediaIndex by viewModel.mediaIndex.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(Unit) { viewModel.ensureMediaIndexFresh() }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.ensureMediaIndexFresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var searchQuery by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var selectedFormatFilter by remember { mutableStateOf("All") }
    var sortOption by remember { mutableStateOf(SortOption.DATE) }
    var sortAscending by remember { mutableStateOf(false) }
    var showGridMode by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }

    // Deep scan of the current directory (bounded, cached + off the main thread) so
    // documents stored deeper than the mediaIndex scan still show up in the
    // Document Reader list. Results are memoized per directory to avoid
    // re-scanning on return visits.
    var deepDocumentFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    LaunchedEffect(currentDir) {
        val key = currentDir.absolutePath
        val hit = synchronized(deepDocumentCacheLock) { deepDocumentCache[key] }
        if (hit != null) {
            deepDocumentFiles = hit
        } else {
            val found = withContext(Dispatchers.IO) {
                val extSet = OdrDocumentRenderer.SUPPORTED_EXTENSIONS
                val out = mutableListOf<File>()
                var count = 0
                fun scan(dir: File, depth: Int) {
                    if (depth > 6 || count >= 400) return
                    val children = dir.listFiles() ?: return
                    for (child in children) {
                        if (child.name.startsWith(".")) continue
                        if (child.isDirectory) scan(child, depth + 1)
                        else if (child.extension.lowercase() in extSet) {
                            out.add(child)
                            count++
                        }
                    }
                }
                scan(currentDir, 0)
                out
            }
            synchronized(deepDocumentCacheLock) { deepDocumentCache[key] = found }
            deepDocumentFiles = found
        }
    }

    // Scan documents across storage (mediaIndex built off the main thread)
    val discoveredDocuments by produceState(emptyList<File>(), currentDir, searchQuery, selectedFormatFilter, mediaIndex, sortOption, sortAscending, deepDocumentFiles) {
        value = withContext(Dispatchers.IO) {
            val storageFiles = StorageHelper.listFiles(currentDir).map { it.file }
            val allAccessibleFiles = mediaIndex.map { it.file }

            val documentExtensions = OdrDocumentRenderer.SUPPORTED_EXTENSIONS

            val filtered = (storageFiles + allAccessibleFiles + deepDocumentFiles)
                .distinctBy { it.absolutePath }
                .filter { file ->
                    file.isFile && (
                        file.extension.lowercase() in documentExtensions ||
                        StorageHelper.getMimeType(file).contains("pdf")
                    )
                }
                .filter { file ->
                    if (searchQuery.isEmpty()) true else file.name.contains(searchQuery, ignoreCase = true)
                }
                .filter { file ->
                    when (selectedFormatFilter) {
                        "PDFs" -> file.extension.lowercase() == "pdf"
                        "Office" -> file.extension.lowercase() in OdrDocumentRenderer.SUPPORTED_EXTENSIONS && file.extension.lowercase() != "pdf"
                        else -> true
                    }
                }

            val comparator = when (sortOption) {
                SortOption.NAME -> compareBy<File> { it.name.lowercase() }
                SortOption.DATE -> compareBy<File> { it.lastModified() }
                SortOption.SIZE -> compareBy<File> { it.length() }
                SortOption.TYPE -> compareBy<File> { it.extension.lowercase() }
            }
            if (sortAscending) filtered.sortedWith(comparator) else filtered.sortedWith(comparator).reversed()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Search (icon reveals inline field) + sort controls, one row
        val useGlassmorphism by viewModel.useGlassmorphism.collectAsState()
        FloatingTopBar(useGlass = useGlassmorphism) {
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
            if (searchOpen) {
                AppSearchField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = "Search documents...",
                    leadingIcon = AppIcons.FindInPage,
                    modifier = Modifier.weight(1f)
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
            IconButton(onClick = { searchOpen = !searchOpen }) {
                Icon(
                    AppIcons.Search,
                    contentDescription = "Search",
                    tint = if (searchOpen || searchQuery.isNotBlank()) MaterialTheme.colorScheme.primary
                    else LocalContentColor.current
                )
            }

                Box {
                    TextButton(onClick = { showSortMenu = true }) {
                        Icon(AppIcons.Sort, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Sort: ${sortOption.label}")
                    }
                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false }
                    ) {
                        SortOption.values().forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = {
                                    sortOption = option
                                    showSortMenu = false
                                },
                                trailingIcon = {
                                    if (sortOption == option) {
                                        Icon(AppIcons.Check, contentDescription = null)
                                    }
                                }
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(if (sortAscending) "Ascending" else "Descending") },
                            onClick = {
                                sortAscending = !sortAscending
                                showSortMenu = false
                            },
                            trailingIcon = {
                                Icon(
                                    imageVector = if (sortAscending) AppIcons.ArrowUpward else AppIcons.ArrowDownward,
                                    contentDescription = null
                                )
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                IconButton(
                    onClick = { viewModel.refreshMediaIndex(force = true) },
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Icon(AppIcons.Refresh, contentDescription = "Refresh")
                }
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(
                    onClick = { showGridMode = !showGridMode },
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Icon(
                        imageVector = if (showGridMode) AppIcons.ViewList else AppIcons.GridView,
                        contentDescription = if (showGridMode) "Show List" else "Show Grid"
                    )
                }
            }
            }

            // Format Filter Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("All", "PDFs", "Office").forEach { filter ->
                    FilterChip(
                        selected = selectedFormatFilter == filter,
                        onClick = { selectedFormatFilter = filter },
                        label = { Text(filter) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (discoveredDocuments.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = AppIcons.FolderZip,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (searchQuery.isNotEmpty()) "No documents match query" else "No document files found",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Supported: ${OdrDocumentRenderer.SUPPORTED_LABEL}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            } else if (showGridMode) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 150.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    gridItems(discoveredDocuments, key = { it.absolutePath }) { file ->
                        DocumentGridItem(file = file, onClick = { onOpenDocument(file) })
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(discoveredDocuments, key = { it.absolutePath }) { file ->
                        DocumentRowItem(file = file, onClick = { onOpenDocument(file) })
                    }
                }
            }
        }
    }

@Composable
private fun docExtColor(ext: String): Color {
    return when (ext) {
        "PDF" -> Color(0xFFEF4444)
        "DOCX", "DOC", "ODT", "RTF" -> Color(0xFF3B82F6)
        "XLSX", "XLS", "ODS", "CSV" -> Color(0xFF10B981)
        "PPTX", "PPT", "ODP" -> Color(0xFFF97316)
        "JSON", "XML" -> Color(0xFFF59E0B)
        "TXT", "LOG", "MD", "HTML", "KT", "JAVA" -> Color(0xFF64748B)
        "EPUB" -> Color(0xFFA855F7)
        else -> MaterialTheme.colorScheme.primary
    }
}

@Composable
private fun DocumentRowItem(file: File, onClick: () -> Unit) {
    val sizeFormatted = String.format(Locale.getDefault(), "%.1f KB", file.length().toDouble() / 1024)
    val ext = file.extension.uppercase()

    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = docExtColor(ext),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = ext.take(4),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "$sizeFormatted • ${file.parentFile?.name ?: "Storage"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                imageVector = AppIcons.ChevronRight,
                contentDescription = "Read Document",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DocumentGridItem(file: File, onClick: () -> Unit) {
    val ext = file.extension.uppercase()
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth()
        ) {
            Surface(
                color = docExtColor(ext),
                shape = AppCornerRadius.sm,
                modifier = Modifier
                    .size(52.dp)
                    .align(Alignment.CenterHorizontally)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = ext.take(4),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = file.name,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = String.format(Locale.getDefault(), "%.1f KB", file.length().toDouble() / 1024),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PptxSlideContent(slide: PptxSlide, rotation: Int) {
    val lines = slide.text.lines()
    val title = lines.firstOrNull { it.isNotBlank() } ?: ""
    val body = lines.dropWhile { it.isBlank() || it == title }.filter { it.isNotBlank() }
    val scrollState = rememberScrollState()
    val density = LocalDensity.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .then(if (rotation != 0) Modifier.graphicsLayer { rotationZ = rotation.toFloat() } else Modifier)
            .verticalScroll(scrollState)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (title.isNotEmpty()) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold,
                        platformStyle = PlatformTextStyle(includeFontPadding = false)
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))
            }
            if (body.isEmpty() && slide.images.isEmpty()) {
                Text(
                    text = "(No content on this slide)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                if (slide.images.isNotEmpty()) {
                    slide.images.forEach { bmp ->
                        Spacer(modifier = Modifier.height(12.dp))
                        // Cap the displayed width at the image's native pixel
                        // width (converted to dp) so small embedded images are
                        // never upscaled to full screen width, which blurs any
                        // baked-in text. Larger images still fill the width.
                        val nativeWidthDp = with(density) { bmp.width.toDp() }
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = nativeWidthDp)
                                .clip(MaterialTheme.shapes.small)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }
                if (body.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        body.forEach { line ->
                            // Preserve whatever list marker the slide already
                            // has; don't force "• " onto every line (keeps
                            // numbered lists / plain paragraphs in order).
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    platformStyle = PlatformTextStyle(includeFontPadding = false)
                                ),
                                lineHeight = 26.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Rasterized slide renderer. Draws each slide (images + text) onto a bitmap
 * at 2x the on-screen resolution using the native android.graphics renderer,
 * then displays it through the same Image path already proven to be crisp.
 * This sidesteps Compose's text layer entirely, which was rendering soft on
 * certain devices.
 */
@Composable
private fun RasterPptxSlideContent(
    slide: PptxSlide,
    rotation: Int,
    viewportWidth: Int,
    viewportHeight: Int
) {
    val density = LocalDensity.current
    val overlay = remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(slide, rotation, viewportWidth, viewportHeight) {
        val src = withContext(Dispatchers.Default) {
            renderPptxSlideToBitmap(slide, rotation, viewportWidth, viewportHeight, density)
                ?.asImageBitmap()
        }
        overlay.value = src
    }

    val bmp = overlay.value
    if (bmp == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else {
        Image(
            bitmap = bmp,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )
    }
}

private fun renderPptxSlideToBitmap(
    slide: PptxSlide,
    rotation: Int,
    viewportWidth: Int,
    viewportHeight: Int,
    density: Density
): Bitmap? {
    val lines = slide.text.lines()
    val title = lines.firstOrNull { it.isNotBlank() } ?: ""
    val body = lines.dropWhile { it.isBlank() || it == title }.filter { it.isNotBlank() }

    val scale = 2f
    val canvasWidth = (viewportWidth * scale).toInt()

    val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        textSize = with(density) { 16.sp.toPx() } * scale
        isDither = true
    }
    val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = with(density) { 24.sp.toPx() } * scale
        isDither = true
    }

    val margin = with(density) { 16.dp.toPx() * scale }.toInt()
    val contentWidth = canvasWidth - margin * 2
    val lineSpacing = with(density) { 12.dp.toPx() * scale }.toInt()
    val dividerSpacing = lineSpacing

    // Measure total height first.
    var y = margin
    if (title.isNotEmpty()) {
        y += titlePaint.fontMetricsInt.let { it.descent - it.ascent }
        y += dividerSpacing
    }
    val imgBlockOffsets = mutableListOf<Pair<Bitmap, Int>>()
    for (bmp in slide.images) {
        val scaledH = bmp.height * contentWidth / bmp.width
        imgBlockOffsets.add(bmp to scaledH)
        y += scaledH + lineSpacing
    }
    val bodyLayouts = body.map { line ->
        StaticLayout.Builder.obtain(
            line,
            0,
            line.length,
            bodyPaint,
            contentWidth
        ).build()
    }
    y += bodyLayouts.sumOf { it.height + lineSpacing }

    // Guard against absurdly tall canvases.
    val canvasHeight = y + margin
    if (canvasHeight > 16384) return null
    if (canvasHeight <= 0 || contentWidth <= 0) return null

    val bitmap = Bitmap.createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    if (rotation != 0) {
        val cx = canvasWidth / 2f
        val cy = canvasHeight / 2f
        canvas.rotate(rotation.toFloat(), cx, cy)
    }

    val bgPaint = Paint().apply { color = android.graphics.Color.WHITE }
    canvas.drawRect(0f, 0f, canvasWidth.toFloat(), canvasHeight.toFloat(), bgPaint)

    var cyPos = margin.toFloat()

    if (title.isNotEmpty()) {
        canvas.drawText(title, margin.toFloat(), cyPos - titlePaint.ascent(), titlePaint)
        cyPos += (titlePaint.descent() - titlePaint.ascent()) + dividerSpacing
        val rulePaint = Paint().apply {
            color = android.graphics.Color.parseColor("#E2E8F0")
            strokeWidth = with(density) { 1.dp.toPx() * scale }
        }
        canvas.drawLine(
            margin.toFloat(),
            cyPos,
            (canvasWidth - margin).toFloat(),
            cyPos,
            rulePaint
        )
        cyPos += dividerSpacing
    }

    for ((bmp, scaledH) in imgBlockOffsets) {
        val scaledW = contentWidth
        canvas.drawBitmap(
            bmp,
            Rect(0, 0, bmp.width, bmp.height),
            RectF(
                margin.toFloat(),
                cyPos,
                (margin + scaledW).toFloat(),
                cyPos + scaledH.toFloat()
            ),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG or Paint.ANTI_ALIAS_FLAG)
        )
        cyPos += scaledH.toFloat() + lineSpacing
    }

    for (layout in bodyLayouts) {
        canvas.save()
        canvas.translate(margin.toFloat(), cyPos)
        layout.draw(canvas)
        canvas.restore()
        cyPos += layout.height + lineSpacing
    }

    return bitmap
}

@Composable
fun PptxDocumentViewer(
    file: File,
    modifier: Modifier = Modifier,
    showControls: Boolean = true
) {
    val context = LocalContext.current
    var slides by remember(file) { mutableStateOf<List<PptxSlide>>(emptyList()) }
    var isLoading by remember(file) { mutableStateOf(true) }
    var loadingError by remember { mutableStateOf<String?>(null) }
    var showIndex by remember(file) { mutableStateOf(false) }
    var rotation by remember(file) { mutableIntStateOf(0) }
    val coroutineScope = rememberCoroutineScope()

    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val reqWidth = with(density) { (configuration.screenWidthDp * 2f).dp.toPx() }.toInt()
    val reqHeight = with(density) { (configuration.screenHeightDp * 2f).dp.toPx() }.toInt()

    LaunchedEffect(file) {
        isLoading = true
        loadingError = null
        slides = withContext(Dispatchers.IO) {
            readPptxSlides(file, reqWidth, reqHeight)
        }
        isLoading = false
        if (slides.isEmpty()) {
            loadingError = "Could not read slides from this file."
        }
    }

    val prefs = remember(file) { context.getSharedPreferences("reader_prefs", Context.MODE_PRIVATE) }
    val savedSlide = remember(file) { prefs.getInt("pptx_slide_${file.absolutePath}", 0) }

    val pagerState = rememberPagerState(
        initialPage = savedSlide.coerceAtLeast(0),
        pageCount = { slides.size.coerceAtLeast(1) }
    )

    // Save reading position when slide changes
    LaunchedEffect(pagerState, slides) {
        if (slides.isNotEmpty()) {
            snapshotFlow { pagerState.currentPage }.collect { index ->
                prefs.edit().putInt("pptx_slide_${file.absolutePath}", index).apply()
            }
        }
    }

    if (isLoading) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(12.dp))
                Text("Opening slides...", style = MaterialTheme.typography.bodyMedium)
            }
        }
    } else if (loadingError != null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp)
            ) {
                Icon(
                    imageVector = AppIcons.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Unable to open presentation",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = loadingError!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    } else {
        Box(modifier = modifier.fillMaxSize()) {
            var containerSize by remember { mutableStateOf<IntSize?>(null) }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { containerSize = it }
            ) { slideIndex ->
                val size = containerSize
                if (size != null) {
                    RasterPptxSlideContent(
                        slide = slides[slideIndex],
                        rotation = rotation,
                        viewportWidth = size.width,
                        viewportHeight = size.height
                    )
                }
            }

            if (showControls) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 8.dp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                if (pagerState.currentPage > 0) {
                                    coroutineScope.launch {
                                        pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                    }
                                }
                            }
                        ) {
                            Icon(AppIcons.KeyboardArrowLeft, contentDescription = "Previous Slide")
                        }
                        Text(
                            text = "Slide ${pagerState.currentPage + 1} / ${slides.size}",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Row {
                            IconButton(onClick = { rotation = (rotation + 90) % 360 }) {
                                Icon(AppIcons.RotateRight, contentDescription = "Rotate Slide")
                            }
                            IconButton(onClick = { showIndex = !showIndex }) {
                                Icon(
                                    imageVector = if (showIndex) AppIcons.ViewAgenda else AppIcons.GridView,
                                    contentDescription = "Slide Index"
                                )
                            }
                            IconButton(
                                onClick = {
                                    if (pagerState.currentPage < slides.size - 1) {
                                        coroutineScope.launch {
                                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                        }
                                    }
                                }
                            ) {
                                Icon(AppIcons.KeyboardArrowRight, contentDescription = "Next Slide")
                            }
                        }
                    }
                }
            }

            // Slide index overlay
            AnimatedVisibility(
                visible = showIndex,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = if (showControls) 56.dp else 0.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 4.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    LazyColumn(
                        modifier = Modifier.height(260.dp)
                    ) {
                        items(slides.size) { index ->
                            val firstLine = slides[index].text.lines().firstOrNull { it.isNotBlank() } ?: ""
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        coroutineScope.launch {
                                            pagerState.animateScrollToPage(index)
                                        }
                                        showIndex = false
                                    }
                                    .background(
                                        if (index == pagerState.currentPage) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            Color.Transparent
                                        }
                                    )
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    color = if (index == pagerState.currentPage) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                    shape = CircleShape,
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = "${index + 1}",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            color = if (index == pagerState.currentPage) {
                                                MaterialTheme.colorScheme.onPrimary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            }
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = firstLine.ifEmpty { "Slide ${index + 1}" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

private data class PptxSlide(
    val text: String,
    val images: List<Bitmap>
)

private fun readPptxSlides(file: File, reqWidth: Int, reqHeight: Int): List<PptxSlide> {
    return try {
        val slideNumberRegex = Regex("slide(\\d+)\\.xml$")
        val slideTexts = mutableListOf<Pair<Int, PptxSlide>>()
        ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence()
                .filter { it.name.startsWith("ppt/slides/slide") && it.name.endsWith(".xml") }
                .toList()
            for (entry in entries) {
                val num = slideNumberRegex.find(entry.name)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                val xml = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).readText()
                slideTexts.add(num to PptxSlide(text = extractPptxSlideText(xml), images = extractPptxSlideImages(zip, entry.name, xml, reqWidth, reqHeight)))
            }
        }
        slideTexts.sortedBy { it.first }.map { it.second }
    } catch (e: Exception) {
        emptyList()
    }
}

private fun extractPptxSlideImages(
    zip: ZipFile,
    slideEntryName: String,
    slideXml: String,
    reqWidth: Int,
    reqHeight: Int
): List<Bitmap> {
    return try {
        val relsEntryName = "ppt/slides/_rels/" + slideEntryName.substringAfterLast("/") + ".rels"
        val relsEntry = zip.getEntry(relsEntryName) ?: return emptyList()
        val relsXml = zip.getInputStream(relsEntry).bufferedReader(Charsets.UTF_8).readText()
        val relMap = mutableMapOf<String, String>()
        Regex("""Id="([^"]+)"[^>]*Target="([^"]+)"""").findAll(relsXml).forEach {
            relMap[it.groupValues[1]] = it.groupValues[2]
        }
        val embedIds = Regex("""r:embed="([^"]+)"""").findAll(slideXml).map { it.groupValues[1] }.toSet()
        val images = mutableListOf<Bitmap>()
        for (rid in embedIds) {
            val target = relMap[rid] ?: continue
            val mediaPath = if (target.contains("/")) target.substringAfterLast("/") else target
            val mediaEntry = zip.getEntry("ppt/media/$mediaPath") ?: continue
            val bytes = zip.getInputStream(mediaEntry).use { it.readBytes() }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                val boundW = bounds.outWidth
                val boundH = bounds.outHeight
                while (boundH / sample >= reqHeight && boundW / sample >= reqWidth) {
                    sample *= 2
                }
            }
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)?.let { images.add(it) }
        }
        images
    } catch (e: Exception) {
        emptyList()
    }
}

private fun extractPptxSlideText(xml: String): String {
    val sb = StringBuilder(xml.length / 2)
    var i = 0
    // Track whether we are inside the current shape's text body.
    // Keeping an inShape flag lets us insert an extra paragraph separator
    // between separate shapes (text boxes, diagrams, captions) so their
    // reading order is preserved instead of running together.
    while (i < xml.length) {
        if (xml[i] == '<') {
            val closeTag = xml.indexOf('>', i)
            if (closeTag < 0) break
            var tag = xml.substring(i + 1, closeTag)
            val tagName = tag.substringBefore(" ").substringBefore(">")
            when (tagName) {
                "a:p", "/a:p" -> sb.append('\n')
                "a:br", "/a:br" -> sb.append('\n')
                "a:tab" -> sb.append('\t')
                "/p:sp", "/p:graphicFrame", "/p:cxnSp", "/p:pic" -> sb.append('\n')
                "p:sp", "p:graphicFrame", "p:cxnSp", "p:pic" -> {
                    if (sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
                    sb.append('\n')
                }
            }
            i = closeTag + 1
        } else {
            sb.append(xml[i])
            i++
        }
    }
    return sb.toString()
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n")
}

private data class OdrRenderState(
    val rendered: OdrDocumentRenderer.RenderedDocument? = null,
    val loading: Boolean = false,
    val error: String? = null
)

@Suppress("DEPRECATION")
@Composable
private fun OdrDocumentView(state: OdrRenderState, backgroundColor: Color) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        when {
            state.loading -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Rendering with OpenDocument...", style = MaterialTheme.typography.bodyMedium)
                }
            }
            state.error != null -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Icon(
                        imageVector = AppIcons.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = state.error!!,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                }
            }
            state.rendered != null -> {
                val rendered = state.rendered!!
                AndroidView(
                    factory = { webViewContext ->
                        WebView(webViewContext).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = true
                            settings.allowContentAccess = true
                            settings.allowFileAccessFromFileURLs = true
                            settings.allowUniversalAccessFromFileURLs = false
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                            settings.setSupportZoom(true)
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            setBackgroundColor(android.graphics.Color.WHITE)
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                                    val scheme = url.substringBefore(":").lowercase(Locale.ROOT)
                                    return scheme !in setOf("file", "content", "data")
                                }

                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    val scheme = request.url.scheme?.lowercase(Locale.ROOT)
                                    return scheme !in setOf("file", "content", "data")
                                }
                            }
                        }
                    },
                    update = { webView ->
                        if (webView.url != rendered.url) {
                            webView.loadUrl(rendered.url)
                        }
                    },
                    onRelease = { webView ->
                        webView.stopLoading()
                        webView.destroy()
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentReaderDetailView(
    file: File,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val fileExtension = remember(file) { file.extension.lowercase() }
    val supported = remember(fileExtension) { fileExtension in OdrDocumentRenderer.SUPPORTED_EXTENSIONS }
    var renderToken by remember(file) { mutableIntStateOf(0) }
    var odrState by remember(file) { mutableStateOf(OdrRenderState()) }
    var chromeVisible by remember(file) { mutableStateOf(true) }

    LaunchedEffect(file, supported, renderToken) {
        if (!supported) {
            odrState = OdrRenderState(error = "Unsupported document format: .$fileExtension")
            return@LaunchedEffect
        }
        odrState = OdrRenderState(loading = true)
        val result = withContext(Dispatchers.IO) {
            runCatching { OdrDocumentRenderer.render(context, file) }
        }
        odrState = result.fold(
            onSuccess = { OdrRenderState(rendered = it) },
            onFailure = {
                OdrRenderState(error = it.message ?: "Could not render this document")
            }
        )
    }

    val renderedForCleanup = odrState.rendered
    DisposableEffect(renderedForCleanup) {
        onDispose {
            renderedForCleanup?.cleanup()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        OdrDocumentView(
            state = odrState,
            backgroundColor = MaterialTheme.colorScheme.background
        )

        if (chromeVisible) {
            FloatingTopBar {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onClose) {
                        Icon(AppIcons.ArrowBack, contentDescription = "Back")
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = file.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (supported) {
                                "OpenDocument renderer • ${fileExtension.uppercase()}"
                            } else {
                                "Unsupported format • .$fileExtension"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { renderToken++ }) {
                        Icon(AppIcons.Refresh, contentDescription = "Reload document")
                    }
                    IconButton(onClick = { chromeVisible = false }) {
                        Icon(AppIcons.Fullscreen, contentDescription = "Hide document controls")
                    }
                }
            }
        } else {
            SmallFloatingActionButton(
                onClick = { chromeVisible = true },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
            ) {
                Icon(AppIcons.FullscreenExit, contentDescription = "Show document controls")
            }
        }
    }
}

private data class SpreadsheetGrid(
    val rows: List<List<String>>,
    val note: String?
)

private suspend fun readSpreadsheet(file: File): SpreadsheetGrid {
    return withContext(kotlinx.coroutines.Dispatchers.IO) {
        val ext = file.extension.lowercase()
        if (ext != "xlsx") {
            SpreadsheetGrid(rows = emptyList(), note = "Unsupported spreadsheet format")
        } else {
            parseXlsx(file)
        }
    }
}

private fun parseXlsx(file: File): SpreadsheetGrid {
    try {
        ZipFile(file).use { zip ->

            val sharedStrings = mutableListOf<String>()
            val sstEntry = zip.getEntry("xl/sharedStrings.xml")
            if (sstEntry != null) {
                zip.getInputStream(sstEntry).use { input ->
                    val parser = XmlPullParserFactory.newInstance().newPullParser()
                    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                    parser.setInput(input, "UTF-8")
                    val sb = StringBuilder()
                    var inText = false
                    var event = parser.eventType
                    while (event != XmlPullParser.END_DOCUMENT) {
                        when (event) {
                            XmlPullParser.START_TAG -> {
                                if (parser.name == "t") {
                                    inText = true
                                    sb.setLength(0)
                                }
                            }
                            XmlPullParser.TEXT -> if (inText) sb.append(parser.text)
                            XmlPullParser.END_TAG -> {
                                if (parser.name == "t" && inText) {
                                    sharedStrings.add(sb.toString())
                                    inText = false
                                }
                            }
                        }
                        event = parser.next()
                    }
                }
            }

            val sheetEntry = zip.entries()
                .asSequence()
                .map { it as ZipEntry }
                .firstOrNull { it.name.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }
                ?: return SpreadsheetGrid(rows = emptyList(), note = "No worksheet found in this .xlsx file.")

            val grid = LinkedHashMap<Int, MutableMap<Int, String>>()
            var maxCol = 0
            var errorNote: String? = null

            zip.getInputStream(sheetEntry).use { input ->
                val parser = XmlPullParserFactory.newInstance().newPullParser()
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                parser.setInput(input, "UTF-8")

                var currentRow = -1
                var cellRef = ""
                var cellType = ""
                var inlineText = StringBuilder()
                var inInline = false
                var inV = false
                var vText = StringBuilder()

                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    when (event) {
                        XmlPullParser.START_TAG -> {
                            when (parser.name) {
                                "row" -> {
                                    val rowAttr = parser.getAttributeValue(null, "r")
                                    currentRow = rowAttr?.toIntOrNull() ?: (currentRow + 1)
                                }
                                "c" -> {
                                    cellRef = parser.getAttributeValue(null, "r") ?: ""
                                    cellType = parser.getAttributeValue(null, "t") ?: ""
                                    inlineText.setLength(0)
                                    inInline = false
                                }
                                "is" -> { inInline = true }
                                "t" -> { if (inInline) inlineText.setLength(0) }
                                "v" -> { inV = true; vText.setLength(0) }
                            }
                        }
                        XmlPullParser.TEXT -> {
                            if (inV) vText.append(parser.text)
                            if (inInline) inlineText.append(parser.text)
                        }
                        XmlPullParser.END_TAG -> {
                            when (parser.name) {
                                "t" -> if (inInline) { /* inline string text gathered */ }
                                "c" -> {
                                    if (currentRow >= 0) {
                                        val rowMap = grid.getOrPut(currentRow) { LinkedHashMap() }
                                        val colIndex = columnIndex(cellRef)
                                        val value = when (cellType) {
                                            "s" -> {
                                                val idx = vText.toString().trim().toIntOrNull() ?: -1
                                                if (idx in sharedStrings.indices) sharedStrings[idx] else ""
                                            }
                                            "inlineStr" -> inlineText.toString()
                                            "str", "" -> vText.toString()
                                            "b" -> if (vText.toString().trim() == "1") "TRUE" else "FALSE"
                                            else -> vText.toString()
                                        }
                                        rowMap[colIndex] = value
                                        if (colIndex > maxCol) maxCol = colIndex
                                    }
                                }
                            }
                        }
                    }
                    event = parser.next()
                }
            }

            if (grid.isEmpty()) {
                return SpreadsheetGrid(rows = emptyList(), note = "This spreadsheet appears to be empty.")
            }

            val rows = ArrayList<List<String>>(grid.size)
            val colCount = maxCol + 1
            val sortedRows = grid.entries.sortedBy { it.key }
            for ((_, rowMap) in sortedRows) {
                val row = ArrayList<String>(colCount)
                for (c in 0 until colCount) {
                    row.add(rowMap[c] ?: "")
                }
                rows.add(row)
            }

            return SpreadsheetGrid(rows = rows, note = errorNote)
        }
    } catch (e: Exception) {
        return SpreadsheetGrid(rows = emptyList(), note = "Could not read spreadsheet: ${e.localizedMessage}")
    }
}

private fun columnIndex(cellRef: String): Int {
    var idx = 0
    for (ch in cellRef) {
        if (ch in 'A'..'Z') {
            idx = idx * 26 + (ch - 'A' + 1)
        } else if (ch in 'a'..'z') {
            idx = idx * 26 + (ch - 'a' + 1)
        } else {
            break
        }
    }
    return idx - 1
}

@Composable
private fun SpreadsheetViewer(grid: SpreadsheetGrid) {
    if (grid.rows.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp)
            ) {
                Icon(
                    imageVector = AppIcons.GridOn,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = grid.note ?: "No data",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
        return
    }

    val colCount = grid.rows.maxOfOrNull { it.size } ?: 0
    LazyColumn(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        itemsIndexed(grid.rows) { rowIndex, row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (rowIndex == 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
            ) {
                for (c in 0 until colCount) {
                    val cellText = if (c < row.size) row[c] else ""
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = cellText.ifEmpty { "" },
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = if (rowIndex == 0) FontWeight.Bold else FontWeight.Normal
                            ),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

private suspend fun readDocumentText(file: File, isPdf: Boolean): String {
    if (isPdf) return "PDF Document Rendered Below"
    val ext = file.extension.lowercase()
    if (ext in listOf("ppt", "pptx", "xls", "xlsx")) return ""
    return withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            when (ext) {
                "docx" -> readDocxText(file)
                "doc" -> "Legacy .doc files are not supported. Please convert to .docx or .txt."
                else -> {
                    if (file.exists() && file.length() < 10 * 1024 * 1024) {
                        file.readText()
                    } else {
                        "File is too large to display directly (>10MB)."
                    }
                }
            }
        } catch (e: Exception) {
            "Error reading document: ${e.localizedMessage}"
        }
    }
}

private fun readDocxText(file: File): String {
    try {
        ZipFile(file).use { zip ->
            val entry = zip.getEntry("word/document.xml")
                ?: return "Could not find document content in this .docx file."
            zip.getInputStream(entry).use { input ->
                val xml = input.bufferedReader(Charsets.UTF_8).readText()
                val out = StringBuilder(xml.length / 2)
                var i = 0
                while (i < xml.length) {
                    if (xml[i] == '<') {
                        val closeTag = xml.indexOf('>', i)
                        if (closeTag < 0) break
                        val tag = xml.substring(i + 1, closeTag)
                        when {
                            tag == "w:p" || tag.startsWith("w:p ") || tag.startsWith("w:p/") -> out.append('\n')
                            tag.startsWith("w:tab") -> out.append('\t')
                            tag.startsWith("w:br") || tag.startsWith("w:cr") -> out.append('\n')
                        }
                        i = closeTag + 1
                    } else {
                        out.append(xml[i])
                        i++
                    }
                }
                return out.toString().trim()
            }
        }
    } catch (e: Exception) {
        return "Could not open document: ${e.localizedMessage}"
    }
}
