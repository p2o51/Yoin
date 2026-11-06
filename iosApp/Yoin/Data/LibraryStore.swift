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
///
/// Local history (`HistoryStore`, with `NotesStore` and `LocalRatings`) lays
/// over the server's shelves the way Android's Room history does: Activities
/// by the activity log ("2h ago"), Jump Back In's rated-album and noted-track
/// 1×2s and memory marks, Rediscover's 90-day rule ("7 months away").
@MainActor
@Observable
final class LibraryStore {
    enum Phase: Equatable { case idle, loading, loaded, failed }

    struct SearchHits: Sendable {
        var query: String
        var scope: SearchScope = .library
        var artists: [LibraryItem] = []
        var albums: [Album] = []
        var songs: [PlaylistEntry] = []
        var playlists: [LibraryItem] = []
        /// Catalog search: the kinds with a next page, and each kind's next offset (Spotify).
        var more: Set<SearchKind> = []
        var offsets: [SearchKind: Int] = [:]
    }

    @ObservationIgnored let session: ServerSession
    @ObservationIgnored let sources: SourceSelection
    @ObservationIgnored let appleMusic: AppleMusicSource
    /// The Spotify account in use, its source built by the app as accounts switch (nil: none).
    var spotifyAccount: (source: SpotifySource, scope: String)?
    @ObservationIgnored let history: HistoryStore
    @ObservationIgnored let notes: NotesStore
    @ObservationIgnored let ratings: LocalRatings
    /// Pages and lists on disk (Data/Cache/DetailDiskCache): painted at once, refreshed behind.
    @ObservationIgnored let disk: DetailDiskCache
    @ObservationIgnored private let log = Logger(subsystem: "com.gpo.yoin", category: "library")
    /// Subsonic plays waiting for the server, and when each song started (its submission's time).
    @ObservationIgnored let scrobbles: ScrobbleOutbox = .shared
    @ObservationIgnored private var scrobbleStarts: [String: Int64] = [:]
    @ObservationIgnored private var flushingScrobbles = false

    /// Bumps on every sign-in / sign-out; views key their load tasks on it.
    private(set) var generation = 0

    /// The server's lists behind Home (history lays over them in `home`).
    private(set) var homeLists: HomeLists?
    private(set) var homePhase: Phase = .idle

    struct HomeLists: Sendable {
        var recent: [Album]
        var frequent: [Album]
        var newest: [Album]
        var highest: [Album]
        var random: [Album]
        var recentTracks: [PlaylistEntry]
    }

    private(set) var albums: [Album] = []
    private(set) var artists: [LibraryItem] = []
    private(set) var playlists: [LibraryItem] = []
    private(set) var songs: [PlaylistEntry] = []
    private(set) var favorites: [PlaylistEntry] = []
    /// The source's starred artists and albums (Subsonic getStarred2), over
    /// Favorites' songs. In memory only: the disk copy holds the songs.
    private(set) var favoriteArtists: [LibraryItem] = []
    private(set) var favoriteAlbums: [Album] = []
    private(set) var phases: [String: Phase] = [:]
    /// The last failed load's message per key (shown with its Retry); cleared when the key loads.
    private(set) var failures: [String: String] = [:]

    private(set) var albumDetails: [String: Album] = [:]
    private(set) var artistPages: [String: ArtistPage] = [:]
    private(set) var playlistDetails: [String: PlaylistInfo] = [:]

    var search: SearchHits?
    private(set) var searchPhase: Phase = .idle
    /// A search kind's next page being read, or failed (Android searchPaging); a kind not here
    /// has more exactly when `search.more` says so.
    var searchPaging: [SearchKind: SongsMore] = [:]
    /// Where Liked Songs' next read starts (Spotify; nil: the list is whole).
    @ObservationIgnored var spotifySongsNext: Int?

    /// Artist hearts set here, ahead of the server (reverted when the write
    /// fails). Song hearts live in `FavoritesStore`, shared with Now Playing.
    private(set) var starredOverride: [String: Bool] = [:]
    @ObservationIgnored private let hearts: FavoritesStore = .shared

    init(session: ServerSession, sources: SourceSelection, appleMusic: AppleMusicSource,
         history: HistoryStore = .shared, notes: NotesStore = .shared, ratings: LocalRatings = .shared,
         disk: DetailDiskCache = .shared) {
        self.session = session
        self.sources = sources
        self.appleMusic = appleMusic
        self.history = history
        self.notes = notes
        self.ratings = ratings
        self.disk = disk
        observeSession()
        hearts.onWrite = { [weak self] provider, songId in
            // Wherever the heart was set: the stored pages that show it are written with it.
            guard let self else { return }
            let on = self.hearts.isFavorite(provider: provider, songId: songId)
            self.dropAlbums(containing: songId) { $0.starred = on }
            self.dropFromDisk(.list, "favorites")
            // The starred list is the source's: read it again on the next visit.
            self.favoritesStale = true
        }
        Task { await disk.purgeExpired() }
    }

    /// A new client (sign-in, sign-out, another server) or another source
    /// starts from scratch.
    private func observeSession() {
        withObservationTracking {
            _ = session.client
            _ = sources.active
            _ = appleMusic.authorization
            _ = spotifyAccount?.scope
        } onChange: { [weak self] in
            Task { @MainActor in
                guard let self else { return }
                self.reset()
                self.observeSession()
            }
        }
    }

    /// The Subsonic client, when Subsonic is the active source.
    var client: SubsonicClient? { sources.active == .subsonic ? session.client : nil }
    /// Apple Music, when it is the active source and authorized.
    var apple: AppleMusicSource? { sources.active == .appleMusic && appleMusic.isAuthorized ? appleMusic : nil }
    /// Spotify, when it is the active source and an account is signed in.
    var spotify: SpotifySource? { sources.active == .spotify ? spotifyAccount?.source : nil }
    var isReal: Bool { client != nil || apple != nil || spotify != nil }
    /// The active source's provider token (new ids are minted in it).
    var provider: String {
        if spotify != nil { return SpotifyMapping.provider }
        return apple != nil ? AppleMusicMapping.provider : MediaRef.companion.PROVIDER_SUBSONIC
    }

    /// The active source's words for a failure.
    private func message(for error: Error) -> String {
        if spotify != nil { return SpotifySource.message(for: error) }
        return apple != nil ? AppleMusicSource.message(for: error) : ServerSession.message(for: error)
    }

    /// A palette for a page whose data is still loading: Subsonic's cover for
    /// [coverId] when given, else a quiet placeholder.
    func placeholderPalette(coverId: String?) -> CoverPalette {
        if let mapping { return mapping.palette(coverId: coverId) }
        return AppleMusicMapping.palette(nil, key: coverId ?? "")
    }

    private var mapping: SubsonicMapping? { client.map(SubsonicMapping.init(client:)) }

    /// Drops everything loaded (sign-in / sign-out).
    func reset() {
        generation += 1
        homeLists = nil
        lastRecordedSong = nil
        openPlayId = nil
        homePhase = .idle
        albums = []
        artists = []
        playlists = []
        songs = []
        songsMore = .none
        songsPager = nil
        favorites = []
        favoriteArtists = []
        favoriteAlbums = []
        phases = [:]
        failures = [:]
        albumDetails = [:]
        artistPages = [:]
        playlistDetails = [:]
        search = nil
        searchRequest = nil
        searchPhase = .idle
        starredOverride = [:]
        hearts.reset()
        // Old-source loads stop here (Codex C01); one that answers anyway fails `ensureLive`.
        for task in inFlight.values { task.cancel() }
        inFlight = [:]
        albumsNextOffset = nil
    }

    func phase(_ key: String) -> Phase { phases[key] ?? .idle }

    // MARK: Home

