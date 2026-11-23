package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AppCornerRadius
import java.util.Locale

/** Recommended max width for readable content in landscape. */
val AppContentMaxWidth: Dp = 840.dp

fun Modifier.landscapeContentWidth(max: Dp = AppContentMaxWidth): Modifier =
    this.fillMaxWidth().widthIn(max = max)

/** Consistent human-readable byte size across the app (e.g. "1.2 MB", "840 B"). */
fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"

    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}

/** Standard confirm dialog: warning red confirm, neutral cancel. */
@Composable
fun AppConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String = "Delete",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = {
                onConfirm()
            }) { Text(confirmLabel, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/** Centered empty-state placeholder shared by list screens. */
@Composable
fun AppEmptyState(
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = AppIcons.Inbox
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(48.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                message,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
        }
    }
}

@Composable
private fun glassTopBarFillBrush(): Brush {
    val colors = MaterialTheme.colorScheme
    return Brush.linearGradient(
        listOf(
            colors.surface.copy(alpha = 0.92f),
            colors.surfaceContainerHighest.copy(alpha = 0.72f),
            colors.surface.copy(alpha = 0.58f)
        )
    )
}

@Composable
private fun glassTopBarEdgeBrush(): Brush {
    val colors = MaterialTheme.colorScheme
    return Brush.linearGradient(
        listOf(
            Color.White.copy(alpha = 0.34f),
            colors.onSurface.copy(alpha = 0.08f),
            colors.primary.copy(alpha = 0.22f)
        )
    )
}

@Composable
private fun glassTopBarSheenBrush(): Brush = Brush.verticalGradient(
    listOf(Color.White.copy(alpha = 0.18f), Color.Transparent)
)

/** Floating rounded top bar ("rounded card with margins") used by list screens. */
@Composable
fun FloatingTopBar(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
    shape: Shape = MaterialTheme.shapes.large,
    useGlass: Boolean = false,
    glassTint: Color? = null,
    content: @Composable RowScope.() -> Unit
) {
    val fillBrush = if (glassTint != null) {
        Brush.linearGradient(
            listOf(
                glassTint.copy(alpha = 0.88f),
                glassTint.copy(alpha = 0.72f),
                glassTint.copy(alpha = 0.58f)
            )
        )
    } else {
        glassTopBarFillBrush()
    }

    val safeHorizontal = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
    if (useGlass) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .windowInsetsPadding(safeHorizontal)
                .padding(horizontal = 12.dp)
                .padding(top = 8.dp)
                .shadow(4.dp, shape, clip = false)
                .clip(shape)
                .background(fillBrush)
        ) {
            Box(Modifier.matchParentSize().background(glassTopBarSheenBrush()))
            Box(Modifier.matchParentSize().border(1.dp, glassTopBarEdgeBrush(), shape))
            Row(
                modifier = Modifier.padding(contentPadding),
                verticalAlignment = Alignment.CenterVertically
            ) { content() }
        }
    } else {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .windowInsetsPadding(safeHorizontal)
                .padding(horizontal = 12.dp)
                .padding(top = 8.dp),
            shape = shape,
            tonalElevation = 3.dp,
            color = MaterialTheme.colorScheme.surfaceContainer
        ) {
            Row(
                modifier = Modifier.padding(contentPadding),
                verticalAlignment = Alignment.CenterVertically
            ) { content() }
        }
    }
}
