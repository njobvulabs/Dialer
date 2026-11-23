package com.example.util

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.snapshotFlow
import com.example.ui.MainViewModel
import com.example.ui.components.AppIcons
import kotlinx.coroutines.flow.distinctUntilChanged

// Restores the last scroll position for a scrollable list keyed by screen.
@Composable
fun rememberSavedListState(key: String, viewModel: MainViewModel): LazyListState {
    // `key(key)` recreates the state when a screen swaps its key (e.g. the launcher's
    // grid/list switch), so the saved index is restored from — and written back to —
    // the key that is actually on screen.
    val state = key(key) {
        rememberLazyListState(initialFirstVisibleItemIndex = viewModel.getScrollPosition(key))
    }
    LaunchedEffect(state) {
        snapshotFlow { state.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { viewModel.setScrollPosition(key, it) }
    }
    return state
}

// Restores the last scroll position for a grid keyed by screen.
@Composable
fun rememberSavedGridState(key: String, viewModel: MainViewModel): LazyGridState {
    val state = key(key) {
        rememberLazyGridState(initialFirstVisibleItemIndex = viewModel.getScrollPosition(key))
    }
    LaunchedEffect(state) {
        snapshotFlow { state.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { viewModel.setScrollPosition(key, it) }
    }
    return state
}

// Restores the last scroll position for a staggered grid keyed by screen.
@Composable
fun rememberSavedStaggeredGridState(key: String, viewModel: MainViewModel): LazyStaggeredGridState {
    val state = key(key) {
        rememberLazyStaggeredGridState(initialFirstVisibleItemIndex = viewModel.getScrollPosition(key))
    }
    LaunchedEffect(state) {
        snapshotFlow { state.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { viewModel.setScrollPosition(key, it) }
    }
    return state
}