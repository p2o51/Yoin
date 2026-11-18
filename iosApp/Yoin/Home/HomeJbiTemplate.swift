import Foundation

/*
 * Jump Back In, templated: a Swift port of Android's HomeJbiTemplate.kt. On panes of 3+ feed units the
 * grid is K lanes of phone-sized cover columns, every cover the same size:
 *
 *   cover        1×1  a cover, as on the phone
 *   tallSignal   1×2  a rating / note card standing: cover over its copy
 *   wideSignal   2×1  the phone's 1×2 lying down: cover beside its copy (two columns = one lane)
 *
 * Each lane starts at its own height (a fraction of a row lower than its neighbour), so no row line runs
 * across the grid; a lying card breaks a column line. A standing card's neighbours keep its height; a lying
 * card's right neighbour drops half a row. A seed from the data picks offsets and where the two cards go,
 * so the same shelf always lands the same way. Pure: no UI types in here.
 */

// MARK: - Seeded random (kotlin.random.Random(seed: Int), XorWow)

/// Kotlin's `Random(seed: Int)`: the same XorWow generator, so the same seed draws the same numbers as
/// Android (`nextBoolean`, `nextInt(until)`).
struct KotlinRandom {
    private var x: Int32
    private var y: Int32
    private var z: Int32 = 0
    private var w: Int32 = 0
    private var v: Int32
    private var addend: Int32

    init(seed: Int32) {
        let seed2 = seed >> 31
        x = seed
        y = seed2
        v = ~seed
        addend = (seed << 10) ^ Int32(bitPattern: UInt32(bitPattern: seed2) >> 4)
        for _ in 0..<64 { _ = nextInt() }
    }

    mutating func nextInt() -> Int32 {
        var t = x
        t ^= Int32(bitPattern: UInt32(bitPattern: t) >> 2)
        x = y; y = z; z = w
        let v0 = v
        w = v0
        t = (t ^ (t << 1)) ^ v0 ^ (v0 << 4)
        v = t
        addend = addend &+ 362_437
        return t &+ addend
    }

    /// The top [bitCount] bits of the next draw (Kotlin `nextBits`); a draw is consumed even for 0.
    private mutating func nextBits(_ bitCount: Int) -> Int32 {
        let r = nextInt()
        guard bitCount > 0 else { return 0 }
        return Int32(bitPattern: UInt32(bitPattern: r) >> UInt32(32 - bitCount))
    }

    mutating func nextBoolean() -> Bool { nextBits(1) != 0 }

    /// Kotlin `nextInt(until)`: [0, until).
    mutating func nextInt(until n: Int) -> Int {
        precondition(n > 0)
        let n32 = Int32(n)
        if n32 & -n32 == n32 {
            return Int(nextBits(31 - n32.leadingZeroBitCount))
        }
        while true {
            let bits = Int32(bitPattern: UInt32(bitPattern: nextInt()) >> 1)
            let v = bits % n32
            // Kotlin: `while (bits - v + (n - 1) < 0)` with Int32 wrap-around.
            if bits &- v &+ (n32 &- 1) >= 0 { return Int(v) }
        }
    }
}

/// Java's `String.hashCode()` (UTF-16 code units), for seeds that match Android's.
func javaStringHash(_ s: String) -> Int32 {
    var h: Int32 = 0
    for u in s.utf16 { h = h &* 31 &+ Int32(u) }
    return h
}

// MARK: - Model

/// What the template needs of a card: a stable id and whether it is a signal (rating / note) card.
protocol JbiCard: Hashable {
    var stableId: String { get }
    /// A signal card (Android `expanded`): seated on a 1×2 / 2×1; every other card is a 1×1 cover.
    var expanded: Bool { get }
}

enum JbiPieceKind: Hashable, Sendable {
    case cover, tallSignal, wideSignal

    var columnSpan: Int { self == .wideSignal ? 2 : 1 }
    var rowSpan: Int { self == .tallSignal ? 2 : 1 }
    var isSignal: Bool { self != .cover }
}

/// A piece at its top-left cell; `row` counts from its lane's own top.
struct JbiPiece: Hashable, Sendable {
    var kind: JbiPieceKind
    var column: Int
    var row: Int

