import SwiftUI
import YoinShared

/*
 * Two-column Home on iPad and Mac (parity p4 B2; Android HomeFeedRows.kt, HomeGridUnits.kt, HomeShelfPager.kt;
 * design.md §主页「两栏」). When the feed's container is at least 840 × 480 pt (`HomeDensity.wide`, the shared
 * `homeTwoColumn` tier: the container's size, not the window's or the orientation) the feed lays out in rows of
 * one full section or two halves 20 pt apart. Every section is then a whole number of grid units tall, so the two
 * halves of a row always line up: height = 40 + n·150 + (n − 1)·14 (`HomeGrid`). Each cell reads its own width
 * (`HomeCell`, Android `forHomeColumn`): a half lays out its section for half the content, a full row for all of
 * it. The arithmetic is :shared's (`YoinHomeLayout`), pinned by shared/src/commonTest/resources/golden/home-grid.json.
 */

// MARK: - The grid's numbers (shared HomeGridTokens, through the facade)

enum HomeGrid {
    private static var core: YoinHomeLayout { YoinHomeLayout.shared }

    /// One unit (150): a 106 pt cover and its two caption lines.
    static let unit = CGFloat(HomeGridTokens.shared.Unit)
    /// Between two units of one section.
    static let gap = CGFloat(HomeGridTokens.shared.Gap)
    /// The title row every section has in two columns.
    static let titleRow = CGFloat(HomeGridTokens.shared.TitleRow)
    /// A unit drag pulls this far past the smallest or largest count (a rubber band past it).
    static let overdrag = CGFloat(HomeGridTokens.shared.Overdrag)
    /// The narrowest gap between two cover cards in a row.
    static let columnGap = CGFloat(HomeGridTokens.shared.ColumnGap)
    /// Between two of Recently Added's track rows.
    static let trackRowGap = CGFloat(HomeGridTokens.shared.TrackRowGap)
    /// Between the two halves of a row (shared `HomeSpanRules.ColumnGap`).
    static let rowGap = CGFloat(HomeSpanRules.shared.ColumnGap)
    /// Between two rows of the feed (the one-column stack's spacing).
    static let feedSpacing: CGFloat = 18
    /// A card on the grid: the cover side U − 44 (106).
    static var cover: CGFloat { CGFloat(core.gridCoverDp(unitDp: Float(unit))) }

    /// The feed lays out in two columns: its container is ≥ 840 × 480 (shared `homeTwoColumn`; iOS has no tabletop).
    static func twoColumn(width: CGFloat, height: CGFloat) -> Bool {
        core.twoColumn(containerWidthDp: Float(width), containerHeightDp: Float(height), tabletop: false)
    }

    /// A section's height at [units]: T + n·U + (n − 1)·G.
    static func height(units: Int) -> CGFloat { CGFloat(core.gridHeightDp(units: Int32(clamping: units))) }
    /// Under the title row: n·U + (n − 1)·G.
    static func content(units: Int) -> CGFloat { CGFloat(core.gridContentDp(units: Int32(clamping: units))) }
    /// Cover columns a [width] row seats: ⌊(W + 12) / (106 + 12)⌋, at least 1.
    static func columns(width: CGFloat) -> Int {
        Int(core.gridColumns(widthDp: Float(width), coverDp: Float(cover), gapDp: Float(columnGap)))
    }
    /// Recently Added's track rows (three a unit), columns (⌊W / 280⌋) and one row's height.
    static func trackRows(units: Int) -> Int { Int(core.gridTrackRows(units: Int32(clamping: units))) }
    static func trackColumns(width: CGFloat) -> Int { Int(core.gridTrackColumns(widthDp: Float(width))) }
    static func trackRowHeight(units: Int) -> CGFloat {
        CGFloat(core.gridTrackRowDp(units: Int32(clamping: units), gapDp: Float(trackRowGap)))
    }
    /// Activities' columns on the grid (≥ 2) and its cards' spans (a bento: the lead and every other card two wide).
    static func activityColumns(width: CGFloat) -> Int {
        Int(core.gridActivityColumns(widthDp: Float(width), gapDp: Float(columnGap)))
    }

