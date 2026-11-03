import Foundation
import Observation
import SwiftUI
import YoinShared
import os

/// Everything the screens read from the signed-in Subsonic server, through
/// the shared Kotlin `SubsonicClient`. Signed out (`isReal == false`), the
/// screens keep reading `MockLibrary`, so the `-screen` screenshots render
/// as before.
///
/// Sources (Android HomeViewModel / LibraryViewModel for Subsonic, where iOS
/// has no Room history yet):
/// - Activities: `getAlbumList2(recent)`.
/// - Jump Back In: `frequent` and `recent` interleaved, then `random` to fill
///   the 12 cells (Android: a random pool).
/// - Recently Added: `newest`; the 2 × 2 tracks are the two newest albums'
///   first tracks.
/// - Rediscover: `highest` (the user's rated albums), else `random`.
/// - Library: albums `alphabeticalByName`, `getArtists`, `getPlaylists`,
///   songs `getRandomSongs(50)` (Android's Random mix), Favorites
///   `getStarred2` songs.
/// - Search: `search3`, playlists matched by name locally.
@MainActor
@Observable
final class LibraryStore {
    enum Phase: Equatable { case idle, loading, loaded, failed }

    struct SearchHits: Sendable {
        var query: String
        var artists: [LibraryItem] = []
        var albums: [Album] = []
        var songs: [PlaylistEntry] = []
        var playlists: [LibraryItem] = []
    }

    @ObservationIgnored let session: ServerSession
    @ObservationIgnored private let log = Logger(subsystem: "com.gpo.yoin", category: "library")

    /// Bumps on every sign-in / sign-out; views key their load tasks on it.
    private(set) var generation = 0

    private(set) var home: HomeFeed?
    private(set) var homePhase: Phase = .idle

    private(set) var albums: [Album] = []
    private(set) var artists: [LibraryItem] = []
    private(set) var playlists: [LibraryItem] = []
    private(set) var songs: [PlaylistEntry] = []
    private(set) var favorites: [PlaylistEntry] = []
    private(set) var phases: [String: Phase] = [:]

    private(set) var albumDetails: [String: Album] = [:]
    private(set) var artistPages: [String: ArtistPage] = [:]
    private(set) var playlistDetails: [String: PlaylistInfo] = [:]

    private(set) var search: SearchHits?
    private(set) var searchPhase: Phase = .idle

    /// Hearts set here, ahead of the server (reverted when the write fails).
    private(set) var starredOverride: [String: Bool] = [:]

    init(session: ServerSession) {
        self.session = session
        observeSession()
    }

    /// A new client (sign-in, sign-out, another server) starts from scratch.
    private func observeSession() {
        withObservationTracking { _ = session.client } onChange: { [weak self] in
            Task { @MainActor in
                guard let self else { return }
                self.reset()
                self.observeSession()
            }
        }
    }

    var client: SubsonicClient? { session.client }
    var isReal: Bool { session.client != nil }

    private var mapping: SubsonicMapping? { client.map(SubsonicMapping.init(client:)) }

    /// Drops everything loaded (sign-in / sign-out).
    func reset() {
        generation += 1
        home = nil
        homePhase = .idle
        albums = []
        artists = []
        playlists = []
        songs = []
        favorites = []
        phases = [:]
        albumDetails = [:]
        artistPages = [:]
        playlistDetails = [:]
        search = nil
        searchPhase = .idle
        starredOverride = [:]
    }

    func phase(_ key: String) -> Phase { phases[key] ?? .idle }

    // MARK: Home

