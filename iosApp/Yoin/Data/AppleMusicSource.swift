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

    @ObservationIgnored private var storefront: String?
    /// Song raw id → its resource JSON, for the player.
    @ObservationIgnored private var songResources: [String: Data] = [:]
    /// Catalog song ids known to be in the user's library (from library reads and adds).
    @ObservationIgnored private var inLibrary: Set<String> = []
    @ObservationIgnored private var subscriptionTask: Task<Void, Never>?
    @ObservationIgnored let log = Logger(subsystem: "com.gpo.yoin", category: "applemusic")

    init() {
        authorization = Self.map(MusicAuthorization.currentStatus)
        if isAuthorized { watchSubscription() }
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

    static func message(for error: Error) -> String {
        if let e = error as? MusicDataRequest.Error {
            return e.detailText.isEmpty ? e.title : e.detailText
        }
        return error.localizedDescription
    }

    private func countryCode() async throws -> String {
        if let storefront { return storefront }
        let code = try await MusicDataRequest.currentCountryCode
        storefront = code
        return code
    }

    private func get(_ path: String, _ query: [String: String] = [:]) async throws -> AppleMusicJSON.Object {
        guard var c = URLComponents(string: "https://api.music.apple.com" + path) else {
            throw RequestError(message: "Bad request")
        }
        if !query.isEmpty {
            c.queryItems = (c.queryItems ?? []) + query.sorted { $0.key < $1.key }.map { URLQueryItem(name: $0.key, value: $0.value) }
        }
        guard let url = c.url else { throw RequestError(message: "Bad request") }
        let response = try await MusicDataRequest(urlRequest: URLRequest(url: url)).response()
        guard let root = AppleMusicJSON.object(response.data) else { throw RequestError(message: "Unreadable answer") }
        return root
    }

    /// Every page of a list, following `next`, up to [cap] resources.
    private func all(_ path: String, _ query: [String: String] = [:], cap: Int) async throws -> [AppleMusicJSON.Object] {
        var out: [AppleMusicJSON.Object] = []
        var root = try await get(path, query)
        out += AppleMusicJSON.resources(root)
        while out.count < cap, let next = AppleMusicJSON.next(root) {
            root = try await get(next)
            out += AppleMusicJSON.resources(root)
        }
        return Array(out.prefix(cap))
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
        let addedAlbums = AppleMusicMapping.albums(in: added)
        return HomeLists(recent: AppleMusicMapping.albums(in: played),
                         frequent: AppleMusicMapping.albums(in: heavy),
                         newest: Array(addedAlbums.prefix(10)),
                         older: Array(addedAlbums.dropFirst(10)).shuffled(),
                         recentTracks: AppleMusicMapping.entries(songs(tracks)))
    }

    // MARK: Library

    func libraryAlbums(cap: Int = 2000) async throws -> [Album] {
        let r = try await all("/v1/me/library/albums", ["limit": "100", "include": "catalog"], cap: cap)
        return AppleMusicMapping.albums(in: r)
    }

    func libraryArtists(cap: Int = 2000) async throws -> [LibraryItem] {
        let r = try await all("/v1/me/library/artists", ["limit": "100", "include": "catalog"], cap: cap)
        var seen = Set<String>()
        return r.compactMap(AppleMusicMapping.artist).filter { seen.insert($0.rawId).inserted }
            .map(AppleMusicMapping.artistItem)
    }

    func libraryPlaylists() async throws -> [LibraryItem] {
        try await all("/v1/me/library/playlists", ["limit": "100"], cap: 500)
            .compactMap(AppleMusicMapping.playlist).map(AppleMusicMapping.playlistItem)
    }

    /// The first few hundred library songs (the API lists them in library order).
    func librarySongs(cap: Int = 300) async throws -> [PlaylistEntry] {
        let r = try await all("/v1/me/library/songs", ["limit": "100", "include": "albums,catalog"], cap: cap)
        return AppleMusicMapping.entries(songs(r))
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
            let albums = (AppleMusicJSON.related(r, "albums") ?? []).compactMap(AppleMusicMapping.album).map(AppleMusicMapping.album)
            return AppleMusicMapping.artistPage(am, albums: albums, topSongs: [])
        }
        return try await catalogArtist(rawId)
    }

    private func catalogArtist(_ id: String) async throws -> ArtistPage? {
        let root = try await get(try await catalog("artists") + "/\(id)", ["include": "albums", "views": "top-songs"])
        guard let r = AppleMusicJSON.resources(root).first, let am = AppleMusicMapping.artist(r) else { return nil }
        let albums = (AppleMusicJSON.related(r, "albums") ?? []).compactMap(AppleMusicMapping.album).map(AppleMusicMapping.album)
        return AppleMusicMapping.artistPage(am, albums: albums, topSongs: songs(AppleMusicJSON.view(r, "top-songs") ?? []))
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
        return AppleMusicMapping.playlist(p, songs: songs(tracks))
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
        return SearchResults(artists: list("artists").compactMap(AppleMusicMapping.artist).map(AppleMusicMapping.artistItem),
                             albums: AppleMusicMapping.albums(in: list("albums")),
                             songs: AppleMusicMapping.entries(songs(list("songs"))),
                             playlists: list("playlists").compactMap(AppleMusicMapping.playlist).map(AppleMusicMapping.playlistItem))
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
        guard let song = await song(rawId) else { throw RequestError(message: "Song unavailable") }
        try await MusicKit.MusicLibrary.shared.add(song)
        inLibrary.insert(rawId)
    }
}
