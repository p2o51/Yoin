package com.gpo.yoin.ui.theme

import android.graphics.Bitmap
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Extracts a Material 3 dark [ColorScheme] from an album cover [Bitmap] using
 * the AndroidX Palette API.
 *
 * Swatch → MD3 token mapping:
 * - **primary** ← vibrant or dominant swatch
 * - **primaryContainer** ← darkened primary
 * - **secondary** ← light-vibrant / dark-vibrant
 * - **surface / background** ← dark-muted, clamped to very low luminance (< 0.10)
 * - **on-colors** are contrast-checked to guarantee ≥ 4.5 : 1 ratio
 *
 * Returns `null` when [bitmap] is null or extraction yields no usable swatches.
 */
object PaletteExtractor {

    private const val MAX_COLORS = 16
    private const val SURFACE_MAX_LUMINANCE = 0.10f
    private const val BACKGROUND_MAX_LUMINANCE = 0.08f
    private const val MIN_CONTRAST_RATIO = 4.5f

    suspend fun extractColorScheme(bitmap: Bitmap?): ColorScheme? {
        if (bitmap == null) return null
        return withContext(Dispatchers.Default) {
            val palette = Palette.from(bitmap)
                .maximumColorCount(MAX_COLORS)
                .generate()
            buildColorScheme(palette)
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun buildColorScheme(palette: Palette): ColorScheme? {
        val vibrant = palette.vibrantSwatch
        val darkVibrant = palette.darkVibrantSwatch
        val lightVibrant = palette.lightVibrantSwatch
        val muted = palette.mutedSwatch
        val darkMuted = palette.darkMutedSwatch
        val lightMuted = palette.lightMutedSwatch
        val dominant = palette.dominantSwatch

        // Need at least one swatch to build a scheme.
        val primarySwatch = vibrant ?: dominant ?: muted ?: return null

        // ── Primary group ────────────────────────────────────────────────
        val primary = Color(primarySwatch.rgb)
        val primaryContainer = darken(primary, 0.35f)
        val onPrimary = contrastForeground(primary)
        val onPrimaryContainer = contrastForeground(primaryContainer)
        val inversePrimary = darken(primary, 0.30f)

        // ── Secondary group ──────────────────────────────────────────────
        val secondarySwatch = lightVibrant ?: darkVibrant ?: muted ?: primarySwatch
        val secondary = Color(secondarySwatch.rgb)
        val secondaryContainer = darken(secondary, 0.35f)
        val onSecondary = contrastForeground(secondary)
        val onSecondaryContainer = contrastForeground(secondaryContainer)

        // ── Tertiary group ───────────────────────────────────────────────
        val tertiarySwatch = lightMuted ?: darkVibrant ?: muted ?: primarySwatch
        val tertiary = Color(tertiarySwatch.rgb)
        val tertiaryContainer = darken(tertiary, 0.35f)
        val onTertiary = contrastForeground(tertiary)
        val onTertiaryContainer = contrastForeground(tertiaryContainer)

        // ── Surface / Background — kept very dark ────────────────────────
        val surfaceSwatch = darkMuted ?: muted ?: dominant
        val rawSurfaceArgb = surfaceSwatch?.rgb ?: 0xFF1C1B1F.toInt()
        val surface = clampLuminance(rawSurfaceArgb, SURFACE_MAX_LUMINANCE)
        val background = clampLuminance(rawSurfaceArgb, BACKGROUND_MAX_LUMINANCE)
        val onSurface = contrastForeground(surface)
        val onBackground = contrastForeground(background)

        val surfaceVariant = lighten(surface, 0.06f)
        val onSurfaceVariant = contrastForeground(surfaceVariant)
        val surfaceDim = darken(surface, 0.03f)
        val surfaceBright = lighten(surface, 0.12f)

        // Surface container hierarchy (progressively lighter).
        val surfaceContainerLowest = darken(surface, 0.02f)
        val surfaceContainerLow = lighten(surface, 0.02f)
        val surfaceContainer = lighten(surface, 0.04f)
        val surfaceContainerHigh = lighten(surface, 0.06f)
        val surfaceContainerHighest = lighten(surface, 0.08f)

        // ── Inverse ──────────────────────────────────────────────────────
        val inverseSurface = contrastForeground(surface)
        val inverseOnSurface = contrastForeground(inverseSurface)

        // ── Outline ──────────────────────────────────────────────────────
        val outline = lighten(surface, 0.20f)
        val outlineVariant = lighten(surface, 0.12f)

        return darkColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            inversePrimary = inversePrimary,
            secondary = secondary,
            onSecondary = onSecondary,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiary,
            onTertiary = onTertiary,
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onTertiaryContainer,
            background = background,
            onBackground = onBackground,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            surfaceTint = primary,
            inverseSurface = inverseSurface,
            inverseOnSurface = inverseOnSurface,
            error = Color(0xFFF2B8B5),
            onError = Color(0xFF601410),
            errorContainer = Color(0xFF8C1D18),
            onErrorContainer = Color(0xFFF9DEDC),
            outline = outline,
            outlineVariant = outlineVariant,
            scrim = Color.Black,
            surfaceBright = surfaceBright,
            surfaceDim = surfaceDim,
            surfaceContainer = surfaceContainer,
            surfaceContainerHigh = surfaceContainerHigh,
            surfaceContainerHighest = surfaceContainerHighest,
            surfaceContainerLow = surfaceContainerLow,
            surfaceContainerLowest = surfaceContainerLowest,
        )
    }

    // ── Color helpers ────────────────────────────────────────────────────

    /** Clamp HSL lightness of [argb] to at most [maxL]. */
    private fun clampLuminance(argb: Int, maxL: Float): Color {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(argb, hsl)
        if (hsl[2] > maxL) hsl[2] = maxL
        return Color(ColorUtils.HSLToColor(hsl))
    }

    /** Reduce HSL lightness by [amount]. */
    private fun darken(color: Color, amount: Float): Color {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color.toArgb(), hsl)
        hsl[2] = (hsl[2] - amount).coerceIn(0f, 1f)
        return Color(ColorUtils.HSLToColor(hsl))
    }

    /** Increase HSL lightness by [amount]. */
    private fun lighten(color: Color, amount: Float): Color {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color.toArgb(), hsl)
        hsl[2] = (hsl[2] + amount).coerceIn(0f, 1f)
        return Color(ColorUtils.HSLToColor(hsl))
    }

    /**
     * Pick a foreground [Color] that meets WCAG 4.5 : 1 contrast against [background].
     * Prefers soft MD3 baseline tones over pure white / black.
     */
    private fun contrastForeground(background: Color): Color {
        val bgArgb = background.toArgb()
        val light = Color(0xFFE6E1E5) // MD3 baseline on-surface (light)
        val dark = Color(0xFF1C1B1F) // MD3 baseline on-surface (dark)
        val lightRatio = ColorUtils.calculateContrast(light.toArgb(), bgArgb).toFloat()
        val darkRatio = ColorUtils.calculateContrast(dark.toArgb(), bgArgb).toFloat()

        return when {
            lightRatio >= MIN_CONTRAST_RATIO -> light
            darkRatio >= MIN_CONTRAST_RATIO -> dark
            lightRatio > darkRatio -> Color.White
            else -> Color.Black
        }
    }
}
