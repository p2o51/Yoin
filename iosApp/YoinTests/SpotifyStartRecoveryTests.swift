import XCTest
@testable import Yoin

/// A Spotify start on this iPhone that didn't land (SpotifyAudioOutput.recoverHere; 51, 2026-11-07):
/// a fresh connection once, then Spotify woken on the song — never "Open Spotify" by hand.
final class SpotifyStartRecoveryTests: XCTestCase {
    func test_should_reconnectFirst_when_aStartFails() {
        XCTAssertEqual(SpotifyAudioOutput.recovery(retried: false), .reconnect)
    }

    func test_should_wakeSpotify_when_theFreshConnectionFailedToo() {
        XCTAssertEqual(SpotifyAudioOutput.recovery(retried: true), .wake)
    }

    func test_should_waitForTheCloseBeforeReconnecting() {
        XCTAssertGreaterThan(SpotifyAudioOutput.reconnectGapMs, 0)
    }
}
