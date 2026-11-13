import SwiftUI
import YoinShared

/// Pushed pages on the phone's NavigationStack.
enum Route: Hashable {
    case album(Album)
    case artist(ArtistRef)
    case playlist(String)
    /// Server sign-in (from the account avatar).
    case settings
    /// Settings › About › Tips, optionally on one tip (Home's bubble opens it).
    case tips(focus: String?)
    /// Home's Memories grid → All Memories (#186).
    case allMemories
    /// Settings › Yoin Labs (#189).
    case labs
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

    var section: ShellSection = .home {
        // Home left while a pushed page covered it (Memories stays open under
        // a push, HomeView): Memories goes with it, unseen.
        didSet { if section != .home { memories.snapClosed() } }
    }
    var path: [Route] = []
    /// A Settings page to open on arrival (the scrapbook's "Sign in to sync" → NeoDB); taken once.
    var settingsDestination: SettingsDestination?
    var padSelection: Album?
    var isPad = false
    var npExpanded = false
    /// Home's Memories bubble is out of the Dynamic Island (or the notch): the soft status-bar
    /// band steps aside so the island's black meets the screen's top without a haze.
    var islandOut = false
    /// The Spotify sign-in the library's source was built from (account + when it signed in).
    @ObservationIgnored var spotifySignIn: String?
    /// Bumps on every start: a page read that ends after a newer start doesn't play (F02).
    @ObservationIgnored var startGeneration = 0
    /// Phone: the pill ⇄ page morph (one progress value; see
    /// NowPlayingPresentation). Follows `npExpanded`.
    let np = NowPlayingPresentation()
    /// Home's Memories overlay (ShellOverlayUp): one reveal progress.
    let memories = MemoriesPresentation()
    /// iPad: Now Playing as a right-hand inspector panel (Closed → Panel → Full).
    var npPanel = false
    /// iPad: the window's content width, for the panel/columns budget.
    var padWidth: CGFloat = 1024
    /// Screenshot aid: resolve the NP budget as a short (~700 dp) Android phone.
    var npForceShort = false
    /// Screenshot aid: expand Now Playing slowly so a mid-morph frame can be captured.
    var slowMorph = false
    var glassFlavor: GlassFlavor = .system
    /// The first-run landing / Welcome guide over the shell (Landing/LandingView.swift); nil = none.
    var landing: LandingSession?
    /// Search surface open (from the bar's search circle).
    var searchActive = false
    /// What search grew out of: the bar's circle morphs into the field;
    /// Library's header pill already is the field (same rect), so from there
    /// the field stays put and only what's around it changes.
    var searchOrigin: SearchOrigin = .bar
    /// Where the bar's search circle rests (global), for Search's morph.
    var searchCircleFrame: CGRect = .zero
    enum SearchOrigin { case bar, library }

    /// The next Search opens on the provider's whole catalog (Android
    /// onLibraryLongClick: Apple Music → AppleMusicGlobal), taken once.
    var searchStartsOnCatalog = false

    func openSearch(from origin: SearchOrigin, catalog: Bool = false) {
        searchStartsOnCatalog = catalog
        searchOrigin = origin
        withAnimation(Yoin.spatial) {
            searchActive = true
            searchWarming = false
        }
    }

    /// Search is mounted, unseen, under a finger on the bar's search circle:
    /// its first build (~90 ms on an iPhone Air) runs during the press, not on
    /// the open's first frame. No tap within a beat: it goes again.
    private(set) var searchWarming = false
    @ObservationIgnored private var searchWarmTask: Task<Void, Never>?

