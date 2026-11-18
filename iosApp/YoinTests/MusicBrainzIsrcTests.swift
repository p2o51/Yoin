import Foundation
import os
import XCTest
@testable import Yoin

private final class MusicBrainzStubProtocol: URLProtocol {
    struct State: Sendable {
        var requests: [URLRequest] = []
        var handler: (@Sendable (URLRequest) -> (Int, Data))?
    }

    static let state = OSAllocatedUnfairLock(initialState: State())

    static func reset(handler: @escaping @Sendable (URLRequest) -> (Int, Data)) {
        state.withLock {
            $0.requests = []
            $0.handler = handler
        }
    }

    static var requests: [URLRequest] {
        state.withLock { $0.requests }
    }

    static var session: URLSession {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [MusicBrainzStubProtocol.self]
        return URLSession(configuration: config)
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let req = request
        let handler = Self.state.withLock { st -> (@Sendable (URLRequest) -> (Int, Data))? in
            st.requests.append(req)
            return st.handler
        }
        let (status, data) = handler?(req) ?? (500, Data())
        let response = HTTPURLResponse(
            url: req.url ?? URL(string: "https://musicbrainz.org")!,
            statusCode: status,
            httpVersion: nil,
            headerFields: nil
        )!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: data)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}

private final class FakeClock: @unchecked Sendable {
    private let state = OSAllocatedUnfairLock(initialState: (now: TimeInterval(100.0), sleeps: [TimeInterval]()))

    func current() -> TimeInterval {
        state.withLock { $0.now }
    }

    func sleep(_ seconds: TimeInterval) {
        state.withLock {
            $0.sleeps.append(seconds)
            $0.now += seconds
        }
    }

    func advance(by seconds: TimeInterval) {
        state.withLock { $0.now += seconds }
    }

    var recordedSleeps: [TimeInterval] {
        state.withLock { $0.sleeps }
    }
}

final class MusicBrainzIsrcTests: XCTestCase {

    // MARK: - MusicBrainzIsrcClient

    func test_should_sendUserAgentAndIsrcQuery_and_orderMatchingDurationFirstWithDeduplication() async throws {
        let payload = """
        {
          "recordings": [
            { "title": "Wrong Length", "length": 190000, "artist-credit": [{ "name": "Hebe Tien" }] },
            { "title": "Fallback No Length", "artist-credit": [{ "name": "田馥甄" }] },
            { "title": "摩天輪", "length": 244800, "artist-credit": [{ "name": "田馥甄" }] },
            { "title": "摩天輪", "length": 245900, "artist-credit": [{ "name": "田馥甄" }] },
            { "title": "Ferries wheel", "length": 243000, "artist-credit": [{ "name": "Hebe Tien" }] },
            { "title": "Fallback No Length", "artist-credit": [{ "name": "田馥甄" }] }
          ]
        }
        """.data(using: .utf8)!

        MusicBrainzStubProtocol.reset { _ in (200, payload) }
        let client = MusicBrainzIsrcClient(
            session: MusicBrainzStubProtocol.session,
            minGapSeconds: 0
        )

        let names = try await client.names(isrc: " twd950519106 ", durationMs: 244_800)

        XCTAssertEqual(MusicBrainzStubProtocol.requests.count, 1)
        let req = try XCTUnwrap(MusicBrainzStubProtocol.requests.first)
        XCTAssertEqual(req.url?.path, "/ws/2/isrc/TWD950519106")
        let query = URLComponents(url: try XCTUnwrap(req.url), resolvingAgainstBaseURL: false)?.queryItems ?? []
        XCTAssertEqual(query.first(where: { $0.name == "fmt" })?.value, "json")
        XCTAssertEqual(query.first(where: { $0.name == "inc" })?.value, "artist-credits")
        XCTAssertEqual(req.value(forHTTPHeaderField: "User-Agent"), "Yoin/1 ( https://github.com/p2o51/Yoin )")
        XCTAssertEqual(req.value(forHTTPHeaderField: "Accept"), "application/json")

        XCTAssertEqual(names.map(\.title), ["摩天輪", "Ferries wheel", "Fallback No Length"])
        XCTAssertEqual(names.map(\.artist), ["田馥甄", "Hebe Tien", "田馥甄"])
    }

