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
    private(set) var playlists: [LibraryItem] = []
    private(set) var playlistDetails: [String: PlaylistInfo] = [:]
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
    /// Apple Music in use (then `client` is nil).
    private var apple: AppleMusicSource? { session.apple }
    /// Spotify in use (then `client` is nil).
    private var spotify: SpotifySource? { session.spotify }
    private var mapping: SubsonicMapping? { client.map(SubsonicMapping.init(client:)) }

    func phase(_ key: String) -> Phase { phases[key] ?? .idle }

    /// Signed out or a new account: nothing from the old server stays.
    func reset() {
        generation += 1
        inflight.values.forEach { $0.cancel() }
        inflight = [:]
        inLibrary = [:]
        playlists = []
        playlistDetails = [:]
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
        await runCore(key, force: force) { live in try await body(client, mapping, live) }
    }

    /// The same for Spotify (Web API requests).
    private func runSpotify(_ key: String, force: Bool = false,
                            _ body: @escaping @MainActor (SpotifySource, _ live: @escaping @MainActor @Sendable () -> Bool) async throws -> Void) async {
        guard let spotify else { return }
        await runCore(key, force: force) { live in try await body(spotify, live) }
    }

    /// The same for Apple Music (MusicKit requests).
    private func runApple(_ key: String, force: Bool = false,
                          _ body: @escaping @MainActor (AppleMusicSource, _ live: @escaping @MainActor @Sendable () -> Bool) async throws -> Void) async {
        guard let apple else { return }
        await runCore(key, force: force) { live in try await body(apple, live) }
    }

    private func runCore(_ key: String, force: Bool,
                         _ body: @escaping @MainActor (_ live: @escaping @MainActor @Sendable () -> Bool) async throws -> Void) async {
        // One load per key at a time; a forced one waits for the one running, then reads again (so the
        // newest answer lands last).
        if let running = inflight[key] {
            await running.value
            if !force { return }
        }
        if !force, phase(key) == .loaded { return }
        let gen = generation
        phases[key] = .loading
        let task = Task { @MainActor in
            let live: @MainActor @Sendable () -> Bool = { gen == self.generation }
            do {
                try await body(live)
                guard live() else { return }
                self.phases[key] = .loaded
            } catch {
                guard live() else { return }
                let message = self.apple != nil ? AppleMusicSource.message(for: error)
                    : self.spotify != nil ? SpotifySource.message(for: error) : MacSession.message(for: error)
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
        if spotify != nil {
            await runSpotify("home", force: force) { s, live in
                let h = try await s.homeLists()
                guard live() else { return }
                self.home = Home(recentlyPlayed: h.recent, recentlyAdded: h.newest, random: h.frequent + h.older,
                                 randomSongs: h.recentTracks)
            }
            return
        }
        if apple != nil {
            await runApple("home", force: force) { a, live in
                let h = try await a.homeLists()
                guard live() else { return }
                self.home = Home(recentlyPlayed: h.recent, recentlyAdded: h.newest, random: h.frequent + h.older,
                                 randomSongs: h.recentTracks)
            }
            return
        }
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
        if spotify != nil {
            await runSpotify("albums", force: force) { s, live in
                let list = try await s.libraryAlbums()
                guard live() else { return }
                self.albums = list
            }
            return
        }
        if apple != nil {
            await runApple("albums", force: force) { a, live in
                let list = try await a.libraryAlbums()
                guard live() else { return }
                self.albums = list
            }
            return
        }
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
        if spotify != nil {
            await runSpotify("artists", force: force) { s, live in
                let list = try await s.libraryArtists()
                guard live() else { return }
                self.artists = list
            }
            return
        }
        if apple != nil {
            await runApple("artists", force: force) { a, live in
                let list = try await a.libraryArtists()
                guard live() else { return }
                self.artists = list
            }
            return
        }
        await run("artists", force: force) { client, m, live in
            let index = try await client.getArtists()
            guard live() else { return }
            self.artists = index.flatMap(\.artists).map(m.artistItem)
        }
    }

    func loadSongs(force: Bool = false) async {
        if spotify != nil {
            await runSpotify("songs", force: force) { s, live in
                let list = try await s.librarySongs()
                guard live() else { return }
                self.songs = list
            }
            return
        }
        if apple != nil {
            await runApple("songs", force: force) { a, live in
                let list = try await a.librarySongs()
                guard live() else { return }
                self.songs = list
            }
            return
        }
        await run("songs", force: force) { client, m, live in
            // Navidrome / OpenSubsonic answer an empty search3 with the whole library, a page at a time.
            var found: [SongInfo] = []
            var offset: Int32 = 0
            while offset < 20_000 {
                let page = try await client.search3(query: "", artistCount: 0, albumCount: 0, songCount: 500,
                                                    songOffset: offset).songs
                found += page
                if page.count < 500 { break }
                offset += 500
            }
            if found.isEmpty { found = try await client.getRandomSongs(size: 200) }
            guard live() else { return }
            self.songs = found.enumerated().map { m.entry($1, index: $0) }
        }
    }

    func album(_ id: String) -> Album? { albumDetails[id] }

    func playlist(_ id: String) -> PlaylistInfo? { playlistDetails[id] }

    func loadPlaylists(force: Bool = false) async {
        if spotify != nil {
            await runSpotify("playlists", force: force) { s, live in
                let list = try await s.libraryPlaylists()
                guard live() else { return }
                self.playlists = list
            }
            return
        }
        if apple != nil {
            await runApple("playlists", force: force) { a, live in
                let list = try await a.libraryPlaylists()
                guard live() else { return }
                self.playlists = list
            }
            return
        }
        await run("playlists", force: force) { client, m, live in
            let list = try await client.getPlaylists()
            guard live() else { return }
            self.playlists = list.map(m.playlistItem)
        }
    }

    func loadPlaylist(_ id: String, force: Bool = false) async {
        if spotify != nil {
            await runSpotify("playlist:\(id)", force: force) { s, live in
                guard let v = try await s.playlist(id), live() else { return }
                self.playlistDetails[id] = v
            }
            return
        }
        if apple != nil {
            await runApple("playlist:\(id)", force: force) { a, live in
                guard let p = try await a.playlist(id), live() else { return }
                self.playlistDetails[id] = p
            }
            return
        }
        await run("playlist:\(id)", force: force) { client, m, live in
            guard let d = try await client.getPlaylist(id: id), live() else { return }
            self.playlistDetails[id] = m.playlist(d)
        }
    }

    // MARK: Playlist writes (Subsonic and Spotify; Apple Music's stay read-only on the Mac)

    struct WriteError: LocalizedError {
        var message: String
        var errorDescription: String? { message }
    }

    /// Who may change [p]: on Subsonic its owner (the signed-in user); on Spotify its owner or, when it is
    /// collaborative, anyone it is shared with.
    func canEdit(_ p: PlaylistInfo) -> Bool {
        if spotify != nil { return p.ownedByMe == true || p.collaborative }
        if let user = session.account?.username { return client != nil && (p.owner.isEmpty || p.owner == user) }
        return false
    }

    /// The playlists songs can go into (the song menu's Add to Playlist).
    var writablePlaylists: [LibraryItem] {
        if spotify != nil { return spotifyWritable }
        // Subsonic: the server refuses another user's playlist (and says so).
        if client != nil { return playlists }
        return []
    }
    private(set) var spotifyWritable: [LibraryItem] = []

    func loadWritablePlaylists() async {
        guard let spotify else { return }
        let gen = generation
        guard let r = try? await spotify.writablePlaylists(), gen == generation else { return }
        spotifyWritable = r.writable
    }

    /// Appends songs to a playlist.
    func add(songIds: [String], toPlaylist id: String) async throws {
        let gen = generation
        if let spotify {
            try await spotify.addTracks(songIds, to: id)
        } else if let client {
            try await client.updatePlaylist(id: id, name: nil, comment: nil, isPublic: nil, songIdsToAdd: songIds,
                                            songIndexesToRemove: [])
        } else {
            throw WriteError(message: "Not available for this account")
        }
        guard gen == generation else { return }
        await loadPlaylist(id, force: true)
        await loadPlaylists(force: true)
    }

    /// Takes the entry at [index] (its place in the page) out of the playlist.
    func remove(entryAt index: Int, fromPlaylist id: String) async throws {
        guard let p = playlistDetails[id], p.entries.indices.contains(index) else { return }
        let gen = generation
        if let spotify {
            guard let position = p.positions?[index] else {
                throw WriteError(message: "Reload the playlist, then try again")
            }
            _ = try await spotify.removeTracks([.init(trackRawId: p.entries[index].track.id.rawId, position: position)],
                                               from: id, snapshotId: p.snapshotId)
        } else if let client {
            try await client.updatePlaylist(id: id, name: nil, comment: nil, isPublic: nil, songIdsToAdd: [],
                                            songIndexesToRemove: [KotlinInt(int: Int32(index))])
        } else {
            throw WriteError(message: "Not available for this account")
        }
        guard gen == generation else { return }
        await loadPlaylist(id, force: true)
        await loadPlaylists(force: true)
    }

    /// A new, empty playlist; its raw id.
    func createPlaylist(name: String) async throws -> String? {
        let gen = generation
        var id: String?
        if let spotify {
            id = try await spotify.createPlaylist(name: name).id
        } else if let client {
            id = try await client.createPlaylist(name: name, comment: nil, songIds: [])?.summary.id
        } else {
            throw WriteError(message: "Not available for this account")
        }
        guard gen == generation else { return nil }
        await loadPlaylists(force: true)
        await loadWritablePlaylists()
        return id
    }

    func renamePlaylist(_ id: String, to name: String) async throws {
        let gen = generation
        if let spotify {
            try await spotify.renamePlaylist(id, name: name)
        } else if let client {
            try await client.updatePlaylist(id: id, name: name, comment: nil, isPublic: nil, songIdsToAdd: [],
                                            songIndexesToRemove: [])
        } else {
            throw WriteError(message: "Not available for this account")
        }
        guard gen == generation else { return }
        await loadPlaylist(id, force: true)
        await loadPlaylists(force: true)
    }

    /// Subsonic: deleted on the server; Spotify: it leaves the user's library.
    func deletePlaylist(_ id: String) async throws {
        let gen = generation
        if let spotify {
            try await spotify.unfollowPlaylist(id)
        } else if let client {
            try await client.deletePlaylist(id: id)
        } else {
            throw WriteError(message: "Not available for this account")
        }
        guard gen == generation else { return }
        playlistDetails[id] = nil
        await loadPlaylists(force: true)
        await loadWritablePlaylists()
    }

    func loadAlbum(_ id: String, force: Bool = false) async {
        if spotify != nil {
            await runSpotify("album:\(id)", force: force) { s, live in
                guard let v = try await s.album(id), live() else { return }
                self.albumDetails[id] = v
            }
            return
        }
        if apple != nil {
            await runApple("album:\(id)", force: force) { a, live in
                guard let album = try await a.album(id), live() else { return }
                self.albumDetails[id] = album
            }
            return
        }
        await run("album:\(id)", force: force) { client, m, live in
            guard let a = try await client.getAlbum(id: id), live() else { return }
            self.albumDetails[id] = m.album(a)
        }
    }

    func artistPage(_ id: String) -> ArtistPage? { artistPages[id] }

    func loadArtist(_ id: String, force: Bool = false) async {
        if spotify != nil {
            await runSpotify("artist:\(id)", force: force) { s, live in
                guard let v = try await s.artist(id), live() else { return }
                self.artistPages[id] = v
            }
            return
        }
        if apple != nil {
            await runApple("artist:\(id)", force: force) { a, live in
                guard let page = try await a.artist(id), live() else { return }
                self.artistPages[id] = page
            }
            return
        }
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
        if let spotify, !q.isEmpty {
            let r = try await spotify.search(q)
            return SearchHits(albums: r.albums, artists: r.artists, songs: r.songs)
        }
        if let apple, !q.isEmpty {
            let r = try await apple.search(q)
            return SearchHits(albums: r.albums, artists: r.artists, songs: r.songs)
        }
        guard let client, let m = mapping, !q.isEmpty else { return SearchHits() }
        let r = try await client.search3(query: q, artistCount: 6, albumCount: 12, songCount: 20)
        return SearchHits(albums: r.albums.map(m.album), artists: r.artists.map(m.artistItem),
                          songs: r.songs.enumerated().map { m.entry($1, index: $0) })
    }

    // MARK: Writes

    func isStarred(_ track: Track) -> Bool { isStarred(songId: track.id.rawId, fallback: track.starred) }
    func isStarred(songId: String, fallback: Bool) -> Bool {
        if let o = starredOverride["song:\(songId)"] { return o }
        // Apple Music's heart is library membership: a library song is in it; a catalog song as last read.
        if apple != nil { return AppleMusicMapping.isLibraryId(songId) || inLibrary[songId] == true }
        if spotify != nil { return inLibrary[songId] ?? fallback }
        return fallback
    }

    /// Apple Music: catalog songs known to be in (or out of) the library, by raw id.
    private(set) var inLibrary: [String: Bool] = [:]

    /// Asks Apple Music whether the song is in the library (the current song's heart).
    func refreshLibraryMembership(_ songId: String) {
        if let spotify {
            let gen = generation
            Task {
                let saved = await spotify.isSaved(songId)
                guard gen == self.generation, let saved else { return }
                self.inLibrary[songId] = saved
            }
            return
        }
        guard let apple, !AppleMusicMapping.isLibraryId(songId) else { return }
        let gen = generation
        Task {
            let found = await apple.isInLibrary(songId)
            guard gen == self.generation, let found else { return }
            self.inLibrary[songId] = found
            if found { self.starredOverride["song:\(songId)"] = nil }
        }
    }

    func toggleStar(songId: String, current: Bool) {
        let key = "song:\(songId)"
        let gen = generation
        if let spotify {
            // Spotify's heart is Liked Songs: saved or not, both ways.
            let on = !isStarred(songId: songId, fallback: current)
            starredOverride[key] = on
            Task {
                do {
                    if on { try await spotify.save(songId) } else { try await spotify.unsave(songId) }
                    guard gen == self.generation else { return }
                    self.inLibrary[songId] = on
                } catch {
                    guard gen == self.generation else { return }
                    self.starredOverride[key] = !on
                    Self.log.error("like failed: \(SpotifySource.message(for: error), privacy: .public)")
                }
            }
            return
        }
        if let apple {
            // Apple Music's heart is "in your library": it can be added, not taken out. The add is only
            // accepted at first; the heart stays on while Apple confirms, and goes back if it doesn't.
            guard !isStarred(songId: songId, fallback: current) else { return }
            starredOverride[key] = true
            Task {
                do {
                    try await apple.addToLibrary(songId)
                    try? await Task.sleep(for: .seconds(3))
                    guard gen == self.generation else { return }
                    let found = await apple.isInLibrary(songId)
                    guard gen == self.generation else { return }
                    if let found { self.inLibrary[songId] = found }
                    if found != true { self.starredOverride[key] = nil }
                } catch {
                    guard gen == self.generation else { return }
                    self.starredOverride[key] = nil
                    Self.log.error("library add failed: \(AppleMusicSource.message(for: error), privacy: .public)")
                }
            }
            return
        }
        let on = !(starredOverride[key] ?? current)
        starredOverride[key] = on
        guard let client else { return }
        Task {
            do {
                if on { try await client.star(id: songId, albumId: nil, artistId: nil) }
                else { try await client.unstar(id: songId, albumId: nil, artistId: nil) }
            } catch {
                // The write failed: the heart goes back (unless the account changed meanwhile).
                guard gen == self.generation else { return }
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
        // Apple Music and Spotify keep no rating Yoin can write.
        guard apple == nil, spotify == nil else { return }
        let before = ratingOverride[songId]
        ratingOverride[songId] = .some(rating)
        guard let client else { return }
        let gen = generation
        Task {
            do { try await client.setRating(id: songId, rating: Int32(SubsonicMapping.serverRating(rating))) } catch {
                guard gen == self.generation else { return }
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
        if spotify != nil, e.track.id.provider == SpotifyMapping.provider {
            return PlayableTrack(id: e.track.id.rawId, provider: SpotifyMapping.provider, title: e.track.title,
                                 artist: e.track.artist ?? e.album.artist, album: e.album.title,
                                 albumId: e.album.id.rawId.hasPrefix("song:") ? nil : e.album.id.rawId,
                                 durationSec: Double(e.track.durationSec),
                                 streamURL: MacSpotifyOutput.streamURL(trackId: e.track.id.rawId),
                                 coverURL: e.album.coverURL)
        }
        if apple != nil, e.track.id.provider == AppleMusicMapping.provider {
            return PlayableTrack(id: e.track.id.rawId, provider: AppleMusicMapping.provider, title: e.track.title,
                                 artist: e.track.artist ?? e.album.artist, album: e.album.title,
                                 albumId: e.album.id.rawId.hasPrefix("song:") ? nil : e.album.id.rawId,
                                 durationSec: Double(e.track.durationSec),
                                 streamURL: AppleMusicMapping.streamURL(rawId: e.track.id.rawId),
                                 coverURL: e.album.coverURL)
        }
        guard let client else { return nil }
        guard let url = URL(string: client.streamUrl(id: e.track.id.rawId, maxBitRate: nil, format: nil)) else { return nil }
        return PlayableTrack(id: e.track.id.rawId, provider: e.track.id.provider, title: e.track.title,
                             artist: e.track.artist ?? e.album.artist, album: e.album.title,
                             albumId: e.album.id.rawId, durationSec: Double(e.track.durationSec), streamURL: url,
                             coverURL: e.album.coverURL, starred: e.track.starred,
                             userRating: e.track.rating.map { Int($0 / 2) }, sourceKbps: e.track.bitRateKbps)
    }
}