    func loadHome(force: Bool = false) async {
        guard force || homePhase == .idle || homePhase == .failed else { return }
        guard isReal else { return }
        // A launch or an account: plays left over from before go out now.
        flushScrobbles()
        // The last feed paints at once (Android's Home snapshot); the load below replaces it.
        let gen = generation, epoch = diskEpoch, scope = historyScope
        homePhase = .loading
        if homeLists == nil, let codec, let hit = await disk.read(DetailCacheDTO.Home.self, scope: scope, kind: .home, id: ""),
           gen == generation, homeLists == nil {
            homeLists = codec.home(hit.value)
            homePhase = .loaded
        }
        guard gen == generation else { return }
        if let apple { return await loadAppleHome(apple, epoch: epoch) }
        if let spotify { return await loadSpotifyHome(spotify, epoch: epoch) }
        guard let client, let mapping else { return }
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
                    guard gen == generation else { return }
                    keepAlbum(album, epoch: epoch)
                    tracks += album.tracks.prefix(2).map { PlaylistEntry(album: album, track: $0) }
                }
            }
            guard gen == generation else { return }
            let lists = HomeLists(recent: recentAlbums, frequent: f.map(mapping.album), newest: newestAlbums,
                                  highest: h.map(mapping.album), random: x.map(mapping.album), recentTracks: tracks)
            homeLists = lists
            homePhase = .loaded
            persist(codec?.dto(lists), kind: .home, id: "", epoch: epoch)
        } catch {
            guard gen == generation else { return }
            log.error("home failed: \(ServerSession.message(for: error), privacy: .public)")
            // Offline: the stored feed stays up.
            homePhase = homeLists != nil ? .loaded : .failed
        }
    }

    /// Apple Music (Android AppleMusicSource for the lists): Activities =
    /// recently played albums; Jump Back In = heavy rotation interleaved with
    /// the rest of recently played, then older library adds; Recently Added =
    /// the library's newest albums, its 2 × 2 grid the last songs played;
    /// Rediscover = older library adds (no ratings to read from Apple).
    private func loadAppleHome(_ apple: AppleMusicSource, epoch: Int) async {
        let gen = generation
        homePhase = .loading
        do {
            let l = try await apple.homeLists()
            guard gen == generation else { return }
            let lists = HomeLists(recent: l.recent, frequent: l.frequent, newest: l.newest, highest: [],
                                  random: l.older, recentTracks: l.recentTracks)
            homeLists = lists
            homePhase = .loaded
            persist(codec?.dto(lists), kind: .home, id: "", epoch: epoch)
        } catch {
            guard gen == generation else { return }
            log.error("home failed: \(AppleMusicSource.message(for: error), privacy: .public)")
            homePhase = homeLists != nil ? .loaded : .failed
        }
    }

    /// Spotify (Android SpotifyMusicSource): Activities = recently played; Recently Added = the newest
    /// saved albums, its grid the last songs played; the rest of the saved albums behind.
    private func loadSpotifyHome(_ spotify: SpotifySource, epoch: Int) async {
        let gen = generation
        homePhase = .loading
        do {
            let l = try await spotify.homeLists()
            guard gen == generation else { return }
            let lists = HomeLists(recent: l.recent, frequent: l.frequent, newest: l.newest, highest: [],
                                  random: l.older, recentTracks: l.recentTracks)
            homeLists = lists
            homePhase = .loaded
            persist(codec?.dto(lists), kind: .home, id: "", epoch: epoch)
        } catch {
            guard gen == generation else { return }
            log.error("home failed: \(SpotifySource.message(for: error), privacy: .public)")
            homePhase = homeLists != nil ? .loaded : .failed
        }
    }

    /// Home: the server's shelves with the local history laid over them.
    /// Reads the history, notes and ratings, so a new play or note redraws it.
    ///
    /// Built once and kept until something it read changes (Home's body runs
    /// again whenever Now Playing or a sheet moves over it; rebuilding the
    /// history each time cost ~20 ms a pass on an iPhone Air). The build runs
    /// under observation tracking; the first change it saw bumps
    /// [homeRevision], which Home reads, so Home asks again and gets a new one.
    var home: HomeFeed? {
        let revision = homeRevision
        if let cached = homeCache, cached.revision == revision { return cached.feed }
        let feed = withObservationTracking {
            homeLists.map { l in
                Self.buildHome(recent: l.recent, frequent: l.frequent, newest: l.newest, highest: l.highest,
                               random: l.random, recentTracks: l.recentTracks, history: homeHistory(l),
                               playlists: playlists)
            }
        } onChange: { [weak self] in
            // Called before the change lands: rebuild on the next turn.
            DispatchQueue.main.async { self?.homeRevision &+= 1 }
        }
        homeCache = (revision, feed)
        return feed
    }

    private var homeRevision = 0
    @ObservationIgnored private var homeCache: (revision: Int, feed: HomeFeed?)?

    /// The account's album memories (the Home lists' albums known too), for
    /// the Memories bubble and deck. Signed out: none (the mock deck stands in).
    var memories: [AlbumMemory] {
        guard isReal else { return [] }
        return HomeHistory.memories(historyInputs(extra: homeListAlbums))
    }

    /// Every album the Home lists carry (so a memory knows its cover and counts).
    private var homeListAlbums: [Album] {
        guard let l = homeLists else { return [] }
        var all: [Album] = l.recent
        all += l.frequent
        all += l.newest
        all += l.highest
        all += l.random
        all += l.recentTracks.map(\.album)
        return all
    }

    /// The account's written notes (Android `countNotes`: blank notes don't count).
    var noteCount: Int {
        guard isReal else { return 0 }
        let provider = provider
        return notes.notes.filter { $0.provider == provider && !$0.content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }.count
    }

    /// What the Memories deck reads: the memories, each album's notes (by the
    /// plays' and loaded track lists' song → album), reviews, titles, ratings.
    /// Signed out: the mock deck.
    func memoryDeckInputs() -> MemoryDeck.Inputs {
        guard isReal else { return LaunchConfig.demoContent ? MemoryDeck.mockInputs() : MemoryDeck.Inputs(memories: []) }
        let provider = provider
        let inputs = historyInputs(extra: homeListAlbums)
        var songAlbum: [String: String] = [:]
        for p in inputs.plays { if let a = p.albumId { songAlbum[p.songId] = a } }
        for (id, album) in inputs.details { for t in album.tracks where songAlbum[t.id.rawId] == nil { songAlbum[t.id.rawId] = id } }
        var byAlbum: [String: [SongNote]] = [:]
        for n in inputs.notes { if let a = songAlbum[n.trackId] { byAlbum[a, default: []].append(n) } }
        let memoryStore = AlbumMemoryStore.shared
        let ratings = ratings
        return MemoryDeck.Inputs(
            memories: HomeHistory.memories(inputs), notes: byAlbum,
            review: { id in
                guard let r = memoryStore.album(provider: provider, albumId: id), let text = r.review else { return nil }
                return (text, r.reviewUpdatedAt ?? r.updatedAt)
            },
            title: { id in
                if let t = memoryStore.userTitle(provider: provider, albumId: id) { return (t, true) }
                return memoryStore.memoryTitle(provider: provider, albumId: id).map { ($0, false) }
            },
            rating: { ratings.rating(provider: provider, songId: $0) },
            yoinTitle: { memoryStore.memory(provider: provider, entityId: $0)?.title },
            copy: { memoryStore.memory(provider: provider, entityId: $0)?.copy })
    }

    /// Loads the album details the account's memories need and the app has
    /// no track list for (Android AlbumMemoryCandidateBuilder's `getAlbum`):
    /// a synced album rating or review never resolved here, an album known
    /// only from a play's snapshot. Through the account's cached loader, a
    /// few at a time. True when anything new landed.
    @discardableResult
    func hydrateMemoryAlbums() async -> Bool {
        guard isReal else { return false }
        let gen = generation
        let missing = HomeHistory.writtenAlbumIds(historyInputs(extra: homeListAlbums))
            .filter { albumDetails[$0]?.tracks.isEmpty ?? true }
            .sorted()
        guard !missing.isEmpty else { return false }
        var landed = false
        var pending = missing[...]
        while !pending.isEmpty, gen == generation {
            let batch = pending.prefix(Self.memoryHydrationBatch)
            pending = pending.dropFirst(batch.count)
            let loads = batch.map { id in Task { await loadAlbum(id) } }
            for load in loads { await load.value }
            if batch.contains(where: { !(albumDetails[$0]?.tracks.isEmpty ?? true) }) { landed = true }
        }
        return landed && gen == generation
    }

    /// Album loads Memories runs at once while hydrating.
    static let memoryHydrationBatch = 4

    /// Whose Home this is (Android `homeScopeKey` provider|profile): the
    /// bubble's "seen" mark and the Home layout are kept per account.
    var homeScope: String { isReal ? "\(provider)|\(historyScope)" : "mock" }

    /// What the local stores add to Home (nil with no history, notes or
    /// ratings: the server's shelves alone, as before).
    struct HomeOverlay {
        /// Newest activity first, with when.
        var activities: [HomeHistory.ActivityPick] = []
        var signals: [JBISignal] = []
        var rediscover: [RediscoverEntry] = []
        var marks: [String: AlbumMemoryMark] = [:]
        /// History exists: Rediscover follows its rules (empty = no section).
        var hasHistory = false
        var now: Int64 = 0
    }

    private func homeHistory(_ l: HomeLists) -> HomeOverlay? {
        guard isReal else { return nil }
        let inputs = historyInputs(extra: l.recent + l.frequent + l.newest + l.highest + l.random
            + l.recentTracks.map(\.album))
        let records = history.recentActivities(scope: historyScope, provider: provider, limit: 40)
        if inputs.plays.isEmpty && records.isEmpty && inputs.notes.isEmpty && ratings.ratings.isEmpty
            && inputs.ratedAlbumIds.isEmpty { return nil }
        let memories = HomeHistory.memories(inputs)
        var o = HomeOverlay(hasHistory: !inputs.plays.isEmpty, now: inputs.now)
        o.activities = HomeHistory.activityPicks(records, resolve: inputs.resolve)
        if let rated = HomeHistory.ratedAlbumSignal(memories) { o.signals.append(rated) }
        if let noted = HomeHistory.notedTrackSignal(inputs, placeholder: { [weak self] note in
            self?.placeholderAlbum(note) ?? Album(id: MediaId(provider: note.provider, rawId: note.trackId),
                                                    title: note.title, artist: note.artist, year: 0,
                                                    palette: AppleMusicMapping.palette(nil, key: note.trackId),
                                                    tracks: [])
        }) { o.signals.append(noted) }
        o.marks = HomeHistory.marks(memories)
        // Albums shown elsewhere on the page stay off the shelf: the memory
        // 1×2, and Recently Added's albums added within the last 30 days
        // (Android's window; the server's "newest" list has no window of its own).
        let monthAgo = inputs.now - 30 * HomeHistory.dayMs
        let added = l.newest.filter { a in Self.epochMs(a.created).map { $0 >= monthAgo } ?? false }
        let exclude = Set(o.signals.filter { $0.kind == .ratedAlbum }.map(\.album.id.rawId))
            .union(added.map(\.id.rawId))
        // Songs you rated or noted come back too (Android selectRediscoverSongs): not the Jump Back In
        // noted track, not Recently Added's, and not those of an album Recently Added shows.
        let excludeSongs = Set(l.recentTracks.map(\.track.id.rawId))
            .union(o.signals.compactMap { $0.kind == .notedTrack ? $0.song?.track.id.rawId : nil })
        let songs = HomeHistory.selectRediscoverSongs(HomeHistory.songMemories(inputs), now: inputs.now,
                                                      excludeSongIds: excludeSongs,
                                                      excludeAlbumIds: Set(added.map(\.id.rawId)))
        o.rediscover = HomeHistory.rediscover(memories, now: inputs.now, exclude: exclude, songs: songs,
                                              songAlbum: { [weak self] s in
            let play = inputs.plays.filter { $0.songId == s.songId }.max { $0.startedAt < $1.startedAt }
            return (s.albumId.isEmpty ? nil : inputs.details[s.albumId] ?? inputs.resolve(s.albumId, play))
                ?? self?.songPlaceholderAlbum(s)
                ?? Album(id: MediaId(provider: s.provider, rawId: "song:\(s.songId)"), title: s.title, artist: s.artist,
                         year: 0, palette: AppleMusicMapping.palette(nil, key: s.songId), tracks: [])
        })
        return o
    }

    /// Pure: the shelves from the lists (unit-tested).
    nonisolated static func buildHome(recent: [Album], frequent: [Album], newest: [Album], highest: [Album],
                                      random: [Album], recentTracks: [PlaylistEntry],
                                      history: HomeOverlay? = nil, playlists: [LibraryItem] = []) -> HomeFeed {
        func plays(_ a: Album) -> String {
            guard let n = a.playCount, n > 0 else { return "Recently active" }
            return n == 1 ? "1 play" : "\(n) plays"
        }
        // The activity log first ("2h ago"), then the server's recent albums.
        struct Slot { var album: Album; var entity: ActivityEntity; var entityId: String; var eyebrow: String? }
        var picked: [Slot] = (history?.activities ?? []).map {
            Slot(album: $0.album, entity: $0.entity, entityId: $0.entityId,
                 eyebrow: HomeHistory.timeAgo($0.at, now: history?.now ?? 0))
        }
        for a in recent where picked.count < 4 && !picked.contains(where: { $0.entity == .album && $0.entityId == a.id.rawId }) {
            picked.append(Slot(album: a, entity: .album, entityId: a.id.rawId, eyebrow: nil))
        }
        // The hero only ever shows an album or a playlist: artists keep to the smaller cards.
        if let h = picked.firstIndex(where: { $0.entity != .artist }), h > 0 { picked.insert(picked.remove(at: h), at: 0) }
        // Every slot the widest preset seats: the hero, two rows of cards, a strip.
        // The small cards say only a time, never plays.
        let activities = picked.prefix(Self.activityLimit).enumerated().map { i, p in
            ActivityEntry(id: "activity-\(i)", eyebrow: p.eyebrow ?? ([1, 4].contains(i) ? "" : plays(p.album)),
                          album: p.album, entity: p.entity, entityId: p.entityId)
        }

        let signals = history?.signals ?? []
        // Only an album's own card keeps its cover off the shelf (an artist's or playlist's card shows that artist or playlist).
        var seen = Set(activities.filter { $0.entity == .album }.map(\.album.id))
        for s in signals where s.kind == .ratedAlbum { seen.insert(s.album.id) }
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
        if let history, history.hasHistory {
            // Android's rule once there is history: kept something, 90+ days away.
            rediscover = history.rediscover
        } else {
            // No plays recorded yet: the server's rated albums stand in.
            let rated = highest.filter { $0.rating != nil }
            for a in (rated + random.reversed()) where rediscover.count < 2 && !rediscover.contains(where: { $0.album.id == a.id }) {
                rediscover.append(RediscoverEntry(album: a, reason: a.rating != nil ? "Rated" : "From your library",
                                                  away: a.genre ?? (a.year > 0 ? String(a.year) : ""),
                                                  score: a.rating, footnoteDate: Self.yearMonth(a.created),
                                                  footnotePlays: "\(a.playCount ?? 0)"))
            }
        }
        let shownInActivities = Set(activities.filter { $0.entity == .album }.map(\.album.id))
        return HomeFeed(layout: .server, activities: activities, jumpBackIn: jbi, recentTracks: recentTracks,
                        recentAlbums: recentAlbums, rediscover: rediscover, signals: signals,
                        memoryMarks: history?.marks ?? [:],
                        recentlyPlayed: recent.filter { !shownInActivities.contains($0.id) },
                        playlists: Array(playlists.prefix(homePlaylistLimit)))
    }

    /// Activities entries kept: the hero, two rows of cards and a strip (the XL preset).
    nonisolated static let activityLimit = 6

    /// Your Playlists' length (Android HOME_PLAYLIST_LIMIT).
    nonisolated static let homePlaylistLimit = 20

    /// An ISO-8601 date (the server's `created`) in epoch ms.
    ///
    /// Read by hand: Library sorts by it, and a fresh ISO8601DateFormatter
    /// per album (two on a miss) cost most of a sort's time on the main
    /// thread. Anything that isn't `yyyy-MM-ddTHH:mm:ss[.fff](Z|±HH:mm)`
    /// goes to a shared formatter.
    nonisolated static func epochMs(_ iso: String?) -> Int64? {
        guard let iso else { return nil }
        if let ms = fastEpochMs(iso) { return ms }
        let date = isoFractional.date(from: iso) ?? isoPlain.date(from: iso)
        return date.map { Int64($0.timeIntervalSince1970 * 1000) }
    }

    // ISO8601DateFormatter is safe to share across threads once configured.
    nonisolated(unsafe) private static let isoFractional: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()
    nonisolated(unsafe) private static let isoPlain = ISO8601DateFormatter()

    nonisolated static func fastEpochMs(_ iso: String) -> Int64? {
        let b = Array(iso.utf8)
        func num(_ from: Int, _ count: Int) -> Int? {
            guard from + count <= b.count else { return nil }
            var n = 0
            for c in b[from..<from + count] {
                guard c >= 48, c <= 57 else { return nil }
                n = n * 10 + Int(c - 48)
            }
            return n
        }
        func at(_ i: Int, _ c: Character) -> Bool { i < b.count && b[i] == c.asciiValue }
        guard let y = num(0, 4), at(4, "-"), let mo = num(5, 2), at(7, "-"), let d = num(8, 2),
              at(10, "T") || at(10, "t") || at(10, " "),
              let h = num(11, 2), at(13, ":"), let mi = num(14, 2), at(16, ":"), let sec = num(17, 2),
              (1...12).contains(mo), (1...31).contains(d), h < 24, mi < 60, sec < 61 else { return nil }
        var i = 19
        var ms = 0
        if at(i, ".") {
            i += 1
            var digits = 0
            while i < b.count, b[i] >= 48, b[i] <= 57 {
                if digits < 3 { ms = ms * 10 + Int(b[i] - 48) }
                digits += 1; i += 1
            }
            guard digits > 0 else { return nil }
            if digits < 3 { for _ in digits..<3 { ms *= 10 } }
        }
        var offset = 0
        if at(i, "Z") || at(i, "z") {
            i += 1
        } else if at(i, "+") || at(i, "-") {
            let sign = at(i, "-") ? -1 : 1
            guard let oh = num(i + 1, 2) else { return nil }
            var j = i + 3
            if at(j, ":") { j += 1 }
            guard let om = num(j, 2) else { return nil }
            offset = sign * (oh * 3600 + om * 60)
            i = j + 2
        } else {
            return nil
        }
        guard i == b.count else { return nil }
        // Days from the civil date (Howard Hinnant's days_from_civil).
        let yy = mo <= 2 ? y - 1 : y
        let era = (yy >= 0 ? yy : yy - 399) / 400
        let yoe = yy - era * 400
        let doy = (153 * (mo + (mo > 2 ? -3 : 9)) + 2) / 5 + d - 1
        let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        let days = era * 146_097 + doe - 719_468
        let seconds = Int64(days) * 86400 + Int64(h * 3600 + mi * 60 + sec) - Int64(offset)
        return seconds * 1000 + Int64(ms)
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

    /// Runs `body` once per key and generation (again after a failure or with
    /// `force`). A caller that comes while it runs waits for it instead of
    /// returning empty-handed (Android's single-flight).
    private func once(_ key: String, force: Bool = false, _ body: @escaping @MainActor () async throws -> Void) async {
        guard isReal else { return }
        if let running = inFlight[key] {
            await running.value
            guard force else { return }
        }
        let p = phase(key)
        guard force || p == .idle || p == .failed else { return }
        let gen = generation
        phases[key] = .loading
        let task = Task { @MainActor in
            do {
                // The body (and any task it starts) reads its generation through `ensureLive`.
                try await Self.$loadGeneration.withValue(gen) { try await body() }
                if gen == self.generation {
                    self.phases[key] = .loaded
                    self.failures[key] = nil
                }
            } catch {
                let message = self.message(for: error)
                self.log.error("\(key, privacy: .public) failed: \(message, privacy: .public)")
                if QAProbe.diag { print("[diag] \(key) failed: \(message) — \(error)") }
                if gen == self.generation {
                    self.phases[key] = .failed
                    self.failures[key] = message
                }
            }
        }
        inFlight[key] = task
        await task.value
        if inFlight[key] == task { inFlight[key] = nil }
    }

    /// The generation the running load began in (set by `once` for its body and the tasks it starts).
    @TaskLocal static var loadGeneration: Int?

    /// Throws when the running load began under another source / account: a load publishes nothing after
    /// a reset (Codex C01). Every fetch calls it after its last await, before assigning what it read.
    func ensureLive() throws {
        if let gen = Self.loadGeneration, gen != generation { throw CancellationError() }
        try Task.checkCancellation()
    }

    // MARK: Disk cache

    @ObservationIgnored private var inFlight: [String: Task<Void, Never>] = [:]
    /// Bumps on every edit that drops a disk entry; a load writes back only
    /// what it fetched after the last edit to its entry (DetailDiskCache).
    @ObservationIgnored private var diskEpoch = 0

    /// Album / artist pages hardly change: a stored copy under a week old
    /// is served without the network, refreshed behind it past two hours
    /// (Android `detailDiskFreshMs`, `detailRevalidateAfterMs`). Playlists
    /// and the lists always ask again; their copy paints first and stands
    /// in offline.
    static let detailFresh: TimeInterval = 7 * 24 * 60 * 60
    static let revalidateAfter: TimeInterval = 2 * 60 * 60

    /// How this source's models go to disk; nil for the mock library.
    private var codec: DetailCacheCodec? {
        // Spotify keeps its artwork URLs as they are, as Apple Music does.
        if apple != nil || spotify != nil { return .appleMusic }
        return mapping.map(DetailCacheCodec.subsonic)
    }

    /// [once] with the disk in front (Android `loadCachedDetail`): with
    /// nothing in memory the stored copy paints first; then [fetch] runs
    /// unless the copy is younger than [fresh]; a failed fetch over a
    /// stored copy keeps the copy (offline). [fetch] returns what to store.
    private func cachedLoad<D: Codable & Sendable>(
        _ key: String, kind: DetailDiskCache.Kind, id: String, force: Bool, fresh: TimeInterval = 0,
        inMemory: @escaping @MainActor () -> Bool,
        restore: @escaping @MainActor (D, DetailCacheCodec) -> Void,
        fetch: @escaping @MainActor () async throws -> D?
    ) async {
        await once(key, force: force) { [self] in
            let gen = generation, epoch = diskEpoch, scope = historyScope
            var storedAt: Date?
            if !inMemory(), let codec, let hit = await disk.read(D.self, scope: scope, kind: kind, id: id) {
                guard gen == generation else { return }
                restore(hit.value, codec)
                storedAt = hit.cachedAt
                phases[key] = .loaded
            }
            @MainActor func fetchAndStore() async throws {
                let value = try await fetch()
                guard gen == generation else { return }
                persist(value, kind: kind, id: id, epoch: epoch, scope: scope)
            }
            if !force, let storedAt, fresh > 0 {
                let age = Date().timeIntervalSince(storedAt)
                if age <= fresh {
                    if age > Self.revalidateAfter { Task { try? await fetchAndStore() } }
                    return
                }
            }
            do {
                try await fetchAndStore()
            } catch {
                guard storedAt != nil else { throw error }
                log.info("\(key, privacy: .public): offline, showing the stored copy")
            }
        }
    }

    /// Writes [value] behind the caller (encode and file write off the main actor).
    private func persist<D: Codable & Sendable>(_ value: D?, kind: DetailDiskCache.Kind, id: String, epoch: Int,
                                                scope: String? = nil) {
        guard let value, isReal else { return }
        let scope = scope ?? historyScope
        let disk = disk
        Task.detached(priority: .utility) { await disk.write(value, scope: scope, kind: kind, id: id, epoch: epoch) }
    }

    /// An album fetched on the way to something else (Home's grid, an
    /// artist's releases): kept in memory and on disk like an opened one.
    private func keepAlbum(_ album: Album, epoch: Int) {
        albumDetails[album.id.rawId] = album
        persist(codec?.dto(album), kind: .album, id: album.id.rawId, epoch: epoch)
    }

    /// An edit changed what an entry holds: its disk copy goes, and loads
    /// begun before it can't write the old one back.
    private func dropFromDisk(_ kind: DetailDiskCache.Kind, _ id: String) {
        diskEpoch += 1
        let epoch = diskEpoch, scope = historyScope, disk = disk
        Task.detached(priority: .utility) { await disk.remove(scope: scope, kind: kind, id: id, epoch: epoch) }
    }

    /// A heart or rating on [trackId] changed: the stored pages that show it are written again with
    /// the change (Codex N20: dropping them left a downloaded album with no track list offline).
    private func dropAlbums(containing trackId: String, edit: (inout Track) -> Void = { _ in }) {
        for (id, album) in albumDetails where album.tracks.contains(where: { $0.id.rawId == trackId }) {
            var updated = album
            for i in updated.tracks.indices where updated.tracks[i].id.rawId == trackId { edit(&updated.tracks[i]) }
            albumDetails[id] = updated
            dropFromDisk(.album, id)
            persist(codec?.dto(updated), kind: .album, id: id, epoch: diskEpoch)
        }
    }

    /// Where the next page of Subsonic albums starts after a read that failed part-way (nil: from 0).
    @ObservationIgnored private var albumsNextOffset: Int32?
    static let albumPage: Int32 = 500

    /// Albums (A12): the first page shows at once, every page after it as it comes, to the end of the
    /// library (no ceiling; Android LibraryViewModel publishes batches too). A read that fails part-way
    /// keeps the albums so far and the retry continues from the next page.
    func loadAlbums(force: Bool = false) async {
        await cachedLoad("albums", kind: .list, id: "albums", force: force, inMemory: { !self.albums.isEmpty },
                         restore: { (d: [DetailCacheDTO.Album], c) in self.albums = d.map(c.album) }) { [self] in
            if let apple {
                // Nothing on screen yet: each page shows as it comes (a library of 1,600
                // albums is 17 pages, a minute on a phone).
                let progressive = albums.isEmpty
                let all = try await apple.libraryAlbums(onPage: { [self] partial in
                    if progressive, (try? ensureLive()) != nil { albums = partial }
                })
                try ensureLive()
                albums = all
                return codec?.dtoAlbums(albums)
            }
            if let spotify {
                let progressive = albums.isEmpty
                let all = try await spotify.libraryAlbums(onPage: { [self] partial in
                    if progressive, (try? ensureLive()) != nil { albums = partial }
                })
                try ensureLive()
                albums = all
                return codec?.dtoAlbums(albums)
            }
            guard let client, let mapping else { return nil }
            // Nothing on screen yet: each page shows as it comes. Over a stored copy: the whole list at the end.
            let progressive = albums.isEmpty
            var offset: Int32 = 0
            var all: [Album] = []
            if !force, let resume = albumsNextOffset, Int(resume) == albums.count {
                offset = resume
                all = albums
            }
            while true {
                let page: [AlbumInfo]
                do {
                    page = try await client.getAlbumList2(type: .alphabeticalByName, size: Self.albumPage, offset: offset)
                } catch {
                    try ensureLive()
                    if offset > 0 {
                        albums = all
                        albumsNextOffset = offset
                    }
                    throw error
                }
                try ensureLive()
                all += page.map(mapping.album)
                offset += Int32(page.count)
                if progressive { albums = all }
                if page.count < Int(Self.albumPage) { break }
            }
            albums = all
            albumsNextOffset = nil
            return codec?.dtoAlbums(all)
        }
    }

    func loadArtists(force: Bool = false) async {
        await cachedLoad("artists", kind: .list, id: "artists", force: force, inMemory: { !self.artists.isEmpty },
                         restore: { (d: [DetailCacheDTO.Item], c) in self.artists = d.map(c.item) }) { [self] in
            if let apple {
                let progressive = artists.isEmpty
                let read = try await apple.libraryArtists(onPage: { [self] partial in
                    if progressive, (try? ensureLive()) != nil { artists = partial }
                })
                try ensureLive()
                artists = read
            } else if let spotify {
                let progressive = artists.isEmpty
                let read = try await spotify.libraryArtists(onPage: { [self] partial in
                    if progressive, (try? ensureLive()) != nil { artists = partial }
                })
                try ensureLive()
                artists = read
            } else {
                guard let client, let mapping else { return nil }
                let read = try await client.getArtists().flatMap { $0.artists.map(mapping.artistItem) }
                try ensureLive()
                artists = read
            }
            return codec.map { c in artists.map(c.dto) }
        }
    }

    func loadPlaylists(force: Bool = false) async {
        await cachedLoad("playlists", kind: .list, id: "playlists", force: force, inMemory: { !self.playlists.isEmpty },
                         restore: { (d: [DetailCacheDTO.Item], c) in self.playlists = d.map(c.item) }) { [self] in
            if let apple {
                let read = try await apple.libraryPlaylists()
                try ensureLive()
                playlists = read
            } else if let spotify {
                let read = try await spotify.libraryPlaylists()
                try ensureLive()
                playlists = read
            } else {
                guard let client, let mapping else { return nil }
                let read = try await client.getPlaylists().map(mapping.playlistItem)
                try ensureLive()
                playlists = read
            }
            return codec.map { c in playlists.map(c.dto) }
        }
    }

    /// What follows Songs' rows (Android LibrarySongsMore): nothing (the
    /// list is whole), more to read, a page being read, or a failed read.
    enum SongsMore: Equatable { case none, available, loading, failed }

    /// Songs' foot. Only Subsonic reads Songs a page at a time.
    private(set) var songsMore: SongsMore = .none
    /// Subsonic's Songs: its albums' songs, newest album first (Android
    /// `songsFromNewestAlbums`). Apple Music lists library songs of its own.
    @ObservationIgnored private var songsPager: NewestAlbumSongsPager?

    /// Songs is a random sample (Android `canReshuffleSongs`): no source
    /// here does that any more; the mock library still shows its mix.
    var songsAreRandomMix: Bool { !isReal }

    func loadSongs(force: Bool = false) async {
        await cachedLoad("songs", kind: .list, id: "songs", force: force, inMemory: { !self.songs.isEmpty },
                         restore: { (d: [DetailCacheDTO.Entry], c) in self.songs = d.map(c.entry) }) { [self] in
            if let apple {
                let read = try await apple.librarySongs()
                try ensureLive()
                songs = read
                songsMore = .none
            } else if let spotify {
                // Liked Songs (Android: Spotify's Songs is the saved tracks), the first read then more as the list ends.
                let read = try await spotify.librarySongs(offset: 0)
                try ensureLive()
                songs = read.songs
                spotifySongsNext = read.next
                songsMore = read.next == nil ? .none : .available
            } else {
                guard let client, let mapping else { return nil }
                // A refresh starts again from the newest album.
                let pager = NewestAlbumSongsPager(
                    loadAlbums: { offset, size in
                        try await client.getAlbumList2(type: .newest, size: Int32(size), offset: Int32(offset))
                            .map(mapping.album)
                    },
                    loadAlbum: { [weak self, gen = generation] album in
                        if let have = self?.albumDetails[album.id.rawId], !have.tracks.isEmpty { return have }
                        let read = try await client.getAlbum(id: album.id.rawId).map(mapping.album)
                        // Another source or account since (C01): nothing it read lands.
                        guard let self, gen == self.generation else { throw CancellationError() }
                        try self.ensureLive()
                        guard let full = read else { return nil }
                        self.albumDetails[full.id.rawId] = full
                        return full
                    })
                songsPager = pager
                let first = try await pager.next()
                guard songsPager === pager else { return nil }
                songs = first
                songsMore = pager.reachedEnd ? .none : .available
            }
            return codec.map { c in songs.map(c.dto) }
        }
    }

    /// Songs' next page (Android `loadMoreSongs`): as the list nears its
    /// end, or from the foot's retry after a failed read. One at a time.
    func loadMoreSongs() async {
        if spotify != nil { return await loadMoreSpotifySongs() }
        guard let pager = songsPager, songsMore == .available || songsMore == .failed else { return }
        let gen = generation
        songsMore = .loading
        do {
            let more = try await pager.next()
            guard gen == generation, songsPager === pager else { return }
            songs += more
            songsMore = pager.reachedEnd ? .none : .available
            persist(codec.map { c in songs.map(c.dto) }, kind: .list, id: "songs", epoch: diskEpoch)
        } catch {
            guard gen == generation, songsPager === pager else { return }
            log.error("songs page failed: \(ServerSession.message(for: error), privacy: .public)")
            // The rows stay; the foot's retry reads the same page again.
            songsMore = .failed
        }
    }

    /// Spotify's Liked Songs beyond the first read (SpotifySource+Search librarySongs(offset:)).
    private func loadMoreSpotifySongs() async {
        guard let spotify, let from = spotifySongsNext, songsMore == .available || songsMore == .failed else { return }
        let gen = generation
        songsMore = .loading
        do {
            let more = try await spotify.librarySongs(offset: from)
            guard gen == generation, self.spotify === spotify else { return }
            let have = Set(songs.map(\.track.id))
            songs += more.songs.filter { !have.contains($0.track.id) }
            spotifySongsNext = more.next
            songsMore = more.next == nil ? .none : .available
            persist(codec.map { c in songs.map(c.dto) }, kind: .list, id: "songs", epoch: diskEpoch)
        } catch {
            guard gen == generation, self.spotify === spotify else { return }
            log.error("liked songs page failed: \(SpotifySource.message(for: error), privacy: .public)")
            songsMore = .failed
        }
    }

    /// A heart changed since the starred list was read.
    @ObservationIgnored private var favoritesStale = false

    func loadFavorites(force: Bool = false) async {
        let force = force || favoritesStale
        favoritesStale = false
        // The starred artists and albums paint from disk too (Codex F04: offline, only the songs came back).
        if favoriteArtists.isEmpty, favoriteAlbums.isEmpty, let codec {
            let scope = historyScope, gen = generation
            let artists = await disk.read([DetailCacheDTO.Item].self, scope: scope, kind: .list, id: "favoriteArtists")
            let albums = await disk.read([DetailCacheDTO.Album].self, scope: scope, kind: .list, id: "favoriteAlbums")
            if gen == generation, favoriteArtists.isEmpty, favoriteAlbums.isEmpty {
                favoriteArtists = artists?.value.map(codec.item) ?? []
                favoriteAlbums = albums?.value.map(codec.album) ?? []
            }
        }
        await cachedLoad("favorites", kind: .list, id: "favorites", force: force, inMemory: { !self.favorites.isEmpty },
                         restore: { (d: [DetailCacheDTO.Entry], c) in self.favorites = d.map(c.entry) }) { [self] in
            // Apple Music has no favorites read (loved songs aren't listable,
            // library membership isn't a heart: Android rule): none.
            if apple != nil { return nil }
            if let spotify {
                // The heart is Liked Songs: Favorites lists them.
                let read = try await spotify.librarySongs()
                try ensureLive()
                favoriteArtists = []
                favoriteAlbums = []
                favorites = read
                return codec.map { c in favorites.map(c.dto) }
            }
            guard let client, let mapping else { return nil }
            let starred = try await client.getStarred2()
            try ensureLive()
            // Android FavoritesTabContent: starred artists, albums, then songs.
            favoriteArtists = starred.artists.map(mapping.artistItem)
            favoriteAlbums = starred.albums.map(mapping.album)
            favorites = starred.songs.enumerated().map { mapping.entry($1, index: $0) }
            if let c = codec {
                persist(favoriteArtists.map(c.dto), kind: .list, id: "favoriteArtists", epoch: diskEpoch)
                persist(favoriteAlbums.map(c.dto), kind: .list, id: "favoriteAlbums", epoch: diskEpoch)
            }
            return codec.map { c in favorites.map(c.dto) }
        }
    }

    /// When each album, artist and playlist was last opened or played in
    /// Yoin (Android LibraryRecents), keyed "album:<id>" / "artist:<id>" /
    /// "playlist:<id>": Library's Recents order and its rail.
    func libraryRecents() -> [String: Int64] {
        guard isReal else { return [:] }
        let scope = historyScope, provider = provider
        var out: [String: Int64] = [:]
        func note(_ key: String, _ at: Int64) { if at > out[key] ?? .min { out[key] = at } }
        for a in history.activities where a.scope == scope && a.provider == provider {
            switch a.entity {
            case .album: note("album:\(a.entityId)", a.timestamp)
            case .artist: note("artist:\(a.entityId)", a.timestamp)
            case .playlist: note("playlist:\(a.entityId)", a.timestamp)
            case .song:
                if let album = a.albumId { note("album:\(album)", a.timestamp) }
                if let artist = a.artistId { note("artist:\(artist)", a.timestamp) }
            }
        }
        for play in history.plays(scope: scope, provider: provider) {
            if let album = play.albumId { note("album:\(album)", play.startedAt) }
            if let artist = play.artistId { note("artist:\(artist)", play.startedAt) }
        }
        return out
    }

    /// The signed-in user (Subsonic), whose playlists are "By You".
    var username: String? { client != nil ? session.account?.username : nil }

    /// Library's All grid: playlists, artists, then albums.
    var libraryItems: [LibraryItem] {
        if apple != nil || spotify != nil { return playlists + artists + albums.map(AppleMusicMapping.albumItem) }
        guard let mapping else { return [] }
        return playlists + artists + albums.map(mapping.albumItem)
    }

    // MARK: Details

    func album(_ id: String) -> Album? { albumDetails[id] }

    func loadAlbum(_ id: String, force: Bool = false) async {
        await cachedLoad("album:\(id)", kind: .album, id: id, force: force, fresh: Self.detailFresh,
                         inMemory: { self.albumDetails[id] != nil },
                         restore: { (d: DetailCacheDTO.Album, c) in self.albumDetails[id] = c.album(d) }) { [self] in
            var album: Album?
            if let apple {
                album = try await apple.album(id)
            } else if let spotify {
                album = try await spotify.album(id)
            } else if let client, let mapping {
                album = try await client.getAlbum(id: id).map(mapping.album)
            }
            try ensureLive()
            guard let album else { return nil }
            albumDetails[id] = album
            return codec?.dto(album)
        }
    }

    func artistPage(_ id: String) -> ArtistPage? { artistPages[id] }

    func loadArtist(_ id: String, force: Bool = false) async {
        await cachedLoad("artist:\(id)", kind: .artist, id: id, force: force, fresh: Self.detailFresh,
                         inMemory: { self.artistPages[id] != nil },
                         restore: { (d: DetailCacheDTO.ArtistPage, c) in self.artistPages[id] = c.artistPage(d) }) { [self] in
            if let apple {
                let read = try await apple.artist(id)
                try ensureLive()
                guard let page = read else { return nil }
                artistPages[id] = page
                return codec?.dto(page)
            }
            if let spotify {
                let read = try await spotify.artist(id)
                try ensureLive()
                guard let page = read else { return nil }
                artistPages[id] = page
                return codec?.dto(page)
            }
            guard let client, let mapping else { return nil }
            // The load's own generation (captured before any await), not whatever is current now.
            let gen = Self.loadGeneration ?? generation, epoch = diskEpoch
            let read = try await client.getArtist(id: id)
            try ensureLive()
            guard let info = read else { return nil }
            artistPages[id] = mapping.artistPage(info, loadedAlbums: info.albums.compactMap { albumDetails[$0.id] })
            // Most Played needs the songs: fetch the releases (up to 12).
            var loaded: [Album] = []
            for summary in info.albums.prefix(12) {
                if let have = albumDetails[summary.id] { loaded.append(have); continue }
                if let full = try? await client.getAlbum(id: summary.id) {
                    let album = mapping.album(full)
                    guard gen == generation else { return nil }
                    keepAlbum(album, epoch: epoch)
                    loaded.append(album)
                }
            }
            try ensureLive()
            let page = mapping.artistPage(info, loadedAlbums: loaded)
            artistPages[id] = page
            return codec?.dto(page)
        }
    }

    func playlist(_ id: String) -> PlaylistInfo? { playlistDetails[id] }

    func loadPlaylist(_ id: String, force: Bool = false) async {
        await cachedLoad("playlist:\(id)", kind: .playlist, id: id, force: force,
                         inMemory: { self.playlistDetails[id] != nil },
                         restore: { (d: DetailCacheDTO.Playlist, c) in self.playlistDetails[id] = c.playlist(d) }) { [self] in
            var detail: PlaylistInfo?
            if let apple {
                if var d = try await apple.playlist(id) {
                    // The library row's cover when the playlist answers without one.
                    if d.palette.cover == nil, let row = playlistSummary(id) { d.palette = row.palette }
                    detail = d
                }
            } else if let spotify {
                if var d = try await spotify.playlist(id) {
                    if d.palette.cover == nil, let row = playlistSummary(id) { d.palette = row.palette }
                    detail = d
                }
            } else if let client, let mapping {
                detail = try await client.getPlaylist(id: id).map(mapping.playlist)
            }
            try ensureLive()
            guard let detail else { return nil }
            playlistDetails[id] = detail
            return codec?.dto(detail)
        }
    }

    /// The playlist's library row, for a page whose detail is still loading.
    func playlistSummary(_ id: String) -> LibraryItem? { playlists.first { $0.id.rawId == id } }

    /// Playlist writes (Data/PlaylistActions.swift) edit the Library rows and
    /// the cached pages in place, ahead of the server.
    /// The edit lands on disk too: changed pages and the list are dropped
    /// (so no load in flight writes the old ones back), then stored as edited.
    func patchPlaylists(_ edit: (inout [LibraryItem], inout [String: PlaylistInfo]) -> Void) {
        let before = playlistDetails, rowsBefore = playlists
        edit(&playlists, &playlistDetails)
        guard let codec else { return }
        for id in Set(before.keys).union(playlistDetails.keys) where before[id] != playlistDetails[id] {
            dropFromDisk(.playlist, id)
            persist(playlistDetails[id].map(codec.dto), kind: .playlist, id: id, epoch: diskEpoch)
        }
        if rowsBefore != playlists {
            dropFromDisk(.list, "playlists")
            persist(playlists.map(codec.dto), kind: .list, id: "playlists", epoch: diskEpoch)
        }
    }

    // MARK: Search

    /// Where a search looks (Android LibrarySearchScope): the saved library,
    /// or the provider's whole catalog (Apple Music only; Subsonic's
    /// search3 is its library).
    enum SearchScope: String, Sendable { case library, catalog }

    /// The query and scope `search` / `searchPhase` answer: results and a
    /// failure belong to one request, never shown under another (Android
    /// drops a reply whose query or scope moved on).
    struct SearchRequest: Equatable, Sendable {
        var query: String
        var scope: SearchScope
    }

    var searchRequest: SearchRequest?

    /// Whether this source searches a catalog apart from its library.
    var searchesCatalog: Bool { apple != nil || spotify != nil }

    func runSearch(_ query: String, scope: SearchScope = .library) async {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard isReal, !q.isEmpty else { search = nil; searchRequest = nil; searchPhase = .idle; return }
        let request = SearchRequest(query: q, scope: searchesCatalog ? scope : .library)
        searchRequest = request
        searchPhase = .loading
        searchPaging = [:]
        do {
            let hits: SearchHits
            if let apple {
                // Android searchWithScope: the catalog, or the saved library.
                let r = request.scope == .catalog ? try await apple.search(q) : try await apple.searchLibrary(q)
                hits = SearchHits(query: q, scope: request.scope, artists: r.artists, albums: r.albums,
                                  songs: r.songs, playlists: r.playlists)
            } else if let spotify {
                // Android searchWithScope: the catalog, or what the account has saved.
                let r = request.scope == .catalog ? try await spotify.search(q) : try await spotifyLibrarySearch(q, spotify)
                hits = SearchHits(query: q, scope: request.scope, artists: r.artists, albums: r.albums,
                                  songs: r.songs, playlists: r.playlists, more: r.more,
                                  offsets: r.counts)
            } else if let client, let mapping {
                let r = try await client.search3(query: q, artistCount: 20, albumCount: 30, songCount: 40)
                // Android SubsonicMusicSource.search: search3 alone, which has no
                // playlists (and so no Playlists chip, searchFiltersFor), so All
                // never lists a Playlists section for a server either.
                hits = SearchHits(query: q, scope: .library,
                                  artists: r.artists.map(mapping.artistItem),
                                  albums: r.albums.map(mapping.album),
                                  songs: r.songs.enumerated().map { mapping.entry($1, index: $0) },
                                  playlists: [])
            } else { return }
            guard !Task.isCancelled, searchRequest == request else { return }
            search = hits
            searchPhase = .loaded
        } catch {
            guard !Task.isCancelled, searchRequest == request else { return }
            let message = message(for: error)
            log.error("search failed: \(message, privacy: .public)")
            // A failure is not "no results": the old request's rows go too.
            search = nil
            searchPhase = .failed
        }
    }

    // MARK: Hearts and ratings

    func isStarred(_ track: Track) -> Bool {
        hearts.isFavorite(provider: track.id.provider, songId: track.id.rawId, known: track.starred)
    }

    func isStarred(artist page: ArtistPage) -> Bool { starredOverride["artist:\(page.id.rawId)"] ?? page.starred }

    /// Optimistic heart; reverts when the server refuses (Android rule).
    func toggleStar(_ track: Track) {
        // Apple Music has no heart to write (library membership is Now Playing's add button).
        guard track.id.provider != AppleMusicMapping.provider else { return }
        // The shared heart: Now Playing and the song sheet see it at once
        // (its onWrite drops the stored pages that show it).
        hearts.toggle(provider: track.id.provider, songId: track.id.rawId, known: track.starred,
                      client: client, apple: nil)
    }

    func toggleStar(artist page: ArtistPage) {
        guard page.id.provider != AppleMusicMapping.provider else { return }
        let id = page.id.rawId
        let now = !isStarred(artist: page)
        if let spotify, page.id.provider == SpotifyMapping.provider {
            // Follow on Spotify (Codex N13), the star reverting when Spotify refuses.
            let key = "artist:\(id)", before = starredOverride[key]
            starredOverride[key] = now
            Task {
                do { try await spotify.setFollowed(now, artist: id) } catch {
                    log.error("follow failed: \(SpotifySource.message(for: error), privacy: .public)")
                    withAnimation(Yoin.effects) { self.starredOverride[key] = before ?? !now }
                }
            }
            dropFromDisk(.artist, id)
            return
        }
        write(key: "artist:\(id)", on: now) { client, on in
            on ? try await client.star(id: nil, albumId: nil, artistId: id)
                : try await client.unstar(id: nil, albumId: nil, artistId: id)
        }
        dropFromDisk(.artist, id)
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
        guard let client, track.id.provider == MediaRef.companion.PROVIDER_SUBSONIC else { return false }
        do {
            try await client.setRating(id: track.id.rawId, rating: Int32(SubsonicMapping.serverRating(rating)))
            dropAlbums(containing: track.id.rawId) { $0.rating = rating }
            return true
        } catch {
            log.error("rating failed: \(ServerSession.message(for: error), privacy: .public)")
            return false
        }
    }

    // MARK: Playback

    /// A queue entry for the engine (stream + cover URLs from the client).
    func playable(_ e: PlaylistEntry) -> PlayableTrack? {
        if apple != nil, e.track.id.provider == AppleMusicMapping.provider {
            return PlayableTrack(id: e.track.id.rawId, provider: AppleMusicMapping.provider, title: e.track.title,
                                 artist: e.track.artist ?? e.album.artist, album: e.album.title,
                                 albumId: e.album.id.rawId.hasPrefix("song:") ? nil : e.album.id.rawId,
                                 durationSec: Double(e.track.durationSec),
                                 streamURL: AppleMusicMapping.streamURL(rawId: e.track.id.rawId),
                                 coverURL: e.album.coverURL)
        }
        if spotify != nil, e.track.id.provider == SpotifyMapping.provider {
            let albumId = e.album.id.rawId.hasPrefix("song:") ? nil : e.album.id.rawId
            var t = PlayableTrack(id: e.track.id.rawId, provider: SpotifyMapping.provider, title: e.track.title,
                                  artist: e.track.artist ?? e.album.artist, album: e.album.title, albumId: albumId,
                                  durationSec: Double(e.track.durationSec),
                                  streamURL: SpotifyAudioOutput.streamURL(trackId: e.track.id.rawId),
                                  coverURL: e.album.coverURL)
            // Liked when known; otherwise unknown, so Now Playing asks Spotify (Codex N11): "not in
            // the Liked Songs read so far" isn't "not liked".
            let known = e.track.starred || hearts.hasReport(provider: SpotifyMapping.provider, songId: e.track.id.rawId)
            t.starred = known ? isStarred(e.track) : nil
            return t
        }
        guard let client, e.track.id.provider == MediaRef.companion.PROVIDER_SUBSONIC else { return nil }
        var t = PlayableTrack(id: e.track.id.rawId, provider: e.track.id.provider, title: e.track.title,
                              artist: e.track.artist ?? e.album.artist, album: e.album.title,
                              albumId: e.album.id.rawId.hasPrefix("song:") ? nil : e.album.id.rawId,
                              durationSec: Double(e.track.durationSec),
                              streamURL: URL(string: client.streamUrl(id: e.track.id.rawId, maxBitRate: nil,
                                                                      format: nil))!,
                              coverURL: e.album.coverURL)
        t.starred = isStarred(e.track)
        t.userRating = e.track.rating.map { SubsonicMapping.serverRating($0) }
        t.sourceKbps = e.track.bitRateKbps
        return t
    }

    /// The album with its tracks (loads them when only the summary is known).
    func fullAlbum(_ album: Album) async -> Album {
        if !album.tracks.isEmpty || !isReal { return album }
        await loadAlbum(album.id.rawId)
        return albumDetails[album.id.rawId] ?? album
    }

    func scrobble(_ track: PlayableTrack, submission: Bool) {
        // Apple Music counts its own plays (ApplicationMusicPlayer reports them).
        guard let client, track.provider == MediaRef.companion.PROVIDER_SUBSONIC else { return }
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        guard submission else {
            scrobbleStarts[track.id] = nowMs
            // Now playing is live or nothing: a failure (offline) is dropped, never queued (Android).
            Task {
                do { try await client.scrobble(id: track.id, submission: false, timeMs: nil) } catch {
                    log.info("now playing not sent: \(ServerSession.message(for: error), privacy: .public)")
                }
            }
            return
        }
        guard let scope = session.scope else { return }
        // A play is kept until the server has it (Android PendingScrobbles), timed when it started.
        scrobbles.add(PendingScrobble(scope: scope, songId: track.id,
                                      timeMs: scrobbleStarts.removeValue(forKey: track.id) ?? nowMs))
        flushScrobbles()
    }

    /// Sends the account's waiting plays, oldest first, one at a time: each leaves the outbox once the
    /// server took it, or refused it for good (a missing song); the network or the server being away
    /// stops the run, and the next play, the next launch or the network coming back tries again.
    func flushScrobbles() {
        guard !flushingScrobbles, let client, let scope = session.scope, scrobbles.next(for: scope) != nil else { return }
        flushingScrobbles = true
        Task {
            defer { flushingScrobbles = false }
            while let play = scrobbles.next(for: scope) {
                do {
                    try await client.scrobble(id: play.songId, submission: true, timeMs: KotlinLong(value: play.timeMs))
                    scrobbles.remove(play)
                } catch {
                    let message = ServerSession.message(for: error)
                    guard Self.scrobbleRefused(error) else {
                        log.info("plays kept for later: \(message, privacy: .public)")
                        return
                    }
                    log.info("play dropped: \(message, privacy: .public)")
                    scrobbles.remove(play)
                }
            }
        }
    }

    /// The server answered and said no to this play (an API error that isn't about the sign-in):
    /// sending it again won't change that. Unreachable, HTTP or a bad sign-in keep it.
    nonisolated static func scrobbleRefused(_ error: Error) -> Bool {
        guard let k = (error as NSError).kotlinException as? SubsonicException else { return false }
        return k.code >= 0 && !k.isAuthFailure
    }

    // MARK: Local history

    /// Whose history this is (Android's profile id): the Subsonic server and
    /// user, or Apple Music. Paired with `provider` on every row.
    var historyScope: String {
        if apple != nil { return AppleMusicMapping.provider }
        if spotify != nil, let scope = spotifyAccount?.scope { return scope }
        // The account's scope (`Profile.subsonicScope`), steady across an edit of how its address is written.
        return session.scope ?? ""
    }

    /// The song the last play row was written for (Android
    /// `lastRecordedTrackId`: the same song again right after is not a new play).
    @ObservationIgnored private var lastRecordedSong: String?
    @ObservationIgnored private var openPlayId: String?

    /// The engine's scrobble hook: a song that starts (`submission == false`)
    /// is a play row; one heard long enough marks it completed.
    func recordHistory(_ t: PlayableTrack, submission: Bool, positionSec: Double) {
        guard isReal, t.provider == provider else { return }
        let key = "\(t.provider):\(t.id)"
        if submission {
            if let id = openPlayId, lastRecordedSong == key {
                history.markListened(playId: id, listenedSec: positionSec, completed: true)
            }
            return
        }
        guard key != lastRecordedSong else { return }
        lastRecordedSong = key
        let albumId = t.albumId.flatMap { $0.isEmpty ? nil : $0 }
        openPlayId = history.recordPlay(scope: historyScope, provider: t.provider, songId: t.id, albumId: albumId,
                                        artistId: albumId.flatMap { albumDetails[$0]?.artistId },
                                        title: t.title, album: t.album, artist: t.artist,
                                        coverKey: Self.coverKey(t.coverURL, provider: t.provider),
                                        durationSec: t.durationSec)
    }

    /// An album page opened (Android `recordAlbumVisit`).
    func recordAlbumVisit(_ album: Album) {
        guard isReal, album.id.provider == provider, !album.id.rawId.hasPrefix("song:") else { return }
        history.recordAlbumVisit(scope: historyScope, provider: provider, albumId: album.id.rawId, title: album.title,
                                 artist: album.artist, artistId: album.artistId,
                                 coverKey: Self.coverKey(album.coverURL, provider: provider))
    }

    /// An artist page opened (Android `recordArtistVisit`).
    func recordArtistVisit(_ page: ArtistPage) {
        guard isReal, page.id.provider == provider else { return }
        history.recordArtistVisit(scope: historyScope, provider: provider, artistId: page.id.rawId, name: page.name,
                                  coverKey: Self.coverKey(page.palette.cover?.url, provider: provider))
    }

    /// A playlist page opened (Android `recordPlaylistVisit`).
    func recordPlaylistVisit(_ info: PlaylistInfo) {
        guard isReal, info.id.provider == provider else { return }
        history.recordPlaylistVisit(scope: historyScope, provider: provider, playlistId: info.id.rawId, name: info.title,
                                    owner: info.owner, coverKey: Self.coverKey(info.palette.cover?.url, provider: provider))
    }

    /// What the album page shows of its plays in Yoin.
    struct AlbumHistory: Equatable, Sendable {
        var playCount: Int
        var firstPlayedAt: Int64?
        var lastPlayedAt: Int64?
        /// Plays per song id.
        var songPlays: [String: Int]
        /// "Yesterday" / "16:04" (nil = never played here).
        var lastPlay: (day: String, time: String)? {
            lastPlayedAt.map { HomeHistory.lastPlayLabels($0, now: Int64(Date().timeIntervalSince1970 * 1000)) }
        }

    }

    /// The album's plays in Yoin; nil signed out (the mock pages keep their copy).
    func albumHistory(_ album: Album) -> AlbumHistory? {
        guard isReal, album.id.provider == provider else { return nil }
        let id = album.id.rawId
        let stats = history.albumStats(scope: historyScope, provider: provider, albumId: id)
        return AlbumHistory(playCount: stats?.playCount ?? 0, firstPlayedAt: stats?.firstPlayedAt,
                            lastPlayedAt: stats?.lastPlayedAt,
                            songPlays: history.songPlayCounts(scope: historyScope, provider: provider, albumId: id))
    }

    /// The local stores as HomeHistory reads them, for the active account.
    func historyInputs(extra: [Album] = []) -> HomeHistory.Inputs {
        let provider = provider
        var known: [String: Album] = [:]
        for a in albums + extra where known[a.id.rawId] == nil { known[a.id.rawId] = a }
        let details = albumDetails
        let ratings = ratings
        let albumRecords = AlbumMemoryStore.shared.albums.values.filter { $0.provider == provider }
        let albumRatings = Dictionary(albumRecords.map { ($0.albumId, $0.rating) }, uniquingKeysWith: { $1 })
        let albumWrites = Dictionary(albumRecords.map { ($0.albumId, max($0.updatedAt, $0.reviewUpdatedAt ?? 0)) },
                                     uniquingKeysWith: { max($0, $1) })
        let reviewed = Set(albumRecords.filter { !($0.review ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
            .map(\.albumId))
        let ratingTimes = ratings.updatedAt
        return HomeHistory.Inputs(
            now: history.nowMs,
            plays: history.plays(scope: historyScope, provider: provider),
            notes: notes.notes.filter { $0.provider == provider },
            details: details, known: known,
            localRating: { ratings.rating(provider: provider, songId: $0) },
            resolve: { [weak self] id, snap in
                details[id] ?? known[id] ?? self?.snapshotAlbum(id, snap)
            },
            localAlbumRating: { albumRatings[$0] },
            ratedAlbumIds: albumRecords
                .filter { $0.rating > 0 || !($0.review ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
                .map(\.albumId),
            ratingWrittenAt: { ratingTimes[LocalRatings.key(provider: provider, songId: $0)] },
            albumWrittenAt: { albumWrites[$0] },
            albumReviewed: { reviewed.contains($0) })
    }

    /// An album made from a play's snapshot, for one the app has no copy of.
    private func snapshotAlbum(_ id: String, _ snap: PlayEvent?) -> Album? {
        guard let snap, !snap.album.isEmpty || !snap.title.isEmpty else { return nil }
        let palette: CoverPalette
        if let mapping, snap.provider == MediaRef.companion.PROVIDER_SUBSONIC {
            palette = mapping.palette(coverId: snap.coverKey ?? id)
        } else {
            palette = AppleMusicMapping.palette(snap.coverKey.flatMap(URL.init(string:)), key: id)
        }
        return Album(id: MediaId(provider: snap.provider, rawId: id), title: snap.album.isEmpty ? snap.title : snap.album,
                     artist: snap.artist, year: 0, palette: palette, tracks: [], artistId: snap.artistId)
    }

    /// A Rediscover song's cover when its album isn't known: a quiet placeholder.
    private func songPlaceholderAlbum(_ s: HomeHistory.SongMemory) -> Album {
        Album(id: MediaId(provider: s.provider, rawId: "song:\(s.songId)"), title: s.title, artist: s.artist, year: 0,
              palette: placeholderPalette(coverId: s.coverKey), tracks: [])
    }

    /// A noted song's cover when it never played here: a quiet placeholder.
    private func placeholderAlbum(_ note: SongNote) -> Album {
        Album(id: MediaId(provider: note.provider, rawId: "song:\(note.trackId)"), title: note.title,
              artist: note.artist, year: 0, palette: placeholderPalette(coverId: nil), tracks: [])
    }

    /// What a history row keeps of a cover: Subsonic's cover id (its URL
    /// carries the credentials), else the artwork URL.
    static func coverKey(_ url: URL?, provider: String) -> String? {
        guard let url else { return nil }
        if provider == MediaRef.companion.PROVIDER_SUBSONIC {
            return URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "id" }?.value
        }
        return url.absoluteString
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
