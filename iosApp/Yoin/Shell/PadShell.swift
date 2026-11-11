import SwiftUI

/// iPad's navigation (51 2026-11-11): no system tab bar, no sidebar. Both columns run to the top edge
/// under the status bar (a soft band, as on the phone), and Yoin's own two pieces of floating glass
/// carry the navigation: the nav capsule (Home, Library, Search) and the phone's mini player. Where they
/// sit is `PadNavStyle` (A: one row at the bottom centre; B: the two bottom corners), switchable in
/// Yoin Labs. Albums open in a right-hand column of the same window, Now Playing as a panel beside the
/// columns (or Full when the window is too narrow).
struct PadShell: View {
    @Environment(AppModel.self) private var model
    @AppStorage(PadNavStyle.key) private var styleRaw = PadNavStyle.b.rawValue
    @Namespace private var npNS
    enum PadTab: Hashable { case sections, search }
    private var tab: PadTab { model.searchActive ? .search : .sections }

    private var style: PadNavStyle { PadNavStyle(rawValue: styleRaw) ?? .b }

    var body: some View {
        GeometryReader { geo in
            let panelWidth = min(max(geo.size.width * 0.5, 360), 420)
            HStack(spacing: 0) {
                // A TabView with its bar hidden, not a ZStack: each place is hosted on its own (a
                // NavigationStack under the shell's recede layers, with no TabView between, never pushed),
                // and kept alive between visits as tabs are. Yoin's own dock is the navigation.
                TabView(selection: Binding(get: { tab }, set: { _ in })) {
                    Tab(value: PadTab.sections) {
                        PadColumns(npNamespace: npNS, active: !model.searchActive, style: style)
                            .toolbarVisibility(.hidden, for: .tabBar)
                    }
                    Tab(value: PadTab.search) {
                        // Search's own surface in the shell column (Android Wide: results on the left,
                        // what they open in the detail column).
                        PadColumns(npNamespace: npNS, active: model.searchActive, onCloseSearch: closeSearch, style: style)
                            .toolbarVisibility(.hidden, for: .tabBar)
                    }
                }
                .tabViewStyle(.tabBarOnly)
                .overlay { PadDock(style: style) }
                if model.npPanel {
                    // Inside the shell's plane: it recedes with the shell.
                    NowPlayingView(npNamespace: npNS, panel: true)
                        .frame(width: panelWidth)
                        .clipShape(.rect(cornerRadius: 28))
                        .padding([.vertical, .trailing], 8)
                        .transition(.move(edge: .trailing).combined(with: .opacity))
                }
            }
            .background(Yoin.surface.ignoresSafeArea())
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
            // `-screen settings`: the sign-in page in the album column.
            if LaunchConfig.baseScreen == "settings", !model.path.contains(.settings) { model.openSettings() }
        }
    }

    /// Search's ✕ on iPad: back to the section it was opened from.
    private func closeSearch() {
        withAnimation(Yoin.spatial) { model.searchActive = false }
    }
}

/// Where iPad's two pieces of floating glass sit (51 2026-11-11, prototype artifact 5XRNjtNy…). A Yoin
/// Labs choice while 51 compares them; `-ipadNav A|B` at launch for screenshots.
enum PadNavStyle: String, CaseIterable, Identifiable {
    /// One row, nav then player, centred on the window's bottom edge (Android tablets' place).
    case a = "A"
    /// The nav capsule in the bottom-leading corner, the player in the bottom-trailing one; in a split
    /// the nav belongs to the shell column and the player to the detail column.
    case b = "B"

    static let key = "ipadNav"
    var id: String { rawValue }
    var label: LocalizedStringKey {
        switch self {
        case .a: "A Bottom row"
        case .b: "B Two corners"
        }
    }
}

/// The floating pieces' sizes, shared by the dock and the columns that keep their content clear of it.
enum PadDockMetrics {
    /// Glass height (the phone bar's).
    static let height: CGFloat = 60
    /// From the window's bottom edge.
    static let bottom: CGFloat = 22
    /// From the window's side edges (B).
    static let side: CGFloat = 24
    /// Between the two pieces (A).
    static let gap: CGFloat = 12
    static let playerWidth: CGFloat = 320
    /// The Play split's height.
    static let playHeight: CGFloat = 56
    /// The Play split's bottom when it shares the dock's row: centred on the glass.
    static var playInRow: CGFloat { bottom + (height - playHeight) / 2 }
    /// The Play split's bottom when the dock's row is taken: one gap above it.
    static var playLifted: CGFloat { bottom + height + gap }
    /// What a root page reserves under its last row, on top of the phone's bar clearance (which is
    /// already in SectionContent): the dock sits a little higher than the phone bar.
    static let extraShellClearance: CGFloat = 16
}

/// iPad's floating glass: the nav capsule and the mini player (ShellBar's own pieces, so the selection,
/// colours, wave wash, long presses and the failure banner are the phone's), placed by `PadNavStyle`.
/// Laid over the columns, out of the bottom safe area (the window's edge is the measure).
private struct PadDock: View {
    @Environment(AppModel.self) private var model
    var style: PadNavStyle
    @Namespace private var npNS

