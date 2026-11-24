import Foundation
import MusicKit
import Observation
import os

/// Apple Music through MusicKit: the authorization and subscription state,
/// and every read the screens need as Apple Music API requests
/// (`MusicDataRequest`, which signs them with the app's automatic developer
/// token and the user's music token), parsed by `AppleMusicMapping`.
///
/// Why `MusicDataRequest` and not `MusicLibraryRequest` for the library:
/// library items read on device carry `musicKit://` artwork that only
/// MusicKit's own `ArtworkImage` can draw; the web API answers with http
/// artwork the shared cover pipeline (`CoverArtwork`) can fetch and read
/// colors off, and with the `catalog` relationship Android's identity rule
/// needs (library → catalog id).
///
/// Playback resolves a queue entry back to a MusicKit `Song`, decoded from
/// the same resource JSON (kept per song id when a list is parsed).
@MainActor
@Observable
final class AppleMusicSource {
    enum Authorization: String, Sendable {
        case notDetermined, denied, restricted, authorized
    }

    struct Subscription: Equatable, Sendable {
        var canPlayCatalogContent: Bool
        var canBecomeSubscriber: Bool
    }

    private(set) var authorization: Authorization
    private(set) var subscription: Subscription?

    var isAuthorized: Bool { authorization == .authorized }

    typealias RequestTransport = @MainActor (_ path: String, _ query: [String: String]) async throws -> AppleMusicJSON.Object
    typealias StorefrontProvider = @MainActor () async throws -> String

    nonisolated static let maxLibrarySongs = 2000
    nonisolated static let songsPageSize = 100

    private struct AltNameKey: Hashable, Sendable {
        var catalogId: String
        var regionKey: String
    }

    private struct AltNameEntry: Hashable, Sendable {
        var title: String
        var artist: String
    }

    private struct SongLinksEntry: Hashable, Sendable {
        var albumId: String?
        var artistId: String?
        var artistName: String?
    }

    private struct StorefrontInfo: Sendable {
        var defaultLanguage: String?
        var supportedLanguages: Set<String>
    }

    @ObservationIgnored private var storefront: String?
    /// Song raw id → its resource JSON, for the player.
    @ObservationIgnored private var songResources: [String: Data] = [:]
    /// Library song raw id (with and without `library:`) → catalog song id when known.
    @ObservationIgnored private var libraryCatalogIds: [String: String] = [:]
    /// Catalog song ids known to be in the user's library (from library reads and adds).
    @ObservationIgnored private var inLibrary: Set<String> = []
    @ObservationIgnored private var alternateNamesCache = BoundedLRU<AltNameKey, [AltNameEntry]>(capacity: 256)
    @ObservationIgnored private var isrcCache = BoundedLRU<String, String?>(capacity: 256)
    @ObservationIgnored private var songLinksCache = BoundedLRU<String, SongLinksEntry>(capacity: 256)
    @ObservationIgnored private var storefrontInfoCache: [String: StorefrontInfo] = [:]
    @ObservationIgnored private var subscriptionTask: Task<Void, Never>?
    @ObservationIgnored private let transport: RequestTransport?
    @ObservationIgnored private let storefrontProvider: StorefrontProvider?
    @ObservationIgnored let log = Logger(subsystem: "com.gpo.yoin", category: "applemusic")
    /// The account's name region (its page in Settings), read on every list so a change applies at once.
    @ObservationIgnored var nameRegion: @MainActor () -> AppleMusicNameRegion = {
        AppleMusicNameRegionStore.shared.region(ProfileStore.appleMusicId)
    }
    @ObservationIgnored private lazy var regionalNames = AppleMusicNameResolver(
        catalog: { [weak self] storefront, kind, ids, language in
            guard let self else { return [:] }
            let root = try await self.get("/v1/catalog/\(storefront)/\(kind)", ["ids": ids.joined(separator: ","), "l": language])
            return AppleMusicNameResolver.names(in: AppleMusicJSON.resources(root))
        },
        byIsrc: { [weak self] storefront, isrcs, language in
            guard let self else { return [:] }
            return try await self.namesByIsrc(storefront: storefront, isrcs: isrcs, language: language)
        },
        catalogResources: { [weak self] storefront, kind, query in
            guard let self else { return [] }
            let root = try await self.get("/v1/catalog/\(storefront)/\(kind)", query)
            return AppleMusicNameResolver.parseResources(in: AppleMusicJSON.resources(root))
        })

    init() {
        self.transport = nil
        self.storefrontProvider = nil
        authorization = Self.map(MusicAuthorization.currentStatus)
        if isAuthorized { watchSubscription() }
    }

    init(storefront: String? = nil, authorization: Authorization = .authorized,
         storefrontProvider: StorefrontProvider? = nil, transport: @escaping RequestTransport) {
        self.storefront = storefront
        self.authorization = authorization
        self.storefrontProvider = storefrontProvider
        self.transport = transport
    }

    private static func map(_ s: MusicAuthorization.Status) -> Authorization {
        switch s {
        case .authorized: .authorized
        case .denied: .denied
        case .restricted: .restricted
        default: .notDetermined
        }
    }

    // MARK: Authorization

