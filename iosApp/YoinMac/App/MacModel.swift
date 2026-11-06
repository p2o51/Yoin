import AppKit
import os
import Observation
import SwiftUI
import YoinShared

/// The Mac window's state: account, library, player, and where the window is
/// (root tab, detail column, Now Playing's state chain, search).
@MainActor
@Observable
final class MacModel {
    enum Tab: Hashable { case home, library }
    enum LibraryKind: String, CaseIterable, Hashable {
        case albums = "Albums", artists = "Artists", playlists = "Playlists", songs = "Songs", memories = "Memories",
             downloads = "Downloads"
    }

    enum Detail: Hashable {
        case album(Album)
        case artist(id: String, name: String)
        case playlist(id: String, title: String)
    }

    /// Now Playing's one chain (docs/adaptive-principles.md): closed → side panel → full window; focus is a
    /// flag on panel / full.
    enum NowPlayingMode: Equatable { case closed, panel, full }

    /// Shell metrics (prototype v1): the side panel, the room the content keeps beside it, the split point.
    static let panelWidth: CGFloat = 420
    static let minContentBesidePanel: CGFloat = 320
    static let minColumnsBesidePanel: CGFloat = 744
    static let columnsMinWidth: CGFloat = 840
    static let handleWidth: CGFloat = 24

    let session = MacSession()
    let library: MacLibrary
    /// Streams through AVQueuePlayer, Apple Music through MusicKit's ApplicationMusicPlayer (as on iOS).
    let player: PlayerEngine
    @ObservationIgnored private let appleOutput = AppleMusicAudioOutput()
    @ObservationIgnored let spotifyOutput = MacSpotifyOutput()
    @ObservationIgnored private var membershipCheckedFor: String?
    let lyrics = MacLyrics()
    @ObservationIgnored private var system: MacPlaybackSystem?

    var tab: Tab = .home
    var libraryKind: LibraryKind = .albums
    /// Pushed detail pages, newest last (a column when the window is wide enough).
    var details: [Detail] = []
    var npMode: NowPlayingMode = .closed
    var focus = false
    var searchOpen = false
    /// The sign-in page is up to add another account (the current one stays until that works).
    var addingAccount = false
    /// The mini player window is showing.
    var miniOpen = false
    var selectedTrack: String?
    /// The left column's share when a detail column is open (45 : 55 by default).
    var splitFraction: CGFloat = 0.45
    var windowWidth: CGFloat = 1280 { didSet { if windowWidth != oldValue { reflowNowPlaying() } } }
    /// Full Now Playing was entered from the panel (Esc goes back to it) or straight from closed.
    @ObservationIgnored private var fullCameFromPanel = false
    /// The panel went full only because the window got too narrow for it; it comes back when there's room.
    @ObservationIgnored private var panelSqueezedToFull = false

    /// Songs the queue was built from, so Now Playing can show their album and cover.
    @ObservationIgnored private var known: [String: PlaylistEntry] = [:]

