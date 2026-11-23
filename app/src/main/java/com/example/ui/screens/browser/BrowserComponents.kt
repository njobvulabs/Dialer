package com.example.ui.screens.browser

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.AppIcons
import org.mozilla.geckoview.GeckoSession
import java.util.Locale

// -------------------------------------------------------------- small pieces

@Composable
internal fun MenuRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun ClearRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }
        .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label)
    }
}

/**
 * The Site Info sheet: what the page is, whether the connection is secure, its
 * certificate, how many trackers were blocked, and the stored per-site
 * permission decisions. Opened by tapping the security glyph in the URL bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SiteInfoSheet(
    tab: GeckoTab?,
    onDismiss: () -> Unit
) {
    val info = tab?.securityInfo
    val url = tab?.url.orEmpty()
    val host = info?.host
        ?: runCatching { Uri.parse(url).host }.getOrNull()
        ?: url
    val cert = info?.certificate
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val secure = info?.isSecure == true
    val connection = when {
        info == null -> "No security information"
        info.isException -> "Certificate error accepted"
        secure && info.securityMode ==
            GeckoSession.ProgressDelegate.SecurityInformation.SECURITY_MODE_VERIFIED ->
            "Secure connection (verified)"
        secure -> "Secure connection"
        else -> "Not secure"
    }
    val mixed = when {
        info == null -> null
        info.mixedModeActive ==
            GeckoSession.ProgressDelegate.SecurityInformation.CONTENT_LOADED ||
            info.mixedModePassive ==
            GeckoSession.ProgressDelegate.SecurityInformation.CONTENT_LOADED ->
            "contains insecure content"
        info.mixedModeActive ==
            GeckoSession.ProgressDelegate.SecurityInformation.CONTENT_BLOCKED ||
            info.mixedModePassive ==
            GeckoSession.ProgressDelegate.SecurityInformation.CONTENT_BLOCKED ->
            "insecure content blocked"
        else -> null
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                host.ifBlank { "Site information" },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when {
                        tab?.isPrivate == true -> AppIcons.VisibilityOff
                        secure -> AppIcons.Lock
                        else -> AppIcons.Public
                    },
                    contentDescription = null,
                    tint = if (secure) Color(0xFF16A34A) else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (mixed != null) "$connection — $mixed" else connection,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            InfoRow("Trackers blocked", (tab?.blockedCount ?: 0).toString())
            InfoRow("Host", host.ifBlank { "—" })
            if (cert != null) {
                InfoRow("Issued to", cert.subjectDN?.name ?: "—")
                InfoRow("Issued by", cert.issuerDN?.name ?: "—")
                InfoRow(
                    "Valid until",
                    runCatching {
                        java.text.SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
                            .format(cert.notAfter)
                    }.getOrDefault("—")
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text("Site permissions", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            SitePermissionsEditor(currentUrl = url)
        }
    }
}

@Composable
internal fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            maxLines = 2, overflow = TextOverflow.Ellipsis
        )
    }
}

internal fun showToast(context: Context, msg: String) {
    android.os.Handler(android.os.Looper.getMainLooper()).post {
        runCatching { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
    }
}


internal fun closeAllFlowImpl(manager: GeckoTabManager, resetAttach: () -> Unit) {
    runCatching { manager.releaseAll() }
    manager.closeAll()
    resetAttach()
}

internal fun String?.ifBlankOrNull(): String? = this?.takeIf { it.isNotBlank() }

/**
 * One new-tab tile (B8). Long-press is the *only* way to remove a tile, so a
 * stray tap can never delete one — the same rule the Notes / Gallery screens use.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NtpTileView(
    title: String,
    url: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val color = tileColor(url)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick
        )
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(color.copy(alpha = 0.15f), CircleShape)
                .border(1.dp, color.copy(alpha = 0.4f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = title.trim().take(1).uppercase().ifEmpty { "?" },
                color = color,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun MenuSection(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
    )
}