    /// One card placed on the grid: [index] into the caller's list, its row, first column and span.
    struct Slot: Hashable {
        var index: Int
        var row: Int
        var column: Int
        var span: Int
    }

    /// Cards of [spans] columns each, row-major into [rows] rows of [columns] (shared `homeGridPack`).
    static func pack(spans: [Int], columns: Int, rows: Int) -> [Slot] {
        core.gridPack(spans: spans.map { KotlinInt(int: Int32($0)) }, columns: Int32(columns), rows: Int32(rows)).map {
            Slot(index: Int($0.index), row: Int($0.row), column: Int($0.column), span: Int($0.span))
        }
    }

    /// A cell's left edge and width across [width], [gap] apart, whole points (shared `homeGridCellX`).
    static func cellX(column: Int, span: Int, columns: Int, width: CGFloat, gap: CGFloat) -> (x: CGFloat, width: CGFloat) {
        let r = core.gridCellX(column: Int32(column), span: Int32(span), columns: Int32(columns),
                               widthPx: Int32(max(0, width.rounded(.down))), gapPx: Int32(gap.rounded()))
        return (CGFloat(truncating: r[0]), CGFloat(truncating: r[1]))
    }

    /// The unit counts [section] takes in two columns.
    static func unitRange(_ section: HomeSection) -> ClosedRange<Int> {
        let r = core.unitRange(id: section.id).map(\.intValue)
        return r.count == 2 ? r[0]...r[1] : 1...1
    }

    /// The range a row's handle drags over: [left]'s own, or the overlap with [right].
    static func rowUnitRange(_ left: HomeSection, _ right: HomeSection?) -> ClosedRange<Int> {
        let r = core.rowUnitRange(left: left.id, right: right?.id).map(\.intValue)
        return r.count == 2 ? r[0]...r[1] : 1...1
    }

    /// The count in [range] whose height is nearest [height].
    static func unitsNearest(height: CGFloat, range: ClosedRange<Int>, density: CGFloat = 1) -> Int {
        Int(core.unitsNearest(heightPx: Float(height), first: Int32(range.lowerBound), last: Int32(range.upperBound),
                              density: Float(density)))
    }

    /// [left] and [right] can be the two halves of a row (their unit ranges meet).
    static func canPair(_ left: HomeSection, _ right: HomeSection) -> Bool { core.canPair(left: left.id, right: right.id) }

    /// The feed's rows for [displayed]: one section, or a pair left → right side by side (shared `homeFeedRows`).
    static func rows(_ displayed: [HomeSection], pairs: [(left: HomeSection, right: HomeSection)], twoColumn: Bool) -> [[HomeSection]] {
        core.feedRows(displayed: displayed.map(\.id), pairs: pairs.map { [$0.left.id, $0.right.id] }, twoColumn: twoColumn)
            .map { $0.compactMap(HomeSection.init(rawValue:)) }
    }

    /// A row's cell widths across [width]: a pair's two halves, or under a span drag of [dragged] at [dragWidth]
    /// the dragged cell that wide and its partner what is left (shared `homeRowCellWidths`).
    static func cellWidths(_ row: [HomeSection], width: CGFloat, gap: CGFloat = rowGap, dragged: HomeSection?,
                           dragWidth: CGFloat) -> [CGFloat] {
        core.rowCellWidths(row: row.map(\.id), widthPx: Int32(max(0, width.rounded(.down))), gapPx: Int32(gap.rounded()),
                           dragged: dragged?.id, dragPx: Int32(max(0, dragWidth.rounded()))).map { CGFloat(truncating: $0) }
    }

    /// A half-column page's geometry: whole cards, the gaps stretched to the column's edges.
    static func pageGeometry(page: CGFloat, card: CGFloat, minGap: CGFloat) -> ShelfPageGeometry {
        core.shelfPageGeometry(pagePx: Float(page), cardPx: Float(card), minGapPx: Float(minGap))
    }

