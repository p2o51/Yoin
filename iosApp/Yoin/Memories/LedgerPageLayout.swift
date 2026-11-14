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

/// Keeping the long views lazy (a long-time user has hundreds of cards). The timeline is cut at its full-line
/// rows: a full line already starts every column level, so a masonry per stretch lays out exactly as one over
/// the whole page, and each stretch (a month) is built only when it scrolls near. The cards have no full lines,
/// so each card is dealt to the lane that is shortest by its estimated height and every lane is a lazy stack.
enum LedgerLazy {
    /// [items] cut before each full-line item: a month's header and its cards, a run of quiet months.
    static func segments<T>(_ items: [T], isFullLine: (T) -> Bool) -> [[T]] {
        var out: [[T]] = []
        for item in items {
            if isFullLine(item) || out.isEmpty { out.append([item]) } else { out[out.count - 1].append(item) }
        }
        return out
    }

    /// Each item's index in its lane: in order, to the lane shortest so far by [heights] (the first on a tie).
    static func lanes(_ heights: [CGFloat], count: Int, spacing: CGFloat) -> [[Int]] {
        let n = max(count, 1)
        var tops = Array(repeating: CGFloat(0), count: n)
        var out = Array(repeating: [Int](), count: n)
        for (i, h) in heights.enumerated() {
            let lane = tops.indices.min { tops[$0] < tops[$1] } ?? 0
            out[lane].append(i)
            tops[lane] += h + spacing
        }
        return out
    }

    /// Roughly how many lines [text] takes at [width] in a [size] pt face: Latin about half an em a
    /// character, CJK and the rest a whole em.
    static func lines(_ text: String?, width: CGFloat, size: CGFloat, max: Int) -> Int {
        guard let text, !text.isEmpty, width > 0 else { return 0 }
        var total = 0
        for paragraph in text.split(separator: "\n", omittingEmptySubsequences: false) {
            let ems = paragraph.unicodeScalars.reduce(CGFloat(0)) { $0 + ($1.isASCII ? 0.5 : 1) }
            total += Swift.max(1, Int((ems * size / width).rounded(.up)))
        }
        return Swift.min(total, max)
    }
}

extension LedgerItem {
    /// The Cards view's card for this item, roughly (`AllMemoriesView` albumCard and songCard, Android's
    /// AlbumGridCard and SongGridCard), at a lane [width]: enough to deal cards to lanes, not to place them.
    /// Both: 10 inside, the foot (a 20 pt score and the day) 30.
    func estimatedCardHeight(width: CGFloat) -> CGFloat {
        let inner = Swift.max(width - 20, 0)
        switch self {
        case .album(let album, let title, _, _, _, _):
            // The cover whole, 6, the title in the serif (two lines at most), the name, the artist.
            let titleLines = LedgerLazy.lines(title, width: inner, size: 16, max: 2)
            return 20 + inner + 6 + CGFloat(titleLines) * 24 + 20 + (album.artist.isEmpty ? 0 : 16) + 30
        case .song(_, _, _, _, _, let note, _, _):
            // The 44 cover row, then the newest note (five lines at most) 6 under it.
            let lines = LedgerLazy.lines(note?.text, width: inner, size: 14, max: 5)
            return 20 + 44 + (lines > 0 ? 6 + CGFloat(lines) * 20 : 0) + 30
        }
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
