import XCTest
@testable import Yoin

/// Android GeminiKeyHintTest.
final class GeminiKeyHintTests: XCTestCase {
    private let day: Int64 = 24 * 60 * 60 * 1000

    func test_should_beDue_when_neverShown() { XCTAssertTrue(GeminiKeyHint.isDue(nil, nowMs: 1_000)) }

    func test_should_notBeDue_when_shownWithinThreeDays() {
        XCTAssertFalse(GeminiKeyHint.isDue(10 * day, nowMs: 12 * day))
        XCTAssertFalse(GeminiKeyHint.isDue(10 * day, nowMs: 13 * day - 1))
    }

    func test_should_beDue_when_threeDaysPassed() { XCTAssertTrue(GeminiKeyHint.isDue(10 * day, nowMs: 13 * day)) }

    func test_should_beDue_when_clockSetBack() { XCTAssertTrue(GeminiKeyHint.isDue(10 * day, nowMs: 5 * day)) }
}
