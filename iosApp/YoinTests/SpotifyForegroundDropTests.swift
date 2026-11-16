import XCTest
@testable import Yoin

/// Spotify going away, or App Remote going quiet, while Yoin drives this iPhone in front (audit 11-15
/// batch 3): Yoin reads me/player instead of freezing on the last state. Same fakes as
/// SpotifyBrokenConnectionTests.
@MainActor
final class SpotifyForegroundDropTests: XCTestCase {
    private var app: FakeSpotifyApp!
    private var remote: SpotifyRemote!
    private var spotify: SpotifyAudioOutput!
    private var engine: PlayerEngine!
    private var source: SpotifySource!

    private let a = "4unM7lTfMfbiFMc6Hgnar6", b = "2JXQjdWdKhpdYYs48QEc2j"

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

    // #7
    func test_should_showPausedAndReadMeplayer_when_spotifyDropsTheConnectionInFront() async throws {
        try await playA()
        let reads = WebStub.reads, made = app.connectionsMade
        app.quit()
        try await waitUntil(2, "for the pause") { !self.engine.isPlaying }
        // me/player read at once and on the poll; the connection tried again.
        try await waitUntil(SpotifyAudioOutput.pollSec + 2, "for the poll") { WebStub.reads >= reads + 2 }
        XCTAssertGreaterThan(app.connectionsMade, made)
        XCTAssertFalse(engine.isPlaying)
        XCTAssertEqual(app.wakes, 1, "Yoin doesn't open Spotify by itself")
    }

    // #7: Spotify plays on, and is then paused from its lock screen card, with App Remote out of reach.
    func test_should_followAPauseThroughMeplayer_when_appRemoteCantComeBack() async throws {
        try await playA()
        app.refuseConnects = true
        app.links.forEach { $0.droppedBySpotify() }
        // me/player shows A still playing here: Yoin plays on with it.
        try await waitUntil(3, "for me/player's play") { self.engine.isPlaying && WebStub.reads >= 2 }
        app.paused = true
        try await waitUntil(SpotifyAudioOutput.pollSec + 2, "for me/player's pause") { !self.engine.isPlaying }
        XCTAssertNil(engine.failure)
    }

    // #27
    func test_should_keepTheClockWhereItGot_when_theConnectionDrops() async throws {
        try await playA()
        try await Task.sleep(for: .milliseconds(1200))
        let before = spotify.currentTimeSec
        XCTAssertGreaterThan(before, 2)
        app.refuseConnects = true
        app.links.forEach { $0.droppedBySpotify() }
        try await Task.sleep(for: .milliseconds(100))
        XCTAssertGreaterThanOrEqual(spotify.currentTimeSec, before, "not back at App Remote's last report (1 s)")
    }

    // #6: a connection replaced for going quiet leaves me/player read until its successor speaks.
    func test_should_needTwoQuietLooks_beforeReplacingAConnection() {
        XCTAssertFalse(SpotifyAudioOutput.replacesSilent(strikes: 1, decisive: false))
        XCTAssertTrue(SpotifyAudioOutput.replacesSilent(strikes: 2, decisive: false))
        XCTAssertTrue(SpotifyAudioOutput.replacesSilent(strikes: 1, decisive: true), "a song run past its end is enough")
    }

    // #52, device 11-16 09:02:09: Devices › This iPhone with App Remote refused (POSIX 61) was followed
    // only by the 10 s watch. me/player is read every 4 s instead.
    func test_should_pollMeplayer_when_movedBackHereAndAppRemoteRefuses() async throws {
        try await playA()
        remote.dropForBackground()
        app.refuseConnects = true
        try await Task.sleep(for: .milliseconds(100))
        let reads = WebStub.reads
        NotificationCenter.default.post(name: SpotifyPlayingDevice.transferred, object: nil,
                                        userInfo: ["id": "ip", "name": "iPhone", "type": "Smartphone"])
        // Two reads in two poll periods (the watch alone reads once in 10 s).
        try await waitUntil(SpotifyAudioOutput.pollSec * 2 + 1.5, "for the poll") { WebStub.reads >= reads + 2 }
        XCTAssertEqual(app.wakes, 1, "Yoin doesn't open Spotify for it")
    }

    // Device 11-16 09:03:52: a seek with App Remote refused went over the Web API, and nothing read
    // me/player after it but the 10 s watch.
    func test_should_pollMeplayer_when_aCommandFindsAppRemoteRefused() async throws {
        try await playA()
        remote.dropForBackground()
        app.refuseConnects = true
        try await Task.sleep(for: .milliseconds(100))
        let reads = WebStub.reads
        engine.seek(to: 50)
        try await waitUntil(3, "for the seek over the Web API") { WebStub.commands.contains { $0.hasSuffix("/me/player/seek") } }
        // The command's own check and its read after, then the poll's.
        try await waitUntil(SpotifyAudioOutput.pollSec * 2 + 1.5, "for the poll") { WebStub.reads >= reads + 4 }
    }

    func test_should_pollHere_onlyWhileAppRemoteCarriesNothing() {
        XCTAssertTrue(SpotifyAudioOutput.pollsHere(isHere: true, driving: true, remoteCarries: false, silent: false))
        XCTAssertFalse(SpotifyAudioOutput.pollsHere(isHere: true, driving: true, remoteCarries: true, silent: false))
        XCTAssertFalse(SpotifyAudioOutput.pollsHere(isHere: false, driving: true, remoteCarries: false, silent: false), "a speaker polls on its own")
        XCTAssertFalse(SpotifyAudioOutput.pollsHere(isHere: true, driving: false, remoteCarries: false, silent: false))
    }

    // #6: a reconnect over a broken connection that the SDK still reads as connected isn't "connected".
    func test_should_notReadABrokenConnectionAsConnected_when_reconnecting() async throws {
        try await playA()
        app.refuseConnects = true
        remote.markBroken("test")
        let ok = await remote.connect(timeout: 1)
        XCTAssertFalse(ok)
        XCTAssertFalse(remote.isConnected && !remote.broken)
    }
}
