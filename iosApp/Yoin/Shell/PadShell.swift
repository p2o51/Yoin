import SwiftUI

/// iPad's navigation (51 2026-11-11): no system tab bar, no sidebar. Both columns run to the top edge
/// under the status bar (a soft band, as on the phone), and Yoin's own two pieces of floating glass
/// carry the navigation: the nav capsule (Home, Library, Search) in the bottom-leading corner and the
/// phone's mini player in the bottom-trailing one (51 2026-11-11 chose this "two corners" placement over
/// one centred row). Albums open in a right-hand column of the same window, Now Playing as a panel beside the
/// columns (or Full when the window is too narrow).
struct PadShell: View {
    @Environment(AppModel.self) private var model
    @Namespace private var npNS
    /// Search's surface is built on its first visit and kept (its query and results).
    @State private var searchBuilt = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion


    var body: some View {
        GeometryReader { geo in
            let panelWidth = min(max(geo.size.width * 0.5, 360), 420)
            HStack(spacing: 0) {
                // One tab of a TabView with its bar hidden: the tab hosts the places on its own (a
                // NavigationStack under the shell's recede layers, with no TabView between, never pushed).
                // Inside it the sections and Search cross-fade (Android: Search rises over the shell), each
                // kept alive between visits. Yoin's own dock is the navigation.
                TabView {
                    Tab {
                        ZStack {
                            PadColumns(npNamespace: npNS, active: !model.searchActive)
                                .opacity(model.searchActive ? 0 : 1)
                                .allowsHitTesting(!model.searchActive)
                                .accessibilityHidden(model.searchActive)
                            if searchBuilt {
                                // Search's own surface in the shell column (Android Wide: results on the left,
                                // what they open in the detail column).
                                PadColumns(npNamespace: npNS, active: model.searchActive, onCloseSearch: closeSearch)
                                    .opacity(model.searchActive ? 1 : 0)
                                    .scaleEffect(model.searchActive || reduceMotion ? 1 : 0.98)
                                    .allowsHitTesting(model.searchActive)
                                    .accessibilityHidden(!model.searchActive)
                            }
                        }
                        .animation(Yoin.effects, value: model.searchActive)
                        .toolbarVisibility(.hidden, for: .tabBar)
                    }
                }
                .tabViewStyle(.tabBarOnly)
                .onChange(of: model.searchActive, initial: true) { _, on in if on { searchBuilt = true } }
                .overlay { PadDock() }
                if model.npPanel {
                    // Inside the shell's plane: it recedes with the shell.
                    NowPlayingView(npNamespace: npNS, panel: true)
                        .frame(width: panelWidth)
                        .clipShape(.rect(cornerRadius: 28))
                        .padding([.vertical, .trailing], 8)
                        // Where Full grows out of when the panel goes Full (and folds back into).
                        .onGeometryChange(for: CGRect.self) { $0.frame(in: .global).insetBy(dx: 0, dy: 0) } action: { r in
                            model.np.panelFrame = r.inset(by: UIEdgeInsets(top: 8, left: 0, bottom: 8, right: 8))
                        }
                        // Android OverlayPlayerVisibility: in and out by its own width, no fade, on the
                        // spring the columns give way on.
                        .transition(.move(edge: .trailing))
                }
            }
            .background(Yoin.surface.ignoresSafeArea())
        }
        // World depth, as on the phone: the shell steps back under Full Now
        // Playing and under a sheet (the NP panel is part of its plane);
        // Full Now Playing under a sheet it opens.
        .modifier(WorldRecede(layer: .shell))
        .overlay {
            // Full Now Playing grows out of the mini player in the bottom-trailing corner and folds back
            // into it (Android #190 on a tablet), on the phone's one progress: ⌄, the drag and back all move it.
            if model.np.mounted {
                NowPlayingMorph(npNamespace: npNS)
                    .modifier(WorldRecede(layer: .nowPlaying))
                    .transition(.identity)
            }
        }
        .onChange(of: model.npExpanded) { _, open in
            // Full opened from the panel goes back to the panel (Android's Full → Panel → Closed), whatever
            // closed it (⌄, the drag, back): the panel is put back under the page, which folds into it.
            if !open, model.np.fromPanel, !model.npPanel, model.padPanelFits {
                var t = Transaction(); t.disablesAnimations = true
                withTransaction(t) { model.npPanel = true }
            }
            model.np.show(open)
        }
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { model.padWidth = $0 }
        // A column opening beside the panel where the two columns no longer fit: the page stays visible and
        // the panel steps away (Android: "the page is always visible").
        .onChange(of: model.padColumnOpen) { _, open in
            if open, model.npPanel, !model.padPanelFits { withAnimation(Yoin.spatial) { model.npPanel = false } }
        }
        .onAppear {
            if model.npExpanded { model.np.snapOpen() }
            // `-screen settings`: the sign-in page in the album column.
            if LaunchConfig.baseScreen == "settings", !model.path.contains(.settings) { model.openSettings() }
        }
    }

