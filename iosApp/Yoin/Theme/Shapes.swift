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