    /// The system prompt (only the first time; later it answers at once).
    func requestAuthorization() async {
        authorization = Self.map(await MusicAuthorization.request())
        if isAuthorized { watchSubscription() }
    }

    func refreshAuthorization() {
        authorization = Self.map(MusicAuthorization.currentStatus)
        if isAuthorized, subscriptionTask == nil { watchSubscription() }
    }

    private func watchSubscription() {
        subscriptionTask?.cancel()
        subscriptionTask = Task { [weak self] in
            for await s in MusicSubscription.subscriptionUpdates {
                guard let self else { return }
                self.subscription = Subscription(canPlayCatalogContent: s.canPlayCatalogContent,
                                                 canBecomeSubscriber: s.canBecomeSubscriber)
            }
        }
    }

    // MARK: Requests

    struct RequestError: LocalizedError {
        var message: String
        var errorDescription: String? { message }
    }

    nonisolated static func message(for error: Error) -> String {
        if let e = error as? MusicDataRequest.Error {
            return e.detailText.isEmpty ? e.title : e.detailText
        }
        return error.localizedDescription
    }

    nonisolated static func httpStatus(of error: Error) -> Int? {
        if let e = error as? MusicDataRequest.Error { return e.status }
        if let e = error as? HTTPStatusError { return e.httpStatus }
        if let e = error as? SpotifyAPIError {
            switch e {
            case .unauthorized: return 401
            case .rateLimited: return 429
            case .http(let status, _): return status
            default: return nil
            }
        }
        return nil
    }

    nonisolated static func isAuthOrRateLimit(_ error: Error) -> Bool {
        guard let status = httpStatus(of: error) else { return false }
        return status == 401 || status == 403 || status == 429
    }

    /// The storefront the public web links name (`us`); nil when it can't be read.
    func webStorefront() async -> String? { try? await countryCode() }

    private func countryCode() async throws -> String {
        if let storefront { return storefront }
        let code: String
        if let storefrontProvider {
            code = try await storefrontProvider()
        } else {
            code = try await MusicDataRequest.currentCountryCode
        }
        storefront = code
        return code
    }

    private func get(_ path: String, _ query: [String: String] = [:]) async throws -> AppleMusicJSON.Object {
        if let transport { return try await transport(path, query) }
        guard var c = URLComponents(string: "https://api.music.apple.com" + path) else {
            throw RequestError(message: String(localized: "Bad request"))
        }
        if !query.isEmpty {
            c.queryItems = (c.queryItems ?? []) + query.sorted { $0.key < $1.key }.map { URLQueryItem(name: $0.key, value: $0.value) }
        }
        guard let url = c.url else { throw RequestError(message: String(localized: "Bad request")) }
        let response = try await MusicDataRequest(urlRequest: URLRequest(url: url)).response()
        guard let root = AppleMusicJSON.object(response.data) else {
            if QAProbe.diag {
                print("[diag] unreadable \(url.absoluteString) status=\(response.urlResponse.statusCode) bytes=\(response.data.count) "
                      + "body=\(String(decoding: response.data.prefix(400), as: UTF8.self))")
            }
            throw RequestError(message: String(localized: "Unreadable answer"))
        }
        return root
    }

    /// Every page of a list, following `next`, up to [cap] resources. [onPage] gets what has
    /// arrived so far after each page (a long library shows as it comes).
    private func all(_ path: String, _ query: [String: String] = [:], cap: Int,
                     onPage: (@MainActor ([AppleMusicJSON.Object]) async -> Void)? = nil) async throws -> [AppleMusicJSON.Object] {
        var out: [AppleMusicJSON.Object] = []
        var root = try await get(path, query)
        out += AppleMusicJSON.resources(root)
        while out.count < cap, let next = AppleMusicJSON.next(root) {
            await onPage?(out)
            // Apple's `next` link carries the offset only; asked bare, page 2 answered 401 with no body
            // (iPhone, 2026-11-05: Albums and Artists past 100 never loaded). The options go back on.
            root = try await get(next, Self.missing(query, in: next))
            out += AppleMusicJSON.resources(root)
        }
        return Array(out.prefix(cap))
    }

    /// Every page of a list, to the end (Android `all`): a page that fails fails the read, and a `next` link
    /// seen before is refused, so what comes back is the whole list or nothing. A `next` link that leaves out
    /// one of [query]'s options gets it back (Apple's links carry the offset, not always `include`).
    private func everyPage(_ path: String, _ query: [String: String] = [:]) async throws -> [AppleMusicJSON.Object] {
        var out: [AppleMusicJSON.Object] = []
        var visited = Set<String>()
        var root = try await get(path, query)
        out += AppleMusicJSON.resources(root)
        while let next = AppleMusicJSON.next(root) {
            guard visited.insert(next).inserted else { throw RequestError(message: String(localized: "Apple Music repeated a page")) }
            root = try await get(next, Self.missing(query, in: next))
            out += AppleMusicJSON.resources(root)
        }
        return out
    }

    /// [query]'s options a `next` link (path + query) doesn't carry.
    nonisolated static func missing(_ query: [String: String], in next: String) -> [String: String] {
        let carried = Set(URLComponents(string: next)?.queryItems?.map(\.name) ?? [])
        return query.filter { !carried.contains($0.key) }
    }

