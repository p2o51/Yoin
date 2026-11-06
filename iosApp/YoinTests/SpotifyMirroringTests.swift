import XCTest
@testable import Yoin

/// Spotify against a fake Web API (51's build 4 log, 2026-11-06): Yoin opened while the Pixel played
/// shows that song, and a start on the playing Pixel sends Spotify's modes before the play, never after.
@MainActor
final class SpotifyMirroringTests: XCTestCase {
    private var spotify: SpotifyAudioOutput!
    private var source: SpotifySource!
    private var engine: PlayerEngine!

    override func setUp() {
        MirrorStub.reset()
        spotify = SpotifyAudioOutput()
        engine = PlayerEngine(output: spotify)
        let cfg = URLSessionConfiguration.ephemeral
        cfg.protocolClasses = [MirrorStub.self]
        source = SpotifySource(api: SpotifyWebAPI(token: { "tok" }, session: URLSession(configuration: cfg), sleep: { _ in }))
    }

    override func tearDown() {
        // No account first: a stop while playing would pause Spotify into the next test's log.
        spotify.control = nil
        spotify.stop()
        spotify = nil
        engine = nil
        source = nil
        SpotifyPlayingDevice.shared.name = nil
    }

    nonisolated static func pixel(playing: Bool = true, uri: String? = "spotify:track:34h4HhkN3FD0FqIf3Pm4F6",
                                  shuffle: Bool = true) -> String {
        let item = uri.map {
            #"{"uri":"\#($0)","duration_ms":200000,"name":"Song On Pixel","artists":[{"name":"Them"}],"album":{"name":"Theirs","images":[]}}"#
        } ?? "null"
        return #"{"device":{"id":"7a3d","name":"Pixel 11 Pro","type":"Smartphone"},"is_playing":\#(playing),"progress_ms":112946,"shuffle_state":\#(shuffle),"repeat_state":"\#(shuffle ? "context" : "off")","item":\#(item)}"#
    }

    private func waitUntil(_ seconds: Double = 3, _ done: () -> Bool) async throws {
        let end = Date().addingTimeInterval(seconds)
        while !done() {
            guard Date() < end else { return XCTFail("timed out") }
            try await Task.sleep(for: .milliseconds(20))
        }
    }

    private func song(_ id: String) -> PlayableTrack {
        PlayableTrack(id: id, provider: SpotifyMapping.provider, title: id, artist: "A", album: "Al", albumId: nil,
                      durationSec: 200, streamURL: SpotifyAudioOutput.streamURL(trackId: id), coverURL: nil)
    }

    private var puts: [String] { MirrorStub.requests.filter { $0.method == "PUT" }.map(\.url.path) }

    func test_should_showWhatThePixelPlays_when_yoinOpensWithNothingLoaded() async throws {
        MirrorStub.route = { _ in .init(status: 200, body: Self.pixel()) }
        spotify.adopt = { true }
        spotify.control = source
        try await waitUntil { self.engine.currentTrack?.title == "Song On Pixel" }
        XCTAssertTrue(engine.isPlaying)
        XCTAssertEqual(engine.currentTrack?.artist, "Them")
        XCTAssertEqual(SpotifyPlayingDevice.shared.name, "Pixel 11 Pro")
        // Showing it changes nothing in Spotify (its shuffle stays on).
        XCTAssertEqual(puts, [])
    }

    func test_should_notTakeOver_when_yoinPlaysAnotherSource() async throws {
        MirrorStub.route = { _ in .init(status: 200, body: Self.pixel()) }
        spotify.adopt = { false }
        spotify.control = source
        try await waitUntil { MirrorStub.requests.contains { $0.url.path == "/v1/me/player" } }
        try await Task.sleep(for: .milliseconds(200))
        XCTAssertNil(engine.currentTrack)
    }

    func test_should_turnModesOffBeforeThePlay_when_startingOnThePlayingPixel() async throws {
        MirrorStub.route = { s in s.method == "GET" ? .init(status: 200, body: Self.pixel()) : .init(status: 204, body: "") }
        spotify.adopt = { false }
        spotify.control = source
        engine.play(queue: [song("2FNGRT6OEuexOkwHcWRsm1"), song("07AxzhPisOpLqA7vnHXLZ4")])
        try await waitUntil { self.puts.contains("/v1/me/player/play") }
        try await Task.sleep(for: .milliseconds(300))
        XCTAssertEqual(puts, ["/v1/me/player/shuffle", "/v1/me/player/repeat", "/v1/me/player/play"])
        let play = try XCTUnwrap(MirrorStub.requests.first { $0.url.path == "/v1/me/player/play" })
        XCTAssertEqual(play.url.query, "device_id=7a3d")
    }

