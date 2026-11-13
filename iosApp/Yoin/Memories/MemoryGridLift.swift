import SwiftUI

/*
 * The grid becomes the top bar (Android ui/memories/ledger/MemoryGridLift.kt, owner 2026-11-09 / 11-12): as
 * All Memories scrolls up past its grid, the grid's squares rise into the top bar and piece together there as
 * a mosaic of square pixels, oldest first down each column and on across. Scrolling back down they come home.
 * One progress drives it all: how far the grid has gone under the bar.
 *
 * The grid writes where its squares sit in the page's content (they don't move as it scrolls); the scroll
 * view writes its offset. The layer over the page reads both while drawing.
 */

/// One square that rises: a week's or day's whole cell (`mark` -1), or one album of a month's quad.
struct LiftPixel: Hashable {
    var index: Int
    var cell: MemoryGridCell
    var mark: Int
}

/// The bar's squares: `rows` of them down, each `side` pt, `places` in all (the last column may run off the edge).
struct BarMosaic: Equatable {
    var rows: Int
    var side: CGFloat
    var places: Int
}

enum MemoryGridLiftMath {
    /// Past this many rows the squares would be specks; extra squares then share places.
    static let maxBarRows = 8
    /// How far into the lift the bar counts as formed.
    static let formed: CGFloat = 0.5

    /// The grid's squares with something in them, oldest first.
    static func pixels(_ grid: MemoryGrid) -> [LiftPixel] {
        switch grid.scale {
        case .month:
            grid.cells.enumerated().flatMap { index, cell in
                cell.marks.indices.map { LiftPixel(index: index, cell: cell, mark: $0) }
            }
        case .week, .day:
            grid.cells.enumerated().compactMap { index, cell in
                cell.future || cell.marks.isEmpty ? nil : LiftPixel(index: index, cell: cell, mark: -1)
            }
        }
    }

    /// The fewest rows (two at least, so it reads as pixels, not tiles) whose squares, filling the bar's
    /// height and running across its width, have a place for each of `count` squares.
    static func mosaic(count: Int, width: CGFloat, height: CGFloat) -> BarMosaic {
        var rows = 2
        while true {
            let side = height / CGFloat(rows)
            let columns = max(Int((width / side).rounded(.up)), 1)
            if rows * columns >= count || rows >= maxBarRows { return BarMosaic(rows: rows, side: side, places: rows * columns) }
            rows += 1
        }
    }

    /// Which square takes place `k`: when there are more places than squares, a square takes neighbouring places.
    static func pixel(forPlace k: Int, places: Int, count: Int) -> Int { Int(Int64(k) * Int64(count) / Int64(places)) }

    /// 0 = the grid wholly below the bar's bottom, 1 = wholly gone under it.
    static func progress(gridTop: CGFloat, gridHeight: CGFloat, barBottom: CGFloat) -> CGFloat {
        guard gridHeight > 0 else { return 0 }
        return min(max((barBottom - gridTop) / gridHeight, 0), 1)
    }

    /// The page's own grid while it rises: hidden once its copies take off (so no square shows twice), or,
    /// under Reduce Motion where nothing flies and the bar fades in at `progress`, faded out the other way.
    static func realGridOpacity(progress: CGFloat, lifting: Bool, reduced: Bool) -> Double {
        reduced ? Double(1 - min(max(progress, 0), 1)) : (lifting ? 0 : 1)
    }

    /// Square `i` of `n`'s way from the grid to the bar: oldest first, close together so they rise as one flock.
    static func flight(_ i: Int, of n: Int, at p: CGFloat) -> CGFloat {
        let lag = 0.1 * CGFloat(i) / CGFloat(max(n, 1))
        return smoothstep(0.02 + lag, 0.6 + lag, p)
    }

    /// The bar's place `k`, `rows` down a column, columns from the leading edge.
    static func target(_ k: Int, _ mosaic: BarMosaic, width: CGFloat, rtl: Bool) -> CGRect {
        let column = k / mosaic.rows, row = k % mosaic.rows
        let x = rtl ? width - CGFloat(column + 1) * mosaic.side : CGFloat(column) * mosaic.side
        // A hair larger than the square so neighbours meet without a seam.
        return CGRect(x: x, y: CGFloat(row) * mosaic.side, width: mosaic.side + 1, height: mosaic.side + 1)
    }
}