    /// A card's width when [perPage] of them, 14 apart, fill a [page]-wide page.
    static func cardWidth(page: CGFloat, perPage: Int) -> CGFloat {
        CGFloat(core.shelfCardWidthDp(pageWidthDp: Float(page), perPage: Int32(perPage)))
    }

    /// Pages of [cards] cards after [leadPages] one-page lead items.
    static func pageCount(cards: Int, perPage: Int, leadPages: Int) -> Int {
        Int(core.shelfPageCount(cardCount: Int32(cards), perPage: Int32(perPage), leadPages: Int32(leadPages)))
    }

    /// The trailing filler that makes a paged shelf's last page whole (0 when it already is).
    static func filler(cards: Int, geometry: ShelfPageGeometry) -> CGFloat {
        CGFloat(core.shelfFillerPx(cardCount: Int32(cards), geometry: geometry))
    }

    /// The page a release lands on: one from [startPage] in the swipe's direction past 400 pt/s, else the nearer.
    static func releasePage(scroll: CGFloat, velocity: CGFloat, stride: CGFloat, startPage: Int, pages: Int,
                            fling: CGFloat = CGFloat(HomeShelfPagerRules.shared.FlingVelocity)) -> Int {
        Int(core.shelfReleasePage(scrollPx: Float(scroll), velocityPx: Float(velocity), stridePx: Float(stride),
                                  startPage: Int32(startPage), pageCount: Int32(pages), flingPx: Float(fling)))
    }

    /// Activities' row preset in two columns (Android activityGridPreset): the phone's bento for the column,
    /// its rows from the units — 1 S, 2 M, 3 XL.
    static func activityPreset(units: Int) -> HomeRowPreset { units <= 1 ? .s : units == 2 ? .m : .xl }
}

// MARK: - A cell's context

/// What a section lays out for (Android `forHomeColumn` + `LocalHomeGridHeight` + `LocalHomeColumn`): its own
/// density for its width, its row's grid units (nil in one column) and whether it is half a row (a half pages
/// its shelf inside the column instead of bleeding to the window's edge).
struct HomeCell: Equatable {
    var density: HomeDensity
    /// The row's unit count: the cell is exactly `HomeGrid.height(units:)` tall. Nil: one column.
    var units: Int?
    var half: Bool = false
    /// The box's live height (a unit drag in flight); nil: exactly its units' grid height.
    var height: CGFloat? = nil

    /// The width the section lays out across.
    var width: CGFloat { density.content }
    /// How far a free shelf bleeds past the content (the page margin; none in a half, whose shelf pages).
    var bleed: CGFloat { half ? 0 : density.margin }
    var grid: Bool { units != nil }

    /// One column: the feed's own density.
    static func feed(_ density: HomeDensity) -> HomeCell { HomeCell(density: density, units: nil) }
}

extension HomeDensity {
    /// A two-column cell [width] wide (Android `forHomeColumn`): its own feed units and cover columns, the Wide
    /// recipe from 776 across (the narrowest Wide content), the medium one below; the feed's page [margin] kept
    /// for a full row's bleeding shelves.
    init(column width: CGFloat, margin: CGFloat) {
        self.init(width: width + 2 * margin, height: 1000)
        self.width = width
        self.margin = margin
        content = width
        wide = width >= 776
        if wide {
            units = min(8, max(5, Int(((width + 10) / 140).rounded(.down))))
        } else if width < 310 {
            units = 1
        } else if width < 448 {
            units = 2
        } else {
            units = width < 560 ? 3 : 4
        }
        coverColumns = min(10, max(3, Int(((width + 12) / 124).rounded(.down))))
    }
}

extension EnvironmentValues {
    /// The section's cell in two columns (nil in one column): its title takes the shared 40 pt title row.
    @Entry var homeGridCell: HomeCell? = nil
}

// MARK: - The rows