    var endColumn: Int { column + kind.columnSpan }
    var endRow: Int { row + kind.rowSpan }
}

/// `columns` lanes of `rows` cells each, every cell tiled exactly once. `offsets` (one per column, in row
/// pitches) is how far each column starts below the grid's top; a lying card's two columns share theirs.
/// `pieces` are in reading order: by where they sit on screen (row + offset), then column.
struct JbiTemplate: Hashable, Sendable {
    var columns: Int
    var rows: Int
    var offsets: [Float]
    var pieces: [JbiPiece]

    var covers: Int { pieces.filter { $0.kind == .cover }.count }

    /// Where [piece] sits, in row pitches from the grid's top.
    func top(of piece: JbiPiece) -> Float { Float(piece.row) + offsets[piece.column] }
}

/// A card seated on its piece.
struct JbiCell<Card: JbiCard>: Hashable {
    var card: Card
    var piece: JbiPiece
}

/// A template with its cells (same order as the template's pieces).
struct JbiLayout<Card: JbiCard>: Hashable {
    var template: JbiTemplate
    var cells: [JbiCell<Card>]
}

private let jbiMaxSignals = 2
private let jbiSignalCells = 2
private let jbiHalfRow: Float = 0.5
private let jbiDriftSteps: [Float] = [0, 0.25, 0.5]

/// Reading order: by where a piece sits on screen, then column.
private func jbiReading(_ offsets: [Float]) -> (JbiPiece, JbiPiece) -> Bool {
    { a, b in
        let ta = Float(a.row) + offsets[a.column], tb = Float(b.row) + offsets[b.column]
        return ta != tb ? ta < tb : a.column < b.column
    }
}

private extension Array {
    /// A stable sort (Swift's is not documented as one; Kotlin's `sortedWith` is).
    func stableSorted(by less: (Element, Element) -> Bool) -> [Element] {
        enumerated().sorted { a, b in
            if less(a.element, b.element) { return true }
            if less(b.element, a.element) { return false }
            return a.offset < b.offset
        }.map(\.element)
    }
}

// MARK: - Layout

/// Seats [cards] (signal cards = `expanded`, at most two; the rest covers in ranking order) on [columns]
/// lanes of up to [rows] cells, staggered and picked by [seed]. When the shelf is too short for [rows], one
/// row fewer, down to one; nil = too few covers even for that (the caller falls back to the plain grid).
func jbiLayout<Card: JbiCard>(cards: [Card], columns: Int, rows: Int, seed: Int32) -> JbiLayout<Card>? {
    guard columns >= 2, rows >= 1 else { return nil }
    let compacts = cards.filter { !$0.expanded }
    let signals = Array(cards.filter(\.expanded).prefix(jbiSparseSignalLimit(covers: compacts.count, columns: columns)))
    var r = rows
    while r >= 1 {
        let template = jbiTemplate(columns: columns, rows: r, signals: signals.count, seed: seed)
        if template.covers <= compacts.count {
            var covers = compacts.makeIterator()
            var signalCards = signals.makeIterator()
            let cells = template.pieces.map { piece in
                JbiCell(card: (piece.kind.isSignal ? signalCards.next() : covers.next())!, piece: piece)
            }
            return JbiLayout(template: template, cells: cells)
        }
        r -= 1
    }
    return nil
}

