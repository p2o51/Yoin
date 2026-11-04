import SwiftUI
import YoinShared

/// Pushed pages on the phone's NavigationStack.
enum Route: Hashable {
    case album(Album)
    case artist(ArtistRef)
    case playlist(String)
    /// Server sign-in (from the account avatar).
    case settings
}

enum ShellSection: Hashable {
    case home
    case library
}

@MainActor
@Observable
final class AppModel {
    /// The app's one model (see RootView).
    static let shared = AppModel()

    let library: any MusicLibrary
    let playbackSource: any PlaybackSource

    var section: ShellSection = .home
    var path: [Route] = []
    var padSelection: Album?
    var isPad = false
    var npExpanded = false
    /// Phone: the pill ⇄ page morph (one progress value; see
    /// NowPlayingPresentation). Follows `npExpanded`.
    let np = NowPlayingPresentation()
    /// iPad: Now Playing as a right-hand inspector panel (Closed → Panel → Full).
    var npPanel = false
    /// iPad: the window's content width, for the panel/columns budget.
    var padWidth: CGFloat = 1024
    /// Screenshot aid: resolve the NP budget as a short (~700 dp) Android phone.
    var npForceShort = false
    /// Screenshot aid: expand Now Playing slowly so a mid-morph frame can be captured.
    var slowMorph = false
    var glassFlavor: GlassFlavor = .system
    var showLanding = false
    /// Search surface open (from the bar's search circle).
    var searchActive = false
    /// What search grew out of: the bar's circle morphs into the field;
    /// Library's header pill already is the field (same rect), so from there
    /// the field stays put and only what's around it changes.
    var searchOrigin: SearchOrigin = .bar
    /// Where the bar's search circle rests (global), for Search's morph.
    var searchCircleFrame: CGRect = .zero
    enum SearchOrigin { case bar, library }

    func openSearch(from origin: SearchOrigin) {
        searchOrigin = origin
        withAnimation(Yoin.spatial) { searchActive = true }
    }
    /// Nothing playing: the bar folds the pill away (Android idle bar).
    /// Signed in, that is whenever the engine has no song (the mock song is
    /// only for the signed-out screenshots).
    var isIdle: Bool {
        get { mockIdle || (store.isReal && player.currentTrack == nil) }
        set { mockIdle = newValue }
    }
    private var mockIdle = false
    /// The mock snapshot the `-screen` screenshots render while the engine's
    /// queue is empty.
    var mockPlayback: PlaybackSnapshot
    /// Native playback (AVQueuePlayer, lock screen, remote commands).
    let playbackSystem: PlaybackSystem
    var player: PlayerEngine { playbackSystem.engine }
    /// The signed-in Subsonic server (shared Kotlin client); signed out =
    /// the mock library, as the screenshots use.
    let session = ServerSession()
    /// Apple Music through MusicKit (authorization, catalog and library reads).
    let appleMusic = AppleMusicSource()
    /// Mock, Subsonic or Apple Music: what the screens read from.
    let sources: SourceSelection
    /// What the screens read from that server (signed out: nothing; the
    /// screens fall back to the mock library).
    let store: LibraryStore
    /// Queue sheet on Now Playing.
    var queueSheetOpen = false

    /// What the shell and Now Playing show: the engine's song once it has
    /// one, else the mock. Never carries the position while the engine
    /// plays (read it through `PlaybackPosition`), so ticks don't churn
    /// every reader. Writes go to the mock (Glass Lab, screenshot aids).
    var playback: PlaybackSnapshot {
        get {
            guard let t = player.currentTrack else { return mockPlayback }
            return PlaybackSnapshot(album: store.albumForPlaying(t) ?? Self.album(for: t, library: library),
                                    track: Self.track(for: t),
                                    positionSec: 0, isPlaying: player.isPlaying,
                                    lyricLine: "", nextLyricLine: "")
        }
        set { mockPlayback = newValue }
    }

    /// The engine's song as the UI's Album: real titles and the song's own
    /// cover (colors read off it); with no cover URL, colors borrowed from a
    /// mock cover (stable per album).
    static func album(for t: PlayableTrack, library: any MusicLibrary) -> Album {
        let key = t.albumId ?? t.album
        let palette: CoverPalette
        if let url = t.coverURL {
            let ref = CoverRef(url: url)
            let (a, b, c) = SubsonicMapping.placeholder(ref.key)
            palette = CoverPalette(a: a, b: b, c: c, cover: ref)
        } else {
            let mocks = library.albums()
            palette = mocks[key.unicodeScalars.reduce(0) { $0 + Int($1.value) } % max(mocks.count, 1)].palette
        }
        return Album(id: MediaId(provider: t.provider, rawId: key), title: t.album, artist: t.artist,
                     year: 0, palette: palette, tracks: [track(for: t)])
    }

