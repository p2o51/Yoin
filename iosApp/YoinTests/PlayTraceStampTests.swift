import XCTest
@testable import Yoin

final class PlayTraceStampTests: XCTestCase {
    func test_should_stampTheDayAndTheTime_when_aLineIsLogged() {
        var c = DateComponents()
        c.year = 2026; c.month = 11; c.day = 3; c.hour = 9; c.minute = 5; c.second = 7; c.nanosecond = 42_000_000
        let date = Calendar.current.date(from: c)!
        XCTAssertEqual(PlayTrace.stamp.string(from: date), "11-03 09:05:07.042")
    }
}
