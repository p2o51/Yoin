import SwiftUI

/// iPad first take (option A in docs/ipad.md): iPadOS 26 top glass tab bar
/// that adapts to a sidebar, the mini player as the tab view's bottom
/// accessory, albums in a right-hand column of the same window, and Now
/// Playing as an inspector panel (or Full when the window is too narrow).
struct PadShell: View {
    @Environment(AppModel.self) private var model
    @Namespace private var npNS
    @State private var tab: PadTab = .home

    enum PadTab: Hashable { case home, library, search }

    var body: some View {
        @Bindable var model = model
        TabView(selection: $tab) {
            Tab("Home", image: "yoin.home", value: PadTab.home) {
                PadColumns(npNamespace: npNS, active: tab == .home)
            }
            Tab("Library", image: "yoin.library", value: PadTab.library) {
                PadColumns(npNamespace: npNS, active: tab == .library)
            }
            Tab("Search", image: "yoin.search", value: PadTab.search, role: .search) {
                PadColumns(npNamespace: npNS, active: tab == .search)
            }
        }
        .tabViewStyle(.sidebarAdaptable)
        .tabViewBottomAccessory {
            PadMiniPlayer()
        }
        .overlay {
            if model.npExpanded {
                NowPlayingView(npNamespace: npNS)
                    .transition(.opacity.combined(with: .scale(scale: 0.96)))
            }
        }
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { model.padWidth = $0 }
        .onAppear {
            tab = model.section == .library ? .library : .home
            // `-screen settings`: the sign-in page in the album column.
            if LaunchConfig.baseScreen == "settings", !model.path.contains(.settings) { model.openSettings() }
        }
        .onChange(of: tab) { _, t in
            model.select(t == .home ? .home : .library)
        }
    }
}

/// Shell column | handle | album column. Below 840 pt the album is pushed.
///
/// Routes (`model.path`: artist, playlist, settings, and albums opened from
/// a pushed page) push inside the album column's own NavigationStack — the
/// same destinations as PhoneShell — so the shell column stays put. Below
/// 840 pt there is one stack and everything pushes over the shell.
private struct PadColumns: View {
    @Environment(AppModel.self) private var model
    var npNamespace: Namespace.ID
    /// Only the selected tab's stack drives `model.path`: TabView keeps the
    /// other tabs alive, and two stacks bound to one path would both push.
    var active: Bool

    var body: some View {
        // Yoin's own panel column instead of `.inspector`: on iPadOS 26.5 the
        // inspector inside a sidebar-adaptable TabView showed up at launch with
        // its binding still false, and in narrow windows it turns into a
        // sheet, where Yoin's rule is "no room for a panel → Full".
        GeometryReader { geo in
            let panelWidth = min(max(geo.size.width * 0.5, 360), 420)
            HStack(spacing: 0) {
                columns
                if model.npPanel {
                    NowPlayingView(npNamespace: npNamespace, panel: true)
                        .frame(width: panelWidth)
                        .clipShape(.rect(cornerRadius: 28))
                        .padding([.vertical, .trailing], 8)
                        .transition(.move(edge: .trailing).combined(with: .opacity))
                }
            }
            .background(Yoin.surface)
        }
    }

    private var columns: some View {
        GeometryReader { geo in
            let w = geo.size.width
            if w >= 840, let album = model.padSelection {
                // resolvePaneBudget: shell share 0.45, ≥ 600 when w ≥ 1080, both ≥ 360.
                let shell = max(360, w >= 1080 ? max(600, w * 0.45) : w * 0.45)
                HStack(spacing: 0) {
                    // Outside a NavigationStack the floating top tab bar adds
                    // no safe area, so clear it by hand.
                    SectionContent()
                        .safeAreaPadding(.top, 52)
                        .frame(width: shell)
                    Capsule()
                        .fill(Yoin.outline)
                        .frame(width: 4, height: 48)
                        .frame(width: 12)
                        .frame(maxHeight: .infinity)
                        .background(Yoin.surface)
                    NavigationStack(path: path) {
                        AlbumDetailView(album: album, padColumn: true)
                            .id(album.id)
                            .transition(.opacity.combined(with: .scale(scale: 0.98)))
                            .navigationDestination(for: Route.self, destination: destination)
                    }
                    .clipShape(.rect(cornerRadius: 28))
                    .padding(.vertical, 8)
                    .padding(.trailing, 8)
                }
                .background(Yoin.surface)
            } else {
                NavigationStack(path: path) {
                    SectionContent()
                        .toolbar(.hidden, for: .navigationBar)
                        .navigationDestination(for: Route.self, destination: destination)
                }
                .onChange(of: model.padSelection) { _, album in
                    // No album column: an album picked in the shell pushes.
                    guard active, let album, model.path.last != .album(album) else { return }
                    model.path.append(.album(album))
                }
            }
        }
        .onGeometryChange(for: Bool.self) { $0.size.width >= 840 } action: { wide = $0 }
        .onChange(of: model.padSelection) { _, _ in
            // A new album in the column replaces whatever was pushed on it.
            guard active, wide else { return }
            model.path.removeAll()
        }
    }

    @State private var wide = false

    private var path: Binding<[Route]> {
        active ? Binding(get: { model.path }, set: { model.path = $0 }) : .constant([])
    }

    /// PhoneShell's destinations, in the column.
    @ViewBuilder
    private func destination(_ route: Route) -> some View {
        switch route {
        case .album(let album):
            AlbumDetailView(album: album)
        case .artist(let ref):
            ArtistDetailView(ref: ref)
        case .playlist(let id):
            let info = model.playlistInfo(id)
            AlbumDetailView(album: info.asAlbum, playlist: info)
                .task(id: "\(model.store.generation)|\(id)") { await model.store.loadPlaylist(id) }
        case .settings:
            ServerSettingsView()
        }
    }
}

private struct PadMiniPlayer: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let p = model.playback
        Button { model.openNowPlayingOnPad() } label: {
            HStack(spacing: 10) {
                CoverArt(palette: p.album.palette, cornerRadius: 5, seed: p.album.seed)
                    .frame(width: 32, height: 32)
                VStack(alignment: .leading, spacing: 0) {
                    Text(p.track.title).font(.gsf(14, .semibold))
                    Text(p.album.artist).font(.gsf(12)).foregroundStyle(Yoin.inkVariant)
                }
                .lineLimit(1)
                Spacer(minLength: 8)
                PlaybackPosition { position, duration in
                    WavyProgressBar(progress: position / duration,
                                    duration: duration, isPlaying: p.isPlaying,
                                    tint: p.album.palette.tint, trackTint: Yoin.inkVariant.opacity(0.3)) { fraction in
                        model.player.seek(to: fraction * duration)
                    }
                }
                    .frame(width: 120)
                YoinIcon("pause_filled", size: 22)
                    .padding(.leading, 6)
            }
            .foregroundStyle(Yoin.ink)
            .padding(.horizontal, 12)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
    }
}
