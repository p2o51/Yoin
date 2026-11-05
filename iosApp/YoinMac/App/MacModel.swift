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
    enum LibraryKind: String, CaseIterable, Hashable { case albums = "Albums", artists = "Artists", songs = "Songs" }

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
    let player = PlayerEngine(output: AVAudioOutput())
    let lyrics = MacLyrics()
    @ObservationIgnored private var system: MacPlaybackSystem?

    var tab: Tab = .home
    var libraryKind: LibraryKind = .albums
    /// Pushed detail pages, newest last (a column when the window is wide enough).
    var details: [Detail] = []
    var npMode: NowPlayingMode = .closed
    var focus = false
    var searchOpen = false
    var selectedTrack: String?
    /// The left column's share when a detail column is open (45 : 55 by default).
    var splitFraction: CGFloat = 0.45
    var windowWidth: CGFloat = 1280
    /// Full Now Playing was entered from the panel (Esc goes back to it) or straight from closed.
    @ObservationIgnored private var fullCameFromPanel = false

    /// Songs the queue was built from, so Now Playing can show their album and cover.
    @ObservationIgnored private var known: [String: PlaylistEntry] = [:]

    init() {
        library = MacLibrary(session: session)
        system = MacPlaybackSystem(player: player)
        player.onScrobble = { [weak self] track, submission in self?.library.scrobble(track, submission: submission) }
        player.onStateChange = { [weak self] in
            guard let self else { return }
            self.system?.refresh()
            self.lyrics.load(self.player.currentTrack, client: self.session.client)
        }
    }

    // MARK: Layout

    var detail: Detail? { details.last }
    var contentWidth: CGFloat { windowWidth - (npMode == .panel ? Self.panelWidth : 0) }
    /// A detail page sits beside the root as a column (else it is pushed full width).
    var detailAsColumn: Bool { detail != nil && contentWidth >= Self.columnsMinWidth }

    func open(_ d: Detail) {
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

    /// The bar's pill: closed → panel (or straight to full when the panel doesn't fit) → closed.
    func toggleNowPlaying() {
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
        withAnimation(Yoin.spatial) {
            fullCameFromPanel = npMode == .panel
            npMode = .full
        }
    }

    func toggleFocus() {
        withAnimation(Yoin.spatial) { focus.toggle() }
    }

    /// Esc: focus → full → panel → closed, then search, then the detail page.
    func back() {
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

    func play(album: Album, shuffle: Bool = false, startAt: Int = 0) {
        Task {
            let full = await library.fullAlbum(album)
            let entries = full.tracks.map { PlaylistEntry(album: full, track: $0) }
            if player.shuffle != shuffle { player.setShuffle(shuffle) }
            play(entries, startAt: startAt)
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

    func signedOut() {
        player.stop()
        library.reset()
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