    func loadHome(force: Bool = false) async {
        guard let client, let mapping, force || homePhase == .idle || homePhase == .failed else { return }
        let gen = generation
        homePhase = .loading
        do {
            // One after another: the Kotlin client isn't Sendable, so no `async let`.
            let r = try await client.getAlbumList2(type: .recent, size: 12, offset: 0)
            let f = try await client.getAlbumList2(type: .frequent, size: 12, offset: 0)
            let n = try await client.getAlbumList2(type: .newest, size: 10, offset: 0)
            let h = try await client.getAlbumList2(type: .highest, size: 8, offset: 0)
            let x = try await client.getAlbumList2(type: .random, size: 16, offset: 0)
            let recentAlbums = r.map(mapping.album)
            let newestAlbums = n.map(mapping.album)
            // The two newest albums' first tracks for the 2 × 2 grid.
            var tracks: [PlaylistEntry] = []
            for info in n.prefix(2) {
                if let full = try? await client.getAlbum(id: info.id) {
                    let album = mapping.album(full)
                    albumDetails[album.id.rawId] = album
                    tracks += album.tracks.prefix(2).map { PlaylistEntry(album: album, track: $0) }
                }
            }
            guard gen == generation else { return }
            home = Self.buildHome(recent: recentAlbums, frequent: f.map(mapping.album), newest: newestAlbums,
                                  highest: h.map(mapping.album), random: x.map(mapping.album), recentTracks: tracks)
            homePhase = .loaded
        } catch {
            guard gen == generation else { return }
            log.error("home failed: \(ServerSession.message(for: error), privacy: .public)")
            homePhase = .failed
        }
    }

    /// Pure: the shelves from the lists (unit-tested).
    nonisolated static func buildHome(recent: [Album], frequent: [Album], newest: [Album], highest: [Album],
                                      random: [Album], recentTracks: [PlaylistEntry]) -> HomeFeed {
        func plays(_ a: Album) -> String {
            guard let n = a.playCount, n > 0 else { return "Recently active" }
            return n == 1 ? "1 play" : "\(n) plays"
        }
        let activityIds = ["hero", "artist", "wide", "strip"]
        // The artist card already says "Artist" over a narrow column: no eyebrow there.
        let activities = zip(activityIds, recent.prefix(4)).map {
            ActivityEntry(id: $0, eyebrow: $0 == "artist" ? "" : plays($1), album: $1)
        }

        var seen = Set(activities.map(\.album.id))
        var jbi: [Album] = []
        let rest = Array(recent.dropFirst(4))
        var mixed: [Album] = []
        for i in 0..<max(frequent.count, rest.count) {
            if i < frequent.count { mixed.append(frequent[i]) }
            if i < rest.count { mixed.append(rest[i]) }
        }
        mixed += random
        for a in mixed where jbi.count < 12 && seen.insert(a.id).inserted { jbi.append(a) }

        let gridAlbums = Set(recentTracks.map(\.album.id))
        let recentAlbums = newest.filter { !gridAlbums.contains($0.id) || recentTracks.isEmpty }

        var rediscover: [RediscoverEntry] = []
        let rated = highest.filter { $0.rating != nil }
        for a in (rated + random.reversed()) where rediscover.count < 2 && !rediscover.contains(where: { $0.album.id == a.id }) {
            rediscover.append(RediscoverEntry(album: a, reason: a.rating != nil ? "Rated" : "From your library",
                                              away: a.genre ?? (a.year > 0 ? String(a.year) : ""),
                                              score: a.rating, footnoteDate: Self.yearMonth(a.created),
                                              footnotePlays: "\(a.playCount ?? 0)"))
        }
        return HomeFeed(layout: .server, activities: activities, jumpBackIn: jbi, recentTracks: recentTracks,
                        recentAlbums: recentAlbums, rediscover: rediscover)
    }

    /// "2024-03-17T…" → "2024.03" (Android's Rediscover footnote).
    nonisolated static func yearMonth(_ iso: String?) -> String {
        guard let iso, iso.count >= 7 else { return "" }
        let s = Array(iso)
        return String(s[0..<4]) + "." + String(s[5..<7])
    }

    // MARK: Library lists

    func loadLibrary(_ kind: LibraryKind?) async {
        switch kind {
        case nil:
            async let a: Void = loadAlbums()
            async let b: Void = loadArtists()
            async let c: Void = loadPlaylists()
            _ = await (a, b, c)
        case .albums?: await loadAlbums()
        case .artists?: await loadArtists()
        case .playlists?: await loadPlaylists()
        case .songs?: await loadSongs()
        case .favorites?: await loadFavorites()
        }
    }

    /// Runs `body` once per key and generation (again after a failure or with `force`).
    private func once(_ key: String, force: Bool = false, _ body: () async throws -> Void) async {
        guard isReal else { return }
        let p = phase(key)
        guard force || p == .idle || p == .failed else { return }
        let gen = generation
        phases[key] = .loading
        do {
            try await body()
            if gen == generation { phases[key] = .loaded }
        } catch {
            log.error("\(key, privacy: .public) failed: \(ServerSession.message(for: error), privacy: .public)")
            if gen == generation { phases[key] = .failed }
        }
    }

