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

    // #51
    func test_should_sendOnlyTheLastOfAPauseAndAPlay_when_tappedInARow() async throws {
        WebStub.onSpeaker = true
        app.running = true
        app.playing = "spotify:track:\(a)"
        engine.play(queue: [song(a), song(b)])
        try await waitUntil(5, "for the start") { self.starts.count == 1 }
        // The start is seen on the speaker (held commands go).
        try await Task.sleep(for: .seconds(SpotifyAudioOutput.verifySec + 0.5))
        engine.pause()
        engine.resume()
        try await Task.sleep(for: .milliseconds(800))
        XCTAssertFalse(WebStub.commands.contains("PUT /v1/me/player/pause"), "the pause a play overtook isn't sent")
        XCTAssertEqual(starts.count, 2, "the play is")
    }

    func test_should_ignoreAReadFromBeforeAWebCommand_onlyWhileItContradictsIt() {
        let now = Date()
        let pause = SpotifyAudioOutput.WebIntent(uri: "u", paused: true, positionSec: nil, playing: false, at: now)
        XCTAssertEqual(SpotifyAudioOutput.readVerdict(pause, uri: "u", isPlaying: true, progressSec: 30, ageSec: 0.7), .ignore)
        XCTAssertEqual(SpotifyAudioOutput.readVerdict(pause, uri: "u", isPlaying: false, progressSec: 30, ageSec: 0.7), .apply)
        XCTAssertEqual(SpotifyAudioOutput.readVerdict(pause, uri: "u", isPlaying: true, progressSec: 30, ageSec: 3.5), .apply,
                       "past the window the read is the truth")
        XCTAssertEqual(SpotifyAudioOutput.readVerdict(pause, uri: "other", isPlaying: true, progressSec: 0, ageSec: 0.7), .apply,
                       "another song is applied as usual")
        let seek = SpotifyAudioOutput.WebIntent(uri: "u", paused: nil, positionSec: 95, playing: true, at: now)
        XCTAssertEqual(SpotifyAudioOutput.readVerdict(seek, uri: "u", isPlaying: true, progressSec: 31, ageSec: 0.6), .ignore)
        XCTAssertEqual(SpotifyAudioOutput.readVerdict(seek, uri: "u", isPlaying: true, progressSec: 96, ageSec: 0.6), .apply)
    }

    // #50
    func test_should_needTwoEmptyReads_beforeCallingAStartDropped() {
        let uri = "spotify:track:\(a)"
        XCTAssertEqual(SpotifyAudioOutput.startCheck(nil, uri: uri, fromSec: 0, elapsed: 3, emptiesInRow: 1), .waiting)
        XCTAssertEqual(SpotifyAudioOutput.startCheck(nil, uri: uri, fromSec: 0, elapsed: 7, emptiesInRow: 2), .dropped)
    }

    // #60
    func test_should_seekAfterTheStart_onlyWhenTheSpotMovedMeanwhile() {
        XCTAssertTrue(SpotifyAudioOutput.seeksAfterStart(startSec: 0, soughtSec: 95))
        XCTAssertFalse(SpotifyAudioOutput.seeksAfterStart(startSec: 42, soughtSec: 42.4))
    }

    func test_should_surfaceFailureAgainImmediately_when_listenerTapsRetryWithinFiveSeconds() async throws {
        // Adopted speaker pauses, then vanishes (404 on PUT me/player/play); tapping Retry < 5 s later
        // must surface the error again instead of swallowing it under reportGapSec.
        let pausedSpeaker = SpotifySource.PlayerNow(
            deviceId: "k1", deviceName: "Living Room Back", deviceType: "Speaker",
            isPlaying: false, trackURI: "spotify:track:\(a)", progressMs: 102_000, durationMs: 200_000,
            trackName: "Speaker Song", artistName: "Artist", albumName: "Album"
        )
        let failCfg = URLSessionConfiguration.ephemeral
        failCfg.protocolClasses = [VanishedSpeakerStub.self]
        VanishedSpeakerStub.now = pausedSpeaker
        let failSource = SpotifySource(api: SpotifyWebAPI(token: { "tok" }, session: URLSession(configuration: failCfg), sleep: { _ in }))
        spotify.adopt = { true }
        spotify.control = nil
        VanishedSpeakerStub.now = SpotifySource.PlayerNow(
            deviceId: "k1", deviceName: "Living Room Back", deviceType: "Speaker",
            isPlaying: true, trackURI: "spotify:track:\(a)", progressMs: 102_000, durationMs: 200_000,
            trackName: "Speaker Song", artistName: "Artist", albumName: "Album"
        )
        spotify.control = failSource
        try await waitUntil(3, "adopt speaker") { self.engine.currentTrack?.title == "Speaker Song" }

        // Speaker pauses, then goes 404 on commands.
        VanishedSpeakerStub.now = pausedSpeaker
        engine.pause()
        try await Task.sleep(for: .milliseconds(300))
        VanishedSpeakerStub.commandsNotFound = true

        engine.resume()
        try await waitUntil(3, "first 404 banner") { self.engine.failure != nil }
        XCTAssertFalse(engine.isPlaying)

        // Immediate Retry (< 5 s): must NOT swallow the second 404!
        engine.retry()
        try await waitUntil(3, "retry 404 banner") { self.engine.failure != nil && !self.engine.isPlaying }
        XCTAssertNotNil(engine.failure)
        XCTAssertFalse(engine.isPlaying)
    }

    func test_should_keepScreenAwake_onlyWhileNowPlayingIsOpenAndPlaying() {
        XCTAssertTrue(KeepScreenOnObserver.shouldDisableIdleTimer(
            keepScreenOn: true, npExpanded: true, isPad: false, npPanel: false, isPlaying: true))
        XCTAssertFalse(KeepScreenOnObserver.shouldDisableIdleTimer(
            keepScreenOn: true, npExpanded: true, isPad: false, npPanel: false, isPlaying: false),
            "paused or offline speaker must let the screen sleep")
        XCTAssertFalse(KeepScreenOnObserver.shouldDisableIdleTimer(
            keepScreenOn: true, npExpanded: false, isPad: false, npPanel: false, isPlaying: true))
        XCTAssertTrue(KeepScreenOnObserver.shouldDisableIdleTimer(
            keepScreenOn: true, npExpanded: false, isPad: true, npPanel: true, isPlaying: true))
        XCTAssertFalse(KeepScreenOnObserver.shouldDisableIdleTimer(
            keepScreenOn: false, npExpanded: true, isPad: false, npPanel: false, isPlaying: true))
    }
}