    func test_should_sendOnlyThePlay_when_spotifysModesAreAlreadyOff() async throws {
        MirrorStub.route = { s in s.method == "GET" ? .init(status: 200, body: Self.pixel(shuffle: false)) : .init(status: 204, body: "") }
        spotify.adopt = { false }
        spotify.control = source
        engine.play(queue: [song("2FNGRT6OEuexOkwHcWRsm1"), song("07AxzhPisOpLqA7vnHXLZ4")])
        try await waitUntil { self.puts.contains("/v1/me/player/play") }
        try await Task.sleep(for: .milliseconds(300))
        XCTAssertEqual(puts, ["/v1/me/player/play"])
    }

    func test_should_sendNoPause_when_thePixelLostTheSong() async throws {
        // Run 2 of build 4: after the start the Pixel has no song; the pause that followed was refused.
        MirrorStub.route = { s in
            let started = MirrorStub.requests.contains { $0.url.path == "/v1/me/player/play" }
            if s.method != "GET" { return .init(status: 204, body: "") }
            return .init(status: 200, body: started ? Self.pixel(playing: false, uri: nil, shuffle: false) : Self.pixel(shuffle: false))
        }
        spotify.adopt = { false }
        spotify.control = source
        engine.play(queue: [song("2FNGRT6OEuexOkwHcWRsm1")])
        try await waitUntil(5) { self.engine.failure != nil }
        try await Task.sleep(for: .milliseconds(300))
        XCTAssertFalse(engine.isPlaying)
        XCTAssertEqual(puts, ["/v1/me/player/play"])
        XCTAssertEqual(engine.failure?.message, "Spotify on Pixel 11 Pro stopped the song. Retry plays it on this iPhone.")
    }

    func test_should_showTheOtherSong_when_theDeviceStillPlaysItAtTheCheck() async throws {
        // The start never took and the Pixel plays on: Yoin shows that, no failure.
        MirrorStub.route = { s in s.method == "GET" ? .init(status: 200, body: Self.pixel(shuffle: false)) : .init(status: 204, body: "") }
        spotify.adopt = { false }
        spotify.control = source
        engine.play(queue: [song("2FNGRT6OEuexOkwHcWRsm1")])
        try await waitUntil(5) { self.engine.externalTrack != nil }
        XCTAssertNil(engine.failure)
        XCTAssertEqual(engine.currentTrack?.title, "Song On Pixel")
        XCTAssertTrue(engine.isPlaying)
    }

    // MARK: Where a start goes (51, 2026-11-06 06:51)

    /// A start with Spotify's active device in [reply]: whether a Web API command went anywhere.
    private func start(with reply: MirrorStub.Reply) async throws {
        MirrorStub.route = { s in s.method == "GET" ? reply : .init(status: 204, body: "") }
        spotify.adopt = { false }
        spotify.control = source
        engine.play(queue: [song("2FNGRT6OEuexOkwHcWRsm1")])
        try await waitUntil { MirrorStub.requests.contains { $0.url.path == "/v1/me/player" } }
        try await Task.sleep(for: .seconds(2))
    }

    func test_should_playOnThisIPhone_when_theActiveDeviceIsPaused() async throws {
        // A paused Pixel is still Spotify's active device: the start isn't sent there.
        try await start(with: .init(status: 200, body: Self.pixel(playing: false, shuffle: false)))
        XCTAssertEqual(puts, [])
        XCTAssertFalse(MirrorStub.requests.contains { $0.method != "GET" })
        XCTAssertNil(SpotifyPlayingDevice.shared.name)
    }

    func test_should_playOnThisIPhone_when_nothingIsActive() async throws {
        try await start(with: .init(status: 204, body: ""))
        XCTAssertFalse(MirrorStub.requests.contains { $0.method != "GET" })
        XCTAssertNil(SpotifyPlayingDevice.shared.name)
    }

