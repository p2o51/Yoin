/*
 * Copyright 2022 Google LLC
 *
 * Modified for Yoin (Swift port) of app/src/main/java/dynamiccolor/{DynamicScheme,ColorSpecs,MaterialDynamicColors}.kt.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import Foundation

/// Provides important settings for creating colors dynamically, and 6 color palettes. Requires: 1. A
/// color. (source color) 2. A theme. (Variant) 3. Whether or not its dark mode. 4. Contrast level.
/// (-1 to 1, currently contrast ratio 3.0 and 7.0)
struct DynamicScheme: Sendable {
    enum Platform: Sendable { case PHONE, WATCH }

    static let DEFAULT_SPEC_VERSION = SpecVersion.SPEC_2021
    static let DEFAULT_PLATFORM = Platform.PHONE

    let sourceColorHctList: [Hct]
    let variant: Variant
    let isDark: Bool
    let contrastLevel: Double
    let platform: Platform
    let specVersion: SpecVersion
    let primaryPalette: TonalPalette
    let secondaryPalette: TonalPalette
    let tertiaryPalette: TonalPalette
    let neutralPalette: TonalPalette
    let neutralVariantPalette: TonalPalette
    let errorPalette: TonalPalette

    var sourceColorHct: Hct { sourceColorHctList[0] }
    var sourceColorArgb: Int { sourceColorHct.toInt() }

    init(
        sourceColorHctList: [Hct],
        variant: Variant,
        isDark: Bool,
        contrastLevel: Double,
        platform: Platform = DEFAULT_PLATFORM,
        specVersion: SpecVersion = DEFAULT_SPEC_VERSION,
        primaryPalette: TonalPalette,
        secondaryPalette: TonalPalette,
        tertiaryPalette: TonalPalette,
        neutralPalette: TonalPalette,
        neutralVariantPalette: TonalPalette,
        errorPalette: TonalPalette
    ) {
        precondition(!sourceColorHctList.isEmpty, "sourceColorHctList cannot be empty")
        self.sourceColorHctList = sourceColorHctList
        self.variant = variant
        self.isDark = isDark
        self.contrastLevel = contrastLevel
        self.platform = platform
        self.specVersion = DynamicScheme.maybeFallbackSpecVersion(specVersion, variant)
        self.primaryPalette = primaryPalette
        self.secondaryPalette = secondaryPalette
        self.tertiaryPalette = tertiaryPalette
        self.neutralPalette = neutralPalette
        self.neutralVariantPalette = neutralVariantPalette
        self.errorPalette = errorPalette
    }

    func getHct(_ dynamicColor: DynamicColor) -> Hct { dynamicColor.getHct(self) }
    func getArgb(_ dynamicColor: DynamicColor) -> Int { dynamicColor.getArgb(self) }

    /// Kotlin reads roles through `MaterialDynamicColors` (a ColorSpec2026
    /// whose colors fall back to their 2025 definitions on a SPEC_2025
    /// scheme); this port reads the 2025 definitions directly.
    private var dynamicColors: ColorSpec2025 { MaterialDynamicColors() }

    var primaryPaletteKeyColor: Int { getArgb(dynamicColors.primaryPaletteKeyColor) }
    var secondaryPaletteKeyColor: Int { getArgb(dynamicColors.secondaryPaletteKeyColor) }
    var tertiaryPaletteKeyColor: Int { getArgb(dynamicColors.tertiaryPaletteKeyColor) }
    var neutralPaletteKeyColor: Int { getArgb(dynamicColors.neutralPaletteKeyColor) }
    var neutralVariantPaletteKeyColor: Int { getArgb(dynamicColors.neutralVariantPaletteKeyColor) }
    var background: Int { getArgb(dynamicColors.background) }
    var onBackground: Int { getArgb(dynamicColors.onBackground) }
    var surface: Int { getArgb(dynamicColors.surface) }
    var surfaceDim: Int { getArgb(dynamicColors.surfaceDim) }
    var surfaceBright: Int { getArgb(dynamicColors.surfaceBright) }
    var surfaceContainerLowest: Int { getArgb(dynamicColors.surfaceContainerLowest) }
    var surfaceContainerLow: Int { getArgb(dynamicColors.surfaceContainerLow) }
    var surfaceContainer: Int { getArgb(dynamicColors.surfaceContainer) }
    var surfaceContainerHigh: Int { getArgb(dynamicColors.surfaceContainerHigh) }
    var surfaceContainerHighest: Int { getArgb(dynamicColors.surfaceContainerHighest) }
    var onSurface: Int { getArgb(dynamicColors.onSurface) }
    var surfaceVariant: Int { getArgb(dynamicColors.surfaceVariant) }
    var onSurfaceVariant: Int { getArgb(dynamicColors.onSurfaceVariant) }
    var inverseSurface: Int { getArgb(dynamicColors.inverseSurface) }
    var inverseOnSurface: Int { getArgb(dynamicColors.inverseOnSurface) }
    var outline: Int { getArgb(dynamicColors.outline) }
    var outlineVariant: Int { getArgb(dynamicColors.outlineVariant) }
    var shadow: Int { getArgb(dynamicColors.shadow) }
    var scrim: Int { getArgb(dynamicColors.scrim) }
    var surfaceTint: Int { getArgb(dynamicColors.surfaceTint) }
    var primary: Int { getArgb(dynamicColors.primary) }
    var onPrimary: Int { getArgb(dynamicColors.onPrimary) }
    var primaryContainer: Int { getArgb(dynamicColors.primaryContainer) }
    var onPrimaryContainer: Int { getArgb(dynamicColors.onPrimaryContainer) }
    var inversePrimary: Int { getArgb(dynamicColors.inversePrimary) }
    var secondary: Int { getArgb(dynamicColors.secondary) }
    var onSecondary: Int { getArgb(dynamicColors.onSecondary) }
    var secondaryContainer: Int { getArgb(dynamicColors.secondaryContainer) }
    var onSecondaryContainer: Int { getArgb(dynamicColors.onSecondaryContainer) }
    var tertiary: Int { getArgb(dynamicColors.tertiary) }
    var onTertiary: Int { getArgb(dynamicColors.onTertiary) }
    var tertiaryContainer: Int { getArgb(dynamicColors.tertiaryContainer) }
    var onTertiaryContainer: Int { getArgb(dynamicColors.onTertiaryContainer) }
    var error: Int { getArgb(dynamicColors.error) }
    var onError: Int { getArgb(dynamicColors.onError) }
    var errorContainer: Int { getArgb(dynamicColors.errorContainer) }
    var onErrorContainer: Int { getArgb(dynamicColors.onErrorContainer) }
    var primaryFixed: Int { getArgb(dynamicColors.primaryFixed) }
    var primaryFixedDim: Int { getArgb(dynamicColors.primaryFixedDim) }
    var onPrimaryFixed: Int { getArgb(dynamicColors.onPrimaryFixed) }
    var onPrimaryFixedVariant: Int { getArgb(dynamicColors.onPrimaryFixedVariant) }
    var secondaryFixed: Int { getArgb(dynamicColors.secondaryFixed) }
    var secondaryFixedDim: Int { getArgb(dynamicColors.secondaryFixedDim) }
    var onSecondaryFixed: Int { getArgb(dynamicColors.onSecondaryFixed) }
    var onSecondaryFixedVariant: Int { getArgb(dynamicColors.onSecondaryFixedVariant) }
    var tertiaryFixed: Int { getArgb(dynamicColors.tertiaryFixed) }
    var tertiaryFixedDim: Int { getArgb(dynamicColors.tertiaryFixedDim) }
    var onTertiaryFixed: Int { getArgb(dynamicColors.onTertiaryFixed) }
    var onTertiaryFixedVariant: Int { getArgb(dynamicColors.onTertiaryFixedVariant) }

    static func from(_ other: DynamicScheme, isDark: Bool) -> DynamicScheme {
        from(other, isDark: isDark, contrastLevel: other.contrastLevel)
    }

    static func from(_ other: DynamicScheme, isDark: Bool, contrastLevel: Double) -> DynamicScheme {
        DynamicScheme(
            sourceColorHctList: other.sourceColorHctList,
            variant: other.variant,
            isDark: isDark,
            contrastLevel: contrastLevel,
            platform: other.platform,
            specVersion: other.specVersion,
            primaryPalette: other.primaryPalette,
            secondaryPalette: other.secondaryPalette,
            tertiaryPalette: other.tertiaryPalette,
            neutralPalette: other.neutralPalette,
            neutralVariantPalette: other.neutralVariantPalette,
            errorPalette: other.errorPalette
        )
    }

    /// Returns a new hue based on a piecewise function and input color hue.
    static func getPiecewiseValue(_ sourceColorHct: Hct, _ hueBreakpoints: [Double], _ hues: [Double]) -> Double {
        let size = min(hueBreakpoints.count - 1, hues.count)
        let sourceHue = sourceColorHct.hue
        for i in 0..<max(size, 0) {
            if sourceHue >= hueBreakpoints[i] && sourceHue < hueBreakpoints[i + 1] {
                return MathUtils.sanitizeDegreesDouble(hues[i])
            }
        }
        return sourceHue
    }

    /// Returns a shifted hue based on a piecewise function and input color hue.
    static func getRotatedHue(_ sourceColorHct: Hct, _ hueBreakpoints: [Double], _ rotations: [Double]) -> Double {
        var rotation = getPiecewiseValue(sourceColorHct, hueBreakpoints, rotations)
        if min(hueBreakpoints.count - 1, rotations.count) <= 0 {
            rotation = 0.0
        }
        return MathUtils.sanitizeDegreesDouble(sourceColorHct.hue + rotation)
    }

    private static func maybeFallbackSpecVersion(_ specVersion: SpecVersion, _ variant: Variant) -> SpecVersion {
        if variant == .CMF { return specVersion }
        if variant == .EXPRESSIVE || variant == .VIBRANT || variant == .TONAL_SPOT || variant == .NEUTRAL {
            return specVersion == .SPEC_2026 ? .SPEC_2025 : specVersion
        }
        return .SPEC_2021
    }
}

/// Kotlin `ColorSpecs`. Only SPEC_2025 is ported (SchemeExpressive with
/// SPEC_2025 is all Yoin uses); other versions trap.
enum ColorSpecs {
    static func get(_ specVersion: SpecVersion) -> ColorSpec2025 {
        precondition(specVersion == .SPEC_2025, "Only SPEC_2025 is ported to Swift (got \(specVersion)).")
        return ColorSpec2025()
    }
}

/// Kotlin `MaterialDynamicColors` (backed by ColorSpec2026 there). For a
/// SPEC_2025 scheme every 2026 color resolves to its 2025 definition, so the
/// port uses ColorSpec2025 directly.
typealias MaterialDynamicColors = ColorSpec2025
