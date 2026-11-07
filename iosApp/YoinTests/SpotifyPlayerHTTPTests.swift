import XCTest
@testable import Yoin

/// The Web API player calls as they go on the wire (51's 2026-11-06 phone log): the start's body and
/// headers, and how a me/player read is understood.
@MainActor
final class SpotifyPlayerHTTPTests: XCTestCase {
    override func setUp() { PlayerStub.reset() }

    private func source() -> SpotifySource {
        let cfg = URLSessionConfiguration.ephemeral
        cfg.protocolClasses = [PlayerStub.self]
        return SpotifySource(api: SpotifyWebAPI(token: { "tok" }, session: URLSession(configuration: cfg), sleep: { _ in }))
    }

    func test_should_sendUrisAndPositionAsJSON_when_startingOnADevice() async throws {
        PlayerStub.route = { _ in .init(status: 204, body: "") }
        try await source().startPlayback(uris: ["spotify:track:4n2Gy75q4SgNuq5ZUKxnq3", "spotify:track:0ad9g8OoHG7u872SaLRlcy"],
                                         positionMs: 5000, deviceId: "ecfc279b")
        let r = try XCTUnwrap(PlayerStub.requests.first)
        XCTAssertEqual(r.method, "PUT")
        XCTAssertEqual(r.url.path, "/v1/me/player/play")
        XCTAssertEqual(r.url.query, "device_id=ecfc279b")
        XCTAssertEqual(r.headers["Content-Type"], "application/json")
        let body = try XCTUnwrap(JSONSerialization.jsonObject(with: r.body) as? [String: Any])
        XCTAssertEqual(body["uris"] as? [String], ["spotify:track:4n2Gy75q4SgNuq5ZUKxnq3", "spotify:track:0ad9g8OoHG7u872SaLRlcy"])
        XCTAssertEqual(body["position_ms"] as? Int, 5000)
    }

    func test_should_neverResendAStart_when_aNewerOneLandedDuringTheRateLimitWait() async throws {
        // Album A gets 429; while it waits, the listener starts album B. A's retry must not go out and
        // play over B (Codex 11-07).
        PlayerStub.route = { _ in PlayerStub.requests.count <= 1 ? .init(status: 429, body: "") : .init(status: 204, body: "") }
        let wanted = Flag(true)
        let cfg = URLSessionConfiguration.ephemeral
        cfg.protocolClasses = [PlayerStub.self]
        let api = SpotifyWebAPI(token: { "tok" }, session: URLSession(configuration: cfg), sleep: { _ in wanted.set(false) })
        do {
            try await SpotifySource(api: api).startPlayback(uris: ["spotify:track:a"], while: { wanted.get() })
            XCTFail("the replaced start went out")
        } catch is SpotifyCommandDropped {}
        XCTAssertEqual(PlayerStub.requests.count, 1)
    }

    func test_should_saveAndRemoveTheAlbumThroughTheLibrary_when_toggled() async throws {
        PlayerStub.route = { _ in .init(status: 200, body: "") }
        try await source().setAlbumSaved(true, album: "al1")
        try await source().setAlbumSaved(false, album: "al1")
        let r = PlayerStub.requests
        XCTAssertEqual(r.map(\.method), ["PUT", "DELETE"])
        XCTAssertEqual(r.map(\.url.path), ["/v1/me/library", "/v1/me/library"])
        XCTAssertEqual(r.first?.url.query, "uris=spotify:album:al1")
    }

    func test_should_readTheAlbumsLibraryState_when_asked() async throws {
        PlayerStub.route = { _ in .init(status: 200, body: "[true]") }
        let saved = await source().isAlbumSaved("al1")
        XCTAssertEqual(saved, true)
        XCTAssertEqual(PlayerStub.requests.first?.url.path, "/v1/me/library/contains")
        PlayerStub.route = { _ in .init(status: 500, body: "") }
        let failed = await source().isAlbumSaved("al1")
        XCTAssertNil(failed)
    }

    func test_should_readTheDeviceTrackAndProgress_when_spotifyPlays() async throws {
        PlayerStub.route = { _ in .init(status: 200, body: #"{"device":{"id":"ecfc","name":"iPhone","type":"Smartphone","is_active":true},"is_playing":true,"progress_ms":14657,"shuffle_state":false,"repeat_state":"off","item":{"uri":"spotify:track:1IEKU3WzzShCxANW5Jfv6g","duration_ms":171172}}"#) }
        let now = try await source().playerNow()
        XCTAssertEqual(now?.deviceId, "ecfc")
        XCTAssertEqual(now?.trackURI, "spotify:track:1IEKU3WzzShCxANW5Jfv6g")
        XCTAssertEqual(now?.isPlaying, true)
        XCTAssertEqual(now?.progressMs, 14657)
    }

    func test_should_readNothingActive_when_spotifyAnswers204() async throws {
        PlayerStub.route = { _ in .init(status: 204, body: "") }
        let now = try await source().playerNow()
        XCTAssertNil(now)
    }

    func test_should_readAnIdleDevice_when_itHasNoTrack() async throws {
        // The Pixel of run 1: listed as the device, not playing, nothing loaded.
        PlayerStub.route = { _ in .init(status: 200, body: #"{"device":{"id":"7a3d","name":"Pixel 11 Pro","type":"Smartphone"},"is_playing":false,"progress_ms":0,"item":null}"#) }
        let now = try await source().playerNow()
        XCTAssertEqual(now?.deviceName, "Pixel 11 Pro")
        XCTAssertEqual(now?.isPlaying, false)
        XCTAssertNil(now?.trackURI)
    }
}

private final class Flag: @unchecked Sendable {
    private let lock = NSLock()
    private var value: Bool
    init(_ v: Bool) { value = v }
    func get() -> Bool { lock.withLock { value } }
    func set(_ v: Bool) { lock.withLock { value = v } }
}

/// Answers each request from [route] and records it with its headers and body.
private final class PlayerStub: URLProtocol {
    struct Seen {
        var method: String
        var url: URL
        var headers: [String: String]
        var body: Data
    }

    struct Reply {
        var status: Int
        var body: String
    }

    private static let lock = NSLock()
    nonisolated(unsafe) static var route: @Sendable (Seen) -> Reply = { _ in Reply(status: 500, body: "") }
    nonisolated(unsafe) private static var seen: [Seen] = []

    static func reset() { lock.withLock { seen = [] } }
    static var requests: [Seen] { lock.withLock { seen } }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    private static func body(_ r: URLRequest) -> Data {
        if let b = r.httpBody { return b }
        guard let stream = r.httpBodyStream else { return Data() }
        stream.open()
        defer { stream.close() }
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 4096)
        while stream.hasBytesAvailable {
            let n = stream.read(&buffer, maxLength: buffer.count)
            if n <= 0 { break }
            data.append(buffer, count: n)
        }
        return data
    }

    override func startLoading() {
        let s = Seen(method: request.httpMethod ?? "GET", url: request.url!,
                     headers: request.allHTTPHeaderFields ?? [:], body: Self.body(request))
        Self.lock.withLock { Self.seen.append(s) }
        let reply = Self.route(s)
        let response = HTTPURLResponse(url: request.url!, statusCode: reply.status, httpVersion: "HTTP/1.1",
                                       headerFields: ["Content-Type": "application/json"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(reply.body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}