/// The template for [columns] lanes of [rows] cells with [signals] signal cards. Seeded, so the same
/// arguments always return the same template. Each card stands (1×2) or lies (2×1) by the seed — a standing
/// card needs two rows, so on one row both lie down; the two never share a lane, and stand apart when there
/// is room. Lane 0 always starts at the top, under the section title.
func jbiTemplate(columns: Int, rows: Int, signals: Int, seed: Int32) -> JbiTemplate {
    var random = KotlinRandom(seed: seed &* 1_000_003 &+ Int32(columns) &* 101 &+ Int32(rows) &* 13)
    let wanted: [JbiPieceKind] = (0..<min(max(signals, 0), jbiMaxSignals)).map { _ in
        rows >= 2 && random.nextBoolean() ? .tallSignal : .wideSignal
    }

    // Where the cards go (see the Android source for the owner's reasoning): a standing card's copy sits
    // beside its neighbours' next covers, so those lanes start level with it; a lying card's right
    // neighbour starts half a row lower. A lying card with no pair of columns free stands up.
    let wideCount = wanted.filter { $0 == .wideSignal }.count
    let standing = wanted.map { kind -> JbiPieceKind in
        kind == .wideSignal && columns < 2 * wideCount && rows >= 2 ? .tallSignal : kind
    }
    // On one row with too few columns for both lying cards, only the first goes in.
    let kinds = standing.reduce(0) { $0 + $1.columnSpan } > columns ? Array(standing.prefix(1)) : standing
    let placement = bestPlacement(columns: columns, kinds: kinds, random: &random)
    let pairStarts = placement.enumerated().filter { kinds[$0.offset] == .wideSignal }.map(\.element)
    let lanes = lanesOf(columns: columns, pairStarts: pairStarts)
    let spanning: [JbiPiece] = placement.enumerated().map { i, column in
        let kind = kinds[i]
        let row: Int
        if kind == .tallSignal {
            row = random.nextInt(until: rows - 1)
        } else if column + kind.columnSpan < columns {
            row = 0
        } else {
            row = random.nextInt(until: rows)
        }
        return JbiPiece(kind: kind, column: column, row: row)
    }
    let group = runsOf(lanes: lanes, cards: spanning)
    // Runs in left-to-right order (they are contiguous): groupBy keeps first-seen order, sorted by first lane.
    var runs: [[Int]] = []
    var runIndexOfGroup: [Int: Int] = [:]
    for lane in lanes.indices {
        if let r = runIndexOfGroup[group[lane]] {
            runs[r].append(lane)
        } else {
            runIndexOfGroup[group[lane]] = runs.count
            runs.append([lane])
        }
    }
    runs.sort { $0[0] < $1[0] }
    var runOfLane = [Int](repeating: 0, count: lanes.count)
    for (r, run) in runs.enumerated() { for lane in run { runOfLane[lane] = r } }
    // A lying card's run at 0 (unless it is itself a right neighbour), the run on its right half a row below.
    var required = [Float?](repeating: nil, count: runs.count)
    for card in spanning.filter({ $0.kind == .wideSignal }).stableSorted(by: { $0.column < $1.column }) {
        guard let laneIndex = lanes.firstIndex(where: { $0.contains(card.column) }) else { continue }
        let cardRun = runOfLane[laneIndex]
        guard laneIndex + 1 < runOfLane.count else { continue }
        let rightRun = runOfLane[laneIndex + 1]
        if rightRun == cardRun { continue }
        let top = required[cardRun] ?? 0
        required[cardRun] = top
        required[rightRun] = top + jbiHalfRow
    }
    let runOffsets = laneOffsets(required: required, random: &random)
    var offsets = [Float](repeating: 0, count: columns)
    for (r, run) in runs.enumerated() {
        for laneIndex in run { for c in lanes[laneIndex] { offsets[c] = runOffsets[r] } }
    }

    var taken = [[Bool]](repeating: [Bool](repeating: false, count: columns), count: rows)
    for piece in spanning {
        for r in piece.row..<piece.endRow { for c in piece.column..<piece.endColumn { taken[r][c] = true } }
    }
    var covers: [JbiPiece] = []
    for r in 0..<rows { for c in 0..<columns where !taken[r][c] { covers.append(JbiPiece(kind: .cover, column: c, row: r)) } }
    let pieces = (spanning + covers).stableSorted(by: jbiReading(offsets))
    return JbiTemplate(columns: columns, rows: rows, offsets: offsets, pieces: pieces)
}

/// Lanes left to right: a lying card's two columns (from [pairStarts]) are one.
private func lanesOf(columns: Int, pairStarts: [Int]) -> [Range<Int>] {
    var out: [Range<Int>] = []
    var c = 0
    while c < columns {
        if pairStarts.contains(c) {
            out.append(c..<(c + 2))
            c += 2
        } else {
            out.append(c..<(c + 1))
            c += 1
        }
    }
    return out
}