    // MARK: Names from another storefront (Android AppleMusicSource.regionalTracks / regionalAlbums)

    /// [songs] under the account's name region's names.
    private func named(_ songs: [AMSong]) async -> [AMSong] {
        let region = nameRegion()
        guard region != .off else { return songs }
        return await regionalNames.songs(region, accountStorefront: try? await countryCode(), songs)
    }

    private func named(_ albums: [AMAlbum]) async -> [AMAlbum] {
        let region = nameRegion()
        guard region != .off else { return albums }
        return await regionalNames.albums(region, accountStorefront: try? await countryCode(), albums)
    }

    private func named(_ artists: [AMArtist]) async -> [AMArtist] {
        let region = nameRegion()
        guard region != .off else { return artists }
        return await regionalNames.artists(region, accountStorefront: try? await countryCode(), artists)
    }

    /// The albums in a mixed list, renamed (`AppleMusicMapping.albums(in:)`).
    private func albumList(_ resources: [AppleMusicJSON.Object]) async -> [Album] {
        AppleMusicMapping.uniqueById(await named(resources.compactMap(AppleMusicMapping.album)).map(AppleMusicMapping.album))
    }

    /// Songs by ISRC in another storefront and language: ISRC → the first song's names. Nothing is kept for
    /// playback (the songs are that storefront's).
    private func namesByIsrc(storefront: String, isrcs: [String], language: String) async throws -> [String: AppleMusicNameResolver.Names] {
        let batches = AppleMusicMapping.isrcBatches(isrcs)
        guard !batches.isEmpty else { return [:] }
        var found: [String: AppleMusicNameResolver.Names] = [:]
        let maxConcurrency = AppleMusicNameResolver.maxConcurrentBatches
        for waveStart in stride(from: 0, to: batches.count, by: maxConcurrency) {
            let wave = batches[waveStart..<min(waveStart + maxConcurrency, batches.count)]
            let tasks = wave.map { batch in
                (batch, Task { @MainActor [self] in
                    let root = try await self.get("/v1/catalog/\(storefront)/songs", ["filter[isrc]": batch.joined(separator: ","), "l": language])
                    return AppleMusicJSON.resources(root).compactMap(AppleMusicMapping.song)
                })
            }
            defer { for (_, task) in tasks { task.cancel() } }
            for (batch, task) in tasks {
                let songs = try await task.value
                for (code, list) in AppleMusicMapping.groupByIsrc(songs, batch: batch) where found[code] == nil {
                    if let s = list.first { found[code] = .init(title: s.title, artist: s.artist, album: s.album) }
                }
            }
        }
        return found
    }

    private func catalog(_ kind: String) async throws -> String {
        "/v1/catalog/\(try await countryCode())/\(kind)"
    }

    /// Keeps each song's JSON for the player; returns the songs.
    private func songs(_ resources: [AppleMusicJSON.Object]) -> [AMSong] {
        resources.compactMap { r in
            guard let s = AppleMusicMapping.song(r) else { return nil }
            remember(s, resource: r)
            return s
        }
    }

    private func remember(_ s: AMSong, resource: AppleMusicJSON.Object) {
        if songResources[s.rawId] == nil, let data = try? JSONSerialization.data(withJSONObject: resource) {
            songResources[s.rawId] = data
        }
        if let libId = s.libraryId, let c = s.catalogId {
            libraryCatalogIds[libId] = c
            libraryCatalogIds[AppleMusicMapping.libraryPrefix + libId] = c
        }
        if let isrc = s.isrc {
            isrcCache.insert(isrc, forKey: s.rawId)
            if let libId = s.libraryId {
                isrcCache.insert(isrc, forKey: AppleMusicMapping.libraryPrefix + libId)
            }
        }
        if s.libraryId != nil, let c = s.catalogId { inLibrary.insert(c) }
    }

    private func rememberTracks(_ resources: [AppleMusicJSON.Object]) {
        for r in resources {
            for t in AppleMusicJSON.related(r, "tracks") ?? [] { if let s = AppleMusicMapping.song(t) { remember(s, resource: t) } }
        }
    }

    // MARK: Home

    struct HomeLists: Sendable {
        var recent: [Album]
        var frequent: [Album]
        var newest: [Album]
        var older: [Album]
        var recentTracks: [PlaylistEntry]
    }

    /// Recently played albums, heavy rotation, recently added (the newest ten
    /// and the twenty before them) and the last songs played. Each list
    /// fails on its own: an account with no history still gets a page.
    func homeLists() async throws -> HomeLists {
        func attempt(_ name: String, _ body: () async throws -> [AppleMusicJSON.Object]) async -> [AppleMusicJSON.Object] {
            do { return try await body() } catch {
                log.info("home \(name, privacy: .public) failed: \(Self.message(for: error), privacy: .public)")
                return []
            }
        }
        let played = await attempt("recent") { try await all("/v1/me/recent/played", ["limit": "10"], cap: 20) }
        let heavy = await attempt("heavy rotation") { try await all("/v1/me/history/heavy-rotation", ["limit": "10"], cap: 10) }
        let added = await attempt("recently added") {
            try await all("/v1/me/library/recently-added", ["limit": "10", "include": "catalog"], cap: 30)
        }
        let tracks = await attempt("recent tracks") {
            try AppleMusicJSON.resources(await get("/v1/me/recent/played/tracks",
                                                   ["limit": "4", "types": "songs,library-songs"]))
        }
        if played.isEmpty && heavy.isEmpty && added.isEmpty && tracks.isEmpty {
            // Nothing answered: surface the failure rather than an empty page.
            _ = try await get("/v1/me/library/albums", ["limit": "1"])
        }
        let addedAlbums = await albumList(added)
        return HomeLists(recent: await albumList(played),
                         frequent: await albumList(heavy),
                         newest: Array(addedAlbums.prefix(10)),
                         older: Array(addedAlbums.dropFirst(10)).shuffled(),
                         recentTracks: AppleMusicMapping.entries(await named(songs(tracks))))
    }

