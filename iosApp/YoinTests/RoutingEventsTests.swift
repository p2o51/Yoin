import XCTest
@testable import Yoin

/// RoutingAudioOutput's EventsGate (the live chain PlayerEngine → router → gate → Spotify): every
/// AudioOutputEvents member reaches the engine, the protocol's defaulted ones included, and the
/// router passes play-mode changes on to the active output.
@MainActor
final class RoutingEventsTests: XCTestCase {
    private final class SpyOutput: AudioOutput {
        weak var events: AudioOutputEvents?
        var currentTimeSec: Double = 0
        var modes: [(Bool, RepeatMode)] = []
        func load(_ current: URL, next: URL?, startSec: Double) {}
        func preload(_ next: URL?) {}
        func play() {}
        func pause() {}
        func seek(to sec: Double) {}
        func stop() {}
        func playModesDidChange(shuffle: Bool, repeatMode: RepeatMode) { modes.append((shuffle, repeatMode)) }
    }

    private final class Recorder: AudioOutputEvents {
        var calls: [String] = []
        func outputTimeDidChange(_ sec: Double) {}
        func outputDurationDidChange(_ sec: Double) {}
        func outputIsBufferingDidChange(_ buffering: Bool) {}
        func outputDidAdvanceToPreloaded() {}
        func outputDidReachEnd() {}
        func outputDidFail(_ message: String, network: Bool) {}
        func outputPlayingDidChangeExternally(_ playing: Bool) {}
        func outputUpcoming(limit: Int) -> [URL] { calls.append("upcoming"); return [URL(string: "x://1")!] }
        func outputDidJump(to url: URL) -> Bool { calls.append("jump"); return true }
        func outputQueueSnapshot() -> OutputQueueSnapshot { calls.append("snapshot"); return OutputQueueSnapshot(userQueued: true) }
        func outputDidRecover(playing: Bool) { calls.append("recover") }
    }

    func test_should_forwardEveryEvent_when_theChildIsActive() {
        let spotify = SpyOutput()
        let router = RoutingAudioOutput(av: SpyOutput(), apple: AppleMusicAudioOutput(), spotify: spotify)
        let recorder = Recorder()
        router.events = recorder
        router.adopt(spotify)

        XCTAssertEqual(spotify.events?.outputUpcoming(limit: 3), [URL(string: "x://1")!])
        XCTAssertEqual(spotify.events?.outputDidJump(to: URL(string: "x://1")!), true)
        XCTAssertEqual(spotify.events?.outputQueueSnapshot().userQueued, true)
        spotify.events?.outputDidRecover(playing: true)
        XCTAssertEqual(recorder.calls, ["upcoming", "jump", "snapshot", "recover"])
    }

    func test_should_dropEvents_when_theChildIsNotActive() {
        let spotify = SpyOutput()
        let router = RoutingAudioOutput(av: SpyOutput(), apple: AppleMusicAudioOutput(), spotify: spotify)
        let recorder = Recorder()
        router.events = recorder
        XCTAssertEqual(spotify.events?.outputUpcoming(limit: 3), [])
        spotify.events?.outputDidRecover(playing: true)
        XCTAssertEqual(recorder.calls, [])
    }

    func test_should_passPlayModesOn_toTheActiveOutput() {
        let spotify = SpyOutput()
        let router = RoutingAudioOutput(av: SpyOutput(), apple: AppleMusicAudioOutput(), spotify: spotify)
        router.adopt(spotify)
        router.playModesDidChange(shuffle: true, repeatMode: .one)
        XCTAssertEqual(spotify.modes.count, 1)
        XCTAssertEqual(spotify.modes.first?.0, true)
        XCTAssertEqual(spotify.modes.first?.1, .one)
    }
}