    func prewarmSearch() {
        guard !searchActive, !searchWarming, !isPad else { return }
        searchStartsOnCatalog = false
        searchOrigin = .bar
        searchWarming = true
        searchWarmTask?.cancel()
        searchWarmTask = Task { @MainActor [weak self] in
            try? await Task.sleep(for: .milliseconds(NowPlayingPresentation.warmHoldMs))
            guard let self, !Task.isCancelled, !searchActive else { return }
            searchWarming = false
        }
    }
    /// Nothing playing: the bar folds the pill away (Android idle bar).
    /// Signed in, that is whenever the engine has no song (the mock song is
    /// only for the signed-out screenshots).
    var isIdle: Bool {
        get { mockIdle || ((store.isReal || !LaunchConfig.demoContent) && player.currentTrack == nil) }
        set { mockIdle = newValue }
    }
    private var mockIdle = false
    /// The mock snapshot the `-screen` screenshots render while the engine's
    /// queue is empty (only then: a real launch with nothing playing is idle).
    var mockPlayback: PlaybackSnapshot
    /// Native playback (AVQueuePlayer, lock screen, remote commands).
    let playbackSystem: PlaybackSystem
    var player: PlayerEngine { playbackSystem.engine }
    /// The Subsonic server in use (shared Kotlin client); none = the mock
    /// library, as the screenshots use.
    let session = ServerSession()
    /// The accounts (several Subsonic servers, Apple Music) and the one in use (Data/Profiles.swift).
    let profiles: ProfileManager
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
        #if DEBUG
        SongAboutDemo.launch(album: mock.album, track: mock.track)
        #endif
        self.playbackSystem = PlaybackSystem.live()
        let sources = SourceSelection(subsonicSignedIn: false)
        self.sources = sources
        let apple = appleMusic
        // The account list (migrated once from the single-server build), and the account in use.
        let forced = (UserDefaults.standard.volatileDomain(forName: UserDefaults.argumentDomain)[SourceSelection.key]
            as? String).flatMap(ActiveSource.init(rawValue:))
        self.profiles = ProfileManager(store: ProfileStore(appleMusicAuthorized: apple.isAuthorized),
                                       session: session, sources: sources,
                                       appleAuthorized: { apple.isAuthorized }, forcedSource: forced)
        self.store = LibraryStore(session: session, sources: sources, appleMusic: appleMusic)
        playbackSystem.appleOutput?.resolve = { id in await apple.song(id) }
        #if DEBUG
        profiles.useFakeSpotify()
        #endif
        observeSpotifyAccount()
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
        case "settings": path = [.settings]
        case "labs": path = [.labs]
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
        // Screenshots and demos open iPad with the mock's first album in the column. A real launch opens with
        // no column (Android: the right column appears only when a page is opened), never a made-up album.
        if LaunchConfig.demoContent, !LaunchConfig.screen.hasPrefix("home") { padSelection = library.albums().first }
        let store = store
        let engine = player
        // Codex A39: what the engine did on its own reaches the shell (the toast); a held failure stays
        // on `player.failure` until Retry / play.
        player.onEvent = { event in
            switch event {
            case .skippedAfterError(let track, _): PlaylistActions.shared.show(String(localized: "Couldn't play \(track.title)"))
            case .failed(let track, let message):
                // A start Spotify refused (no app here, no Premium) isn't the server: the banner says which.
                let refused = SpotifyIssues.Issue(message: message) != nil
                PlaylistActions.shared.show(refused ? String(localized: "Couldn't play \(track.title)")
                                                    : String(localized: "Couldn't reach the server for \(track.title)"))
            case .queueFailed: PlaylistActions.shared.show(String(localized: "Couldn't play this queue"))
            case .queueEnded: break
            }
        }
        // Codex A38: timeOffset only where the server lists transcodeOffset.
        let session = session
        player.timeOffsetSupported = { session.supportsTimeOffset }
        // Codex C04: another account replaces the one in use: its queue (signed URLs, scrobble and
        // history callbacks) ends with it.
        profiles.endPlayback = { [weak self, weak engine] in
            self?.startGeneration += 1
            engine?.stop()
        }
        // Offline downloads are signed by the signed-in account only when they open.
        OfflineDownloads.shared.sign = { [weak session] id, quality in
            guard let client = session?.client else { return nil }
            let kbps = quality.maxBitRateKbps.map { KotlinInt(int: Int32($0)) }
            return URL(string: client.streamUrl(id: id, maxBitRate: kbps,
                                                format: kbps == nil ? nil : SubsonicStreamURLs.transcodeFormat))
        }
        // Downloads the system finished or held while Yoin was away arrive before the queue moves on.
        BackgroundTransfers.shared.reconnect(overMobileData: OfflineDownloads.shared.overMobileData)
        OfflineDownloads.shared.pump()
        // Waiting plays and ratings go out when the network comes back or the app comes to the front.
        store.scrobbles.onReachable = { [weak store] in
            store?.flushScrobbles()
            store?.flushRatings()
        }
        store.scrobbles.watchNetwork()
        NotificationCenter.default.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil,
                                               queue: .main) { [weak store] _ in
            MainActor.assumeIsolated {
                store?.flushScrobbles()
                store?.flushRatings()
            }
        }
        watchPlaybackReports()
        Task { await profiles.fillSpotifyAvatars() }
        player.onScrobble = { track, submission, listenedSec in
            store.scrobble(track, submission: submission)
            // Local play history (Data/History): a row when the song starts, marked once it counts with
            // the time really listened (a seek skips, it isn't listening).
            store.recordHistory(track, submission: submission, positionSec: listenedSec)
        }
        #if DEBUG
        signInFromLaunchArguments()
        runProfilesScript()
        runSpotifyIssueDemo()
        // `-landingReset YES`: forget that the landing was seen (QA of the first-run gate).
        if UserDefaults.standard.bool(forKey: "landingReset") { DefaultsLandingStore().reset() }
        #endif
        // First launch with no account: the landing (an install with an account never sees it).
        landing = LandingGate.atLaunch(store: DefaultsLandingStore(),
                                       hasAccount: !profiles.profiles.isEmpty || appleMusic.isAuthorized,
                                       launch: LaunchConfig.landingLaunch)
    }

    /// Settings › About › Welcome guide.
    func openWelcomeGuide() {
        guard landing == nil else { return }
        landing = LandingGate.rerun()
    }

    #if DEBUG
    /// Debug builds, account QA without typing: `-profilesScript "add:http://127.0.0.1:4995;wait:2000;
    /// switch:0;remove:1;open:settings"`. `add:<url>` connects demo/demo there (Settings › Add account's
    /// path, made the account in use); `switch:<n>` / `remove:<n>` act on the n-th card as a tap / a
    /// confirmed Remove do; `open:` settings, home or library. Never in release builds.
    private func runProfilesScript() {
        guard let script = UserDefaults.standard.string(forKey: "profilesScript") else { return }
        Task {
            try? await Task.sleep(for: .milliseconds(800))
            for token in script.split(separator: ";") {
                let colon = token.firstIndex(of: ":") ?? token.endIndex
                let verb = token[..<colon], arg = colon == token.endIndex ? "" : String(token[token.index(after: colon)...])
                let card = Int(arg).flatMap { $0 < profiles.profiles.count ? profiles.profiles[$0] : nil }
                switch verb {
                case "add": _ = await profiles.connectSubsonic(baseUrl: arg, username: "demo", password: "demo", activate: true)
                case "switch": if let card { await profiles.switchTo(card.id) }
                case "remove": if let card { profiles.remove(card.id) }
                case "wait": try? await Task.sleep(for: .milliseconds(Int(arg) ?? 1000))
                case "open":
                    switch arg {
                    case "settings": if !path.contains(.settings) { openSettings() }
                    case "library": path = []; select(.library)
                    default: path = []; select(.home)
                    }
                default: break
                }
            }
        }
    }

    /// `-spotifyIssueDemo install|premium` (with `-spotifyFake`): the stand-in account's QA Mix is
    /// started as a tap on its first song would. install: the simulator has no Spotify app, so the
    /// start is refused as on a phone without it. premium: the refusal a free account gets (there
    /// is none to test with) is reported for it.
    private func runSpotifyIssueDemo() {
        guard let demo = UserDefaults.standard.string(forKey: "spotifyIssueDemo") else { return }
        Task {
            for _ in 0..<40 where store.spotify == nil { try? await Task.sleep(for: .milliseconds(250)) }
            await store.loadPlaylist("pl_owned")
            guard let p = store.playlist("pl_owned"), !p.entries.isEmpty else { return }
            play(p.entries)
            if demo == "premium" { playbackSystem.spotifyOutput?.demoRefuse(.premiumRequired) }
        }
    }

    /// Debug builds: `-subsonicURL http://127.0.0.1:4979 -subsonicUser demo
    /// -subsonicPass demo` signs in on launch (the screen's own path, minus
    /// typing). `-subsonicSignOut YES` signs out. Never in release builds.
    private func signInFromLaunchArguments() {
        let d = UserDefaults.standard
        if d.bool(forKey: "subsonicSignOut") { profiles.removeAllSubsonic() }
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
                guard await profiles.connectSubsonic(baseUrl: url, username: user, password: pass,
                                                     activate: true) == nil else { return }
            }
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
                // `-serverSeekFromEnd 14`: near the song's end, for the lyric outro.
                let fromEnd = d.double(forKey: "serverSeekFromEnd")
                if fromEnd > 0 {
                    for _ in 0..<40 where player.progress.durationSec <= 0 { try? await Task.sleep(for: .milliseconds(250)) }
                    try? await Task.sleep(for: .seconds(1))
                    let d = player.progress.durationSec > 0 ? player.progress.durationSec : player.currentTrack?.durationSec ?? 0
                    player.seek(to: max(0, d - fromEnd))
                }
                // `-serverRating 8.4`: the playing song rated as a released drag would (rating outbox QA: with
                // the server off it stays owed, and goes out when the account or the network is back).
                if d.object(forKey: "serverRating") != nil, let t = player.currentTrack {
                    NowPlayingData.shared.dragRating(d.double(forKey: "serverRating"), t)
                    NowPlayingData.shared.commitRating(t, client: session.client)
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
            case "all-memories": path = [.allMemories]
            case "labs": path = [.labs]
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
        case .settings, .tips, .allMemories, .labs, nil: return nil
        }
    }

    /// Back from a pushed page. Pops through the path, not `dismiss()`, so
    /// the bar starts its detail → shell morph with the pop instead of when
    /// the pop finishes.
    func back() {
        // iPad: the album picked into the detail column is the column's root; its ← closes the column.
        if path.isEmpty, isPad, padSelection != nil {
            withAnimation(Yoin.spatial) { padSelection = nil }
            return
        }
        guard !path.isEmpty else { return }
        path.removeLast()
    }

    /// The cover tile each album was last opened from (`zoomSource(_:_:tile:)`):
    /// the zoom into its page, and back out, uses that tile's frame alone. Keyed by
    /// id only, two tiles of one album on a page (Activities and Jump Back In)
    /// were both sources, and the cover flew out of the wrong one.
    @ObservationIgnored private var zoomTiles: [MediaId: String] = [:]

    /// The zoom transition's source id for an album's page; nil when it wasn't opened from a
    /// cover tile (search, an artist, Memories, Now Playing), which then pushes the plain way.
    func zoomSourceID(_ id: MediaId) -> String? { zoomTiles[id].map { ZoomTile.sourceID(id, tile: $0) } }

    func open(_ album: Album, tile: String? = nil) {
        zoomTiles[album.id] = tile
        prefetch(.album(album.id.rawId))
        if isPad {
            withAnimation(Yoin.spatial) { padSelection = album }
        } else {
            path.append(.album(album))
        }
    }

    func openPlaylist(_ id: String) {
        prefetch(.playlist(id))
        path.append(.playlist(id))
    }

    func openArtist(_ name: String, id: String? = nil) {
        if let id { prefetch(.artist(id)) }
        path.append(.artist(ArtistRef(name, id: id)))
    }

    enum Prefetch { case album(String), artist(String), playlist(String) }

    /// Tap-time prefetch (Android ui/detail/DetailPrefetch.kt): the page's load starts as the tap lands,
    /// ahead of the push; the page then joins the load in flight (the store's single flight) or reads it.
    func prefetch(_ target: Prefetch) {
        guard store.isReal else { return }
        let store = store
        Task(priority: .userInitiated) {
            switch target {
            case .album(let id) where !id.hasPrefix("song:"): await store.loadAlbum(id)
            case .artist(let id): await store.loadArtist(id)
            case .playlist(let id): await store.loadPlaylist(id)
            default: break
            }
        }
    }

    /// An Activities card's real target (Android `HomeEntryTarget`): its album, artist or playlist page.
    func openActivity(_ entry: ActivityEntry, tile: String? = nil) {
        switch entry.entity {
        case .artist: openArtist(entry.album.title, id: entry.entityId ?? entry.album.artistId)
        case .playlist: openPlaylist(entry.targetId)
        case .album, .song: open(entry.album, tile: tile)
        }
    }

    /// Opens [route] (Now Playing's Playing-from line).
    func open(_ route: Route) {
        switch route {
        case .album(let album): open(album)
        default: path.append(route)
        }
    }

    func openSettings(_ destination: SettingsDestination? = nil) {
        settingsDestination = destination
        if path.last == .settings { return }
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
            #if DEBUG
            // `-artistDemo empty` (with `-serverRoute artist:<id>`): the loaded page as an
            // artist without releases, for the discography's "No releases" line.
            if UserDefaults.standard.string(forKey: "artistDemo") == "empty", var page = store.artistPage(id) {
                page.releases = []
                page.mostPlayed = []
                return page
            }
            #endif
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
        case album(Album), playlist(id: String, title: String), artist(ArtistRef)

        var title: String {
            switch self {
            case .album(let a): a.title
            case .playlist(_, let t): t
            case .artist(let ref): ref.name
            }
        }

        /// Android PlayingFromLabel's kind line.
        var kindLabel: String {
            switch self {
            case .album: String(localized: "PLAYING FROM ALBUM")
            case .playlist: String(localized: "PLAYING FROM PLAYLIST")
            case .artist: String(localized: "PLAYING FROM ARTIST")
            }
        }

        /// The page it opens (the one the queue was started on).
        var route: Route {
            switch self {
            case .album(let a): .album(a)
            case .playlist(let id, _): .playlist(id)
            case .artist(let ref): .artist(ref)
            }
        }
    }

    private(set) var playingFrom: PlayingFrom?

    /// Replaces the queue with [entries] and plays [startAt]; shuffle on or off.
    /// [shuffle]: a Shuffle play (the list starts shuffled, shuffle on). An ordinary start keeps the
    /// remembered play mode (Codex F03; Android PlaybackManager keeps it across starts).
    func play(_ entries: [PlaylistEntry], startAt: Int = 0, shuffle: Bool = false) {
        startGeneration += 1
        let tracks = entries.compactMap(store.playable)
        guard !tracks.isEmpty else { return }
        playingFrom = switch path.last ?? padSelection.map({ .album($0) }) {
        case .album(let a): .album(a)
        case .playlist(let id): .playlist(id: id, title: playlistInfo(id).title)
        case .artist(let ref): .artist(ref)
        default: nil
        }
        playbackSystem.spotifyOutput?.contextPlan = spotifyContext(entries, tracks: tracks)
        if shuffle { player.setShuffle(true) }
        player.play(queue: tracks, startAt: shuffle ? Int.random(in: 0..<tracks.count) : min(startAt, tracks.count - 1))
    }

    /// The album or playlist page a Spotify queue starts from, as Spotify's own context (C-F1): this
    /// iPhone's Spotify then plays on through it while iOS suspends Yoin. Lists from Home, Library or
    /// Search have none.
    private func spotifyContext(_ entries: [PlaylistEntry], tracks: [PlayableTrack]) -> SpotifyContextPlan? {
        guard store.spotify != nil, tracks.allSatisfy({ $0.provider == SpotifyMapping.provider }) else { return nil }
        let entryIds = entries.map(\.track.id.rawId)
        switch playingFrom {
        case .album(let a) where a.id.provider == SpotifyMapping.provider:
            // The album page's songs are the album's, in its order.
            return Self.spotifyContext(uri: "spotify:album:\(a.id.rawId)", contextIds: entryIds,
                                       indices: Array(entryIds.indices), queueIds: tracks.map(\.id))
        case .playlist(let id, _):
            // A playlist's items that aren't songs count in its indices (positions).
            let info = playlistInfo(id)
            guard info.id.provider == SpotifyMapping.provider, let positions = info.positions,
                  info.entries.map(\.track.id.rawId) == entryIds else { return nil }
            return Self.spotifyContext(uri: "spotify:playlist:\(id)", contextIds: entryIds, indices: positions,
                                       queueIds: tracks.map(\.id))
        default:
            return nil
        }
    }

    /// [queueIds] (the playable songs, in order) placed in a context of [contextIds] at [indices]; nil
    /// when one isn't there.
    nonisolated static func spotifyContext(uri: String, contextIds: [String], indices: [Int],
                                           queueIds: [String]) -> SpotifyContextPlan? {
        guard contextIds.count == indices.count, !queueIds.isEmpty else { return nil }
        var from = 0
        var placed: [Int] = []
        for id in queueIds {
            guard let i = contextIds[from...].firstIndex(of: id) else { return nil }
            placed.append(indices[i])
            from = i + 1
        }
        return SpotifyContextPlan(uri: uri, trackIds: queueIds, indices: placed)
    }

    /// Plays what [load] reads (a page's songs), unless something else started meanwhile (Codex F02:
    /// a slow read used to replace the queue the listener had started since).
    func play(loading load: @escaping () async -> [PlaylistEntry], shuffle: Bool = false) {
        startGeneration += 1
        let gen = startGeneration
        Task {
            let entries = await load()
            guard gen == startGeneration else { return }
            play(entries, shuffle: shuffle)
        }
    }

    /// Play next (stacked so the list keeps its order) or Add to queue.
    /// [done] says whether the songs really went in (the sheet only says so then).
    func enqueue(_ entries: [PlaylistEntry], next: Bool, done: (@MainActor (Bool) -> Void)? = nil) {
        let tracks = entries.compactMap(store.playable)
        guard !tracks.isEmpty else { done?(false); return }
        // Spotify plays its own song: Yoin's queue isn't what plays next, Spotify's is. Add to queue
        // goes into Spotify's queue; Play next (Android hides it on Spotify) and other services' songs
        // can't, and never replace what plays (Codex review 2026-11-10 #16).
        if player.externalTrack != nil {
            guard !next, tracks.allSatisfy({ $0.provider == SpotifyMapping.provider }),
                  let spotify = playbackSystem.spotifyOutput else { done?(false); return }
            spotify.enqueueOnSpotify(tracks.map(\.id)) { ok in done?(ok) }
            return
        }
        defer { done?(true) }
        guard player.currentItem != nil else {
            // An empty player takes them in order and waits for Play (Android addMediaItems).
            playingFrom = nil
            playbackSystem.spotifyOutput?.contextPlan = nil
            return player.cue(queue: tracks)
        }
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
            // The discography in order (Codex F01; Android loadArtistPlayTracks): Spotify and Apple
            // Music start with up to 100 songs, a server with every release.
            let page = artistPage(ref)
            let limit = store.provider == MediaRef.companion.PROVIDER_SUBSONIC ? Int.max : 100
            var out: [PlaylistEntry] = []
            for release in page.releases where out.count < limit {
                let full = await store.fullAlbum(release)
                out += full.tracks.map { PlaylistEntry(album: full, track: $0) }
            }
            if out.isEmpty { return page.mostPlayed }
            return Array(out.prefix(limit))
        case .settings, .tips, .allMemories, .labs, nil:
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

    /// Play, pause and song changes go to the server's now-playing view (OpenSubsonic `playbackReport`, #130).
    private func watchPlaybackReports() {
        let player = player
        withObservationTracking {
            _ = player.isPlaying
            _ = player.currentTrack?.id
        } onChange: { [weak self] in
            Task { @MainActor in
                guard let self else { return }
                self.store.reportPlayback(track: player.currentTrack, isPlaying: player.isPlaying,
                                          positionSec: player.progress.positionSec)
                self.watchPlaybackReports()
            }
        }
    }

    func select(_ s: ShellSection) {
        // Home again while Memories is up: it retreats to Home (its back).
        if s == .home, section == .home, memories.isOpen { return memories.close() }
        // A pushed page with no album to play (Settings) shows only Home in the
        // bar: it goes back to the root page, like the back button.
        withAnimation(Yoin.spatial) {
            if isDetail { path.removeAll() }
            section = s
        }
    }

    /// Resigns whatever field has the keyboard, now (not on the next update).
    static func dismissKeyboard() {
        UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
    }

    func setNowPlaying(expanded: Bool) {
        withAnimation(Yoin.spatial) { npExpanded = expanded }
    }

    /// iPad: whether the side panel fits beside the columns (Android: window − panel ≥ 744).
    var padPanelFits: Bool {
        padWidth - Self.padPanelWidth(padWidth) >= (padColumnOpen ? Self.padTwoColumnsMin : Self.padPanelBesideMin)
    }

    /// The side panel's width (Android: clamp(window × 0.5, 360, 420)).
    static func padPanelWidth(_ window: CGFloat) -> CGFloat { min(max(window * 0.5, 360), 420) }
    /// What the panel must leave beside it (Android NowPlayingPresentation): one phone column (320), or
    /// two columns and the slot (744) while a detail column is open.
    static let padPanelBesideMin: CGFloat = 320
    static let padTwoColumnsMin: CGFloat = 744
    /// iPad: a detail column is out (the window is Wide and a page is open in it).
    var padColumnOpen: Bool { padWidth >= 840 && (padSelection != nil || !path.isEmpty) }
    /// iPad: the side panel grows into Full Now Playing.
    func npPanelToFull() {
        // The Full page grows out of the panel itself (the morph starts on the panel's rect, which it covers).
        np.fromPanel = true
        var t = Transaction(); t.disablesAnimations = true
        withTransaction(t) { npPanel = false }
        setNowPlaying(expanded: true)
    }
    /// iPad: Full Now Playing goes back to the side panel.
    func npFullToPanel() {
        // The panel is back in place under the page, and the page folds into its rect.
        np.fromPanel = true
        var t = Transaction(); t.disablesAnimations = true
        withTransaction(t) { npPanel = true }
        setNowPlaying(expanded: false)
    }

    /// iPad pill tap: the panel only when there is room for it beside the
    /// columns (Android rule: shell + detail + handle ≥ 744, else go Full).
    func openNowPlayingOnPad() {
        if padPanelFits {
            withAnimation(Yoin.spatial) { npPanel = true }
        } else {
            setNowPlaying(expanded: true)
        }
    }
}

