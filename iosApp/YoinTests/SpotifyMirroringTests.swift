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
