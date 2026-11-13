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

/// Spring press feedback for solid content; under an iPad pointer (and on a Mac) the card lifts toward it.
struct PressScale: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.96 : 1)
            .animation(Yoin.spatialFast, value: configuration.isPressed)
            .hoverEffect(.lift)
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

/// Format tags as small tonal blocks ("FLAC 24/96", "5.1"), one block each, no separators (Android
/// AudioFormatTagRow: labelSmall with tabular figures, 6 × 1 inside a 6-corner block, 4 apart, in
/// secondaryContainer). VoiceOver reads the row as one "Audio format FLAC 16/44.1 5.1".
struct FormatTagRow: View {
    var tags: [String]
    var container: Color
    var content: Color

    /// A tag's block: labelSmall's 16 line and 1 above and below.
    static let height: CGFloat = Tokens.type.labelSmall.lineHeight.cg + 2

    var body: some View {
        HStack(spacing: 4) {
            ForEach(tags, id: \.self) { tag in
                Text(tag)
                    .font(.gsf(Tokens.type.labelSmall.size.cg, Font.Weight(gsf: Tokens.type.labelSmall.weight)).monospacedDigit())
                    .tracking(Tokens.type.labelSmall.letterSpacing.cg)
                    .lineLimit(1)
                    .fixedSize()
                    .foregroundStyle(content)
                    .padding(.horizontal, 6)
                    .frame(height: Self.height)
                    .background(container, in: .rect(cornerRadius: 6, style: .continuous))
                    .transition(.opacity)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("Audio format \(tags.joined(separator: " "))"))
    }
}

/// The bar's accent-style selection: a subtle pill inside the glass (grey on
/// light glass, light on dark), under accent-colored text.
struct SelectionPill: View {
    var cornerRadius: CGFloat
    @Environment(\.colorScheme) private var colorScheme
    var body: some View {
        RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
            .fill(colorScheme == .dark ? Color.white.opacity(0.16) : Color.black.opacity(0.07))
            .padding(3)
    }
}

extension View {
    /// A horizontal shelf's ends fade instead of cutting hard where its container stops (a split's column
    /// edge, the window edge): Android's "no mid-page truncation" (full bleed, contentPadding, edge fade).
    func shelfEdgeFade(_ width: CGFloat = 16) -> some View {
        mask {
            HStack(spacing: 0) {
                LinearGradient(colors: [.clear, .black], startPoint: .leading, endPoint: .trailing).frame(width: width)
                Rectangle()
                LinearGradient(colors: [.black, .clear], startPoint: .leading, endPoint: .trailing).frame(width: width)
            }
        }
    }
}

extension View {
    /// Lays the view (a page's scroll view) out at exactly the space it is offered and reports that width.
    /// A vertical scroll view takes its content's width across, so a page that sized its content from its own
    /// measured width held itself at a width it once had when its container narrowed (an iPad column
    /// opening beside it, a rotation); the offered width is the one input instead.
    func offeredWidth(_ action: @escaping (CGFloat) -> Void) -> some View {
        GeometryReader { g in
            self
                .frame(width: g.size.width, height: g.size.height)
                .onChange(of: g.size.width, initial: true) { _, w in action(w) }
        }
    }
}
