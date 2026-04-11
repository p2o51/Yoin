package com.gpo.yoin.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import dynamiccolor.ColorSpec
import dynamiccolor.DynamicScheme
import hct.Hct
import scheme.SchemeExpressive

/**
 * Builds a Compose [ColorScheme] from a single seed using the official MCU
 * `SchemeExpressive` algorithm with `SPEC_2025`.
 */
object ExpressiveColorSchemeFactory {
    fun fromSeed(
        seedArgb: Int,
        isDark: Boolean = true,
        contrastLevel: Double = 0.0,
    ): ColorScheme {
        val scheme = SchemeExpressive(
            sourceColorHct = Hct.fromInt(seedArgb),
            isDark = isDark,
            contrastLevel = contrastLevel,
            specVersion = ColorSpec.SpecVersion.SPEC_2025,
            platform = DynamicScheme.Platform.PHONE,
        )

        return if (isDark) {
            darkColorScheme(
                primary = Color(scheme.primary),
                onPrimary = Color(scheme.onPrimary),
                primaryContainer = Color(scheme.primaryContainer),
                onPrimaryContainer = Color(scheme.onPrimaryContainer),
                inversePrimary = Color(scheme.inversePrimary),
                secondary = Color(scheme.secondary),
                onSecondary = Color(scheme.onSecondary),
                secondaryContainer = Color(scheme.secondaryContainer),
                onSecondaryContainer = Color(scheme.onSecondaryContainer),
                tertiary = Color(scheme.tertiary),
                onTertiary = Color(scheme.onTertiary),
                tertiaryContainer = Color(scheme.tertiaryContainer),
                onTertiaryContainer = Color(scheme.onTertiaryContainer),
                background = Color(scheme.background),
                onBackground = Color(scheme.onBackground),
                surface = Color(scheme.surface),
                onSurface = Color(scheme.onSurface),
                surfaceVariant = Color(scheme.surfaceVariant),
                onSurfaceVariant = Color(scheme.onSurfaceVariant),
                surfaceTint = Color(scheme.surfaceTint),
                inverseSurface = Color(scheme.inverseSurface),
                inverseOnSurface = Color(scheme.inverseOnSurface),
                error = Color(scheme.error),
                onError = Color(scheme.onError),
                errorContainer = Color(scheme.errorContainer),
                onErrorContainer = Color(scheme.onErrorContainer),
                outline = Color(scheme.outline),
                outlineVariant = Color(scheme.outlineVariant),
                scrim = Color(scheme.scrim),
                surfaceBright = Color(scheme.surfaceBright),
                surfaceDim = Color(scheme.surfaceDim),
                surfaceContainer = Color(scheme.surfaceContainer),
                surfaceContainerHigh = Color(scheme.surfaceContainerHigh),
                surfaceContainerHighest = Color(scheme.surfaceContainerHighest),
                surfaceContainerLow = Color(scheme.surfaceContainerLow),
                surfaceContainerLowest = Color(scheme.surfaceContainerLowest),
            )
        } else {
            lightColorScheme(
                primary = Color(scheme.primary),
                onPrimary = Color(scheme.onPrimary),
                primaryContainer = Color(scheme.primaryContainer),
                onPrimaryContainer = Color(scheme.onPrimaryContainer),
                inversePrimary = Color(scheme.inversePrimary),
                secondary = Color(scheme.secondary),
                onSecondary = Color(scheme.onSecondary),
                secondaryContainer = Color(scheme.secondaryContainer),
                onSecondaryContainer = Color(scheme.onSecondaryContainer),
                tertiary = Color(scheme.tertiary),
                onTertiary = Color(scheme.onTertiary),
                tertiaryContainer = Color(scheme.tertiaryContainer),
                onTertiaryContainer = Color(scheme.onTertiaryContainer),
                background = Color(scheme.background),
                onBackground = Color(scheme.onBackground),
                surface = Color(scheme.surface),
                onSurface = Color(scheme.onSurface),
                surfaceVariant = Color(scheme.surfaceVariant),
                onSurfaceVariant = Color(scheme.onSurfaceVariant),
                surfaceTint = Color(scheme.surfaceTint),
                inverseSurface = Color(scheme.inverseSurface),
                inverseOnSurface = Color(scheme.inverseOnSurface),
                error = Color(scheme.error),
                onError = Color(scheme.onError),
                errorContainer = Color(scheme.errorContainer),
                onErrorContainer = Color(scheme.onErrorContainer),
                outline = Color(scheme.outline),
                outlineVariant = Color(scheme.outlineVariant),
                scrim = Color(scheme.scrim),
                surfaceBright = Color(scheme.surfaceBright),
                surfaceDim = Color(scheme.surfaceDim),
                surfaceContainer = Color(scheme.surfaceContainer),
                surfaceContainerHigh = Color(scheme.surfaceContainerHigh),
                surfaceContainerHighest = Color(scheme.surfaceContainerHighest),
                surfaceContainerLow = Color(scheme.surfaceContainerLow),
                surfaceContainerLowest = Color(scheme.surfaceContainerLowest),
            )
        }
    }
}
