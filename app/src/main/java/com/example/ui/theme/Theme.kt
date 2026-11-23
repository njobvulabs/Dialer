package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

@Composable
fun MyApplicationTheme(
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    accent: AppAccent = AppAccent.INDIGO,
    dynamicColor: Boolean = true,
    amoledBlack: Boolean = false,
    content: @Composable () -> Unit
) {
    val isSystemDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        AppThemeMode.SYSTEM -> isSystemDark
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }

    val baseColorScheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

        val context = LocalContext.current
        if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (isDark) {
        darkColorScheme(
            primary = accent.primary,
            onPrimary = DarkOnPrimary,
            primaryContainer = DarkPrimaryContainer,
            onPrimaryContainer = DarkOnPrimaryContainer,
            inversePrimary = DarkInversePrimary,
            secondary = accent.secondary,
            onSecondary = DarkOnPrimaryContainer,
            secondaryContainer = DarkSecondaryContainer,
            onSecondaryContainer = DarkOnSecondaryContainer,
            tertiary = DarkTertiary,
            onTertiary = DarkOnTertiary,
            tertiaryContainer = DarkTertiaryContainer,
            onTertiaryContainer = DarkOnTertiaryContainer,
            background = Color(0xFF10131A),
            onBackground = Color(0xFFE2E2E9),
            surface = Color(0xFF1B1E26),
            onSurface = Color(0xFFE2E2E9),
            surfaceVariant = Color(0xFF2E3340),
            onSurfaceVariant = Color(0xFFC4C6D0),
            surfaceTint = DarkSurfaceTint,
            inverseSurface = DarkInverseSurface,
            inverseOnSurface = DarkInverseOnSurface,
            error = DarkError,
            onError = DarkOnError,
            errorContainer = DarkErrorContainer,
            onErrorContainer = DarkOnErrorContainer,
            outline = DarkOutline,
            outlineVariant = DarkOutlineVariant,
            scrim = DarkScrim,
            surfaceBright = DarkSurfaceBright,
            surfaceDim = DarkSurfaceDim,
            surfaceContainerLowest = DarkSurfaceContainerLowest,
            surfaceContainerLow = DarkSurfaceContainerLow,
            surfaceContainer = DarkSurfaceContainer,
            surfaceContainerHigh = DarkSurfaceContainerHigh,
            surfaceContainerHighest = DarkSurfaceContainerHighest
        )
    } else {
        lightColorScheme(
            primary = accent.primary,
            onPrimary = ProfessionalOnPrimary,
            primaryContainer = BluePrimaryContainer,
            onPrimaryContainer = BlueOnPrimaryContainer,
            inversePrimary = ProfessionalInversePrimary,
            secondary = accent.secondary,
            onSecondary = ProfessionalOnSecondaryContainer,
            secondaryContainer = ProfessionalSecondaryContainer,
            onSecondaryContainer = ProfessionalOnSecondaryContainer,
            tertiary = ProfessionalTertiary,
            onTertiary = ProfessionalOnTertiary,
            tertiaryContainer = ProfessionalTertiaryContainer,
            onTertiaryContainer = ProfessionalOnTertiaryContainer,
            background = ProfessionalBackground,
            onBackground = ProfessionalOnSurface,
            surface = ProfessionalSurface,
            onSurface = ProfessionalOnSurface,
            surfaceVariant = ProfessionalSurfaceVariant,
            onSurfaceVariant = ProfessionalOnSurfaceVariant,
            surfaceTint = ProfessionalSurfaceTint,
            inverseSurface = ProfessionalInverseSurface,
            inverseOnSurface = ProfessionalInverseOnSurface,
            error = ProfessionalError,
            onError = ProfessionalOnError,
            errorContainer = ProfessionalErrorContainer,
            onErrorContainer = ProfessionalOnErrorContainer,
            outline = ProfessionalOutline,
            outlineVariant = ProfessionalOutlineVariant,
            scrim = ProfessionalScrim,
            surfaceBright = ProfessionalSurfaceBright,
            surfaceDim = ProfessionalSurfaceDim,
            surfaceContainerLowest = ProfessionalSurfaceContainerLowest,
            surfaceContainerLow = ProfessionalSurfaceContainerLow,
            surfaceContainer = ProfessionalSurfaceContainer,
            surfaceContainerHigh = ProfessionalSurfaceContainerHigh,
            surfaceContainerHighest = ProfessionalSurfaceContainerHighest
        )
    }

    val colorScheme = if (isDark && amoledBlack && !dynamicColor) {
        baseColorScheme.copy(
            background = Color.Black,
            surface = Color(0xFF0A0A0A),
            surfaceVariant = Color(0xFF141414),
            surfaceDim = Color.Black,
            surfaceBright = Color(0xFF1A1A1A),
            surfaceContainer = Color(0xFF0F0F0F),
            surfaceContainerLowest = Color.Black,
            surfaceContainerLow = Color(0xFF0D0D0D),
            surfaceContainerHigh = Color(0xFF161616),
            surfaceContainerHighest = Color(0xFF1C1C1C)
        )
    } else {
        baseColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = AppShapes,
        typography = Typography,
        content = content
    )
}
