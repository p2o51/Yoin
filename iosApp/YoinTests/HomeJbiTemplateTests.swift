import XCTest
@testable import Yoin

/// The lane-staggered Jump Back In (Android HomeJbiTemplateTest, HomeJbiTemplate.kt).
final class HomeJbiTemplateTests: XCTestCase {
    private struct Card: JbiCard {
        var stableId: String
        var expanded = false
    }

    private func cover(_ i: Int) -> Card { Card(stableId: "c\(i)") }
    private func signal(_ i: Int) -> Card { Card(stableId: "s\(i)", expanded: true) }

    private func shelf(signals: Int, covers: Int = 24) -> [Card] {
        (0..<signals).map { signal($0 + 1) } + (0..<covers).map { cover($0 + 1) }
    }

    private func rows(_ columns: Int) -> Int { HomeDensity.jbiRows(columns: columns, preset: .l) }

    private func assertTiles(_ t: JbiTemplate, file: StaticString = #filePath, line: UInt = #line) {
        var seen = [[Int]](repeating: [Int](repeating: 0, count: t.columns), count: t.rows)
        for p in t.pieces { for r in p.row..<p.endRow { for c in p.column..<p.endColumn { seen[r][c] += 1 } } }
        for r in 0..<t.rows { for c in 0..<t.columns { XCTAssertEqual(seen[r][c], 1, "cell \(c),\(r)", file: file, line: line) } }
        // A lying card's two columns are one lane: one offset.
        for p in t.pieces where p.kind == .wideSignal {
            XCTAssertEqual(t.offsets[p.column], t.offsets[p.column + 1], file: file, line: line)
        }
        // Reading order: by where they sit on screen, then column.
        let order = t.pieces.map { (t.top(of: $0), $0.column) }
        let sorted = order.sorted { $0.0 != $1.0 ? $0.0 < $1.0 : $0.1 < $1.1 }
        XCTAssertTrue(zip(order, sorted).allSatisfy { $0 == $1 }, file: file, line: line)
    }

    // MARK: RNG

    /// Draws recorded from the real `kotlin.random.Random(seed)` (kotlin-stdlib 2.2.10 on a JVM): four
    /// `nextInt(1000)`, four `nextBoolean`, three `nextInt(4)`, three `nextInt(1)`, one `nextInt(7)`.
    func test_should_matchKotlinXorWow_when_seeded() {
        let reference: [(Int32, [Int], [Bool], [Int], [Int], Int)] = [
            (42, [333, 440, 941, 302], [false, false, true, true], [0, 0, 2], [0, 0, 0], 3),
            (-7, [175, 263, 657, 941], [true, true, false, true], [3, 2, 1], [0, 0, 0], 4),
            (123_456_789, [630, 550, 934, 544], [false, true, true, true], [1, 3, 3], [0, 0, 0], 0),
            (2_147_483_647, [618, 657, 199, 190], [true, false, true, true], [2, 2, 1], [0, 0, 0], 5),
        ]
        for (seed, thousand, bools, fours, ones, seven) in reference {
            var r = KotlinRandom(seed: seed)
            XCTAssertEqual((0..<4).map { _ in r.nextInt(until: 1000) }, thousand, "seed \(seed)")
            XCTAssertEqual((0..<4).map { _ in r.nextBoolean() }, bools, "seed \(seed)")
            XCTAssertEqual((0..<3).map { _ in r.nextInt(until: 4) }, fours, "seed \(seed)")
            XCTAssertEqual((0..<3).map { _ in r.nextInt(until: 1) }, ones, "seed \(seed)")
            XCTAssertEqual(r.nextInt(until: 7), seven, "seed \(seed)")
        }
    }

    func test_should_matchJavaHashCode_when_seedingFromAnId() {
        XCTAssertEqual(javaStringHash("abc"), 96354)
        XCTAssertEqual(javaStringHash(""), 0)
        XCTAssertEqual(javaStringHash("hello world"), 1_794_106_052)
    }

    // MARK: Template

    func test_should_tileAndSeatEveryCardOnce_when_anyWidthSignalsStyleAndSeed() throws {
        for columns in 3...10 {
            let rows = self.rows(columns)
            for signals in 0...2 {
                for seed in 0..<120 {
                    let layout = try XCTUnwrap(jbiLayout(cards: shelf(signals: signals), columns: columns, rows: rows, seed: Int32(seed)),
                                               "K=\(columns) s=\(signals) seed=\(seed)")
                    XCTAssertEqual(layout.template.rows, rows)
                    assertTiles(layout.template)
                    XCTAssertEqual(layout.template.pieces, layout.cells.map(\.piece))
                    let ids = layout.cells.map(\.card.stableId)
                    XCTAssertEqual(ids.count, Set(ids).count)
                    XCTAssertEqual(layout.cells.filter { $0.piece.kind.isSignal }.count, signals)
                    XCTAssertTrue(layout.cells.filter { $0.piece.kind.isSignal }.allSatisfy(\.card.expanded))
                    XCTAssertTrue(layout.cells.filter { !$0.piece.kind.isSignal }.allSatisfy { !$0.card.expanded })
                }
            }
        }
    }

