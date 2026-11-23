package com.example.ui.screens.dialer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ui.components.AppIcons
import com.example.ui.components.AppSecondaryButton
import com.example.ui.components.landscapeContentWidth
import com.example.data.db.SpeedDialEntity
import com.example.ui.MainViewModel
import com.example.ui.screens.contacts.ContactsScreen
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhoneMainScreen(
    viewModel: MainViewModel,
    onMakeCall: (String, Int) -> Unit,
    initialTab: Int = 0,
    onTabChanged: (Int) -> Unit = {},
    smsInitialNumber: String = "",
    onSmsInitialNumberConsumed: () -> Unit = {}
) {
    val speedDials by viewModel.speedDials.collectAsState()

    val pagerState = rememberPagerState(initialPage = initialTab, pageCount = { 3 })

    var speedDialSimDialogItem by remember { mutableStateOf<SpeedDialEntity?>(null) }

    // Synchronize initialTab updates from navigation parameters
    LaunchedEffect(initialTab) {
        if (initialTab in 0..2) {
            pagerState.scrollToPage(initialTab)
        }
    }

    // Report the settled sub-tab back up so the launcher dock can highlight it,
    // including changes made by tapping the in-screen pills.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect { onTabChanged(it) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // --- Speed Dial Horizontal Strip ---
        if (speedDials.isNotEmpty()) {
            Column {
                Text(
                    text = "Quick Speed Dial",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp)
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().landscapeContentWidth().align(Alignment.CenterHorizontally)
                ) {
                    items(speedDials, key = { it.digit }) { sd ->
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.medium,
                            tonalElevation = 2.dp,
                            modifier = Modifier
                                .combinedClickable(
                                    onClick = { onMakeCall(sd.phoneNumber, 1) },
                                    onLongClick = { speedDialSimDialogItem = sd }
                                )
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Speed Dial Key Digit Badge
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = sd.digit.toString(),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onPrimary
                                        )
                                    }
                                }
                                Column {
                                    Text(
                                        text = sd.label,
                                        style = MaterialTheme.typography.labelLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = sd.phoneNumber,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // --- Sub-tab page content (Calls / Contacts / SMS) ---
        // The in-screen Recents/Contacts/SMS pill bar was removed: the launcher
        // dock already switches these sub-tabs via showPhone()/initialTab, and the
        // dock highlight follows in-screen changes through onTabChanged.
        // userScrollEnabled = false: no inner horizontal drag should compete with
        // the dock / in-screen two-column layouts.
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = false,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) { page ->
                when (page) {
                0 -> CallHistoryScreen(
                    viewModel = viewModel,
                    onMakeCall = onMakeCall
                )
                1 -> ContactsScreen(
                    viewModel = viewModel,
                    onMakeCall = onMakeCall
                )
                2 -> com.example.ui.screens.sms.SmsScreen(
                    viewModel = viewModel,
                    initialNumber = smsInitialNumber,
                    onInitialNumberConsumed = onSmsInitialNumberConsumed
                )
            }
        }
    }

    // --- Long-Press Speed Dial SIM Selector Dialog ---
    speedDialSimDialogItem?.let { sd ->
        // Both choices are equal-weight actions, so neither is the "confirm". Using
        // confirmButton for a Row of two buttons is what forced the old hard-coded
        // green/blue fills; themed secondary buttons carry the SIM identity instead
        // (1 = primary container, 2 = tertiary container).
        AlertDialog(
            onDismissRequest = { speedDialSimDialogItem = null },
            title = { Text("Call ${sd.label}") },
            text = { Text("Select SIM slot to make a call to ${sd.phoneNumber}:") },
            confirmButton = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    AppSecondaryButton(
                        text = "SIM 1",
                        onClick = {
                            onMakeCall(sd.phoneNumber, 1)
                            speedDialSimDialogItem = null
                        },
                        icon = AppIcons.SimCard,
                        modifier = Modifier.weight(1f)
                    )
                    AppSecondaryButton(
                        text = "SIM 2",
                        onClick = {
                            onMakeCall(sd.phoneNumber, 2)
                            speedDialSimDialogItem = null
                        },
                        icon = AppIcons.SimCard,
                        modifier = Modifier.weight(1f)
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { speedDialSimDialogItem = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}
