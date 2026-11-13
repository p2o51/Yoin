import SwiftUI

/*
 * The Memories grid (Android ui/memories/ledger/MemoryGridView.kt, owner 2026-11-08 "B3"): a cell is an
 * album told by its cover's two colours, never the cover itself. An album written about splits base and
 * accent on the diagonal; one only scored is its base, solid; two albums in a week or day splice their bases
 * on the diagonal. A month is a 2×2 of its top four albums. Nothing kept: a faint cell.
 */

/// Where the grid sits: Home's card (compact) or the All Memories page.
enum MemoryGridPlacement { case home, page }

struct MemoryGridView: View {
    var grid: MemoryGrid
    var covers: [String: CoverPalette]
    var placement: MemoryGridPlacement
    var faint: Color
    var outline: Color
    var label: Color = .secondary
    var selected: Int? = nil
    var onCell: ((Int) -> Void)? = nil
    /// All Memories: where the squares sit, so they can rise into the top bar (MemoryGridLift).
    var lift: MemoryGridLift? = nil

    /// The cells shown: on Home a month grid keeps its last six.
    private var shown: ArraySlice<MemoryGridCell> {
        grid.scale == .month && placement == .home ? grid.cells.suffix(6) : grid.cells[...]
    }

    var body: some View {
        Group {
            switch grid.scale {
            case .month: months
            case .week: wall(columns: 13, rows: 2, columnMajor: false, gap: 5)
            case .day: wall(columns: 17, rows: 7, columnMajor: true, gap: 3)
            }
        }
        // Load the covers the grid reads its colours from; each cell redraws as one lands.
        .task(id: Set(albums.compactMap { covers[$0.key]?.cover?.key })) {
            await withTaskGroup(of: Void.self) { group in
                for album in albums { if let ref = covers[album.key]?.cover { group.addTask { await CoverArtwork.shared.load(ref) } } }
            }
        }
    }

    private var albums: [LedgerAlbum] {
        var seen = Set<String>()
        return shown.flatMap { $0.marks.map(\.album) }.filter { seen.insert($0.key).inserted }
    }

    private var paint: MemoryGridPaint { MemoryGridPaint(covers: covers, faint: faint) }

    /// A muted colour of the album's own, picked from its key, so cells don't all wait in one grey.
    static func fallback(_ key: String) -> CoverPalette {
        var h: UInt64 = 1469598103934665603
        for b in key.utf8 { h = (h ^ UInt64(b)) &* 1099511628211 }
        let hue = Double(h % 360) / 360
        return CoverPalette(a: Color(hue: hue, saturation: 0.45, brightness: 0.55),
                            b: Color(hue: (hue + 24.0 / 360).truncatingRemainder(dividingBy: 1), saturation: 0.5, brightness: 0.8),
                            c: .gray)
    }

    // MARK: Weeks and days

    private func wall(columns: Int, rows: Int, columnMajor: Bool, gap: CGFloat) -> some View {
        let cells = Array(shown)
        return GeometryReader { geo in
            let cell = (geo.size.width - gap * CGFloat(columns - 1)) / CGFloat(columns)
            Canvas { [paint] ctx, _ in
                let radius = cell * MemoryGridPaint.wallRadius
                for (i, item) in cells.enumerated() {
                    let (c, r) = columnMajor ? (i / rows, i % rows) : (i % columns, i / columns)
                    let rect = CGRect(x: CGFloat(c) * (cell + gap), y: CGFloat(r) * (cell + gap), width: cell, height: cell)
                    paint.drawCell(item, in: rect, radius: radius, ctx: ctx)
                    if i == selected {
                        let path = Path(roundedRect: rect.insetBy(dx: -2, dy: -2), cornerRadius: radius + 2)
                        ctx.stroke(path, with: .color(outline), lineWidth: 2)
                    }
                }
            }
            .contentShape(.rect)
            .gesture(SpatialTapGesture().onEnded { tap in
                guard let onCell else { return }
                let c = min(max(Int(tap.location.x / (cell + gap)), 0), columns - 1)
                let r = min(max(Int(tap.location.y / (cell + gap)), 0), rows - 1)
                let i = columnMajor ? c * rows + r : r * columns + c
                if cells.indices.contains(i), !cells[i].isEmpty { onCell(i) }
            }, isEnabled: onCell != nil)
        }
        .aspectRatio(CGFloat(columns) / CGFloat(rows), contentMode: .fit)
        // Where the wall sits in the page, for the squares that rise into the top bar.
        .onGeometryChange(for: CGRect.self) { $0.frame(in: .named(MemoryGridLift.space)) } action: { frame in
            lift?.wall = .init(frame: frame, columns: columns, rows: rows, columnMajor: columnMajor, gap: gap)
        }
        .accessibilityElement()
        .accessibilityLabel(Text("Memories grid"))
    }

    // MARK: Months