    func test_should_levelBesideStandingCopyAndDropBesideLyingCopy_when_drifting() {
        for columns in 3...10 {
            for signals in 0...2 {
                for seed in 0..<150 {
                    let t = jbiTemplate(columns: columns, rows: rows(columns), signals: signals, seed: Int32(seed))
                    let at = "K=\(columns) s=\(signals) seed=\(seed): \(t)"
                    let offsets = t.offsets
                    XCTAssertEqual(offsets[0], 0, at)
                    var level = Set<Int>(), mayLevel = Set<Int>(), dropped = Set<Int>()
                    for p in t.pieces {
                        switch p.kind {
                        case .tallSignal:
                            if p.column > 0 { level.insert(p.column - 1) }
                            if p.column < columns - 1 { level.insert(p.column) }
                        case .wideSignal:
                            level.insert(p.column)
                            if p.column > 0 { mayLevel.insert(p.column - 1) }
                            if p.endColumn < columns { dropped.insert(p.endColumn - 1) }
                        case .cover: break
                        }
                    }
                    for p in t.pieces where p.kind == .wideSignal && p.endColumn < columns {
                        let right = p.endColumn
                        if dropped.contains(right - 1) && !level.contains(right - 1) {
                            XCTAssertEqual(offsets[right], offsets[p.column] + 0.5, at)
                            XCTAssertEqual(p.row, 0, at)
                        }
                    }
                    for c in level { XCTAssertEqual(offsets[c], offsets[c + 1], at) }
                    for c in 0..<(columns - 1) where !level.contains(c) && !mayLevel.contains(c) {
                        XCTAssertNotEqual(offsets[c], offsets[c + 1], "\(at) | \(c)")
                    }
                }
            }
        }
    }

    func test_should_keepTheTwoCardsInDifferentLanes_when_bothPresent() {
        for columns in 3...10 {
            for seed in 0..<150 {
                let t = jbiTemplate(columns: columns, rows: rows(columns), signals: 2, seed: Int32(seed))
                let signals = t.pieces.filter { $0.kind.isSignal }
                XCTAssertEqual(signals.count, 2)
                let (a, b) = (signals[0], signals[1])
                XCTAssertTrue(a.endColumn <= b.column || b.endColumn <= a.column, "K=\(columns) seed=\(seed)")
                // A lane apart wherever the width allows (their copies never side by side).
                if columns >= 5 {
                    XCTAssertTrue(a.endColumn < b.column || b.endColumn < a.column, "K=\(columns) seed=\(seed): \(t)")
                }
            }
        }
    }

    func test_should_layCardsDown_when_oneRow() {
        let t = jbiTemplate(columns: 9, rows: 1, signals: 2, seed: 4)
        XCTAssertTrue(t.pieces.filter { $0.kind.isSignal }.allSatisfy { $0.kind == .wideSignal })
    }

    func test_should_returnSameLayout_when_sameInputs() {
        let cards = shelf(signals: 2)
        for columns in 3...10 {
            let a = jbiLayout(cards: cards, columns: columns, rows: rows(columns), seed: 42)
            let b = jbiLayout(cards: Array(cards), columns: columns, rows: rows(columns), seed: 42)
            XCTAssertEqual(a, b)
        }
    }

    func test_should_varyTemplates_when_seedsDiffer() {
        for columns in 4...10 {
            let distinct = Set((0..<40).map { jbiTemplate(columns: columns, rows: rows(columns), signals: 2, seed: Int32($0)) })
            XCTAssertGreaterThanOrEqual(distinct.count, 8, "K=\(columns): \(distinct.count)")
        }
    }

    func test_should_keepSeed_when_signalCardArrives() {
        let covers = shelf(signals: 0)
        XCTAssertEqual(jbiLayoutSeed(covers), jbiLayoutSeed([signal(1), signal(2)] + covers))
    }

    func test_should_seatCoversInRankingOrder_when_readingOrder() throws {
        let layout = try XCTUnwrap(jbiLayout(cards: shelf(signals: 2), columns: 9, rows: 2, seed: 3))
        let seated = layout.cells.filter { $0.piece.kind == .cover }.map(\.card)
        XCTAssertEqual(Array(shelf(signals: 2).filter { !$0.expanded }.prefix(seated.count)), seated)
    }

