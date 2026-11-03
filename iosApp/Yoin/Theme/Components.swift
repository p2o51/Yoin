import SwiftUI

struct SectionTitle: View {
    var text: String
    init(_ text: String) { self.text = text }
    var body: some View {
        Text(text)
            .font(.gsf(22, .medium))
            .foregroundStyle(Yoin.ink)
    }
}

/// Spring press feedback for solid content.
struct PressScale: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.96 : 1)
            .animation(Yoin.spatialFast, value: configuration.isPressed)
    }
}

/// Scalloped cookie rating badge; optional rings = one groove per track.
struct CookieBadge: View {
    var score: Double
    var palette: CoverPalette
    var size: CGFloat = 84
    var rings: Int = 0
    var ratedRings: Int = 0
    /// Album rating (wght 690) vs. track average (640), as GrooveType.
    var albumRating = true

    var body: some View {
        ZStack {
            CookieShape()
                .fill(palette.tint)
            if rings > 0 {
                ForEach(0..<rings, id: \.self) { i in
                    let f = 0.42 + 0.46 * CGFloat(i) / CGFloat(max(rings - 1, 1))
                    Circle()
                        .stroke(.white.opacity(i < ratedRings ? 0.28 : 0.12),
                                style: StrokeStyle(lineWidth: 0.8, dash: i < ratedRings ? [] : [2, 3]))
                        .frame(width: size * f, height: size * f)
                }
            }
            let label = String(format: "%.1f", score)
            Text(label)
                .font(YoinFont.score(label, size: size * 0.32, albumRating: albumRating))
                .foregroundStyle(.white)
        }
        .frame(width: size, height: size)
        .accessibilityLabel(String(format: "Rating %.1f", score))
    }
}
