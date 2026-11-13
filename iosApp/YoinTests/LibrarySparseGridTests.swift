import XCTest
@testable import Yoin

/// LibraryGrid.sparseColumns: a short Library on a wide screen grows its cells (Android LibrarySparseGridTest,
/// 8f90026d — the same cases and numbers).
final class LibrarySparseGridTests: XCTestCase {
    func test_should_growCells_when_fewItemsOnALandscapeTablet() {
        XCTAssertEqual(LibraryGrid.sparseColumns(itemCount: 8, baseColumns: 11, width: 1248, height: 640), 6)
    }

    func test_should_keepUsualCells_when_theShelfFillsThePage() {
        XCTAssertEqual(LibraryGrid.sparseColumns(itemCount: 200, baseColumns: 11, width: 1248, height: 640), 11)
    }

    func test_should_takeFewestColumnsThatStillFit_when_inBetween() {
        let columns = LibraryGrid.sparseColumns(itemCount: 40, baseColumns: 11, width: 1248, height: 1000)
        XCTAssertTrue((7...11).contains(columns))
        let cell = (1248 - 12 * CGFloat(columns - 1)) / CGFloat(columns)
        let rows = (40 + columns - 1) / columns
        XCTAssertLessThanOrEqual((cell + 44) * CGFloat(rows) + 16 * CGFloat(rows - 1), 1000)
    }

    func test_should_neverGoBelowThreeOrHalf_when_veryFewItems() {
        XCTAssertEqual(LibraryGrid.sparseColumns(itemCount: 1, baseColumns: 5, width: 568, height: 900), 3)
        XCTAssertEqual(LibraryGrid.sparseColumns(itemCount: 1, baseColumns: 11, width: 1248, height: 640), 6)
        XCTAssertEqual(LibraryGrid.sparseColumns(itemCount: 1, baseColumns: 3, width: 360, height: 640), 3)
    }

    func test_should_countTheSortRow_when_itShows() {
        // iPad 13" landscape library (1376 wide, 1312 grid → 13 base columns), 15 items, a ~880 viewport.
        let n = LibraryGrid.sparseColumns(itemCount: 15, baseColumns: 13, width: 1344, height: 880, leading: 48)
        XCTAssertTrue((7...13).contains(n))
    }
}
