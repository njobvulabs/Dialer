package com.example.ui.screens.home

import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.AdminReceiver
import com.example.data.db.ContactEntity
import com.example.ui.LAUNCHER_LAYOUT_LIST
import com.example.ui.MainViewModel
import com.example.ui.components.AppContentMaxWidth
import com.example.ui.components.AppIcons
import com.example.service.AudioEngine
import com.example.ui.components.AppSearchField
import com.example.ui.screens.apps.AppItem
import com.example.ui.screens.apps.LAUNCHER_SECTIONS
import com.example.ui.screens.webapp.CustomWebApps
import com.example.ui.theme.AppCornerRadius
import com.example.util.ContactNumbers
import com.example.util.ExpressionEval
import com.example.util.rememberSavedGridState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The launcher-style landing screen. The clock/date header, search toggle, missed/
 * unread summary chips and the pinned + favourites rows live INSIDE the grid as
 * full-span items so they scroll away with the content. Tiles are grouped under
 * labelled section headers. Tapping a tile calls [onLaunch] with the route id
 * MainActivity understands; long-pressing pins/unpins it. A favourite contact chip
 * dials through [onCallContact].
 */
@Composable
fun LauncherHomeScreen(
    viewModel: MainViewModel,
    favoriteContacts: List<ContactEntity> = emptyList(),
    missedCount: Int = 0,
    smsUnread: Int = 0,
    wallpaper: ImageBitmap?,
    wallpaperAnimate: Boolean = true,
    onLaunch: (String) -> Unit,
    onCallContact: (String) -> Unit = {},
    onSearchWeb: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current

    val columns by viewModel.launcherColumns.collectAsState()
    val layout by viewModel.launcherLayout.collectAsState()
    val showClock by viewModel.launcherShowClock.collectAsState()
    val clockFormat by viewModel.launcherClockFormat.collectAsState()
    val showSections by viewModel.launcherShowSections.collectAsState()
    val showRecent by viewModel.launcherShowRecent.collectAsState()
    val showLabels by viewModel.launcherShowLabels.collectAsState()
    val launcherIconShape by viewModel.launcherIconShape.collectAsState()
    val launcherSearchAll by viewModel.launcherSearchAll.collectAsState()
    val pinnedIds by viewModel.pinnedAppIds.collectAsState()
    val recentIds by viewModel.recentAppIds.collectAsState()
    val tileSize by viewModel.launcherTileSize.collectAsState()
    val opacity by viewModel.launcherBackgroundOpacity.collectAsState()
    val scrim by viewModel.launcherScrim.collectAsState()
    val showNowPlaying by viewModel.launcherShowNowPlaying.collectAsState()
    val showNotificationBadges by viewModel.launcherShowNotificationBadges.collectAsState()
    val doubleTapLock by viewModel.launcherDoubleTapLock.collectAsState()
    val notificationCounts by viewModel.notificationCounts.collectAsState()

    val isList = layout == LAUNCHER_LAYOUT_LIST
    var showSearch by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    // Separate scroll keys per layout: an index saved in the grid means a different
    // item in the one-column list, so restoring it would jump to a random place.
    val gridState = rememberSavedGridState(
        if (isList) "launcher_list" else "launcher",
        viewModel
    )

    data class InstalledAppEntry(
        val label: String,
        val packageName: String,
        val icon: Drawable
    )

    val installedApps by produceState(emptyList<InstalledAppEntry>(), Unit) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val pm = context.packageManager
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val apps = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                apps.mapNotNull { resolveInfo ->
                    val appInfo = resolveInfo.activityInfo.applicationInfo
                    if (appInfo.packageName == context.packageName) return@mapNotNull null
                    val label = runCatching { pm.getApplicationLabel(appInfo).toString() }.getOrNull() ?: return@mapNotNull null
                    val icon = runCatching { pm.getApplicationIcon(appInfo) }.getOrNull() ?: return@mapNotNull null
                    InstalledAppEntry(label, appInfo.packageName, icon)
                }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
            }.getOrDefault(emptyList())
        }
    }

    val customWebApps = remember { CustomWebApps.list(context) }

    // Filter first, THEN group, so a section header never shows with no tiles.
    val sections = remember(query, launcherSearchAll, installedApps) {
        val q = query.trim()
        if (q.isEmpty()) {
            LAUNCHER_SECTIONS.map { (name, items) ->
                name to (if (name == "Web apps") items + customWebApps.map { CustomWebApps.toAppItem(it) } else items)
            }
        } else {
            val baseList = if (launcherSearchAll) {
                LAUNCHER_SECTIONS.flatMap { it.second }
            } else {
                LAUNCHER_SECTIONS.flatMap { it.second }
            }
            val matchedBuiltIn = baseList.filter { item ->
                item.title.contains(q, ignoreCase = true) ||
                    item.description.contains(q, ignoreCase = true) ||
                    item.keywords.contains(q, ignoreCase = true)
            }
            val result = mutableListOf<Pair<String, List<AppItem>>>()
            if (matchedBuiltIn.isNotEmpty()) {
                result.add("Apps" to matchedBuiltIn.distinctBy { it.id })
            }
            if (launcherSearchAll && installedApps.isNotEmpty()) {
                val matchedInstalled = installedApps.filter { app ->
                    app.label.contains(q, ignoreCase = true) || app.packageName.contains(q, ignoreCase = true)
                }
                if (matchedInstalled.isNotEmpty()) {
                    val items = matchedInstalled.map { app ->
                        AppItem(
                            id = "pkg:${app.packageName}",
                            title = app.label,
                            icon = AppIcons.Apps,
                            description = app.packageName,
                            keywords = ""
                        )
                    }
                    result.add("Installed apps" to items.distinctBy { it.id })
                }
            }
            result
        }
    }
    val anyResults = sections.isNotEmpty() || query.trim().isEmpty()
    val allItems = remember { LAUNCHER_SECTIONS.flatMap { it.second } }
    val pinnedItems = remember(pinnedIds) {
        pinnedIds.mapNotNull { id -> allItems.firstOrNull { it.id == id } }
    }
    val recentItems = remember(recentIds) {
        recentIds.mapNotNull { id -> allItems.firstOrNull { it.id == id } }
    }
    val favorites = remember(favoriteContacts) { favoriteContacts.take(12) }

    val trimmedQuery = query.trim()
    // The address book is only needed for smart search; don't keep the merged
    // local+device flow subscribed while the launcher is idle.
    val contacts = if (trimmedQuery.isBlank()) {
        emptyList<ContactEntity>()
    } else {
        viewModel.combinedContacts.collectAsState().value
    }
    val searchDigits = remember(trimmedQuery) { trimmedQuery.filter { it.isDigit() } }
    val contactMatches = remember(trimmedQuery, contacts) {
        runCatching {
            if (trimmedQuery.isBlank()) emptyList()
            else contacts.filter { contact ->
                contact.name.contains(trimmedQuery, ignoreCase = true) ||
                    (searchDigits.isNotEmpty() &&
                        ContactNumbers.keys(contact.phoneNumber).any { it.contains(searchDigits) })
            }.take(5)
        }.getOrElse { emptyList() }
    }
    val dialNumber = trimmedQuery.takeIf { q ->
        q.isNotBlank() &&
            searchDigits.length >= 3 &&
            q.all { it.isDigit() || it in "+ -()" }
    }
    val dialMatchesContact = dialNumber != null && contactMatches.any { contact ->
        ContactNumbers.keys(contact.phoneNumber).any { it == searchDigits }
    }
    val calcResult = remember(trimmedQuery) {
        runCatching {
            if (trimmedQuery.isBlank() || !ExpressionEval.looksLikeExpression(trimmedQuery)) {
                null
            } else {
                ExpressionEval.evaluateExpression(trimmedQuery)
            }
        }.getOrNull()
    }

    // Missed-call count on the Phone tile, unread inbox SMS on the Messages tile,
    // and per-package notification counts for installed app search results.
    fun badgeFor(id: String): Int = when {
        id == "phone" -> missedCount
        id == "messages" -> smsUnread
        showNotificationBadges && id.startsWith("pkg:") ->
            notificationCounts[id.removePrefix("pkg:")] ?: 0
        else -> 0
    }

    val swatch = when (tileSize) {
        0 -> 48.dp
        2 -> 64.dp
        else -> 56.dp
    }
    val iconSize = swatch * 0.5f

    LaunchedEffect(showSearch) {
        if (showSearch) runCatching { focusRequester.requestFocus() }
    }

    // Back closes the search field before it can leave the Home screen.
    BackHandler(enabled = showSearch) {
        showSearch = false
        query = ""
        keyboard?.hide()
    }

    fun closeKeyboardAndLaunch(id: String) {
        keyboard?.hide()
        if (id.startsWith("pkg:")) {
            val pkg = id.removePrefix("pkg:")
            val pm = context.packageManager
            val launch = runCatching { pm.getLaunchIntentForPackage(pkg) }.getOrNull()
            if (launch != null) {
                runCatching { context.startActivity(launch) }
                return
            }
        }
        onLaunch(id)
    }

    val configuration = LocalConfiguration.current
    val layoutDirection = LocalLayoutDirection.current
    val screenWidthDp = configuration.screenWidthDp
    val adaptiveGrid = !isList && (columns == -1 || screenWidthDp >= 700)
    val fixedGridColumns = when (columns) {
        6 -> 6
        5 -> 5
        else -> 4
    }
    val gridColumns = when {
        isList -> GridCells.Fixed(1)
        adaptiveGrid -> GridCells.Adaptive(84.dp)
        else -> GridCells.Fixed(fixedGridColumns)
    }
    val gridMaxWidth = when {
        isList || !adaptiveGrid -> AppContentMaxWidth
        else -> (screenWidthDp - 24).coerceAtLeast(360).dp
    }

    // The launcher is drawn edge-to-edge (MainActivity drops the Scaffold inset for
    // it) so a wallpaper fills behind the status and navigation bars. The wallpaper
    // Box fills the whole window; the grid keeps its own content inset below.
    val gridInsets = WindowInsets.safeDrawing.asPaddingValues()

    Box(modifier = Modifier.fillMaxSize()) {
        // Readability scrim so tile labels/headers stay legible on busy photos.
        LauncherBackground(wallpaper = wallpaper, opacity = opacity, scrim = scrim, crossfade = wallpaperAnimate)
        LazyVerticalGrid(
            columns = gridColumns,
            state = gridState,
            contentPadding = PaddingValues(
                start = gridInsets.calculateStartPadding(layoutDirection) + 12.dp,
                end = gridInsets.calculateEndPadding(layoutDirection) + 12.dp,
                top = gridInsets.calculateTopPadding() + 12.dp,
                bottom = gridInsets.calculateBottomPadding() + 24.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .widthIn(max = gridMaxWidth)
        ) {
            item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    GlassPanel(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        shape = AppCornerRadius.xl,
                        elevation = 4.dp,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val clockModifier = Modifier
                                .weight(1f)
                                .then(
                                    if (doubleTapLock) {
                                        Modifier.pointerInput(Unit) {
                                            detectTapGestures(
                                                onDoubleTap = { lockScreen(context) }
                                            )
                                        }
                                    } else {
                                        Modifier
                                    }
                                )
                            if (showClock) {
                                ClockHeader(
                                    clockFormat = clockFormat,
                                    modifier = clockModifier
                                )
                            } else {
                                Spacer(modifier = clockModifier)
                            }
                            if (doubleTapLock) {
                                IconButton(onClick = { lockScreen(context) }) {
                                    Icon(
                                        AppIcons.Lock,
                                        contentDescription = "Lock screen",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            IconButton(onClick = { showSearch = !showSearch }) {
                                Icon(
                                    AppIcons.Search,
                                    contentDescription = "Search apps",
                                    tint = if (showSearch || query.isNotBlank())
                                        MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    AnimatedVisibility(visible = showSearch) {
                        AppSearchField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = "Search apps, contacts or calculate",
                            onClose = { showSearch = false },
                            focusRequester = focusRequester,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    if (trimmedQuery.isBlank() && showNowPlaying) {
                        val nowPlayingActive = AudioEngine.active.value
                        if (nowPlayingActive) {
                            NowPlayingCard(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                onOpen = { onLaunch("audio") }
                            )
                        }
                    }

                    if (trimmedQuery.isNotBlank()) {
                        SmartSearchActions(
                            query = trimmedQuery,
                            contactMatches = contactMatches,
                            dialNumber = dialNumber.takeIf { !dialMatchesContact },
                            calcResult = calcResult,
                            onCallContact = { keyboard?.hide(); onCallContact(it) },
                            onSearchWeb = { keyboard?.hide(); onSearchWeb(it) }
                        )
                    }

                    // Summary chips: only when there is something to report.
                    if (missedCount > 0 || smsUnread > 0) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (missedCount > 0) {
                                SummaryChip(
                                    icon = AppIcons.History,
                                    text = if (missedCount == 1) "1 missed call"
                                    else "$missedCount missed calls",
                                    onClick = { closeKeyboardAndLaunch("phone") }
                                )
                            }
                            if (smsUnread > 0) {
                                SummaryChip(
                                    icon = AppIcons.Sms,
                                    text = if (smsUnread == 1) "1 unread message"
                                    else "$smsUnread unread messages",
                                    onClick = { closeKeyboardAndLaunch("messages") }
                                )
                            }
                        }
                    }

                    if (showRecent && query.isBlank() && recentItems.isNotEmpty()) {
                        RowTitle("Recent")
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) {
                            items(recentItems, key = { it.id }) { item ->
                                LauncherTile(
                                    item = item,
                                    pinned = false,
                                    swatch = swatch,
                                    iconSize = iconSize,
                                    onClick = { closeKeyboardAndLaunch(item.id) },
                                    badgeCount = badgeFor(item.id),
                                    showLabel = showLabels,
                                    iconShape = launcherIconShape
                                )
                            }
                        }
                    }

                    if (query.isBlank() && favorites.isNotEmpty()) {
                        RowTitle("Favourites")
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) {
                            items(favorites, key = { it.id }) { contact ->
                                FavoriteContactTile(
                                    contact = contact,
                                    swatch = swatch,
                                    iconSize = iconSize,
                                    onClick = {
                                        keyboard?.hide()
                                        onCallContact(ContactNumbers.primary(contact.phoneNumber))
                                    }
                                )
                            }
                        }
                    }

                    if (query.isBlank() && pinnedItems.isNotEmpty()) {
                        RowTitle("Pinned")
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) {
                            items(pinnedItems, key = { it.id }) { item ->
                                LauncherTile(
                                    item = item,
                                    pinned = true,
                                    swatch = swatch,
                                    iconSize = iconSize,
                                    onClick = { closeKeyboardAndLaunch(item.id) },
                                    onTogglePin = { viewModel.togglePinnedApp(item.id) },
                                    badgeCount = badgeFor(item.id),
                                    showLabel = showLabels,
                                    iconShape = launcherIconShape
                                )
                            }
                        }
                    }
                }
            }

            val hasQuery = query.trim().isNotEmpty()
            if (hasQuery && !sections.any { it.second.isNotEmpty() }) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No apps match \"$query\"",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else if (!hasQuery || anyResults) {
                sections.forEach { (name, items) ->
                    if (showSections) {
                        item(key = "section_$name", span = { GridItemSpan(maxLineSpan) }) {
                            RowTitle(name, topPadding = 10.dp)
                        }
                    }
                    items(items, key = { it.id }) { item ->
                        if (isList) {
                        LauncherListRow(
                            item = item,
                            pinned = pinnedIds.contains(item.id),
                            onClick = { closeKeyboardAndLaunch(item.id) },
                            onTogglePin = if (item.id.startsWith("pkg:")) null else { { viewModel.togglePinnedApp(item.id) } },
                            badgeCount = badgeFor(item.id),
                            iconShape = launcherIconShape
                        )
                        } else {
                            LauncherTile(
                                item = item,
                                pinned = pinnedIds.contains(item.id),
                                swatch = swatch,
                                iconSize = iconSize,
                                onClick = { closeKeyboardAndLaunch(item.id) },
                                onTogglePin = if (item.id.startsWith("pkg:")) null else { { viewModel.togglePinnedApp(item.id) } },
                                badgeCount = badgeFor(item.id),
                                showLabel = showLabels,
                                iconShape = launcherIconShape
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Pill showing a count summary (missed calls / unread messages) that opens its tab. */
@Composable
private fun SummaryChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    onClick: () -> Unit
) {
    GlassPanel(
        onClick = onClick,
        shape = AppCornerRadius.pill,
        tint = MaterialTheme.colorScheme.errorContainer,
        elevation = 2.dp,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

/** Search result rows shown above the app grid while a query is active. */
@Composable
private fun SmartSearchActions(
    query: String,
    contactMatches: List<ContactEntity>,
    dialNumber: String?,
    calcResult: String?,
    onCallContact: (String) -> Unit,
    onSearchWeb: (String) -> Unit
) {
    val context = LocalContext.current
    Column {
        RowTitle("Smart search", topPadding = 8.dp)
        contactMatches.forEach { contact ->
            val number = ContactNumbers.primary(contact.phoneNumber)
            if (number.isNotBlank()) {
                SearchActionResult(
                    icon = AppIcons.Person,
                    title = contact.name,
                    subtitle = number,
                    onClick = { onCallContact(number) }
                )
            }
        }
        dialNumber?.let { number ->
            SearchActionResult(
                icon = AppIcons.Call,
                title = "Call $number",
                subtitle = "Dial this number",
                onClick = { onCallContact(number) }
            )
        }
        calcResult?.let { result ->
            SearchActionResult(
                icon = AppIcons.Calculate,
                title = "= $result",
                subtitle = query,
                onClick = {
                    runCatching {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(ClipData.newPlainText("Result", result))
                    }
                    Toast.makeText(context, "Result copied", Toast.LENGTH_SHORT).show()
                }
            )
        }
        SearchActionResult(
            icon = AppIcons.Public,
            title = "Search the web",
            subtitle = "\"$query\" on DuckDuckGo",
            onClick = { onSearchWeb(query) }
        )
    }
}

@Composable
private fun SearchActionResult(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit
) {
    GlassPanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        onClick = onClick,
        elevation = 2.dp,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** Locks the device when the admin force-lock policy is active, otherwise asks for it. */
private fun lockScreen(context: Context) {
    val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
    val admin = ComponentName(context, AdminReceiver::class.java)
    if (dpm.isAdminActive(admin)) {
        val locked = runCatching { dpm.lockNow() }.isSuccess
        if (!locked) {
            // The admin can be active from an older build that did not declare the
            // force-lock policy. Android caches that policy set at activation time,
            // so the user must toggle the admin once for the new policy to apply.
            Toast.makeText(
                context,
                "Re-enable NJ-Dialer device admin to allow locking",
                Toast.LENGTH_LONG
            ).show()
            runCatching {
                context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
            }
        }
    } else {
        Toast.makeText(
            context,
            "Activate NJ-Dialer device admin to lock the screen",
            Toast.LENGTH_SHORT
        ).show()
        runCatching {
            context.startActivity(
                Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                    putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Allow NJ-Dialer to lock the screen when you double-tap the Home clock."
                    )
                }
            )
        }
    }
}

/** A favourite-contact launcher tile: avatar initial + name, tap dials. */
@Composable
private fun FavoriteContactTile(
    contact: ContactEntity,
    swatch: androidx.compose.ui.unit.Dp,
    iconSize: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(swatch + 24.dp)
            .clip(AppCornerRadius.md)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = "Call ${contact.name}"
            }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Surface(
            shape = AppCornerRadius.md,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(swatch)
        ) {
            Box(contentAlignment = Alignment.Center) {
                val initial = contact.name.trim().firstOrNull()?.uppercase() ?: "#"
                Text(
                    text = initial,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
        Text(
            text = contact.name,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Decodes a launcher background, downscaling to [targetLongEdge] px on the long
 * edge so a full-resolution camera photo cannot OOM the grid. Decodes as RGB_565
 * (a wallpaper needs no alpha) and finishes the downscale exactly, because
 * [android.graphics.BitmapFactory.Options.inSampleSize] is power-of-two and can
 * leave the bitmap up to twice the target. Returns null on any failure.
 */
internal fun decodeSampledBitmap(path: String, targetLongEdge: Int = 1080): android.graphics.Bitmap? {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(path, bounds)
    val srcLongEdge = maxOf(bounds.outWidth, bounds.outHeight)
    if (srcLongEdge <= 0) return null

    var sample = 1
    while (srcLongEdge / (sample * 2) >= targetLongEdge) sample *= 2

    // JPEG cannot carry alpha, so RGB_565 halves the memory with no visual change.
    // Other formats may be transparent, so keep ARGB_8888 for them.
    val jpeg = bounds.outMimeType?.equals("image/jpeg", ignoreCase = true) == true
    val opts = android.graphics.BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = if (jpeg) {
            android.graphics.Bitmap.Config.RGB_565
        } else {
            android.graphics.Bitmap.Config.ARGB_8888
        }
    }
    val sampled = android.graphics.BitmapFactory.decodeFile(path, opts) ?: return null

    val sampledLongEdge = maxOf(sampled.width, sampled.height)
    if (sampledLongEdge <= targetLongEdge) return sampled

    val scale = targetLongEdge.toFloat() / sampledLongEdge
    val targetWidth = (sampled.width * scale).toInt().coerceAtLeast(1)
    val targetHeight = (sampled.height * scale).toInt().coerceAtLeast(1)
    return runCatching {
        android.graphics.Bitmap.createScaledBitmap(sampled, targetWidth, targetHeight, true)
            .also { sampled.recycle() }
    }.getOrElse {
        sampled.recycle()
        null
    }
}