/// The feed's sections in rows (Android HomeFeedRowLayout): the subviews are the sections in order, [rows]
/// says how many each row takes (1 or 2) and [heights] how tall each row is. A pair's halves are [gap] apart, the
/// left at the content's leading edge, the right at its trailing one; a span drag ([drag]) sizes its cell to the
/// finger and its partner takes what is left. One flat list keyed by section, so a section that changes rows (a
/// pair forming or splitting, a drop, Undo) moves and resizes to its new place on the spring instead of fading.
struct HomeFeedRowsLayout: Layout {
    var rows: [[HomeSection]]
    var heights: [CGFloat]
    var spacing: CGFloat = HomeGrid.feedSpacing
    /// The span drag in flight: its section and live width.
    var drag: (section: HomeSection, width: CGFloat)? = nil

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? 0
        let h = heights.reduce(0, +) + spacing * CGFloat(max(0, heights.count - 1))
        return CGSize(width: width, height: h)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var i = 0
        var y = bounds.minY
        for (r, row) in rows.enumerated() {
            let h = r < heights.count ? heights[r] : 0
            let widths = HomeGrid.cellWidths(row, width: bounds.width, dragged: drag.flatMap { row.contains($0.section) ? $0.section : nil },
                                             dragWidth: drag?.width ?? bounds.width)
            for (k, _) in row.enumerated() where i < subviews.count {
                let w = k < widths.count ? widths[k] : bounds.width
                let x = k == 0 ? bounds.minX : bounds.maxX - w
                subviews[i].place(at: CGPoint(x: x, y: y), anchor: .topLeading, proposal: ProposedViewSize(width: w, height: h))
                i += 1
            }
            y += h + spacing
        }
        // Any subview past the rows (a section fading out): placed at the end, out of the way.
        while i < subviews.count {
            subviews[i].place(at: CGPoint(x: bounds.minX, y: y), anchor: .topLeading, proposal: ProposedViewSize(width: bounds.width, height: 0))
            i += 1
        }
    }
}

// MARK: - The box a section lives in

extension View {
    /// Two columns: exactly [units] grid units tall ([height] while a unit drag runs), the content top-aligned in
    /// it and cut off at the bottom (sideways it may still bleed, as a full row's shelves do). One column: as it is.
    @ViewBuilder
    func homeGridBox(_ units: Int?, height: CGFloat? = nil) -> some View {
        if let units {
            let h = height ?? HomeGrid.height(units: units)
            frame(height: h, alignment: .top)
                .frame(maxWidth: .infinity, alignment: .topLeading)
                .mask(alignment: .top) { Rectangle().frame(width: 20_000, height: h) }
        } else {
            self
        }
    }
}

/// The 40 pt title row every section has on the grid: the title on its centre line, then [trailing] (a paged
/// shelf's dots and ‹ ›) at its end.
struct HomeGridTitleRow<Trailing: View>: View {
    var title: String
    @ViewBuilder var trailing: () -> Trailing

    var body: some View {
        HStack(spacing: 8) {
            HomeSectionTitle(title).frame(maxWidth: .infinity, alignment: .leading)
            trailing()
        }
        .frame(height: HomeGrid.titleRow)
    }
}

extension HomeGridTitleRow where Trailing == EmptyView {
    init(_ title: String) { self.init(title: title) { EmptyView() } }
}

/// Cards on the grid (Android HomeGridCells): every card exactly [rowHeight] tall, rows [rowGap] apart, [columns]
/// equal columns [gap] apart across the width; [stretchLast]: the last card takes what is left of its row. A
/// change of units at the same width moves each card to its new place on the spatial spring.
struct HomeGridCellsLayout: Layout {
    var slots: [HomeGrid.Slot]
    var columns: Int
    var gap: CGFloat = HomeGrid.columnGap
    var rowHeight: CGFloat = HomeGrid.unit
    var rowGap: CGFloat = HomeGrid.gap
    var stretchLast = false

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let rows = (slots.map(\.row).max() ?? -1) + 1
        return CGSize(width: proposal.width ?? 0, height: rows <= 0 ? 0 : CGFloat(rows) * rowHeight + CGFloat(rows - 1) * rowGap)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        for (i, slot) in slots.enumerated() where i < subviews.count {
            let span = stretchLast && i == slots.count - 1 ? columns - slot.column : slot.span
            let x = HomeGrid.cellX(column: slot.column, span: max(1, min(span, columns - slot.column)), columns: columns,
                                   width: bounds.width, gap: gap)
            subviews[i].place(at: CGPoint(x: bounds.minX + x.x, y: bounds.minY + CGFloat(slot.row) * (rowHeight + rowGap)),
                              anchor: .topLeading, proposal: ProposedViewSize(width: x.width, height: rowHeight))
        }
    }
}