/// Run id per lane: a standing card's lane joins both neighbours (the lanes beside its copy); every other
/// lane is a run of its own.
private func runsOf(lanes: [Range<Int>], cards: [JbiPiece]) -> [Int] {
    var group = Array(lanes.indices)
    func join(_ a: Int, _ b: Int) {
        guard lanes.indices.contains(a), lanes.indices.contains(b) else { return }
        let from = group[b], to = group[a]
        for i in group.indices where group[i] == from { group[i] = to }
    }
    for card in cards where card.kind == .tallSignal {
        guard let laneIndex = lanes.firstIndex(where: { $0.contains(card.column) }) else { continue }
        join(laneIndex, laneIndex - 1)
        join(laneIndex, laneIndex + 1)
    }
    return group
}

/// The cards' columns ([kinds] in order): of every placement where they don't overlap, those keeping the
/// two a lane apart and then leaving the most runs of lanes to stagger, the seed picking among them.
private func bestPlacement(columns: Int, kinds: [JbiPieceKind], random: inout KotlinRandom) -> [Int] {
    guard !kinds.isEmpty else { return [] }
    func spots(_ kind: JbiPieceKind) -> ClosedRange<Int> { 0...(columns - kind.columnSpan) }
    var candidates: [[Int]] = []
    if kinds.count == 1 {
        for a in spots(kinds[0]) { candidates.append([a]) }
    } else {
        for a in spots(kinds[0]) {
            for b in spots(kinds[1]) {
                let aEnd = a + kinds[0].columnSpan, bEnd = b + kinds[1].columnSpan
                if a < bEnd && b < aEnd { continue }
                candidates.append([a, b])
            }
        }
    }
    // Score: the two cards a lane apart first, then the most runs left to stagger.
    let scored: [([Int], Int)] = candidates.map { placement in
        let pairs = placement.enumerated().filter { kinds[$0.offset] == .wideSignal }.map(\.element)
        let lanes = lanesOf(columns: columns, pairStarts: pairs)
        let cards = placement.enumerated().map { JbiPiece(kind: kinds[$0.offset], column: $0.element, row: 0) }
        let runs = Set(runsOf(lanes: lanes, cards: cards)).count
        let apart = cards.count < 2 || abs(
            lanes.firstIndex { $0.contains(cards[0].column) }! - lanes.firstIndex { $0.contains(cards[1].column) }!
        ) >= 2
        return (placement, (apart ? 1000 : 0) + runs)
    }
    let best = scored.map(\.1).max()!
    let top = scored.filter { $0.1 == best }.map(\.0)
    return top[random.nextInt(until: top.count)]
}

/// One offset per run of lanes, in row pitches; a [required] one always wins (the lying cards' runs and
/// their right neighbours). Otherwise the first is at 0 and each next one drifts to one of {0, ¼, ½},
/// never its left neighbour's nor a required right neighbour's.
private func laneOffsets(required: [Float?], random: inout KotlinRandom) -> [Float] {
    var out: [Float] = []
    for i in required.indices {
        if let fixed = required[i] {
            out.append(fixed)
        } else if i == 0 {
            out.append(0)
        } else {
            let next: Float? = i + 1 < required.count ? required[i + 1] : nil
            var choices = jbiDriftSteps.filter { $0 != out.last && $0 != next }
            if choices.isEmpty { choices = jbiDriftSteps.filter { $0 != out.last } }
            out.append(choices[random.nextInt(until: choices.count)])
        }
    }
    return out
}

/// Signal cards a shelf of [covers] covers takes on [columns] lanes (Android 8f90026d, owner 2026-11-12: a
/// small library's two rating cards took the whole row and left no cover): while the covers can't fill one
/// row, one card; else the usual two.
func jbiSparseSignalLimit(covers: Int, columns: Int) -> Int { covers < columns ? 1 : jbiMaxSignals }

/// The seed for a shelf: its leading cover, so a signal card arriving doesn't re-roll the layout
/// (Android `activityLayoutSeed`: `key.hashCode() and Int.MAX_VALUE`).
func jbiLayoutSeed<Card: JbiCard>(_ cards: [Card]) -> Int32 {
    javaStringHash(cards.first { !$0.expanded }?.stableId ?? "") & Int32.max
}