    func test_should_beTheRemote_when_theActiveDeviceIsPlaying() async throws {
        try await start(with: .init(status: 200, body: Self.pixel(shuffle: false)))
        // Remote control only: a start with its device_id, no transfer.
        XCTAssertEqual(puts, ["/v1/me/player/play"])
        XCTAssertEqual(MirrorStub.requests.first { $0.url.path == "/v1/me/player/play" }?.url.query, "device_id=7a3d")
        XCTAssertFalse(MirrorStub.requests.contains { $0.method == "PUT" && $0.url.path == "/v1/me/player" })
        XCTAssertEqual(SpotifyPlayingDevice.shared.name, "Pixel 11 Pro")
    }

    // MARK: The Mac end-to-end rule (2026-11-06)

    func test_should_holdAPauseUntilTheStartIsSeen_when_pausedRightAfterAStart() async throws {
        MirrorStub.route = { s in
            if s.method != "GET" { return .init(status: 204, body: "") }
            let started = MirrorStub.requests.contains { $0.url.path == "/v1/me/player/play" }
            return .init(status: 200, body: Self.pixel(uri: started ? "spotify:track:2FNGRT6OEuexOkwHcWRsm1"
                                                                    : "spotify:track:34h4HhkN3FD0FqIf3Pm4F6", shuffle: false))
        }
        spotify.adopt = { false }
        spotify.control = source
        engine.play(queue: [song("2FNGRT6OEuexOkwHcWRsm1")])
        try await waitUntil { self.puts.contains("/v1/me/player/play") }
        try await Task.sleep(for: .milliseconds(100))
        engine.pause()
        engine.seek(to: 30)
        try await Task.sleep(for: .milliseconds(500))
        // Nothing goes into the load on its way.
        XCTAssertEqual(puts, ["/v1/me/player/play"])
        try await waitUntil(5) { self.puts.count == 3 }
        XCTAssertEqual(puts.first, "/v1/me/player/play")
        XCTAssertEqual(Set(puts.dropFirst()), ["/v1/me/player/pause", "/v1/me/player/seek"])
    }

    func test_should_judgeAStart_when_readWithinItsWindow() {
        let uri = "spotify:track:a"
        func now(_ u: String?, playing: Bool, at ms: Int = 0) -> SpotifySource.PlayerNow? {
            SpotifySource.PlayerNow(deviceId: "7a3d", deviceName: "Pixel 11 Pro", deviceType: "Smartphone",
                                    isPlaying: playing, trackURI: u, progressMs: ms)
        }
        XCTAssertEqual(SpotifyAudioOutput.startCheck(nil, uri: uri, fromSec: 0, elapsed: 3), .dropped)
        XCTAssertEqual(SpotifyAudioOutput.startCheck(now(nil, playing: false), uri: uri, fromSec: 0, elapsed: 12), .dropped)
        XCTAssertEqual(SpotifyAudioOutput.startCheck(now(uri, playing: false), uri: uri, fromSec: 0, elapsed: 3), .waiting)
        XCTAssertEqual(SpotifyAudioOutput.startCheck(now(uri, playing: false), uri: uri, fromSec: 0, elapsed: 8), .dropped)
        XCTAssertNil(SpotifyAudioOutput.startCheck(now(uri, playing: true), uri: uri, fromSec: 0, elapsed: 3))
        // Played a while, then paused on the device: the listener's pause.
        XCTAssertNil(SpotifyAudioOutput.startCheck(now(uri, playing: false, at: 9000), uri: uri, fromSec: 0, elapsed: 12))
        XCTAssertNil(SpotifyAudioOutput.startCheck(nil, uri: uri, fromSec: 0, elapsed: 25))
    }

    func test_should_moveTheClock_when_showingSpotifysOwnSong() async throws {
        MirrorStub.route = { _ in .init(status: 200, body: Self.pixel()) }
        spotify.adopt = { true }
        spotify.control = source
        try await waitUntil { self.engine.currentTrack?.title == "Song On Pixel" }
        let first = engine.progress.positionSec
        try await Task.sleep(for: .milliseconds(1200))
        XCTAssertGreaterThan(engine.progress.positionSec, first + 0.7)
    }

    // MARK: Late commands and Spotify's own song (Codex review, 2026-11-06)

