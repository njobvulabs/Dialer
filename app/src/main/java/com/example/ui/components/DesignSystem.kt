package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AppCornerRadius

/**
 * Design-system components.
 *
 * New screens should use these shared components, and legacy call sites migrate as
 * they are touched. [AppSearchField] is the single search-bar implementation.
 *
 * Rules when adding to this file:
 * - Use `MaterialTheme.colorScheme.*` and `AppCornerRadius` / `MaterialTheme.shapes.*`
 *   tokens. Never a literal colour or a raw `.dp` radius for a component shape.
 * - Every interactive element must be reachable with the same padding as its peers;
 *   these are phone-sized targets, not desktop widgets.
 * - No I/O, no ViewModel, no side effects. Presentational only.
 */

// ---------------------------------------------------------------------------
// Buttons
// ---------------------------------------------------------------------------

/**
 * Button hierarchy.
 *
 * The app had 21 hand-rolled `containerColor = MaterialTheme.colorScheme...` overrides
 * that produced four different "primary button" appearances. Use these instead:
 * - [AppPrimaryButton]   one per screen, the main action
 * - [AppSecondaryButton] supporting/alternative action
 * - [AppDangerButton]    destructive only (delete, wipe, revoke)
 * - [AppTextButton]      low-emphasis, in-flow
 *
 * `AppPrimaryButton` deliberately does NOT mirror `ButtonDefaults.buttonColors`
 * (destructive red on the error role), because the existing screens that used a red
 * filled button were always delete actions.
 */
@Composable
fun AppPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text)
    }
}

@Composable
fun AppSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text)
    }
}

@Composable
fun AppDangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text)
    }
}

@Composable
fun AppTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true
) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text)
    }
}

// ---------------------------------------------------------------------------
// Containers
// ---------------------------------------------------------------------------

/**
 * A titled content card, the unit list screens stack to group related rows.
 *
 * `tonalElevation` is 0 and the colour is `surfaceContainer`, matching the existing
 * `FloatingTopBar` / list-row look rather than M3's elevated card, which reads too
 * heavy when several are stacked on a low-contrast background.
 */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    trailing: @Composable (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(14.dp),
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = AppCornerRadius.md,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(contentPadding)) {
            if (title != null || trailing != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (title != null) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    trailing?.invoke()
                }
                Spacer(Modifier.height(10.dp))
            }
            content()
        }
    }
}

/**
 * The app's standard list row: leading icon, title + subtitle, trailing slot.
 *
 * Default radius is `AppCornerRadius.md` (14.dp), which is the radius the app's existing
 * rows already use most often — see the migration note in AGENTS.md.
 *
 * There is deliberately **no** `onLongClick` parameter. Selection mode and
 * long-press-to-delete are widespread in this app (Gallery, Notes, Downloads, Wi-Fi
 * Transfer), but they need `Modifier.combinedClickable` plus a selected/checked
 * overlay, which a `Surface(onClick=...)` cannot host without the surface's own ripple
 * and indication disagreeing with the gesture. Screens needing those keep their
 * existing rows until Phase 3 reworks them as a deliberate change.
 */
