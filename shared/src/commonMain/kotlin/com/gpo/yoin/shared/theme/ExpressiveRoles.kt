package com.gpo.yoin.shared.theme

import dynamiccolor.ColorSpec
import dynamiccolor.DynamicScheme
import hct.Hct
import scheme.SchemeExpressive

/**
 * The colour roles of a seed, the way Android's ExpressiveColorSchemeFactory
 * builds them (MCU SchemeExpressive, SPEC_2025, phone): ARGB per Compose role
 * name, in ColorScheme order. iOS fills its CoverScheme from this.
 */
object ExpressiveRoles {
    fun argbRoles(seedArgb: Int, isDark: Boolean, contrastLevel: Double = 0.0): Map<String, Int> {
        val s = SchemeExpressive(
            sourceColorHct = Hct.fromInt(seedArgb),
            isDark = isDark,
            contrastLevel = contrastLevel,
            specVersion = ColorSpec.SpecVersion.SPEC_2025,
            platform = DynamicScheme.Platform.PHONE,
        )
        return linkedMapOf(
            "primary" to s.primary,
            "onPrimary" to s.onPrimary,
            "primaryContainer" to s.primaryContainer,
            "onPrimaryContainer" to s.onPrimaryContainer,
            "inversePrimary" to s.inversePrimary,
            "secondary" to s.secondary,
            "onSecondary" to s.onSecondary,
            "secondaryContainer" to s.secondaryContainer,
            "onSecondaryContainer" to s.onSecondaryContainer,
            "tertiary" to s.tertiary,
            "onTertiary" to s.onTertiary,
            "tertiaryContainer" to s.tertiaryContainer,
            "onTertiaryContainer" to s.onTertiaryContainer,
            "background" to s.background,
            "onBackground" to s.onBackground,
            "surface" to s.surface,
            "onSurface" to s.onSurface,
            "surfaceVariant" to s.surfaceVariant,
            "onSurfaceVariant" to s.onSurfaceVariant,
            "surfaceTint" to s.surfaceTint,
            "inverseSurface" to s.inverseSurface,
            "inverseOnSurface" to s.inverseOnSurface,
            "error" to s.error,
            "onError" to s.onError,
            "errorContainer" to s.errorContainer,
            "onErrorContainer" to s.onErrorContainer,
            "outline" to s.outline,
            "outlineVariant" to s.outlineVariant,
            "scrim" to s.scrim,
            "surfaceBright" to s.surfaceBright,
            "surfaceDim" to s.surfaceDim,
            "surfaceContainer" to s.surfaceContainer,
            "surfaceContainerHigh" to s.surfaceContainerHigh,
            "surfaceContainerHighest" to s.surfaceContainerHighest,
            "surfaceContainerLow" to s.surfaceContainerLow,
            "surfaceContainerLowest" to s.surfaceContainerLowest,
        )
    }
}