extension AppModel {
    /// The Spotify account in use, as a source for the library and a token for the Spotify app: built
    /// again whenever the account in use changes (Android ProfileManager.activeSource for Spotify).
    func observeSpotifyAccount() {
        withObservationTracking {
            syncSpotifyAccount()
        } onChange: { [weak self] in
            Task { @MainActor in self?.observeSpotifyAccount() }
        }
    }

    private func syncSpotifyAccount() {
        guard let p = profiles.active, p.service == .spotify, !profiles.needsSignIn(p),
              let tokens = profiles.store.spotify(p.id) else {
            if store.spotifyAccount != nil {
                store.spotifyAccount = nil
                spotifySignIn = nil
                AccountScope.shared.spotify = nil
                SpotifyRemote.shared.token = nil
                SpotifyRemote.shared.bind(account: nil)
            }
            return
        }
        let scope = AccountScope.spotifyScope(userId: tokens.userId)
        // A new sign-in for the same user (after a revoke) builds a fresh source too (Codex N09).
        let signIn = "\(p.id)|\(p.spotifySignedInAt ?? 0)"
        guard spotifySignIn != signIn else { return }
        spotifySignIn = signIn
        // Another account never drives the Spotify app through the previous one's remote (N06).
        SpotifyRemote.shared.bind(account: p.id)
        let profiles = profiles
        let id = p.id
        let provider = SpotifyTokenProvider(profileId: p.id, tokens: tokens, onRefreshed: { fresh in
            // One owner of the tokens in memory too (N10): a switch back reuses the refreshed ones.
            Task { @MainActor in profiles.store.updateSpotify(fresh, for: id) }
        }) {
            // Revoked (invalid_grant): the account asks to sign in again (N09).
            Task { @MainActor in profiles.markSpotifyRevoked(id) }
        }
        let api = SpotifyWebAPI(token: { try await provider.accessToken() },
                                refresh: { _ = try await provider.accessToken(force: true) })
        let source = SpotifySource(api: api)
        source.userId = tokens.userId
        store.spotifyAccount = (source, scope)
        AccountScope.shared.spotify = scope
        SpotifyRemote.shared.token = { try await provider.accessToken() }
        playbackSystem.spotifyOutput?.control = source
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

/// One cover tile's identity for the album zoom: a tile keeps it as `@State`, marks
/// its cover with `zoomSource(_:_:tile:)` and opens with `model.open(_:tile:)`.
struct ZoomTile {
    let key = UUID().uuidString

    static func sourceID(_ id: MediaId, tile: String?) -> String {
        "\(tile ?? "-")|\(id.provider)|\(id.rawId)"
    }
}

extension View {
    /// An album page zooms out of (and back into) its tile when it has one. Without one, a zoom
    /// has nothing to shrink into and its edge swipe didn't follow the finger: the standard
    /// push, whose back swipe always does.
    @ViewBuilder
    func zoomTransition(_ sourceID: String?, _ ns: Namespace.ID) -> some View {
        if let sourceID { navigationTransition(.zoom(sourceID: sourceID, in: ns)) } else { self }
    }

    @ViewBuilder
    func zoomSource(_ id: MediaId, _ ns: Namespace.ID?, tile: String) -> some View {
        if let ns { matchedTransitionSource(id: ZoomTile.sourceID(id, tile: tile), in: ns) } else { self }
    }
}
