import Foundation
import Observation
import YoinShared
import os

/// Everything the Mac screens read from the Subsonic server: Home's lists,
/// the Library grids, album / artist pages and search, plus the writes
/// (star, rating, scrobble). One `SubsonicClient` (MacSession), the iOS
/// `SubsonicMapping` turning its models into the shared Swift UI models.
@MainActor
@Observable
final class MacLibrary {
    enum Phase: Equatable { case idle, loading, loaded, failed(String) }

    struct Home: Sendable {
        var recentlyPlayed: [Album] = []
        var recentlyAdded: [Album] = []
        var random: [Album] = []
        var randomSongs: [PlaylistEntry] = []
    }

    struct SearchHits: Sendable {
        var albums: [Album] = []
        var artists: [LibraryItem] = []
        var songs: [PlaylistEntry] = []
        var isEmpty: Bool { albums.isEmpty && artists.isEmpty && songs.isEmpty }
    }

    private(set) var home = Home()
    private(set) var albums: [Album] = []
    private(set) var artists: [LibraryItem] = []
    private(set) var songs: [PlaylistEntry] = []
    private(set) var albumDetails: [String: Album] = [:]
    private(set) var artistPages: [String: ArtistPage] = [:]
    private(set) var phases: [String: Phase] = [:]
    /// Hearts flipped here before the server's lists catch up (key: "song:id", "album:id", "artist:id").
    private(set) var starredOverride: [String: Bool] = [:]
    /// Ratings set here (Yoin 0–10), by song id.
    private(set) var ratingOverride: [String: Double?] = [:]

    @ObservationIgnored private let session: MacSession
    @ObservationIgnored private var generation = 0
    @ObservationIgnored private var inflight: [String: Task<Void, Never>] = [:]
    private static let log = Logger(subsystem: "com.gpo.yoin.mac", category: "library")

    init(session: MacSession) {
        self.session = session
    }

    var client: SubsonicClient? { session.client }
    private var mapping: SubsonicMapping? { client.map(SubsonicMapping.init(client:)) }

    func phase(_ key: String) -> Phase { phases[key] ?? .idle }

    /// Signed out or a new account: nothing from the old server stays.
    func reset() {
        generation += 1
        inflight.values.forEach { $0.cancel() }
        inflight = [:]
        home = Home()
        albums = []
        artists = []
        songs = []
        albumDetails = [:]
        artistPages = [:]
        phases = [:]
        starredOverride = [:]
        ratingOverride = [:]
    }

    // MARK: Loads

    /// One load per key at a time: a second caller waits for the one in flight. `body` gets `live`, false once
    /// the account changed; it checks it right before writing state (a late answer from an old account
    /// must not land in the new one's library).
    private func run(_ key: String, force: Bool = false,
                     _ body: @escaping @MainActor (SubsonicClient, SubsonicMapping, _ live: @escaping @MainActor @Sendable () -> Bool) async throws -> Void) async {
        guard let client, let mapping else { return }
        if !force, let running = inflight[key] { await running.value; return }
        if !force, phase(key) == .loaded { return }
        let gen = generation
        phases[key] = .loading
        let task = Task { @MainActor in
            let live: @MainActor @Sendable () -> Bool = { gen == self.generation }
            do {
                try await body(client, mapping, live)
                guard live() else { return }
                self.phases[key] = .loaded
            } catch {
                guard live() else { return }
                let message = MacSession.message(for: error)
                Self.log.error("\(key, privacy: .public) failed: \(message, privacy: .public)")
                self.phases[key] = .failed(message)
            }
        }
        inflight[key] = task
        await task.value
        if inflight[key] == task { inflight[key] = nil }
    }

    /// The account's generation: callers that load and then act (play an album) check it is unchanged.
    var accountGeneration: Int { generation }