    init() {
        let router = RoutingAudioOutput(av: AVAudioOutput(), apple: appleOutput, spotify: spotifyOutput)
        player = PlayerEngine(output: router)
        library = MacLibrary(session: session)
        system = MacPlaybackSystem(player: player)
        player.onScrobble = { [weak self] track, submission in
            guard let self else { return }
            self.library.scrobble(track, submission: submission)
            self.recordHistory(track, submission: submission)
        }
        player.onStateChange = { [weak self] in
            guard let self else { return }
            self.system?.refresh()
            self.lyrics.choiceScope = self.session.accountKey
            self.lyrics.load(self.player.currentTrack, client: self.session.client)
            if let t = self.player.currentTrack, t.id != self.membershipCheckedFor,
               t.provider == AppleMusicMapping.provider || t.provider == SpotifyMapping.provider {
                self.membershipCheckedFor = t.id
                self.library.refreshLibraryMembership(t.id)
            }
        }
        // Offline downloads (iOS OfflineDownloads): this Subsonic account's, signed only when a file opens;
        // a downloaded song plays from its file.
        let offline = OfflineDownloads.shared
        offline.scope = { [weak self] in
            guard let self, self.session.client != nil else { return nil }
            return self.session.accountKey
        }
        offline.sign = { [weak self] id, quality in
            guard let client = self?.session.client else { return nil }
            let kbps = quality.maxBitRateKbps.map { KotlinInt(int: Int32($0)) }
            return URL(string: client.streamUrl(id: id, maxBitRate: kbps,
                                                format: kbps == nil ? nil : SubsonicStreamURLs.transcodeFormat))
        }
        player.downloadedFile = { OfflineDownloads.shared.localFile(for: $0) }
        player.streamQuality = { MacSettings.streamQuality }
        offline.pump()
        spotifyOutput.source = { [weak self] in self?.session.spotify }
        // Playlist backups (iOS Data/Backup) read and restore through the Mac's accounts; the ones followed
        // here are checked again whenever Yoin comes to the front (iOS: foreground), off that path.
        MacBackupAccounts.shared.session = session
        NotificationCenter.default.addObserver(forName: NSApplication.didBecomeActiveNotification, object: nil,
                                               queue: .main) { _ in
            Task { @MainActor in
                try? await Task.sleep(for: .seconds(8))
                _ = await PlaylistBackups.shared.followDue()
            }
        }
        spotifyOutput.onDevice = { [weak self] name in self?.spotifyDevice = name }
        // Spotify playing somewhere is what Yoin shows (iOS 7702336e), unless Yoin itself plays another source.
        spotifyOutput.adopt = { [weak router, weak self] in
            guard let router, let self else { return false }
            if self.player.isPlaying, self.player.currentTrack?.provider != SpotifyMapping.provider { return false }
            router.adopt(self.spotifyOutput)
            return true
        }
        spotifyOutput.startWatching()
        // The first-run landing (iOS LandingGate): once per install, before any account; `-macScreen landing`
        // shows it every time and records nothing; other QA screens skip it.
        #if DEBUG
        let launch: LandingLaunch = MacLaunch.screen == "landing" ? .forced : MacLaunch.screen != nil ? .demo : .normal
        #else
        let launch = LandingLaunch.normal
        #endif
        landing = LandingGate.atLaunch(store: DefaultsLandingStore(), hasAccount: !session.profiles.isEmpty, launch: launch)
        // Apple Music songs resolve through whichever Apple Music source is in use now.
        appleOutput.resolve = { [weak self] id in await self?.session.apple?.song(id) }
    }

    // MARK: Layout

    var detail: Detail? { details.last }
    var contentWidth: CGFloat { windowWidth - (npMode == .panel ? Self.panelWidth : 0) }
    /// A detail page sits beside the root as a column (else it is pushed full width).
    var detailAsColumn: Bool {
        detail != nil && contentWidth >= (npMode == .panel ? Self.minColumnsBesidePanel : Self.columnsMinWidth)
    }

    func open(_ d: Detail) {
        panelSqueezedToFull = false
        withAnimation(Yoin.spatial) {
            if npMode == .full { npMode = .closed; focus = false }
            if detailAsColumn || details.isEmpty { details = [d] } else { details.append(d) }
            // The panel can't keep two columns beside it: it closes rather than squeezing them.
            if npMode == .panel, windowWidth - Self.panelWidth < Self.minColumnsBesidePanel { npMode = .closed }
        }
    }

    func closeDetail() {
        withAnimation(Yoin.spatial) { _ = details.popLast() }
    }

    func select(_ t: Tab) {
        panelSqueezedToFull = false
        withAnimation(Yoin.spatial) {
            tab = t
            if !detailAsColumn { details = [] }
            if npMode == .full { npMode = .closed; focus = false }
        }
    }

    // MARK: Now Playing chain

    private var panelFits: Bool {
        windowWidth - Self.panelWidth >= (detail != nil ? Self.minColumnsBesidePanel : Self.minContentBesidePanel)
    }