private final class VanishedSpeakerStub: URLProtocol {
    nonisolated(unsafe) static var now: SpotifySource.PlayerNow?
    nonisolated(unsafe) static var commandsNotFound = false

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let url = request.url!
        let method = request.httpMethod ?? "GET"
        let status: Int
        let body: String
        if method == "GET", url.path.hasSuffix("/me/player") {
            if let n = Self.now {
                status = 200
                body = #"""
                {"device":{"id":"\#(n.deviceId ?? "")","name":"\#(n.deviceName)","type":"\#(n.deviceType)"},
                 "is_playing":\#(n.isPlaying),"progress_ms":\#(n.progressMs),"shuffle_state":false,"repeat_state":"off",
                 "item":{"uri":"\#(n.trackURI ?? "")","duration_ms":\#(n.durationMs),"name":"\#(n.trackName ?? "")",
                         "artists":[{"name":"\#(n.artistName ?? "")"}],"album":{"name":"\#(n.albumName ?? "")","images":[]}}}
                """#
            } else {
                status = 204
                body = ""
            }
        } else if Self.commandsNotFound {
            status = 404
            body = #"{"error":{"status":404,"message":"Device not found"}}"#
        } else {
            status = 204
            body = ""
        }
        let resp = HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1",
                                   headerFields: ["Content-Type": "application/json"])!
        client?.urlProtocol(self, didReceive: resp, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}
