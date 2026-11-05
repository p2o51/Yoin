import XCTest
@testable import Yoin

/// Spotify Web API JSON → DTOs → Swift UI models (Data/Spotify), and the
/// client's paging / 429 / 401 behaviour against a URLProtocol stub.
final class SpotifyMappingTests: XCTestCase {
    private func decode<T: Decodable>(_ s: String, as: T.Type = T.self) throws -> T {
        try SpotifyWebAPI.decoder.decode(T.self, from: Data(s.utf8))
    }

    private let img = #"[{"url":"https://i.scdn.co/image/big","width":640,"height":640},{"url":"https://i.scdn.co/image/small","width":64,"height":64}]"#

    private func trackJSON(_ id: String, name: String = "Morning Static", n: Int = 1, artist: String = "Kiri Ono",
                           album: String = "Harbor Lights") -> String {
        """
        {"id":"\(id)","name":"\(name)","type":"track","duration_ms":245000,"track_number":\(n),"disc_number":1,
         "artists":[{"id":"ar1","name":"\(artist)"}],
         "album":{"id":"al1","name":"\(album)","album_type":"album","total_tracks":9,"release_date":"2021-05-24",
                  "images":\(img),"artists":[{"id":"ar1","name":"\(artist)"}]}}
        """
    }

    // MARK: Fixtures

    func test_should_map_album_with_tracks_when_full_album_decodes() throws {
        let a: SpotifyDTO.Album = try decode("""
        {"id":"al1","name":"Harbor Lights","album_type":"album","total_tracks":2,"release_date":"2021-05-24",
         "images":\(img),"artists":[{"id":"ar1","name":"Kiri Ono"}],
         "tracks":{"items":[\(trackJSON("t1", n: 1)),\(trackJSON("t2", name: "Low Tide", n: 2, artist: "Guest"))],"next":null,"total":2}}
        """)
        let album = try XCTUnwrap(SpotifyMapping.album(a, tracks: a.tracks?.items ?? [], saved: ["t2"]))
        XCTAssertEqual(album.id, MediaId(provider: "spotify", rawId: "al1"))
        XCTAssertEqual(album.year, 2021)
        XCTAssertEqual(album.artistId, "ar1")
        XCTAssertEqual(album.coverURL?.absoluteString, "https://i.scdn.co/image/big")
        XCTAssertEqual(album.tracks.map(\.title), ["Morning Static", "Low Tide"])
        XCTAssertNil(album.tracks[0].artist, "the album's own artist is left off")
        XCTAssertEqual(album.tracks[1].artist, "Guest")
        XCTAssertEqual([album.tracks[0].starred, album.tracks[1].starred], [false, true])
        XCTAssertEqual(album.totalSec, 490)
    }

    func test_should_call_a_four_track_single_an_ep_when_album_type_is_single() {
        XCTAssertEqual(SpotifyMapping.kind(albumType: "single", totalTracks: 1), .single)
        XCTAssertEqual(SpotifyMapping.kind(albumType: "single", totalTracks: 5), .ep)
        XCTAssertEqual(SpotifyMapping.kind(albumType: "compilation", totalTracks: 20), .compilation)
    }

    func test_should_skip_removed_local_and_episode_entries_when_playlist_has_them() throws {
        let p: SpotifyDTO.Playlist = try decode("""
        {"id":"pl1","name":"Mix","description":"A &amp; B <a href=\\"spotify:playlist:x\\">Top Hits</a>.","public":false,
         "owner":{"id":"u1","display_name":"51"},"images":\(img),
         "items":{"total":5,"next":null,"items":[
           {"item":\(trackJSON("t1"))},
           {"item":null},
           {"item":{"id":null,"name":"My Demo","is_local":true,"artists":[{"id":null,"name":"Me"}],
                    "album":{"id":null,"name":"","images":[]},"duration_ms":1000}},
           {"track":{"id":"ep1","name":"Episode","type":"episode"}},
           {"track":\(trackJSON("t2", name: "Low Tide", n: 2))}]}}
        """)
        let (tracks, offsets) = SpotifyMapping.playableTracks(p.entries?.items ?? [])
        XCTAssertEqual(tracks.compactMap(\.id), ["t1", "t2"])
        XCTAssertEqual(offsets, [0, 4], "raw playlist positions of the kept rows")
        let info = SpotifyMapping.playlist(p, tracks: tracks)
        XCTAssertEqual(info.entries.map(\.track.title), ["Morning Static", "Low Tide"])
        XCTAssertEqual(info.owner, "51")
        XCTAssertEqual(info.isPublic, false)
        XCTAssertEqual(info.description, "A & B Top Hits.")
        XCTAssertEqual(SpotifyMapping.playlistItem(p).count, 5)
    }