    private var months: some View {
        let columns = placement == .home ? 6 : 4
        let cells = Array(shown.enumerated())
        let offset = grid.cells.count - shown.count
        let rows = stride(from: 0, to: cells.count, by: columns).map { Array(cells[$0..<min($0 + columns, cells.count)]) }
        return VStack(spacing: 12) {
            ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                HStack(alignment: .top, spacing: columns > 4 ? 8 : 12) {
                    ForEach(row, id: \.offset) { i, cell in
                        VStack(spacing: 4) {
                            quad(cell, outlined: selected == offset + i)
                                .onGeometryChange(for: CGRect.self) { $0.frame(in: .named(MemoryGridLift.space)) } action: {
                                    lift?.quads[offset + i] = $0
                                }
                            Text(cell.start.formatted(.dateTime.month(.abbreviated)))
                                .homeType(Tokens.type.labelSmall)
                                .foregroundStyle(label)
                                .lineLimit(1)
                        }
                        .frame(maxWidth: .infinity)
                        .contentShape(.rect)
                        .onTapGesture { if !cell.isEmpty { onCell?(offset + i) } }
                        .accessibilityElement(children: .combine)
                        .accessibilityLabel(Text(cell.start.formatted(.dateTime.year().month(.wide))))
                    }
                    ForEach(0..<(columns - row.count), id: \.self) { _ in Color.clear.frame(maxWidth: .infinity) }
                }
            }
        }
    }

    private func quad(_ cell: MemoryGridCell, outlined: Bool) -> some View {
        Canvas { [paint] ctx, size in
            let gap = MemoryGridPaint.quadGap
            let q = (size.width - gap) / 2
            let radius = q * MemoryGridPaint.quadRadius
            for i in 0..<4 {
                let rect = CGRect(x: CGFloat(i % 2) * (q + gap), y: CGFloat(i / 2) * (q + gap), width: q, height: q)
                if i < cell.marks.count { paint.drawMark(cell.marks[i], in: rect, radius: radius, ctx: ctx) }
                else { ctx.fill(Path(roundedRect: rect, cornerRadius: radius), with: .color(faint)) }
            }
            if outlined {
                ctx.stroke(Path(roundedRect: CGRect(origin: .zero, size: size), cornerRadius: radius * 1.6),
                           with: .color(outline), lineWidth: 2)
            }
        }
        .aspectRatio(1, contentMode: .fit)
    }
}

/// How the grid paints a cell: shared by the grid and its squares in flight to the top bar.
struct MemoryGridPaint {
    var covers: [String: CoverPalette]
    var faint: Color

    /// A wall cell's corner, of its side; a month quad's album square's; the gap inside a quad.
    static let wallRadius: CGFloat = 0.18
    static let quadRadius: CGFloat = 0.16
    static let quadGap: CGFloat = 2

    func colors(_ album: LedgerAlbum) -> BackdropColors {
        BackdropColors(covers[album.key] ?? MemoryGridView.fallback(album.key))
    }

    func drawCell(_ cell: MemoryGridCell, in rect: CGRect, radius: CGFloat, ctx: GraphicsContext,
                  colors: ((LedgerAlbum) -> BackdropColors)? = nil) {
        guard !cell.future else { return }
        let of = colors ?? self.colors
        switch cell.marks.count {
        case 0: ctx.fill(Path(roundedRect: rect, cornerRadius: radius), with: .color(faint))
        case 1: drawMark(cell.marks[0], in: rect, radius: radius, ctx: ctx, colors: of)
        default: drawSplit(of(cell.marks[0].album).base, of(cell.marks[1].album).base, in: rect, radius: radius, ctx: ctx)
        }
    }

    func drawMark(_ mark: MemoryGridMark, in rect: CGRect, radius: CGFloat, ctx: GraphicsContext,
                  colors: ((LedgerAlbum) -> BackdropColors)? = nil) {
        let c = (colors ?? self.colors)(mark.album)
        if mark.wrote { drawSplit(c.base, c.accent, in: rect, radius: radius, ctx: ctx) }
        else { ctx.fill(Path(roundedRect: rect, cornerRadius: radius), with: .color(c.base)) }
    }

    /// [first] top-left of the diagonal (top-right to bottom-left), [second] bottom-right.
    func drawSplit(_ first: Color, _ second: Color, in rect: CGRect, radius: CGFloat, ctx: GraphicsContext) {
        var clipped = ctx
        clipped.clip(to: Path(roundedRect: rect, cornerRadius: radius))
        clipped.fill(Path(rect), with: .color(second))
        var tri = Path()
        tri.move(to: rect.origin)
        tri.addLine(to: CGPoint(x: rect.maxX, y: rect.minY))
        tri.addLine(to: CGPoint(x: rect.minX, y: rect.maxY))
        tri.closeSubpath()
        clipped.fill(tri, with: .color(first))
    }
}
