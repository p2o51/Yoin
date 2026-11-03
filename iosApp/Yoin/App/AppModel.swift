import SwiftUI

enum ShellSection: Hashable {
    case home
    case library
}

enum NowPlayingBase: String {
    /// A: flat cover color under the glass sheet.
    case flat
    /// B: frosted base, so Now Playing reads as two layers of glass.
    case frost
}

@MainActor
@Observable
final class AppModel {
    let library: any MusicLibrary
    let playbackSource: any PlaybackSource

    var section: ShellSection = .home
    var path: [Album] = []
    var padSelection: Album?
    var isPad = false
    var npExpanded = false
    /// iPad: Now Playing as a right-hand inspector panel (Closed → Panel → Full).
    var npPanel = false
    /// iPad: the window's content width, for the panel/columns budget.
    var padWidth: CGFloat = 1024
    var npBase: NowPlayingBase = .flat
    var glassFlavor: GlassFlavor = .system
    var showLanding = false
    var playback: PlaybackSnapshot

    init(library: any MusicLibrary = MockLibrary.shared, playback: any PlaybackSource = MockLibrary.shared) {
        self.library = library
        self.playbackSource = playback
        self.playback = playback.current()
        npBase = NowPlayingBase(rawValue: LaunchConfig.npBase) ?? .flat
        glassFlavor = GlassFlavor(rawValue: LaunchConfig.glass) ?? .system
        switch LaunchConfig.baseScreen {
        case "library": section = .library
        case "album": path = [library.albums()[0]]
        case "np", "np-frost": npExpanded = true
        case "np-panel": npPanel = true
        case "landing": showLanding = true
        default: break
        }
        padSelection = library.albums()[0]
    }

    /// Accent for the whole app: the playing cover's color.
    var accent: Color { playback.album.palette.tint }

    /// The album page is on screen (phone), so the bar takes its detail form.
    var isDetail: Bool { !isPad && !path.isEmpty }
    var detailAlbum: Album? { isPad ? padSelection : path.last }

    func open(_ album: Album) {
        if isPad {
            withAnimation(Yoin.spatial) { padSelection = album }
        } else {
            path.append(album)
        }
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