/// The packed shelf of [cells] cells (the phone's 3 × 4 = 12): up to [maxSignals] wide cards first, then
/// covers for the cells left (Android `trimToPhoneShelf`).
func trimToPhoneShelf<Card: JbiCard>(_ cards: [Card], cells: Int = 12, maxSignals: Int = 2) -> [Card] {
    let wide = Array(cards.filter(\.expanded).prefix(max(0, maxSignals)))
    let compacts = cards.filter { !$0.expanded }
    return wide + compacts.prefix(max(0, cells - wide.count * jbiSignalCells))
}

/// The plain column grid's rows (Android `packWidgetRows`): each wide card with up to columns − 2 covers,
/// alternating sides, then the rest of the covers [columns] to a row.
func jbiPackRows<Card: JbiCard>(_ cards: [Card], columns: Int) -> [[Card]] {
    var wide = cards.filter(\.expanded)[...]
    var compact = cards.filter { !$0.expanded }[...]
    var rows: [[Card]] = []
    var pairIndex = 0
    while let big = wide.popFirst() {
        var fill: [Card] = []
        while fill.count < columns - 2, let c = compact.popFirst() { fill.append(c) }
        if fill.isEmpty {
            rows.append([big])
        } else if pairIndex % 2 == 0 {
            rows.append([big] + fill)
        } else {
            rows.append(fill + [big])
        }
        pairIndex += 1
    }
    while !compact.isEmpty {
        let n = min(columns, compact.count)
        rows.append(Array(compact.prefix(n)))
        compact = compact.dropFirst(n)
    }
    return rows
}

// MARK: - Row presets (Android jbiPresetLayout)

/// [layout] at [rows] rows, keeping every card it can where it is, so neighbouring presets interpolate card
/// by card. More rows append whole rows of covers (and signal cards of their own when [cards] has more);
/// fewer cut the bottom rows, a signal card past the new bottom moves up in its own lane.
func jbiPresetLayout<Card: JbiCard>(_ layout: JbiLayout<Card>, cards: [Card], rows: Int) -> JbiLayout<Card> {
    if rows == layout.template.rows { return layout }
    if rows > layout.template.rows { return growJbiLayout(layout, cards: cards, rows: rows) }
    return shrinkJbiLayout(layout, rows: max(rows, 1))
}

/// Signal cards [addedRows] rows of [columns] lanes bring along: one for every 6 cells they add, rounded to
/// the nearest, at least one.
func jbiGrowSignals(addedRows: Int, columns: Int) -> Int {
    max(1, (addedRows * columns + jbiCellsPerExtraSignal / 2) / jbiCellsPerExtraSignal)
}

private let jbiCellsPerExtraSignal = 6

private func growJbiLayout<Card: JbiCard>(_ layout: JbiLayout<Card>, cards: [Card], rows: Int) -> JbiLayout<Card> {
    let template = layout.template
    let columns = template.columns
    let oldRows = template.rows
    let seated = Set(layout.cells.map(\.card.stableId))
    let spare = cards.filter { !$0.expanded && !seated.contains($0.stableId) }
    let spareSignals = cards.filter { $0.expanded && !seated.contains($0.stableId) }
    // As many whole rows as the shelf fills: each standing card covers one new cell and lends its old
    // cover another, so it takes no extra cover.
    var addedRows = rows - oldRows
    var standing: [Int] = []
    while addedRows > 0 {
        let wanted = min(spareSignals.count, jbiGrowSignals(addedRows: addedRows, columns: columns))
        standing = growSignalColumns(layout, count: wanted)
        if spare.count >= addedRows * columns - 2 * standing.count { break }
        addedRows -= 1
    }
    if addedRows <= 0 { return layout }
    let reading = jbiReading(template.offsets)
    let bottom = oldRows - 1
    // The covers the new cards stand on, in the old reading order: they move down first.
    let lent = layout.cells
        .filter { $0.piece.kind == .cover && $0.piece.row == bottom && standing.contains($0.piece.column) }
        .stableSorted { reading($0.piece, $1.piece) }
    let kept = layout.cells.filter { cell in !lent.contains { $0.card.stableId == cell.card.stableId } }
    let signalCells: [JbiCell<Card>] = standing.sorted().enumerated().map { i, column in
        JbiCell(card: spareSignals[i], piece: JbiPiece(kind: .tallSignal, column: column, row: bottom))
    }
    var newCovers: [JbiPiece] = []
    for r in oldRows..<(oldRows + addedRows) {
        for c in 0..<columns {
            if r == oldRows && standing.contains(c) { continue }
            newCovers.append(JbiPiece(kind: .cover, column: c, row: r))
        }
    }
    newCovers = newCovers.stableSorted(by: reading)
    var next = (lent.map(\.card) + spare).makeIterator()
    let cells = (kept + signalCells + newCovers.map { JbiCell(card: next.next()!, piece: $0) })
        .stableSorted { reading($0.piece, $1.piece) }
    var grown = template
    grown.rows = oldRows + addedRows
    grown.pieces = cells.map(\.piece)
    return JbiLayout(template: grown, cells: cells)
}

