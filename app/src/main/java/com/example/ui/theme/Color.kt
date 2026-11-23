package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// Professional Polish Theme Colors
val BluePrimary = Color(0xFF0061A4)
val BlueSecondary = Color(0xFF535F70)
val BluePrimaryContainer = Color(0xFFD3E3FD)
val BlueOnPrimaryContainer = Color(0xFF001D35)
val ProfessionalBackground = Color(0xFFF8F9FF)
val ProfessionalSurface = Color(0xFFFFFFFF)
val ProfessionalSurfaceVariant = Color(0xFFEEF1F9)
val ProfessionalOutline = Color(0xFFDDE2EE)
val ProfessionalOnSurface = Color(0xFF1C1B1F)
val ProfessionalOnSurfaceVariant = Color(0xFF44474E)
val ProfessionalError = Color(0xFFBA1A1A)

// --- Light scheme, roles Theme.kt did not previously name ---
val ProfessionalOnPrimary = Color(0xFFFFFFFF)
val ProfessionalSecondaryContainer = Color(0xFFD8E2F9)
val ProfessionalOnSecondaryContainer = Color(0xFF101C2B)
val ProfessionalTertiary = Color(0xFF00696D)
val ProfessionalOnTertiary = Color(0xFFFFFFFF)
val ProfessionalTertiaryContainer = Color(0xFF9CF1F4)
val ProfessionalOnTertiaryContainer = Color(0xFF002022)
val ProfessionalErrorContainer = Color(0xFFFFDAD6)
val ProfessionalOnErrorContainer = Color(0xFF410002)
val ProfessionalOnError = Color(0xFFFFFFFF)
val ProfessionalOutlineVariant = Color(0xFFC3C6D2)

val ProfessionalInverseSurface = Color(0xFF2F3033)
val ProfessionalInverseOnSurface = Color(0xFFF1F0F7)
val ProfessionalInversePrimary = Color(0xFF9FCAFF)
val ProfessionalSurfaceTint = Color(0xFF0061A4)
val ProfessionalScrim = Color(0xFF000000)
val ProfessionalSurfaceDim = Color(0xFFD8D9E0)
val ProfessionalSurfaceBright = Color(0xFFF8F9FF)
val ProfessionalSurfaceContainerLowest = Color(0xFFFFFFFF)
val ProfessionalSurfaceContainerLow = Color(0xFFF2F3FA)
val ProfessionalSurfaceContainer = Color(0xFFECEEF4)
val ProfessionalSurfaceContainerHigh = Color(0xFFE6E8EE)
val ProfessionalSurfaceContainerHighest = Color(0xFFE0E2E8)

// --- Dark scheme, roles Theme.kt did not previously name ---
val DarkOnPrimary = Color(0xFF00325B)
val DarkPrimaryContainer = Color(0xFF004878)
val DarkOnPrimaryContainer = Color(0xFFD3E3FD)
val DarkSecondaryContainer = Color(0xFF3A4757)
val DarkOnSecondaryContainer = Color(0xFFD8E2F9)
val DarkTertiary = Color(0xFF80D4D8)
val DarkOnTertiary = Color(0xFF00373A)
val DarkTertiaryContainer = Color(0xFF004F52)
val DarkOnTertiaryContainer = Color(0xFF9CF1F4)
val DarkError = Color(0xFFFFB4AB)
val DarkOnError = Color(0xFF690005)
val DarkErrorContainer = Color(0xFF93000A)
val DarkOnErrorContainer = Color(0xFFFFDAD6)
val DarkOutline = Color(0xFF8E9099)
val DarkOutlineVariant = Color(0xFF44474E)
val DarkInverseSurface = Color(0xFFE2E2E9)
val DarkInverseOnSurface = Color(0xFF2F3033)
val DarkInversePrimary = Color(0xFF0061A4)
val DarkSurfaceTint = Color(0xFF9FCAFF)
val DarkScrim = Color(0xFF000000)
val DarkSurfaceDim = Color(0xFF10131A)
val DarkSurfaceBright = Color(0xFF36393F)
val DarkSurfaceContainerLowest = Color(0xFF0B0E14)
val DarkSurfaceContainerLow = Color(0xFF191C23)
val DarkSurfaceContainer = Color(0xFF1D2027)
val DarkSurfaceContainerHigh = Color(0xFF272A31)
val DarkSurfaceContainerHighest = Color(0xFF32353C)

// Accent Palettes
enum class AppAccent(val displayName: String, val primary: Color, val secondary: Color) {
    BLUE("Blue", Color(0xFF0061A4), Color(0xFF535F70)),
    INDIGO("Indigo", Color(0xFF4F46E5), Color(0xFF818CF8)),
    TEAL("Teal", Color(0xFF0D9488), Color(0xFF2DD4BF)),
    PURPLE("Purple", Color(0xFF7C3AED), Color(0xFFA78BFA)),
    PINK("Pink", Color(0xFFDB2777), Color(0xFFF472B6)),
    ORANGE("Orange", Color(0xFFEA580C), Color(0xFFFB923C)),
    GREEN("Green", Color(0xFF16A34A), Color(0xFF4ADE80)),
    RED("Red", Color(0xFFDC2626), Color(0xFFF87171)),
    CYAN("Cyan", Color(0xFF0891B2), Color(0xFF22D3EE)),
    BROWN("Brown", Color(0xFF92400E), Color(0xFFB08968)),
    MONO("Graphite", Color(0xFF4B5563), Color(0xFF6B7280))
}

enum class AppThemeMode(val displayName: String) {
    SYSTEM("System Default"),
    LIGHT("Light"),
    DARK("Dark")
}