    func test_should_returnAllDeduplicatedCandidates_when_durationIsNilOrZero() async throws {
        let payload = """
        {
          "recordings": [
            { "title": "Song A", "length": 180000, "artist-credit": [{ "name": "Artist 1" }] },
            { "title": "Song A", "length": 240000, "artist-credit": [{ "name": "Artist 1" }] },
            { "title": "Song B", "length": 240000, "artist-credit": [{ "artist": { "name": "Artist 2" } }] }
          ]
        }
        """.data(using: .utf8)!

        MusicBrainzStubProtocol.reset { _ in (200, payload) }
        let client = MusicBrainzIsrcClient(
            session: MusicBrainzStubProtocol.session,
            minGapSeconds: 0
        )

        let noDuration = try await client.names(isrc: "USRC17607839", durationMs: nil)
        XCTAssertEqual(noDuration.map(\.title), ["Song A", "Song B"])
        XCTAssertEqual(noDuration.map(\.artist), ["Artist 1", "Artist 2"])

        let zeroDuration = try await client.names(isrc: "USRC17607839", durationMs: 0)
        XCTAssertEqual(zeroDuration.map(\.title), ["Song A", "Song B"])
        XCTAssertEqual(MusicBrainzStubProtocol.requests.count, 1, "Second lookup uses cached recordings")
    }

    func test_should_skipNetwork_when_isrcIsInvalid() async throws {
        MusicBrainzStubProtocol.reset { _ in (500, Data()) }
        let client = MusicBrainzIsrcClient(
            session: MusicBrainzStubProtocol.session,
            minGapSeconds: 0
        )

        let empty = try await client.names(isrc: "not-an-isrc", durationMs: 200_000)
        XCTAssertTrue(empty.isEmpty)
        XCTAssertEqual(MusicBrainzStubProtocol.requests.count, 0)
    }

    func test_should_cache404EmptyResult_and_notCache500Failure() async throws {
        let callCount = OSAllocatedUnfairLock(initialState: 0)
        let okPayload = """
        {"recordings":[{"title":"Found Later","length":200000,"artist-credit":[{"name":"Artist"}]}]}
        """.data(using: .utf8)!

        MusicBrainzStubProtocol.reset { req in
            if req.url?.path.hasSuffix("/TW0000000404") == true {
                return (404, Data())
            }
            let count = callCount.withLock { c -> Int in
                c += 1
                return c
            }
            return count == 1 ? (500, Data()) : (200, okPayload)
        }
        let client = MusicBrainzIsrcClient(
            session: MusicBrainzStubProtocol.session,
            minGapSeconds: 0
        )

        let miss1 = try await client.names(isrc: "TW0000000404", durationMs: 200_000)
        let miss2 = try await client.names(isrc: "TW0000000404", durationMs: 200_000)
        XCTAssertTrue(miss1.isEmpty)
        XCTAssertTrue(miss2.isEmpty)
        XCTAssertEqual(MusicBrainzStubProtocol.requests.count, 1, "404 miss should be cached")

        do {
            _ = try await client.names(isrc: "TW0000000500", durationMs: 200_000)
            XCTFail("Expected HTTP 500 error")
        } catch let error as MusicBrainzIsrcClient.FetchError {
            XCTAssertEqual(error, .httpStatus(500))
        }

        let retry = try await client.names(isrc: "TW0000000500", durationMs: 200_000)
        XCTAssertEqual(retry.map(\.title), ["Found Later"])
        XCTAssertEqual(MusicBrainzStubProtocol.requests.count, 3)
    }

    func test_should_evictLeastRecentlyUsed_when_exceedingMaxEntries() async throws {
        MusicBrainzStubProtocol.reset { req in
            let code = req.url?.lastPathComponent ?? "X"
            let body = """
            {"recordings":[{"title":"Title \(code)","artist-credit":[{"name":"Artist"}]}]}
            """.data(using: .utf8)!
            return (200, body)
        }
        let client = MusicBrainzIsrcClient(
            session: MusicBrainzStubProtocol.session,
            maxEntries: 2,
            minGapSeconds: 0
        )

        _ = try await client.names(isrc: "USAAA0000001")
        _ = try await client.names(isrc: "USAAA0000002")
        // Refresh 001 so 002 becomes LRU.
        _ = try await client.names(isrc: "USAAA0000001")
        // Insert 003 -> evicts 002.
        _ = try await client.names(isrc: "USAAA0000003")
        // 001 is still cached; 002 misses cache and hits network again.
        _ = try await client.names(isrc: "USAAA0000001")
        _ = try await client.names(isrc: "USAAA0000002")

        XCTAssertEqual(
            MusicBrainzStubProtocol.requests.map { $0.url?.lastPathComponent },
            ["USAAA0000001", "USAAA0000002", "USAAA0000003", "USAAA0000002"]
        )
    }