    static func track(for t: PlayableTrack) -> Track {
        Track(id: MediaId(provider: t.provider, rawId: t.id), number: 1, title: t.title,
              durationSec: max(1, Int(t.durationSec.rounded())),
              rating: t.userRating.flatMap { $0 > 0 ? Double($0 * 2) : nil }, starred: t.starred ?? false)
    }

    init(library: any MusicLibrary = MockLibrary.shared, playback: any PlaybackSource = MockLibrary.shared) {
        self.library = library
        self.playbackSource = playback
        var mock = playback.current()
        #if DEBUG
        // Screenshots: `-mockAlbum 1…12` plays another mock cover.
        let mockAlbum = UserDefaults.standard.integer(forKey: "mockAlbum")
        if mockAlbum > 0, mockAlbum <= MockLibrary.allAlbums.count {
            let al = MockLibrary.allAlbums[mockAlbum - 1]
            mock.album = al
            mock.track = al.tracks[min(2, al.tracks.count - 1)]
        }
        #endif
        self.mockPlayback = mock
        self.playbackSystem = PlaybackSystem.live()
        let sources = SourceSelection(subsonicSignedIn: session.isSignedIn)
        self.sources = sources
        self.store = LibraryStore(session: session, sources: sources, appleMusic: appleMusic)
        let apple = appleMusic
        playbackSystem.appleOutput?.resolve = { id in await apple.song(id) }
        glassFlavor = GlassFlavor(rawValue: LaunchConfig.glass) ?? .system
        switch LaunchConfig.baseScreen {
        case "library": section = .library
        case "library-albums", "library-songs", "library-scroller", "library-artists", "library-playlists",
             "library-favorites": section = .library
        case "album", "album-list", "album-mid", "album-scrapbook", "album-scrapbook-bottom": path = [.album(library.albums()[0])]
        case "np", "np-touch": npExpanded = true
        case "np-queue": npExpanded = true; queueSheetOpen = true
        case "np-airplay": npExpanded = true
        case "np-short": npExpanded = true; npForceShort = true
        case "np-morph": slowMorph = true; np.slow = true
        case "np-morph-close": slowMorph = true; np.slow = true; npExpanded = true
        case "np-panel": npPanel = true
        case "landing": showLanding = true
        case "settings": path = [.settings]
        case "artist", "artist-bottom": path = [.artist(ArtistRef("Mina Asahi"))]
        case "playlist", "playlist-list": path = [.playlist("pl1")]
        case "progress35": mockPlayback.positionSec = Int(Double(mockPlayback.track.durationSec) * 0.35)
        case "search", "search-results", "search-catalog", "search-albums", "search-songs", "search-artists",
             "search-playlists", "search-empty-results", "search-scrolled": searchActive = true
        case "home-search":
            // The bar's search circle, from Home: the field grows out of it.
            Task { @MainActor [weak self] in
                try? await Task.sleep(for: .milliseconds(900))
                self?.openSearch(from: .bar)
                // `-searchAutoClose YES`: folds back after a beat (QA clip).
                guard UserDefaults.standard.bool(forKey: "searchAutoClose") else { return }
                try? await Task.sleep(for: .milliseconds(Int(2000 / LaunchConfig.animationSpeed)))
                withAnimation(Yoin.spatial) { self?.searchActive = false }
            }
        case "search-longpress":
            // The bar's Library long-press (ShellBar.openLibrarySearch), a
            // beat after launch so the shot shows where it lands.
            section = .library
            Task { @MainActor [weak self] in
                try? await Task.sleep(for: .milliseconds(900))
                self?.openSearch(from: .library)
            }
        case "song-menu":
            // Home with a song's sheet up (the depth cue under a sheet).
            Task { @MainActor [weak self] in
                try? await Task.sleep(for: .milliseconds(1200))
                guard let p = self?.mockPlayback else { return }
                PlaylistActions.shared.openMenu(PlaylistEntry(album: p.album, track: p.track), context: .nowPlaying)
            }
        case "idle": isIdle = true
        case "library-idle": isIdle = true; section = .library
        default: break
        }
        padSelection = library.albums()[0]
        let store = store
        let engine = player
        player.onScrobble = { [weak engine] track, submission in
            store.scrobble(track, submission: submission)
            // Local play history (Data/History): a row when the song starts, marked once it counts.
            store.recordHistory(track, submission: submission, positionSec: engine?.progress.positionSec ?? 0)
        }
        #if DEBUG
        signInFromLaunchArguments()
        #endif
    }

