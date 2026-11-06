import XCTest
@testable import Yoin

/// The lyric list's song-change motion (two Next taps in a row flashed the middle song's lines).
@MainActor
final class LyricsDirectionTests: XCTestCase {
    func test_should_cut_when_songChangesWhileTheLastSlideRuns() {
        let d = LyricsDirection()
        d.update(key: "a", index: 0, now: 10)
        XCTAssertFalse(d.inPlace)
        d.update(key: "b", index: 1, now: 11)
        XCTAssertFalse(d.inPlace, "a lone change slides")
        d.update(key: "c", index: 2, now: 11.15)
        XCTAssertTrue(d.inPlace, "a change inside the slide's settle cuts")
        XCTAssertTrue(d.rapid)
        XCTAssertTrue(d.forward)
        d.update(key: "d", index: 3, now: 12)
        XCTAssertFalse(d.inPlace, "once settled, a change slides again")
    }

    func test_should_swapInPlace_when_theOutroHandedOver() {
        let d = LyricsDirection()
        d.update(key: "a", index: 0, now: 10)
        d.inPlaceNext = "b"
        d.update(key: "b", index: 1, now: 20)
        XCTAssertTrue(d.inPlace)
        XCTAssertFalse(d.rapid, "the outro's swap keeps the leaving list opaque")
    }
}
