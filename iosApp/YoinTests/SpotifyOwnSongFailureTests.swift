import XCTest
@testable import Yoin

/// Audit 11-15 #38: a failure on Spotify's own song with no queue under it.
@MainActor
final class SpotifyOwnSongFailureTests: XCTestCase {
    func test_should_holdTheFailureOnSpotifysSong_and_retryItThere_when_noQueueIsParked() {
        let output = FakeAudioOutput()
        let engine = PlayerEngine(output: output)
        let theirs = PlayableTrack(id: "x", provider: "spotify", title: "X", artist: "", album: "", albumId: nil,
                                   durationSec: 100, streamURL: URL(string: "spotify-yoin://track/x")!, coverURL: nil)
        engine.outputExternalTrackDidChange(theirs, positionSec: 30, playing: false)
        engine.outputDidFail(SpotifyFailureCopy.openSpotify(on: "iPhone"), network: true)
        XCTAssertEqual(engine.failure?.track.id, "x", "the banner shows, on Spotify's song")
        XCTAssertFalse(engine.isPlaying)
        engine.retry()
        XCTAssertNil(engine.failure)
        XCTAssertTrue(output.playing, "Retry plays Spotify's song")
        XCTAssertEqual(output.loads, 0, "nothing of a queue is loaded")
    }

    func test_should_notReloadTheParkedQueue_when_retryingSpotifysOwnSong() {
        let output = FakeAudioOutput()
        let engine = PlayerEngine(output: output)
        let mine = PlayableTrack(id: "m", provider: "spotify", title: "M", artist: "", album: "", albumId: nil,
                                 durationSec: 100, streamURL: URL(string: "spotify-yoin://track/m")!, coverURL: nil)
        engine.play(queue: [mine], startAt: 0)
        let loads = output.loads
        let theirs = PlayableTrack(id: "x", provider: "spotify", title: "X", artist: "", album: "", albumId: nil,
                                   durationSec: 100, streamURL: URL(string: "spotify-yoin://track/x")!, coverURL: nil)
        engine.outputExternalTrackDidChange(theirs, positionSec: 30, playing: true)
        engine.outputDidFail("Spotify on Kitchen isn't responding — open Spotify there", network: true)
        engine.resume()
        XCTAssertEqual(output.loads, loads, "the parked song isn't started over Spotify's")
        XCTAssertEqual(engine.currentTrack?.id, "x")
        XCTAssertNil(engine.failure)
    }
}