    /// A resize that leaves no room beside the panel turns it into the full window; widening the window
    /// again brings the panel back.
    private func reflowNowPlaying() {
        if npMode == .panel, !panelFits {
            withAnimation(Yoin.spatial) { npMode = .full }
            fullCameFromPanel = true
            panelSqueezedToFull = true
        } else if npMode == .full, panelSqueezedToFull, panelFits {
            withAnimation(Yoin.spatial) { npMode = .panel }
            panelSqueezedToFull = false
        }
    }

    /// The bar's pill: closed → panel (or straight to full when the panel doesn't fit) → closed.
    func toggleNowPlaying() {
        panelSqueezedToFull = false
        withAnimation(Yoin.spatial) {
            switch npMode {
            case .closed:
                if panelFits { npMode = .panel } else { npMode = .full; fullCameFromPanel = false }
            case .panel, .full:
                npMode = .closed
                focus = false
            }
        }
    }

    func expandNowPlaying() {
        panelSqueezedToFull = false
        withAnimation(Yoin.spatial) {
            fullCameFromPanel = npMode == .panel
            npMode = .full
        }
    }

    /// ⌘L: into the full window, or back out of it (to the panel it came from, else closed).
    func toggleFullNowPlaying() {
        if npMode == .full { focus = false; back() } else { expandNowPlaying() }
    }

    /// ⌘F: focus wherever Now Playing is; from closed it opens the full window in focus.
    func toggleFocusFromAnywhere() {
        if npMode == .closed {
            panelSqueezedToFull = false
            withAnimation(Yoin.spatial) { fullCameFromPanel = false; npMode = .full; focus = true }
        } else {
            toggleFocus()
        }
    }

    func toggleFocus() {
        withAnimation(Yoin.spatial) { focus.toggle() }
    }

    /// Esc: focus → full → panel → closed, then search, then the detail page.
    func back() {
        panelSqueezedToFull = false
        withAnimation(Yoin.spatial) {
            if searchOpen { searchOpen = false; return }
            if focus { focus = false; return }
            switch npMode {
            case .full:
                npMode = fullCameFromPanel && panelFits ? .panel : .closed
                return
            case .panel:
                npMode = .closed
                return
            case .closed: break
            }
            if !details.isEmpty { details.removeLast() }
        }
    }

    var canGoBack: Bool { searchOpen || focus || npMode != .closed || !details.isEmpty }

    // MARK: Playback

    func play(_ entries: [PlaylistEntry], startAt: Int = 0) {
        for e in entries { known[e.track.id.rawId] = e }
        let tracks = entries.compactMap(library.playable)
        guard !tracks.isEmpty else { return }
        player.play(queue: tracks, startAt: min(startAt, tracks.count - 1))
    }

    func playNext(_ e: PlaylistEntry) {
        known[e.track.id.rawId] = e
        if let t = library.playable(e) { player.playNext(t) }
    }

    func addToQueue(_ e: PlaylistEntry) {
        known[e.track.id.rawId] = e
        if let t = library.playable(e) { player.addToQueue(t) }
    }

    /// Play / Shuffle keep the listener's play mode (docs/design.md): Shuffle plays the album in a random
    /// order. Only the newest request plays, and only for the account it was made in.
    func play(album: Album, shuffle: Bool = false, startAt: Int = 0) {
        playRequest += 1
        let token = playRequest
        let gen = library.accountGeneration
        Task {
            let full = await library.fullAlbum(album)
            guard token == playRequest, gen == library.accountGeneration else { return }
            var entries = full.tracks.map { PlaylistEntry(album: full, track: $0) }
            if shuffle { entries.shuffle() }
            play(entries, startAt: shuffle ? 0 : startAt)
        }
    }

    @ObservationIgnored private var playRequest = 0