    #if DEBUG
    /// Debug builds: `-subsonicURL http://127.0.0.1:4979 -subsonicUser demo
    /// -subsonicPass demo` signs in on launch (the screen's own path, minus
    /// typing). `-subsonicSignOut YES` signs out. Never in release builds.
    private func signInFromLaunchArguments() {
        let d = UserDefaults.standard
        if d.bool(forKey: "subsonicSignOut") { session.signOut() }
        let url = d.string(forKey: "subsonicURL")
        let user = d.string(forKey: "subsonicUser") ?? ""
        let pass = d.string(forKey: "subsonicPass") ?? ""
        let route = d.string(forKey: "serverRoute")
        let play = d.string(forKey: "serverPlay")
        // `-serverPlay` / `-serverRoute` without `-subsonicURL`: the saved
        // account as it is, answering or not (offline cache QA).
        guard url != nil || (session.isSignedIn && (route != nil || play != nil)) else { return }
        Task {
            if let url {
                guard await session.signIn(baseUrl: url, username: user, password: pass) else { return }
            }
            sources.use(.subsonic)
            // `-historySeed demo`: backdated plays, ratings and a note (Data/History/HistoryDebugSeed.swift).
            if let seed = d.string(forKey: "historySeed") { await store.seedHistory(seed) }
            // Screenshots of server pages: `-serverRoute album:<id>` (artist:, playlist:),
            // `-serverPlay album:<id>` starts that album (pair with -silentAudio YES).
            try? await Task.sleep(for: .milliseconds(300))
            if let play, play.hasPrefix("album:") {
                let id = String(play.dropFirst(6))
                await store.loadAlbum(id)
                if let a = store.album(id) { self.play(a.tracks.map { PlaylistEntry(album: a, track: $0) }) }
                if d.bool(forKey: "serverOpenNowPlaying") {
                    try? await Task.sleep(for: .seconds(1))
                    setNowPlaying(expanded: true)
                }
            }
            guard let route else { return }
            let colon = route.firstIndex(of: ":") ?? route.endIndex
            let kind = route[..<colon], id = colon == route.endIndex ? "" : String(route[route.index(after: colon)...])
            switch kind {
            case "album":
                await store.loadAlbum(id)
                if let a = store.album(id) { path = [.album(a)] }
            case "artist": path = [.artist(ArtistRef("", id: id))]
            case "playlist": path = [.playlist(id)]
            case "settings": path = [.settings]
            default: break
            }
        }
    }
    #endif

    /// Accent for the whole app: the playing cover's color, Yoin violet when idle.
    var accent: Color { isIdle ? Yoin.violet : playback.album.palette.tint }

    /// The album page is on screen (phone), so the bar takes its detail form.
    var isDetail: Bool { !isPad && !path.isEmpty }
    /// The album whose scheme colors the detail bar: the album page's own,
    /// or an artist page's newest release.
    var detailAlbum: Album? {
        if isPad { return padSelection }
        switch path.last {
        case .album(let a): return a
        case .artist(let ref): return artistPage(ref).releases.first
        case .playlist(let id): return playlistInfo(id).asAlbum
        case .settings, nil: return nil
        }
    }

    /// Back from a pushed page. Pops through the path, not `dismiss()`, so
    /// the bar starts its detail → shell morph with the pop instead of when
    /// the pop finishes.
    func back() {
        guard !path.isEmpty else { return }
        path.removeLast()
    }

    func open(_ album: Album) {
        if isPad {
            withAnimation(Yoin.spatial) { padSelection = album }
        } else {
            path.append(.album(album))
        }
    }

    func openPlaylist(_ id: String) {
        path.append(.playlist(id))
    }

    func openArtist(_ name: String, id: String? = nil) {
        path.append(.artist(ArtistRef(name, id: id)))
    }

    func openSettings() {
        path.append(.settings)
    }

    // MARK: Pages' data (server when signed in, else the mock)

    /// An album with the most the app knows: the server's detail when loaded.
    func resolved(_ album: Album) -> Album {
        guard album.id.provider != "mock" else { return album }
        return store.album(album.id.rawId) ?? album
    }

    func artistPage(_ ref: ArtistRef) -> ArtistPage {
        if store.isReal, let id = ref.id {
            return store.artistPage(id)
                ?? ArtistPage(id: MediaId(provider: store.provider, rawId: id), name: ref.name,
                              starred: false, palette: store.placeholderPalette(coverId: nil),
                              releases: [], mostPlayed: [])
        }
        return MockLibrary.shared.artistPage(ref.name)
    }

