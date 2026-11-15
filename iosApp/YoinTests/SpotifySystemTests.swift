import AVFoundation
import XCTest
@testable import Yoin

/// The OS ties around a Spotify song (audit 11-15 #54, #56): the audio route and the lock screen
/// commands belong to Yoin's own sound only.
@MainActor
final class SpotifySystemTests: XCTestCase {
    private func track(_ id: String, provider: String, scheme: String) -> PlayableTrack {
        PlayableTrack(id: id, provider: provider, title: id, artist: "A", album: "Al", albumId: nil, durationSec: 200,
                      streamURL: URL(string: "\(scheme)://track/\(id)")!, coverURL: nil)
    }

    private func headphonesOut() {
        NotificationCenter.default.post(name: AVAudioSession.routeChangeNotification, object: nil,
                                        userInfo: [AVAudioSessionRouteChangeReasonKey:
                                                   AVAudioSession.RouteChangeReason.oldDeviceUnavailable.rawValue])
    }

    func test_should_notPauseSpotify_when_headphonesGoAway() {
        let output = FakeAudioOutput()
        let engine = PlayerEngine(output: output)
        let system = PlaybackSystem(engine: engine)
        engine.play(queue: [track("s", provider: "spotify", scheme: "spotify-yoin")], startAt: 0)
        XCTAssertTrue(engine.isPlaying)
        headphonesOut()
        XCTAssertTrue(engine.isPlaying, "Spotify on a speaker plays on")
        withExtendedLifetime(system) {}
    }

    func test_should_pauseYoinsOwnSound_when_headphonesGoAway() {
        let output = FakeAudioOutput()
        let engine = PlayerEngine(output: output)
        let system = PlaybackSystem(engine: engine)
        engine.play(queue: [track("t", provider: "subsonic", scheme: "http")], startAt: 0)
        XCTAssertTrue(engine.isPlaying)
        headphonesOut()
        XCTAssertFalse(engine.isPlaying)
        withExtendedLifetime(system) {}
    }

    func test_should_dropTheLockScreenCommands_when_playbackStops() {
        let output = FakeAudioOutput()
        let engine = PlayerEngine(output: output)
        let system = PlaybackSystem(engine: engine)
        engine.play(queue: [track("t", provider: "subsonic", scheme: "http")], startAt: 0)
        XCTAssertTrue(system.commandsInstalled)
        engine.stop()
        XCTAssertFalse(system.commandsInstalled, "no blank Yoin card's commands left behind")
    }
}