    /// Downloads an album (its full track list) for offline listening, or removes / retries it.
    func toggleDownload(album: Album) {
        let offline = OfflineDownloads.shared
        let collection = OfflineDownloads.Collection(kind: .album, id: album.id.rawId, title: album.title,
                                                     subtitle: album.artist,
                                                     coverId: OfflineDownloads.coverId(album.palette.cover))
        let gen = library.accountGeneration
        let scope = session.accountKey
        offline.rowTapped(offline.status(collection.key), key: collection.key) {
            Task {
                let full = await library.fullAlbum(album)
                // Only for the account that asked (a switch meanwhile could hand back another's album).
                guard gen == library.accountGeneration, scope == session.accountKey else { return }
                download(collection, full.tracks.map { PlaylistEntry(album: full, track: $0) })
            }
        }
    }

    /// A download's own songs as queue entries, from what was saved (works offline, with nothing loaded).
    func downloadedEntries(_ collectionKey: String) -> [PlaylistEntry] {
        let songs = OfflineDownloads.shared.visible.filter { s in s.collections.contains { $0.key == collectionKey } }
        let collection = songs.first?.collections.first { $0.key == collectionKey }
        let cover = collection?.coverId.flatMap { id in
            session.client.flatMap { URL(string: $0.coverArtUrl(id: id, size: nil)) }
        }
        return songs.map { s in
            let palette = MacArtistDetail.placeholder(s.albumId ?? s.songId)
            let album = Album(id: MediaId(provider: MediaRef.companion.PROVIDER_SUBSONIC, rawId: s.albumId ?? "song:\(s.songId)"),
                              title: s.album, artist: s.artist, year: 0,
                              palette: CoverPalette(a: palette.a, b: palette.b, c: palette.c,
                                                    cover: cover.map { CoverRef(url: $0) }),
                              tracks: [])
            let track = Track(id: MediaId(provider: MediaRef.companion.PROVIDER_SUBSONIC, rawId: s.songId), number: 0,
                              title: s.title, durationSec: s.durationSec, rating: nil, artist: s.artist)
            return PlaylistEntry(album: album, track: track)
        }
    }

    /// Plays a download from its saved songs, or retries its failed ones.
    func playDownload(_ collectionKey: String) {
        let entries = downloadedEntries(collectionKey)
        if !entries.isEmpty { play(entries) }
    }

    func retryDownload(_ e: OfflineDownloads.Entry) {
        let entries = downloadedEntries(e.collection.key)
        OfflineDownloads.shared.remove(e.collection.key)
        download(e.collection, entries)
    }

    /// One song on its own.
    func toggleDownload(song e: PlaylistEntry) {
        let offline = OfflineDownloads.shared
        let collection = OfflineDownloads.Collection(kind: .song, id: e.track.id.rawId, title: e.track.title,
                                                     subtitle: e.track.artist ?? e.album.artist,
                                                     coverId: OfflineDownloads.coverId(e.album.palette.cover))
        offline.rowTapped(offline.status(collection.key), key: collection.key) { download(collection, [e]) }
    }

    private func download(_ c: OfflineDownloads.Collection, _ entries: [PlaylistEntry]) {
        OfflineDownloads.shared.download(c, entries: entries) { [library] e in
            library.playable(e).flatMap { SubsonicCacheKey.of($0.streamURL) }
        }
    }

    // MARK: History (iOS HistoryStore: this Mac's plays, per account)

