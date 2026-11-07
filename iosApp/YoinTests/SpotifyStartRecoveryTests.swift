import XCTest
@testable import Yoin

/// A Spotify start on this iPhone that didn't land (SpotifyAudioOutput.recoverHere; 51, 2026-11-07):
/// a fresh connection once, then Spotify woken on the song once, then the failure held for Retry.
final class SpotifyStartRecoveryTests: XCTestCase {
    func test_should_reconnectFirst_when_aStartFails() {
        XCTAssertEqual(SpotifyAudioOutput.recovery(retried: false, woke: false), .reconnect)
    }

    func test_should_wakeSpotify_when_theFreshConnectionFailedToo() {
        XCTAssertEqual(SpotifyAudioOutput.recovery(retried: true, woke: false), .wake)
    }

    func test_should_holdTheFailure_when_theWakeFailedToo() {
        // One reconnect, one wake, then a failure and Retry: never a loop of trips to Spotify (Codex 11-07).
        XCTAssertEqual(SpotifyAudioOutput.recovery(retried: true, woke: true), .fail)
        XCTAssertEqual(SpotifyAudioOutput.recovery(retried: false, woke: true), .fail)
    }

    func test_should_waitForTheCloseBeforeReconnecting() {
        XCTAssertGreaterThan(SpotifyAudioOutput.reconnectGapMs, 0)
    }

    func test_should_endOnlyItsOwnConnect_when_aConnectTimerFires() {
        // An earlier connect's 3 s timer fired during the next one and failed it, so the next album
        // woke Spotify again (Codex 11-07 #9): a timer ends only its own attempt.
        XCTAssertTrue(SpotifyRemote.timeoutEnds(attempt: 4, current: 4))
        XCTAssertFalse(SpotifyRemote.timeoutEnds(attempt: 3, current: 4))
    }
}
