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

/// Cover art. Mock covers: a three-color mesh with a soft Yoin arm shape;
/// server covers (`palette.cover`): the real image.
struct CoverArt: View {
    var palette: CoverPalette
    var cornerRadius: CGFloat = 6
    var seed: Int = 0

    var body: some View {
        if let cover = palette.cover {
            // A server cover: the real image (placeholder color until it loads).
            RemoteCover(ref: cover, placeholder: palette.b.mix(with: palette.a, by: 0.25, in: .perceptual))
                .clipShape(.rect(cornerRadius: cornerRadius))
        } else {
            mesh
        }
    }

    private var mesh: some View {
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

/// Android ExpressiveBackdropPalette: base and accent tones from the cover.
struct BackdropColors {
    var base: Color
    var accent: Color

    init(_ palette: CoverPalette) {
        func hsl(_ c: Color) -> (CGFloat, CGFloat, CGFloat) {
            let (r, g, b, _) = rgbaComponents(c)
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

/// Home cover on iOS. Android puts covers on MaterialShapes backdrops (Bun,
/// Ghostish); iOS music apps show the bare cover, so here it is a square with
/// a continuous corner that scales with size (≈ 11 pt at 170, 6 pt at 56), a
/// hairline inner stroke so light covers don't melt into the page, and a soft
/// shadow tinted with the cover. Artists stay circles.
struct EntityBackdrop: View {
    enum Entity { case album, song, playlist, artist }
    var entity: Entity
    var palette: CoverPalette
    var seed: Int
    var size: CGFloat
    var stroke: Color = .black

    static func corner(_ size: CGFloat) -> CGFloat { 3 + size * 0.045 }

    var body: some View {
        let r = entity == .artist ? size / 2 : Self.corner(size)
        CoverArt(palette: palette, cornerRadius: 0, seed: seed)
            .frame(width: size, height: size)
            .clipShape(.rect(cornerRadius: r, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: r, style: .continuous)
                    .strokeBorder(stroke.opacity(0.08), lineWidth: 0.5)
            }
            .shadow(color: palette.a.opacity(0.22), radius: 8, y: 6)
    }
}