    func loadAlbums(force: Bool = false) async {
        await once("albums", force: force) {
            guard let client, let mapping else { return }
            var all: [Album] = []
            var offset: Int32 = 0
            while true {
                let page = try await client.getAlbumList2(type: .alphabeticalByName, size: 500, offset: offset)
                all += page.map(mapping.album)
                if page.count < 500 || all.count >= 5000 { break }
                offset += 500
            }
            albums = all
        }
    }

    func loadArtists(force: Bool = false) async {
        await once("artists", force: force) {
            guard let client, let mapping else { return }
            artists = try await client.getArtists().flatMap { $0.artists.map(mapping.artistItem) }
        }
    }

    func loadPlaylists(force: Bool = false) async {
        await once("playlists", force: force) {
            guard let client, let mapping else { return }
            playlists = try await client.getPlaylists().map(mapping.playlistItem)
        }
    }

    func loadSongs(force: Bool = false) async {
        await once("songs", force: force) {
            guard let client, let mapping else { return }
            songs = try await client.getRandomSongs(size: 50).enumerated().map { mapping.entry($1, index: $0) }
        }
    }

    func loadFavorites(force: Bool = false) async {
        await once("favorites", force: force) {
            guard let client, let mapping else { return }
            favorites = try await client.getStarred2().songs.enumerated().map { mapping.entry($1, index: $0) }
        }
    }

    /// Library's All grid: playlists, artists, then albums.
    var libraryItems: [LibraryItem] {
        guard let mapping else { return [] }
        return playlists + artists + albums.map(mapping.albumItem)
    }

    // MARK: Details

    func album(_ id: String) -> Album? { albumDetails[id] }

    func loadAlbum(_ id: String, force: Bool = false) async {
        await once("album:\(id)", force: force) {
            guard let client, let mapping else { return }
            if let info = try await client.getAlbum(id: id) { albumDetails[id] = mapping.album(info) }
        }
    }

    func artistPage(_ id: String) -> ArtistPage? { artistPages[id] }

    func loadArtist(_ id: String, force: Bool = false) async {
        await once("artist:\(id)", force: force) {
            guard let client, let mapping else { return }
            guard let info = try await client.getArtist(id: id) else { return }
            artistPages[id] = mapping.artistPage(info, loadedAlbums: info.albums.compactMap { albumDetails[$0.id] })
            // Most Played needs the songs: fetch the releases (up to 12).
            var loaded: [Album] = []
            for summary in info.albums.prefix(12) {
                if let have = albumDetails[summary.id] { loaded.append(have); continue }
                if let full = try? await client.getAlbum(id: summary.id) {
                    let album = mapping.album(full)
                    albumDetails[summary.id] = album
                    loaded.append(album)
                }
            }
            artistPages[id] = mapping.artistPage(info, loadedAlbums: loaded)
        }
    }

    func playlist(_ id: String) -> PlaylistInfo? { playlistDetails[id] }

    func loadPlaylist(_ id: String, force: Bool = false) async {
        await once("playlist:\(id)", force: force) {
            guard let client, let mapping else { return }
            if let detail = try await client.getPlaylist(id: id) { playlistDetails[id] = mapping.playlist(detail) }
        }
    }

    /// The playlist's library row, for a page whose detail is still loading.
    func playlistSummary(_ id: String) -> LibraryItem? { playlists.first { $0.id.rawId == id } }

    // MARK: Search

    func runSearch(_ query: String) async {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let client, let mapping, !q.isEmpty else { search = nil; searchPhase = .idle; return }
        searchPhase = .loading
        do {
            async let lists: Void = loadPlaylists()
            let r = try await client.search3(query: q, artistCount: 20, albumCount: 30, songCount: 40)
            await lists
            guard !Task.isCancelled else { return }
            let lower = q.lowercased()
            search = SearchHits(query: q,
                                artists: r.artists.map(mapping.artistItem),
                                albums: r.albums.map(mapping.album),
                                songs: r.songs.enumerated().map { mapping.entry($1, index: $0) },
                                playlists: playlists.filter { $0.title.lowercased().contains(lower) })
            searchPhase = .loaded
        } catch {
            guard !Task.isCancelled else { return }
            log.error("search failed: \(ServerSession.message(for: error), privacy: .public)")
            searchPhase = .failed
        }
    }

