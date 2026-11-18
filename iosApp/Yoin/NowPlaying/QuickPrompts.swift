import SwiftUI

/// The quick questions offered wherever a song is asked about while its field is empty: Now Playing's Ask
/// field and a scrapbook track sheet's new entry (Android GeminiPrompts, owner-approved prototype v3,
/// 2026-11-02). A tap fills the field; the keyboard's Send asks it. Ones already asked about the song aren't
/// offered.
enum QuickPrompts {
    /// Android `cmp_quick_prompts`, in its order.
    static var all: [String] {
        [String(localized: "What does this song sample?"),
         String(localized: "Who sampled this song?"),
         String(localized: "What are the lyrics about?"),
         String(localized: "How was it written?"),
         String(localized: "What covers are there?"),
         String(localized: "How was it received?")]
    }

    /// Android `remainingQuickPrompts`: [prompts] less the ones in [asked], compared the way About rows are
    /// keyed (`SongAboutStore.normalize`).
    static func remaining(_ prompts: [String] = all, asked: [String]) -> [String] {
        let done = Set(asked.map(SongAboutStore.normalize))
        return prompts.filter { !done.contains(SongAboutStore.normalize($0)) }
    }

    /// What a song's rows already ask (Android `askedQuestions` and the scrapbook's asks): an Ask row's
    /// question as typed, the heading of the listener's own entry (as renamed). Removed rows don't count.
    static func asked(in entries: [SongAboutStore.Entry]) -> [String] {
        entries.compactMap { row in
            guard row.kind != SongAboutStore.Kind.canonical, !row.isBlank else { return nil }
            let words = row.kind == SongAboutStore.Kind.own ? row.titleText : row.promptText
            return words.flatMap { $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : $0 }
                ?? row.entryKey
        }
    }
}

/// Android QuickPrompts: the questions as a row of pills (labelLarge medium, 14 × 9 in, 8 apart) that
/// scrolls sideways; a tap hands one to [onPick]. [inset] is the row's start and end padding inside the
/// scroll, so the pills scroll out to its edges. With [fadeEdges] (a row that ends inside a card rather than
/// at the screen edge) each end fades while more pills lie beyond it, so no pill is cut off hard.
struct QuickPromptsRow: View {
    var prompts: [String]
    var container: Color
    var content: Color
    var inset: CGFloat = 0
    var fadeEdges = false
    var onPick: (String) -> Void

    /// How far each end fades (0 to 1), eased on the effects spring as more pills come to lie beyond it.
    @State private var leading: CGFloat = 0
    @State private var trailing: CGFloat = 0

    /// Android horizontalEdgeFadeOnScroll's width (a third of the row at most).
    private static let edge: CGFloat = 24

    var body: some View {
        ScrollView(.horizontal) {
            HStack(spacing: 8) {
                ForEach(prompts, id: \.self) { prompt in
                    Button { onPick(prompt) } label: {
                        Text(prompt)
                            .yoinRole(Tokens.type.labelLarge, weight: .medium)
                            .lineLimit(1)
                            .foregroundStyle(content)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 9)
                            .background(container, in: .capsule)
                            .contentShape(.capsule)
                    }
                    .buttonStyle(PressScale())
                }
            }
            .padding(.horizontal, inset)
        }
        .scrollIndicators(.hidden)
        .onScrollGeometryChange(for: [Bool].self) { g in
            let offset = g.contentOffset.x + g.contentInsets.leading
            return [offset > 1, offset + g.containerSize.width < g.contentSize.width - 1]
        } action: { _, beyond in
            guard fadeEdges else { return }
            let next: (CGFloat, CGFloat) = (beyond[0] ? 1 : 0, beyond[1] ? 1 : 0)
            guard next != (leading, trailing) else { return }
            withAnimation(Yoin.effects) { leading = next.0; trailing = next.1 }
        }
        .mask { if fadeEdges { edgeFade } else { Rectangle() } }
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Quick questions")
    }

    /// Android's edge: a smoothstep-eased ramp from clear to the row, laid over the row by how far it fades
    /// (no onset line, and the same geometry while it eases).
    private var edgeFade: some View {
        GeometryReader { g in
            let w = min(Self.edge, g.size.width / 3)
            HStack(spacing: 0) {
                ramp(.leading, strength: leading).frame(width: w)
                Rectangle()
                ramp(.trailing, strength: trailing).frame(width: w)
            }
        }
        .padding(.vertical, -8)
    }

    private func ramp(_ edge: HorizontalEdge, strength: CGFloat) -> some View {
        let stops = [0, 0.104, 0.352, 0.648, 0.896, 1].enumerated().map { i, t in
            Gradient.Stop(color: .black.opacity(t), location: CGFloat(i) / 5)
        }
        return ZStack {
            Rectangle().opacity(1 - strength)
            LinearGradient(stops: stops, startPoint: edge == .leading ? .leading : .trailing,
                           endPoint: edge == .leading ? .trailing : .leading)
                .opacity(strength)
        }
    }
}