// MARK: - A shelf on the grid

/// A titled shelf on the grid (Android HomeShelf on the grid): in a full row the free shelf, bleeding to the
/// container's edge; in half a row ([HomeCell.half]) it pages: each page a whole number of cards, the gaps
/// stretched so they meet the column's edges, a swipe turns one page and snaps, and the title row carries the page
/// dots and ‹ ›. [cardWidth] is each card's width (all one width); [cards] how many cards (columns) there are.
/// [lead]: one item a whole page wide first (Recently Added's track grid), built by [leadContent] for a width.
struct HomeGridShelf<Lead: View, Content: View>: View {
    @Environment(\.homeScheme) private var scheme
    @Environment(\.homeEdit) private var edit
    var title: String
    var cell: HomeCell
    var cardWidth: CGFloat
    var cards: Int
    /// Full rows: the gap between cards.
    var fullGap: CGFloat = 14
    /// The half's narrowest gap (the grid's 12 for covers; 14 for a card [perPage] sizes).
    var minGap: CGFloat = HomeGrid.columnGap
    var hasLead = false
    var leadContent: (CGFloat) -> Lead
    /// The cards, for this gap between them.
    var content: (CGFloat) -> Content

    init(title: String, cell: HomeCell, cardWidth: CGFloat, cards: Int, fullGap: CGFloat = 14, minGap: CGFloat = HomeGrid.columnGap,
         hasLead: Bool, @ViewBuilder lead: @escaping (CGFloat) -> Lead, @ViewBuilder content: @escaping (CGFloat) -> Content) {
        self.title = title
        self.cell = cell
        self.cardWidth = cardWidth
        self.cards = cards
        self.fullGap = fullGap
        self.minGap = minGap
        self.hasLead = hasLead
        leadContent = lead
        self.content = content
    }

    @State private var pager = HomeShelfPagerState()
    @State private var position = ScrollPosition(x: 0)

    var body: some View {
        if !cell.half {
            VStack(alignment: .leading, spacing: 0) {
                HomeGridTitleRow(title)
                ScrollView(.horizontal) {
                    HStack(alignment: .top, spacing: fullGap) {
                        if hasLead { leadContent(cell.width) }
                        content(fullGap)
                    }
                    .padding(.horizontal, cell.bleed)
                }
                .scrollIndicators(.hidden)
                .scrollDisabled(edit?.isEditing == true)
                .padding(.horizontal, -cell.bleed)
            }
        } else {
            paged
        }
    }

    private var paged: some View {
        let g = HomeGrid.pageGeometry(page: cell.width, card: cardWidth, minGap: minGap)
        let perPage = Int(g.perPage)
        let gap = CGFloat(g.gapPx)
        let stride = CGFloat(g.stridePx)
        let leadPages = hasLead ? 1 : 0
        let pages = HomeGrid.pageCount(cards: cards, perPage: perPage, leadPages: leadPages)
        let filler = HomeGrid.filler(cards: cards, geometry: g)
        let current = min(max(pager.page, 0), max(pages - 1, 0))
        return VStack(alignment: .leading, spacing: 0) {
            HomeGridTitleRow(title: title) {
                if pages > 1 && edit?.isEditing != true {
                    HomeShelfPageControls(pages: pages, current: current) { target in
                        pager.rest = target
                        withAnimation(Yoin.spatial) { position.scrollTo(x: CGFloat(target) * stride) }
                    }
                }
            }
            ScrollView(.horizontal) {
                HStack(alignment: .top, spacing: gap) {
                    if hasLead { leadContent(cell.width).frame(width: cell.width, alignment: .topLeading) }
                    content(gap)
                    if filler > 0 { Color.clear.frame(width: filler, height: 1) }
                }
            }
            .scrollIndicators(.hidden)
            .scrollDisabled(edit?.isEditing == true)
            .scrollTargetBehavior(HomeShelfPaging(stride: stride, pages: pages, pager: pager))
            .scrollPosition($position)
            .onScrollGeometryChange(for: CGFloat.self) { $0.contentOffset.x } action: { _, x in
                pager.x = x
                let now = stride > 0 ? Int((x / stride).rounded()) : 0
                if now != pager.page { pager.page = now }
            }
            // A resize (the column, a span change) keeps the page it was on, re-seated at once.
            .onChange(of: stride) { _, now in
                let page = min(pager.rest, max(pages - 1, 0))
                pager.rest = page
                position.scrollTo(x: CGFloat(page) * now)
            }
        }
    }
}