    // MARK: Library

    func libraryAlbums(cap: Int = 2000, onPage: (([Album]) -> Void)? = nil) async throws -> [Album] {
        var pageHandler: (@MainActor ([AppleMusicJSON.Object]) async -> Void)?
        if let onPage {
            // Each page under the region's names too (cached: the pages before it cost nothing again).
            pageHandler = { [self] resources in onPage(await albumList(resources)) }
        }
        let r = try await all("/v1/me/library/albums", ["limit": "100", "include": "catalog"], cap: cap, onPage: pageHandler)
        return await albumList(r)
    }

    func libraryArtists(cap: Int = 2000, onPage: (([LibraryItem]) -> Void)? = nil) async throws -> [LibraryItem] {
        func items(_ r: [AppleMusicJSON.Object]) async -> [LibraryItem] {
            var seen = Set<String>()
            let unique = r.compactMap(AppleMusicMapping.artist).filter { seen.insert($0.rawId).inserted }
            return await named(unique).map(AppleMusicMapping.artistItem)
        }
        var pageHandler: (@MainActor ([AppleMusicJSON.Object]) async -> Void)?
        if let onPage { pageHandler = { onPage(await items($0)) } }
        let r = try await all("/v1/me/library/artists", ["limit": "100", "include": "catalog"], cap: cap, onPage: pageHandler)
        return await items(r)
    }

    /// Every library playlist, all pages (a backup tells a playlist gone by its absence here, so a prefix
    /// would make the rest look deleted).
    func libraryPlaylists() async throws -> [LibraryItem] {
        try await everyPage("/v1/me/library/playlists", ["limit": "100"])
            .compactMap(AppleMusicMapping.playlist).map(AppleMusicMapping.playlistItem)
    }

    /// Library songs, up to `maxLibrarySongs` (or `cap`).
    func librarySongs(cap: Int = maxLibrarySongs) async throws -> [PlaylistEntry] {
        let capped = min(max(cap, 0), Self.maxLibrarySongs)
        guard capped > 0 else { return [] }
        let r = try await all("/v1/me/library/songs", ["limit": "100", "include": "albums,catalog,artists"], cap: capped)
        return AppleMusicMapping.entries(await named(songs(r)))
    }

    /// One cursor/offset page of library songs, capped at `maxLibrarySongs` (2000).
    func librarySongsPage(offset: Int, limit: Int = songsPageSize) async throws -> (songs: [PlaylistEntry], nextOffset: Int?) {
        guard offset >= 0, offset < Self.maxLibrarySongs, limit > 0 else { return ([], nil) }
        let pageLimit = min(limit, Self.songsPageSize, Self.maxLibrarySongs - offset)
        let root = try await get("/v1/me/library/songs",
                                 ["limit": String(pageLimit), "offset": String(offset), "include": "albums,catalog,artists"])
        let resources = AppleMusicJSON.resources(root)
        let entries = AppleMusicMapping.entries(await named(songs(resources)))
        let nextOffset: Int?
        if let next = AppleMusicJSON.next(root), !resources.isEmpty {
            let parsed = URLComponents(string: next)?.queryItems?.first(where: { $0.name == "offset" })?.value
                .flatMap(Int.init) ?? (offset + resources.count)
            nextOffset = (parsed > offset && parsed < Self.maxLibrarySongs) ? parsed : nil
        } else {
            nextOffset = nil
        }
        return (songs: entries, nextOffset: nextOffset)
    }

    /// Personal Made For You mixes (`pl.pm-*`) from `/v1/me/recommendations`, hydrating bare mixes from catalog.
    func madeForYou() async throws -> [LibraryItem] {
        let groups = try await everyPage("/v1/me/recommendations")
        let listed = AppleMusicMapping.madeForYouMixes(in: groups)
        let bare = listed.filter { $0.title.isEmpty }.map(\.id.rawId)
        guard !bare.isEmpty else { return listed }
        let shop = try await countryCode()
        var byId: [String: LibraryItem] = [:]
        var idx = 0
        while idx < bare.count {
            let end = min(idx + 25, bare.count)
            let chunk = Array(bare[idx..<end])
            idx = end
            let root = try await get("/v1/catalog/\(shop)/playlists", ["ids": chunk.joined(separator: ",")])
            for r in AppleMusicJSON.resources(root) {
                if let mix = AppleMusicMapping.madeForYouMix(r) {
                    byId[mix.id.rawId] = mix
                }
            }
        }
        return listed.compactMap { mix in mix.title.isEmpty ? byId[mix.id.rawId] : mix }
            .filter { !$0.title.isEmpty }
    }

