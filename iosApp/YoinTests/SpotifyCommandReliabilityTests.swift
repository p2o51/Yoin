import XCTest
@testable import Yoin

/// Commands to this iPhone's Spotify that App Remote takes nowhere, fail on Spotify's own song, or
/// find Spotify suspended (audit 11-15 batch 4: #5, #38, #9). Same fakes as SpotifyBrokenConnectionTests.
@MainActor
final class SpotifyCommandReliabilityTests: XCTestCase {
    private var app: FakeSpotifyApp!
    private var remote: SpotifyRemote!
    private var spotify: SpotifyAudioOutput!
    private var engine: PlayerEngine!
    private var source: SpotifySource!

    private let a = "4unM7lTfMfbiFMc6Hgnar6"

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

    private func playA() async throws {
        engine.play(queue: [song(a)])
        try await waitUntil(8, "for A") { self.app.statesSent > 0 && self.engine.failure == nil && self.engine.isPlaying }
        try await Task.sleep(for: .milliseconds(300))
    }

    // #5
    func test_should_resendThePause_then_goThroughTheWebAPI_when_appRemoteNeverAnswersIt() async throws {
        try await playA()
        app.commandsSilent = true
        engine.pause()
        try await waitUntil(SpotifyAudioOutput.seekEchoSec + 4, "for the pause again") {
            self.app.commands.filter { $0 == "pause" }.count >= 2
        }
        // The fresh connection reports Spotify still playing: that's from before the pause, not a play.
        try await Task.sleep(for: .milliseconds(300))
        XCTAssertFalse(engine.isPlaying, "a pause waiting on its answer isn't undone")
        try await waitUntil(SpotifyAudioOutput.seekEchoSec + 3, "for the Web API pause") {
            WebStub.commands.contains { $0.hasPrefix("PUT") && $0.hasSuffix("/me/player/pause") }
        }
        XCTAssertFalse(engine.isPlaying)
        XCTAssertEqual(app.wakes, 1)
    }

    func test_should_sendThePauseOnce_when_aStateAnswersIt() async throws {
        try await playA()
        engine.pause()
        try await Task.sleep(for: .seconds(SpotifyAudioOutput.seekEchoSec + 0.8))
        XCTAssertEqual(app.commands.filter { $0 == "pause" }.count, 1)
        XCTAssertFalse(engine.isPlaying)
    }

    func test_should_tellWhatAnswersACommand() {
        let a = "spotify:track:a", b = "spotify:track:b"
        XCTAssertTrue(SpotifyAudioOutput.answers(.paused(true), uri: a, paused: true, positionMs: 0))
        XCTAssertFalse(SpotifyAudioOutput.answers(.paused(true), uri: a, paused: false, positionMs: 0), "a periodic state isn't the pause")
        XCTAssertTrue(SpotifyAudioOutput.answers(.moved(from: a), uri: b, paused: false, positionMs: 0))
        XCTAssertFalse(SpotifyAudioOutput.answers(.moved(from: a), uri: a, paused: false, positionMs: 0), "a skip moves the song")
        XCTAssertTrue(SpotifyAudioOutput.answers(.seeked(toMs: 42_000), uri: a, paused: false, positionMs: 42_300))
        XCTAssertFalse(SpotifyAudioOutput.answers(.seeked(toMs: 42_000), uri: a, paused: false, positionMs: 1000),
                       "a fresh connection's state back where the song was isn't the seek")
        // A pause the listener has since undone (Play in Yoin) isn't sent again.
        XCTAssertFalse(SpotifyAudioOutput.stillWanted(.paused(true), wantsPlay: true))
        XCTAssertTrue(SpotifyAudioOutput.stillWanted(.paused(true), wantsPlay: false))
    }

    // #9
    func test_should_playOnTheFirstConnection_when_aPlayFoundSpotifySuspended() async throws {
        try await playA()
        engine.pause()
        try await waitUntil(3, "for the pause") { self.app.paused }
        // iOS suspends the paused Spotify: App Remote can't reach it and me/player has nothing.
        app.refuseConnects = true
        app.webEmpty = true
        app.links.forEach { $0.droppedBySpotify() }
        try await Task.sleep(for: .milliseconds(200))
        engine.resume()
        try await waitUntil(5, "for the failure") { self.engine.failure != nil }
        // The listener opens Spotify from the banner (it comes up paused on A) and comes back.
        app.refuseConnects = false
        app.webEmpty = false
        NotificationCenter.default.post(name: UIApplication.willEnterForegroundNotification, object: nil)
        try await waitUntil(5, "for the kept Play") { self.app.commands.last == "resume" }
        try await waitUntil(3, "for Yoin to play") { self.engine.isPlaying && self.engine.failure == nil }
        XCTAssertEqual(app.wakes, 1, "no wake: the kept Play went over the new connection")
    }

    func test_should_keepAPlay_onlyForTheSongPausedThereAndNotTooLate() {
        let a = "spotify:track:a", b = "spotify:track:b"
        XCTAssertTrue(SpotifyAudioOutput.resumesPending(shownURI: a, aimedURI: nil, stateURI: a, paused: true, ageSec: 10))
        XCTAssertFalse(SpotifyAudioOutput.resumesPending(shownURI: a, aimedURI: nil, stateURI: a, paused: false, ageSec: 10), "already playing")
        XCTAssertFalse(SpotifyAudioOutput.resumesPending(shownURI: a, aimedURI: nil, stateURI: b, paused: true, ageSec: 10), "another song")
        XCTAssertFalse(SpotifyAudioOutput.resumesPending(shownURI: a, aimedURI: nil, stateURI: a, paused: true,
                                                         ageSec: SpotifyAudioOutput.pendingResumeSec + 1))
    }
}
