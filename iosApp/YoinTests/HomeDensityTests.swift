import XCTest
@testable import Yoin

/// Home's density by width (Home/HomeDensity.swift, Android FeedUnits / HomeFeedDensity).
final class HomeDensityTests: XCTestCase {
    func test_should_keepThePhoneLayout_when_theContainerIsPhoneWide() {
        let d = HomeDensity(width: 402, height: 874)
        XCTAssertEqual(d.margin, 16)
        XCTAssertEqual(d.units, 2)
        XCTAssertFalse(d.isTablet)
    }

    func test_should_capAndCentre_when_mediumWide() {
        let d = HomeDensity(width: 834, height: 1194)
        XCTAssertFalse(d.wide)
        XCTAssertEqual(d.content, 688)
        XCTAssertEqual(d.margin, 73)
        XCTAssertEqual(d.units, 4)
        XCTAssertEqual(d.coverColumns, 5)
    }

    func test_should_addUnitsNotSize_when_wide() {
        let d = HomeDensity(width: 1032, height: 1376)
        XCTAssertTrue(d.wide)
        XCTAssertEqual(d.margin, 32)
        XCTAssertEqual(d.units, 6)
        XCTAssertEqual(d.coverColumns, 7)
        XCTAssertLessThanOrEqual(d.jbiColumn(gap: 12).cover, 128)
        XCTAssertEqual(HomeDensity(width: 1376, height: 1032).units, 8)
    }

    func test_should_fillEveryRowWithoutHoles_when_theGridIsBuilt() {
        for n in 3...8 {
            let rows = HomeDensity.activityGrid(units: n, hasHero: true, supportingCount: 20, preset: .l)
            XCTAssertEqual(rows.last?.shape, .strip)
            for row in rows where row.shape == .cards {
                XCTAssertEqual(Int(row.slots.reduce(0) { $0 + $1.weight }), n, "row of \(n) units")
            }
        }
        // A short supply: the last card takes the row's rest.
        let short = HomeDensity.activityGrid(units: 5, hasHero: true, supportingCount: 1, preset: .l)
        XCTAssertEqual(short.count, 1)
        XCTAssertEqual(Int(short[0].slots.reduce(0) { $0 + $1.weight }), 5)
    }

    func test_should_staggerLanesTheSameEveryLaunch_when_seeded() {
        let a = HomeDensity.jbiOffsets(lanes: 7, seed: HomeDensity.stableSeed("al-harbor"))
        XCTAssertEqual(a, HomeDensity.jbiOffsets(lanes: 7, seed: HomeDensity.stableSeed("al-harbor")))
        XCTAssertEqual(a.first, 0)
        for i in 1..<a.count { XCTAssertNotEqual(a[i], a[i - 1]) }
        XCTAssertEqual(HomeDensity.jbiRows(columns: 3, preset: .l), 4)
        XCTAssertEqual(HomeDensity.jbiRows(columns: 7, preset: .l), 2)
        XCTAssertEqual(HomeDensity(width: 1376, height: 1032).recentTrackColumns, 4)
    }
}