    /// English names (`l=en-US`) for a catalog song on non-English storefronts (or when name region overrides),
    /// kept in a 256-entry LRU cache.
    func alternateNames(trackId: String) async -> [(title: String, artist: String)] {
        let catalogId: String? = AppleMusicMapping.isCatalogSongId(trackId)
            ? trackId
            : libraryCatalogIds[trackId]
        guard let catalogId, !catalogId.isEmpty else { return [] }
        let region = nameRegion()
        let cacheKey = AltNameKey(catalogId: catalogId, regionKey: region.key)
        if let cached = alternateNamesCache.value(forKey: cacheKey) {
            return cached.map { ($0.title, $0.artist) }
        }
        do {
            let shop = try await countryCode()
            if let info = try await storefrontInfo(shop) {
                if !info.supportedLanguages.isEmpty
                    && !info.supportedLanguages.contains("en-us")
                    && !info.supportedLanguages.contains("en") {
                    alternateNamesCache.insert([], forKey: cacheKey)
                    return []
                }
                if info.defaultLanguage?.hasPrefix("en") == true && region == .off {
                    alternateNamesCache.insert([], forKey: cacheKey)
                    return []
                }
            }
            let root = try await get("/v1/catalog/\(shop)/songs/\(catalogId)", ["l": "en-US"])
            let names = AppleMusicJSON.resources(root).compactMap(AppleMusicMapping.alternateName)
            let entries = names.map { AltNameEntry(title: $0.title, artist: $0.artist) }
            alternateNamesCache.insert(entries, forKey: cacheKey)
            return entries.map { ($0.title, $0.artist) }
        } catch {
            if Self.httpStatus(of: error) == 404 {
                alternateNamesCache.insert([], forKey: cacheKey)
            }
            return []
        }
    }

    private func storefrontInfo(_ shop: String) async throws -> StorefrontInfo? {
        if let cached = storefrontInfoCache[shop] { return cached }
        do {
            let root = try await get("/v1/storefronts/\(shop)")
            guard let first = AppleMusicJSON.resources(root).first else { return nil }
            let attrs = AppleMusicJSON.attributes(first)
            let defaultLang = AppleMusicJSON.string(attrs, "defaultLanguageTag")?.lowercased()
            let supported = Set((attrs["supportedLanguageTags"] as? [String] ?? []).map { $0.lowercased() })
            let info = StorefrontInfo(defaultLanguage: defaultLang, supportedLanguages: supported)
            storefrontInfoCache[shop] = info
            return info
        } catch {
            if Self.httpStatus(of: error) == 404 { return nil }
            throw error
        }
    }

    /// ISRC code for a catalog or library song, backed by a 256-entry LRU cache.
    func isrc(trackId: String) async -> String? {
        guard !trackId.isEmpty else { return nil }
        if let cached = isrcCache.value(forKey: trackId) { return cached }
        do {
            let root: AppleMusicJSON.Object
            if AppleMusicMapping.isCatalogSongId(trackId) {
                let shop = try await countryCode()
                root = try await get("/v1/catalog/\(shop)/songs/\(trackId)")
            } else {
                let libId = AppleMusicMapping.resourceId(trackId)
                guard AppleMusicMapping.isLibraryId(trackId) || libId.hasPrefix("i."), !libId.isEmpty else { return nil }
                root = try await get("/v1/me/library/songs/\(libId)", ["include": "catalog"])
            }
            guard let resource = AppleMusicJSON.resources(root).first else {
                isrcCache.insert(nil, forKey: trackId)
                return nil
            }
            let code = AppleMusicMapping.song(resource)?.isrc
            isrcCache.insert(code, forKey: trackId)
            return code
        } catch {
            if Self.httpStatus(of: error) == 404 {
                isrcCache.insert(nil, forKey: trackId)
            }
            return nil
        }
    }

    /// Album, artist, and artist name for a song missing route links (PR #244), cached in a 256-entry LRU.
    func songLinks(trackId: String) async -> (albumId: String?, artistId: String?, artistName: String?) {
        let bare = AppleMusicMapping.resourceId(trackId)
        guard !trackId.isEmpty, !bare.isEmpty else { return (nil, nil, nil) }
        if let cached = songLinksCache.value(forKey: trackId) {
            return (cached.albumId, cached.artistId, cached.artistName)
        }
        do {
            let root: AppleMusicJSON.Object
            if AppleMusicMapping.isCatalogSongId(trackId) {
                let shop = try await countryCode()
                root = try await get("/v1/catalog/\(shop)/songs/\(trackId)", ["include": "albums,artists"])
            } else {
                guard AppleMusicMapping.isLibraryId(trackId) || bare.hasPrefix("i.") else { return (nil, nil, nil) }
                root = try await get("/v1/me/library/songs/\(bare)", ["include": "catalog,albums,artists"])
            }
            guard let resource = AppleMusicJSON.resources(root).first else {
                songLinksCache.insert(SongLinksEntry(), forKey: trackId)
                return (nil, nil, nil)
            }
            let resolved = AppleMusicMapping.songLinks(resource)
            songLinksCache.insert(SongLinksEntry(albumId: resolved.albumId, artistId: resolved.artistId,
                                                 artistName: resolved.artistName), forKey: trackId)
            return resolved
        } catch {
            if Self.httpStatus(of: error) == 404 {
                songLinksCache.insert(SongLinksEntry(), forKey: trackId)
            }
            return (nil, nil, nil)
        }
    }

