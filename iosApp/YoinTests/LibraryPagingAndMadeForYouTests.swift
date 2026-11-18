import Foundation
import SwiftUI
import XCTest
@testable import Yoin

private final class Locked<Value>: @unchecked Sendable {
    private let lock = NSLock()
    private var storage: Value

    init(_ initial: Value) {
        self.storage = initial
    }

    func withValue<T>(_ body: (inout Value) -> T) -> T {
        lock.withLock { body(&storage) }
    }

    var value: Value {
        lock.withLock { storage }
    }
}

private func makeAppleSongResource(id: String, catalogId: String? = nil, name: String = "Song",
                                   artist: String = "Artist", isrc: String? = nil,
                                   albumId: String? = nil, artistId: String? = nil,
                                   url: String? = nil) -> AppleMusicJSON.Object {
    let isLib = catalogId != nil || id.hasPrefix("i.")
    var playParams: AppleMusicJSON.Object = ["id": id]
    if let catalogId { playParams["catalogId"] = catalogId }
    var attrs: AppleMusicJSON.Object = [
        "name": name,
        "artistName": artist,
        "albumName": "Album",
        "durationInMillis": 180_000,
        "playParams": playParams
    ]
    if let isrc { attrs["isrc"] = isrc }
    if let url { attrs["url"] = url }
    var rels: AppleMusicJSON.Object = [:]
    if let catalogId {
        var cAttrs: AppleMusicJSON.Object = ["name": name, "artistName": artist]
        if let isrc { cAttrs["isrc"] = isrc }
        rels["catalog"] = ["data": [["id": catalogId, "type": "songs", "attributes": cAttrs]]]
    }
    if let albumId {
        rels["albums"] = ["data": [["id": albumId, "type": "albums"]]]
    }
    if let artistId {
        rels["artists"] = ["data": [["id": artistId, "type": "artists", "attributes": ["name": artist]]]]
    }
    return [
        "id": id,
        "type": isLib ? "library-songs" : "songs",
        "attributes": attrs,
        "relationships": rels
    ]
}

private func makeSpotifyTrackJSON(_ id: String, name: String = "Title", artist: String = "Artist",
                                  artistId: String = "ar1", albumId: String = "al1",
                                  durationMs: Int = 210_000, isrc: String? = nil) -> String {
    let ext = isrc.map { "\"external_ids\":{\"isrc\":\"\($0)\"}," } ?? ""
    return """
    {"id":"\(id)","name":"\(name)","type":"track","duration_ms":\(durationMs),"track_number":1,"disc_number":1,
     \(ext)
     "artists":[{"id":"\(artistId)","name":"\(artist)"}],
     "album":{"id":"\(albumId)","name":"Album","album_type":"album","total_tracks":10,"release_date":"2024-01-01",
              "artists":[{"id":"\(artistId)","name":"\(artist)"}]}}
    """
}

@MainActor
final class LibraryPagingAndMadeForYouTests: XCTestCase {
    private var tempDirs: [URL] = []
    private var defaultSuites: [String] = []

    override func setUp() {
        super.setUp()
        LibraryDataStubProtocol.reset()
        FavoritesStore.shared.reset()
    }

    override func tearDown() {
        LibraryDataStubProtocol.reset()
        FavoritesStore.shared.reset()
        for dir in tempDirs { try? FileManager.default.removeItem(at: dir) }
        for suite in defaultSuites { UserDefaults.standard.removePersistentDomain(forName: suite) }
        tempDirs = []
        defaultSuites = []
        super.tearDown()
    }

    // MARK: - Helpers

    private struct MockHTTPStatusError: HTTPStatusError {
        let httpStatus: Int
    }