@Composable
fun AppListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val colors = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = AppCornerRadius.md,
        color = colors,
        onClick = onClick,
        enabled = enabled
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leadingIcon != null) {
                Surface(
                    shape = RoundedCornerShape(percent = 50),
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        leadingIcon,
                        contentDescription = null,
                        tint = if (selected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(10.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (trailingIcon != null) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    trailingIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Search
// ---------------------------------------------------------------------------

/**
 * Icon-toggled search field for screens that keep a caller-placed header toggle.
 * The rendered field is the shared [AppSearchField].
 *
 * The toggle is **caller-placed**, not rendered here. Every screen that uses this
 * pattern puts the search icon inside its own header row (beside the section title or
 * on the chips line), so [searchToggle] receives what the icon must do and the caller
 * decides where it sits:
 *
 * ```
 * SearchToggleField(query, onQueryChange, visible = showSearch,
 *                   searchToggle = { SearchIconButton(it) { showSearch = !showSearch } })
 * ```
 *
 * [isActive] is true while the field is open OR a query is set, which is the tint the
 * existing screens use to show "search is on".
 */
@Composable
fun SearchToggleField(
    query: String,
    onQueryChange: (String) -> Unit,
    searchToggle: @Composable (isActive: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    visible: Boolean = false,
    placeholder: String = "Search"
) {
    Column(modifier.fillMaxWidth()) {
        searchToggle(visible || query.isNotEmpty())
        AnimatedVisibility(visible = visible) {
            AppSearchField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = placeholder,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            )
        }
    }
}

/**
 * The app's single compact search bar: 40 dp tall, pill-shaped, 14 sp text.
 *
 * [onClose] keeps a close action permanently available at the trailing edge. When it
 * is null, the trailing button only appears while [value] is non-empty and clears it.
 * Adjacent find/navigation controls stay outside the field; pass a `weight` modifier
 * when the field shares a row with them.
 */
@Composable
fun AppSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search",
    leadingIcon: ImageVector = AppIcons.Search,
    onClose: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = AppCornerRadius.pill
    val hasClose = onClose != null
    val borderColor = if (focused) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    val focusModifier = if (focusRequester != null) {
        Modifier.focusRequester(focusRequester)
    } else {
        Modifier
    }

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .height(40.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, borderColor, shape)
            .then(focusModifier)
            .padding(horizontal = 12.dp),
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            color = MaterialTheme.colorScheme.onSurface
        ),
        singleLine = true,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        interactionSource = interactionSource,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    innerTextField()
                }
                if (hasClose || value.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(AppCornerRadius.pill)
                            .clickable(role = Role.Button) {
                                if (hasClose) {
                                    onValueChange("")
                                    onClose?.invoke()
                                } else {
                                    onValueChange("")
                                }
                                keyboard?.hide()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = AppIcons.Close,
                            contentDescription = if (hasClose) "Close search" else "Clear search",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    )
}

/**
 * The standard search toggle icon: primary-tinted while active.
 *
 * Pass `isActive` from [SearchToggleField]'s `searchToggle` callback.
 */
@Composable
fun SearchIconButton(
    isActive: Boolean,
    onClick: () -> Unit,
    contentDescription: String = "Search"
) {
    IconButton(onClick = onClick) {
        Icon(
            AppIcons.Search,
            contentDescription = contentDescription,
            tint = if (isActive) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

// ---------------------------------------------------------------------------
// Tabs / chips
// ---------------------------------------------------------------------------

/**
 * Segmented pill tabs for the "Notes | Tasks" / "Phone Info | APK Backup" pattern.
 *
 * Distinct from M3 `FilterChip`: these are exclusive mode switches (one of N), so the
 * whole row is horizontally scrollable and the selected pill fills with `primary`.
 *
 * [badges] attaches a count to a tab (unread SMS, missed calls). A badge is rendered
 * in the `error` role whether or not its tab is selected — it means "unread", which
 * should read the same everywhere, and the selected state is already carried by the
 * pill fill. A tab mapped to 0 (or absent) renders no badge.
 *
 * [iconFor] is optional; supply it when the tabs replace something that was
 * icon-first (e.g. the Phone tab's Recents/Contacts/SMS), so the glyphs survive the
 * move to labelled pills.
 *
 * [labelOnlyWhenSelected] keeps the row narrow: only the active pill shows its label,
 * the others collapse to their icon. It is ignored when [iconFor] is null — hiding
 * labels from text-only pills would leave them empty. Inactive pills also get tighter
 * horizontal padding, which is what actually reclaims the width.
 */
@Composable
fun PillTabRow(
    tabs: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    badges: Map<String, Int> = emptyMap(),
    iconFor: ((String) -> ImageVector?)? = null,
    labelOnlyWhenSelected: Boolean = false
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tabs.forEach { tab ->
            val isSelected = tab == selected
            val badge = badges[tab] ?: 0
            val icon = iconFor?.invoke(tab)
            // Guarded on icon != null so a text-only row can never collapse to nothing.
            val hideLabel = labelOnlyWhenSelected && !isSelected && icon != null
            Surface(
                onClick = { onSelect(tab) },
                shape = AppCornerRadius.pill,
                color = if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                Row(
                    modifier = Modifier.padding(
                        horizontal = if (hideLabel) 12.dp else 16.dp,
                        vertical = 8.dp
                    ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (icon != null) {
                        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                        if (!hideLabel) Spacer(Modifier.width(6.dp))
                    }
                    if (!hideLabel) {
                        Text(text = tab, style = MaterialTheme.typography.labelLarge)
                    }
                    if (badge > 0) {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            shape = AppCornerRadius.pill,
                            color = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ) {
                            Text(
                                text = if (badge > 99) "99+" else "$badge",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A small count/状态 pill. Used for unread SMS counts, missed calls, file counts.
 *
 * [tone] picks the semantic role; the default is a neutral variant surface so a plain
 * count never competes with [MaterialTheme.colorScheme.error] for attention.
 */
@Composable
fun StatusChip(
    text: String,
    modifier: Modifier = Modifier,
    tone: ChipTone = ChipTone.NEUTRAL,
    icon: ImageVector? = null
) {
    val container: Color
    val content: Color
    when (tone) {
        ChipTone.NEUTRAL -> {
            container = MaterialTheme.colorScheme.surfaceVariant
            content = MaterialTheme.colorScheme.onSurfaceVariant
        }
        ChipTone.PRIMARY -> {
            container = MaterialTheme.colorScheme.primary
            content = MaterialTheme.colorScheme.onPrimary
        }
        ChipTone.ERROR -> {
            container = MaterialTheme.colorScheme.error
            content = MaterialTheme.colorScheme.onError
        }
        ChipTone.SUCCESS -> {
            container = MaterialTheme.colorScheme.tertiaryContainer
            content = MaterialTheme.colorScheme.onTertiaryContainer
        }
    }
    Surface(
        modifier = modifier,
        shape = AppCornerRadius.pill,
        color = container,
        contentColor = content
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(text, style = MaterialTheme.typography.labelSmall)
        }
    }
}

enum class ChipTone { NEUTRAL, PRIMARY, ERROR, SUCCESS }

// ---------------------------------------------------------------------------
// Dialogs
// ---------------------------------------------------------------------------

/**
 * Confirmation dialog with a neutral confirm button.
 *
 * [AppConfirmDialog] (in AppComponents.kt) remains the destructive variant and is the
 * one used for deletes. Use this when the action is not destructive.
 */
@Composable
fun AppDialog(
    title: String,
    message: String,
    confirmLabel: String = "OK",
    dismissLabel: String = "Cancel",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } }
    )
}

/** Confirms a non-destructive yes/no choice, e.g. "Exit & keep running". */
@Composable
fun AppChoiceDialog(
    title: String,
    message: String?,
    options: List<String>,
    onChoose: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (message != null) Text(message)
        },
        confirmButton = {
            Column {
                options.forEachIndexed { index, label ->
                    TextButton(
                        onClick = { onChoose(index) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(label, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    )
}