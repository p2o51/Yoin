import XCTest
@testable import Yoin

/// All Memories: the grid's squares rising into the top bar (Memories/MemoryGridLift.swift, Android MemoryGridLift.kt).
@MainActor
final class MemoryGridLiftTests: XCTestCase {
    private let account = LedgerAccount(provider: "subsonic", scope: "https://a|me")
    private func album(_ id: String) -> LedgerAlbum {
        LedgerAlbum(account: account, albumId: id, name: id, artist: "", coverKey: nil)
    }
    private func cell(_ ids: [String], future: Bool = false) -> MemoryGridCell {
        MemoryGridCell(start: Date(timeIntervalSince1970: 0), end: Date(timeIntervalSince1970: 0),
                       marks: ids.map { MemoryGridMark(album: album($0), wrote: false, count: 1) },
                       writings: 0, entries: ids.count, future: future)
    }

    func test_should_liftEveryAlbumOfAMonth_when_theGridCountsMonths() {
        let grid = MemoryGrid(scale: .month, cells: [cell(["a", "b"]), cell([]), cell(["c"])])
        let pixels = MemoryGridLiftMath.pixels(grid)
        XCTAssertEqual(pixels.map(\.index), [0, 0, 2])
        XCTAssertEqual(pixels.map(\.mark), [0, 1, 0])
    }

    func test_should_liftOnlyKeptPastCells_when_theGridCountsWeeks() {
        let grid = MemoryGrid(scale: .week, cells: [cell(["a"]), cell([]), cell(["b", "c"]), cell(["d"], future: true)])
        let pixels = MemoryGridLiftMath.pixels(grid)
        XCTAssertEqual(pixels.map(\.index), [0, 2])
        XCTAssertEqual(pixels.map(\.mark), [-1, -1])
    }

    func test_should_useTwoRows_when_fewSquaresFillTheBar() {
        let m = MemoryGridLiftMath.mosaic(count: 5, width: 400, height: 120)
        XCTAssertEqual(m.rows, 2)
        XCTAssertEqual(m.side, 60)
        XCTAssertEqual(m.places, 14) // ceil(400 / 60) = 7 columns
    }

    func test_should_addRowsUntilEverySquareHasAPlace_when_manySquares() {
        // 3 rows: side 40, 10 columns = 30 places < 40; 4 rows: side 30, 14 columns = 56.
        let m = MemoryGridLiftMath.mosaic(count: 40, width: 400, height: 120)
        XCTAssertEqual(m.rows, 4)
        XCTAssertEqual(m.places, 56)
    }

    func test_should_stopAtEightRows_when_squaresWouldBeSpecks() {
        let m = MemoryGridLiftMath.mosaic(count: 10_000, width: 400, height: 120)
        XCTAssertEqual(m.rows, 8)
        XCTAssertEqual(m.places, 8 * 27)
    }

    func test_should_shareNeighbouringPlaces_when_morePlacesThanSquares() {
        let owners = (0..<6).map { MemoryGridLiftMath.pixel(forPlace: $0, places: 6, count: 3) }
        XCTAssertEqual(owners, [0, 0, 1, 1, 2, 2])
    }

    func test_should_runDownEachColumnThenAcross_when_placingTheBar() {
        let m = BarMosaic(rows: 2, side: 50, places: 8)
        XCTAssertEqual(MemoryGridLiftMath.target(1, m, width: 200, rtl: false).origin, CGPoint(x: 0, y: 50))
        XCTAssertEqual(MemoryGridLiftMath.target(2, m, width: 200, rtl: false).origin, CGPoint(x: 50, y: 0))
        XCTAssertEqual(MemoryGridLiftMath.target(0, m, width: 200, rtl: true).origin, CGPoint(x: 150, y: 0))
    }

    func test_should_riseOldestFirst_when_theGridGoesUnderTheBar() {
        XCTAssertEqual(MemoryGridLiftMath.flight(0, of: 10, at: 0.02), 0)
        XCTAssertGreaterThan(MemoryGridLiftMath.flight(0, of: 10, at: 0.4), MemoryGridLiftMath.flight(9, of: 10, at: 0.4))
        XCTAssertEqual(MemoryGridLiftMath.flight(9, of: 10, at: 1), 1)
    }

    func test_should_followTheScroll_when_theGridPassesUnderTheBar() {
        let lift = MemoryGridLift()
        lift.grid = CGRect(x: 16, y: 200, width: 300, height: 100)
        // At rest the content sits below the bar (offset = -inset).
        lift.scrolled(y: -120, insetTop: 120)
        XCTAssertEqual(lift.progress, 0)
        XCTAssertFalse(lift.lifting)
        // The grid's top meets the bar's bottom at y 80, its bottom at 180.
        lift.scrolled(y: 130, insetTop: 120)
        XCTAssertEqual(lift.progress, 0.5, accuracy: 0.0001)
        XCTAssertTrue(lift.lifting)
        lift.scrolled(y: 400, insetTop: 120)
        XCTAssertEqual(lift.progress, 1)
        // Back down: the squares come home and the real grid returns.
        lift.scrolled(y: 0, insetTop: 120)
        XCTAssertEqual(lift.progress, 0)
        XCTAssertFalse(lift.lifting)
    }

    func test_should_findEachSquareInTheGrid_when_theGridIsPlaced() {
        let lift = MemoryGridLift()
        lift.wall = .init(frame: CGRect(x: 10, y: 100, width: 128, height: 20), columns: 13, rows: 2, columnMajor: false, gap: 5)
        // side = (128 - 60) / 13 ≈ 5.23; index 14 = column 1, row 1.
        let pixel = LiftPixel(index: 14, cell: cell(["a"]), mark: -1)
        let r = try! XCTUnwrap(lift.rect(of: pixel))
        let side = (128.0 - 60) / 13
        XCTAssertEqual(r.minX, 10 + side + 5, accuracy: 0.001)
        XCTAssertEqual(r.minY, 100 + side + 5, accuracy: 0.001)
        lift.quads[3] = CGRect(x: 0, y: 0, width: 42, height: 42)
        let q = try! XCTUnwrap(lift.rect(of: LiftPixel(index: 3, cell: cell(["a", "b", "c", "d"]), mark: 3)))
        XCTAssertEqual(q, CGRect(x: 22, y: 22, width: 20, height: 20))
        XCTAssertNil(lift.rect(of: LiftPixel(index: 4, cell: cell(["a"]), mark: 0)))
    }
}
