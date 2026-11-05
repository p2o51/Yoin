import AppKit
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
        case albums = "Albums", artists = "Artists", songs = "Songs", downloads = "Downloads"
    }

    enum Detail: Hashable {
        case album(Album)
        case artist(id: String, name: String)
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
        player = PlayerEngine(output: RoutingAudioOutput(av: AVAudioOutput(), apple: appleOutput))
        library = MacLibrary(session: session)
        system = MacPlaybackSystem(player: player)
        player.onScrobble = { [weak self] track, submission in self?.library.scrobble(track, submission: submission) }
        player.onStateChange = { [weak self] in
            guard let self else { return }
            self.system?.refresh()
            self.lyrics.load(self.player.currentTrack, client: self.session.client)
            if let t = self.player.currentTrack, t.id != self.membershipCheckedFor,
               t.provider == AppleMusicMapping.provider {
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
        offline.pump()
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
        offline.rowTapped(offline.status(collection.key), key: collection.key) {
            Task {
                let full = await library.fullAlbum(album)
                download(collection, full.tracks.map { PlaylistEntry(album: full, track: $0) })
            }
        }
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

    /// The account in use changed: its downloads run, another's wait.
    func accountDidChange() { OfflineDownloads.shared.accountChanged() }

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
    var currentEntry: PlaylistEntry? { entry(for: player.currentTrack) }

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

    func signedOut() {
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
