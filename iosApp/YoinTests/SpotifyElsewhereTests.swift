import XCTest
@testable import Yoin

/// Home's pill for another Spotify account's playback (HomeSpotifyElsewhere.swift).
final class SpotifyElsewhereTests: XCTestCase {
    private func e(_ id: String, playing: Bool) -> SpotifyElsewhere {
        SpotifyElsewhere(profileId: id, title: "Pink + White", artist: "Frank Ocean", coverURL: nil, isPlaying: playing)
    }

    func testPlayingAccountWinsOverPausedOne() {
        XCTAssertEqual(SpotifyElsewhere.pick([e("paused", playing: false), e("playing", playing: true)])?.profileId, "playing")
        XCTAssertEqual(SpotifyElsewhere.pick([e("paused", playing: false)])?.profileId, "paused")
        XCTAssertNil(SpotifyElsewhere.pick([]))
    }

    func testHiddenWhileYoinPlays() {
        XCTAssertTrue(SpotifyElsewhere.visible(e("a", playing: true), yoinPlaying: false))
        XCTAssertFalse(SpotifyElsewhere.visible(e("a", playing: true), yoinPlaying: true))
        XCTAssertFalse(SpotifyElsewhere.visible(nil, yoinPlaying: false))
    }

    func testShowsFiveSeconds() {
        XCTAssertEqual(SpotifyElsewhere.showSeconds, 5)
    }
}
