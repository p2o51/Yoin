import XCTest
@testable import Yoin

/// Yoin as the remote for Spotify on a speaker (audit 11-15 batch 12: #21 #22 #23 #45). Same fakes as
/// SpotifyBrokenConnectionTests, with me/player naming a speaker.
@MainActor
final class SpotifySpeakerTests: XCTestCase {
    private var app: FakeSpotifyApp!
    private var remote: SpotifyRemote!
    private var spotify: SpotifyAudioOutput!
    private var engine: PlayerEngine!
    private var source: SpotifySource!

    private let a = "4unM7lTfMfbiFMc6Hgnar6", b = "2JXQjdWdKhpdYYs48QEc2j", c = "3rH0pSMX1qwb5CaiefcTi4"

    override func setUp() {
        WebStub.reset()
        let app = FakeSpotifyApp()
        self.app = app
        remote = SpotifyRemote(makeLink: { _, _ in FakeLink(app: app) }, clientId: { "cid" }, enabled: { true })
        app.remote = remote
        remote.bind(account: "p1")
        remote.token = { "tok" }
        spotify = SpotifyAudioOutput(remote: remote, issues: SpotifyIssues(), spotifyInstalled: { true }, device: .phone)
        engine = PlayerEngine(output: spotify)
        let cfg = URLSessionConfiguration.ephemeral
        cfg.protocolClasses = [WebStub.self]
        source = SpotifySource(api: SpotifyWebAPI(token: { "tok" }, session: URLSession(configuration: cfg), sleep: { _ in }))
        spotify.control = source
    }

    override func tearDown() {
        spotify.control = nil
        spotify.stop()
        remote.disconnect()
        spotify = nil
        engine = nil
        remote = nil
        app = nil
        source = nil
        SpotifyPlayingDevice.shared.name = nil
        WebStub.reset()
    }

    private func song(_ id: String) -> PlayableTrack {
        PlayableTrack(id: id, provider: SpotifyMapping.provider, title: id, artist: "A", album: "Al", albumId: nil,
                      durationSec: 200, streamURL: SpotifyAudioOutput.streamURL(trackId: id), coverURL: nil)
    }

    private func waitUntil(_ seconds: Double = 8, _ what: String = "", _ done: () -> Bool) async throws {
        let end = Date().addingTimeInterval(seconds)
        while !done() {
            guard Date() < end else { return XCTFail("timed out \(what)") }
            try await Task.sleep(for: .milliseconds(20))
        }
    }

    private var starts: [String] {
        zip(WebStub.commands, WebStub.commandQueries).filter { $0.0 == "PUT /v1/me/player/play" }.map(\.1)
    }

    // #21
    func test_should_stayOnThePausedSpeaker_when_theListenerPressesNext() async throws {
        WebStub.onSpeaker = true
        app.running = true
        app.playing = "spotify:track:\(a)"
        engine.play(queue: [song(a), song(b), song(c)])
        try await waitUntil(5, "for the start on the speaker") { self.starts.count == 1 }
        XCTAssertEqual(SpotifyPlayingDevice.shared.name, "Kitchen")
        // The speaker is paused, then Next.
        app.paused = true
        engine.pause()
        try await Task.sleep(for: .milliseconds(300))
        engine.next()
        try await waitUntil(5, "for the next start") { self.starts.count == 2 }
        XCTAssertTrue(starts[1].contains("device_id=k1"), "the start goes to the speaker, not this iPhone: \(starts[1])")
        XCTAssertEqual(SpotifyPlayingDevice.shared.name, "Kitchen")
        XCTAssertEqual(app.wakes, 0)
        XCTAssertEqual(app.connectionsMade, 0, "App Remote isn't asked")
    }

    func test_should_keepTheSpeaker_whenStillActiveOrUnread_butNotForANewQueue() {
        let paused = SpotifySource.PlayerNow(deviceId: "k1", deviceName: "Kitchen", isPlaying: false)
        XCTAssertEqual(SpotifyAudioOutput.keptTarget(kind: .listener, lastTarget: "k1", read: .some(paused)), "k1")
        XCTAssertEqual(SpotifyAudioOutput.keptTarget(kind: .auto, lastTarget: "k1", read: .some(paused)), "k1")
        XCTAssertEqual(SpotifyAudioOutput.keptTarget(kind: .listener, lastTarget: "k1", read: nil), "k1", "a failed read keeps it")
        XCTAssertNil(SpotifyAudioOutput.keptTarget(kind: .newQueue, lastTarget: "k1", read: .some(paused)),
                     "a new queue goes where something plays")
        XCTAssertNil(SpotifyAudioOutput.keptTarget(kind: .listener, lastTarget: "k1", read: .some(nil)), "gone from me/player")
        XCTAssertNil(SpotifyAudioOutput.keptTarget(kind: .listener, lastTarget: nil, read: .some(paused)))
    }
}
