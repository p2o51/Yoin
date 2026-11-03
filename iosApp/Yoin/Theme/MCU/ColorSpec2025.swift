/*
 * Copyright 2025 Google LLC
 *
 * Modified for Yoin (Swift port) of app/src/main/java/dynamiccolor/ColorSpec2025.kt (plus the ColorSpec2021.kt members it inherits).
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

/// [ColorSpec] implementation for the 2025 spec.
///
/// Port notes:
/// - Kotlin builds each 2025 color as `super.x.extendSpecVersion(SPEC_2025, color2025)`
///   on top of the 2021 one. On a SPEC_2025 scheme every wrapper resolves to
///   `color2025`, so this port stores the 2025 definition directly.
/// - The members 2025 inherits unchanged from ColorSpec2021 (palette key
///   colors, shadow, scrim) are copied here.
/// - Palettes are ported for the variants ColorSpec2025 handles itself
///   (NEUTRAL, TONAL_SPOT, EXPRESSIVE, VIBRANT); others fall back to 2021 in
///   Kotlin and trap here.
struct ColorSpec2025: Sendable {
    // MARK: Main Palettes (ColorSpec2021)

    var primaryPaletteKeyColor: DynamicColor {
        DynamicColor(name: "primary_palette_key_color", palette: { $0.primaryPalette },
                     tone: { $0.primaryPalette.keyColor.tone })
    }

    var secondaryPaletteKeyColor: DynamicColor {
        DynamicColor(name: "secondary_palette_key_color", palette: { $0.secondaryPalette },
                     tone: { $0.secondaryPalette.keyColor.tone })
    }

    var tertiaryPaletteKeyColor: DynamicColor {
        DynamicColor(name: "tertiary_palette_key_color", palette: { $0.tertiaryPalette },
                     tone: { $0.tertiaryPalette.keyColor.tone })
    }

    var neutralPaletteKeyColor: DynamicColor {
        DynamicColor(name: "neutral_palette_key_color", palette: { $0.neutralPalette },
                     tone: { $0.neutralPalette.keyColor.tone })
    }

    var neutralVariantPaletteKeyColor: DynamicColor {
        DynamicColor(name: "neutral_variant_palette_key_color", palette: { $0.neutralVariantPalette },
                     tone: { $0.neutralVariantPalette.keyColor.tone })
    }

    var errorPaletteKeyColor: DynamicColor {
        DynamicColor(name: "error_palette_key_color", palette: { $0.errorPalette },
                     tone: { $0.errorPalette.keyColor.tone })
    }

    // MARK: Surfaces [S]

    var background: DynamicColor { surface.copy(name: "background") }

    var onBackground: DynamicColor {
        let onSurface = self.onSurface
        return onSurface.copy(name: "on_background", tone: { scheme in
            scheme.platform == .WATCH ? 100.0 : onSurface.getTone(scheme)
        })
    }

    var surface: DynamicColor {
        DynamicColor(
            name: "surface",
            palette: { $0.neutralPalette },
            isBackground: true,
            tone: { scheme in
                if scheme.platform == .PHONE {
                    if scheme.isDark { return 4.0 }
                    if Hct.isYellow(scheme.neutralPalette.hue) { return 99.0 }
                    if scheme.variant == .VIBRANT { return 97.0 }
                    return 98.0
                }
                return 0.0
            }
        )
    }

    var surfaceDim: DynamicColor {
        DynamicColor(
            name: "surface_dim",
            palette: { $0.neutralPalette },
            isBackground: true,
            chromaMultiplier: { scheme in
                if !scheme.isDark {
                    switch scheme.variant {
                    case .NEUTRAL: return 2.5
                    case .TONAL_SPOT: return 1.7
                    case .EXPRESSIVE: return Hct.isYellow(scheme.neutralPalette.hue) ? 2.7 : 1.75
                    case .VIBRANT: return 1.36
                    default: return 1.0
                    }
                }
                return 1.0
            },
            tone: { scheme in
                if scheme.isDark { return 4.0 }
                if Hct.isYellow(scheme.neutralPalette.hue) { return 90.0 }
                if scheme.variant == .VIBRANT { return 85.0 }
                return 87.0
            }
        )
    }

    var surfaceBright: DynamicColor {
        DynamicColor(
            name: "surface_bright",
            palette: { $0.neutralPalette },
            isBackground: true,
            chromaMultiplier: { scheme in
                if scheme.isDark {
                    switch scheme.variant {
                    case .NEUTRAL: return 2.5
                    case .TONAL_SPOT: return 1.7
                    case .EXPRESSIVE: return Hct.isYellow(scheme.neutralPalette.hue) ? 2.7 : 1.75
                    case .VIBRANT: return 1.36
                    default: return 1.0
                    }
                }
                return 1.0
            },
            tone: { scheme in
                if scheme.isDark { return 18.0 }
                if Hct.isYellow(scheme.neutralPalette.hue) { return 99.0 }
                if scheme.variant == .VIBRANT { return 97.0 }
                return 98.0
            }
        )
    }

    var surfaceContainerLowest: DynamicColor {
        DynamicColor(name: "surface_container_lowest", palette: { $0.neutralPalette }, isBackground: true,
                     tone: { $0.isDark ? 0.0 : 100.0 })
    }

    var surfaceContainerLow: DynamicColor {
        DynamicColor(
            name: "surface_container_low",
            palette: { $0.neutralPalette },
            isBackground: true,
            chromaMultiplier: { scheme in
                if scheme.platform == .PHONE {
                    switch scheme.variant {
                    case .NEUTRAL: return 1.3
                    case .TONAL_SPOT: return 1.25
                    case .EXPRESSIVE: return Hct.isYellow(scheme.neutralPalette.hue) ? 1.3 : 1.15
                    case .VIBRANT: return 1.08
                    default: return 1.0
                    }
                }
                return 1.0
            },
            tone: { scheme in
                if scheme.platform == .PHONE {
                    if scheme.isDark { return 6.0 }
                    if Hct.isYellow(scheme.neutralPalette.hue) { return 98.0 }
                    if scheme.variant == .VIBRANT { return 95.0 }
                    return 96.0
                }
                return 15.0
            }
        )
    }

    var surfaceContainer: DynamicColor {
        DynamicColor(
            name: "surface_container",
            palette: { $0.neutralPalette },
            isBackground: true,
            chromaMultiplier: { scheme in
                if scheme.platform == .PHONE {
                    switch scheme.variant {
                    case .NEUTRAL: return 1.6
                    case .TONAL_SPOT: return 1.4
                    case .EXPRESSIVE: return Hct.isYellow(scheme.neutralPalette.hue) ? 1.6 : 1.3
                    case .VIBRANT: return 1.15
                    default: return 1.0
                    }
                }
                return 1.0
            },
            tone: { scheme in
                if scheme.platform == .PHONE {
                    if scheme.isDark { return 9.0 }
                    if Hct.isYellow(scheme.neutralPalette.hue) { return 96.0 }
                    if scheme.variant == .VIBRANT { return 92.0 }
                    return 94.0
                }
                return 20.0
            }
        )
    }

    var surfaceContainerHigh: DynamicColor {
        DynamicColor(
            name: "surface_container_high",
            palette: { $0.neutralPalette },
            isBackground: true,
            chromaMultiplier: { scheme in
                if scheme.platform == .PHONE {
                    switch scheme.variant {
                    case .NEUTRAL: return 1.9
                    case .TONAL_SPOT: return 1.5
                    case .EXPRESSIVE: return Hct.isYellow(scheme.neutralPalette.hue) ? 1.95 : 1.45
                    case .VIBRANT: return 1.22
                    default: return 1.0
                    }
                }
                return 1.0
            },
            tone: { scheme in
                if scheme.platform == .PHONE {
                    if scheme.isDark { return 12.0 }
                    if Hct.isYellow(scheme.neutralPalette.hue) { return 94.0 }
                    if scheme.variant == .VIBRANT { return 90.0 }
                    return 92.0
                }
                return 25.0
            }
        )
    }

    var surfaceContainerHighest: DynamicColor {
        DynamicColor(
            name: "surface_container_highest",
            palette: { $0.neutralPalette },
            isBackground: true,
            chromaMultiplier: { scheme in
                switch scheme.variant {
                case .NEUTRAL: return 2.2
                case .TONAL_SPOT: return 1.7
                case .EXPRESSIVE: return Hct.isYellow(scheme.neutralPalette.hue) ? 2.3 : 1.6
                case .VIBRANT: return 1.29
                default: return 1.0
                }
            },
            tone: { scheme in
                if scheme.isDark { return 15.0 }
                if Hct.isYellow(scheme.neutralPalette.hue) { return 92.0 }
                if scheme.variant == .VIBRANT { return 88.0 }
                return 90.0
            }
        )
    }

    /// The `if PHONE { isDark ? surfaceBright : surfaceDim } else { surfaceContainerHigh }`
    /// background most 2025 roles share.
    private func phoneSurfaceBackground(_ scheme: DynamicScheme) -> DynamicColor? {
        if scheme.platform == .PHONE {
            return scheme.isDark ? surfaceBright : surfaceDim
        }
        return surfaceContainerHigh
    }

    /// The `if PHONE { isDark ? surfaceBright : surfaceDim } else { null }` background of containers.
    private func phoneOnlySurfaceBackground(_ scheme: DynamicScheme) -> DynamicColor? {
        if scheme.platform == .PHONE {
            return scheme.isDark ? surfaceBright : surfaceDim
        }
        return nil
    }

    /// The onSurface-family chroma multiplier.
    private static func onSurfaceChromaMultiplier(_ scheme: DynamicScheme) -> Double {
        if scheme.platform == .PHONE {
            switch scheme.variant {
            case .NEUTRAL: return 2.2
            case .TONAL_SPOT: return 1.7
            case .EXPRESSIVE:
                if Hct.isYellow(scheme.neutralPalette.hue) {
                    return scheme.isDark ? 3.0 : 2.3
                }
                return 1.6
            default: return 1.0
            }
        }
        return 1.0
    }

    var onSurface: DynamicColor {
        let spec = self
        return DynamicColor(
            name: "on_surface",
            palette: { $0.neutralPalette },
            chromaMultiplier: { ColorSpec2025.onSurfaceChromaMultiplier($0) },
            background: { spec.phoneSurfaceBackground($0) },
            tone: { scheme in
                if scheme.variant == .VIBRANT {
                    return spec.tMaxC(scheme.neutralPalette, 0.0, 100.0, 1.1)
                }
                return DynamicColor.getInitialToneFromBackground({ s in
                    if s.platform == .PHONE {
                        return s.isDark ? spec.surfaceBright : spec.surfaceDim
                    }
                    return spec.surfaceContainerHigh
                })(scheme)
            },
            contrastCurve: { scheme in
                (scheme.isDark && scheme.platform == .PHONE) ? spec.getContrastCurve(11.0) : spec.getContrastCurve(9.0)
            }
        )
    }

    var surfaceVariant: DynamicColor { surfaceContainerHighest.copy(name: "surface_variant") }

    var onSurfaceVariant: DynamicColor {
        let spec = self
        return DynamicColor(
            name: "on_surface_variant",
            palette: { $0.neutralPalette },
            chromaMultiplier: { ColorSpec2025.onSurfaceChromaMultiplier($0) },
            background: { spec.phoneSurfaceBackground($0) },
            contrastCurve: { scheme in
                if scheme.platform == .PHONE {
                    return scheme.isDark ? spec.getContrastCurve(6.0) : spec.getContrastCurve(4.5)
                }
                return spec.getContrastCurve(7.0)
            }
        )
    }

    var inverseSurface: DynamicColor {
        DynamicColor(name: "inverse_surface", palette: { $0.neutralPalette }, isBackground: true,
                     tone: { $0.isDark ? 98.0 : 4.0 })
    }

    var inverseOnSurface: DynamicColor {
        let spec = self
        return DynamicColor(
            name: "inverse_on_surface",
            palette: { $0.neutralPalette },
            background: { _ in spec.inverseSurface },
            contrastCurve: { _ in spec.getContrastCurve(7.0) }
        )
    }

    var outline: DynamicColor {
        let spec = self
        return DynamicColor(
            name: "outline",
            palette: { $0.neutralPalette },
            chromaMultiplier: { ColorSpec2025.onSurfaceChromaMultiplier($0) },
            background: { spec.phoneSurfaceBackground($0) },
            contrastCurve: { scheme in
                scheme.platform == .PHONE ? spec.getContrastCurve(3.0) : spec.getContrastCurve(4.5)
            }
        )
    }

    var outlineVariant: DynamicColor {
        let spec = self
        return DynamicColor(
            name: "outline_variant",
            palette: { $0.neutralPalette },
            chromaMultiplier: { ColorSpec2025.onSurfaceChromaMultiplier($0) },
            background: { spec.phoneSurfaceBackground($0) },
            contrastCurve: { scheme in
                scheme.platform == .PHONE ? spec.getContrastCurve(1.5) : spec.getContrastCurve(3.0)
            }
        )
    }

    var shadow: DynamicColor {
        DynamicColor(name: "shadow", palette: { $0.neutralPalette }, tone: { _ in 0.0 })
    }

    var scrim: DynamicColor {
        DynamicColor(name: "scrim", palette: { $0.neutralPalette }, tone: { _ in 0.0 })
    }

    var surfaceTint: DynamicColor { primary.copy(name: "surface_tint") }

    // MARK: Primaries [P]

    var primary: DynamicColor {
        let spec = self
        return DynamicColor(
            name: "primary",
            palette: { $0.primaryPalette },
            isBackground: true,
            background: { spec.phoneSurfaceBackground($0) },
            tone: { scheme in
                switch scheme.variant {
                case .NEUTRAL:
                    if scheme.platform == .PHONE { return scheme.isDark ? 80.0 : 40.0 }
                    return 90.0
                case .TONAL_SPOT:
                    if scheme.platform == .PHONE {
                        return scheme.isDark ? 80.0 : spec.tMaxC(scheme.primaryPalette)
                    }
                    return spec.tMaxC(scheme.primaryPalette, 0.0, 90.0)
                case .EXPRESSIVE:
                    if scheme.platform == .PHONE {
                        let upper: Double
                        if Hct.isYellow(scheme.primaryPalette.hue) {
                            upper = 25.0
                        } else if Hct.isCyan(scheme.primaryPalette.hue) {
                            upper = 88.0
                        } else {
                            upper = 98.0
                        }
                        return spec.tMaxC(scheme.primaryPalette, 0.0, upper)
                    }
                    return spec.tMaxC(scheme.primaryPalette)
                default: // VIBRANT
                    if scheme.platform == .PHONE {
                        return spec.tMaxC(scheme.primaryPalette, 0.0,
                                          Hct.isCyan(scheme.primaryPalette.hue) ? 88.0 : 98.0)
                    }
                    return spec.tMaxC(scheme.primaryPalette)
                }
            },
            contrastCurve: { scheme in
                scheme.platform == .PHONE ? spec.getContrastCurve(4.5) : spec.getContrastCurve(7.0)
            },
            toneDeltaPair: { scheme in
                guard scheme.platform == .PHONE else { return nil }
                return ToneDeltaPair(roleA: spec.primaryContainer, roleB: spec.primary, delta: 5.0,
                                     polarity: .RELATIVE_LIGHTER, constraint: .FARTHER)
            }
        )
    }

    var primaryDim: DynamicColor? {
        let spec = self
        return DynamicColor(
            name: "primary_dim",
            palette: { $0.primaryPalette },
            isBackground: true,
            background: { _ in spec.surfaceContainerHigh },
            tone: { scheme in
                switch scheme.variant {
                case .NEUTRAL: return 85.0
                case .TONAL_SPOT: return spec.tMaxC(scheme.primaryPalette, 0.0, 90.0)
                default: return spec.tMaxC(scheme.primaryPalette)
                }
            },
            contrastCurve: { _ in spec.getContrastCurve(4.5) },
            toneDeltaPair: { _ in
                ToneDeltaPair(roleA: spec.primaryDim!, roleB: spec.primary, delta: 5.0,
                              polarity: .DARKER, constraint: .FARTHER)
            }
        )
    }

    var onPrimary: DynamicColor {
        let spec = self
        return DynamicColor(
            name: "on_primary",
            palette: { $0.primaryPalette },
            background: { $0.platform == .PHONE ? spec.primary : spec.primaryDim },
            contrastCurve: { $0.platform == .PHONE ? spec.getContrastCurve(6.0) : spec.getContrastCurve(7.0) }
        )
    }

    var primaryContainer: DynamicColor {
        let spec = self
        return DynamicColor(
            name: "primary_container",
            palette: { $0.primaryPalette },
            isBackground: true,
            background: { spec.phoneOnlySurfaceBackground($0) },
            tone: { scheme in
                if scheme.platform == .WATCH { return 30.0 }
                switch scheme.variant {
                case .NEUTRAL:
                    return scheme.isDark ? 30.0 : 90.0
                case .TONAL_SPOT:
                    return scheme.isDark
                        ? spec.tMinC(scheme.primaryPalette, 35.0, 93.0)
                        : spec.tMaxC(scheme.primaryPalette, 0.0, 90.0)
                case .EXPRESSIVE:
                    return scheme.isDark
                        ? spec.tMaxC(scheme.primaryPalette, 30.0, 93.0)
                        : spec.tMaxC(scheme.primaryPalette, 78.0,
                                     Hct.isCyan(scheme.primaryPalette.hue) ? 88.0 : 90.0)
                default: // VIBRANT
                    return scheme.isDark
                        ? spec.tMinC(scheme.primaryPalette, 66.0, 93.0)
                        : spec.tMaxC(scheme.primaryPalette, 66.0,
                                     Hct.isCyan(scheme.primaryPalette.hue) ? 88.0 : 93.0)
                }
            },
            contrastCurve: { scheme in
                (scheme.platform == .PHONE && scheme.contrastLevel > 0) ? spec.getContrastCurve(1.5) : nil
            },
            toneDeltaPair: { scheme in
                guard scheme.platform == .WATCH else { return nil }
                return ToneDeltaPair(roleA: spec.primaryContainer, roleB: spec.primaryDim!, delta: 10.0,
                                     polarity: .DARKER, constraint: .FARTHER)
            }
        )
    }

    var onPrimaryContainer: DynamicColor {
        let spec = self
        return DynamicColor(
            name: "on_primary_container",
            palette: { $0.primaryPalette },
            background: { _ in spec.primaryContainer },
            contrastCurve: { $0.platform == .PHONE ? spec.getContrastCurve(6.0) : spec.getContrastCurve(7.0) }
        )
    }

    var inversePrimary: DynamicColor {
        let spec = self
        return DynamicColor(
            name: "inverse_primary",
            palette: { $0.primaryPalette },
            background: { _ in spec.inverseSurface },
            tone: { spec.tMaxC($0.primaryPalette) },
            contrastCurve: { $0.platform == .PHONE ? spec.getContrastCurve(6.0) : spec.getContrastCurve(7.0) }
        )
    }

    // MARK: Secondaries [Q]

    var secondary: DynamicColor {
        let spec = self
        return DynamicColor(
            name: "secondary",
            palette: { $0.secondaryPalette },
            isBackground: true,
            background: { spec.phoneSurfaceBackground($0) },
            tone: { scheme in
                if scheme.platform == .WATCH {
                    return scheme.variant == .NEUTRAL ? 90.0 : spec.tMaxC(scheme.secondaryPalette, 0.0, 90.0)
                }
                switch scheme.variant {
                case .NEUTRAL:
                    return scheme.isDark
                        ? spec.tMinC(scheme.secondaryPalette, 0.0, 98.0)
                        : spec.tMaxC(scheme.secondaryPalette)
                case .VIBRANT:
                    return spec.tMaxC(scheme.secondaryPalette, 0.0, scheme.isDark ? 90.0 : 98.0)
                default: // EXPRESSIVE and TONAL_SPOT
                    return scheme.isDark ? 80.0 : spec.tMaxC(scheme.secondaryPalette)
                }
            },
            contrastCurve: { $0.platform == .PHONE ? spec.getContrastCurve(4.5) : spec.getContrastCurve(7.0) },
            toneDeltaPair: { scheme in
                guard scheme.platform == .PHONE else { return nil }
                return ToneDeltaPair(roleA: spec.secondaryContainer, roleB: spec.secondary, delta: 5.0,
                                     polarity: .RELATIVE_LIGHTER, constraint: .FARTHER)
            }
        )
    }

    var secondaryDim: DynamicColor? {
        let spec = self
        return DynamicColor(
            name: "secondary_dim",
            palette: { $0.secondaryPalette },
            isBackground: true,
            background: { _ in spec.surfaceContainerHigh },
            tone: { scheme in
                scheme.variant == .NEUTRAL ? 85.0 : spec.tMaxC(scheme.secondaryPalette, 0.0, 90.0)
            },
            contrastCurve: { _ in spec.getContrastCurve(4.5) },
            toneDeltaPair: { _ in
                ToneDeltaPair(roleA: spec.secondaryDim!, roleB: spec.secondary, delta: 5.0,
                              polarity: .DARKER, constraint: .FARTHER)
            }
        )
    }

    var onSecondary: DynamicColor {
        let spec = self
        return DynamicColor(
            name: "on_secondary",
            palette: { $0.secondaryPalette },
            background: { $0.platform == .PHONE ? spec.secondary : spec.secondaryDim },
            contrastCurve: { $0.platform == .PHONE ? spec.getContrastCurve(6.0) : spec.getContrastCurve(7.0) }
        )
    }

    var secondaryContainer: DynamicColor {
        let spec = self
        return DynamicColor(
            name: "secondary_container",
            palette: { $0.secondaryPalette },
            isBackground: true,
            background: { spec.phoneOnlySurfaceBackground($0) },
            tone: { scheme in
                if scheme.platform == .WATCH { return 30.0 }
                switch scheme.variant {
                case .VIBRANT:
                    return scheme.isDark
                        ? spec.tMinC(scheme.secondaryPalette, 30.0, 40.0)
                        : spec.tMaxC(scheme.secondaryPalette, 84.0, 90.0)
                case .EXPRESSIVE:
                    return scheme.isDark ? 15.0 : spec.tMaxC(scheme.secondaryPalette, 90.0, 95.0)
                default:
                    return scheme.isDark ? 25.0 : 90.0
                }
            },
            contrastCurve: { scheme in
                (scheme.platform == .PHONE && scheme.contrastLevel > 0) ? spec.getContrastCurve(1.5) : nil
            },
            toneDeltaPair: { scheme in
                guard scheme.platform == .WATCH else { return nil }
                return ToneDeltaPair(roleA: spec.secondaryContainer, roleB: spec.secondaryDim!, delta: 10.0,
                                     polarity: .DARKER, constraint: .FARTHER)
            }
        )
    }

    var onSecondaryContainer: DynamicColor {
        let spec = self
        return DynamicColor(
            name: "on_secondary_container",
            palette: { $0.secondaryPalette },
            background: { _ in spec.secondaryContainer },
            contrastCurve: { $0.platform == .PHONE ? spec.getContrastCurve(6.0) : spec.getContrastCurve(7.0) }
        )
    }

    // MARK: Tertiaries [T]

    var tertiary: DynamicColor {
        let spec = self
        return DynamicColor(
            name: "tertiary",
            palette: { $0.tertiaryPalette },
            isBackground: true,
            background: { spec.phoneSurfaceBackground($0) },
            tone: { scheme in
                if scheme.platform == .WATCH {
                    return scheme.variant == .TONAL_SPOT
                        ? spec.tMaxC(scheme.tertiaryPalette, 0.0, 90.0)
                        : spec.tMaxC(scheme.tertiaryPalette)
                }
                if scheme.variant == .EXPRESSIVE || scheme.variant == .VIBRANT {
                    let upper: Double
                    if Hct.isCyan(scheme.tertiaryPalette.hue) {
                        upper = 88.0
                    } else if scheme.isDark {
                        upper = 98.0
                    } else {
                        upper = 100.0
                    }
                    return spec.tMaxC(scheme.tertiaryPalette, 0.0, upper)
                }
                // NEUTRAL and TONAL_SPOT
                return scheme.isDark
                    ? spec.tMaxC(scheme.tertiaryPalette, 0.0, 98.0)
                    : spec.tMaxC(scheme.tertiaryPalette)
            },
            contrastCurve: { $0.platform == .PHONE ? spec.getContrastCurve(4.5) : spec.getContrastCurve(7.0) },
            toneDeltaPair: { scheme in
                guard scheme.platform == .PHONE else { return nil }
                return ToneDeltaPair(roleA: spec.tertiaryContainer, roleB: spec.tertiary, delta: 5.0,
                                     polarity: .RELATIVE_LIGHTER, constraint: .FARTHER)
            }
        )
    }

    var tertiaryDim: DynamicColor? {
        let spec = self
        return DynamicColor(
            name: "tertiary_dim",
            palette: { $0.tertiaryPalette },
            isBackground: true,
            background: { _ in spec.surfaceContainerHigh },
            tone: { scheme in
                scheme.variant == .TONAL_SPOT
                    ? spec.tMaxC(scheme.tertiaryPalette, 0.0, 90.0)
                    : spec.tMaxC(scheme.tertiaryPalette)
            },
            contrastCurve: { _ in spec.getContrastCurve(4.5) },
            toneDeltaPair: { _ in
                ToneDeltaPair(roleA: spec.tertiaryDim!, roleB: spec.tertiary, delta: 5.0,
                              polarity: .DARKER, constraint: .FARTHER)
            }
        )
    }

    var onTertiary: DynamicColor {
        let spec = self
        return DynamicColor(
            name: "on_tertiary",
            palette: { $0.tertiaryPalette },
            background: { $0.platform == .PHONE ? spec.tertiary : spec.tertiaryDim },
            contrastCurve: { $0.platform == .PHONE ? spec.getContrastCurve(6.0) : spec.getContrastCurve(7.0) }
        )
    }

    var tertiaryContainer: DynamicColor {
        let spec = self
        return DynamicColor(
            name: "tertiary_container",
            palette: { $0.tertiaryPalette },
            isBackground: true,
            background: { spec.phoneOnlySurfaceBackground($0) },
            tone: { scheme in
                if scheme.platform == .WATCH {
                    return scheme.variant == .TONAL_SPOT
                        ? spec.tMaxC(scheme.tertiaryPalette, 0.0, 90.0)
                        : spec.tMaxC(scheme.tertiaryPalette)
                }
                switch scheme.variant {
                case .NEUTRAL:
                    return scheme.isDark
                        ? spec.tMaxC(scheme.tertiaryPalette, 0.0, 93.0)
                        : spec.tMaxC(scheme.tertiaryPalette, 0.0, 96.0)
                case .TONAL_SPOT:
                    return spec.tMaxC(scheme.tertiaryPalette, 0.0, scheme.isDark ? 93.0 : 100.0)
                case .EXPRESSIVE:
                    let upper: Double
                    if Hct.isCyan(scheme.tertiaryPalette.hue) {
                        upper = 88.0
                    } else if scheme.isDark {
                        upper = 93.0
                    } else {
                        upper = 100.0
                    }
                    return spec.tMaxC(scheme.tertiaryPalette, 75.0, upper)
                default: // VIBRANT
                    return scheme.isDark
                        ? spec.tMaxC(scheme.tertiaryPalette, 0.0, 93.0)
                        : spec.tMaxC(scheme.tertiaryPalette, 72.0, 100.0)
                }
            },
            contrastCurve: { scheme in
                (scheme.platform == .PHONE && scheme.contrastLevel > 0) ? spec.getContrastCurve(1.5) : nil
            },
            toneDeltaPair: { scheme in
                guard scheme.platform == .WATCH else { return nil }
                return ToneDeltaPair(roleA: spec.tertiaryContainer, roleB: spec.tertiaryDim!, delta: 10.0,
                                     polarity: .DARKER, constraint: .FARTHER)
            }
        )
    }

    var onTertiaryContainer: DynamicColor {
        let spec = self
        return DynamicColor(
            name: "on_tertiary_container",
            palette: { $0.tertiaryPalette },
            background: { _ in spec.tertiaryContainer },
            contrastCurve: { $0.platform == .PHONE ? spec.getContrastCurve(6.0) : spec.getContrastCurve(7.0) }
        )
    }

    // MARK: Errors [E]

    var error: DynamicColor {
        let spec = self
        return DynamicColor(
            name: "error",
            palette: { $0.errorPalette },
            isBackground: true,
            background: { spec.phoneSurfaceBackground($0) },
            tone: { scheme in
                if scheme.platform == .PHONE {
                    return scheme.isDark
                        ? spec.tMinC(scheme.errorPalette, 0.0, 98.0)
                        : spec.tMaxC(scheme.errorPalette)
                }
                return spec.tMinC(scheme.errorPalette)
            },
            contrastCurve: { $0.platform == .PHONE ? spec.getContrastCurve(4.5) : spec.getContrastCurve(7.0) },
            toneDeltaPair: { scheme in
                guard scheme.platform == .PHONE else { return nil }
                return ToneDeltaPair(roleA: spec.errorContainer, roleB: spec.error, delta: 5.0,
                                     polarity: .RELATIVE_LIGHTER, constraint: .FARTHER)
            }
        )
    }

    var errorDim: DynamicColor? {
        let spec = self
        return DynamicColor(
            name: "error_dim",
            palette: { $0.errorPalette },
            isBackground: true,
            background: { _ in spec.surfaceContainerHigh },
            tone: { spec.tMinC($0.errorPalette) },
            contrastCurve: { _ in spec.getContrastCurve(4.5) },
            toneDeltaPair: { _ in
                ToneDeltaPair(roleA: spec.errorDim!, roleB: spec.error, delta: 5.0,
                              polarity: .DARKER, constraint: .FARTHER)
            }
        )
    }

    var onError: DynamicColor {
        let spec = self
        return DynamicColor(
            name: "on_error",
            palette: { $0.errorPalette },
            background: { $0.platform == .PHONE ? spec.error : spec.errorDim },
            contrastCurve: { $0.platform == .PHONE ? spec.getContrastCurve(6.0) : spec.getContrastCurve(7.0) }
        )
    }

    var errorContainer: DynamicColor {
        let spec = self
        return DynamicColor(
            name: "error_container",
            palette: { $0.errorPalette },
            isBackground: true,
            background: { spec.phoneOnlySurfaceBackground($0) },
            tone: { scheme in
                if scheme.platform == .WATCH { return 30.0 }
                return scheme.isDark
                    ? spec.tMinC(scheme.errorPalette, 30.0, 93.0)
                    : spec.tMaxC(scheme.errorPalette, 0.0, 90.0)
            },
            contrastCurve: { scheme in
                (scheme.platform == .PHONE && scheme.contrastLevel > 0) ? spec.getContrastCurve(1.5) : nil
            },
            toneDeltaPair: { scheme in
                guard scheme.platform == .WATCH else { return nil }
                return ToneDeltaPair(roleA: spec.errorContainer, roleB: spec.errorDim!, delta: 10.0,
                                     polarity: .DARKER, constraint: .FARTHER)
            }
        )
    }

    var onErrorContainer: DynamicColor {
        let spec = self
        return DynamicColor(
            name: "on_error_container",
            palette: { $0.errorPalette },
            background: { _ in spec.errorContainer },
            contrastCurve: { $0.platform == .PHONE ? spec.getContrastCurve(4.5) : spec.getContrastCurve(7.0) }
        )
    }

    // MARK: Primary Fixed Colors [PF]

    private func fixed(
        _ name: String,
        _ palette: @escaping SchemeFn<TonalPalette>,
        container: @escaping @Sendable (ColorSpec2025) -> DynamicColor
    ) -> DynamicColor {
        let spec = self
        return DynamicColor(
            name: name,
            palette: palette,
            isBackground: true,
            background: { spec.phoneOnlySurfaceBackground($0) },
            tone: { scheme in
                let tempS = DynamicScheme.from(scheme, isDark: false, contrastLevel: 0.0)
                return container(spec).getTone(tempS)
            },
            contrastCurve: { scheme in
                (scheme.platform == .PHONE && scheme.contrastLevel > 0) ? spec.getContrastCurve(1.5) : nil
            }
        )
    }

    private func fixedDim(
        _ name: String,
        _ palette: @escaping SchemeFn<TonalPalette>,
        fixed: @escaping @Sendable (ColorSpec2025) -> DynamicColor,
        fixedDim: @escaping @Sendable (ColorSpec2025) -> DynamicColor
    ) -> DynamicColor {
        let spec = self
        return DynamicColor(
            name: name,
            palette: palette,
            isBackground: true,
            tone: { fixed(spec).getTone($0) },
            toneDeltaPair: { _ in
                ToneDeltaPair(roleA: fixedDim(spec), roleB: fixed(spec), delta: 5.0,
                              polarity: .DARKER, constraint: .EXACT)
            }
        )
    }

    private func onFixed(
        _ name: String,
        _ palette: @escaping SchemeFn<TonalPalette>,
        fixedDim: @escaping @Sendable (ColorSpec2025) -> DynamicColor,
        contrast: Double
    ) -> DynamicColor {
        let spec = self
        return DynamicColor(
            name: name,
            palette: palette,
            background: { _ in fixedDim(spec) },
            contrastCurve: { _ in spec.getContrastCurve(contrast) }
        )
    }

    var primaryFixed: DynamicColor {
        fixed("primary_fixed", { $0.primaryPalette }, container: { $0.primaryContainer })
    }

    var primaryFixedDim: DynamicColor {
        fixedDim("primary_fixed_dim", { $0.primaryPalette },
                 fixed: { $0.primaryFixed }, fixedDim: { $0.primaryFixedDim })
    }

    var onPrimaryFixed: DynamicColor {
        onFixed("on_primary_fixed", { $0.primaryPalette }, fixedDim: { $0.primaryFixedDim }, contrast: 7.0)
    }

    var onPrimaryFixedVariant: DynamicColor {
        onFixed("on_primary_fixed_variant", { $0.primaryPalette }, fixedDim: { $0.primaryFixedDim }, contrast: 4.5)
    }

    // MARK: Secondary Fixed Colors [QF]

    var secondaryFixed: DynamicColor {
        fixed("secondary_fixed", { $0.secondaryPalette }, container: { $0.secondaryContainer })
    }

    var secondaryFixedDim: DynamicColor {
        fixedDim("secondary_fixed_dim", { $0.secondaryPalette },
                 fixed: { $0.secondaryFixed }, fixedDim: { $0.secondaryFixedDim })
    }

    var onSecondaryFixed: DynamicColor {
        onFixed("on_secondary_fixed", { $0.secondaryPalette }, fixedDim: { $0.secondaryFixedDim }, contrast: 7.0)
    }

    var onSecondaryFixedVariant: DynamicColor {
        onFixed("on_secondary_fixed_variant", { $0.secondaryPalette }, fixedDim: { $0.secondaryFixedDim },
                contrast: 4.5)
    }

    // MARK: Tertiary Fixed Colors [TF]

    var tertiaryFixed: DynamicColor {
        fixed("tertiary_fixed", { $0.tertiaryPalette }, container: { $0.tertiaryContainer })
    }

    var tertiaryFixedDim: DynamicColor {
        fixedDim("tertiary_fixed_dim", { $0.tertiaryPalette },
                 fixed: { $0.tertiaryFixed }, fixedDim: { $0.tertiaryFixedDim })
    }

    var onTertiaryFixed: DynamicColor {
        onFixed("on_tertiary_fixed", { $0.tertiaryPalette }, fixedDim: { $0.tertiaryFixedDim }, contrast: 7.0)
    }

    var onTertiaryFixedVariant: DynamicColor {
        onFixed("on_tertiary_fixed_variant", { $0.tertiaryPalette }, fixedDim: { $0.tertiaryFixedDim },
                contrast: 4.5)
    }

    // MARK: Other

    func highestSurface(_ scheme: DynamicScheme) -> DynamicColor {
        scheme.isDark ? surfaceBright : surfaceDim
    }

    // MARK: Color value calculations

    func getHct(_ scheme: DynamicScheme, _ color: DynamicColor) -> Hct {
        let palette = color.palette(scheme)
        let tone = getTone(scheme, color)
        let chromaMultiplier = color.chromaMultiplier?(scheme) ?? 1.0
        if chromaMultiplier == 1.0 {
            return palette.getHct(tone)
        }
        let chroma = palette.chroma * chromaMultiplier
        if tone == 99.0 && Hct.isYellow(palette.hue) {
            return TonalPalette.fromHueAndChroma(palette.hue, chroma).getHct(tone)
        }
        return Hct.from(palette.hue, chroma, tone)
    }

    func getTone(_ scheme: DynamicScheme, _ color: DynamicColor) -> Double {
        if let toneDeltaPair = color.toneDeltaPair?(scheme) {
            let roleA = toneDeltaPair.roleA
            let roleB = toneDeltaPair.roleB
            let polarity = toneDeltaPair.polarity
            let constraint = toneDeltaPair.constraint
            let absoluteDelta: Double =
                (polarity == .DARKER
                    || (polarity == .RELATIVE_LIGHTER && scheme.isDark)
                    || (polarity == .RELATIVE_DARKER && !scheme.isDark))
                ? -toneDeltaPair.delta
                : toneDeltaPair.delta
            let amRoleA = color.name == roleA.name
            let selfRole = amRoleA ? roleA : roleB
            let referenceRole = amRoleA ? roleB : roleA
            var selfTone = selfRole.tone(scheme)
            let referenceTone = referenceRole.getTone(scheme)
            let relativeDelta = absoluteDelta * (amRoleA ? 1 : -1)
            switch constraint {
            case .EXACT:
                selfTone = (referenceTone + relativeDelta).coerceIn(0.0, 100.0)
            case .NEARER:
                if relativeDelta > 0 {
                    selfTone = selfTone.coerceIn(referenceTone, referenceTone + relativeDelta).coerceIn(0.0, 100.0)
                } else {
                    selfTone = selfTone.coerceIn(referenceTone + relativeDelta, referenceTone).coerceIn(0.0, 100.0)
                }
            case .FARTHER:
                if relativeDelta > 0 {
                    selfTone = selfTone.coerceIn(referenceTone + relativeDelta, 100.0)
                } else {
                    selfTone = selfTone.coerceIn(0.0, referenceTone + relativeDelta)
                }
            }
            if let background = color.background?(scheme), let contrastCurve = color.contrastCurve?(scheme) {
                let bgTone = background.getTone(scheme)
                let selfContrast = contrastCurve.get(scheme.contrastLevel)
                if !(Contrast.ratioOfTones(bgTone, selfTone) >= selfContrast && scheme.contrastLevel >= 0) {
                    selfTone = DynamicColor.foregroundTone(bgTone, selfContrast)
                }
            }
            if color.isBackground && !color.name.hasSuffix("_fixed_dim") {
                selfTone = selfTone >= 57 ? selfTone.coerceIn(65.0, 100.0) : selfTone.coerceIn(0.0, 49.0)
            }
            return selfTone
        } else {
            var answer = color.tone(scheme)
            guard let background = color.background?(scheme),
                  let contrastCurve = color.contrastCurve?(scheme)
            else {
                return answer // No adjustment for colors with no background.
            }
            let bgTone = background.getTone(scheme)
            let desiredRatio = contrastCurve.get(scheme.contrastLevel)
            if !(Contrast.ratioOfTones(bgTone, answer) >= desiredRatio && scheme.contrastLevel >= 0) {
                answer = DynamicColor.foregroundTone(bgTone, desiredRatio)
            }
            if color.isBackground && !color.name.hasSuffix("_fixed_dim") {
                answer = answer >= 57 ? answer.coerceIn(65.0, 100.0) : answer.coerceIn(0.0, 49.0)
            }
            guard let secondBackground = color.secondBackground?(scheme) else {
                return answer
            }
            let bgTone1 = background.getTone(scheme)
            let bgTone2 = secondBackground.getTone(scheme)
            let upper = max(bgTone1, bgTone2)
            let lower = min(bgTone1, bgTone2)
            if Contrast.ratioOfTones(upper, answer) >= desiredRatio
                && Contrast.ratioOfTones(lower, answer) >= desiredRatio {
                return answer
            }
            let lightOption = Contrast.lighter(upper, desiredRatio)
            let darkOption = Contrast.darker(lower, desiredRatio)
            var availables: [Double] = []
            if let lightOption { availables.append(lightOption) }
            if let darkOption { availables.append(darkOption) }
            let prefersLight = DynamicColor.tonePrefersLightForeground(bgTone1)
                || DynamicColor.tonePrefersLightForeground(bgTone2)
            if prefersLight {
                return lightOption ?? 100.0
            }
            if availables.count == 1 {
                return availables[0]
            }
            return darkOption ?? 0.0
        }
    }

    // MARK: Scheme Palettes

    func getPrimaryPalette(
        _ variant: Variant, _ sourceColorHct: Hct, _ isDark: Bool,
        _ platform: DynamicScheme.Platform, _ contrastLevel: Double
    ) -> TonalPalette {
        switch variant {
        case .NEUTRAL:
            let chroma: Double = platform == .PHONE
                ? (Hct.isBlue(sourceColorHct.hue) ? 12.0 : 8.0)
                : (Hct.isBlue(sourceColorHct.hue) ? 16.0 : 12.0)
            return TonalPalette.fromHueAndChroma(sourceColorHct.hue, chroma)
        case .TONAL_SPOT:
            return TonalPalette.fromHueAndChroma(sourceColorHct.hue, (platform == .PHONE && isDark) ? 26.0 : 32.0)
        case .EXPRESSIVE:
            return TonalPalette.fromHueAndChroma(
                sourceColorHct.hue, platform == .PHONE ? (isDark ? 36.0 : 48.0) : 40.0)
        case .VIBRANT:
            return TonalPalette.fromHueAndChroma(sourceColorHct.hue, platform == .PHONE ? 74.0 : 56.0)
        default:
            preconditionFailure("\(variant) falls back to ColorSpec2021, which is not ported.")
        }
    }

    func getSecondaryPalette(
        _ variant: Variant, _ sourceColorHct: Hct, _ isDark: Bool,
        _ platform: DynamicScheme.Platform, _ contrastLevel: Double
    ) -> TonalPalette {
        switch variant {
        case .NEUTRAL:
            let chroma: Double = platform == .PHONE
                ? (Hct.isBlue(sourceColorHct.hue) ? 6.0 : 4.0)
                : (Hct.isBlue(sourceColorHct.hue) ? 10.0 : 6.0)
            return TonalPalette.fromHueAndChroma(sourceColorHct.hue, chroma)
        case .TONAL_SPOT:
            return TonalPalette.fromHueAndChroma(sourceColorHct.hue, 16.0)
        case .EXPRESSIVE:
            return TonalPalette.fromHueAndChroma(
                DynamicScheme.getRotatedHue(
                    sourceColorHct,
                    [0.0, 105.0, 140.0, 204.0, 253.0, 278.0, 300.0, 333.0, 360.0],
                    [-160.0, 155.0, -100.0, 96.0, -96.0, -156.0, -165.0, -160.0]
                ),
                platform == .PHONE ? (isDark ? 16.0 : 24.0) : 24.0
            )
        case .VIBRANT:
            return TonalPalette.fromHueAndChroma(
                DynamicScheme.getRotatedHue(
                    sourceColorHct,
                    [0.0, 38.0, 105.0, 140.0, 333.0, 360.0],
                    [-14.0, 10.0, -14.0, 10.0, -14.0]
                ),
                platform == .PHONE ? 56.0 : 36.0
            )
        default:
            preconditionFailure("\(variant) falls back to ColorSpec2021, which is not ported.")
        }
    }

    func getTertiaryPalette(
        _ variant: Variant, _ sourceColorHct: Hct, _ isDark: Bool,
        _ platform: DynamicScheme.Platform, _ contrastLevel: Double
    ) -> TonalPalette {
        switch variant {
        case .NEUTRAL:
            return TonalPalette.fromHueAndChroma(
                DynamicScheme.getRotatedHue(
                    sourceColorHct,
                    [0.0, 38.0, 105.0, 161.0, 204.0, 278.0, 333.0, 360.0],
                    [-32.0, 26.0, 10.0, -39.0, 24.0, -15.0, -32.0]
                ),
                platform == .PHONE ? 20.0 : 36.0
            )
        case .TONAL_SPOT:
            return TonalPalette.fromHueAndChroma(
                DynamicScheme.getRotatedHue(
                    sourceColorHct,
                    [0.0, 20.0, 71.0, 161.0, 333.0, 360.0],
                    [-40.0, 48.0, -32.0, 40.0, -32.0]
                ),
                platform == .PHONE ? 28.0 : 32.0
            )
        case .EXPRESSIVE:
            return TonalPalette.fromHueAndChroma(
                DynamicScheme.getRotatedHue(
                    sourceColorHct,
                    [0.0, 105.0, 140.0, 204.0, 253.0, 278.0, 300.0, 333.0, 360.0],
                    [-165.0, 160.0, -105.0, 101.0, -101.0, -160.0, -170.0, -165.0]
                ),
                48.0
            )
        case .VIBRANT:
            return TonalPalette.fromHueAndChroma(
                DynamicScheme.getRotatedHue(
                    sourceColorHct,
                    [0.0, 38.0, 71.0, 105.0, 140.0, 161.0, 253.0, 333.0, 360.0],
                    [-72.0, 35.0, 24.0, -24.0, 62.0, 50.0, 62.0, -72.0]
                ),
                56.0
            )
        default:
            preconditionFailure("\(variant) falls back to ColorSpec2021, which is not ported.")
        }
    }

    func getNeutralPalette(
        _ variant: Variant, _ sourceColorHct: Hct, _ isDark: Bool,
        _ platform: DynamicScheme.Platform, _ contrastLevel: Double
    ) -> TonalPalette {
        switch variant {
        case .NEUTRAL:
            return TonalPalette.fromHueAndChroma(sourceColorHct.hue, platform == .PHONE ? 1.4 : 6.0)
        case .TONAL_SPOT:
            return TonalPalette.fromHueAndChroma(sourceColorHct.hue, platform == .PHONE ? 5.0 : 10.0)
        case .EXPRESSIVE:
            return TonalPalette.fromHueAndChroma(
                getExpressiveNeutralHue(sourceColorHct),
                getExpressiveNeutralChroma(sourceColorHct, isDark, platform)
            )
        case .VIBRANT:
            return TonalPalette.fromHueAndChroma(
                getVibrantNeutralHue(sourceColorHct),
                getVibrantNeutralChroma(sourceColorHct, platform)
            )
        default:
            preconditionFailure("\(variant) falls back to ColorSpec2021, which is not ported.")
        }
    }

    func getNeutralVariantPalette(
        _ variant: Variant, _ sourceColorHct: Hct, _ isDark: Bool,
        _ platform: DynamicScheme.Platform, _ contrastLevel: Double
    ) -> TonalPalette {
        switch variant {
        case .NEUTRAL:
            return TonalPalette.fromHueAndChroma(sourceColorHct.hue, (platform == .PHONE ? 1.4 : 6.0) * 2.2)
        case .TONAL_SPOT:
            return TonalPalette.fromHueAndChroma(sourceColorHct.hue, (platform == .PHONE ? 5.0 : 10.0) * 1.7)
        case .EXPRESSIVE:
            let expressiveNeutralHue = getExpressiveNeutralHue(sourceColorHct)
            let expressiveNeutralChroma = getExpressiveNeutralChroma(sourceColorHct, isDark, platform)
            return TonalPalette.fromHueAndChroma(
                expressiveNeutralHue,
                expressiveNeutralChroma * ((expressiveNeutralHue >= 105 && expressiveNeutralHue < 125) ? 1.6 : 2.3)
            )
        case .VIBRANT:
            let vibrantNeutralHue = getVibrantNeutralHue(sourceColorHct)
            let vibrantNeutralChroma = getVibrantNeutralChroma(sourceColorHct, platform)
            return TonalPalette.fromHueAndChroma(vibrantNeutralHue, vibrantNeutralChroma * 1.29)
        default:
            preconditionFailure("\(variant) falls back to ColorSpec2021, which is not ported.")
        }
    }

    func getErrorPalette(
        _ variant: Variant, _ sourceColorHct: Hct, _ isDark: Bool,
        _ platform: DynamicScheme.Platform, _ contrastLevel: Double
    ) -> TonalPalette {
        let errorHue = DynamicScheme.getPiecewiseValue(
            sourceColorHct,
            [0.0, 3.0, 13.0, 23.0, 33.0, 43.0, 153.0, 273.0, 360.0],
            [12.0, 22.0, 32.0, 12.0, 22.0, 32.0, 22.0, 12.0]
        )
        switch variant {
        case .NEUTRAL: return TonalPalette.fromHueAndChroma(errorHue, platform == .PHONE ? 50.0 : 40.0)
        case .TONAL_SPOT: return TonalPalette.fromHueAndChroma(errorHue, platform == .PHONE ? 60.0 : 48.0)
        case .EXPRESSIVE: return TonalPalette.fromHueAndChroma(errorHue, platform == .PHONE ? 64.0 : 48.0)
        case .VIBRANT: return TonalPalette.fromHueAndChroma(errorHue, platform == .PHONE ? 80.0 : 60.0)
        default:
            // ColorSpec2021.getErrorPalette
            return TonalPalette.fromHueAndChroma(25.0, 84.0)
        }
    }

    private func getExpressiveNeutralHue(_ sourceColorHct: Hct) -> Double {
        DynamicScheme.getRotatedHue(
            sourceColorHct,
            [0.0, 71.0, 124.0, 253.0, 278.0, 300.0, 360.0],
            [10.0, 0.0, 10.0, 0.0, 10.0, 0.0]
        )
    }

    private func getExpressiveNeutralChroma(
        _ sourceColorHct: Hct, _ isDark: Bool, _ platform: DynamicScheme.Platform
    ) -> Double {
        let neutralHue = getExpressiveNeutralHue(sourceColorHct)
        if platform == .PHONE {
            if isDark { return Hct.isYellow(neutralHue) ? 6.0 : 14.0 }
            return 18.0
        }
        return 12.0
    }

    private func getVibrantNeutralHue(_ sourceColorHct: Hct) -> Double {
        DynamicScheme.getRotatedHue(
            sourceColorHct,
            [0.0, 38.0, 105.0, 140.0, 333.0, 360.0],
            [-14.0, 10.0, -14.0, 10.0, -14.0]
        )
    }

    private func getVibrantNeutralChroma(_ sourceColorHct: Hct, _ platform: DynamicScheme.Platform) -> Double {
        let neutralHue = getVibrantNeutralHue(sourceColorHct)
        if platform == .PHONE { return 28.0 }
        return Hct.isBlue(neutralHue) ? 28.0 : 20.0
    }

    fileprivate func tMaxC(
        _ palette: TonalPalette,
        _ lowerBound: Double = 0.0,
        _ upperBound: Double = 100.0,
        _ chromaMultiplier: Double = 1.0
    ) -> Double {
        let answer = findBestToneForChroma(palette.hue, palette.chroma * chromaMultiplier, 100.0, true)
        return answer.coerceIn(lowerBound, upperBound)
    }

    fileprivate func tMinC(
        _ palette: TonalPalette,
        _ lowerBound: Double = 0.0,
        _ upperBound: Double = 100.0
    ) -> Double {
        let answer = findBestToneForChroma(palette.hue, palette.chroma, 0.0, false)
        return answer.coerceIn(lowerBound, upperBound)
    }

    private func findBestToneForChroma(
        _ hue: Double, _ chroma: Double, _ tone: Double, _ byDecreasingTone: Bool
    ) -> Double {
        var tone = tone
        var answer = tone
        var bestCandidate = Hct.from(hue, chroma, answer)
        while bestCandidate.chroma < chroma {
            if tone < 0 || tone > 100 {
                break
            }
            tone += byDecreasingTone ? -1.0 : 1.0
            let newCandidate = Hct.from(hue, chroma, tone)
            if bestCandidate.chroma < newCandidate.chroma {
                bestCandidate = newCandidate
                answer = tone
            }
        }
        return answer
    }

    fileprivate func getContrastCurve(_ defaultContrast: Double) -> ContrastCurve {
        switch defaultContrast {
        case 1.5: return ContrastCurve(1.5, 1.5, 3.0, 5.5)
        case 3.0: return ContrastCurve(3.0, 3.0, 4.5, 7.0)
        case 4.5: return ContrastCurve(4.5, 4.5, 7.0, 11.0)
        case 6.0: return ContrastCurve(6.0, 6.0, 7.0, 11.0)
        case 7.0: return ContrastCurve(7.0, 7.0, 11.0, 21.0)
        case 9.0: return ContrastCurve(9.0, 9.0, 11.0, 21.0)
        case 11.0: return ContrastCurve(11.0, 11.0, 21.0, 21.0)
        case 21.0: return ContrastCurve(21.0, 21.0, 21.0, 21.0)
        default: return ContrastCurve(defaultContrast, defaultContrast, 7.0, 21.0)
        }
    }
}
