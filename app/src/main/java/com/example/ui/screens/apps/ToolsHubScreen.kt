package com.example.ui.screens.apps

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.ui.MainViewModel
import com.example.ui.components.landscapeContentWidth
import com.example.ui.components.AppIcons
import com.example.ui.theme.AppCornerRadius
import com.example.util.rememberSavedGridState

// The fourth main tab (Tools): every utility that used to be buried in the File
// Manager's ⋮ menu (Samba, Wi-Fi Transfer, Phone Info, APK Backup, Text
// Extract, Recycle bin) and in System Tools (Flashlight, Sound Recorder,
// Calculator) plus Internet Radio (which used to open only inside Audio
// Player). Recycle bin is deliberately the FIRST tile (it is the undo path
// for a delete); Internet Radio sits just after Sound Recorder.
// Phone Info and APK Backup are TWO separate tiles here — they used to share
// one screen with a pill switcher, and the switcher is gone (see
// DeviceInfoScreen): "deviceinfo" opens the info list, "apkbackup" the app
// list.
// Linux Terminal moved to bottom nav bar (5th tab).
@Composable
fun ToolsHubScreen(
    viewModel: MainViewModel,
    onLaunchApp: (String) -> Unit
) {
    val toolList = TOOLS

    val gridState = rememberSavedGridState("tools", viewModel)

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 140.dp),
        state = gridState,
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        items(toolList) { app ->
            // Same tile styling as AppsHubScreen: surfaceContainer + AppCornerRadius.md,
            // titleMedium/bodySmall used verbatim (no font-weight / size overrides).
            Card(
                onClick = { onLaunchApp(app.id) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                shape = AppCornerRadius.md,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = app.icon,
                        contentDescription = app.title,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = app.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = app.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
