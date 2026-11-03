import SwiftUI

/// Pushed pages on the phone's NavigationStack.
enum Route: Hashable {
    case album(Album)
    case artist(String)
    case playlist(String)
}

enum ShellSection: Hashable {
    case home
    case library
}

@MainActor
@Observable
final class AppModel {
    let library: any MusicLibrary
    let playbackSource: any PlaybackSource

    var section: ShellSection = .home
    var path: [Route] = []
    var padSelection: Album?
    var isPad = false
    var npExpanded = false
    /// Phone: the Now Playing morph's target (lags `npExpanded` by a frame on
    /// open so the page mounts at the pill first). The pill is the geometry
    /// source for cover / title / surface whenever this is false.
    var npMorphOpen = false
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
    /// Nothing playing: the bar folds the pill away (Android idle bar).
    var isIdle = false
    var playback: PlaybackSnapshot

    init(library: any MusicLibrary = MockLibrary.shared, playback: any PlaybackSource = MockLibrary.shared) {
        self.library = library
        self.playbackSource = playback
        self.playback = playback.current()
        glassFlavor = GlassFlavor(rawValue: LaunchConfig.glass) ?? .system
        switch LaunchConfig.baseScreen {
        case "library": section = .library
        case "library-albums", "library-songs", "library-scroller": section = .library
        case "album", "album-list", "album-mid", "album-scrapbook", "album-scrapbook-bottom": path = [.album(library.albums()[0])]
        case "np", "np-touch": npExpanded = true
        case "np-short": npExpanded = true; npForceShort = true
        case "np-morph": slowMorph = true
        case "np-morph-close": slowMorph = true; npExpanded = true
        case "np-panel": npPanel = true
        case "landing": showLanding = true
        case "artist", "artist-bottom": path = [.artist("Mina Asahi")]
        case "playlist", "playlist-list": path = [.playlist("pl1")]
        case "progress35": self.playback.positionSec = Int(Double(self.playback.track.durationSec) * 0.35)
        case "search", "search-results", "search-catalog", "search-albums", "search-songs": searchActive = true
        case "idle": isIdle = true
        case "library-idle": isIdle = true; section = .library
        default: break
        }
        padSelection = library.albums()[0]
    }

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
        case .artist(let name): return MockLibrary.shared.releases(by: name).first
        case .playlist(let id): return MockLibrary.shared.playlist(id).asAlbum
        case nil: return nil
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

    func openArtist(_ name: String) {
        path.append(.artist(name))
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
