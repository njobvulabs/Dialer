package com.example.ui.screens.home

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.db.ContactEntity
import com.example.ui.MainViewModel
import com.example.ui.components.AppIcons
import com.example.ui.screens.sys.SystemShortcuts
import com.example.ui.theme.AppCornerRadius
import com.example.util.rememberSavedGridState

@Composable
fun DashboardPage(
    viewModel: MainViewModel,
    wallpaper: ImageBitmap?,
    wallpaperAnimate: Boolean = true,
    favoriteContacts: List<ContactEntity> = emptyList(),
    missedCount: Int = 0,
    smsUnread: Int = 0,
    onLaunch: (String) -> Unit = {}
) {
    val showClock by viewModel.launcherShowClock.collectAsState()
    val clockFormat by viewModel.launcherClockFormat.collectAsState()
    val opacity by viewModel.launcherBackgroundOpacity.collectAsState()
    val scrim by viewModel.launcherScrim.collectAsState()
    val context = LocalContext.current

    val gridInsets = WindowInsets.systemBars.asPaddingValues()
    val appStoreItems = SystemShortcuts.items.filter {
        it.id == "system:apps" || it.id == "system:play" ||
            it.id == "system:palm" || it.id == "system:phonemaster"
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LauncherBackground(wallpaper = wallpaper, opacity = opacity, scrim = scrim, crossfade = wallpaperAnimate)
        LazyVerticalGrid(
            columns = GridCells.Fixed(1),
            state = rememberSavedGridState("launcher_tools_dashboard", viewModel),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = gridInsets.calculateTopPadding() + 12.dp,
                bottom = gridInsets.calculateBottomPadding() + 68.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item(key = "dash_header", span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    if (showClock) {
                        GlassPanel(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp),
                            shape = AppCornerRadius.xl,
                            elevation = 4.dp,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            ClockHeader(
                                clockFormat = clockFormat,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "At a glance",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        GlassPanel(
                            modifier = Modifier.weight(1f),
                            onClick = { onLaunch("phone") },
                            elevation = 3.dp,
                            contentPadding = PaddingValues(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(AppIcons.Call, contentDescription = null)
                                Spacer(Modifier.size(10.dp))
                                Column {
                                    Text("Missed", style = MaterialTheme.typography.labelSmall)
                                    Text("$missedCount", style = MaterialTheme.typography.titleMedium)
                                }
                            }
                        }
                        GlassPanel(
                            modifier = Modifier.weight(1f),
                            onClick = { onLaunch("messages") },
                            elevation = 3.dp,
                            contentPadding = PaddingValues(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(AppIcons.Message, contentDescription = null)
                                Spacer(Modifier.size(10.dp))
                                Column {
                                    Text("Unread SMS", style = MaterialTheme.typography.labelSmall)
                                    Text("$smsUnread", style = MaterialTheme.typography.titleMedium)
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        GlassPanel(
                            modifier = Modifier.weight(1f),
                            onClick = { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) },
                            elevation = 3.dp,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    AppIcons.Wifi,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text("Wi-Fi", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        GlassPanel(
                            modifier = Modifier.weight(1f),
                            onClick = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
                            elevation = 3.dp,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    AppIcons.Bluetooth,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text("Bluetooth", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
            item(key = "dash_apps_header", span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        "Apps & Stores",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
            items(appStoreItems, key = { it.id }) { item ->
                LauncherListRow(
                    item = item,
                    pinned = false,
                    onClick = { SystemShortcuts.launch(context, item.id) },
                    onTogglePin = null,
                    glass = true
                )
            }
        }
    }
}
