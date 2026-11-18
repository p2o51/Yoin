import SwiftUI
import XCTest
@testable import Yoin

/// A diary pull stays "engaged" (the text stays interactive) from its start to its release,
/// including once p drops below .5, so the pull's own end is never disabled out from under it.
@MainActor
final class MemoriesDiaryPullTests: XCTestCase {
    private let distance = MemoriesBackTokens.diaryMorphDistance

    func test_should_stayPulling_when_pullDropsBelowHalf() {
        let d = MemoriesDiary()
        d.snap(to: 1)
        d.startPull(banded: true, fromScrolled: false)
        d.pullBy(distance * 0.7)
        XCTAssertLessThan(d.fraction, 0.5)
        XCTAssertFalse(d.isDiaryLevel)
        XCTAssertTrue(d.isPulling)
    }

    func test_should_endPulling_when_released() {
        let d = MemoriesDiary()
        d.snap(to: 1)
        d.startPull(banded: true, fromScrolled: false)
        d.pullBy(distance * 0.7)
        d.releasePull(velocity: 0)
        XCTAssertFalse(d.isPulling)
        XCTAssertTrue(d.isSettling)
    }

    func test_should_notMarkPulling_when_cardRubberBand() {
        let d = MemoriesDiary()
        d.startPull(banded: false, fromScrolled: false, ceiling: 0)
        XCTAssertFalse(d.isPulling)
    }
}
