package com.example.ui.screens.browser

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.components.AppIcons
import com.example.ui.components.AppSearchField

/** In-page find bar: query field, match counter, prev/next, close. */
@Composable
internal fun FindInPageBar(
    query: String,
    onQueryChange: (String) -> Unit,
    matchLabel: String,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppSearchField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = "Find in page...",
                leadingIcon = AppIcons.FindInPage,
                onClose = onClose,
                modifier = Modifier.weight(1f)
            )
            Text(matchLabel, style = MaterialTheme.typography.labelSmall)
            IconButton(onClick = onPrev) { Text("↑", fontWeight = FontWeight.Bold) }
            IconButton(onClick = onNext) { Text("↓", fontWeight = FontWeight.Bold) }
        }
    }
}
