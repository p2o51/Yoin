import XCTest
@testable import Yoin

/// Android UpNextTimingTest.
final class UpNextTimingTests: XCTestCase {
    private let duration: Int64 = 240_000

    func test_should_revealTenSecondsOutAndHandOverEarly_when_theOutroIsLong() {
        XCTAssertEqual(UpNextTiming.forUpNext(lastLineStartMs: 200_000, nextTimed: true, durationMs: duration),
                       UpNextTiming(revealStartMs: 230_000, handoffAtMs: 238_600))
    }

    func test_should_stillChoreograph_when_theLastLineStartsThreeSecondsBeforeTheEnd() {
        let last = duration - 3_000
        let t = UpNextTiming.forUpNext(lastLineStartMs: last, nextTimed: true, durationMs: duration)!
        XCTAssertGreaterThanOrEqual(t.revealStartMs, last)
        XCTAssertLessThanOrEqual(t.handoffAtMs, duration - UpNextTiming.handoffMinLeadMs)
        XCTAssertEqual(t.handoffAtMs, duration - 1_000)
    }

    func test_should_revealWithTheLastLine_when_itStartsJustBeforeTheEnd() {
        let last = duration - 1_500
        let t = UpNextTiming.forUpNext(lastLineStartMs: last, nextTimed: true, durationMs: duration)!
        XCTAssertEqual(t.revealStartMs, last)
        XCTAssertEqual(t.handoffAtMs, duration - UpNextTiming.handoffMinLeadMs)
    }

    func test_should_handOverBeforeTheLastTick_when_anyTimingIsProduced() {
        for last in stride(from: duration - 60_000, through: duration - UpNextTiming.handoffMinLeadMs, by: 100) {
            guard let t = UpNextTiming.forUpNext(lastLineStartMs: last, nextTimed: true, durationMs: duration) else { continue }
            XCTAssertGreaterThanOrEqual(duration - 250, t.handoffAtMs - 300)
            XCTAssertGreaterThanOrEqual(t.revealStartMs, last)
            XCTAssertLessThanOrEqual(t.revealStartMs, t.handoffAtMs)
        }
    }

    func test_should_skip_when_theLastLineStartsInsideTheFinalHandOverLead_orNothingToStage() {
        XCTAssertNil(UpNextTiming.forUpNext(lastLineStartMs: duration - 500, nextTimed: true, durationMs: duration))
        XCTAssertNil(UpNextTiming.forUpNext(lastLineStartMs: 200_000, nextTimed: false, durationMs: duration))
        XCTAssertNil(UpNextTiming.forUpNext(lastLineStartMs: 200_000, nextTimed: true, durationMs: 0))
        XCTAssertNil(UpNextTiming.forUpNext(lastLineStartMs: nil, nextTimed: true, durationMs: duration))
    }

    func test_should_quantizeProgress_when_inTheWindow() {
        let t = UpNextTiming(revealStartMs: 0, handoffAtMs: 2_400)
        XCTAssertEqual(t.progress(-1), 0)
        XCTAssertEqual(t.progress(1_200), 0.5)
        XCTAssertEqual(t.progress(5_000), 1)
    }
}
