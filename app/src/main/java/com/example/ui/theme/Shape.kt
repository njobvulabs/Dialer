package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Shape tokens.
 *
 * `extraLarge` is deliberately left at M3's stock **28.dp**, even though this is a
 * dense list suite. `extraLarge` is the shape M3 **dialogs, bottom sheets and menus**
 * resolve (`DialogDefaults.shape`), not the one cards use (cards use `medium`), so
 * tightening it would restyle every one of the app's ~29 `AlertDialog`s — a visual
 * change that belongs to Phase 2, not to wiring tokens. See `AppCornerRadius` below for
 * the radii the app actually uses on dense surfaces.
 *
 * Wire this in via `MaterialTheme(shapes = AppShapes)` (done, `Theme.kt`). Until a
 * screen is migrated it keeps its literal `RoundedCornerShape(n.dp)`; migrating a
 * screen means replacing those literals with the nearest token, not inventing a new
 * value.
 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)


/**
 * Named corner radii for the values that do not land on an M3 role.
 *
 * Kept as one place to look instead of a literal `.dp` at each call site. These are
 * the shapes the app used directly and has no M3 slot for:
 * - [AppCornerRadius.xs]   3.dp  — tiny chips / count badges
 * - [AppCornerRadius.sm]  10.dp  — mid controls between `small` and `medium`
 * - [AppCornerRadius.md]  14.dp  — list rows (the app's most common row radius)
 * - [AppCornerRadius.pill] 21.dp — segmented control pills / selection chips
 * - [AppCornerRadius.xl]  24.dp  — large feature cards
 */
object AppCornerRadius {
    val xs = RoundedCornerShape(3.dp)
    val sm = RoundedCornerShape(10.dp)
    val md = RoundedCornerShape(14.dp)
    val pill = RoundedCornerShape(21.dp)
    val xl = RoundedCornerShape(24.dp)
}