    func playlistInfo(_ id: String) -> PlaylistInfo {
        guard store.isReal else { return MockLibrary.shared.playlist(id) }
        if let loaded = store.playlist(id) { return loaded }
        let row = store.playlistSummary(id)
        let palette = row?.palette ?? store.placeholderPalette(coverId: id)
        return PlaylistInfo(id: MediaId(provider: store.provider, rawId: id),
                            title: row?.title ?? "", owner: "", isPublic: nil, description: nil,
                            palette: palette, entries: [])
    }

    // MARK: Playing server songs

    /// Where the queue plays from (Android ActivityContext): the detail page
    /// it was started on; nil from Home / Library / Search lists.
    enum PlayingFrom: Equatable {
        case album(String), playlist(String), artist(String)

        var title: String {
            switch self {
            case .album(let t), .playlist(let t), .artist(let t): t
            }
        }
    }

    private(set) var playingFrom: PlayingFrom?

    /// Replaces the queue with [entries] and plays [startAt]; shuffle on or off.
    func play(_ entries: [PlaylistEntry], startAt: Int = 0, shuffle: Bool = false) {
        let tracks = entries.compactMap(store.playable)
        guard !tracks.isEmpty else { return }
        playingFrom = switch path.last ?? padSelection.map({ .album($0) }) {
        case .album(let a): .album(a.title)
        case .playlist(let id): .playlist(playlistInfo(id).title)
        case .artist(let ref): .artist(ref.name)
        default: nil
        }
        player.setShuffle(shuffle)
        player.play(queue: tracks, startAt: shuffle ? Int.random(in: 0..<tracks.count) : min(startAt, tracks.count - 1))
    }

    /// Play next (stacked so the list keeps its order) or Add to queue.
    func enqueue(_ entries: [PlaylistEntry], next: Bool) {
        let tracks = entries.compactMap(store.playable)
        guard !tracks.isEmpty else { return }
        guard player.currentTrack != nil else { return play(entries) }
        if next { tracks.reversed().forEach(player.playNext) } else { tracks.forEach(player.addToQueue) }
    }

    /// What the detail bar's Play acts on: the album page's tracks, the
    /// playlist's entries, the artist's Most Played (else newest release).
    func detailEntries() async -> [PlaylistEntry] {
        switch path.last {
        case .album(let a):
            let full = await store.fullAlbum(resolved(a))
            return full.tracks.map { PlaylistEntry(album: full, track: $0) }
        case .playlist(let id):
            await store.loadPlaylist(id)
            return playlistInfo(id).entries
        case .artist(let ref):
            let page = artistPage(ref)
            if !page.mostPlayed.isEmpty { return page.mostPlayed }
            guard let first = page.releases.first else { return [] }
            let full = await store.fullAlbum(first)
            return full.tracks.map { PlaylistEntry(album: full, track: $0) }
        case .settings, nil:
            return []
        }
    }

    /// Glass Lab cover picker: play this album's first track at the same progress.
    func play(_ album: Album) {
        let fraction = Double(playback.positionSec) / Double(playback.track.durationSec)
        let track = album.tracks[min(2, album.tracks.count - 1)]
        playback.album = album
        playback.track = track
        playback.positionSec = Int(fraction * Double(track.durationSec))
    }

    func select(_ s: ShellSection) {
        withAnimation(Yoin.spatial) { section = s }
    }

    func setNowPlaying(expanded: Bool) {
        withAnimation(Yoin.spatial) { npExpanded = expanded }
    }

    /// iPad pill tap: the panel only when there is room for it beside the
    /// columns (Android rule: shell + detail + handle ≥ 744, else go Full).
    func openNowPlayingOnPad() {
        let panel = min(max(padWidth * 0.5, 360), 420)
        if padWidth - panel >= 744 {
            withAnimation(Yoin.spatial) { npPanel = true }
        } else {
            setNowPlaying(expanded: true)
        }
    }
}

private struct ZoomNamespaceKey: EnvironmentKey {
    static let defaultValue: Namespace.ID? = nil
}

extension EnvironmentValues {
    /// Namespace for `.navigationTransition(.zoom)` from covers into album pages.
    var zoomNamespace: Namespace.ID? {
        get { self[ZoomNamespaceKey.self] }
        set { self[ZoomNamespaceKey.self] = newValue }
    }
}

extension View {
    @ViewBuilder
    func zoomSource(_ id: MediaId, _ ns: Namespace.ID?) -> some View {
        if let ns { matchedTransitionSource(id: id, in: ns) } else { self }
    }
}
