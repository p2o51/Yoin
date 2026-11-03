import UIKit
import SwiftUI

/// MD3 Expressive "cookie": a scalloped circle (Cookie12Sided on Android).
struct CookieShape: Shape {
    var lobes: Int = 12
    var depth: CGFloat = 0.075

    func path(in rect: CGRect) -> Path {
        let center = CGPoint(x: rect.midX, y: rect.midY)
        let radius = min(rect.width, rect.height) / 2
        var path = Path()
        let steps = 360
        for i in 0...steps {
            let t = Double(i) / Double(steps) * 2 * .pi
            let r = radius * (1 - depth / 2 + depth / 2 * CGFloat(cos(Double(lobes) * t)))
            let p = CGPoint(x: center.x + r * CGFloat(cos(t - .pi / 2)), y: center.y + r * CGFloat(sin(t - .pi / 2)))
            if i == 0 { path.move(to: p) } else { path.addLine(to: p) }
        }
        path.closeSubpath()
        return path
    }
}

/// Mock cover art: three-color mesh with a soft Yoin arm shape.
struct CoverArt: View {
    var palette: CoverPalette
    var cornerRadius: CGFloat = 6
    var seed: Int = 0

    var body: some View {
        MeshGradient(
            width: 3, height: 3,
            points: [
                [0, 0], [0.5, 0], [1, 0],
                [0, 0.5], [seed.isMultiple(of: 2) ? 0.62 : 0.38, 0.45], [1, 0.5],
                [0, 1], [0.5, 1], [1, 1],
            ],
            colors: [
                palette.a, palette.a, palette.b,
                palette.c, palette.a, palette.b,
                palette.c, palette.c, palette.a,
            ]
        )
        .overlay {
            GeometryReader { geo in
                let s = geo.size.width
                Capsule()
                    .fill(palette.b.opacity(0.55))
                    .frame(width: s * 0.22, height: s * 0.62)
                    .rotationEffect(.degrees(seed.isMultiple(of: 2) ? 35 : -30))
                    .position(x: s * 0.68, y: s * 0.42)
                    .blur(radius: s * 0.02)
            }
        }
        .clipShape(.rect(cornerRadius: cornerRadius))
    }
}

extension Album {
    /// Stable per album (String.hashValue is randomized per launch).
    var seed: Int { id.rawId.unicodeScalars.reduce(0) { $0 + Int($1.value) } % 7 }
}

/// MaterialShapes.Bun (Android's album backdrop): a squared stack of two
/// lobes with a pinched waist. Drawn from MaterialShapes' Bun points
/// (waist at x 0.796, lobe edge 0.992 at y 0.631, mirrored), corners eased by
/// hand rather than RoundedPolygon smoothing.
struct BunShape: Shape {
    func path(in r: CGRect) -> Path {
        func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: r.minX + x * r.width, y: r.minY + y * r.height) }
        var path = Path()
        path.move(to: p(0.5, 0))
        path.addLine(to: p(0.78, 0))
        path.addCurve(to: p(0.99, 0.30), control1: p(0.97, 0), control2: p(1.0, 0.12))
        path.addCurve(to: p(0.796, 0.5), control1: p(0.985, 0.42), control2: p(0.86, 0.46))
        path.addCurve(to: p(0.99, 0.70), control1: p(0.86, 0.54), control2: p(0.985, 0.58))
        path.addCurve(to: p(0.78, 1), control1: p(1.0, 0.88), control2: p(0.97, 1))
        path.addLine(to: p(0.22, 1))
        path.addCurve(to: p(0.01, 0.70), control1: p(0.03, 1), control2: p(0, 0.88))
        path.addCurve(to: p(0.204, 0.5), control1: p(0.015, 0.58), control2: p(0.14, 0.54))
        path.addCurve(to: p(0.01, 0.30), control1: p(0.14, 0.46), control2: p(0.015, 0.42))
        path.addCurve(to: p(0.22, 0), control1: p(0, 0.12), control2: p(0.03, 0))
        path.closeSubpath()
        return path
    }
}

/// MaterialShapes.Ghostish (playlist backdrop), approximated: round head,
/// straight sides, a three-lobed hem.
struct GhostishShape: Shape {
    func path(in r: CGRect) -> Path {
        func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: r.minX + x * r.width, y: r.minY + y * r.height) }
        var path = Path()
        path.move(to: p(0, 0.5))
        path.addArc(center: p(0.5, 0.5), radius: r.width / 2, startAngle: .degrees(180), endAngle: .degrees(0), clockwise: false)
        path.addLine(to: p(1, 0.9))
        path.addQuadCurve(to: p(0.667, 0.9), control: p(0.833, 1.1))
        path.addQuadCurve(to: p(0.333, 0.9), control: p(0.5, 1.1))
        path.addQuadCurve(to: p(0, 0.9), control: p(0.167, 1.1))
        path.closeSubpath()
        return path
    }
}

/// Android ExpressiveBackdropPalette: base and accent tones from the cover.
struct BackdropColors {
    var base: Color
    var accent: Color

    init(_ palette: CoverPalette) {
        func hsl(_ c: Color) -> (CGFloat, CGFloat, CGFloat) {
            var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
            UIColor(c).getRed(&r, green: &g, blue: &b, alpha: &a)
            let mx = max(r, g, b), mn = min(r, g, b), l = (mx + mn) / 2
            guard mx != mn else { return (0, 0, l) }
            let d = mx - mn
            let s = l > 0.5 ? d / (2 - mx - mn) : d / (mx + mn)
            var h: CGFloat = mx == r ? (g - b) / d + (g < b ? 6 : 0) : mx == g ? (b - r) / d + 2 : (r - g) / d + 4
            h /= 6
            return (h, s, l)
        }
        func color(_ h: CGFloat, _ s: CGFloat, _ l: CGFloat) -> Color {
            let v = l + s * min(l, 1 - l)
            return Color(hue: h, saturation: v == 0 ? 0 : 2 * (1 - l / v), brightness: v)
        }
        let (h, s, l) = hsl(palette.a)
        // base: S × 0.82 ∈ [0.18, 0.78], L × 0.72 + 0.12 ∈ [0.24, 0.62]
        base = color(h, min(0.78, max(0.18, s * 0.82)), min(0.62, max(0.24, l * 0.72 + 0.12)))
        // accent: a lighter swatch near the seed hue, S × 1.08 ∈ [0.24, 0.92], L + 0.14 ∈ [0.36, 0.82]
        let (ah, as_, al) = hsl(palette.b)
        accent = color(ah, min(0.92, max(0.24, as_ * 1.08)), min(0.82, max(0.36, al + 0.14)))
    }
}

/// Android entity backdrop: the cover sits at 0.72 of a shape filled with the
/// backdrop base, bottom-end aligned (HomeWidgetGrid.kt:880-980).
struct EntityBackdrop: View {
    enum Entity { case album, song, playlist, artist }
    var entity: Entity
    var palette: CoverPalette
    var seed: Int
    var size: CGFloat

    var body: some View {
        let base = BackdropColors(palette).base
        ZStack(alignment: .bottomTrailing) {
            switch entity {
            case .album: BunShape().fill(base)
            case .song: Circle().fill(base)
            case .playlist: GhostishShape().fill(base)
            case .artist: Color.clear
            }
            if entity == .artist {
                CoverArt(palette: palette, cornerRadius: size, seed: seed)
            } else {
                CoverArt(palette: palette, cornerRadius: 4, seed: seed)
                    .frame(width: size * 0.72, height: size * 0.72)
            }
        }
        .frame(width: size, height: size)
    }
}