    func loadHome(force: Bool = false) async {
        await run("home", force: force) { client, m, live in
            async let recent = client.getAlbumList2(type: .recent, size: 16, offset: 0)
            async let newest = client.getAlbumList2(type: .newest, size: 18, offset: 0)
            async let random = client.getAlbumList2(type: .random, size: 18, offset: 0)
            async let randomSongs = client.getRandomSongs(size: 8)
            let (r, n, x, s) = try await (recent, newest, random, randomSongs)
            guard live() else { return }
            self.home = Home(recentlyPlayed: r.map(m.album), recentlyAdded: n.map(m.album), random: x.map(m.album),
                             randomSongs: s.enumerated().map { m.entry($1, index: $0) })
        }
    }

    func loadAlbums(force: Bool = false) async {
        await run("albums", force: force) { client, m, live in
            var all: [Album] = []
            var offset: Int32 = 0
            while offset < 5000 {
                let page = try await client.getAlbumList2(type: .alphabeticalByName, size: 500, offset: offset)
                all += page.map(m.album)
                if page.count < 500 { break }
                offset += 500
            }
            guard live() else { return }
            self.albums = all
        }
    }

    func loadArtists(force: Bool = false) async {
        await run("artists", force: force) { client, m, live in
            let index = try await client.getArtists()
            guard live() else { return }
            self.artists = index.flatMap(\.artists).map(m.artistItem)
        }
    }

    func loadSongs(force: Bool = false) async {
        await run("songs", force: force) { client, m, live in
            // Navidrome / OpenSubsonic answer an empty search3 with the whole library.
            var found = try await client.search3(query: "", artistCount: 0, albumCount: 0, songCount: 500).songs
            if found.isEmpty { found = try await client.getRandomSongs(size: 200) }
            guard live() else { return }
            self.songs = found.enumerated().map { m.entry($1, index: $0) }
        }
    }

    func album(_ id: String) -> Album? { albumDetails[id] }

    func loadAlbum(_ id: String, force: Bool = false) async {
        await run("album:\(id)", force: force) { client, m, live in
            guard let a = try await client.getAlbum(id: id), live() else { return }
            self.albumDetails[id] = m.album(a)
        }
    }

    func artistPage(_ id: String) -> ArtistPage? { artistPages[id] }

    func loadArtist(_ id: String, force: Bool = false) async {
        await run("artist:\(id)", force: force) { client, m, live in
            guard let a = try await client.getArtist(id: id) else { return }
            // Most Played needs the songs: the first releases' track lists, at once.
            let ids = a.albums.prefix(8).map(\.id).filter { self.albumDetails[$0] == nil }
            let loaded = try await withThrowingTaskGroup(of: AlbumInfo?.self) { group in
                for id in ids { group.addTask { try await client.getAlbum(id: id) } }
                var out: [AlbumInfo] = []
                for try await al in group { if let al { out.append(al) } }
                return out
            }
            guard live() else { return }
            for info in loaded { self.albumDetails[info.id] = m.album(info) }
            let known = a.albums.compactMap { self.albumDetails[$0.id] }
            self.artistPages[id] = m.artistPage(a, loadedAlbums: known)
        }
    }

    /// The album with its tracks (a list album has none until its page loads).
    func fullAlbum(_ album: Album) async -> Album {
        if !album.tracks.isEmpty { return album }
        if let hit = albumDetails[album.id.rawId] { return hit }
        await loadAlbum(album.id.rawId)
        return albumDetails[album.id.rawId] ?? album
    }

    func search(_ query: String) async throws -> SearchHits {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let client, let m = mapping, !q.isEmpty else { return SearchHits() }
        let r = try await client.search3(query: q, artistCount: 6, albumCount: 12, songCount: 20)
        return SearchHits(albums: r.albums.map(m.album), artists: r.artists.map(m.artistItem),
                          songs: r.songs.enumerated().map { m.entry($1, index: $0) })
    }

    // MARK: Writes

    func isStarred(_ track: Track) -> Bool { starredOverride["song:\(track.id.rawId)"] ?? track.starred }
    func isStarred(songId: String, fallback: Bool) -> Bool { starredOverride["song:\(songId)"] ?? fallback }

