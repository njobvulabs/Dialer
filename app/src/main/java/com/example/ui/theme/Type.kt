package com.example.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Type tokens.
 *
 * All 15 M3 roles are now spelled out. `Type.kt` previously defined only `bodyLarge`
 * and left the other 14 at the library default, which made the scale impossible to
 * reason about and left `titleLarge`/`labelSmall` silently disagreeing with the rest
 * (their values read as "whatever the library happened to default to", not as a
 * decision anyone made).
 *
 * Metrics are **stock M3 on purpose** — Phase 1.2 is normalization, so this file must
 * not move a single pixel of existing UI. Values are now *visible* and can be tuned
 * deliberately in Phase 2. `bodyLarge` keeps the 16/24/0.5 override the old file had,
 * which is itself stock M3, so the one role that was already defined is unchanged.
 *
 * Two candidates for a Phase 2 tightening, deliberately NOT applied here:
 * - `titleLarge`/`titleMedium` are used as list-row headers in several screens
 *   (`CallHistoryScreen`, `CallStatsScreen`, `InCallScreen`, `VaultScreen`,
 *   `ContactToolsScreen`, `ContactsScreen`), where 22sp eats a lot of a row.
 * - Display and headline roles are unused by any screen today; they are spelled out
 *   only so a future hero surface has a defined value instead of an accident.
 *

import com.example.ui.components.AppIcons
 * Weights come from the M3 type scale, not from ad-hoc `copy(fontWeight = Bold)` at
 * call sites — `titleMedium`/`titleSmall`/`labelLarge` are already Medium here.
 */
private val Sans = FontFamily.Default

private fun style(
    size: Int,
    lineHeight: Int,
    letterSpacing: Double,
    weight: FontWeight = FontWeight.Normal,
) = TextStyle(
    fontFamily = Sans,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing.sp,
)

val Typography = Typography(
    displayLarge = style(57, 64, -0.25),
    displayMedium = style(45, 52, 0.0),
    displaySmall = style(36, 44, 0.0),

    headlineLarge = style(32, 40, 0.0),
    headlineMedium = style(28, 36, 0.0),
    headlineSmall = style(24, 32, 0.0),

    titleLarge = style(22, 28, 0.0),
    titleMedium = style(16, 24, 0.15, FontWeight.Medium),
    titleSmall = style(14, 20, 0.1, FontWeight.Medium),

    bodyLarge = style(16, 24, 0.5),
    bodyMedium = style(14, 20, 0.25),
    bodySmall = style(12, 16, 0.4),

    labelLarge = style(14, 20, 0.1, FontWeight.Medium),
    labelMedium = style(12, 16, 0.5, FontWeight.Medium),
    labelSmall = style(11, 16, 0.5, FontWeight.Medium),
)