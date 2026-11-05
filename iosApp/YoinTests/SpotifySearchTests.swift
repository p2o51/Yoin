import XCTest
@testable import Yoin

/// Spotify search: the catalog's `next` continuation, the 10-per-page and 1000 ceiling rules,
/// and the saved-library filter (Android searchSpotifySavedLibrary).
@MainActor
final class SpotifySearchTests: XCTestCase {
    private func source() -> SpotifySource {
        let cfg = URLSessionConfiguration.ephemeral
        cfg.protocolClasses = [SearchStubProtocol.self]
        return SpotifySource(api: SpotifyWebAPI(token: { "tok" }, session: URLSession(configuration: cfg)))
    }

    override func setUp() { SearchStubProtocol.reset() }

    private nonisolated static func track(_ n: Int) -> String {
        #"{"id":"t\#(n)","name":"Song \#(n)","duration_ms":1000,"track_number":1,"artists":[{"id":"a1","name":"Ann"}],"album":{"id":"al1","name":"Alb","artists":[{"id":"a1","name":"Ann"}],"images":[]}}"#
    }

    private nonisolated static func page(kind: String, ids: [String], next: Bool) -> String {
        #""\#(kind)":{"items":[\#(ids.joined(separator: ","))],"next":\#(next ? #""https://api.spotify.com/v1/search?next""# : "null")}"#
    }

    func test_should_reportMoreKinds_when_aKindHasNext() async throws {
        SearchStubProtocol.route = { _, _ in
            "{" + Self.page(kind: "tracks", ids: (1...10).map(Self.track), next: true) + ","
                + Self.page(kind: "playlists", ids: ["null"], next: false) + "}"
        }
        let r = try await source().search("song")
        XCTAssertEqual(r.songs.count, 10)
        XCTAssertEqual(r.more, [.songs])
        XCTAssertEqual(r.counts[.songs], 10)
        XCTAssertEqual(r.counts[.playlists], 1)
        let q = SearchStubProtocol.requests[0].url!.query!
        XCTAssertTrue(q.contains("limit=10"))
        XCTAssertTrue(q.contains("offset=0"))
    }

    func test_should_askTheNextOffset_when_loadingMoreOfOneKind() async throws {
        SearchStubProtocol.route = { _, _ in "{" + Self.page(kind: "tracks", ids: (11...14).map(Self.track), next: false) + "}" }
        let r = try await source().search("song", kinds: [.songs], offset: 10)
        XCTAssertEqual(r.songs.map(\.track.title), ["Song 11", "Song 12", "Song 13", "Song 14"])
        XCTAssertTrue(r.more.isEmpty)
        let q = SearchStubProtocol.requests[0].url!.query!
        XCTAssertTrue(q.contains("offset=10"))
        XCTAssertTrue(q.contains("type=track"))
        XCTAssertFalse(q.contains("album"))
    }

    func test_should_stopContinuing_when_theNextPageWouldPassTheCeiling() async throws {
        SearchStubProtocol.route = { _, _ in "{" + Self.page(kind: "tracks", ids: (1...10).map(Self.track), next: true) + "}" }
        let r = try await source().search("song", kinds: [.songs], offset: 990)
        XCTAssertTrue(r.more.isEmpty)
    }

    private func album(_ id: String, _ title: String, _ artist: String, artistId: String? = nil) -> Album {
        Album(id: MediaId(provider: SpotifyMapping.provider, rawId: id), title: title, artist: artist, year: 2020,
              palette: SpotifyMapping.palette(nil, key: id), tracks: [], artistId: artistId)
    }

    func test_should_filterTheSavedLibrary_when_scopeIsLibrary() {
        let thien = album("a1", "Thiên Đường", "Vu", artistId: "v1")
        let other = album("a2", "Other", "Someone", artistId: "s1")
        let track = Track(id: MediaId(provider: SpotifyMapping.provider, rawId: "t1"), number: 1, title: "Duong Xa",
                          durationSec: 100, rating: nil)
        let liked = PlaylistEntry(album: other, track: track)
        let pl = LibraryItem(id: MediaId(provider: SpotifyMapping.provider, rawId: "p1"), kind: .playlist,
                             title: "Road Trip", subtitle: "", palette: thien.palette, owner: "Duong")
        let r = SpotifySource.filterLibrary("thien duong", artists: [], albums: [thien, other], songs: [liked], playlists: [pl])
        XCTAssertEqual(r.albums.map(\.title), ["Thiên Đường"])
        XCTAssertTrue(r.songs.isEmpty)
        XCTAssertTrue(r.playlists.isEmpty)
        // Single word: the track, the owner's playlist; the artist of the saved album.
        let d = SpotifySource.filterLibrary("duong", artists: [], albums: [thien, other], songs: [liked], playlists: [pl])
        XCTAssertEqual(d.songs.count, 1)
        XCTAssertEqual(d.playlists.count, 1)
        XCTAssertEqual(d.albums.count, 1)
        let artists = SpotifySource.filterLibrary("someone", artists: [], albums: [thien, other], songs: [], playlists: [])
        XCTAssertEqual(artists.artists.map(\.title), ["Someone"])
        XCTAssertEqual(artists.albums.count, 1)
    }

    func test_should_matchNothing_when_queryIsBlank() {
        let r = SpotifySource.filterLibrary("  ", artists: [], albums: [album("a", "A", "B")], songs: [], playlists: [])
        XCTAssertTrue(r.albums.isEmpty)
    }

    func test_should_readLikedSongsFromOffset_when_pagingTheLibrary() async throws {
        SearchStubProtocol.route = { req, _ in
            let q = req.url!.query!
            XCTAssertTrue(q.contains("offset=300"), q)
            return #"{"items":[{"track":"# + Self.track(301) + #"}],"next":null}"#
        }
        let r = try await source().librarySongs(offset: 300)
        XCTAssertEqual(r.songs.count, 1)
        XCTAssertNil(r.next)
    }
}

private final class SearchStubProtocol: URLProtocol {
    private static let lock = NSLock()
    nonisolated(unsafe) static var route: @Sendable (URLRequest, Int) -> String = { _, _ in "{}" }
    nonisolated(unsafe) private static var seen: [URLRequest] = []
    static func reset() { lock.withLock { seen = [] } }
    static var requests: [URLRequest] { lock.withLock { seen } }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let n = Self.lock.withLock { () -> Int in Self.seen.append(request); return Self.seen.count }
        let body = Self.route(request, n)
        let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: nil)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}
