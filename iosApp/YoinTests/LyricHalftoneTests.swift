import XCTest
@testable import Yoin

/// Focus's halftone lyric lines in the iPad's two-column Now Playing (NowPlaying/NowPlayingLyricsTools.swift,
/// Android LyricReadableLinesTest, 52b72a9f): the current line solid, the next two and the line just sung
/// readable, the rest fine dots.
final class LyricHalftoneTests: XCTestCase {
    private func readable(active: Int, _ range: ClosedRange<Int>) -> [Int] {
        range.filter { LyricHalftone.isReadable(line: $0, active: active) }
    }

    func test_should_keepTheLineJustSungAndTheNextTwoReadable_when_aLineIsCurrent() {
        XCTAssertEqual(readable(active: 5, 0...10), [4, 6, 7])
    }

    func test_should_notTreatTheCurrentLineAsReadable_when_itIsBeingSung() {
        XCTAssertFalse(LyricHalftone.isReadable(line: 5, active: 5))
        XCTAssertEqual(LyricHalftone.radius(line: 5, active: 5), LyricHalftone.solid)
    }

    func test_should_keepOnlyTheNextTwoReadable_when_theFirstLineIsCurrent() {
        XCTAssertEqual(readable(active: 0, 0...10), [1, 2])
    }

    func test_should_keepNothingReadable_when_noLineIsCurrent() {
        XCTAssertEqual(readable(active: -1, 0...10), [])
    }

    func test_should_restLinesTwoOrMoreAboveAsFineDots_when_aLineIsCurrent() {
        XCTAssertEqual(LyricHalftone.radius(line: 4, active: 5), LyricHalftone.readable)
        XCTAssertEqual(LyricHalftone.radius(line: 3, active: 5), LyricHalftone.restLarge)
        XCTAssertEqual(LyricHalftone.radius(line: 8, active: 5), LyricHalftone.restLarge)
    }
}
