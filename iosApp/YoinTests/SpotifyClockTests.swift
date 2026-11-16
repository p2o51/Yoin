import XCTest
@testable import Yoin

/// The clock a Web API read sets (audit 11-15 #63).
final class SpotifyClockTests: XCTestCase {
    func test_should_addHalfTheRoundTrip_when_theSameSongPlaysOnAndTheClockIsOff() {
        // Read at 60.0 s, 0.4 s out and back: Spotify is at about 60.2 s; the local clock says 58.
        XCTAssertEqual(SpotifyAudioOutput.readPositionMs(60_000, roundTripSec: 0.4, sameSongPlaying: true, localSec: 58), 60_200)
    }

    func test_should_keepTheLocalClock_when_theCorrectionIsSmall() {
        XCTAssertEqual(SpotifyAudioOutput.readPositionMs(60_000, roundTripSec: 0.4, sameSongPlaying: true, localSec: 60.5), 60_500)
    }

    func test_should_takeTheReadAsItIs_when_theSongOrTheStateChanged() {
        XCTAssertEqual(SpotifyAudioOutput.readPositionMs(1_000, roundTripSec: 0.4, sameSongPlaying: false, localSec: 200), 1_000)
        XCTAssertEqual(SpotifyAudioOutput.readPositionMs(60_000, roundTripSec: 0, sameSongPlaying: true, localSec: 58), 60_000)
    }
}