/// Up to [count] lanes for new standing cards over [layout]'s bottom row: a lane whose bottom cell is a
/// cover, never beside another signal card on that row or another new one, farthest from the signals already
/// there first (ties by a seed from the template). Fewer when the lanes run out.
private func growSignalColumns<Card: JbiCard>(_ layout: JbiLayout<Card>, count: Int) -> [Int] {
    if count <= 0 { return [] }
    let template = layout.template
    let bottom = template.rows - 1
    func columnsOf(_ cell: JbiCell<Card>) -> Range<Int> { cell.piece.column..<cell.piece.endColumn }
    let signalColumns = Set(layout.cells.filter { $0.piece.kind.isSignal }.flatMap(columnsOf))
    let bottomSignal = Set(layout.cells
        .filter { $0.piece.kind.isSignal && ($0.piece.row..<$0.piece.endRow).contains(bottom) }
        .flatMap(columnsOf))
    let coverOnBottom = Set(layout.cells.filter { $0.piece.kind == .cover && $0.piece.row == bottom }.map(\.piece.column))
    var random = KotlinRandom(seed: Int32(template.columns) &* 7919 &+ Int32(template.rows) &* 104_729 &+ Int32(template.pieces.count))
    var picked: [Int] = []
    for _ in 0..<count {
        let taken = signalColumns.union(picked)
        let open = (0..<template.columns).filter { c in
            coverOnBottom.contains(c) && !picked.contains(c)
                && !bottomSignal.contains(c - 1) && !bottomSignal.contains(c + 1)
                && !picked.contains(c - 1) && !picked.contains(c + 1)
        }
        if open.isEmpty { return picked }
        func distance(_ c: Int) -> Int { taken.map { abs($0 - c) }.min() ?? template.columns }
        let best = open.map(distance).max()!
        let ties = open.filter { distance($0) == best }
        picked.append(ties[random.nextInt(until: ties.count)])
    }
    return picked
}

