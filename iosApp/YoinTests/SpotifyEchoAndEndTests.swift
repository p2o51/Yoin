import XCTest
@testable import Yoin

/// The pure rules behind a command App Remote never answers and a song end it never reports
/// (a tester's build 28 log, 2026-11-15).
final class SpotifyEchoAndEndTests: XCTestCase {
    func test_should_expectEcho_when_seekMovesTheSong() {
        XCTAssertTrue(SpotifyAudioOutput.seekExpectsEcho(fromSec: 30, toSec: 95))
        XCTAssertTrue(SpotifyAudioOutput.seekExpectsEcho(fromSec: 95, toSec: 30))
    }

    func test_should_notExpectEcho_when_seekLandsWhereSpotifyIs() {
        XCTAssertFalse(SpotifyAudioOutput.seekExpectsEcho(fromSec: 60.2, toSec: 61))
    }

    func test_should_holdTheShownTimeAtTheEnd_when_theClockRunsPast() {
        XCTAssertEqual(SpotifyAudioOutput.shownTime(213.5, durationSec: 210), 210)
        XCTAssertEqual(SpotifyAudioOutput.shownTime(42, durationSec: 210), 42)
    }

    func test_should_showTheClock_when_theDurationIsUnknown() {
        XCTAssertEqual(SpotifyAudioOutput.shownTime(42, durationSec: 0), 42)
    }

    func test_should_flagAnOverrun_onlyPastTheSlack() {
        XCTAssertFalse(SpotifyAudioOutput.overran(timeSec: 211, durationSec: 210))
        XCTAssertTrue(SpotifyAudioOutput.overran(timeSec: 212.5, durationSec: 210))
    }
}

/// Device 11-16: a song started over App Remote plays on through its album, so the engine's next may be
/// Spotify's own next already; queued as well it would play twice.
final class SpotifyEnqueueSkipTests: XCTestCase {
    func test_should_notQueue_when_spotifysNextIsTheEnginesNext() {
        XCTAssertTrue(SpotifyAudioOutput.spotifyPlaysNextAlready(spotifysNext: "spotify:track:a", next: "spotify:track:a"))
    }

    func test_should_queue_when_spotifysNextIsAnotherSongOrUnknown() {
        XCTAssertFalse(SpotifyAudioOutput.spotifyPlaysNextAlready(spotifysNext: "spotify:track:b", next: "spotify:track:a"))
        XCTAssertFalse(SpotifyAudioOutput.spotifyPlaysNextAlready(spotifysNext: nil, next: "spotify:track:a"))
    }
}