    func toggleStar(songId: String, current: Bool) {
        let key = "song:\(songId)"
        let on = !(starredOverride[key] ?? current)
        starredOverride[key] = on
        guard let client else { return }
        Task {
            do {
                if on { try await client.star(id: songId, albumId: nil, artistId: nil) }
                else { try await client.unstar(id: songId, albumId: nil, artistId: nil) }
            } catch {
                // The write failed: the heart goes back.
                self.starredOverride[key] = !on
                Self.log.error("star failed: \(MacSession.message(for: error), privacy: .public)")
            }
        }
    }

    func rating(_ track: Track) -> Double? {
        if let r = ratingOverride[track.id.rawId] { return r }
        return track.rating
    }

    func rating(songId: String, fallback: Double?) -> Double? {
        if let r = ratingOverride[songId] { return r }
        return fallback
    }

    /// Yoin 0–10 (nil clears); the server keeps 0–5 stars.
    func setRating(songId: String, _ rating: Double?) {
        let before = ratingOverride[songId]
        ratingOverride[songId] = .some(rating)
        guard let client else { return }
        Task {
            do { try await client.setRating(id: songId, rating: Int32(SubsonicMapping.serverRating(rating))) } catch {
                self.ratingOverride[songId] = before
                Self.log.error("rating failed: \(MacSession.message(for: error), privacy: .public)")
            }
        }
    }

    /// PlayerEngine decides when (started; then half the song or 4 minutes heard). "Now playing" is
    /// fire-and-forget; a play that couldn't be sent waits in `pendingScrobbles` (with the time it
    /// happened) and goes with the next one that can.
    func scrobble(_ track: PlayableTrack, submission: Bool) {
        guard let client, track.provider == MediaRef.companion.PROVIDER_SUBSONIC else { return }
        guard submission else {
            Task { try? await client.scrobble(id: track.id, submission: false, timeMs: nil) }
            return
        }
        let key = pendingKeyForAccount
        var queue = pending(key)
        queue.append(PendingScrobble(id: track.id, timeMs: Int64(Date().timeIntervalSince1970 * 1000)))
        setPending(Array(queue.suffix(500)), key)
        flushScrobbles(client, key: key)
    }

    private struct PendingScrobble: Codable, Equatable {
        var id: String
        var timeMs: Int64
    }

    private static let pendingKey = "pendingScrobbles"

    /// Kept per account in the defaults (scope: the server and user).
    private func pending(_ key: String) -> [PendingScrobble] {
        guard let d = UserDefaults.standard.data(forKey: key) else { return [] }
        return (try? JSONDecoder().decode([PendingScrobble].self, from: d)) ?? []
    }

    private func setPending(_ list: [PendingScrobble], _ key: String) {
        UserDefaults.standard.set(try? JSONEncoder().encode(list), forKey: key)
    }

    private var pendingKeyForAccount: String { "\(Self.pendingKey).\(session.accountKey)" }
    @ObservationIgnored private var flushing: Set<String> = []

    /// Sends the account's waiting plays in order; the account's key is fixed for the whole flush.
    private func flushScrobbles(_ client: SubsonicClient, key: String) {
        guard !flushing.contains(key) else { return }
        flushing.insert(key)
        Task {
            defer { flushing.remove(key) }
            while let next = pending(key).first {
                do {
                    try await client.scrobble(id: next.id, submission: true, timeMs: KotlinLong(value: next.timeMs))
                    setPending(pending(key).filter { $0 != next }, key)
                } catch {
                    return
                }
            }
        }
    }

    // MARK: Playback

    func playable(_ e: PlaylistEntry) -> PlayableTrack? {
        guard let client else { return nil }
        guard let url = URL(string: client.streamUrl(id: e.track.id.rawId, maxBitRate: nil, format: nil)) else { return nil }
        return PlayableTrack(id: e.track.id.rawId, provider: e.track.id.provider, title: e.track.title,
                             artist: e.track.artist ?? e.album.artist, album: e.album.title,
                             albumId: e.album.id.rawId, durationSec: Double(e.track.durationSec), streamURL: url,
                             coverURL: e.album.coverURL, starred: e.track.starred,
                             userRating: e.track.rating.map { Int($0 / 2) }, sourceKbps: e.track.bitRateKbps)
    }
}