    func test_should_enforceRateLimitGapBetweenRequests() async throws {
        let clock = FakeClock()
        MusicBrainzStubProtocol.reset { _ in
            (200, Data(#"{"recordings":[{"title":"T","artist-credit":[{"name":"A"}]}]}"#.utf8))
        }
        let client = MusicBrainzIsrcClient(
            session: MusicBrainzStubProtocol.session,
            minGapSeconds: 1.1,
            clock: { clock.current() },
            sleeper: { clock.sleep($0) }
        )

        _ = try await client.names(isrc: "JPPPP0000001")
        clock.advance(by: 0.3)
        _ = try await client.names(isrc: "JPPPP0000002")

        XCTAssertEqual(clock.recordedSleeps.count, 1)
        XCTAssertEqual(clock.recordedSleeps[0], 0.8, accuracy: 0.0001)
    }

    // MARK: - SpotifyNameLanguages

    func test_should_mapIsrcCountriesAndChineseScriptsCorrectly() {
        XCTAssertEqual(SpotifyNameLanguages.isrcLanguage(isrc: "twd950519106"), "zh-TW")
        XCTAssertEqual(SpotifyNameLanguages.isrcLanguage(isrc: "HK001"), "zh-HK")
        XCTAssertEqual(SpotifyNameLanguages.isrcLanguage(isrc: "MO001"), "zh-HK")
        XCTAssertEqual(SpotifyNameLanguages.isrcLanguage(isrc: "CN001"), "zh-CN")
        XCTAssertEqual(SpotifyNameLanguages.isrcLanguage(isrc: "JP001"), "ja")
        XCTAssertEqual(SpotifyNameLanguages.isrcLanguage(isrc: "KR001"), "ko")
        XCTAssertNil(SpotifyNameLanguages.isrcLanguage(isrc: "USRC17607839"))
        XCTAssertNil(SpotifyNameLanguages.isrcLanguage(isrc: nil))

        XCTAssertFalse(SpotifyNameLanguages.sameLanguage("zh-CN", "zh-TW"))
        XCTAssertTrue(SpotifyNameLanguages.sameLanguage("zh-Hant", "zh-TW"))
        XCTAssertTrue(SpotifyNameLanguages.sameLanguage("zh-Hans-CN", "zh-CN"))
        XCTAssertTrue(SpotifyNameLanguages.sameLanguage("ja-JP", "ja"))
        XCTAssertTrue(SpotifyNameLanguages.sameLanguage("en-US", "en"))
    }

    func test_should_orderAcceptLanguagesWithIsrcOrEnglishFallback() {
        XCTAssertEqual(
            SpotifyNameLanguages.acceptLanguages(isrc: "TWD950519106", locale: Locale(identifier: "zh_Hans_CN")),
            ["zh-CN", "zh-TW"]
        )
        XCTAssertEqual(
            SpotifyNameLanguages.acceptLanguages(isrc: "TWD950519106", locale: Locale(identifier: "zh_TW")),
            ["zh-TW"]
        )
        XCTAssertEqual(
            SpotifyNameLanguages.acceptLanguages(isrc: "USRC17607839", locale: Locale(identifier: "ja_JP")),
            ["ja-JP", "en"]
        )
        XCTAssertEqual(
            SpotifyNameLanguages.acceptLanguages(isrc: "USRC17607839", locale: Locale(identifier: "en_US")),
            ["en-US"]
        )
        XCTAssertEqual(
            SpotifyNameLanguages.acceptLanguages(isrc: "JPPPP0000001", locale: Locale(identifier: "ja_JP")),
            ["ja-JP"]
        )
        XCTAssertEqual(
            SpotifyNameLanguages.acceptLanguages(isrc: "KRA010000001", locale: Locale(identifier: "ja_JP")),
            ["ja-JP", "ko"]
        )
        XCTAssertEqual(
            SpotifyNameLanguages.deviceLanguage(locale: Locale(identifier: "und")),
            "en"
        )
    }
}