    private func makeStore(active: ActiveSource, appleMusic: AppleMusicSource? = nil,
                           spotify: SpotifySource? = nil) -> LibraryStore {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("yoin-t3-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        tempDirs.append(dir)
        let suite = "yoin-t3-\(UUID().uuidString)"
        defaultSuites.append(suite)
        let defaults = UserDefaults(suiteName: suite)!
        let sources = SourceSelection(subsonicSignedIn: false, defaults: defaults)
        sources.use(active)
        let apple = appleMusic ?? AppleMusicSource(storefront: "us") { _, _ in [:] }
        let notes = NotesStore(fileURL: dir.appendingPathComponent("notes.json"))
        let ratings = LocalRatings(fileURL: dir.appendingPathComponent("ratings.json"))
        let history = HistoryStore(fileURL: nil)
        let disk = DetailDiskCache(directory: dir.appendingPathComponent("disk"))
        let store = LibraryStore(session: ServerSession(), sources: sources, appleMusic: apple,
                                 history: history, notes: notes, ratings: ratings, disk: disk,
                                 spotifyAccount: spotify.map { ($0, "sp-test") })
        store.memoryHydrationSleep = { _ in }
        return store
    }

    private func makeSpotifyAPI(maxRetries: Int = 0) -> SpotifyWebAPI {
        let cfg = URLSessionConfiguration.ephemeral
        cfg.protocolClasses = [LibraryDataStubProtocol.self]
        return SpotifyWebAPI(token: { "tok" }, session: URLSession(configuration: cfg), maxRetries: maxRetries,
                             sleep: { _ in })
    }

    // MARK: - BoundedLRU

    func test_should_evict_oldest_and_preserve_nil_misses_when_capacity_exceeded() {
        var cache = BoundedLRU<String, String?>(capacity: 2)
        XCTAssertNil(cache.value(forKey: "missing"))
        cache.insert(nil, forKey: "a")
        cache.insert("valB", forKey: "b")
        // Cached nil miss returns `.some(nil)`.
        let hitA = cache.value(forKey: "a")
        XCTAssertNotNil(hitA)
        XCTAssertNil(hitA!)
        // Accessing "a" moved "a" to MRU, so inserting "c" evicts "b".
        cache.insert("valC", forKey: "c")
        XCTAssertEqual(cache.count, 2)
        XCTAssertNil(cache.value(forKey: "b"))
        XCTAssertNotNil(cache.value(forKey: "a"))
        XCTAssertEqual(cache.value(forKey: "c") ?? nil, "valC")
    }

    // MARK: - Apple Music Paging, MadeForYou, Metadata & Restore Error Propagation

    func test_should_page_apple_library_songs_up_to_2000_and_stop_at_cap() async throws {
        var requestedOffsets: [String] = []
        let source = AppleMusicSource(storefront: "us") { path, query in
            XCTAssertEqual(path, "/v1/me/library/songs")
            let offset = Int(query["offset"] ?? "0") ?? 0
            requestedOffsets.append(String(offset))
            let limit = Int(query["limit"] ?? "100") ?? 100
            let items = (0..<limit).map { idx in
                makeAppleSongResource(id: "s\(offset + idx)", catalogId: "\(10_000 + offset + idx)",
                                      name: "Track \(offset + idx)")
            }
            return [
                "data": items,
                "next": "/v1/me/library/songs?offset=\(offset + limit)"
            ]
        }

        let page1 = try await source.librarySongsPage(offset: 0, limit: 100)
        XCTAssertEqual(page1.songs.count, 100)
        XCTAssertEqual(page1.nextOffset, 100)

        let lastPage = try await source.librarySongsPage(offset: 1900, limit: 100)
        XCTAssertEqual(lastPage.songs.count, 100)
        XCTAssertNil(lastPage.nextOffset, "pagination stops once offset hits 2000")

        let pastCap = try await source.librarySongsPage(offset: 2000, limit: 100)
        XCTAssertTrue(pastCap.songs.isEmpty)
        XCTAssertNil(pastCap.nextOffset)
        XCTAssertEqual(requestedOffsets, ["0", "1900"])
    }

    func test_should_extract_and_hydrate_personal_mixes_when_madeForYou_called() async throws {
        var requestedPaths: [String] = []
        let source = AppleMusicSource(storefront: "us") { path, query in
            requestedPaths.append(path)
            if path == "/v1/me/recommendations" {
                return [
                    "data": [
                        [
                            "id": "rec1",
                            "type": "personal-recommendation",
                            "relationships": [
                                "contents": [
                                    "data": [
                                        ["id": "pl.1234", "type": "playlists", "attributes": ["name": "Not a PM"]],
                                        ["id": "pl.pm-favorites", "type": "playlists",
                                         "attributes": ["name": "Favorites Mix", "curatorName": "Apple Music"]],
                                        ["id": "pl.pm-chill", "type": "playlists"],
                                        ["id": "pl.pm-favorites", "type": "playlists"]
                                    ]
                                ]
                            ]
                        ]
                    ]
                ]
            }
            if path == "/v1/catalog/us/playlists" {
                XCTAssertEqual(query["ids"], "pl.pm-chill")
                return [
                    "data": [
                        [
                            "id": "pl.pm-chill",
                            "type": "playlists",
                            "attributes": [
                                "name": "Chill Mix",
                                "description": ["short": "Laid-back tracks"]
                            ]
                        ]
                    ]
                ]
            }
            XCTFail("unexpected path \(path)")
            return [:]
        }

        let mixes = try await source.madeForYou()
        XCTAssertEqual(mixes.map(\.id.rawId), ["pl.pm-favorites", "pl.pm-chill"])
        XCTAssertEqual(mixes.map(\.title), ["Favorites Mix", "Chill Mix"])
        XCTAssertEqual(mixes[1].subtitle, "Laid-back tracks")
        XCTAssertEqual(requestedPaths, ["/v1/me/recommendations", "/v1/catalog/us/playlists"])
    }

    func test_should_lookup_enUS_alternateNames_with_lru_and_skip_english_storefront() async {
        var calls: [String] = []
        let jpSource = AppleMusicSource(storefront: "jp") { path, query in
            calls.append("\(path)?\(query["l"] ?? "")")
            if path == "/v1/storefronts/jp" {
                return ["data": [["id": "jp", "type": "storefronts",
                                  "attributes": ["defaultLanguageTag": "ja-JP",
                                                 "supportedLanguageTags": ["ja-JP", "en-US"]]]]]
            }
            return ["data": [makeAppleSongResource(id: "12345", name: "Sunny", artist: "Yorushika")]]
        }
        jpSource.nameRegion = { .off }

        let first = await jpSource.alternateNames(trackId: "12345")
        XCTAssertEqual(first.count, 1)
        XCTAssertEqual(first.first?.title, "Sunny")
        XCTAssertEqual(first.first?.artist, "Yorushika")
        // Second call hits 256-entry LRU cache.
        let second = await jpSource.alternateNames(trackId: "12345")
        XCTAssertEqual(second.first?.title, "Sunny")
        XCTAssertEqual(calls, ["/v1/storefronts/jp?", "/v1/catalog/jp/songs/12345?en-US"])

        // Library-only track with no catalog id returns empty without requests.
        let libEmpty = await jpSource.alternateNames(trackId: "library:i.onlylocal")
        XCTAssertTrue(libEmpty.isEmpty)

        // English default storefront skips catalog lookup when nameRegion == .off.
        var usCalls: [String] = []
        let usSource = AppleMusicSource(storefront: "us") { path, _ in
            usCalls.append(path)
            return ["data": [["id": "us", "type": "storefronts",
                              "attributes": ["defaultLanguageTag": "en-US",
                                             "supportedLanguageTags": ["en-US"]]]]]
        }
        usSource.nameRegion = { .off }
        let usRes = await usSource.alternateNames(trackId: "999")
        XCTAssertTrue(usRes.isEmpty)
        XCTAssertEqual(usCalls, ["/v1/storefronts/us"])
    }

    func test_should_resolve_and_cache_apple_isrc_and_songLinks() async {
        var hits = 0
        let source = AppleMusicSource(storefront: "us") { path, query in
            hits += 1
            if path == "/v1/catalog/us/songs/555" && query["include"] == "albums,artists" {
                return ["data": [makeAppleSongResource(id: "555", name: "S", artist: "Real Artist",
                                                       albumId: "al77", artistId: "ar88")]]
            }
            return ["data": [makeAppleSongResource(id: "555", isrc: "  jpab12345678 ")]]
        }

        let code1 = await source.isrc(trackId: "555")
        let code2 = await source.isrc(trackId: "555")
        XCTAssertEqual(code1, "JPAB12345678")
        XCTAssertEqual(code2, "JPAB12345678")
        XCTAssertEqual(hits, 1)

        let links1 = await source.songLinks(trackId: "555")
        let links2 = await source.songLinks(trackId: "555")
        XCTAssertEqual(links1.albumId, "al77")
        XCTAssertEqual(links1.artistId, "ar88")
        XCTAssertEqual(links1.artistName, "Real Artist")
        XCTAssertEqual(links2.albumId, "al77")
        XCTAssertEqual(hits, 2)
    }

    func test_should_rethrow_auth_and_rate_limit_errors_when_catalogSongsByIsrcThrowing_called() async {
        for status in [401, 403, 429] {
            let source = AppleMusicSource(storefront: "us") { _, _ in
                throw MockHTTPStatusError(httpStatus: status)
            }
            do {
                _ = try await source.catalogSongsByIsrcThrowing(["USRC17607839"])
                XCTFail("expected status \(status) to throw")
            } catch {
                XCTAssertEqual(AppleMusicSource.httpStatus(of: error), status)
            }
            // Non-throwing Stage 1 wrapper remains safe.
            let swallowed = await source.catalogSongsByIsrc(["USRC17607839"])
            XCTAssertTrue(swallowed.isEmpty)
        }
    }

    // MARK: - Spotify DTOs, QueueEntry, market=from_token, Paging 2000, ISRC & nativeNames

    func test_should_decode_isRestricted_durationMs_and_isrc_preserving_QueueEntry_compat() throws {
        // Backwards-compatible QueueEntry init without durationMs compiles and defaults nil.
        let compat = SpotifySource.QueueEntry(id: 0, uri: "spotify:track:a", title: "A", artist: "B", coverURL: nil)
        XCTAssertNil(compat.durationMs)

        let trackData = Data(makeSpotifyTrackJSON("t1", durationMs: 195_432, isrc: "USRC12345678").utf8)
        let dtoTrack = try SpotifyWebAPI.decoder.decode(SpotifyDTO.Track.self, from: trackData)
        XCTAssertEqual(dtoTrack.externalIds?.isrc, "USRC12345678")

        let entries = SpotifySource.queueEntries([dtoTrack])
        XCTAssertEqual(entries.first?.durationMs, 195_432)

        let devData = Data(#"{"id":"d1","name":"Sonos","type":"Speaker","is_active":true,"is_restricted":true,"is_private_session":true}"#.utf8)
        let dev = try SpotifyWebAPI.decoder.decode(SpotifyDTO.Device.self, from: devData)
        XCTAssertEqual(dev.isRestricted, true)

        let now = SpotifySource.PlayerNow(deviceId: nil, deviceName: "Sonos", deviceType: "Speaker",
                                          isPrivateSession: true, isPlaying: true, isRestricted: true)
        let merged = SpotifySource.withPlaying([], now: now)
        XCTAssertEqual(merged.count, 1)
        XCTAssertTrue(merged[0].restricted)
        XCTAssertTrue(merged[0].privateSession)
    }

    func test_should_pass_market_from_token_on_catalog_track_album_and_playerNow() async throws {
        let queries = Locked([String: String]())
        LibraryDataStubProtocol.route = { req, _ in
            let path = req.url!.path
            let query = req.url!.query ?? ""
            queries.withValue { $0[path] = query }
            if path.hasSuffix("/tracks/t1") {
                return .init(status: 200, body: makeSpotifyTrackJSON("t1"))
            }
            if path.hasSuffix("/albums/al1") {
                return .init(status: 200, body: #"{"id":"al1","name":"A","artists":[],"tracks":{"items":[]}}"#)
            }
            return .init(status: 200, body: #"{"device":{"id":"d1","name":"Phone","type":"Smartphone","is_restricted":true},"is_playing":true,"item":{"uri":"spotify:track:t1","type":"track","duration_ms":200000,"name":"T"}}"#)
        }
        let source = SpotifySource(api: makeSpotifyAPI())
        _ = try await source.track("t1")
        _ = try await source.album("al1")
        let now = try await source.playerNow()
        let recorded = queries.value
        XCTAssertTrue(recorded["/v1/tracks/t1"]?.contains("market=from_token") == true)
        XCTAssertTrue(recorded["/v1/albums/al1"]?.contains("market=from_token") == true)
        XCTAssertTrue(recorded["/v1/me/player"]?.contains("market=from_token") == true)
        XCTAssertEqual(now?.isRestricted, true)
    }

    func test_should_page_spotify_savedTracksPage_up_to_2000_and_stop_at_cap() async throws {
        LibraryDataStubProtocol.route = { req, _ in
            let q = URLComponents(url: req.url!, resolvingAgainstBaseURL: false)?.queryItems ?? []
            let offset = Int(q.first { $0.name == "offset" }?.value ?? "0") ?? 0
            let limit = Int(q.first { $0.name == "limit" }?.value ?? "50") ?? 50
            let items = (0..<limit).map { i in
                #"{"added_at":"2026-01-01T00:00:00Z","track":\#(makeSpotifyTrackJSON("sp\(offset + i)"))}"#
            }.joined(separator: ",")
            return .init(status: 200, body: #"{"items":[\#(items)],"next":"https://api.spotify.com/v1/me/tracks?offset=\#(offset + limit)&limit=\#(limit)","total":2500}"#)
        }
        let source = SpotifySource(api: makeSpotifyAPI())
        let page0 = try await source.savedTracksPage(offset: 0)
        XCTAssertEqual(page0.songs.count, 50)
        XCTAssertEqual(page0.next, 50)

        let page1950 = try await source.savedTracksPage(offset: 1950)
        XCTAssertEqual(page1950.songs.count, 50)
        XCTAssertNil(page1950.next, "savedTracksPage stops at 2000")

        let pastCap = try await source.savedTracksPage(offset: 2000)
        XCTAssertTrue(pastCap.songs.isEmpty)
        XCTAssertNil(pastCap.next)
    }

    func test_should_deduplicate_and_cache_spotify_nativeNames_and_isrc() async throws {
        let calls = Locked([(String, String)]())
        let source = SpotifySource(api: makeSpotifyAPI()) { id, lang in
            calls.withValue { $0.append((id, lang)) }
            switch lang.lowercased() {
            case "ja", "ja-jp":
                return try SpotifyWebAPI.decoder.decode(
                    SpotifyDTO.Track.self,
                    from: Data(makeSpotifyTrackJSON(id, name: "晴る", artist: "ヨルシカ", isrc: "jpco02400123").utf8))
            default:
                return try SpotifyWebAPI.decoder.decode(
                    SpotifyDTO.Track.self,
                    from: Data(makeSpotifyTrackJSON(id, name: "Haru", artist: "Yorushika", isrc: "jpco02400123").utf8))
            }
        }
        let names = await source.nativeNames(trackId: "4uLU6hMCjMI75M1A2tKUQC",
                                            acceptLanguages: ["ja", "JA", "en", "en-US"])
        // "JA" is deduped against "ja"; "en" & "en-US" produce identical (Haru, Yorushika) so pair is deduped.
        XCTAssertEqual(names.map(\.title), ["晴る", "Haru"])
        XCTAssertEqual(calls.value.map(\.1), ["ja", "en", "en-US"])

        // Cached LRU hit makes no extra requests.
        let cachedNames = await source.nativeNames(trackId: "4uLU6hMCjMI75M1A2tKUQC",
                                                  acceptLanguages: ["ja", "en", "en-US"])
        XCTAssertEqual(cachedNames.map(\.title), ["晴る", "Haru"])
        XCTAssertEqual(calls.value.count, 3)

        // ISRC seeded from nativeNames doesn't hit network either.
        let isrc = await source.isrc(trackId: "4uLU6hMCjMI75M1A2tKUQC")
        XCTAssertEqual(isrc, "JPCO02400123")
        XCTAssertEqual(LibraryDataStubProtocol.requests.count, 0)
    }

    // MARK: - LibraryStore Paging, #65 Sync, hydrateMemoryAlbums, MadeForYou, FailureReports, SongLinks

    func test_should_paginate_apple_and_spotify_songs_past_300_and_report_errors() async {
        // Apple Music loadSongs + loadMoreSongs + error reporting
        var failSecondPage = true
        let apple = AppleMusicSource(storefront: "us") { path, query in
            XCTAssertEqual(path, "/v1/me/library/songs")
            let offset = Int(query["offset"] ?? "0") ?? 0
            if offset == 100 && failSecondPage { throw MockHTTPStatusError(httpStatus: 503) }
            let items = (0..<100).map { i in
                makeAppleSongResource(id: "s\(offset + i)", catalogId: "\(50_000 + offset + i)",
                                      name: "Song \(offset + i)")
            }
            return ["data": items, "next": "/v1/me/library/songs?offset=\(offset + 100)"]
        }
        let appleStore = makeStore(active: .appleMusic, appleMusic: apple)
        await appleStore.loadSongs()
        XCTAssertEqual(appleStore.songs.count, 100)
        XCTAssertEqual(appleStore.songsMore, .available)

        await appleStore.loadMoreSongs()
        XCTAssertEqual(appleStore.songsMore, .failed)
        XCTAssertNotNil(appleStore.failureReports["songsMore"])

        failSecondPage = false
        await appleStore.loadMoreSongs()
        XCTAssertEqual(appleStore.songs.count, 200)
        XCTAssertEqual(appleStore.songsMore, .available)
        XCTAssertNil(appleStore.failureReports["songsMore"])

        // Page through 400 songs (past old 300 cap).
        await appleStore.loadMoreSongs()
        await appleStore.loadMoreSongs()
        XCTAssertEqual(appleStore.songs.count, 400)
        XCTAssertEqual(appleStore.songsMore, .available)

        // Spotify Liked Songs paging past 300 via savedTracksPage
        LibraryDataStubProtocol.route = { req, _ in
            let q = URLComponents(url: req.url!, resolvingAgainstBaseURL: false)?.queryItems ?? []
            let offset = Int(q.first { $0.name == "offset" }?.value ?? "0") ?? 0
            let items = (0..<50).map { i in
                #"{"added_at":"2026-01-01T00:00:00Z","track":\#(makeSpotifyTrackJSON("sp\(offset + i)"))}"#
            }.joined(separator: ",")
            return .init(status: 200, body: #"{"items":[\#(items)],"next":"https://api.spotify.com/v1/me/tracks?offset=\#(offset + 50)&limit=50","total":1000}"#)
        }
        let spotify = SpotifySource(api: makeSpotifyAPI())
        let spStore = makeStore(active: .spotify, spotify: spotify)
        await spStore.loadSongs()
        XCTAssertEqual(spStore.songs.count, 50)
        XCTAssertEqual(spStore.songsMore, .available)
        for _ in 0..<6 { await spStore.loadMoreSongs() }
        XCTAssertEqual(spStore.songs.count, 350, "Spotify songs paginate past old 300 truncation")
        XCTAssertEqual(spStore.songsMore, .available)
    }

    func test_should_keep_store_songs_in_sync_when_liked_status_toggles_65() async {
        LibraryDataStubProtocol.route = { req, _ in
            XCTAssertTrue(req.url!.path.hasSuffix("/me/tracks"))
            let items = [makeSpotifyTrackJSON("s1", name: "One"), makeSpotifyTrackJSON("s2", name: "Two")]
                .map { #"{"added_at":"2026-01-01T00:00:00Z","track":\#($0)}"# }
                .joined(separator: ",")
            return .init(status: 200, body: #"{"items":[\#(items)],"next":null,"total":2}"#)
        }
        let spotify = SpotifySource(api: makeSpotifyAPI())
        let store = makeStore(active: .spotify, spotify: spotify)
        await store.loadSongs()
        XCTAssertEqual(store.songs.map(\.track.id.rawId), ["s1", "s2"])
        guard store.songs.count == 2 else { return }

        // Unlike s1 via toggleStar -> removed immediately from store.songs.
        store.toggleStar(store.songs[0].track)
        XCTAssertEqual(store.songs.map(\.track.id.rawId), ["s2"])

        // Re-like s1 via toggleStar -> restored locally at top of store.songs.
        let s1Track = Track(id: MediaId(provider: SpotifyMapping.provider, rawId: "s1"),
                            number: 1, title: "One", durationSec: 180, artist: "Artist", starred: false)
        store.toggleStar(s1Track)
        XCTAssertEqual(store.songs.map(\.track.id.rawId), ["s1", "s2"])
        XCTAssertTrue(store.songs[0].track.starred)

        // Like a brand-new track s3 not yet in store.songs -> appears at top immediately.
        let s3Track = Track(id: MediaId(provider: SpotifyMapping.provider, rawId: "s3"),
                            number: 1, title: "Three", durationSec: 180, artist: "Artist", starred: false)
        store.toggleStar(s3Track)
        XCTAssertEqual(store.songs.map(\.track.id.rawId), ["s3", "s1", "s2"])
    }

    func test_should_cap_throttle_skip_hearts_and_abort_on_429_or_offline_when_hydrating_memory_albums() async {
        // 1. Cap at 24 albums, pause between batches of 2, skip me/tracks/contains (fillSpotifyHearts).
        let albumRequests = Locked([String]())
        let containsCalls = Locked(0)
        LibraryDataStubProtocol.route = { req, _ in
            if req.url!.path.hasSuffix("/me/tracks/contains") {
                containsCalls.withValue { $0 += 1 }
                return .init(status: 200, body: "[]")
            }
            let rawId = req.url!.lastPathComponent
            albumRequests.withValue { $0.append(rawId) }
            return .init(status: 200, body: """
            {"id":"\(rawId)","name":"Album \(rawId)","artists":[{"id":"ar1","name":"Artist"}],
             "tracks":{"items":[\(makeSpotifyTrackJSON("trk-\(rawId)", albumId: rawId))]}}
            """)
        }
        let store = makeStore(active: .spotify, spotify: SpotifySource(api: makeSpotifyAPI()))
        for i in 0..<30 {
            let aid = String(format: "al%02d", i)
            let sid = "trk-\(aid)"
            store.history.recordPlay(scope: store.historyScope, provider: SpotifyMapping.provider,
                                     songId: sid, albumId: aid, artistId: "ar1",
                                     title: "T\(i)", album: "Album \(i)", artist: "Artist",
                                     coverKey: nil, durationSec: 180)
            store.notes.add(provider: SpotifyMapping.provider, trackId: sid, content: "note \(i)",
                            positionMs: nil, title: "T\(i)", artist: "Artist")
        }
        let pauses = Locked(0)
        store.memoryHydrationSleep = { _ in pauses.withValue { $0 += 1 } }
        let landed = await store.hydrateMemoryAlbums()
        XCTAssertTrue(landed)
        XCTAssertEqual(albumRequests.value.count, 24, "caps one pass at 24 albums")
        XCTAssertEqual(pauses.value, 11, "12 batches of 2 -> 11 pauses")
        XCTAssertEqual(containsCalls.value, 0, "skips fillSpotifyHearts during background hydration")

        // 2. Abort immediately on 429 without firing remaining batches.
        LibraryDataStubProtocol.reset()
        let abortedRequests = Locked(0)
        LibraryDataStubProtocol.route = { _, _ in
            abortedRequests.withValue { $0 += 1 }
            return .init(status: 429, headers: ["Retry-After": "60"], body: "")
        }
        let rateLimitedStore = makeStore(active: .spotify, spotify: SpotifySource(api: makeSpotifyAPI()))
        for i in 0..<10 {
            let aid = String(format: "rl%02d", i)
            let sid = "trk-\(aid)"
            rateLimitedStore.history.recordPlay(scope: rateLimitedStore.historyScope, provider: SpotifyMapping.provider,
                                                songId: sid, albumId: aid, artistId: "ar1",
                                                title: "T\(i)", album: "Album \(i)", artist: "Artist",
                                                coverKey: nil, durationSec: 180)
            rateLimitedStore.notes.add(provider: SpotifyMapping.provider, trackId: sid, content: "note \(i)",
                                       positionMs: nil, title: "T\(i)", artist: "Artist")
        }
        let rateLimitedPauses = Locked(0)
        rateLimitedStore.memoryHydrationSleep = { _ in rateLimitedPauses.withValue { $0 += 1 } }
        let rlLanded = await rateLimitedStore.hydrateMemoryAlbums()
        XCTAssertFalse(rlLanded)
        XCTAssertEqual(abortedRequests.value, LibraryStore.memoryHydrationBatch, "stops after first failing batch")
        XCTAssertEqual(rateLimitedPauses.value, 0)

        // 3. Offline URLError detection aborts too.
        XCTAssertTrue(LibraryStore.shouldAbortHydration(URLError(.notConnectedToInternet)))
        XCTAssertFalse(LibraryStore.shouldAbortHydration(MockHTTPStatusError(httpStatus: 404)))
    }

    func test_should_load_madeForYou_home_contract_failureReports_and_songLinks() async {
        // Apple Music loadMadeForYou + loadSongLinks
        let apple = AppleMusicSource(storefront: "us") { path, _ in
            if path == "/v1/me/recommendations" {
                return ["data": [["id": "r1", "type": "personal-recommendation",
                                  "relationships": ["contents": ["data": [
                                    ["id": "pl.pm-mix1", "type": "playlists", "attributes": ["name": "New Music Mix"]]
                                  ]]]]]]
            }
            if path == "/v1/catalog/us/songs/777" {
                return ["data": [makeAppleSongResource(id: "777", name: "T", artist: "Singer",
                                                       albumId: "al777", artistId: "ar777")]]
            }
            throw MockHTTPStatusError(httpStatus: 500)
        }
        let store = makeStore(active: .appleMusic, appleMusic: apple)
        await store.loadMadeForYou()
        XCTAssertEqual(store.madeForYou.map(\.title), ["New Music Mix"])
        XCTAssertEqual(store.madeForYouPhase, .loaded)

        // Failure reports on Home & Search + helper API
        await store.loadHome(force: true)
        XCTAssertEqual(store.homePhase, .failed)
        XCTAssertNotNil(store.failureReports["home"])

        await store.runSearch("test", scope: .catalog)
        XCTAssertEqual(store.searchPhase, .failed)
        XCTAssertNotNil(store.failureReports["search"])

        store.setFailureReport("searchMore:tracks", error: MockHTTPStatusError(httpStatus: 429))
        XCTAssertNotNil(store.failureReports["searchMore:tracks"])
        store.setFailureReport("searchMore:tracks", error: nil)
        XCTAssertNil(store.failureReports["searchMore:tracks"])

        let links = await store.loadSongLinks(for: Track(id: MediaId(provider: AppleMusicMapping.provider, rawId: "777"),
                                                         number: 1, title: "T", durationSec: 120))
        XCTAssertEqual(links.albumId, "al777")
        XCTAssertEqual(links.artistId, "ar777")
        XCTAssertEqual(links.artistName, "Singer")

        // Stage 2 Spotify MadeForYou hook
        let spStore = makeStore(active: .spotify, spotify: SpotifySource(api: makeSpotifyAPI()))
        let spMix = LibraryItem(id: MediaId(provider: SpotifyMapping.provider, rawId: "pl-daylist"), kind: .playlist,
                                title: "daylist", subtitle: "Spotify", palette: CoverPalette(a: .red, b: .blue, c: .green))
        spStore.setSpotifyMadeForYou([spMix])
        XCTAssertEqual(spStore.madeForYou, [spMix])
        XCTAssertEqual(spStore.madeForYouPhase, .loaded)

        // HomeLayout Made For You promotion into HomeSection
        XCTAssertEqual(HomeSection.madeForYou.id, "made_for_you")
        XCTAssertEqual(MadeForYouSectionContract.rawId, "made_for_you")
        let layout = HomeLayout.reconcile([
            HomeSectionPref(id: HomeSection.madeForYou.id, enabled: true),
            HomeSectionPref(id: "activities", enabled: true)
        ])
        XCTAssertEqual(layout.sections.first?.section, .madeForYou)
        XCTAssertTrue(layout.retained.isEmpty)
    }
}

private final class LibraryDataStubProtocol: URLProtocol {
    struct StubResponse {
        var status: Int
        var headers: [String: String] = [:]
        var body: String
    }

    private static let lock = NSLock()
    nonisolated(unsafe) static var route: @Sendable (URLRequest, Int) -> StubResponse = { _, _ in
        StubResponse(status: 200, body: "{}")
    }
    nonisolated(unsafe) private static var seen: [URLRequest] = []
    static func reset() {
        lock.withLock {
            seen = []
            route = { _, _ in StubResponse(status: 200, body: "{}") }
        }
    }
    static var requests: [URLRequest] { lock.withLock { seen } }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let count = Self.lock.withLock { () -> Int in Self.seen.append(request); return Self.seen.count }
        let res = Self.route(request, count)
        let response = HTTPURLResponse(url: request.url!, statusCode: res.status, httpVersion: "HTTP/1.1",
                                       headerFields: res.headers)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(res.body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}