/// Where the grid's squares are, in the page's content, and how far the page has scrolled.
@Observable @MainActor
final class MemoryGridLift {
    /// The page's content: the grid's frames are measured in it.
    static let space = "memoryLedgerContent"

    struct Wall: Equatable {
        var frame: CGRect
        var columns: Int
        var rows: Int
        var columnMajor: Bool
        var gap: CGFloat
    }

    /// The scroll view's content offset and top inset (status bar and top bar).
    private(set) var scrollY: CGFloat = 0
    private(set) var insetTop: CGFloat = 0
    /// The scroll view's frame on screen (below the top bar: its content scrolls on up under it).
    var viewport: CGRect = .zero
    /// The whole grid, in content space.
    var grid: CGRect = .zero { didSet { refresh() } }
    /// The grid's copies have taken off: the real grid steps aside so no square shows twice.
    private(set) var lifting = false

    @ObservationIgnored var wall: Wall?
    @ObservationIgnored var quads: [Int: CGRect] = [:]

    func scrolled(y: CGFloat, insetTop: CGFloat) {
        scrollY = y
        self.insetTop = insetTop
        refresh()
    }

    /// The grid's way under the bar (the bar's bottom is the scroll view's top inset).
    var progress: CGFloat {
        MemoryGridLiftMath.progress(gridTop: grid.minY - scrollY, gridHeight: grid.height, barBottom: insetTop)
    }

    private func refresh() {
        let now = progress > 0.001
        if now != lifting { lifting = now }
    }

    /// Where `pixel` sits in content space; nil when its part of the grid isn't placed.
    func rect(of pixel: LiftPixel) -> CGRect? {
        if pixel.mark < 0 {
            guard let wall else { return nil }
            let side = (wall.frame.width - wall.gap * CGFloat(wall.columns - 1)) / CGFloat(wall.columns)
            let column = wall.columnMajor ? pixel.index / wall.rows : pixel.index % wall.columns
            let row = wall.columnMajor ? pixel.index % wall.rows : pixel.index / wall.columns
            return CGRect(x: wall.frame.minX + CGFloat(column) * (side + wall.gap),
                          y: wall.frame.minY + CGFloat(row) * (side + wall.gap), width: side, height: side)
        }
        guard let quad = quads[pixel.index] else { return nil }
        let side = (quad.width - MemoryGridPaint.quadGap) / 2
        return CGRect(x: quad.minX + CGFloat(pixel.mark % 2) * (side + MemoryGridPaint.quadGap),
                      y: quad.minY + CGFloat(pixel.mark / 2) * (side + MemoryGridPaint.quadGap), width: side, height: side)
    }
}