extension HomeGridShelf where Lead == EmptyView {
    init(title: String, cell: HomeCell, cardWidth: CGFloat, cards: Int, fullGap: CGFloat = 14,
         minGap: CGFloat = HomeGrid.columnGap, @ViewBuilder content: @escaping (CGFloat) -> Content) {
        self.init(title: title, cell: cell, cardWidth: cardWidth, cards: cards, fullGap: fullGap, minGap: minGap,
                  hasLead: false, lead: { _ in EmptyView() }, content: content)
    }
}

/// Where a paged shelf rests and is (read by its paging behaviour, off the main actor, and by its dots).
@Observable
final class HomeShelfPagerState: @unchecked Sendable {
    /// The page the dots show (the nearest to where it is).
    var page = 0
    /// The page it last settled on: a swipe moves one page from here.
    @ObservationIgnored var rest = 0
    /// The scroll now.
    @ObservationIgnored var x: CGFloat = 0
}

/// A paged shelf's release (Android ShelfPageFling, shared `shelfReleasePage`): one page from where it rested,
/// in the swipe's direction past 400 pt/s, else to the nearer page; never more than one page away.
struct HomeShelfPaging: ScrollTargetBehavior {
    var stride: CGFloat
    var pages: Int
    var pager: HomeShelfPagerState

    func updateTarget(_ target: inout ScrollTarget, context: TargetContext) {
        guard stride > 0, pages > 1 else { return }
        let page = HomeGrid.releasePage(scroll: pager.x, velocity: context.velocity.dx, stride: stride, startPage: pager.rest, pages: pages)
        pager.rest = page
        target.rect.origin.x = CGFloat(page) * stride
    }
}

/// A paged shelf's dots and ‹ › at the end of its title row (Android ShelfPageDots / ShelfPageButton).
struct HomeShelfPageControls: View {
    @Environment(\.homeScheme) private var scheme
    var pages: Int
    var current: Int
    var turn: (Int) -> Void

    var body: some View {
        let s = scheme ?? .brand(dark: false)
        HStack(spacing: 4) {
            HStack(spacing: 6) {
                ForEach(0..<pages, id: \.self) { i in
                    Circle()
                        .fill(i == current ? s.primary : s.outlineVariant)
                        .frame(width: 6, height: 6)
                        .animation(Yoin.effects, value: current)
                }
            }
            .accessibilityHidden(true)
            .padding(.trailing, 4)
            button("chevron_left", String(localized: "Previous page"), enabled: current > 0) { turn(current - 1) }
            button("chevron_right", String(localized: "Next page"), enabled: current < pages - 1) { turn(current + 1) }
        }
    }

    private func button(_ icon: String, _ label: String, enabled: Bool, _ action: @escaping () -> Void) -> some View {
        let s = scheme ?? .brand(dark: false)
        return Button(action: action) {
            YoinIcon(icon, size: 20)
                .foregroundStyle(s.onSurfaceVariant)
                .frame(width: 32, height: 32)
                .contentShape(.rect)
        }
        .buttonStyle(PressScale())
        .pointerHighlight()
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.38)
        .accessibilityLabel(label)
    }
}