    func test_should_decode_playlist_stub_without_items_when_list_row_has_only_a_total() throws {
        let page: SpotifyDTO.Paging<SpotifyDTO.Playlist?> = try decode("""
        {"items":[null,{"id":"pl9","name":"Theirs","tracks":{"href":"x","total":12},"owner":{"id":"u2"}}],"next":null}
        """)
        let rows = page.items.compactMap { $0 }.map(SpotifyMapping.playlistItem)
        XCTAssertEqual(rows.count, 1)
        XCTAssertEqual(rows[0].count, 12)
        XCTAssertEqual(rows[0].owner, "u2")
    }

    func test_should_map_artist_page_newest_first_when_releases_come_unsorted() throws {
        let a: SpotifyDTO.Artist = try decode(#"{"id":"ar1","name":"Kiri Ono","images":\#(img)}"#)
        let rel: SpotifyDTO.Paging<SpotifyDTO.Album> = try decode("""
        {"items":[{"id":"a1","name":"Old","release_date":"2015","album_type":"album","images":[]},
                  {"id":"a2","name":"New","release_date":"2023-02-01","album_type":"single","total_tracks":1,"images":[]}]}
        """)
        let page = try XCTUnwrap(SpotifyMapping.artistPage(a, releases: rel.items.compactMap { SpotifyMapping.album($0) }, starred: true))
        XCTAssertEqual(page.releases.map(\.title), ["New", "Old"])
        XCTAssertEqual(page.releases[0].kind, .single)
        XCTAssertTrue(page.starred)
        XCTAssertTrue(page.mostPlayed.isEmpty)
    }

    func test_should_map_search_groups_when_response_has_null_entries() throws {
        let r: SpotifyDTO.Search = try decode("""
        {"tracks":{"items":[\(trackJSON("t1")),null]},
         "albums":{"items":[{"id":"al1","name":"Harbor Lights","images":\(img),"artists":[{"id":"ar1","name":"Kiri Ono"}]},null]},
         "artists":{"items":[{"id":"ar1","name":"Kiri Ono","images":\(img)},null]},
         "playlists":{"items":[null,{"id":"pl1","name":"Mix","owner":{"id":"u1","display_name":"51"}}]}}
        """)
        XCTAssertEqual((r.tracks?.items ?? []).compactMap { $0 }.count, 1)
        XCTAssertEqual((r.albums?.items ?? []).compactMap { $0 }.compactMap { SpotifyMapping.album($0) }.map(\.title), ["Harbor Lights"])
        XCTAssertEqual((r.artists?.items ?? []).compactMap { $0.flatMap(SpotifyMapping.artistItem) }.map(\.title), ["Kiri Ono"])
        XCTAssertEqual((r.playlists?.items ?? []).compactMap { $0 }.map(SpotifyMapping.playlistItem).map(\.owner), ["51"])
    }

    func test_should_map_recently_played_to_albums_once_each_when_tracks_repeat() throws {
        let r: SpotifyDTO.RecentlyPlayed = try decode("""
        {"items":[{"played_at":"2026-11-01T10:00:00Z","track":\(trackJSON("t1"))},
                  {"played_at":"2026-11-01T09:00:00Z","track":\(trackJSON("t2", n: 2))},
                  {"played_at":"2026-11-01T08:00:00Z","track":null}]}
        """)
        let played = r.items.compactMap(\.track)
        let albums = SpotifyMapping.uniqueById(played.compactMap { $0.album.flatMap { SpotifyMapping.album($0) } })
        XCTAssertEqual(albums.map(\.id.rawId), ["al1"])
        XCTAssertEqual(SpotifyMapping.entries(played).map(\.track.id.rawId), ["t1", "t2"])
        XCTAssertEqual(SpotifyMapping.entries(played)[0].album.title, "Harbor Lights")
    }

    func test_should_strip_html_and_decode_entities_when_description_is_editorial() {
        XCTAssertEqual(SpotifyMapping.plainText("Rock &#x27;n&#39; roll<br/>by <a href=\"x\">us</a>&nbsp;&hearts;"),
                       "Rock 'n' roll by us &hearts;")
        XCTAssertNil(SpotifyMapping.plainText(""))
        XCTAssertNil(SpotifyMapping.plainText("<p></p>"))
    }

    // MARK: Client (URLProtocol stub)

    private func makeAPI(maxRetries: Int = 2, sleeps: SleepLog = SleepLog(),
                         token: @escaping @Sendable () async throws -> String = { "tok" }) -> SpotifyWebAPI {
        let cfg = URLSessionConfiguration.ephemeral
        cfg.protocolClasses = [SpotifyStubProtocol.self]
        return SpotifyWebAPI(token: token, session: URLSession(configuration: cfg), maxRetries: maxRetries,
                             sleep: { sleeps.add($0) })
    }

    override func setUp() { SpotifyStubProtocol.reset() }

    func test_should_wait_retry_after_then_succeed_when_server_answers_429() async throws {
        let sleeps = SleepLog()
        SpotifyStubProtocol.route = { req, n in
            n == 1 ? .init(status: 429, headers: ["Retry-After": "3"], body: "") : .init(status: 200, body: #"{"items":[]}"#)
        }
        let r = try await makeAPI(sleeps: sleeps).get("me/albums", as: SpotifyDTO.Paging<SpotifyDTO.SavedAlbum>.self)
        XCTAssertTrue(r.items.isEmpty)
        XCTAssertEqual(sleeps.values, [3])
        XCTAssertEqual(SpotifyStubProtocol.requests.count, 2)
    }

    func test_should_throw_rate_limited_when_429_outlasts_the_retries() async {
        SpotifyStubProtocol.route = { _, _ in .init(status: 429, headers: ["Retry-After": "2"], body: "") }
        let sleeps = SleepLog()
        do {
            _ = try await makeAPI(maxRetries: 1, sleeps: sleeps).get("me/albums", as: SpotifyDTO.Paging<SpotifyDTO.Album>.self)
            XCTFail("expected rate limit")
        } catch {
            XCTAssertEqual(error as? SpotifyAPIError, .rateLimited(retryAfter: 2))
        }
        XCTAssertEqual(sleeps.values, [2])
        XCTAssertEqual(SpotifyStubProtocol.requests.count, 2)
    }

    func test_should_not_wait_and_throw_when_retry_after_is_longer_than_the_cap() async {
        SpotifyStubProtocol.route = { _, _ in .init(status: 429, headers: ["Retry-After": "3600"], body: "") }
        let sleeps = SleepLog()
        do {
            _ = try await makeAPI(sleeps: sleeps).get("me/albums", as: SpotifyDTO.Paging<SpotifyDTO.Album>.self)
            XCTFail("expected rate limit")
        } catch {
            XCTAssertEqual(error as? SpotifyAPIError, .rateLimited(retryAfter: 3600))
        }
        XCTAssertTrue(sleeps.values.isEmpty)
    }

    func test_should_surface_unauthorized_and_ask_the_token_again_when_server_answers_401() async throws {
        SpotifyStubProtocol.route = { req, n in
            n == 1 ? .init(status: 401, body: #"{"error":{"status":401,"message":"The access token expired"}}"#)
                : .init(status: 200, body: #"{"items":[]}"#)
        }
        let calls = SleepLog()
        let api = makeAPI(token: { calls.add(0); return "tok\(calls.values.count)" })
        do {
            _ = try await api.get("me/albums", as: SpotifyDTO.Paging<SpotifyDTO.Album>.self)
            XCTFail("expected 401")
        } catch {
            XCTAssertEqual(error as? SpotifyAPIError, .unauthorized)
        }
        // The caller refreshes and calls again once; the header carries the new token.
        _ = try await api.get("me/albums", as: SpotifyDTO.Paging<SpotifyDTO.Album>.self)
        XCTAssertEqual(SpotifyStubProtocol.requests.map { $0.value(forHTTPHeaderField: "Authorization") }, ["Bearer tok1", "Bearer tok2"])
    }

    func test_should_carry_spotify_message_when_request_fails_with_403() async {
        SpotifyStubProtocol.route = { _, _ in .init(status: 403, body: #"{"error":{"status":403,"message":"Forbidden"}}"#) }
        do {
            _ = try await makeAPI().get("playlists/x/items", as: SpotifyDTO.Paging<SpotifyDTO.PlaylistItem>.self)
            XCTFail("expected 403")
        } catch {
            XCTAssertEqual(error as? SpotifyAPIError, .http(status: 403, message: "Forbidden"))
            XCTAssertEqual(SpotifySource.message(for: error), "Forbidden")
        }
    }

    @MainActor
    func test_should_follow_next_links_and_cap_when_saved_tracks_span_pages() async throws {
        func item(_ json: String) -> String { #"{"added_at":"2026-01-01T00:00:00Z","track":\#(json)}"# }
        let page2 = #"{"items":[\#(item(trackJSON("t3"))),{"track":null}],"next":null,"total":4}"#
        let page1 = """
        {"items":[\(item(trackJSON("t1"))),\(item(trackJSON("t2")))],"total":4,
         "next":"https://api.spotify.com/v1/me/tracks?offset=2&limit=2"}
        """
        SpotifyStubProtocol.route = { req, _ in
            let offset = URLComponents(url: req.url!, resolvingAgainstBaseURL: false)?.queryItems?
                .first { $0.name == "offset" }?.value
            return .init(status: 200, body: offset == "2" ? page2 : page1)
        }
        let source = SpotifySource(api: makeAPI())
        let songs = try await source.librarySongs()
        XCTAssertEqual(songs.map(\.track.id.rawId), ["t1", "t2", "t3"])
        XCTAssertTrue(songs.allSatisfy(\.track.starred))
        let capped = try await SpotifySource(api: makeAPI()).librarySongs(cap: 2)
        XCTAssertEqual(capped.count, 2)
        let isSaved = await source.isSaved("t3")
        XCTAssertEqual(isSaved, true, "known from the read, no request")
    }

    @MainActor
    func test_should_open_playlist_without_tracks_when_items_answer_403() async throws {
        SpotifyStubProtocol.route = { req, _ in
            if req.url!.path.hasSuffix("/items") { return .init(status: 403, body: #"{"error":{"status":403,"message":"Forbidden"}}"#) }
            return .init(status: 200, body: #"{"id":"pl9","name":"Theirs","owner":{"id":"u2"},"items":{"href":"x","total":12}}"#)
        }
        let info = try await SpotifySource(api: makeAPI()).playlist("pl9")
        XCTAssertEqual(info?.title, "Theirs")
        XCTAssertEqual(info?.entries.count, 0)
    }

    @MainActor
    func test_should_return_nil_when_album_is_not_found() async throws {
        SpotifyStubProtocol.route = { _, _ in .init(status: 404, body: #"{"error":{"status":404,"message":"Not found"}}"#) }
        let album = try await SpotifySource(api: makeAPI()).album("nope")
        XCTAssertNil(album)
    }

    @MainActor
    func test_should_put_and_delete_library_uri_when_saving_and_unsaving_a_track() async throws {
        SpotifyStubProtocol.route = { _, _ in .init(status: 200, body: "") }
        let source = SpotifySource(api: makeAPI())
        try await source.save("t1")
        try await source.unsave("t1")
        let r = SpotifyStubProtocol.requests
        XCTAssertEqual(r.map(\.httpMethod), ["PUT", "DELETE"])
        XCTAssertTrue(r[0].url!.absoluteString.hasSuffix("/me/library?uris=spotify:track:t1"))
    }
}

// MARK: - Stub

private final class SleepLog: @unchecked Sendable {
    private let lock = NSLock()
    private var _values: [Int] = []
    var values: [Int] { lock.withLock { _values } }
    func add(_ v: Int) { lock.withLock { _values.append(v) } }
}

private final class SpotifyStubProtocol: URLProtocol {
    struct Reply {
        var status: Int
        var headers: [String: String] = [:]
        var body: String
    }

    private static let lock = NSLock()
    nonisolated(unsafe) static var route: @Sendable (URLRequest, Int) -> Reply = { _, _ in Reply(status: 500, body: "") }
    nonisolated(unsafe) private static var seen: [URLRequest] = []

    static func reset() { lock.withLock { seen = [] } }
    static var requests: [URLRequest] { lock.withLock { seen } }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let n = Self.lock.withLock { () -> Int in Self.seen.append(request); return Self.seen.count }
        let reply = Self.route(request, n)
        let response = HTTPURLResponse(url: request.url!, statusCode: reply.status, httpVersion: "HTTP/1.1",
                                       headerFields: reply.headers)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(reply.body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}