/// The squares on their way up and, landed, the bar: drawn over the page and under the top bar's back button.
struct MemoryGridLiftLayer: View {
    let lift: MemoryGridLift
    let pixels: [LiftPixel]
    let paint: MemoryGridPaint
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.layoutDirection) private var layoutDirection

    var body: some View {
        GeometryReader { geo in
            let origin = geo.frame(in: .global).origin
            // Content space to this layer. The scroll view's frame starts under the top bar while its content
            // runs up under the bar and the status bar (its top inset), so the bar ends where the frame starts.
            let dx = lift.viewport.minX - origin.x
            let barBottom = lift.viewport.minY - origin.y
            let dy = barBottom - lift.insetTop - lift.scrollY
            let p = lift.progress
            let reduced = reduceMotion
            let rtl = layoutDirection == .rightToLeft
            let sources = p > 0.001 && !reduced ? pixels.map { lift.rect(of: $0)?.offsetBy(dx: dx, dy: dy) } : []
            Canvas { ctx, size in
                draw(&ctx, size: size, p: p, barBottom: barBottom, sources: sources, reduced: reduced, rtl: rtl)
            }
        }
        .ignoresSafeArea()
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    private func draw(_ ctx: inout GraphicsContext, size: CGSize, p: CGFloat, barBottom: CGFloat,
                      sources: [CGRect?], reduced: Bool, rtl: Bool) {
        let n = pixels.count
        guard n > 0, p > 0.001, barBottom > 0 else { return }
        let mosaic = MemoryGridLiftMath.mosaic(count: n, width: size.width, height: barBottom)
        var colors: [String: BackdropColors] = [:]
        func of(_ album: LedgerAlbum) -> BackdropColors {
            if let c = colors[album.key] { return c }
            let c = paint.colors(album)
            colors[album.key] = c
            return c
        }
        if reduced {
            // Reduce Motion: no flight, the whole bar fades in where it ends up (one layer, not one per square).
            ctx.drawLayer { layer in
                layer.opacity = p
                for k in 0..<mosaic.places {
                    let i = MemoryGridLiftMath.pixel(forPlace: k, places: mosaic.places, count: n)
                    drawBarPixel(pixels[i], in: MemoryGridLiftMath.target(k, mosaic, width: size.width, rtl: rtl),
                                 opacity: 1, ctx: layer, of: of)
                }
            }
        } else {
            for k in 0..<mosaic.places {
                let i = MemoryGridLiftMath.pixel(forPlace: k, places: mosaic.places, count: n)
                let pixel = pixels[i]
                let target = MemoryGridLiftMath.target(k, mosaic, width: size.width, rtl: rtl)
                let q = MemoryGridLiftMath.flight(i, of: n, at: p)
                let from = i < sources.count ? sources[i] : nil
                let rect = from.map { q >= 1 ? target : lerp($0, target, q) } ?? target
                let radius = from.map { $0.width * (pixel.mark < 0 ? MemoryGridPaint.wallRadius : MemoryGridPaint.quadRadius) * (1 - q) } ?? 0
                // The grid's look in flight; the bar's pixels as it lands.
                let landed = smoothstep(0.7, 1, q)
                if landed < 1 {
                    if pixel.mark < 0 { paint.drawCell(pixel.cell, in: rect, radius: radius, ctx: ctx, colors: of) }
                    else { paint.drawMark(pixel.cell.marks[pixel.mark], in: rect, radius: radius, ctx: ctx, colors: of) }
                }
                if landed > 0 { drawBarPixel(pixel, in: rect, opacity: landed, ctx: ctx, of: of) }
            }
        }
        // A light veil under the back button once the bar has formed, so the status bar reads on pale covers too.
        let veil = 0.24 * smoothstep(0.6, 1, p)
        if veil > 0 {
            ctx.fill(Path(CGRect(x: 0, y: 0, width: size.width, height: barBottom)), with: .color(.black.opacity(veil)))
        }
    }

    /// A square landed in the bar, as pixels: two colours (an album written about, or two albums spliced)
    /// become a 2×2 checker of them, one colour stays one square.
    private func drawBarPixel(_ pixel: LiftPixel, in rect: CGRect, opacity: CGFloat, ctx: GraphicsContext,
                              of: (LedgerAlbum) -> BackdropColors) {
        guard let (first, second) = Self.barColors(pixel, of) else { return }
        var ctx = ctx
        ctx.opacity = opacity
        if first == second {
            ctx.fill(Path(rect), with: .color(first))
            return
        }
        let w = rect.width / 2, h = rect.height / 2
        let half = CGSize(width: w + 0.5, height: h + 0.5)
        ctx.fill(Path(CGRect(origin: rect.origin, size: half)), with: .color(first))
        ctx.fill(Path(CGRect(origin: CGPoint(x: rect.minX + w, y: rect.minY), size: half)), with: .color(second))
        ctx.fill(Path(CGRect(origin: CGPoint(x: rect.minX, y: rect.minY + h), size: half)), with: .color(second))
        ctx.fill(Path(CGRect(origin: CGPoint(x: rect.minX + w, y: rect.minY + h), size: half)), with: .color(first))
    }

    private static func barColors(_ pixel: LiftPixel, _ of: (LedgerAlbum) -> BackdropColors) -> (Color, Color)? {
        func mark(_ m: MemoryGridMark) -> (Color, Color) {
            let c = of(m.album)
            return m.wrote ? (c.base, c.accent) : (c.base, c.base)
        }
        if pixel.mark >= 0 { return mark(pixel.cell.marks[pixel.mark]) }
        let marks = pixel.cell.marks
        switch marks.count {
        case 0: return nil
        case 1: return mark(marks[0])
        default: return (of(marks[0].album).base, of(marks[1].album).base)
        }
    }

    private func lerp(_ a: CGRect, _ b: CGRect, _ t: CGFloat) -> CGRect {
        CGRect(x: lerpf(a.minX, b.minX, t), y: lerpf(a.minY, b.minY, t),
               width: lerpf(a.width, b.width, t), height: lerpf(a.height, b.height, t))
    }
}