    /// When each album, artist and playlist was last opened or played here (iOS LibraryStore.libraryRecents),
    /// keyed "album:<id>" / "artist:<id>" / "playlist:<id>": Library's Recents order.
    func libraryRecents() -> [String: Int64] {
        let history = HistoryStore.shared
        let scope = session.accountKey, provider = currentProvider
        guard !scope.isEmpty else { return [:] }
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

    @ObservationIgnored private var openPlay: (song: String, id: String)?

    /// A song that starts is a play row; heard long enough, it counts.
    private func recordHistory(_ t: PlayableTrack, submission: Bool) {
        let history = HistoryStore.shared
        let scope = session.accountKey
        guard !scope.isEmpty else { return }
        if submission {
            guard let open = openPlay, open.song == t.id else { return }
            history.markListened(playId: open.id, listenedSec: player.progress.positionSec, completed: true)
            return
        }
        let e = entry(for: t)
        let coverKey = t.provider == AppleMusicMapping.provider
            ? t.coverURL?.absoluteString
            : OfflineDownloads.coverId(e?.album.palette.cover)
        let albumId = t.albumId.flatMap { $0.hasPrefix("song:") ? nil : $0 }
        let id = history.recordPlay(scope: scope, provider: t.provider, songId: t.id, albumId: albumId,
                                    artistId: e?.album.artistId, title: t.title, album: t.album, artist: t.artist,
                                    coverKey: coverKey, durationSec: t.durationSec)
        openPlay = (t.id, id)
    }

    // MARK: Memories (iOS MemoryDeck over the local stores)

    var currentProvider: String {
        session.isAppleMusic ? AppleMusicMapping.provider
            : session.isSpotify ? SpotifyMapping.provider : MediaRef.companion.PROVIDER_SUBSONIC
    }

    /// iOS LibraryStore.historyInputs on the Mac: this account's plays, notes, ratings and album memories.
    func historyInputs() -> HomeHistory.Inputs {
        let provider = currentProvider
        var known: [String: Album] = [:]
        let lists = library.albums + library.home.recentlyPlayed + library.home.recentlyAdded + library.home.random
        for a in lists where known[a.id.rawId] == nil { known[a.id.rawId] = a }
        let details = library.albumDetails
        let ratings = LocalRatings.shared
        let albumRecords = AlbumMemoryStore.shared.albums.values.filter { $0.provider == provider }
        let albumRatings = Dictionary(albumRecords.map { ($0.albumId, $0.rating) }, uniquingKeysWith: { $1 })
        let albumWrites = Dictionary(albumRecords.map { ($0.albumId, max($0.updatedAt, $0.reviewUpdatedAt ?? 0)) },
                                     uniquingKeysWith: { max($0, $1) })
        let reviewed = Set(albumRecords.filter { !($0.review ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
            .map(\.albumId))
        let ratingTimes = ratings.updatedAt
        let client = session.client
        return HomeHistory.Inputs(
            now: HistoryStore.shared.nowMs,
            plays: HistoryStore.shared.plays(scope: session.accountKey, provider: provider),
            notes: NotesStore.shared.notes.filter { $0.provider == provider },
            details: details, known: known,
            localRating: { ratings.rating(provider: provider, songId: $0) },
            resolve: { id, snap in
                if let a = details[id] ?? known[id] { return a }
                guard let snap, !snap.album.isEmpty || !snap.title.isEmpty else { return nil }
                let p = MacArtistDetail.placeholder(id)
                let url: URL? = snap.provider == MediaRef.companion.PROVIDER_SUBSONIC
                    ? snap.coverKey.flatMap { k in client.flatMap { URL(string: $0.coverArtUrl(id: k, size: nil)) } }
                    : snap.coverKey.flatMap(URL.init(string:))
                return Album(id: MediaId(provider: snap.provider, rawId: id),
                             title: snap.album.isEmpty ? snap.title : snap.album, artist: snap.artist, year: 0,
                             palette: CoverPalette(a: p.a, b: p.b, c: p.c, cover: url.map { CoverRef(url: $0) }),
                             tracks: [], artistId: snap.artistId)
            },
            localAlbumRating: { albumRatings[$0] },
            ratedAlbumIds: albumRecords
                .filter { $0.rating > 0 || !($0.review ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
                .map(\.albumId),
            ratingWrittenAt: { ratingTimes[LocalRatings.key(provider: provider, songId: $0)] },
            albumWrittenAt: { albumWrites[$0] },
            albumReviewed: { reviewed.contains($0) })
    }

    /// iOS hydrateMemoryAlbums: the albums a written memory needs and the Mac has no track list for (a synced
    /// review of an album never opened here), loaded a few at a time for the account that asked.
    func hydrateMemoryAlbums() async {
        let gen = library.accountGeneration
        let missing = HomeHistory.writtenAlbumIds(historyInputs())
            .filter { library.albumDetails[$0]?.tracks.isEmpty ?? true }
            .sorted()
            .prefix(24)
        for id in missing {
            guard gen == library.accountGeneration else { return }
            await library.loadAlbum(id)
        }
    }

    /// The Memories deck (iOS memoryDeckInputs): memories, each album's notes, reviews, titles, ratings.
    func memoryCards() -> [MemoryCard] {
        let provider = currentProvider
        let inputs = historyInputs()
        var songAlbum: [String: String] = [:]
        for p in inputs.plays { if let a = p.albumId { songAlbum[p.songId] = a } }
        for (id, album) in inputs.details { for t in album.tracks where songAlbum[t.id.rawId] == nil { songAlbum[t.id.rawId] = id } }
        var byAlbum: [String: [SongNote]] = [:]
        for n in inputs.notes { if let a = songAlbum[n.trackId] { byAlbum[a, default: []].append(n) } }
        let store = AlbumMemoryStore.shared
        return MemoryDeck.cards(MemoryDeck.Inputs(
            memories: HomeHistory.memories(inputs), notes: byAlbum,
            review: { id in
                guard let r = store.album(provider: provider, albumId: id), let text = r.review else { return nil }
                return (text, r.reviewUpdatedAt ?? r.updatedAt)
            },
            title: { id in
                if let t = store.userTitle(provider: provider, albumId: id) { return (t, true) }
                return store.memoryTitle(provider: provider, albumId: id).map { ($0, false) }
            },
            rating: { LocalRatings.shared.rating(provider: provider, songId: $0) },
            yoinTitle: { store.memory(provider: provider, entityId: $0)?.title },
            copy: { store.memory(provider: provider, entityId: $0)?.copy }))
    }

    /// Albums this account has played here, the most recent first (Home's Memories shelf).
    func memories(limit: Int = 18) -> [(album: Album, stats: AlbumPlayStats)] {
        let scope = session.accountKey
        let provider = session.isAppleMusic ? AppleMusicMapping.provider : MediaRef.companion.PROVIDER_SUBSONIC
        guard !scope.isEmpty else { return [] }
        return HistoryStore.shared.lastPlayedAlbums(scope: scope, provider: provider, limit: limit).map { s in
            let key = s.latest.coverKey
            let url: URL? = if provider == AppleMusicMapping.provider { key.flatMap(URL.init(string:)) }
                else { key.flatMap { id in session.client.flatMap { URL(string: $0.coverArtUrl(id: id, size: nil)) } } }
            let p = MacArtistDetail.placeholder(s.albumId)
            let album = Album(id: MediaId(provider: provider, rawId: s.albumId), title: s.latest.album,
                              artist: s.latest.artist, year: 0,
                              palette: CoverPalette(a: p.a, b: p.b, c: p.c, cover: url.map { CoverRef(url: $0) }),
                              tracks: [], artistId: s.latest.artistId)
            return (album, s)
        }
    }

    /// The Spotify device Yoin plays on when it isn't this Mac (Now Playing names it).
    var spotifyDevice: String?
    /// The first-run landing on screen (nil: none).
    var landing: LandingSession?

    /// "Play on This Mac": Spotify moves here (only ever the listener's choice).
    func spotifyPlayHere() {
        spotifyNotice = nil
        Task {
            do {
                try await spotifyOutput.playHere()
            } catch {
                spotifyNotice = SpotifySource.message(for: error)
                Self.log.error("play here failed: \(SpotifySource.message(for: error), privacy: .public)")
            }
        }
    }

    /// Why Play Here didn't work (shown in the failure banner).
    var spotifyNotice: String?

    private static let log = Logger(subsystem: "com.gpo.yoin.mac", category: "model")

    /// The account in use changed: its downloads run, another's wait.
    func accountDidChange() { OfflineDownloads.shared.accountChanged() }

    /// A playlist's songs, shuffled or in order (the play mode stays the listener's).
    func play(playlist id: String, shuffle: Bool = false) {
        playRequest += 1
        let token = playRequest
        let gen = library.accountGeneration
        Task {
            await library.loadPlaylist(id)
            guard token == playRequest, gen == library.accountGeneration,
                  var entries = library.playlist(id)?.entries, !entries.isEmpty else { return }
            if shuffle { entries.shuffle() }
            play(entries)
        }
    }

    /// Empties what's after the current song.
    func clearUpcoming() {
        guard let i = player.currentIndex else { return }
        for j in stride(from: player.queue.count - 1, to: i, by: -1) { player.remove(at: j) }
    }

    /// An album's songs after the current one (in order) or at the end of the queue.
    func queue(album: Album, next: Bool) {
        let gen = library.accountGeneration
        Task {
            let full = await library.fullAlbum(album)
            guard gen == library.accountGeneration else { return }
            let entries = full.tracks.map { PlaylistEntry(album: full, track: $0) }
            if player.currentTrack == nil { play(entries); return }
            if next {
                for e in entries.reversed() { playNext(e) }
            } else {
                for e in entries { addToQueue(e) }
            }
        }
    }

    func entry(for t: PlayableTrack?) -> PlaylistEntry? { t.flatMap { known[$0.id] } }
    /// The current song as an entry: the queue's own, else one made from the track (Spotify's own song,
    /// picked in Spotify: its title and cover).
    var currentEntry: PlaylistEntry? {
        guard let t = player.currentTrack else { return nil }
        if let e = entry(for: t) { return e }
        let p = MacArtistDetail.placeholder(t.albumId ?? t.id)
        let album = Album(id: MediaId(provider: t.provider, rawId: t.albumId ?? "song:\(t.id)"), title: t.album,
                          artist: t.artist, year: 0,
                          palette: CoverPalette(a: p.a, b: p.b, c: p.c, cover: t.coverURL.map { CoverRef(url: $0) }),
                          tracks: [])
        let track = Track(id: MediaId(provider: t.provider, rawId: t.id), number: 0, title: t.title,
                          durationSec: Int(t.durationSec), rating: nil, artist: t.artist)
        return PlaylistEntry(album: album, track: track)
    }

    /// The song before the current one in play order (for the room's top edge).
    var previousTrack: PlayableTrack? {
        guard let i = player.currentIndex, i > 0 else { return nil }
        return player.queue[i - 1].track
    }

    func toggleLikeCurrent() {
        guard let t = player.currentTrack else { return }
        library.toggleStar(songId: t.id, current: library.isStarred(songId: t.id, fallback: t.starred ?? false))
    }

    func isLikedCurrent() -> Bool {
        guard let t = player.currentTrack else { return false }
        return library.isStarred(songId: t.id, fallback: currentEntry?.track.starred ?? t.starred ?? false)
    }

    /// Fast switch: what the previous account showed goes, the other one comes in.
    func switchAccount(_ id: String) {
        guard id != session.activeId || !session.isSignedIn else { return }
        signedOut()
        session.switchTo(id)
    }

    /// Bumps when an Add Account is cancelled or the account changes: a sign-in still running then doesn't land.
    @ObservationIgnored private(set) var authToken = 0
    func supersedeSignIn() { authToken += 1 }

    func signedOut() {
        searchOpen = false
        authToken += 1
        playRequest += 1
        player.stop()
        library.reset()
        lyrics.reset()
        details = []
        npMode = .closed
        focus = false
        tab = .home
    }
}

// MARK: - Colour

/// The cover's Expressive roles (CoverScheme, the shared MCU), Yoin violet before anything plays.
struct MacTint: Equatable {
    var primary: Color
    var onPrimary: Color
    var container: Color
    var onContainer: Color

    static func of(_ cover: URL?, dark: Bool) -> MacTint {
        let seed: Int
        if let cover, let c = CoverArtwork.shared.colors(CoverRef(url: cover)) { seed = c.seedArgb } else { seed = 0xFF6B4FD8 }
        let s = CoverScheme(seedArgb: seed, dark: dark)
        return MacTint(primary: s.primary, onPrimary: s.onPrimary, container: s.primaryContainer,
                       onContainer: s.onPrimaryContainer)
    }
}
