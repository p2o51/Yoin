import SwiftUI

/// The hero's track titles as one paragraph (Android buildAlbumTrackTitles): headlineMedium
/// joined by a space, even = onSurface, odd = onSurfaceVariant, short titles (≤ 16 units,
/// CJK = 2) glued so they wrap as one unit. Each title is its own link: a tap plays from that
/// song, as a row of the list does, and the pressed title is underlined (AlbumTitleLinkStyles).
struct FlowingTrackTitles: View {
    var titles: [String]
    var primary: Color
    var variant: Color
    /// Nil while the titles are only a picture (Android: `interactive` false).
    var onPick: ((Int) -> Void)?

    @State private var hits = TitleHits()
    @State private var pressed: Int?

    var body: some View {
        text
            .lineSpacing(YoinType.lineSpacing(Tokens.type.headlineMedium))
            .textRenderer(FlowingTitlesRenderer(hits: hits, pressed: pressed, primary: primary, variant: variant))
            .gesture(SpatialTapGesture().onEnded { tap($0.location) }, isEnabled: onPick != nil)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(titles.joined(separator: ", "))
            .accessibilityActions {
                if let onPick {
                    ForEach(Array(titles.enumerated()), id: \.offset) { i, t in
                        Button("Play \(t)") { onPick(i) }
                    }
                }
            }
    }

    private var text: Text {
        var out = Text(verbatim: "")
        for (i, t) in titles.enumerated() {
            let units = t.unicodeScalars.reduce(0) { $0 + ($1.value > 0x2E80 ? 2 : 1) }
            let title = units <= 16 ? t.replacingOccurrences(of: " ", with: "\u{00A0}") : t
            let piece = Text(verbatim: title)
                .font(.gsf(28))
                .foregroundStyle(i.isMultiple(of: 2) ? primary : variant)
                .customAttribute(TrackTitleIndex(index: i))
            out = i == 0 ? piece : Text("\(out)\(Text(verbatim: " ").font(.gsf(28)))\(piece)")
        }
        return out
    }

    private func tap(_ location: CGPoint) {
        guard let onPick, let i = hits.index(at: location) else { return }
        pressed = i
        onPick(i)
        Task {
            try? await Task.sleep(for: .milliseconds(180))
            if pressed == i { pressed = nil }
        }
    }
}

/// Which title a run belongs to.
private struct TrackTitleIndex: TextAttribute {
    var index: Int
}

/// Where each title was last drawn, in the text's own space (the renderer writes it).
private final class TitleHits {
    var rects: [(index: Int, rect: CGRect)] = []

    func index(at p: CGPoint) -> Int? {
        // A touch between lines or beside a word still lands on the nearest title on its line.
        if let hit = rects.first(where: { $0.rect.insetBy(dx: -4, dy: -6).contains(p) }) { return hit.index }
        return nil
    }
}

private struct FlowingTitlesRenderer: TextRenderer {
    let hits: TitleHits
    var pressed: Int?
    var primary: Color
    var variant: Color

    func draw(layout: Text.Layout, in ctx: inout GraphicsContext) {
        var rects: [(index: Int, rect: CGRect)] = []
        for line in layout {
            for run in line {
                ctx.draw(run)
                guard let i = run[TrackTitleIndex.self]?.index else { continue }
                let r = run.typographicBounds.rect
                rects.append((i, r))
                if i == pressed {
                    let y = run.typographicBounds.origin.y + run.typographicBounds.descent * 0.5
                    ctx.fill(Path(CGRect(x: r.minX, y: y, width: r.width, height: 2)),
                             with: .color(i.isMultiple(of: 2) ? primary : variant))
                }
            }
        }
        hits.rects = rects
    }
}
