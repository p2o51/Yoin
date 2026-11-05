import SwiftUI

/// iPad first take (option A in docs/ipad.md): iPadOS 26 top glass tab bar
/// that adapts to a sidebar, the mini player as the tab view's bottom
/// accessory, albums in a right-hand column of the same window, and Now
/// Playing as an inspector panel (or Full when the window is too narrow).
struct PadShell: View {
    @Environment(AppModel.self) private var model
    @Namespace private var npNS
    @State private var tab: PadTab = .home
    /// The tab Search was opened from, for its ✕.
    @State private var lastTab: PadTab = .home

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
                // Search's own surface in the shell column (Android Wide:
                // results on the left, what they open in the detail column).
                PadColumns(npNamespace: npNS, active: tab == .search, onCloseSearch: closeSearch)
            }
        }
        .tabViewStyle(.sidebarAdaptable)
        .tabViewBottomAccessory {
            PadMiniPlayer()
        }
        // Library's search pill (and any other entry) lands on the Search tab.
        .onChange(of: model.searchActive) { _, active in
            if active && tab != .search { tab = .search }
        }
        // World depth, as on the phone: the shell steps back under Full Now
        // Playing and under a sheet (the NP panel is part of its plane);
        // Full Now Playing under a sheet it opens.
        .modifier(WorldRecede(layer: .padShell))
        .overlay {
            if model.npExpanded {
                NowPlayingView(npNamespace: npNS)
                    .modifier(WorldRecede(layer: .nowPlaying))
                    .transition(.opacity.combined(with: .scale(scale: 0.96)))
            }
        }
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { model.padWidth = $0 }
        .onAppear {
            tab = model.searchActive ? .search : model.section == .library ? .library : .home
            // `-screen settings`: the sign-in page in the album column.
            if LaunchConfig.baseScreen == "settings", !model.path.contains(.settings) { model.openSettings() }
        }
        .onChange(of: tab) { _, t in
            if t == .search {
                // The field is up at once (no bar circle to grow out of here).
                model.searchOrigin = .library
                if !model.searchActive { model.searchActive = true }
            } else {
                lastTab = t
                if model.searchActive { model.searchActive = false }
                model.select(t == .home ? .home : .library)
            }
        }
    }

    /// Search's ✕ on iPad: back to the tab it was opened from.
    private func closeSearch() {
        model.searchActive = false
        tab = lastTab
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
    /// The Search tab: Search's surface in place of the section, its ✕ here.
    var onCloseSearch: (() -> Void)? = nil

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
                    // Inside the shell's plane: it recedes with the shell.
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
            if w >= 840, model.padSelection != nil || !model.path.isEmpty {
                // resolvePaneBudget: shell share 0.45, ≥ 600 when w ≥ 1080, both ≥ 360.
                let shell = max(360, w >= 1080 ? max(600, w * 0.45) : w * 0.45)
                HStack(spacing: 0) {
                    // Outside a NavigationStack the floating top tab bar adds
                    // no safe area, so clear it by hand.
                    shellContent
                        .safeAreaPadding(.top, 52)
                        .frame(width: shell)
                    Capsule()
                        .fill(Yoin.outline)
                        .frame(width: 4, height: 48)
                        .frame(width: 12)
                        .frame(maxHeight: .infinity)
                        .background(Yoin.surface)
                    detailColumn
                        .clipShape(.rect(cornerRadius: 28))
                        .padding(.vertical, 8)
                        .padding(.trailing, 8)
                }
                .background(Yoin.surface)
            } else {
                NavigationStack(path: path) {
                    shellContent
                        .toolbar(.hidden, for: .navigationBar)
                        .navigationDestination(for: Route.self, destination: destination)
                }
                // A pushed detail page gets the Play split; the shell doesn't.
                .overlay(alignment: .bottomLeading) {
                    if active && !model.path.isEmpty { PadDetailPlay(root: nil) }
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

    /// The wide window's detail column (Android: every detail page is the
    /// shell's right column, docs/adaptive-principles.md): the album picked
    /// into it, else the first page opened from the shell (an artist, a
    /// playlist, Settings), whose back closes the column. Later pages push
    /// inside it.
    @ViewBuilder
    private var detailColumn: some View {
        if let album = model.padSelection {
            NavigationStack(path: path) {
                AlbumDetailView(album: album, padColumn: true)
                    .id(album.id)
                    .transition(.opacity.combined(with: .scale(scale: 0.98)))
                    .navigationDestination(for: Route.self, destination: destination)
            }
            // The column's own Play split (the phone's detail bar
            // lives in its ShellBar, which iPad doesn't have).
            .overlay(alignment: .bottomLeading) {
                if active { PadDetailPlay(root: album) }
            }
        } else if let first = model.path.first {
            NavigationStack(path: tailPath) {
                destination(first)
                    .id(first)
                    .transition(.opacity.combined(with: .scale(scale: 0.98)))
                    .navigationDestination(for: Route.self, destination: destination)
            }
            .overlay(alignment: .bottomLeading) {
                if active { PadDetailPlay(root: nil) }
            }
        }
    }

    /// The column's stack above its root page (`model.path` after the first).
    private var tailPath: Binding<[Route]> {
        guard active else { return .constant([]) }
        return Binding(get: { Array(model.path.dropFirst()) },
                       set: { tail in model.path = Array(model.path.prefix(1)) + tail })
    }

    @ViewBuilder
    private var shellContent: some View {
        if let onCloseSearch {
            SearchView(npNamespace: npNamespace, onClose: onCloseSearch)
        } else {
            SectionContent()
        }
    }

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

/// The detail column's Play split, after the phone's detail bar (ShellBar
/// `playSplit`, Android detail pose): [Play][▾] in the page's cover primary,
/// M3 SplitButton corners. Acts on the page on top of the column: a pushed
/// route, else the album picked into the column ([root]).
private struct PadDetailPlay: View {
    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var colorScheme
    var root: Album?

    private var album: Album? {
        switch model.path.last {
        case .album(let a): return a
        case .artist(let ref): return model.artistPage(ref).releases.first
        case .playlist(let id): return model.playlistInfo(id).asAlbum
        case .settings: return nil
        case nil: return root
        }
    }

    private func entries() async -> [PlaylistEntry] {
        guard model.path.isEmpty, let root else { return await model.detailEntries() }
        let full = await model.store.fullAlbum(model.resolved(root))
        return full.tracks.map { PlaylistEntry(album: full, track: $0) }
    }

    var body: some View {
        if let album {
            let s = CoverScheme(album: album, dark: colorScheme == .dark)
            let tint = s.primary.opacity(0.85)
            let inner = Tokens.bar.SPLIT_INNER_CORNER.cg
            GlassEffectContainer(spacing: 0) {
                HStack(spacing: Tokens.bar.SPLIT_GAP.cg) {
                    Button { model.play(loading: { await entries() }) } label: {
                        HStack(spacing: 8) {
                            YoinIcon("play_filled", size: 20)
                            Text("Play").font(.gsf(16, .medium))
                        }
                        .foregroundStyle(s.onPrimary)
                        .padding(.horizontal, 16)
                        .frame(maxWidth: .infinity)
                        .frame(height: 56)
                        .contentShape(.rect)
                    }
                    .buttonStyle(.plain)
                    .glassEffect(.regular.tint(tint).interactive(),
                                 in: UnevenRoundedRectangle(topLeadingRadius: 28, bottomLeadingRadius: 28,
                                                            bottomTrailingRadius: inner, topTrailingRadius: inner))
                    Menu {
                        Button("Shuffle play") { model.play(loading: { await entries() }, shuffle: true) }
                        Button("Play next") { Task { model.enqueue(await entries(), next: true) } }
                        Button("Add to queue") { Task { model.enqueue(await entries(), next: false) } }
                        if PlaylistActions.shared.canAddToPlaylist(model.store) {
                            Button("Add to playlist") { Task { PlaylistActions.shared.requestAdd(await entries()) } }
                        }
                        // Android OfflineToggle, as the phone's ▾ has it: the column's root album included.
                        if let c = model.detailOfflineCollection, c.kind == .album {
                            OfflineMenuButton(collection: c, entries: { await entries() })
                        }
                        if model.path.isEmpty || model.path.last == .album(album) {
                            Divider()
                            Button("Go to artist") { model.openArtist(album.artist, id: album.artistId) }
                        }
                    } label: {
                        YoinIcon("chevron_down", size: 22)
                            .foregroundStyle(s.onPrimary)
                            .padding(.horizontal, 14)
                            .frame(height: 56)
                            .contentShape(.rect)
                    }
                    .buttonStyle(.plain)
                    .glassEffect(.regular.tint(tint).interactive(),
                                 in: UnevenRoundedRectangle(topLeadingRadius: inner, bottomLeadingRadius: inner,
                                                            bottomTrailingRadius: 28, topTrailingRadius: 28))
                    .accessibilityLabel("More play options")
                }
            }
            .frame(width: Tokens.bar.SPLIT_WIDTH.cg, height: 56)
            .padding(16)
            .transition(.opacity.animation(Yoin.effects))
        }
    }
}

/// The tab view's bottom accessory, after Android's NowPlayingPill: nothing
/// playing = the quiet "Nothing playing" form; playing / paused = cover,
/// title over artist, the wave progress and the real transport state. The
/// accessory opens Now Playing; the play / pause glyph is its own button.
private struct PadMiniPlayer: View {
    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        let p = model.playback
        HStack(spacing: 10) {
            Button { model.openNowPlayingOnPad() } label: {
                HStack(spacing: 10) {
                    if model.isIdle {
                        Text("Nothing playing").font(.gsf(14, .medium)).foregroundStyle(Yoin.inkVariant)
                            .transition(.opacity.animation(Yoin.effects))
                        Spacer(minLength: 0)
                    } else {
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
                    }
                }
                .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .disabled(model.isIdle)
            if !model.isIdle {
                Button {
                    if model.player.currentTrack != nil { model.player.togglePlayPause() }
                    else { model.playback.isPlaying.toggle() }
                } label: {
                    YoinIcon(p.isPlaying ? "pause_filled" : "play_filled", size: 22)
                        .contentTransition(.symbolEffect(.replace))
                        .frame(width: 44, height: 44)
                        .contentShape(.circle)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(p.isPlaying ? "Pause" : "Play")
            }
        }
        .foregroundStyle(Yoin.ink)
        .padding(.horizontal, 12)
        // A held playback failure (Codex A39): its explicit Retry.
        .overlay(alignment: .trailing) {
            // Inside the accessory (it clips): the Retry beside the transport.
            if model.player.failure != nil {
                Button { model.player.retry() } label: {
                    Text("Retry").yoinRole(Tokens.type.labelLarge)
                        .foregroundStyle(CoverScheme.brand(dark: colorScheme == .dark).onError)
                        .padding(.horizontal, 16).frame(minHeight: 36)
                        .background(CoverScheme.brand(dark: colorScheme == .dark).error, in: .capsule)
                }
                .buttonStyle(PressScale())
                .padding(.trailing, 60)
                .accessibilityLabel("Retry playback")
                .transition(.opacity)
            }
        }
        .animation(Yoin.spatial, value: model.isIdle)
        .animation(Yoin.effects, value: model.player.failure)
    }
}