    // MARK: Details

    /// A library album opens as its full catalog album when it has one
    /// (Android parity); an import keeps its own tracklist.
    func album(_ rawId: String) async throws -> Album? {
        if AppleMusicMapping.isLibraryId(rawId) {
            let id = AppleMusicMapping.resourceId(rawId)
            let root = try await get("/v1/me/library/albums/\(id)", ["include": "catalog,tracks,artists"])
            guard let r = AppleMusicJSON.resources(root).first, var am = AppleMusicMapping.album(r) else { return nil }
            rememberTracks([r])
            if let catalogId = am.catalogId, let full = try? await catalogAlbum(catalogId) {
                // Opened by its library id: keep that id so the page's route stays the same.
                var album = full
                album.id = MediaId(provider: AppleMusicMapping.provider, rawId: rawId)
                album.created = am.dateAdded
                return album
            }
            am.catalogId = nil
            return AppleMusicMapping.album(am)
        }
        return try await catalogAlbum(rawId)
    }

    private func catalogAlbum(_ id: String) async throws -> Album? {
        let root = try await get(try await catalog("albums") + "/\(id)", ["include": "tracks,artists"])
        guard let r = AppleMusicJSON.resources(root).first, var am = AppleMusicMapping.album(r) else { return nil }
        let tracks = songs(AppleMusicJSON.related(r, "tracks") ?? [])
        am.tracks = tracks.map { var s = $0; s.albumId = s.albumId ?? id; return s }
        let region = nameRegion()
        if region != .off { am = await regionalNames.album(region, accountStorefront: try? await countryCode(), am) }
        return AppleMusicMapping.album(am)
    }

    /// Releases and top songs; a library artist reads as its catalog artist.
    func artist(_ rawId: String) async throws -> ArtistPage? {
        if AppleMusicMapping.isLibraryId(rawId) {
            let id = AppleMusicMapping.resourceId(rawId)
            let root = try await get("/v1/me/library/artists/\(id)", ["include": "catalog,albums"])
            guard let r = AppleMusicJSON.resources(root).first, var am = AppleMusicMapping.artist(r) else { return nil }
            if let catalogId = am.catalogId, var page = try? await catalogArtist(catalogId) {
                page.id = MediaId(provider: AppleMusicMapping.provider, rawId: rawId)
                return page
            }
            am.catalogId = nil
            let albums = await named((AppleMusicJSON.related(r, "albums") ?? []).compactMap(AppleMusicMapping.album)).map(AppleMusicMapping.album)
            return AppleMusicMapping.artistPage(am, albums: albums, topSongs: [])
        }
        return try await catalogArtist(rawId)
    }

    private func catalogArtist(_ id: String) async throws -> ArtistPage? {
        let root = try await get(try await catalog("artists") + "/\(id)", ["include": "albums", "views": "top-songs"])
        guard let r = AppleMusicJSON.resources(root).first, let am = AppleMusicMapping.artist(r) else { return nil }
        let rawAlbums = (AppleMusicJSON.related(r, "albums") ?? []).compactMap(AppleMusicMapping.album)
        let rawTopSongs = songs(AppleMusicJSON.view(r, "top-songs") ?? [])
        async let renamedArtist = named([am])
        async let albums = named(rawAlbums)
        async let topSongs = named(rawTopSongs)
        let artist = await renamedArtist.first ?? am
        return AppleMusicMapping.artistPage(artist, albums: await albums.map(AppleMusicMapping.album), topSongs: await topSongs)
    }

    func playlist(_ rawId: String) async throws -> PlaylistInfo? {
        let library = AppleMusicMapping.isLibraryId(rawId)
        let id = AppleMusicMapping.resourceId(rawId)
        let path = library ? "/v1/me/library/playlists/\(id)" : try await catalog("playlists") + "/\(id)"
        let root = try await get(path, ["include": "tracks"])
        guard let r = AppleMusicJSON.resources(root).first, let p = AppleMusicMapping.playlist(r) else { return nil }
        var tracks = AppleMusicJSON.related(r, "tracks") ?? []
        var next = AppleMusicJSON.relatedNext(r, "tracks")
        while let page = next, tracks.count < 1000 {
            let more = try await get(page)
            tracks += AppleMusicJSON.resources(more)
            next = AppleMusicJSON.next(more)
        }
        return AppleMusicMapping.playlist(p, songs: await named(songs(tracks)))
    }

