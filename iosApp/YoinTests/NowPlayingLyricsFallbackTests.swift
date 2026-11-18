import XCTest
import YoinShared
@testable import Yoin

@MainActor
final class NowPlayingLyricsFallbackTests: XCTestCase {
    private func makeData(
        autoFetch: @escaping @MainActor (String, String, Int64?) async throws -> LyricsResult?,
        musicBrainzNames: @escaping @Sendable (String, Int64?) async throws -> [(title: String, artist: String)] = { _, _ in [] },
        aiHasKey: @escaping @MainActor () -> Bool = { false },
        aiResolveReleaseNames: @escaping @Sendable (String, String, String?, String?, Int64?, [(title: String, artist: String)]) async throws -> (title: String, artist: String)? = { _, _, _, _, _, _ in nil },
        acceptLanguages: @escaping @Sendable (String?) -> [String] = { SpotifyNameLanguages.acceptLanguages(isrc: $0, deviceLanguage: "ja-JP") }
    ) -> NowPlayingData {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("np-lyrics-test-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: dir) }
        return NowPlayingData(
            ratings: LocalRatings(fileURL: dir.appendingPathComponent("ratings.json")),
            hearts: FavoritesStore(),
            outbox: RatingOutbox(fileURL: nil),
            userStore: LyricsUserStore(fileURL: dir.appendingPathComponent("lyrics-user.json")),
            dependencies: .init(
                autoFetch: autoFetch,
                musicBrainzNames: musicBrainzNames,
                aiHasKey: aiHasKey,
                aiResolveReleaseNames: aiResolveReleaseNames,
                acceptLanguages: acceptLanguages
            )
        )
    }

    private func lyricHit(_ lrc: String, provider: String = "netease", id: String = "100") -> LyricsResult {
        LyricsResult(lrc: lrc, lyrics: LrcParser.shared.parse(raw: lrc), providerName: provider, providerSongId: id)
    }

    private func appleTrack(id: String = "123456", title: String = "キム・ペトラス", artist: String = "キム・ペトラス") -> PlayableTrack {
        PlayableTrack(
            id: id,
            provider: AppleMusicMapping.provider,
            title: title,
            artist: artist,
            album: "Feed The Beast",
            albumId: "al1",
            durationSec: 210,
            streamURL: URL(string: "https://example.com/am/\(id)")!
        )
    }

    private func spotifyTrack(id: String = "spTrack1", title: String = "Ferries wheel", artist: String = "Hebe Tien") -> PlayableTrack {
        PlayableTrack(
            id: id,
            provider: SpotifyMapping.provider,
            title: title,
            artist: artist,
            album: "To Hebe",
            albumId: "alSp1",
            durationSec: 245,
            streamURL: SpotifyAudioOutput.streamURL(trackId: id)
        )
    }

    private func makeSpotifySource(
        handler: @escaping @Sendable (URLRequest) -> (Int, [String: String], String)
    ) -> SpotifySource {
        FallbackURLStub.reset()
        FallbackURLStub.handler = handler
        let cfg = URLSessionConfiguration.ephemeral
        cfg.protocolClasses = [FallbackURLStub.self]
        let api = SpotifyWebAPI(
            token: { "tok" },
            gate: SpotifyRateGate(),
            session: URLSession(configuration: cfg),
            maxRetries: 0,
            sleep: { _ in }
        )
        return SpotifySource(api: api)
    }

    func test_should_detectNonLatinLettersAccurately() {
        XCTAssertFalse(NowPlayingData.hasNonLatinLetter("Kim Petras"))
        XCTAssertFalse(NowPlayingData.hasNonLatinLetter("Café del Mar"))
        XCTAssertFalse(NowPlayingData.hasNonLatinLetter("123 — ?!"))
        XCTAssertTrue(NowPlayingData.hasNonLatinLetter("キム・ペトラス"))
        XCTAssertTrue(NowPlayingData.hasNonLatinLetter("晴る"))
        XCTAssertTrue(NowPlayingData.hasNonLatinLetter("田馥甄"))
        XCTAssertTrue(NowPlayingData.hasNonLatinLetter("아이유"))
    }

    func test_should_tryAppleAlternateNames_whenShownIsNonLatin() async {
        var queries: [(String, String)] = []
        let apple = AppleMusicSource(
            storefrontProvider: { "jp" },
            transport: { path, query in
                if path == "/v1/storefronts/jp" {
                    return ["data": [["id": "jp", "attributes": ["defaultLanguageTag": "ja", "supportedLanguageTags": ["ja", "en-us"]]]]]
                }
                if path == "/v1/catalog/jp/songs/123456", query["l"] == "en-US" {
                    return ["data": [["id": "123456", "attributes": ["name": "Alone", "artistName": "Kim Petras"]]]]
                }
                return ["data": []]
            }
        )
        let data = makeData(autoFetch: { title, artist, _ in
            queries.append((title, artist))
            if title == "Alone" && artist == "Kim Petras" {
                return self.lyricHit("[00:01.00]I've been waiting", provider: "lrclib", id: "l1")
            }
            return nil
        })

        let track = appleTrack(title: "アローン", artist: "キム・ペトラス")
        await data.prepare(track, client: nil, apple: apple)

        XCTAssertEqual(queries.map { "\($0.0)|\($0.1)" }, ["アローン|キム・ペトラス", "Alone|Kim Petras"])
        if case .lines(let loaded) = data.lyricsState(track) {
            XCTAssertEqual(loaded.lines, ["I've been waiting"])
        } else {
            XCTFail("Expected lyrics from Apple alternateNames fallback")
        }
        XCTAssertFalse(data.lyricsRetry.contains(NowPlayingData.key(track)))
    }

    func test_should_followPr226Order_shownNativeMusicBrainzThenAi_forSpotifyTrack() async {
        let events = LockedBox<[String]>([])
        let spotify = makeSpotifySource { req in
            let lang = req.value(forHTTPHeaderField: "Accept-Language") ?? "none"
            events.withValue { $0.append("spotify:\(lang)") }
            if lang == "zh-TW" {
                return (200, [:], #"{"id":"spTrack1","name":"摩天輪","artists":[{"name":"田馥甄"}],"external_ids":{"isrc":"TWD950519106"}}"#)
            }
            return (200, [:], #"{"id":"spTrack1","name":"Ferries wheel","artists":[{"name":"Hebe Tien"}],"external_ids":{"isrc":"TWD950519106"}}"#)
        }
        let data = makeData(
            autoFetch: { title, artist, _ in
                events.withValue { $0.append("autoFetch:\(title)|\(artist)") }
                if title == "摩天輪 (Original)" && artist == "田馥甄" {
                    return self.lyricHit("[00:02.00]摩天輪轉呀轉", provider: "qq", id: "q99")
                }
                return nil
            },
            musicBrainzNames: { isrc, durationMs in
                events.withValue { $0.append("mb:\(isrc)|\(durationMs ?? -1)") }
                return [("摩天輪 MB", "田馥甄")]
            },
            aiHasKey: { true },
            aiResolveReleaseNames: { title, artist, _, isrc, _, otherNames in
                events.withValue { $0.append("ai:\(title)|\(artist)|\(isrc ?? "-")|other=\(otherNames.count)") }
                return ("摩天輪 (Original)", "田馥甄")
            }
        )

        let track = spotifyTrack()
        await data.prepare(track, client: nil, spotify: spotify)

        XCTAssertEqual(events.value, [
            "autoFetch:Ferries wheel|Hebe Tien",
            "spotify:none",
            "spotify:ja-JP",
            "spotify:zh-TW",
            "autoFetch:摩天輪|田馥甄",
            "mb:TWD950519106|245000",
            "autoFetch:摩天輪 MB|田馥甄",
            "ai:Ferries wheel|Hebe Tien|TWD950519106|other=2",
            "autoFetch:摩天輪 (Original)|田馥甄",
        ])
        if case .lines(let loaded) = data.lyricsState(track) {
            XCTAssertEqual(loaded.lines, ["摩天輪轉呀轉"])
        } else {
            XCTFail("Expected AI fallback hit")
        }
    }

    func test_should_skipAiDuringPrefetch_andRunAiOnlyOnceWhenActiveOpens() async {
        let events = LockedBox<[String]>([])
        let spotify = makeSpotifySource { _ in
            (200, [:], #"{"id":"spTrack2","name":"Ferries wheel","artists":[{"name":"Hebe Tien"}],"external_ids":{"isrc":"TWD950519106"}}"#)
        }
        let data = makeData(
            autoFetch: { title, artist, _ in
                events.withValue { $0.append("autoFetch:\(title)|\(artist)") }
                if title == "摩天輪" && artist == "田馥甄" {
                    return self.lyricHit("[00:01.00]AI hit")
                }
                return nil
            },
            musicBrainzNames: { isrc, _ in
                events.withValue { $0.append("mb:\(isrc)") }
                return []
            },
            aiHasKey: { true },
            aiResolveReleaseNames: { _, _, _, _, _, _ in
                events.withValue { $0.append("ai") }
                return ("摩天輪", "田馥甄")
            }
        )

        let track = spotifyTrack(id: "spTrack2")
        // Background prefetch must NOT invoke AI.
        await data.prefetchLyrics(track, client: nil, spotify: spotify)
        XCTAssertEqual(data.lyricsState(track), .none)
        XCTAssertFalse(events.value.contains("ai"))

        // Opening Now Playing runs AI once without repeating Steps 1-4.
        events.withValue { $0.removeAll() }
        await data.prepare(track, client: nil, spotify: spotify)
        XCTAssertEqual(events.value, ["ai", "autoFetch:摩天輪|田馥甄"])
        if case .lines(let loaded) = data.lyricsState(track) {
            XCTAssertEqual(loaded.lines, ["AI hit"])
        } else {
            XCTFail("Expected AI resolution when active song opens")
        }
    }

    func test_should_ignoreAiErrors_withoutMarkingLyricsRetry() async {
        struct AiBoom: Error {}
        let spotify = makeSpotifySource { _ in
            (200, [:], #"{"id":"spTrack3","name":"Ferries wheel","artists":[{"name":"Hebe Tien"}],"external_ids":{"isrc":"TWD950519106"}}"#)
        }
        let data = makeData(
            autoFetch: { _, _, _ in nil },
            aiHasKey: { true },
            aiResolveReleaseNames: { _, _, _, _, _, _ in throw AiBoom() }
        )
        let track = spotifyTrack(id: "spTrack3")
        await data.prepare(track, client: nil, spotify: spotify)
        XCTAssertEqual(data.lyricsState(track), .none)
        XCTAssertFalse(data.lyricsRetry.contains(NowPlayingData.key(track)))
    }

    func test_should_setLyricsRetryOnLyricFetchExceptionAndGeneralError_andRetryInPrefetch() async {
        var attempts = 0
        let kotlinFetchError = NSError(
            domain: "KotlinException",
            code: 0,
            userInfo: [
                "KotlinException": LyricFetchException(message: "HTTP 500", cause: nil),
                NSLocalizedDescriptionKey: "HTTP 500",
            ]
        )
        let data = makeData(autoFetch: { _, _, _ in
            attempts += 1
            if attempts == 1 { throw kotlinFetchError }
            if attempts == 2 { throw URLError(.timedOut) }
            return self.lyricHit("[00:03.00]Recovered line")
        })

        let track = spotifyTrack(id: "spRetry1", title: "Track", artist: "Artist")
        let key = NowPlayingData.key(track)

        // 1. Kotlin LyricFetchException marks .none + lyricsRetry
        await data.prepare(track, client: nil, spotify: nil)
        XCTAssertEqual(data.lyricsState(track), .none)
        XCTAssertTrue(data.lyricsRetry.contains(key))

        // 2. prefetchLyrics does NOT bail on .none when lyricsRetry contains key, and prefetchWithRetry succeeds
        await data.prefetchWithRetry(track, client: nil, retryDelays: [0, 0])
        XCTAssertEqual(attempts, 3)
        XCTAssertFalse(data.lyricsRetry.contains(key))
        if case .lines(let loaded) = data.lyricsState(track) {
            XCTAssertEqual(loaded.lines, ["Recovered line"])
        } else {
            XCTFail("Expected retry to recover lyrics")
        }
    }

    func test_should_preserveTransientFailure_whenMetadataReturnsEmptyAfterAutoFetchThrow() async {
        let kotlinFetchError = NSError(
            domain: "KotlinException",
            code: 0,
            userInfo: ["KotlinException": LyricFetchException(message: "network down", cause: nil)]
        )
        let spotify = makeSpotifySource { _ in (503, [:], "{}") }
        let data = makeData(autoFetch: { _, _, _ in throw kotlinFetchError })

        let track = spotifyTrack(id: "spOffline", title: "晴天", artist: "周杰倫")
        await data.prepare(track, client: nil, spotify: spotify)

        XCTAssertEqual(data.lyricsState(track), .none)
        XCTAssertTrue(data.lyricsRetry.contains(NowPlayingData.key(track)))
    }

    func test_should_notSetLyricsRetry_onCleanEmptyMissOrCancellation() async {
        let cleanData = makeData(autoFetch: { _, _, _ in nil })
        let cleanTrack = spotifyTrack(id: "spClean")
        await cleanData.prepare(cleanTrack, client: nil, spotify: nil)
        XCTAssertEqual(cleanData.lyricsState(cleanTrack), .none)
        XCTAssertFalse(cleanData.lyricsRetry.contains(NowPlayingData.key(cleanTrack)))

        let cancelData = makeData(autoFetch: { _, _, _ in throw CancellationError() })
        let cancelTrack = spotifyTrack(id: "spCancel")
        await cancelData.prepare(cancelTrack, client: nil, spotify: nil)
        XCTAssertEqual(cancelData.lyricsState(cancelTrack), .loading)
        XCTAssertFalse(cancelData.lyricsRetry.contains(NowPlayingData.key(cancelTrack)))
    }

    func test_should_wireSeam1_nativeNamesHeader_QueueEntryPlayable_andMadeForYouMapping() async {
        let capturedHeaders = LockedBox<[String]>([])
        let spotify = makeSpotifySource { req in
            let lang = req.value(forHTTPHeaderField: "Accept-Language") ?? ""
            capturedHeaders.withValue { $0.append(lang) }
            if lang == "zh-TW" {
                return (200, [:], #"{"id":"t1","name":"摩天輪","artists":[{"name":"田馥甄"}],"external_ids":{"isrc":"TWD950519106"}}"#)
            }
            return (200, [:], #"{"id":"t1","name":"Ferries wheel","artists":[{"name":"Hebe Tien"}]}"#)
        }
        let pairs = await spotify.nativeNames(trackId: "t1", acceptLanguages: ["en", "zh-TW"])
        XCTAssertEqual(capturedHeaders.value, ["en", "zh-TW"])
        XCTAssertEqual(pairs.count, 2)
        XCTAssertEqual(pairs[1].title, "摩天輪")

        let entry = SpotifySource.QueueEntry(
            id: 0,
            uri: "spotify:track:abc123",
            title: "Song",
            artist: "Artist",
            coverURL: nil,
            durationMs: 245_000
        )
        XCTAssertEqual(entry.trackId, "abc123")
        XCTAssertEqual(entry.playable?.durationSec, 245.0)

        let containers = [
            SpotifyContentContainer(
                title: "Made For You",
                subtitle: "",
                uri: "spotify:genre:0JQ5DAt0tbjZptfcdMSKl3",
                items: [
                    SpotifyContentItem(title: "Daily Mix 1", subtitle: "Artist A, Artist B",
                                       uri: "spotify:playlist:37i9dQZF1E36GY2ncdnlLE",
                                       imageIdentifier: "spotify:image:img1", isPlayable: true, isContainer: true),
                    SpotifyContentItem(title: "daylist", subtitle: "chill monday",
                                       uri: "spotify:playlist:37i9dQZF1EP6YuccBxUcC1",
                                       imageIdentifier: "spotify:image:img2", isPlayable: true, isContainer: true),
                ]
            )
        ]
        let items = SpotifySource.madeForYouItems(from: containers)
        XCTAssertEqual(items.map(\.id.rawId), ["37i9dQZF1EP6YuccBxUcC1", "37i9dQZF1E36GY2ncdnlLE"])
        XCTAssertEqual(items[0].subtitle, "daylist")
        XCTAssertEqual(items[1].subtitle, "Artist A, Artist B")
        XCTAssertEqual(items[0].palette.cover?.url.absoluteString, "https://i.scdn.co/image/img2")
    }

    func test_should_interruptAppleMusicPlaylistRestore_on401_403_429() async throws {
        for status in [401, 403, 429] {
            let apple = AppleMusicSource(
                storefrontProvider: { "us" },
                transport: { _, _ in throw MockRestoreStatusError(httpStatus: status) }
            )
            let dest = AppleMusicRestoreDestination(apple: apple)
            let track = MatchTrack(id: "spotify:1", title: "Song", artist: "Artist", album: nil,
                                   durationSec: nil, isrc: "USRC17607839")
            do {
                _ = try await PlaylistRestore.resolve([track], on: dest)
                XCTFail("Expected RestoreInterrupted for HTTP \(status)")
            } catch let e as RestoreInterrupted {
                XCTAssertEqual(e.left, 1)
                XCTAssertFalse(e.message.isEmpty)
            }
            XCTAssertTrue(RestoreInterrupted.stops(MockRestoreStatusError(httpStatus: status)))
        }
    }

    func test_should_recordAndClearSearchMoreFailureReport() async {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("searchmore-test-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let suite = "searchmore-test-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer {
            try? FileManager.default.removeItem(at: dir)
            UserDefaults.standard.removePersistentDomain(forName: suite)
        }

        let clockLock = NSLock()
        nonisolated(unsafe) var now = Date(timeIntervalSince1970: 1_700_000_000)
        let gate = SpotifyRateGate(now: { clockLock.withLock { now } })

        let failMore = LockedBox(true)
        FallbackURLStub.reset()
        FallbackURLStub.handler = { req in
            if req.url?.query?.contains("offset=1") == true {
                if failMore.value {
                    return (429, ["Retry-After": "30"], "{}")
                }
                return (200, [:], #"{"tracks":{"items":[{"id":"s2","name":"Two","type":"track","duration_ms":1000,"track_number":1,"disc_number":1,"artists":[{"id":"a1","name":"A"}],"album":{"id":"al1","name":"Al","album_type":"album","total_tracks":1,"release_date":"2024-01-01","artists":[{"id":"a1","name":"A"}]}}],"next":null}}"#)
            }
            return (200, [:], #"{"tracks":{"items":[{"id":"s1","name":"One","type":"track","duration_ms":1000,"track_number":1,"disc_number":1,"artists":[{"id":"a1","name":"A"}],"album":{"id":"al1","name":"Al","album_type":"album","total_tracks":1,"release_date":"2024-01-01","artists":[{"id":"a1","name":"A"}]}}],"next":"https://api.spotify.com/v1/search?offset=1"}}"#)
        }
        let cfg = URLSessionConfiguration.ephemeral
        cfg.protocolClasses = [FallbackURLStub.self]
        let spotify = SpotifySource(api: SpotifyWebAPI(
            token: { "tok" },
            gate: gate,
            session: URLSession(configuration: cfg),
            maxRetries: 0,
            sleep: { _ in }
        ))
        let sources = SourceSelection(subsonicSignedIn: false, defaults: defaults)
        sources.use(.spotify)
        let store = LibraryStore(
            session: ServerSession(),
            sources: sources,
            appleMusic: AppleMusicSource(storefront: "us") { _, _ in [:] },
            history: HistoryStore(fileURL: nil),
            notes: NotesStore(fileURL: dir.appendingPathComponent("notes.json")),
            ratings: LocalRatings(fileURL: dir.appendingPathComponent("ratings.json")),
            disk: DetailDiskCache(directory: dir.appendingPathComponent("disk")),
            spotifyAccount: (spotify, "sp-test")
        )
        await store.runSearch("one", scope: .catalog)
        XCTAssertEqual(store.search?.songs.count, 1)
        XCTAssertEqual(store.searchMore(.songs), .available)

        await store.loadMoreSearch(.songs)
        XCTAssertEqual(store.searchMore(.songs), .failed)
        XCTAssertNotNil(store.failureReports["searchMore:songs"])

        clockLock.withLock { now = now.addingTimeInterval(31) }
        failMore.withValue { $0 = false }
        await store.loadMoreSearch(.songs)
        XCTAssertEqual(store.searchMore(.songs), .none)
        XCTAssertNil(store.failureReports["searchMore:songs"])
    }
}

private final class LockedBox<Value>: @unchecked Sendable {
    private let lock = NSLock()
    private var storage: Value
    init(_ initial: Value) { self.storage = initial }
    func withValue<T>(_ body: (inout Value) -> T) -> T { lock.withLock { body(&storage) } }
    var value: Value { lock.withLock { storage } }
}

private struct MockRestoreStatusError: HTTPStatusError {
    var httpStatus: Int
}

private final class FallbackURLStub: URLProtocol {
    private static let lock = NSLock()
    nonisolated(unsafe) static var handler: @Sendable (URLRequest) -> (Int, [String: String], String) = { _ in
        (200, [:], "{}")
    }
    static func reset() {
        lock.withLock { handler = { _ in (200, [:], "{}") } }
    }
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let res = Self.lock.withLock { Self.handler(request) }
        let response = HTTPURLResponse(url: request.url!, statusCode: res.0, httpVersion: "HTTP/1.1", headerFields: res.1)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(res.2.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}