    /// Search's ✕ on iPad: back to the section it was opened from.
    private func closeSearch() {
        withAnimation(Yoin.spatial) { model.searchActive = false }
    }
}

/// The floating pieces' sizes, shared by the dock and the columns that keep their content clear of it.
enum PadDockMetrics {
    /// Glass height (the phone bar's).
    static let height: CGFloat = 60
    /// From the window's bottom edge.
    static let bottom: CGFloat = 22
    /// From the window's side edges.
    static let side: CGFloat = 24
    /// Between a Play split and the player sharing their row.
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
/// colours, wave wash, long presses and the failure banner are the phone's), in the window's two bottom
/// corners.
/// Laid over the columns, out of the bottom safe area (the window's edge is the measure).
private struct PadDock: View {
    @Environment(AppModel.self) private var model
    @Namespace private var npNS

    /// The player is hidden while Now Playing is up as a panel beside the columns (it would be twice).
    private var showsPlayer: Bool { !model.isIdle && !model.npPanel }

    var body: some View {
        HStack(spacing: 0) {
            ShellBar(npNamespace: npNS, pad: .nav).fixedSize()
            Spacer(minLength: PadDockMetrics.gap)
            if showsPlayer {
                ShellBar(npNamespace: npNS, pad: .player).fixedSize()
                    .transition(.move(edge: .trailing).combined(with: .opacity))
            }
        }
        .padding(.horizontal, PadDockMetrics.side)
        .animation(Yoin.spatial, value: showsPlayer)
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

    var body: some View {
        columns
    }

    private var columns: some View {
        GeometryReader { geo in
            let w = geo.size.width
            // Whether pages open in a column is the window's (Android hasDetailPane: a Wide window), not
            // the space left beside the Now Playing panel: beside the panel the two columns share what is
            // left, each at least a phone column (the panel only opens there when 744 is left).
            if model.padWidth >= 840 {
                // Settings in two panes takes the whole window over the columns (Android's Settings
                // list-detail, I27); the columns keep their pages under it.
                ZStack {
                    wideColumns(w: w)
                        .accessibilityHidden(settingsIndex != nil)
                    if let settingsIndex {
                        SettingsSplitView(tail: settingsTail(settingsIndex)) { AnyView(destination($0)) }
                            .background(Yoin.surface.ignoresSafeArea())
                            .transition(.move(edge: .trailing))
                    }
                }
                .animation(Yoin.spatial, value: settingsIndex != nil)
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
        .onChange(of: model.padWidth >= 840, initial: true) { _, now in wide = now }
        .onChange(of: model.padSelection) { _, _ in
            // A new album in the column replaces whatever was pushed on it.
            guard active, wide else { return }
            model.path.removeAll()
        }
        .onChange(of: columnWanted, initial: true) { _, want in
            // Android DetailPaneState: the column opens and closes on the spatial spring; the shell beside
            // it gives up (and takes back) its width on the same spring. The tree stays put either way, so
            // the feed keeps its place.
            // Android DetailPaneState: the shell takes its column width on the tap's frame (its sections
            // re-tier once, before the spring) and the column slides in with its page; closing, the column
            // slides out first and the shell takes its width back as it lands.
            if want { shownColumn = columnContent }
            withAnimation(Yoin.spatial) { columnOpen = want ? 1 : 0 } completion: {
                if !columnWanted { shownColumn = nil }
            }
        }
        .onChange(of: columnContent) { _, now in if columnWanted, let now { shownColumn = now } }
    }

    /// What the detail column holds: the album picked into it, else the first page opened from the shell.
    private enum ColumnContent: Equatable { case album(Album), route(Route) }

    private var columnContent: ColumnContent? {
        if let album = model.padSelection { return .album(album) }
        if let first = columnPath.first { return .route(first) }
        return nil
    }

    /// Where `.settings` sits in the path while Settings is up in two panes (a wide window).
    private var settingsIndex: Int? {
        guard active, model.settingsSplitShown else { return nil }
        return model.path.firstIndex(of: .settings)
    }

    /// The path the columns show: up to Settings while it is up in two panes (its pages are its own).
    private var columnPath: [Route] {
        guard let settingsIndex else { return model.path }
        return Array(model.path[..<settingsIndex])
    }

    /// The columns' path written back, Settings and what is on it kept.
    private func setColumnPath(_ new: [Route]) {
        if let settingsIndex { model.path = new + model.path[settingsIndex...] } else { model.path = new }
    }

    /// The two-pane Settings' page side: the routes pushed after `.settings`.
    private func settingsTail(_ index: Int) -> Binding<[Route]> {
        Binding(get: { Array(model.path.dropFirst(index + 1)) },
                set: { tail in model.path = Array(model.path.prefix(index + 1)) + tail })
    }

    private var columnWanted: Bool { active && columnContent != nil }

    /// 0 closed … 1 open, on the spatial spring.
    @State private var columnOpen: CGFloat = 0
    /// What the column shows: held through its closing spring, so it leaves as itself.
    @State private var shownColumn: ColumnContent?
    /// The handle's drag: the shell's width the finger left it at (Android PaneSplitState, 1:1, clamped,
    /// no snap); nil = the budget's own split.
    @State private var draggedShell: CGFloat?
    @State private var dragStartShell: CGFloat?

    /// resolvePaneBudget: shell share 0.45, ≥ 600 when w ≥ 1080, both ≥ 360.
    static func shellBudget(_ w: CGFloat) -> CGFloat { max(360, w >= 1080 ? max(600, w * 0.45) : w * 0.45) }

    private func clampShell(_ v: CGFloat, w: CGFloat) -> CGFloat {
        min(max(v, 360), w - Self.handleWidth - 360)
    }

    @ViewBuilder
    private func wideColumns(w: CGFloat) -> some View {
        let restShell = clampShell(draggedShell ?? Self.shellBudget(w), w: w)
        let detail = w - restShell - Self.handleWidth
        // The shell keeps its column width while a column is out (opening, open, or closing).
        let shell = shownColumn == nil ? w : restShell
        let slide = (1 - columnOpen) * (detail + Self.handleWidth)
        HStack(spacing: 0) {
            // Both columns run to the top edge; the feed scrolls up under the status bar with the
            // phone's soft band over it (51 2026-11-11: no strip, no bar on top).
            // Never pushes: in a wide window every page opens in the column.
            shellContent
                .safeAreaPadding(.bottom, PadDockMetrics.extraShellClearance)
                .overlay(alignment: .top) { StatusEdgeBand() }
                .frame(width: shell)
            handle(w: w, rest: restShell)
                .offset(x: slide)
            // The column is as tall as the shell beside it, edge to edge (no inset card): the album's colour
            // starts at the screen's top; its page keeps its own content below the status bar. It keeps its
            // resting width and slides in beside the shell as the shell gives way.
            Group {
                if let shownColumn {
                    detailColumn(shownColumn, playBottom: playBottom(detailWidth: detail))
                        .environment(\.inPadColumn, true)
                        .ignoresSafeArea(.container, edges: [.top, .bottom])
                } else {
                    Color.clear
                }
            }
            .frame(width: detail)
            .background(Yoin.surface.ignoresSafeArea())
            .offset(x: slide)
        }
        .frame(width: w, alignment: .leading)
        .background(Yoin.surface.ignoresSafeArea())
        .onChange(of: w) { _, _ in draggedShell = nil }
    }

    /// The split's handle (Android M3 drag handle): the finger moves it 1:1, clamped so neither column is
    /// narrower than a phone (360); it stays where it is let go.
    private func handle(w: CGFloat, rest: CGFloat) -> some View {
        Capsule()
            .fill(Yoin.outline)
            .frame(width: dragStartShell == nil ? 4 : 6, height: dragStartShell == nil ? 48 : 64)
            .animation(Yoin.spatial, value: dragStartShell == nil)
            // A pointer over the handle: the M3 hovered state layer around it (the held size's box).
            .background {
                RoundedRectangle(cornerRadius: Self.handleWidth / 2, style: .continuous)
                    .fill(Yoin.ink.opacity(PointerHover.opacity(hovered: handleHovered || PointerHover.preview)))
                    .frame(width: Self.handleWidth, height: 64 + Self.handleWidth)
                    .animation(Yoin.effects, value: handleHovered)
            }
            .frame(width: Self.handleWidth)
            .frame(maxHeight: .infinity)
            .contentShape(.rect.inset(by: -10))
            .background(Yoin.surface.ignoresSafeArea())
            .gesture(DragGesture(minimumDistance: 2, coordinateSpace: .global)
                .onChanged { g in
                    if dragStartShell == nil { dragStartShell = rest }
                    var t = Transaction(animation: nil)
                    t.disablesAnimations = true
                    withTransaction(t) { draggedShell = clampShell((dragStartShell ?? rest) + g.translation.width, w: w) }
                }
                .onEnded { _ in dragStartShell = nil })
            // The column-resize pointer (beam with arrows either side); it passes every touch through.
            .overlay { ColumnResizePointer { handleHovered = $0 } }
            .accessibilityLabel("Resize columns")
    }

    @State private var handleHovered = false
    @State private var wide = false
    static let handleWidth: CGFloat = 12

    /// Where the detail column's Play split sits, from the window's bottom edge: the player is the
    /// column's trailing corner, so Play shares its row at the leading corner, unless the column is too
    /// narrow for both (then it goes above).
    private func playBottom(detailWidth: CGFloat) -> CGFloat {
        let row = PadDockMetrics.side + Tokens.bar.SPLIT_WIDTH.cg + PadDockMetrics.gap
            + PadDockMetrics.playerWidth + PadDockMetrics.side
        let playerShown = !model.isIdle && !model.npPanel
        return !playerShown || detailWidth >= row ? PadDockMetrics.playInRow : PadDockMetrics.playLifted
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
    private func detailColumn(_ content: ColumnContent, playBottom: CGFloat) -> some View {
        let clear = clearance(above: playBottom)
        switch content {
        case .album(let album):
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
        case .route(let first):
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
        return Binding(get: { Array(columnPath.dropFirst()) },
                       set: { tail in setColumnPath(Array(columnPath.prefix(1)) + tail) })
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
        active ? Binding(get: { columnPath }, set: { setColumnPath($0) }) : .constant([])
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
        case .tip(let id):
            TipView(start: id)
        case .allMemories:
            AllMemoriesView()
        case .labs:
            LabsView()
        case .neoDBVoices(let album):
            NeoDBVoicesView(album: album)
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
        case .settings, .tips, .tip, .allMemories, .labs, .neoDBVoices: return nil
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