    /// [rawId] read whole for a cloud backup (Android `readPlaylist`): the playlist, then every page of its
    /// entries in order — repeats kept, and what isn't a song (a music video, an entry it can't read) kept
    /// at its place as unreadable. Nil when the service doesn't have the playlist; any failed page throws,
    /// so a backup never saves part of a playlist as the whole.
    func playlistForBackup(_ rawId: String) async throws -> AMPlaylistRead? {
        let library = AppleMusicMapping.isLibraryId(rawId)
        let id = AppleMusicMapping.resourceId(rawId)
        let path = library ? "/v1/me/library/playlists/\(id)" : try await catalog("playlists") + "/\(id)"
        let root: AppleMusicJSON.Object
        do {
            root = try await get(path)
        } catch let e as MusicDataRequest.Error where e.status == 404 {
            return nil
        }
        guard let r = AppleMusicJSON.resources(root).first, let p = AppleMusicMapping.playlist(r) else { return nil }
        let entries: [AppleMusicJSON.Object]
        do {
            entries = try await everyPage(path + "/tracks", ["include": library ? "catalog" : "albums,artists"])
        } catch let e as MusicDataRequest.Error where e.status == 404 && (p.trackCount ?? 0) == 0 {
            // An empty library playlist has no tracks relationship to read.
            entries = []
        }
        for e in entries { if let s = AppleMusicMapping.song(e) { remember(s, resource: e) } }
        return AppleMusicMapping.playlistRead(p, entries: entries)
    }

    // MARK: Search

    struct SearchResults: Sendable {
        var artists: [LibraryItem]
        var albums: [Album]
        var songs: [PlaylistEntry]
        var playlists: [LibraryItem]
    }

    /// The Apple Music catalog (the whole store, not only the library).
    func search(_ term: String) async throws -> SearchResults {
        let root = try await get(try await catalog("search"),
                                 ["term": term, "types": "songs,albums,artists,playlists", "limit": "25"])
        let results = root["results"] as? AppleMusicJSON.Object
        func list(_ key: String) -> [AppleMusicJSON.Object] {
            (results?[key] as? AppleMusicJSON.Object)?["data"] as? [AppleMusicJSON.Object] ?? []
        }
        return SearchResults(artists: await named(list("artists").compactMap(AppleMusicMapping.artist)).map(AppleMusicMapping.artistItem),
                             albums: await albumList(list("albums")),
                             songs: AppleMusicMapping.entries(await named(songs(list("songs")))),
                             playlists: list("playlists").compactMap(AppleMusicMapping.playlist).map(AppleMusicMapping.playlistItem))
    }

    /// The user's saved library (Android `AppleMusicSource.searchLibrary`):
    /// `/v1/me/library/search`, 25 a type. Two library songs or albums can
    /// share one identity; the result lists key rows by id, so each comes once.
    func searchLibrary(_ term: String) async throws -> SearchResults {
        let root = try await get("/v1/me/library/search",
                                 ["term": term, "types": "library-songs,library-albums,library-artists,library-playlists",
                                  "limit": "25"])
        let results = root["results"] as? AppleMusicJSON.Object
        func list(_ key: String) -> [AppleMusicJSON.Object] {
            (results?[key] as? AppleMusicJSON.Object)?["data"] as? [AppleMusicJSON.Object] ?? []
        }
        var songIds = Set<String>(), albumIds = Set<String>(), artistIds = Set<String>()
        return SearchResults(
            artists: list("library-artists").compactMap(AppleMusicMapping.artist).map(AppleMusicMapping.artistItem)
                .filter { artistIds.insert($0.id.rawId).inserted },
            albums: await albumList(list("library-albums")).filter { albumIds.insert($0.id.rawId).inserted },
            songs: AppleMusicMapping.entries(await named(songs(list("library-songs")))).filter { songIds.insert($0.track.id.rawId).inserted },
            playlists: list("library-playlists").compactMap(AppleMusicMapping.playlist).map(AppleMusicMapping.playlistItem))
    }

