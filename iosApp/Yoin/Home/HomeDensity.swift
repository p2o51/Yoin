import CoreGraphics
import Foundation

/*
 * Home's density by width (Android ui/experience/FeedUnits.kt, ui/home/HomeFeedDensity.kt, HomeJbiTemplate.kt;
 * docs/adaptive-principles.md "Feed 密度"). The one input is the feed container's resting width: wider adds
 * cards and columns, never bigger cards. A phone (N ≤ 2) keeps its layout untouched.
 */
struct HomeDensity: Equatable, Sendable {
    /// The container: its width, and whether it is a Wide window (≥ 840 wide, ≥ 480 tall).
    var width: CGFloat
    var wide: Bool
    /// Page margin: Wide 32; otherwise 16, the content capped at 688 and centred.
    var margin: CGFloat
    /// Content width C.
    var content: CGFloat
    /// Feed units N (1–8).
    var units: Int
    /// Phone-sized cover columns (≥ 112 each), 3–10.
    var coverColumns: Int

    static let cappedContent: CGFloat = 688

    init(width w: CGFloat, height h: CGFloat) {
        width = w
        wide = w >= 840 && h >= 480
        margin = wide ? 32 : max(16, (w - Self.cappedContent) / 2)
        content = max(0, w - 2 * margin)
        let c = content
        if wide {
            units = min(8, max(5, Int(((c + 10) / 140).rounded(.down))))
        } else if c < 310 {
            units = 1
        } else if c < 448 {
            units = 2
        } else {
            units = c < 560 ? 3 : 4
        }
        coverColumns = min(10, max(3, Int(((c + 12) / 124).rounded(.down))))
    }

    /// The phone's layouts (N ≤ 2) stay pixel for pixel; wider containers take the tablet ones.
    var isTablet: Bool { units >= 3 }

    // MARK: Activities (unit grid)

    /// Entries the unit grid seats at L (Android 5 / 7 / 8 / 10 / 12 / 13 for N 3…8).
    static func activityEntries(units n: Int) -> Int { [3: 5, 4: 7, 5: 8, 6: 10, 7: 12, 8: 13][n] ?? 5 }

    /// The unit grid's rows at [preset]: row 1 the hero (2 units, 3 from N 7) then cards; row 2 cards whose
    /// seams fall away from row 1's; L adds the strip; XL a mirrored row before it. Spans in units; the
    /// hero is `entryIndex == -1`. A row whose first card is past the supply is left out; a card past it at a
    /// row's end gives its units to the card before it (no hole on the right).
    static func activityGrid(units n: Int, hasHero: Bool, supportingCount: Int, preset: HomeRowPreset) -> [ActivityCodeRow] {
        var next = 0
        var rows: [ActivityCodeRow] = []
        func cards(_ spans: [Int], hero: Int? = nil) -> ActivityCodeRow? {
            var slots: [ActivityCodeSlot] = []
            if let hero { slots.append(ActivityCodeSlot(kind: .hero, weight: Double(hero), entryIndex: -1)) }
            for span in spans {
                guard next < supportingCount else {
                    // Out of entries: the last card takes the rest of the row.
                    if let last = slots.indices.last { slots[last].weight += Double(span) }
                    continue
                }
                slots.append(ActivityCodeSlot(kind: span >= 2 ? .wide : .small, weight: Double(span), entryIndex: next))
                next += 1
            }
            guard slots.contains(where: { $0.entryIndex >= 0 || $0.kind == .hero }) else { return nil }
            return ActivityCodeRow(shape: .cards, slots: slots)
        }
        /// Spans filling [total] units, starting with [first], alternating 1 and 2.
        func fill(_ total: Int, first: Int) -> [Int] {
            var out: [Int] = []
            var left = total
            var s = first
            while left > 0 {
                let take = min(s, left)
                out.append(take)
                left -= take
                s = s == 1 ? 2 : 1
            }
            return out
        }
        let heroSpan = n >= 7 ? 3 : 2
        let row1 = hasHero ? cards(fill(n - heroSpan, first: 1), hero: heroSpan) : cards(fill(n, first: 2))
        if let row1 { rows.append(row1) }
        if preset == .s { return rows }
        // Row 2 starts the other way, so its seams don't line up under row 1's.
        if let row2 = cards(fill(n, first: hasHero ? 2 : 1)) { rows.append(row2) }
        if preset == .m { return rows }
        if preset == .xl, let extra = cards(fill(n, first: hasHero ? 1 : 2)) { rows.append(extra) }
        if next < supportingCount {
            rows.append(ActivityCodeRow(shape: .strip, slots: [ActivityCodeSlot(kind: .strip, weight: 1, entryIndex: next)]))
        }
        return rows
    }

    // MARK: Jump Back In (staggered cover columns)

    /// Rows at L: 3 columns 4 rows, 4–5 columns 3 rows, wider 2 rows (Android jbiTemplateRows).
    static func jbiRows(columns: Int, preset: HomeRowPreset) -> Int {
        let l = columns <= 3 ? 4 : columns <= 5 ? 3 : 2
        switch preset {
        case .s: return max(1, l - 2)
        case .m: return max(1, l - 1)
        case .l: return l
        case .xl: return l + 1
        }
    }

    /// A column's width and its covers' size (0.84 of it, 100–128).
    func jbiColumn(gap: CGFloat) -> (width: CGFloat, cover: CGFloat) {
        let w = (content - gap * CGFloat(coverColumns - 1)) / CGFloat(coverColumns)
        return (w, min(128, max(100, (w * 0.84).rounded())))
    }

    /// Each lane's start offset in rows: 0 / ¼ / ½, neighbours different, the first at 0, picked by [seed]
    /// so the same data always lands the same (Android JbiStaggerStyle.Drift).
    static func jbiOffsets(lanes: Int, seed: Int) -> [CGFloat] {
        var out: [CGFloat] = []
        var rng = UInt64(truncatingIfNeeded: seed &* 2654435761 &+ 1)
        for i in 0..<lanes {
            if i == 0 { out.append(0); continue }
            rng = rng &* 6364136223846793005 &+ 1442695040888963407
            let options: [CGFloat] = [0, 0.25, 0.5].filter { $0 != out[i - 1] }
            out.append(options[Int((rng >> 33) % UInt64(options.count))])
        }
        return out
    }

    /// A seed that is the same on every launch (String.hashValue is not): FNV-1a of the id.
    static func stableSeed(_ id: String) -> Int {
        var h: UInt64 = 0xcbf29ce484222325
        for b in id.utf8 { h = (h ^ UInt64(b)) &* 0x100000001b3 }
        return Int(truncatingIfNeeded: h >> 1)
    }

    // MARK: Recently Added

    /// Track columns of the grid: phone 2; 5–7 cover columns 3; ≥ 8 four (Android recentlyAddedTrackColumns).
    var recentTrackColumns: Int { coverColumns >= 8 ? 4 : coverColumns >= 5 ? 3 : 2 }

    /// The grid's width at [columns] (3+ columns: whole 166-wide cells, 8 apart).
    func recentGridWidth(columns: Int) -> CGFloat {
        columns <= 2 ? min(340, max(226.8, (content - 14) * 2.6 / 3.6)) : CGFloat(columns) * 166 + CGFloat(columns - 1) * 8
    }
}
