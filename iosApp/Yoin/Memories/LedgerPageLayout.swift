import SwiftUI

/*
 * How All Memories lays out at a width (Android MemoryLedgerScreen.kt `ledgerLayoutFor`, #202, owner
 * 2026-11-12: a large screen is used, not left as margins): the stats run large above a wider, centred grid,
 * and the timeline and the cards run in as many columns as fit, so they fall into a masonry. A short window
 * (a landscape phone) is the phone layout whatever its width: height is judged first.
 */
struct LedgerPageLayout: Equatable {
    /// Larger stats over a wider, centred grid, and larger month headers.
    var wide: Bool
    /// The timeline's columns: 1 on a phone (everything full width), more as a masonry.
    var columns: Int
    /// The cards' lanes.
    var lanes: Int
    /// The page's widest, its 16 pt sides included.
    var maxWidth: CGFloat
    /// Between columns.
    var gutter: CGFloat

    /// The grid's cap: centred when wide, at the leading edge otherwise.
    var gridMaxWidth: CGFloat { wide ? 760 : 640 }

    static let phone = LedgerPageLayout(wide: false, columns: 1, lanes: 2, maxWidth: 720, gutter: 12)

    static func at(width: CGFloat, compactHeight: Bool) -> LedgerPageLayout {
        if compactHeight { return phone }
        switch width {
        case 1100...: return LedgerPageLayout(wide: true, columns: 3, lanes: 3, maxWidth: 1240, gutter: 28)
        case 840...: return LedgerPageLayout(wide: true, columns: 2, lanes: 2, maxWidth: 1040, gutter: 28)
        case 600...: return LedgerPageLayout(wide: false, columns: 2, lanes: 2, maxWidth: 840, gutter: 20)
        default: return phone
        }
    }
}

/// A full-width row inside a `LedgerMasonry` (a month's header, a run of quiet months).
struct LedgerFullLine: LayoutValueKey {
    static let defaultValue = false
}

/// A staggered grid (Android LazyVerticalStaggeredGrid): each item goes to the shortest column, in order; a
/// full-line item starts below the tallest and spans them all.
struct LedgerMasonry: Layout {
    var columns: Int
    var spacing: CGFloat
    var rowSpacing: CGFloat = 0

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? 0
        let frames = Self.arrange(width: width, columns: columns, spacing: spacing, rowSpacing: rowSpacing,
                                  items: subviews.map { s in (s[LedgerFullLine.self], { w in s.sizeThatFits(.init(width: w, height: nil)).height }) })
        return CGSize(width: width, height: frames.height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let frames = Self.arrange(width: bounds.width, columns: columns, spacing: spacing, rowSpacing: rowSpacing,
                                  items: subviews.map { s in (s[LedgerFullLine.self], { w in s.sizeThatFits(.init(width: w, height: nil)).height }) })
        for (s, f) in zip(subviews, frames.rects) {
            s.place(at: CGPoint(x: bounds.minX + f.minX, y: bounds.minY + f.minY), proposal: .init(width: f.width, height: nil))
        }
    }

    /// Where each item goes, given whether it is full-line and its height at a width.
    static func arrange(width: CGFloat, columns: Int, spacing: CGFloat, rowSpacing: CGFloat,
                        items: [(fullLine: Bool, height: (CGFloat) -> CGFloat)]) -> (rects: [CGRect], height: CGFloat) {
        let n = max(columns, 1)
        let lane = max((width - spacing * CGFloat(n - 1)) / CGFloat(n), 0)
        var heights = Array(repeating: CGFloat(0), count: n)
        var rects: [CGRect] = []
        for item in items {
            if item.fullLine || n == 1 {
                let y = heights.max() ?? 0
                let h = item.height(width)
                rects.append(CGRect(x: 0, y: y, width: width, height: h))
                heights = Array(repeating: y + h + rowSpacing, count: n)
            } else {
                let i = heights.indices.min { heights[$0] < heights[$1] } ?? 0
                let h = item.height(lane)
                rects.append(CGRect(x: CGFloat(i) * (lane + spacing), y: heights[i], width: lane, height: h))
                heights[i] += h + rowSpacing
            }
        }
        let bottom = heights.max() ?? 0
        return (rects, items.isEmpty ? 0 : max(bottom - rowSpacing, 0))
    }
}

/// A dashed rule, as About's receipt draws it (Android `DashedRule`: 1 pt, 4 on, 3 off).
struct LedgerDashedRule: View {
    var color: Color

    var body: some View {
        Canvas { ctx, size in
            var p = Path()
            p.move(to: CGPoint(x: 0, y: size.height / 2))
            p.addLine(to: CGPoint(x: size.width, y: size.height / 2))
            ctx.stroke(p, with: .color(color), style: StrokeStyle(lineWidth: 1, dash: [4, 3]))
        }
        .frame(height: 1)
        .accessibilityHidden(true)
    }
}

extension LedgerTimelineRow {
    /// Spans every column of the masonry: a month's header, a run of quiet months.
    var isFullLine: Bool {
        switch self {
        case .month, .quiet: true
        case .album, .note, .scores: false
        }
    }
}
