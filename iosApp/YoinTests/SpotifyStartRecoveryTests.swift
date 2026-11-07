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
}