    // MARK: Hearts and ratings

    func isStarred(_ track: Track) -> Bool { starredOverride[track.id.rawId] ?? track.starred }

    func isStarred(artist page: ArtistPage) -> Bool { starredOverride["artist:\(page.id.rawId)"] ?? page.starred }

    /// Optimistic heart; reverts when the server refuses (Android rule).
    func toggleStar(_ track: Track) {
        let key = track.id.rawId
        let now = !isStarred(track)
        write(key: key, on: now) { client, on in
            on ? try await client.star(id: key, albumId: nil, artistId: nil)
                : try await client.unstar(id: key, albumId: nil, artistId: nil)
        }
    }

    func toggleStar(artist page: ArtistPage) {
        let id = page.id.rawId
        let now = !isStarred(artist: page)
        write(key: "artist:\(id)", on: now) { client, on in
            on ? try await client.star(id: nil, albumId: nil, artistId: id)
                : try await client.unstar(id: nil, albumId: nil, artistId: id)
        }
    }

    private func write(key: String, on: Bool, _ call: @escaping @MainActor (SubsonicClient, Bool) async throws -> Void) {
        let before = starredOverride[key]
        starredOverride[key] = on
        guard let client else { return } // mock: local only
        Task {
            do {
                try await call(client, on)
            } catch {
                log.error("star failed: \(ServerSession.message(for: error), privacy: .public)")
                withAnimation(Yoin.effects) { self.starredOverride[key] = before ?? !on }
            }
        }
    }

    /// Writes a track's rating (Yoin's 0–10; the server gets 0–5).
    func setRating(_ track: Track, rating: Double?) async -> Bool {
        guard let client else { return false }
        do {
            try await client.setRating(id: track.id.rawId, rating: Int32(SubsonicMapping.serverRating(rating)))
            return true
        } catch {
            log.error("rating failed: \(ServerSession.message(for: error), privacy: .public)")
            return false
        }
    }

    // MARK: Playback

    /// A queue entry for the engine (stream + cover URLs from the client).
    func playable(_ e: PlaylistEntry) -> PlayableTrack? {
        guard let client, e.track.id.provider != "mock" else { return nil }
        var t = PlayableTrack(id: e.track.id.rawId, provider: e.track.id.provider, title: e.track.title,
                              artist: e.track.artist ?? e.album.artist, album: e.album.title,
                              albumId: e.album.id.rawId.hasPrefix("song:") ? nil : e.album.id.rawId,
                              durationSec: Double(e.track.durationSec),
                              streamURL: URL(string: client.streamUrl(id: e.track.id.rawId, maxBitRate: nil,
                                                                      format: nil))!,
                              coverURL: e.album.coverURL)
        t.starred = isStarred(e.track)
        t.userRating = e.track.rating.map { SubsonicMapping.serverRating($0) }
        return t
    }

    /// The album with its tracks (loads them when only the summary is known).
    func fullAlbum(_ album: Album) async -> Album {
        if !album.tracks.isEmpty || !isReal { return album }
        await loadAlbum(album.id.rawId)
        return albumDetails[album.id.rawId] ?? album
    }

    func scrobble(_ track: PlayableTrack, submission: Bool) {
        guard let client, track.provider != "mock" else { return }
        let ms = submission ? KotlinLong(value: Int64(Date().timeIntervalSince1970 * 1000)) : nil
        Task {
            do { try await client.scrobble(id: track.id, submission: submission, timeMs: ms) } catch {
                log.info("scrobble failed: \(ServerSession.message(for: error), privacy: .public)")
            }
        }
    }

    /// The engine's song as the UI's Album: the fetched album when known
    /// (year, tracks, cover), else what the queue entry carries.
    func albumForPlaying(_ t: PlayableTrack) -> Album? {
        guard let id = t.albumId, var album = albumDetails[id] else { return nil }
        if album.coverURL == nil, let url = t.coverURL {
            album.palette = CoverPalette(a: album.palette.a, b: album.palette.b, c: album.palette.c,
                                         cover: CoverRef(url: url))
        }
        return album
    }
}