    /// The player is hidden while Now Playing is up as a panel beside the columns (it would be twice).
    private var showsPlayer: Bool { !model.isIdle && !model.npPanel }

    var body: some View {
        Group {
            switch style {
            case .a:
                HStack(spacing: PadDockMetrics.gap) {
                    ShellBar(npNamespace: npNS, pad: .nav).fixedSize()
                    if showsPlayer {
                        ShellBar(npNamespace: npNS, pad: .player).fixedSize()
                            .transition(.move(edge: .trailing).combined(with: .opacity))
                    }
                }
                .frame(maxWidth: .infinity, alignment: .center)
            case .b:
                HStack(spacing: 0) {
                    ShellBar(npNamespace: npNS, pad: .nav).fixedSize()
                    Spacer(minLength: PadDockMetrics.gap)
                    if showsPlayer {
                        ShellBar(npNamespace: npNS, pad: .player).fixedSize()
                            .transition(.move(edge: .trailing).combined(with: .opacity))
                    }
                }
                .padding(.horizontal, PadDockMetrics.side)
            }
        }
        .animation(Yoin.spatial, value: showsPlayer)
        .animation(Yoin.spatial, value: style)
        .padding(.bottom, PadDockMetrics.bottom)
        // Memories (pulled down over Home) takes the whole window, as on the phone: the dock leaves on
        // the pull itself.
        .modifier(MemoriesBarRetreat(presentation: model.memories,
                                     active: model.section == .home && !model.searchActive))
        .frame(maxHeight: .infinity, alignment: .bottom)
        .ignoresSafeArea(.container, edges: .bottom)
        .ignoresSafeArea(.keyboard)
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
    var style: PadNavStyle = .b

    var body: some View {
        columns
    }

    private var columns: some View {
        GeometryReader { geo in
            let w = geo.size.width
            if w >= 840, model.padSelection != nil || !model.path.isEmpty {
                // resolvePaneBudget: shell share 0.45, ≥ 600 when w ≥ 1080, both ≥ 360.
                let shell = max(360, w >= 1080 ? max(600, w * 0.45) : w * 0.45)
                let detail = w - shell - Self.handleWidth
                HStack(spacing: 0) {
                    // Both columns run to the top edge; the feed scrolls up under the status bar with the
                    // phone's soft band over it (51 2026-11-11: no strip, no bar on top).
                    shellContent
                        .safeAreaPadding(.bottom, PadDockMetrics.extraShellClearance)
                        .overlay(alignment: .top) { StatusEdgeBand() }
                        .frame(width: shell)
                    Capsule()
                        .fill(Yoin.outline)
                        .frame(width: 4, height: 48)
                        .frame(width: Self.handleWidth)
                        .frame(maxHeight: .infinity)
                        .background(Yoin.surface.ignoresSafeArea())
                    // The column is as tall as the shell beside it, edge to edge (no inset card): the
                    // album's colour starts at the screen's top; its page keeps its own content below the
                    // status bar.
                    detailColumn(playBottom: playBottom(detailWidth: detail))
                        .environment(\.inPadColumn, true)
                        .ignoresSafeArea(.container, edges: [.top, .bottom])
                }
                .background(Yoin.surface.ignoresSafeArea())
            } else {
                NavigationStack(path: path) {
                    shellContent
                        .safeAreaPadding(.bottom, PadDockMetrics.extraShellClearance)
                        .toolbar(.hidden, for: .navigationBar)
                        .navigationDestination(for: Route.self, destination: pushedDestination)
                }
                // The root pages' soft band under the status bar (a pushed page has its own top).
                .overlay(alignment: .top) { if model.path.isEmpty { StatusEdgeBand() } }
                // A pushed detail page gets the Play split, over the dock's row (the dock spans the window).
                .overlay(alignment: .bottomLeading) {
                    if active && !model.path.isEmpty {
                        PadDetailPlay(root: nil)
                            .padding(.leading, PadDockMetrics.side)
                            .padding(.bottom, PadDockMetrics.playLifted)
                            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
                            .ignoresSafeArea(.container, edges: .bottom)
                            .ignoresSafeArea(.keyboard)
                    }
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
    static let handleWidth: CGFloat = 12

    /// Where the detail column's Play split sits, from the window's bottom edge. A: the dock's row is
    /// centred on the window and crosses the column, so Play goes above it. B: the player is the column's
    /// trailing corner, so Play shares its row at the leading corner, unless the column is too narrow for
    /// both (then it goes above).
    private func playBottom(detailWidth: CGFloat) -> CGFloat {
        switch style {
        case .a: return PadDockMetrics.playLifted
        case .b:
            let row = PadDockMetrics.side + Tokens.bar.SPLIT_WIDTH.cg + PadDockMetrics.gap
                + PadDockMetrics.playerWidth + PadDockMetrics.side
            let playerShown = !model.isIdle && !model.npPanel
            return !playerShown || detailWidth >= row ? PadDockMetrics.playInRow : PadDockMetrics.playLifted
        }
    }

    /// What a detail page keeps free under its last row for a Play split whose bottom is `playBottom`
    /// from the window's edge (the page's own bottom safe area is already the home indicator's).
    private func clearance(above playBottom: CGFloat) -> CGFloat {
        playBottom + PadDockMetrics.playHeight + 12 - Self.homeIndicator
    }

    /// iPad's bottom safe area (the home indicator), which a page's bottom inset already holds.
    static let homeIndicator: CGFloat = 20

    /// The wide window's detail column (Android: every detail page is the
    /// shell's right column, docs/adaptive-principles.md): the album picked
    /// into it, else the first page opened from the shell (an artist, a
    /// playlist, Settings), whose back closes the column. Later pages push
    /// inside it.
    @ViewBuilder
    private func detailColumn(playBottom: CGFloat) -> some View {
        let clear = clearance(above: playBottom)
        if let album = model.padSelection {
            NavigationStack(path: path) {
                AlbumDetailView(album: album, padColumn: true)
                    // The column's Play split sits over its foot: the page lays out above it, so the
                    // titles and the list never run under the button (a short landscape column did).
                    .safeAreaPadding(.bottom, clear)
                    .id(album.id)
                    .transition(.opacity.combined(with: .scale(scale: 0.98)))
                    .navigationDestination(for: Route.self) { destination($0).safeAreaPadding(.bottom, clear) }
            }
            // The column's own Play split (the phone's detail bar
            // lives in its ShellBar, which iPad doesn't have).
            .overlay(alignment: .bottomLeading) {
                if active { playSplit(PadDetailPlay(root: album), bottom: playBottom) }
            }
        } else if let first = model.path.first {
            NavigationStack(path: tailPath) {
                destination(first)
                    .safeAreaPadding(.bottom, clear)
                    .id(first)
                    .transition(.opacity.combined(with: .scale(scale: 0.98)))
                    .navigationDestination(for: Route.self) { destination($0).safeAreaPadding(.bottom, clear) }
            }
            .overlay(alignment: .bottomLeading) {
                if active { playSplit(PadDetailPlay(root: nil), bottom: playBottom) }
            }
        }
    }

    /// The Play split at the column's leading corner, `bottom` from the window's edge (the column already
    /// reaches it).
    private func playSplit(_ play: PadDetailPlay, bottom: CGFloat) -> some View {
        play
            .padding(.leading, PadDockMetrics.side)
            .padding(.bottom, bottom)
            .animation(Yoin.spatial, value: bottom)
            // Stays at the column's foot under the keyboard (Search's field), as the dock does: a full
            // frame that reaches past the keyboard's inset, not the split alone.
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
            .ignoresSafeArea(.keyboard)
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

    /// A page pushed over the shell (no detail column): it keeps its last row clear of the Play split,
    /// which sits over the dock's row.
    private func pushedDestination(_ route: Route) -> some View {
        destination(route).safeAreaPadding(.bottom, clearance(above: PadDockMetrics.playLifted))
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
        case .tips(let focus):
            TipsView(focus: focus)
        case .allMemories:
            AllMemoriesView()
        case .labs:
            LabsView()
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
    /// The page's public link, for the ▾ menu's Share and Open in.
    @State private var webLink = DetailWebLinkState()

    private var detailTarget: DetailLinkTarget? { model.detailLinkTarget(root: root) }

    private var album: Album? {
        switch model.path.last {
        case .album(let a): return a
        case .artist(let ref): return model.artistPage(ref).releases.first
        case .playlist(let id): return model.playlistInfo(id).asAlbum
        case .settings, .tips, .allMemories, .labs: return nil
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
                        Button("Play next") { Task { model.enqueue(await entries(), next: true) { ok in if !ok { PlaylistActions.shared.show(String(localized: "Couldn't add to queue")) } } } }
                        Button("Add to queue") { Task { model.enqueue(await entries(), next: false) { ok in if !ok { PlaylistActions.shared.show(String(localized: "Couldn't add to queue")) } } } }
                        if PlaylistActions.shared.canAddToPlaylist(model.store) {
                            Button("Add to playlist") { Task { PlaylistActions.shared.requestAdd(await entries()) } }
                        }
                        if model.path.isEmpty || model.path.last == .album(album) {
                            AlbumLibraryMenuButton(album: album)
                        }
                        // Android OfflineToggle, as the phone's ▾ has it: the column's root album included.
                        if let c = model.detailOfflineCollection, c.kind == .album {
                            OfflineMenuButton(collection: c, entries: { await entries() })
                        }
                        DetailLinkMenuItems(target: detailTarget, link: webLink)
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
            .frame(width: Tokens.bar.SPLIT_WIDTH.cg, height: PadDockMetrics.playHeight)
            .transition(.opacity.animation(Yoin.effects))
            .task(id: album.id) { await AlbumLibraryState.shared.load(album, store: model.store) }
            .task(id: detailTarget) { await webLink.load(detailTarget, apple: model.store.apple) }
        }
    }
}
