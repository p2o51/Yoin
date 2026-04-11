package com.gpo.yoin.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// ── MD3 Dark Color Tokens ──────────────────────────────────────────────
// Explicit fallback palette used when dynamic color is unavailable (API < 31).
// Token names follow the MD3 specification so Phase 12 (palette-driven color)
// can swap them cleanly.

private val md3_dark_primary = Color(0xFFD0BCFF)
private val md3_dark_onPrimary = Color(0xFF381E72)
private val md3_dark_primaryContainer = Color(0xFF4F378B)
private val md3_dark_onPrimaryContainer = Color(0xFFEADDFF)

private val md3_dark_secondary = Color(0xFFCCC2DC)
private val md3_dark_onSecondary = Color(0xFF332D41)
private val md3_dark_secondaryContainer = Color(0xFF4A4458)
private val md3_dark_onSecondaryContainer = Color(0xFFE8DEF8)

private val md3_dark_tertiary = Color(0xFFEFB8C8)
private val md3_dark_onTertiary = Color(0xFF492532)
private val md3_dark_tertiaryContainer = Color(0xFF633B48)
private val md3_dark_onTertiaryContainer = Color(0xFFFFD8E4)

private val md3_dark_error = Color(0xFFF2B8B5)
private val md3_dark_onError = Color(0xFF601410)
private val md3_dark_errorContainer = Color(0xFF8C1D18)
private val md3_dark_onErrorContainer = Color(0xFFF9DEDC)

private val md3_dark_background = Color(0xFF1C1B1F)
private val md3_dark_onBackground = Color(0xFFE6E1E5)
private val md3_dark_surface = Color(0xFF1C1B1F)
private val md3_dark_onSurface = Color(0xFFE6E1E5)

private val md3_dark_surfaceVariant = Color(0xFF49454F)
private val md3_dark_onSurfaceVariant = Color(0xFFCAC4D0)
private val md3_dark_outline = Color(0xFF938F99)
private val md3_dark_outlineVariant = Color(0xFF49454F)

private val md3_dark_inverseSurface = Color(0xFFE6E1E5)
private val md3_dark_inverseOnSurface = Color(0xFF313033)
private val md3_dark_inversePrimary = Color(0xFF6750A4)

private val md3_dark_surfaceTint = Color(0xFFD0BCFF)
private val md3_dark_scrim = Color(0xFF000000)

private val md3_light_primary = Color(0xFF6750A4)
private val md3_light_onPrimary = Color(0xFFFFFFFF)
private val md3_light_primaryContainer = Color(0xFFEADDFF)
private val md3_light_onPrimaryContainer = Color(0xFF21005D)

private val md3_light_secondary = Color(0xFF625B71)
private val md3_light_onSecondary = Color(0xFFFFFFFF)
private val md3_light_secondaryContainer = Color(0xFFE8DEF8)
private val md3_light_onSecondaryContainer = Color(0xFF1D192B)

private val md3_light_tertiary = Color(0xFF7D5260)
private val md3_light_onTertiary = Color(0xFFFFFFFF)
private val md3_light_tertiaryContainer = Color(0xFFFFD8E4)
private val md3_light_onTertiaryContainer = Color(0xFF31111D)

private val md3_light_error = Color(0xFFB3261E)
private val md3_light_onError = Color(0xFFFFFFFF)
private val md3_light_errorContainer = Color(0xFFF9DEDC)
private val md3_light_onErrorContainer = Color(0xFF410E0B)

private val md3_light_background = Color(0xFFFFFBFE)
private val md3_light_onBackground = Color(0xFF1C1B1F)
private val md3_light_surface = Color(0xFFFFFBFE)
private val md3_light_onSurface = Color(0xFF1C1B1F)

private val md3_light_surfaceVariant = Color(0xFFE7E0EC)
private val md3_light_onSurfaceVariant = Color(0xFF49454F)
private val md3_light_outline = Color(0xFF79747E)
private val md3_light_outlineVariant = Color(0xFFCAC4D0)

private val md3_light_inverseSurface = Color(0xFF313033)
private val md3_light_inverseOnSurface = Color(0xFFF4EFF4)
private val md3_light_inversePrimary = Color(0xFFD0BCFF)

private val md3_light_surfaceTint = Color(0xFF6750A4)
private val md3_light_scrim = Color(0xFF000000)

/**
 * Explicit MD3 dark color scheme for devices without dynamic color support.
 *
 * Uses the baseline Material 3 dark palette values so every token is visible
 * and editable in one place. Phase 12 will override these via Palette API.
 */
val YoinDarkColorScheme = darkColorScheme(
    primary = md3_dark_primary,
    onPrimary = md3_dark_onPrimary,
    primaryContainer = md3_dark_primaryContainer,
    onPrimaryContainer = md3_dark_onPrimaryContainer,
    secondary = md3_dark_secondary,
    onSecondary = md3_dark_onSecondary,
    secondaryContainer = md3_dark_secondaryContainer,
    onSecondaryContainer = md3_dark_onSecondaryContainer,
    tertiary = md3_dark_tertiary,
    onTertiary = md3_dark_onTertiary,
    tertiaryContainer = md3_dark_tertiaryContainer,
    onTertiaryContainer = md3_dark_onTertiaryContainer,
    error = md3_dark_error,
    onError = md3_dark_onError,
    errorContainer = md3_dark_errorContainer,
    onErrorContainer = md3_dark_onErrorContainer,
    background = md3_dark_background,
    onBackground = md3_dark_onBackground,
    surface = md3_dark_surface,
    onSurface = md3_dark_onSurface,
    surfaceVariant = md3_dark_surfaceVariant,
    onSurfaceVariant = md3_dark_onSurfaceVariant,
    outline = md3_dark_outline,
    outlineVariant = md3_dark_outlineVariant,
    inverseSurface = md3_dark_inverseSurface,
    inverseOnSurface = md3_dark_inverseOnSurface,
    inversePrimary = md3_dark_inversePrimary,
    surfaceTint = md3_dark_surfaceTint,
    scrim = md3_dark_scrim,
)

/**
 * Explicit MD3 light color scheme for devices without dynamic color support.
 */
val YoinLightColorScheme = lightColorScheme(
    primary = md3_light_primary,
    onPrimary = md3_light_onPrimary,
    primaryContainer = md3_light_primaryContainer,
    onPrimaryContainer = md3_light_onPrimaryContainer,
    secondary = md3_light_secondary,
    onSecondary = md3_light_onSecondary,
    secondaryContainer = md3_light_secondaryContainer,
    onSecondaryContainer = md3_light_onSecondaryContainer,
    tertiary = md3_light_tertiary,
    onTertiary = md3_light_onTertiary,
    tertiaryContainer = md3_light_tertiaryContainer,
    onTertiaryContainer = md3_light_onTertiaryContainer,
    error = md3_light_error,
    onError = md3_light_onError,
    errorContainer = md3_light_errorContainer,
    onErrorContainer = md3_light_onErrorContainer,
    background = md3_light_background,
    onBackground = md3_light_onBackground,
    surface = md3_light_surface,
    onSurface = md3_light_onSurface,
    surfaceVariant = md3_light_surfaceVariant,
    onSurfaceVariant = md3_light_onSurfaceVariant,
    outline = md3_light_outline,
    outlineVariant = md3_light_outlineVariant,
    inverseSurface = md3_light_inverseSurface,
    inverseOnSurface = md3_light_inverseOnSurface,
    inversePrimary = md3_light_inversePrimary,
    surfaceTint = md3_light_surfaceTint,
    scrim = md3_light_scrim,
)
