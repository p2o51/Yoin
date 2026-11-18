import XCTest
@testable import Yoin

/// A Spotify start on this iPhone that didn't land (SpotifyAudioOutput.recoverHere; 51, 2026-11-07):
/// fresh connections (twice), then Spotify woken on the song once, then the failure held for Retry.
final class SpotifyStartRecoveryTests: XCTestCase {
    func test_should_reconnectFirst_when_aStartFails() {
        XCTAssertEqual(SpotifyAudioOutput.recovery(reconnects: 0, woke: false), .reconnect)
        // 51's 11-08 log: a broken connection can take more than one fresh one.
        XCTAssertEqual(SpotifyAudioOutput.recovery(reconnects: 1, woke: false), .reconnect)
    }

    func test_should_wakeSpotify_when_theFreshConnectionsFailedToo() {
        XCTAssertEqual(SpotifyAudioOutput.recovery(reconnects: SpotifyAudioOutput.maxReconnects, woke: false), .wake)
    }

    func test_should_holdTheFailure_when_theWakeFailedToo() {
        // Two reconnects, one wake, then a failure and Retry: never a loop of trips to Spotify (Codex 11-07).
        XCTAssertEqual(SpotifyAudioOutput.recovery(reconnects: 2, woke: true), .fail)
        XCTAssertEqual(SpotifyAudioOutput.recovery(reconnects: 0, woke: true), .fail)
    }

    func test_should_waitForTheCloseBeforeReconnecting() {
        XCTAssertGreaterThan(SpotifyRemote.reconnectGapMs, 0)
    }

    func test_should_endOnlyItsOwnConnect_when_aConnectTimerFires() {
        // An earlier connect's 3 s timer fired during the next one and failed it, so the next album
        // woke Spotify again (Codex 11-07 #9): a timer ends only its own attempt.
        XCTAssertTrue(SpotifyRemote.timeoutEnds(attempt: 4, current: 4))
        XCTAssertFalse(SpotifyRemote.timeoutEnds(attempt: 3, current: 4))
    }

    func test_should_keepTheStart_when_theVerifyReadsFail() {
        typealias Now = SpotifySource.PlayerNow
        let ours = Now(deviceId: "d", deviceName: "Den", isPlaying: true, trackURI: "spotify:track:a")
        let theirs = Now(deviceId: "d", deviceName: "Den", isPlaying: true, trackURI: "spotify:track:z")
        let stopped = Now(deviceId: "d", deviceName: "Den", isPlaying: false, trackURI: "spotify:track:z")
        // Every read failed (timeout, 429): that is no evidence the device stopped (Codex 11-07 #14).
        XCTAssertEqual(SpotifyAudioOutput.verifyOutcome(nil, wanted: "spotify:track:a", next: nil), .stands)
        XCTAssertEqual(SpotifyAudioOutput.verifyOutcome(.some(nil), wanted: "spotify:track:a", next: nil), .dropped)
        XCTAssertEqual(SpotifyAudioOutput.verifyOutcome(.some(ours), wanted: "spotify:track:a", next: nil), .ours(ours))
        XCTAssertEqual(SpotifyAudioOutput.verifyOutcome(.some(theirs), wanted: "spotify:track:a", next: nil), .other(theirs))
        XCTAssertEqual(SpotifyAudioOutput.verifyOutcome(.some(stopped), wanted: "spotify:track:a", next: nil), .dropped)
    }

    func test_should_dropAWatchAnswer_when_theAccountOrSessionMoved() {
        let a = NSObject(), b = NSObject()
        XCTAssertTrue(SpotifyAudioOutput.watchStillValid(asked: a, current: a, askedGen: 2, generation: 2))
        XCTAssertFalse(SpotifyAudioOutput.watchStillValid(asked: a, current: b, askedGen: 2, generation: 2), "account switched")
        XCTAssertFalse(SpotifyAudioOutput.watchStillValid(asked: a, current: nil, askedGen: 2, generation: 2))
        XCTAssertFalse(SpotifyAudioOutput.watchStillValid(asked: a, current: a, askedGen: 2, generation: 3), "a new start")
    }

    func test_should_revokeOnlyTheSignInThatWasRefreshed() {
        // An old refresh's invalid_grant after a re-login must not mark the new sign-in (Codex 11-07).
        XCTAssertTrue(SpotifyTokenProvider.revokes(stored: "r1", refreshed: "r1"))
        XCTAssertFalse(SpotifyTokenProvider.revokes(stored: "r2", refreshed: "r1"))
        XCTAssertFalse(SpotifyTokenProvider.revokes(stored: nil, refreshed: "r1"))
    }
}