    func test_should_dropARetriedSeek_when_theSongChangedMeanwhile() async throws {
        MirrorStub.route = { s in
            if s.method == "GET" {
                // The start is seen, so the seek isn't held.
                let started = MirrorStub.requests.contains { $0.url.path == "/v1/me/player/play" }
                return .init(status: 200, body: Self.pixel(uri: started ? "spotify:track:2FNGRT6OEuexOkwHcWRsm1"
                                                                        : "spotify:track:34h4HhkN3FD0FqIf3Pm4F6", shuffle: false))
            }
            let seeks = MirrorStub.requests.filter { $0.url.path == "/v1/me/player/seek" }.count
            if s.url.path == "/v1/me/player/seek", seeks == 0 { return .init(status: 429, body: "") }
            return .init(status: 204, body: "")
        }
        let engine = self.engine!
        let cfg = URLSessionConfiguration.ephemeral
        cfg.protocolClasses = [MirrorStub.self]
        // The rate limit's wait: the listener moves to the next song before the retry.
        source = SpotifySource(api: SpotifyWebAPI(token: { "tok" }, session: URLSession(configuration: cfg), sleep: { _ in
            await MainActor.run { engine.jump(to: 1) }
        }))
        spotify.adopt = { false }
        spotify.control = source
        engine.play(queue: [song("2FNGRT6OEuexOkwHcWRsm1"), song("07AxzhPisOpLqA7vnHXLZ4")])
        try await waitUntil { self.puts.contains("/v1/me/player/play") }
        try await waitUntil(5) { MirrorStub.requests.filter { $0.method == "GET" }.count >= 2 }
        try await Task.sleep(for: .milliseconds(100))
        engine.seek(to: 30)
        try await waitUntil { self.puts.filter { $0 == "/v1/me/player/play" }.count == 2 }
        try await Task.sleep(for: .milliseconds(300))
        XCTAssertEqual(puts.filter { $0 == "/v1/me/player/seek" }.count, 1)
    }

    func test_should_aimAtTheSong_when_checkingACommand() {
        let aim = SpotifyAudioOutput.Aim(generation: 3, uri: "spotify:track:a")
        XCTAssertTrue(SpotifyAudioOutput.stillAimed(aim, generation: 3, shownURI: "spotify:track:a"))
        XCTAssertFalse(SpotifyAudioOutput.stillAimed(aim, generation: 3, shownURI: "spotify:track:b"))
        XCTAssertFalse(SpotifyAudioOutput.stillAimed(aim, generation: 4, shownURI: "spotify:track:a"))
        let any = SpotifyAudioOutput.Aim(generation: 3, uri: nil)
        XCTAssertTrue(SpotifyAudioOutput.stillAimed(any, generation: 3, shownURI: "spotify:track:b"))
    }

    func test_should_leaveSpotifysOwnSong_when_queueingNextOrShuffling() async throws {
        // The Pixel plays on with its own song; Play next and shuffle change Yoin's queue only.
        MirrorStub.route = { s in s.method == "GET" ? .init(status: 200, body: Self.pixel(shuffle: false)) : .init(status: 204, body: "") }
        spotify.adopt = { false }
        spotify.control = source
        engine.play(queue: [song("2FNGRT6OEuexOkwHcWRsm1"), song("07AxzhPisOpLqA7vnHXLZ4"), song("3a"), song("4b")])
        try await waitUntil(5) { self.engine.externalTrack != nil }
        engine.playNext(song("5c"))
        engine.setShuffle(true)
        try await Task.sleep(for: .seconds(1))
        XCTAssertEqual(puts, ["/v1/me/player/play"])
        XCTAssertEqual(engine.currentTrack?.title, "Song On Pixel")
        XCTAssertTrue(engine.isPlaying)
    }
}

/// Answers each request from [route] and records it.
private final class MirrorStub: URLProtocol {
    struct Seen: Sendable {
        var method: String
        var url: URL
    }

    struct Reply {
        var status: Int
        var body: String
    }

    private static let lock = NSLock()
    nonisolated(unsafe) static var route: @Sendable (Seen) -> Reply = { _ in Reply(status: 500, body: "") }
    nonisolated(unsafe) private static var seen: [Seen] = []

    static func reset() {
        lock.withLock { seen = [] }
        route = { _ in Reply(status: 500, body: "") }
    }

    static var requests: [Seen] { lock.withLock { seen } }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let s = Seen(method: request.httpMethod ?? "GET", url: request.url!)
        let reply = Self.route(s)
        Self.lock.withLock { Self.seen.append(s) }
        let response = HTTPURLResponse(url: request.url!, statusCode: reply.status, httpVersion: "HTTP/1.1",
                                       headerFields: ["Content-Type": "application/json"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(reply.body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}
