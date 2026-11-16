import XCTest
@testable import Yoin

final class SpotifyRateGateTests: XCTestCase {
    private final class Clock: @unchecked Sendable {
        private let lock = NSLock()
        private var t = Date(timeIntervalSince1970: 1_000_000)
        var now: Date { lock.withLock { t } }
        func advance(_ s: TimeInterval) { lock.withLock { t += s } }
    }

    func test_should_report_the_time_left_when_a_backoff_is_recorded() async {
        let clock = Clock()
        let gate = SpotifyRateGate(now: { clock.now })
        let none = await gate.remaining()
        XCTAssertEqual(none, 0)
        await gate.recordBackoff(30)
        clock.advance(10.5)
        let left = await gate.remaining()
        XCTAssertEqual(left, 20)
        clock.advance(20)
        let over = await gate.remaining()
        XCTAssertEqual(over, 0)
    }

    func test_should_keep_the_later_backoff_when_a_shorter_one_comes() async {
        let clock = Clock()
        let gate = SpotifyRateGate(now: { clock.now })
        await gate.recordBackoff(60)
        await gate.recordBackoff(5)
        let left = await gate.remaining()
        XCTAssertEqual(left, 60)
    }

    func test_should_cap_the_backoff_at_a_day_when_retry_after_is_huge() async {
        let gate = SpotifyRateGate()
        await gate.recordBackoff(10_000_000)
        let left = await gate.remaining()
        XCTAssertEqual(left, SpotifyRateGate.maxBackoff)
    }

    func test_should_let_requests_go_when_cleared() async {
        let gate = SpotifyRateGate()
        await gate.recordBackoff(60)
        await gate.clear()
        let left = await gate.remaining()
        XCTAssertEqual(left, 0)
    }

    func test_should_hold_the_next_request_when_every_slot_is_taken() async {
        let gate = SpotifyRateGate(maxConcurrent: 2)
        await gate.acquire()
        await gate.acquire()
        let third = Task { await gate.acquire(); return true }
        try? await Task.sleep(for: .milliseconds(50))
        let running = await gate.inFlight
        XCTAssertEqual(running, 2)
        await gate.release()
        let got = await third.value
        XCTAssertTrue(got)
        let after = await gate.inFlight
        XCTAssertEqual(after, 2)
        await gate.release()
        await gate.release()
        let idle = await gate.inFlight
        XCTAssertEqual(idle, 0)
    }

    func test_should_say_the_wait_in_minutes_when_it_is_long() {
        XCTAssertTrue(SpotifyAPIError.waitText(30).contains("30"))
        XCTAssertTrue(SpotifyAPIError.waitText(600).contains("10"))
        XCTAssertTrue(SpotifyAPIError.waitText(5 * 3600).contains("5"))
    }
}