    func test_should_dropARow_when_shelfTooShort() throws {
        // 5 lanes × 3 with both cards takes 11 covers; 8 only fill two rows.
        let layout = try XCTUnwrap(jbiLayout(cards: shelf(signals: 2, covers: 8), columns: 5, rows: 3, seed: 1))
        XCTAssertEqual(layout.template.rows, 2)
        assertTiles(layout.template)
    }

    func test_should_returnNil_when_notEvenOneRowFits() {
        XCTAssertNil(jbiLayout(cards: shelf(signals: 0, covers: 3), columns: 9, rows: 2, seed: 1))
    }

    func test_should_keepPhoneShelfAtTwelveCells_when_trimming() {
        func cells(_ cards: [Card]) -> Int { cards.reduce(0) { $0 + ($1.expanded ? 2 : 1) } }
        XCTAssertEqual(cells(trimToPhoneShelf(shelf(signals: 2))), 12)
        XCTAssertEqual(cells(trimToPhoneShelf(shelf(signals: 1))), 12)
        XCTAssertEqual(trimToPhoneShelf(shelf(signals: 0)).count, 12)
        XCTAssertEqual(trimToPhoneShelf(shelf(signals: 0, covers: 5)).count, 5)
        XCTAssertEqual(trimToPhoneShelf(shelf(signals: 2)).prefix(3).map(\.stableId), ["s1", "s2", "c1"])
    }

    // MARK: Presets

    func test_should_deriveToTheTabletLadder_when_templated() throws {
        for columns in 3...10 {
            let cards = shelf(signals: 2, covers: 40)
            let stops = jbiTabletLadder(cards: cards, columns: columns)
            let l = rows(columns)
            // S = L−2, M = L−1, XL = L+1 (min 1); identical neighbours merge into one stop.
            let want = [max(1, l - 2), max(1, l - 1), l, l + 1]
            var expected: [Int] = []
            for r in want where expected.last != r { expected.append(r) }
            XCTAssertEqual(stops.map(\.rowCount), expected, "K=\(columns)")
            XCTAssertEqual(stops.flatMap(\.presets), HomeRowPreset.allCases)
        }
    }

    func test_should_mergeSAndM_when_twoRowPane() {
        let stops = jbiTabletLadder(cards: shelf(signals: 2, covers: 40), columns: 7)
        XCTAssertEqual(stops.map(\.presets), [[.s, .m], [.l], [.xl]])
    }

    func test_should_tileEveryPreset_when_growingAndShrinking() throws {
        for columns in 3...10 {
            let cards = shelf(signals: 2, covers: 40)
            for seed in 0..<40 {
                let base = try XCTUnwrap(jbiLayout(cards: cards, columns: columns, rows: rows(columns), seed: Int32(seed)))
                for target in 1...(rows(columns) + 2) {
                    let l = jbiPresetLayout(base, cards: cards, rows: target)
                    let at = "K=\(columns) seed=\(seed) rows=\(target)"
                    XCTAssertEqual(l.template.pieces, l.cells.map(\.piece), at)
                    let ids = l.cells.map(\.card.stableId)
                    XCTAssertEqual(ids.count, Set(ids).count, at)
                    // Every cell of the grid is covered once (a shrunk layout may leave a cell with no spare card).
                    var seen = [[Int]](repeating: [Int](repeating: 0, count: columns), count: l.template.rows)
                    for p in l.template.pieces {
                        XCTAssertLessThanOrEqual(p.endRow, l.template.rows, at)
                        for r in p.row..<p.endRow { for c in p.column..<p.endColumn { seen[r][c] += 1 } }
                    }
                    XCTAssertTrue(seen.allSatisfy { $0.allSatisfy { $0 <= 1 } }, at)
                }
            }
        }
    }

    func test_should_fallBackToPackedShelf_when_tooShortForAnyTemplate() {
        let stops = jbiTabletLadder(cards: shelf(signals: 1, covers: 3), columns: 9)
        XCTAssertEqual(stops.count, 1)
        guard case let .packed(rows) = stops[0].payload else { return XCTFail("expected the packed shelf") }
        XCTAssertEqual(rows.flatMap { $0 }.count, 4)
    }

    func test_should_packWideCardsWithCovers_when_plainColumnGrid() {
        let rows = jbiPackRows(shelf(signals: 2, covers: 6), columns: 4)
        XCTAssertEqual(rows.map { $0.map(\.stableId) }, [["s1", "c1", "c2"], ["c3", "c4", "s2"], ["c5", "c6"]])
    }
}
