import SwiftUI
import UIKit

/// The color roles Android's Now Playing reads, derived from the cover.
///
/// Android builds an MCU `SchemeExpressive` (SPEC_2025) from the cover seed
/// (CoverSeedExtractor → ExpressiveColorSchemeFactory). MCU isn't ported yet,
/// so this approximates the same roles in HSL from the seed hue: primary on
/// the seed hue, secondary and tertiary on MCU SchemeExpressive's hue
/// rotation table (applied to the HSL hue, which is only close to HCT hue),
/// surfaces as near-white tints of the seed. Swap for MCU when the KMP/MCU
/// port lands.
struct CoverScheme {
    var primary: Color
    var onPrimary: Color
    var secondary: Color
    var onSecondary: Color
    var tertiary: Color
    var onTertiary: Color
    var tertiaryContainer: Color
    var onTertiaryContainer: Color
    var background: Color
    var surfaceContainerLow: Color
    var surfaceContainer: Color
    var surfaceContainerHighest: Color
    var surfaceVariant: Color
    var onSurface: Color
    var onSurfaceVariant: Color

    init(seed: Color, dark: Bool) {
        var h: CGFloat = 0, s: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        UIColor(seed).getHue(&h, saturation: &s, brightness: &b, alpha: &a)
        // MCU SchemeExpressive: hue breakpoints and the rotations applied there.
        let hues: [CGFloat] = [0, 21, 51, 121, 151, 191, 271, 321, 360]
        let secondaryRot: [CGFloat] = [45, 95, 45, 20, 45, 90, 45, 45, 45]
        let tertiaryRot: [CGFloat] = [120, 120, 20, 45, 20, 15, 20, 120, 120]
        func rotation(_ table: [CGFloat]) -> CGFloat {
            let deg = h * 360
            for i in 0..<(hues.count - 1) where deg >= hues[i] && deg < hues[i + 1] { return table[i] }
            return table[0]
        }
        let sec = rotation(secondaryRot)
        let ter = rotation(tertiaryRot)
        func hsl(_ dh: CGFloat, _ sat: CGFloat, _ l: CGFloat) -> Color {
            var hue = (h + dh / 360).truncatingRemainder(dividingBy: 1)
            if hue < 0 { hue += 1 }
            // HSL → HSB
            let v = l + sat * min(l, 1 - l)
            let sv = v == 0 ? 0 : 2 * (1 - l / v)
            return Color(hue: hue, saturation: sv, brightness: v)
        }
        if dark {
            primary = hsl(0, 0.70, 0.78); onPrimary = hsl(0, 0.60, 0.16)
            secondary = hsl(sec, 0.28, 0.72); onSecondary = hsl(sec, 0.40, 0.14)
            tertiary = hsl(ter, 0.60, 0.70); onTertiary = hsl(ter, 0.60, 0.14)
            tertiaryContainer = hsl(ter, 0.55, 0.30); onTertiaryContainer = hsl(ter, 0.70, 0.88)
            background = hsl(0, 0.25, 0.07); surfaceContainerLow = hsl(0, 0.24, 0.10); surfaceContainer = hsl(0, 0.22, 0.12)
            surfaceContainerHighest = hsl(0, 0.18, 0.22); surfaceVariant = hsl(0, 0.16, 0.24)
            onSurface = hsl(0, 0.40, 0.90); onSurfaceVariant = hsl(0, 0.18, 0.75)
        } else {
            primary = hsl(0, 0.50, 0.40); onPrimary = .white
            secondary = hsl(sec, 0.30, 0.34); onSecondary = .white
            tertiary = hsl(ter, 0.75, 0.26); onTertiary = .white
            tertiaryContainer = hsl(ter, 0.85, 0.72); onTertiaryContainer = hsl(ter, 0.70, 0.14)
            background = hsl(0, 0.70, 0.975); surfaceContainerLow = hsl(0, 0.62, 0.955); surfaceContainer = hsl(0, 0.55, 0.92)
            surfaceContainerHighest = hsl(0, 0.35, 0.86); surfaceVariant = hsl(0, 0.40, 0.88)
            onSurface = hsl(0, 0.50, 0.17); onSurfaceVariant = hsl(0, 0.18, 0.36)
        }
    }
}
