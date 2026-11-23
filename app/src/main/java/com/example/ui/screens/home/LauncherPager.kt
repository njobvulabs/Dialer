package com.example.ui.screens.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.example.data.db.ContactEntity
import com.example.ui.MainViewModel
import com.example.ui.theme.AppCornerRadius
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The launcher's swipeable surface. Page 0 is the Home grid. Optional pages can
 * be placed left/right and toggled from Launcher Settings.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LauncherPager(
    viewModel: MainViewModel,
    shadeOpen: Boolean = false,
    favoriteContacts: List<ContactEntity> = emptyList(),
    missedCount: Int = 0,
    smsUnread: Int = 0,
    onLaunch: (String) -> Unit,
    onCallContact: (String) -> Unit = {},
    onSearchWeb: (String) -> Unit = {}
) {
    val showDashboardPage by viewModel.launcherShowDashboardPage.collectAsState()

    // The wallpaper is decoded ONCE here and shared by all pages.
    val wallpaperPath by viewModel.launcherWallpaper.collectAsState()
    val wallpaperMode by viewModel.launcherWallpaperMode.collectAsState()
    val wallpaperPaths by viewModel.launcherWallpaperPaths.collectAsState()
    val slideshowInterval by viewModel.launcherSlideshowIntervalSec.collectAsState()
    val slideshowShuffle by viewModel.launcherSlideshowShuffle.collectAsState()
    val reduceMotion by viewModel.reduceMotion.collectAsState()
    val opacity by viewModel.launcherBackgroundOpacity.collectAsState()

    val slideshowPaths = remember(wallpaperPaths, slideshowShuffle) {
        if (slideshowShuffle) wallpaperPaths.shuffled() else wallpaperPaths
    }
    var slideIndex by remember { mutableIntStateOf(0) }
    // Restart from the first slide whenever the set, mode or interval changes. Kept
    // out of the ticker so that a lifecycle transition cannot reset the position.
    LaunchedEffect(wallpaperMode, slideshowPaths, slideshowInterval) {
        slideIndex = 0
    }
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    // Plain ticker while the launcher is on screen; the launcher is only composed on
    // the Home surface, so this stops by itself when a tool or a phone tab is open.
    LaunchedEffect(wallpaperMode, slideshowPaths, slideshowInterval, lifecycleState) {
        if (wallpaperMode != 1 || slideshowPaths.size < 2) return@LaunchedEffect
        if (!lifecycleState.isAtLeast(Lifecycle.State.STARTED)) return@LaunchedEffect
        while (true) {
            delay(slideshowInterval.coerceAtLeast(15) * 1000L)
            slideIndex = (slideIndex + 1) % slideshowPaths.size
        }
    }
    LaunchedEffect(slideshowPaths.size) {
        if (slideshowPaths.isNotEmpty() && slideIndex >= slideshowPaths.size) slideIndex = 0
    }
    val activeWallpaperPath = if (wallpaperMode == 1 && slideshowPaths.isNotEmpty()) {
        slideshowPaths.getOrNull(slideIndex) ?: slideshowPaths.first()
    } else {
        wallpaperPath
    }

    // Hold the previous frame until the next one has decoded, so switching slides
    // never blanks the background on a slow device.
    var wallpaper by remember { mutableStateOf<ImageBitmap?>(null) }
    val wallpaperBlank = activeWallpaperPath == null || opacity <= 0.05f
    LaunchedEffect(activeWallpaperPath, wallpaperBlank) {
        if (wallpaperBlank) {
            wallpaper = null
            return@LaunchedEffect
        }
        val decoded = withContext(Dispatchers.IO) {
            runCatching { decodeSampledBitmap(activeWallpaperPath!!) }.getOrNull()
                ?.asImageBitmap()
        }
        if (decoded != null) wallpaper = decoded
    }

    val pages = buildList {
        add("home")
        if (showDashboardPage) add("dashboard")
    }
    val pageCount = pages.size
    val homeIndex = pages.indexOf("home").takeIf { it >= 0 } ?: 0

    val pagerState = rememberPagerState(initialPage = homeIndex, pageCount = { pageCount })
    val scope = rememberCoroutineScope()

    // If the current page is no longer present, snap back to Home.
    LaunchedEffect(pageCount) {
        if (pagerState.currentPage >= pageCount) {
            pagerState.animateScrollToPage(homeIndex)
        }
    }

    // Back on any non-Home page returns to Home before the app-exit handler runs.
    BackHandler(enabled = pages.getOrNull(pagerState.currentPage) != "home") {
        scope.launch { pagerState.animateScrollToPage(homeIndex) }
    }

    // The indicator only shows while the user is swiping (or just after), then fades
    // out so it doesn't sit on top of the page content.
    var indicatorVisible by remember { mutableStateOf(false) }
    val scrollInProgress = pagerState.isScrollInProgress
    LaunchedEffect(scrollInProgress) {
        if (scrollInProgress) {
            indicatorVisible = true
        } else {
            delay(1_200L)
            indicatorVisible = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = !shadeOpen,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            when (pages.getOrNull(page)) {
                "dashboard" -> DashboardPage(
                    viewModel = viewModel,
                    wallpaper = wallpaper,
                    wallpaperAnimate = !reduceMotion,
                    favoriteContacts = favoriteContacts,
                    missedCount = missedCount,
                    smsUnread = smsUnread,
                    onLaunch = onLaunch
                )
                else -> LauncherHomeScreen(
                    viewModel = viewModel,
                    favoriteContacts = favoriteContacts,
                    missedCount = missedCount,
                    smsUnread = smsUnread,
                    wallpaper = wallpaper,
                    wallpaperAnimate = !reduceMotion,
                    onLaunch = onLaunch,
                    onCallContact = onCallContact,
                    onSearchWeb = onSearchWeb
                )
            }
        }

        if (pageCount > 1) {
            AnimatedVisibility(
                visible = indicatorVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .zIndex(1f)
            ) {
                Row(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(bottom = 10.dp)
                        .clip(AppCornerRadius.pill)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(pageCount) { index ->
                        val active = pagerState.currentPage == index
                        Box(
                            modifier = Modifier
                                .size(if (active) 8.dp else 6.dp)
                                .clip(CircleShape)
                                .background(
                                    if (active) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                )
                        )
                    }
                }
            }
        }
    }
}