    /// Catalog songs for [term] (Android `search(...).tracks`, 25): a restore's matching and its review search.
    func searchSongs(_ term: String) async throws -> [AMSong] {
        let trimmed = term.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return [] }
        let root = try await get(try await catalog("search"),
                                 ["term": trimmed, "types": "songs", "limit": "25", "include[songs]": "albums,artists"])
        let songs = ((root["results"] as? AppleMusicJSON.Object)?["songs"] as? AppleMusicJSON.Object)?["data"]
            as? [AppleMusicJSON.Object] ?? []
        return self.songs(songs)
    }

    /// Catalog songs by ISRC (`GET /v1/catalog/{storefront}/songs?filter[isrc]=…`, Android
    /// `catalogSongsByIsrc`): keeps non-throwing signature for existing Stage 1 callers.
    func catalogSongsByIsrc(_ isrcs: [String]) async -> [String: [AMSong]] {
        (try? await fetchCatalogSongsByIsrc(isrcs, propagateErrors: false)) ?? [:]
    }

    /// Throwing variant for `PlaylistRestore` (Stage 2 wiring): propagates HTTP 401/403/429 auth and
    /// rate-limit errors (and cancellation/network failures) so restore triggers `RestoreInterrupted`.
    func catalogSongsByIsrcThrowing(_ isrcs: [String]) async throws -> [String: [AMSong]] {
        try await fetchCatalogSongsByIsrc(isrcs, propagateErrors: true)
    }

    private func fetchCatalogSongsByIsrc(_ isrcs: [String], propagateErrors: Bool) async throws -> [String: [AMSong]] {
        let batches = AppleMusicMapping.isrcBatches(isrcs)
        guard !batches.isEmpty else { return [:] }
        var found: [String: [AMSong]] = [:]
        let path: String
        do {
            path = try await catalog("songs")
        } catch {
            if propagateErrors { throw error }
            return [:]
        }
        for batch in batches {
            if propagateErrors { try Task.checkCancellation() }
            else if Task.isCancelled { break }
            do {
                let root = try await get(path, ["filter[isrc]": batch.joined(separator: ",")])
                for (code, songs) in AppleMusicMapping.groupByIsrc(self.songs(AppleMusicJSON.resources(root)), batch: batch) {
                    found[code, default: []] += songs
                }
            } catch {
                if error is CancellationError {
                    if propagateErrors { throw error }
                    break
                }
                if !propagateErrors { continue }
                if let status = Self.httpStatus(of: error), status == 400 || status == 404 {
                    continue
                }
                throw error
            }
        }
        return found
    }

    // MARK: Playlist writes (a restore)

    /// A new playlist in the user's library holding [trackIds] (`applemusic:` ids, at most a batch) in
    /// order, sent with the create (Android `createLibraryPlaylist`: an append right after a create can
    /// land before Apple has the playlist). Returns its raw id (`library:p.…`).
    func createLibraryPlaylist(name: String, description: String?, trackIds: [String]) async throws -> String {
        var body: [String: Any] = ["attributes": AppleMusicMapping.playlistAttributes(name: name, description: description)]
        let refs = try trackIds.map(AppleMusicMapping.trackRef)
        if !refs.isEmpty { body["relationships"] = ["tracks": ["data": refs]] }
        let root = try await send("/v1/me/library/playlists", body: body)
        guard let created = AppleMusicJSON.resources(root).first, let id = created["id"] as? String else {
            throw RequestError(message: String(localized: "Apple Music returned no created playlist"))
        }
        return AppleMusicMapping.libraryPrefix + id
    }

    /// Appends [trackIds] to library playlist [playlistRawId] (`library:p.…`), in order.
    func addToLibraryPlaylist(_ trackIds: [String], playlistRawId: String) async throws {
        let id = AppleMusicMapping.resourceId(playlistRawId)
        guard AppleMusicMapping.isLibraryId(playlistRawId), id.hasPrefix("p.") else {
            throw RequestError(message: String(localized: "A library playlist is required"))
        }
        let refs = try trackIds.map(AppleMusicMapping.trackRef)
        guard !refs.isEmpty else { return }
        _ = try await send("/v1/me/library/playlists/\(id)/tracks", body: ["data": refs])
    }

    /// A POST to the Apple Music API with a JSON body; the answer's JSON (empty for a 204).
    private func send(_ path: String, body: [String: Any]) async throws -> AppleMusicJSON.Object {
        guard let url = URL(string: "https://api.music.apple.com" + path) else { throw RequestError(message: String(localized: "Bad request")) }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        let response = try await MusicDataRequest(urlRequest: request).response()
        guard (200..<300).contains(response.urlResponse.statusCode) else {
            throw RequestError(message: String(localized: "Apple Music answered \(response.urlResponse.statusCode)"))
        }
        return response.data.isEmpty ? [:] : AppleMusicJSON.object(response.data) ?? [:]
    }

    // MARK: Playback

    /// The MusicKit song a queue entry plays: decoded from the JSON a list
    /// gave, else read again (catalog by id, library by library id).
    func song(_ rawId: String) async -> Song? {
        if let data = songResources[rawId], let s = try? JSONDecoder().decode(Song.self, from: data) { return s }
        do {
            if AppleMusicMapping.isLibraryId(rawId) {
                var request = MusicLibraryRequest<Song>()
                request.filter(matching: \.id, equalTo: MusicItemID(AppleMusicMapping.resourceId(rawId)))
                return try await request.response().items.first
            }
            let request = MusicCatalogResourceRequest<Song>(matching: \.id, equalTo: MusicItemID(rawId))
            return try await request.response().items.first
        } catch {
            log.error("song \(rawId, privacy: .public) failed: \(Self.message(for: error), privacy: .public)")
            return nil
        }
    }

    /// Whether the song is in the user's library (nil: unknown). A `library:`
    /// id is; a catalog id asks its `library` relationship (404 = not added).
    func isInLibrary(_ rawId: String) async -> Bool? {
        if AppleMusicMapping.isLibraryId(rawId) || inLibrary.contains(rawId) { return true }
        guard AppleMusicMapping.isCatalogSongId(rawId) else { return nil }
        do {
            let root = try await get(try await catalog("songs") + "/\(rawId)/library")
            let added = !AppleMusicJSON.resources(root).isEmpty
            if added { inLibrary.insert(rawId) }
            return added
        } catch let e as MusicDataRequest.Error where e.status == 404 {
            return false
        } catch {
            log.info("library check failed: \(Self.message(for: error), privacy: .public)")
            return nil
        }
    }

    /// Adds the song to the library (`MusicLibrary.add`). There is no remove.
    func addToLibrary(_ rawId: String) async throws {
        guard !AppleMusicMapping.isLibraryId(rawId) else { return }
        guard let song = await song(rawId) else { throw RequestError(message: String(localized: "Song unavailable")) }
        try await MusicKit.MusicLibrary.shared.add(song)
        inLibrary.insert(rawId)
    }
}