private func shrinkJbiLayout<Card: JbiCard>(_ layout: JbiLayout<Card>, rows: Int) -> JbiLayout<Card> {
    let template = layout.template
    let columns = template.columns
    var taken = [[Bool]](repeating: [Bool](repeating: false, count: columns), count: rows)
    func fits(_ p: JbiPiece) -> Bool {
        guard p.column >= 0, p.endColumn <= columns, p.row >= 0, p.endRow <= rows else { return false }
        for r in p.row..<p.endRow { for c in p.column..<p.endColumn where taken[r][c] { return false } }
        return true
    }
    func take(_ p: JbiPiece) {
        for r in p.row..<p.endRow { for c in p.column..<p.endColumn { taken[r][c] = true } }
    }

    // Signals by card rank (the incoming order), at most the limit for this size.
    let signals = layout.cells.filter { $0.piece.kind.isSignal }
    let limit = HomeRowPresets.jbiSignalLimit(rows: rows, cells: rows * columns)
    var kept: [JbiCell<Card>] = []
    for cell in signals.prefix(limit) {
        let piece = cell.piece
        let candidates: [JbiPiece]
        if piece.endRow <= rows {
            candidates = [piece]
        } else if piece.kind == .tallSignal && rows >= 2 {
            candidates = [JbiPiece(kind: piece.kind, column: piece.column, row: rows - 2)]
        } else if piece.kind == .wideSignal {
            candidates = [JbiPiece(kind: piece.kind, column: piece.column, row: rows - 1)]
        } else {
            // A standing card on one row lies down, across its right neighbour or else its left.
            candidates = [
                JbiPiece(kind: .wideSignal, column: piece.column, row: rows - 1),
                JbiPiece(kind: .wideSignal, column: piece.column - 1, row: rows - 1),
            ]
        }
        guard let placed = candidates.first(where: fits) else { continue }
        take(placed)
        kept.append(JbiCell(card: cell.card, piece: placed))
    }

    let oldCovers = layout.cells.filter { $0.piece.kind == .cover }
    var coverAt: [[Int]: JbiCell<Card>] = [:]
    for cell in oldCovers { coverAt[[cell.piece.column, cell.piece.row]] = cell }
    var keptCovers: [JbiCell<Card>] = []
    var open: [JbiPiece] = []
    for r in 0..<rows {
        for c in 0..<columns where !taken[r][c] {
            if let old = coverAt[[c, r]] { keptCovers.append(old) } else { open.append(JbiPiece(kind: .cover, column: c, row: r)) }
        }
    }
    let keptIds = Set(keptCovers.map(\.card.stableId))
    // Displaced covers, best first (the old reading order), fill the open cells in the new one.
    var displaced = oldCovers.filter { !keptIds.contains($0.card.stableId) }.makeIterator()
    let reading = jbiReading(template.offsets)
    let refilled: [JbiCell<Card>] = open.stableSorted(by: reading).compactMap { piece in
        displaced.next().map { JbiCell(card: $0.card, piece: piece) }
    }
    let cells = (kept + keptCovers + refilled).stableSorted { reading($0.piece, $1.piece) }
    return JbiLayout(
        template: JbiTemplate(columns: columns, rows: rows, offsets: template.offsets, pieces: cells.map(\.piece)),
        cells: cells
    )
}

// MARK: - Ladder

/// One Jump Back In composition on a tablet: a seated template, or the plain column grid's rows when the
/// shelf is too short for any template (Android `JbiPresetLayout`).
enum JbiPresetLayout<Card: JbiCard>: Hashable {
    case template(JbiLayout<Card>)
    case packed([[Card]])
}

/// The tablet ladder over [cards] on [columns] lanes (Android `jbiRowLadder` / `jbiTemplateLadder`): the L
/// template grown or shrunk to S = L−2, M = L−1, XL = L+1 rows (min 1), adjacent identical presets merged
/// into one stop; the plain packed shelf (one stop) when no template fits.
func jbiTabletLadder<Card: JbiCard>(cards: [Card], columns: Int) -> [HomeRowStop<JbiPresetLayout<Card>>] {
    let lRows = HomeDensity.jbiRows(columns: columns, preset: .l)
    guard let base = jbiLayout(cards: cards, columns: columns, rows: lRows, seed: jbiLayoutSeed(cards)) else {
        let packed = jbiPackRows(trimToPhoneShelf(cards, cells: 12, maxSignals: min(HomeRowPresets.jbiSignalLimit(rows: 4, cells: 12),
                                                         jbiSparseSignalLimit(covers: cards.filter { !$0.expanded }.count, columns: columns))),
                                 columns: columns)
        return homeRowLadder { _ in (JbiPresetLayout.packed(packed), packed.count) }
    }
    let m = jbiPresetLayout(base, cards: cards, rows: HomeDensity.jbiRows(columns: columns, preset: .m))
    let s = jbiPresetLayout(m, cards: cards, rows: HomeDensity.jbiRows(columns: columns, preset: .s))
    let xl = jbiPresetLayout(base, cards: cards, rows: HomeDensity.jbiRows(columns: columns, preset: .xl))
    return homeRowLadder { preset in
        let layout: JbiLayout<Card>
        switch preset {
        case .s: layout = s
        case .m: layout = m
        case .l: layout = base
        case .xl: layout = xl
        }
        return (JbiPresetLayout.template(layout), layout.template.rows)
    }
}
