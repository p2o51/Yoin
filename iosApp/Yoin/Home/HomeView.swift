import SwiftUI
import YoinShared

/// Home, ported from Android's phone layout (HomeEditorialContent.kt,
/// HomeWidgetGrid.kt, RediscoverSection.kt, HomeGreetingTitle.kt) at feed
/// density N = 2 (content width < 448). Numbers are cited in
/// iosApp/docs/fidelity-home-np.md.
struct HomeView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var colorScheme
    @State private var contentWidth: CGFloat = 370
    /// Edit mode and the account's section layout (HomeEdit.swift).
    @State private var edit = HomeEditController()
    /// The Memories bubble's motion and turns (HomeMemoryBubble.swift).
    @State private var bubble = MemoryBubbleController()
    @State private var scroll = HomeScrollState()
    /// How far the window runs past Home's frame (status bar above, bottom bar below): Memories covers it.
    @State private var pageBleed = EdgeInsets()

    /// App-wide tokens follow the playing cover (design.md 颜色系统 3).
    private var scheme: CoverScheme { CoverScheme(seed: model.accent, dark: colorScheme == .dark) }

    /// Signed in: the server's shelves (nil while the first load runs);
    /// signed out: the mock shelf, for screenshots and demos only
    /// (LaunchConfig.demoContent); a real launch with no account gets nil
    /// and the connect prompt.
    private var liveFeed: HomeFeed? { Self.feed(isReal: model.store.isReal, demo: LaunchConfig.demoContent) { model.store.home } }

    /// What Home shows: the live feed, except while a page is pushed over it and through the
    /// zoom back. Opening an album logs a visit, which moves it into Activities (and out of
    /// Recently Played); applied under the page, the tile the album opened from was gone or
    /// had moved by the time the page shrank back into it, so the cover jumped. The feed
    /// catches up once Home is in view again, on a spring.
    private var feed: HomeFeed? { held ?? liveFeed }
    @State private var held: HomeFeed?

    static func feed(isReal: Bool, demo: Bool, server: () -> HomeFeed?) -> HomeFeed? {
        if isReal { return server() }
        return demo ? MockLibrary.shared.homeFeed() : nil
    }

    private var memories: MemoriesPresentation { model.memories }

    /// Something above Home owns the screen: the bubble keeps quiet, the pull stays shut.
    private var covered: Bool { !model.path.isEmpty || model.npExpanded || model.np.mounted || model.searchActive }

    /// What the bubble can say: the account's latest memory, its others, its notes.
    private var pill: HomeMemoryPill? {
        let store = model.store
        if store.isReal {
            let provider = store.provider
            return HomeMemories.pill(store.memories, noteCount: store.noteCount, scope: store.homeScope) {
                AlbumMemoryStore.shared.memoryTitle(provider: provider, albumId: $0.albumId)
            }
        }
        guard LaunchConfig.demoContent else { return nil }
        return HomeMemories.pill(MemoryDeck.mockMemories(), noteCount: MemoryDeck.mockNotes.values.map(\.count).reduce(0, +),
                                 scope: "mock") { MemoryDeck.mockTitles[$0.albumId] }
    }

    var body: some View {
        ZStack(alignment: .top) {
            feedScroll
                .modifier(IslandPush(bubble: bubble))
                .modifier(MemoriesHomeBehind(presentation: memories))
            MemoryBubbleOverlay(bubble: bubble, memories: memories, pill: pill, scheme: scheme, scroll: scroll,
                                covered: covered, edit: edit)
            if memories.mounted {
                MemoriesView(presentation: memories)
                    // The whole window: under the status bar and the bottom bar too
                    // (Home's frame stops short of both, and no safe area reaches it).
                    // The same band comes back as safe area, so the deck lays out where
                    // it did and only the aurora runs on to the edges.
                    .safeAreaPadding(.top, pageBleed.top)
                    .safeAreaPadding(.bottom, pageBleed.bottom)
                    .padding(.top, -pageBleed.top)
                    .padding(.bottom, -pageBleed.bottom)
                    .modifier(MemoriesPagePose(presentation: memories, bleed: pageBleed))
                    .zIndex(2)
                    .transition(.identity)
            }
        }
        // Memories' page runs the window's height, and travels it.
        .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { frame in
            let window = MemoriesView.windowHeight
            pageBleed = EdgeInsets(top: max(frame.minY, 0), leading: 0, bottom: max(window - frame.maxY, 0), trailing: 0)
            memories.containerHeight = frame.height + pageBleed.top + pageBleed.bottom
        }
        .environment(\.homeScheme, scheme)
        .environment(\.homeEdit, edit)
        .task(id: model.store.generation) {
            await model.store.loadHome()
            // Your Playlists reads the library's list (the Library tab's own load, once per account).
            await model.store.loadPlaylists()
            // The albums memories need (the pill, the marks, the deck), after a relaunch or a sync.
            await model.store.hydrateMemoryAlbums()
        }
        .onAppear { edit.load(scope: model.store.homeScope) }
        .onChange(of: model.store.homeScope) { _, scope in edit.load(scope: scope) }
        // Another device's layout landed (cloud sync): reread it unless a draft is open.
        .onChange(of: HomeLayoutStore.shared.remoteRevision) { edit.load(scope: model.store.homeScope) }
        // Home leaves the screen (Library): Memories and edit mode go with it,
        // unseen. A page pushed over Home (Go to album from a memory) only
        // covers it: Memories stays open underneath and Back returns to it.
        .onDisappear {
            if !MemoriesPresentation.survivesHomeHidden(onHome: model.section == .home, pushed: !model.path.isEmpty) {
                memories.snapClosed()
            }
            edit.snapExit()
        }
        // Something opens over Home: edit mode ends under it.
        .onChange(of: covered) { _, c in if c { edit.exit() } }
        .onChange(of: model.path.isEmpty) { _, home in
            if !home {
                if held == nil { held = liveFeed }
                return
            }
            Task {
                // The zoom back lands first.
                try? await Task.sleep(for: .milliseconds(650))
                guard model.path.isEmpty, held != nil else { return }
                withAnimation(Yoin.spatial) { held = nil }
            }
        }
        #if DEBUG
        // Recording aids, off the view's lifecycle (Home is rebuilt once at launch).
        .onAppear {
            HomeEditScript.edit = edit
            let focus = pill?.recall.dropFirst().first?.albumId
            Task { await MemoriesScript.run(memories, focus: focus) }
            Task { await HomeEditScript.run() }
        }
        #endif
    }

    private var feedScroll: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                header
                if let feed {
                    let shown = edit.order.filter { edit.isEditing || hasContent($0, feed) }
                    ForEach(Array(shown.enumerated()), id: \.element) { i, section in
                        HomeEditBlock(edit: edit, section: section, index: i, covers: covers(section, feed),
                                      rows: rowsHandle(section, feed)) {
                            sectionView(section, feed)
                        }
                    }
                    if edit.isEditing || edit.progress > 0 {
                        HomeEditTray(edit: edit)
                            .modifier(EditFade(edit: edit, from: 0.3, to: 1))
                            .padding(.top, 6)
                    } else if shown.isEmpty && !feed.isEmpty {
                        Text("Home is empty").homeType(Tokens.type.titleMedium, .semibold).foregroundStyle(scheme.onSurfaceVariant)
                    }
                    if !edit.isEditing { editFooter }
                } else if !model.store.isReal {
                    HomeConnectPrompt(scheme: scheme, hasAccount: model.profiles.active != nil) { model.openSettings() }
                } else if model.store.homePhase == .failed {
                    RetryRow(scheme: scheme) { Task { await model.store.loadHome(force: true) } }
                } else {
                    HomeSkeleton(width: contentWidth, scheme: scheme)
                }
            }
            .padding(.horizontal, 16)
            .coordinateSpace(.named(HomeEditController.feedSpace))
            .background(HomeHoldObserver(edit: edit))
            .overlay(alignment: .topLeading) { CarriedStrip(edit: edit, covers: feed.map { covers(edit.hole ?? .activities, $0) } ?? []) }
            .onGeometryChange(for: CGFloat.self) { $0.size.width - 32 } action: { contentWidth = $0 }
            .background(ScrollPullObserver(presentation: memories, enabled: !edit.isEditing && !covered) {
                bubble.cue(.outOfReach, pill: nil)
            })
        }
        .contentMargins(.bottom, 24, for: .scrollContent)
        // A carried section owns the finger: the feed doesn't scroll under it.
        .scrollDisabled(edit.carried != nil)
        // `-screen home-bottom` opens scrolled to the end (screenshots).
        .defaultScrollAnchor(LaunchConfig.screen == "home-bottom" ? .bottom : .top)
        .scrollEdgeEffectStyle(.soft, for: [.top, .bottom])
        // Behind a pull into Memories Home is scaled, and the bottom edge
        // effect then drew as an opaque band under the bar, cutting the feed
        // off with a hard edge. It sits out while Memories is up.
        .scrollEdgeEffectHidden(memories.mounted, for: .bottom)
        .onScrollGeometryChange(for: CGFloat.self) { $0.contentOffset.y + $0.contentInsets.top } action: { _, y in
            scroll.scrolled = y
        }
        .background(
            LinearGradient(colors: [scheme.surfaceContainer, scheme.background, scheme.surfaceContainerLow],
                           startPoint: .top, endPoint: .bottom)
            .ignoresSafeArea()
        )
    }

    // MARK: Sections (the account's layout, Android HomeSection catalog)

    private func hasContent(_ s: HomeSection, _ feed: HomeFeed) -> Bool { feed.hasContent(s) }

    @ViewBuilder
    private func sectionView(_ s: HomeSection, _ feed: HomeFeed) -> some View {
        if !hasContent(s, feed) {
            // Editing, a section with nothing yet: its panel, its title and one honest line.
            VStack(alignment: .leading, spacing: 4) {
                HomeSectionTitle(s.title)
                Text("Nothing here yet").homeType(Tokens.type.bodyMedium).foregroundStyle(scheme.onSurfaceVariant)
            }
            .frame(maxWidth: .infinity, minHeight: 72, alignment: .topLeading)
        } else {
            switch s {
            case .activities: activities(feed.activities, preset: edit.rowsOf(.activities))
            case .jumpBackIn: jumpBackIn(feed, preset: edit.rowsOf(.jumpBackIn))
            case .recentlyAdded: recentlyAdded(feed)
            case .rediscover: rediscover(feed.rediscover)
            case .recentlyPlayed: recentlyPlayed(feed)
            case .yourPlaylists: yourPlaylists(feed.playlists)
            case .otherProfiles: EmptyView()
            }
        }
    }

    /// A strip's covers: the section's first few.
    private func covers(_ s: HomeSection, _ feed: HomeFeed) -> [CoverPalette] {
        if s == .yourPlaylists { return Array(feed.playlists.prefix(3).map(\.palette)) }
        let albums: [Album] = switch s {
        case .activities: feed.activities.map(\.album)
        case .jumpBackIn: feed.signals.map(\.album) + feed.jumpBackIn
        case .recentlyAdded: feed.recentAlbums + feed.recentTracks.map(\.album)
        case .rediscover: feed.rediscover.map(\.album)
        case .recentlyPlayed: feed.recentlyPlayed
        case .yourPlaylists, .otherProfiles: []
        }
        return Array(albums.prefix(3).map(\.palette))
    }

    /// Discoverability (Android Q6b): Edit Home at the foot of the feed.
    private var editFooter: some View {
        Button { edit.enter() } label: {
            HStack(spacing: 8) {
                YoinIcon("edit", size: 18)
                Text("Edit Home").homeType(Tokens.type.labelLarge, .semibold)
            }
            .foregroundStyle(scheme.onSurfaceVariant)
            .padding(.horizontal, 16)
            .frame(height: 48)
        }
        .buttonStyle(PressScale())
        .frame(maxWidth: .infinity)
        .padding(.top, 6)
    }

    // MARK: Header (HomeGreetingTitle.kt)

    private var greeting: String {
        switch Calendar.current.component(.hour, from: .now) {
        case 5..<11: String(localized: "Good morning")
        case 11..<18: String(localized: "Good afternoon")
        default: String(localized: "Good evening")
        }
    }

    private var header: some View {
        HStack(alignment: .center, spacing: 2) {
            VStack(alignment: .leading, spacing: 0) {
                // titleMedium 16/24 and headlineMedium 28/36, both GSF Rounded Medium (ROND 100).
                Text(greeting)
                    .font(YoinFont.rounded(16, relativeTo: .subheadline))
                    .foregroundStyle(scheme.onSurfaceVariant)
                    .frame(height: 24)
                EditTitleSwap(edit: edit, scheme: scheme)
                    .frame(height: 36)
                    // An easy way into Glass Lab on a real phone (besides the
                    // three-finger long-press).
                    .onLongPressGesture { withAnimation(Yoin.spatial) { GlassLab.shared.panelOpen = true } }
            }
            .lineLimit(1)
            // The bubble takes the title's place when it speaks.
            .modifier(BubbleTitleShift(bubble: bubble))
            // Where the title rests (outside the shift, so the bubble's own push doesn't move it).
            .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { bubble.titleFrame = $0 }
            .padding(.trailing, Tokens.home.HEADER_TITLE_BREATHING.cg)
            Spacer(minLength: 0)
            HeaderActions(edit: edit, scheme: scheme) {
                Button { model.openSettings() } label: {
                    // 32 dp avatar in a 48 dp target: the account in use (its shape, service colour and
                    // letter); with none, the mock's (profile 0 = Cookie6Sided).
                    HeaderAccountAvatar(profile: model.profiles.active,
                                        shape: model.profiles.activeId.flatMap { model.profiles.avatarShapes[$0] } ?? 0,
                                        fallback: BackdropColors(model.playback.album.palette).accent.mix(with: scheme.primary, by: 0.5),
                                        onFallback: scheme.onPrimary, ring: scheme.surfaceContainer)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Account")
                .accessibilityValue(model.profiles.active?.title ?? "")
                // Hold and slide onto another account (Android #162).
                .accountQuickSwitch(accounts: model.quickSwitchAccounts, shapes: model.profiles.avatarShapes) { model.quickSwitch(to: $0) }
            }
            .onGeometryChange(for: CGFloat.self) { $0.frame(in: .global).minX } action: { bubble.freeEnd = $0 }
        }
        .frame(minHeight: 48)
        .padding(.top, 12)
    }

    // MARK: Activities (bento, phone preset L)

    /// The bento at [preset] (Android activityCodeRows): the hero, then rows of cards and a strip,
    /// as many as the preset seats and the history fills.
    private func activities(_ a: [ActivityEntry], preset: HomeRowPreset) -> some View {
        let gap = Tokens.home.ACTIVITY_ROW_GAP.cg
        let rows = activityStops(a).stops.stopPayload(for: preset)
        let hasHero = a[0].entity != .artist
        let supporting = hasHero ? Array(a.dropFirst()) : a
        return VStack(alignment: .leading, spacing: gap) {
            HomeSectionTitle(String(localized: "Activities")).padding(.bottom, 6)
            ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                switch row.shape {
                case .hero:
                    HeroCard(entry: a[0])
                case .strip:
                    if let e = supporting.entry(at: row.slots[0].entryIndex) { StripCard(entry: e) }
                case .cards:
                    let usable = max(0, contentWidth - gap * CGFloat(row.slots.count - 1))
                    let total = CGFloat(row.slots.reduce(0) { $0 + $1.weight })
                    HStack(spacing: gap) {
                        ForEach(Array(row.slots.enumerated()), id: \.offset) { _, slot in
                            let width = contentWidth > 0 ? usable * CGFloat(slot.weight) / total : nil
                            Group {
                                if let e = supporting.entry(at: slot.entryIndex) {
                                    if slot.kind == .small { SmallCard(entry: e) } else { WideCard(entry: e) }
                                } else {
                                    // An entry past the supply: a spacer of the same weight.
                                    Color.clear
                                }
                            }
                            .frame(width: width)
                        }
                    }
                    .frame(height: Tokens.home.ACTIVITY_ROW_HEIGHT.cg)
                }
            }
        }
    }

    /// The Activities ladder for [a] (hero found as Android's: the first album or playlist).
    private func activityStops(_ a: [ActivityEntry]) -> (hasHero: Bool, stops: [HomeRowStop<[ActivityCodeRow]>]) {
        let hasHero = !a.isEmpty && a[0].entity != .artist
        return (hasHero, HomeRowPresets.activityLadder(hasHero: hasHero, supportingCount: hasHero ? a.count - 1 : a.count))
    }

    /// [section]'s resize handle: its stops on this screen and the one it stands at; nil when the
    /// section takes no presets or the supply leaves a single stop.
    private func rowsHandle(_ s: HomeSection, _ feed: HomeFeed) -> HomeRowsHandle? {
        guard s.supportsRows, hasContent(s, feed) else { return nil }
        let current = edit.rowsOf(s)
        switch s {
        case .activities:
            let stops = activityStops(feed.activities).stops
            return HomeRowsHandle(stops: stops.map { ($0.rowCount, $0.presetFrom(current)) }, index: stops.stopIndex(of: current))
        case .jumpBackIn where feed.layout == .server:
            let stops = jbiStops(feed)
            return HomeRowsHandle(stops: stops.map { ($0.rowCount, $0.presetFrom(current)) }, index: stops.stopIndex(of: current))
        default: return nil
        }
    }

    // MARK: Jump Back In (3 columns, two 1×2 signal cards)

    @ViewBuilder
    private func jumpBackIn(_ feed: HomeFeed, preset: HomeRowPreset) -> some View {
        if feed.layout == .mock { mockJumpBackIn(feed.jumpBackIn) } else { serverJumpBackIn(feed, preset: preset) }
    }

    /// One Jump Back In row of a server's shelf.
    private enum JBIRow: Hashable {
        /// A 1×2 then a cover, or a cover then a 1×2 (the mock's two rows).
        case signal(JBISignal, Album?, leading: Bool)
        case covers([Album])
    }

    /// The shelf's cells (a 1×2 takes two) at [preset]: 2 / 3 / 4 / 6 rows of three. The signal
    /// cards take the first rows (the rated album's, then the noted track's), as many as the
    /// preset's size lets (Android jbiSignalLimit), then plain covers three to a row.
    private func serverRows(_ feed: HomeFeed, preset: HomeRowPreset) -> [JBIRow] {
        var covers = feed.jumpBackIn[...]
        var rows: [JBIRow] = []
        let signals = HomeRowPresets.jbiSignals(preset, available: feed.signals.count)
        for (i, s) in feed.signals.prefix(signals).enumerated() {
            rows.append(.signal(s, covers.popFirst(), leading: i % 2 == 0))
        }
        while !covers.isEmpty && rows.count < HomeRowPresets.jbiRows(preset) {
            let n = min(3, covers.count)
            rows.append(.covers(Array(covers.prefix(n))))
            covers.removeFirst(n)
        }
        return rows
    }

    private func jbiStops(_ feed: HomeFeed) -> [HomeRowStop<[JBIRow]>] {
        homeRowLadder { preset in
            let rows = serverRows(feed, preset: preset)
            return (rows, rows.count)
        }
    }

    /// A server's shelf: the history's 1×2s, then plain cells, three to a row;
    /// covers wear their memory marks.
    private func serverJumpBackIn(_ feed: HomeFeed, preset: HomeRowPreset) -> some View {
        let colGap = Tokens.home.JBI_COLUMN_GAP.cg
        let col = (contentWidth - 2 * colGap) / 3
        let pairUnit = (contentWidth - colGap) / 3
        let rows = jbiStops(feed).stopPayload(for: preset)
        return VStack(alignment: .leading, spacing: Tokens.home.JBI_ROW_GAP.cg) {
            HomeSectionTitle(String(localized: "Jump Back In"))
            ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                HStack(alignment: .top, spacing: colGap) {
                    switch row {
                    case let .signal(signal, cover, leading):
                        if !leading, let cover {
                            JBICell(album: cover, mark: feed.memoryMarks[cover.id.rawId])
                                .frame(width: pairUnit, alignment: .leading)
                        }
                        SignalCard(signal: signal).frame(width: pairUnit * 2, alignment: .leading)
                        if leading, let cover {
                            JBICell(album: cover, mark: feed.memoryMarks[cover.id.rawId])
                                .frame(width: pairUnit, alignment: .leading)
                        }
                    case let .covers(albums):
                        ForEach(albums) { album in
                            JBICell(album: album, mark: feed.memoryMarks[album.id.rawId])
                                .frame(width: col, alignment: .leading)
                        }
                    }
                }
            }
        }
    }

    private func mockJumpBackIn(_ a: [Album]) -> some View {
        // Compose Row weights (HomeWidgetGrid.kt phone path): each row splits
        // what its own 12 dp gaps leave, so a 1×2 row (one gap) gets
        // (C − 12) × 2/3 + (C − 12)/3 and a three-cover row (C − 24)/3 each.
        let colGap = Tokens.home.JBI_COLUMN_GAP.cg
        let col = (contentWidth - 2 * colGap) / 3
        let pairUnit = (contentWidth - colGap) / 3
        return VStack(alignment: .leading, spacing: Tokens.home.JBI_ROW_GAP.cg) {
            HomeSectionTitle(String(localized: "Jump Back In"))
            VStack(alignment: .leading, spacing: Tokens.home.JBI_ROW_GAP.cg) {
                HStack(alignment: .top, spacing: colGap) {
                    SignalCard(signal: Self.mockSignal(a[0], .ratedAlbum)).frame(width: pairUnit * 2, alignment: .leading)
                    JBICell(album: a[1], mark: .solid).frame(width: pairUnit, alignment: .leading)
                }
                HStack(alignment: .top, spacing: colGap) {
                    JBICell(album: a[2], mark: nil).frame(width: pairUnit, alignment: .leading)
                    SignalCard(signal: Self.mockSignal(a[5], .notedTrack)).frame(width: pairUnit * 2, alignment: .leading)
                }
                ForEach(0..<2, id: \.self) { row in
                    HStack(alignment: .top, spacing: colGap) {
                        ForEach(0..<3, id: \.self) { i in
                            let idx = 3 + row * 3 + i
                            JBICell(album: a[idx], mark: idx == 4 ? .outline : idx == 7 ? .dashed : nil)
                                .frame(width: col, alignment: .leading)
                        }
                    }
                }
            }
        }
    }

    /// The screenshots' hand-set 1×2 copy.
    private static func mockSignal(_ album: Album, _ kind: JBISignal.Kind) -> JBISignal {
        kind == .ratedAlbum
            ? JBISignal(kind: kind, album: album, title: album.title, subtitle: album.artist,
                        score: HomeHistory.scoreText(album.rating), basis: "Oct 28",
                        comment: "A field that stays bright after dark")
            : JBISignal(kind: kind, album: album, title: album.tracks[0].title, subtitle: album.artist,
                        score: "8.0", basis: "Nov 1", comment: "The bridge at 2:10 sounds like summer ending")
    }

    // MARK: Recently Added (2×2 track grid, then album cards)

    private func recentlyAdded(_ feed: HomeFeed) -> some View {
        let tiles = feed.recentTracks
        let gridWidth = min(340, max(226.8, (contentWidth - 14) * 2.6 / 3.6))
        let tileGap = Tokens.home.RECENTLY_ADDED_TILE_GAP.cg
        return VStack(alignment: .leading, spacing: 12) {
            HomeSectionTitle(String(localized: "Recently Added"))
            ScrollView(.horizontal) {
                HStack(alignment: .top, spacing: 14) {
                    if !tiles.isEmpty {
                        VStack(alignment: .leading, spacing: 14) {
                            ForEach(Array(stride(from: 0, to: min(tiles.count, 4), by: 2)), id: \.self) { r in
                                HStack(spacing: tileGap) {
                                    ForEach(r..<min(r + 2, tiles.count), id: \.self) { i in
                                        TrackTile(album: tiles[i].album, track: tiles[i].track)
                                            .frame(width: (gridWidth - tileGap) / 2, alignment: .leading)
                                            .onTapGesture { homeTap(edit) { model.play(tiles, startAt: i) } }
                                    }
                                }
                            }
                        }
                        .frame(width: gridWidth, alignment: .topLeading)
                    }
                    ForEach(Array(feed.recentAlbums.enumerated()), id: \.offset) { _, album in
                        AlbumMini(album: album, mark: feed.memoryMarks[album.id.rawId])
                    }
                }
                .padding(.horizontal, 16)
            }
            .scrollIndicators(.hidden)
            .padding(.horizontal, -16)
        }
    }

    // MARK: Recently Played, Your Playlists (Android AlbumShelfSection, YourPlaylistsSection)

    /// The source's recent albums: Recently Added's album cards, full bleed.
    private func recentlyPlayed(_ feed: HomeFeed) -> some View {
        shelf(HomeSection.recentlyPlayed.title) {
            ForEach(feed.recentlyPlayed) { album in
                AlbumMini(album: album, mark: feed.memoryMarks[album.id.rawId])
            }
        }
    }

    /// The library's playlists: the same card on the playlist shape, its song count under the name.
    private func yourPlaylists(_ playlists: [LibraryItem]) -> some View {
        shelf(HomeSection.yourPlaylists.title) {
            ForEach(playlists) { PlaylistMini(item: $0) }
        }
    }

    private func shelf<Content: View>(_ title: String, @ViewBuilder _ content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HomeSectionTitle(title)
            ScrollView(.horizontal) {
                HStack(alignment: .top, spacing: 14) { content() }
                    .padding(.horizontal, 16)
            }
            .scrollIndicators(.hidden)
            .padding(.horizontal, -16)
        }
    }

    // MARK: Rediscover (peeking shelf)

    private func rediscover(_ cards: [RediscoverEntry]) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HomeSectionTitle(String(localized: "Rediscover"))
            ScrollView(.horizontal) {
                // A smaller song card sits on the album cards' centre line.
                HStack(alignment: .center, spacing: Tokens.home.REDISCOVER_GAP.cg) {
                    ForEach(Array(cards.enumerated()), id: \.offset) { _, c in
                        Group {
                            if let song = c.song {
                                RediscoverSongCard(entry: song, away: c.away, score: c.score, snippet: c.noteSnippet)
                            } else {
                                RediscoverCard(album: c.album, reason: c.reason, away: c.away, score: c.score,
                                               footnote: (c.footnoteDate, c.footnotePlays))
                            }
                        }
                        .frame(width: contentWidth * 0.86)
                    }
                }
                .padding(.horizontal, 16)
            }
            .scrollIndicators(.hidden)
            .padding(.horizontal, -16)
        }
    }
}

/// Home with no account (a skipped sign-in, the last account removed, Apple
/// Music's access gone): one line and the way to Settings.
private struct HomeConnectPrompt: View {
    var scheme: CoverScheme
    /// An account is chosen but can't be read (its sign-in is gone, or
    /// Apple Music access is off): Settings has its card.
    var hasAccount = false
    var action: () -> Void

    var body: some View {
        VStack(spacing: 16) {
            Text(hasAccount ? String(localized: "Sign in again") : String(localized: "No account connected"))
                .homeType(Tokens.type.titleMedium, .semibold)
                .foregroundStyle(scheme.onSurfaceVariant)
            Button(action: action) {
                Text(hasAccount ? String(localized: "Open Settings") : String(localized: "Connect an account"))
                    .yoinRole(Tokens.type.labelLarge)
                    .foregroundStyle(scheme.onSecondaryContainer)
                    .padding(.horizontal, 20)
                    .frame(minHeight: 40)
                    .background(scheme.secondaryContainer, in: .capsule)
                    .contentShape(.capsule)
            }
            .buttonStyle(PressScale())
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 48)
    }
}

// MARK: - Scheme plumbing

/// A card's tap, unless edit mode has it (the long-press that entered edit
/// lands its release on the card under it).
@MainActor func homeTap(_ edit: HomeEditController?, _ action: () -> Void) {
    if edit?.swallowsTap == true { return }
    action()
}

private struct HomeSchemeKey: EnvironmentKey {
    static let defaultValue: CoverScheme? = nil
}

extension EnvironmentValues {
    var homeScheme: CoverScheme? {
        get { self[HomeSchemeKey.self] }
        set { self[HomeSchemeKey.self] = newValue }
    }
}

/// Android card wash: container = lerp(surfaceContainerLow, backdrop base, 0.30).
func cardWash(_ scheme: CoverScheme, _ palette: CoverPalette) -> Color {
    scheme.surfaceContainerLow.mix(with: BackdropColors(palette).base, by: Tokens.home.CARD_WASH_FRACTION, in: .perceptual)
}

/// Android's cover-tone accent (ActivityCardColors.ink, the JBI score rule):
/// the palette base reads on a light surface but sinks into a dark one, so
/// dark mode takes the brighter accent.
func cardInk(_ palette: CoverPalette, dark: Bool) -> Color {
    let b = BackdropColors(palette)
    return dark ? b.accent : b.base
}

// MARK: - Type (helpers in Theme/TypeRoles.swift)

/// Section title: titleLarge SemiBold at 18 sp (line height 28).
struct HomeSectionTitle: View {
    @Environment(\.homeScheme) private var scheme
    var text: String
    init(_ text: String) { self.text = text }
    var body: some View {
        Text(text)
            .homeType(Tokens.type.titleLarge, .semibold, size: 18)
            .foregroundStyle(scheme?.onSurface ?? Yoin.ink)
            .lineLimit(1)
    }
}

/// Android MetaLine (component/MetaLine.kt): groups 12 dp apart, never joined
/// by a glyph. A Stat is its number in Medium (tabular) + 4 dp + its unit in
/// Normal at 60 %; a Plain group takes the line's own style.
private struct MetaLine: View {
    var groups: [(String, String?)]
    var style: YoinTokens.TypographyStyle = Tokens.type.labelSmall
    var color: Color
    var body: some View {
        HStack(spacing: Tokens.metaLine.GROUP_GAP.cg) {
            ForEach(Array(groups.enumerated()), id: \.offset) { _, g in
                if let unit = g.1 {
                    HStack(spacing: Tokens.metaLine.INNER_GAP.cg) {
                        Text(g.0).homeType(style, .medium, digits: true)
                        Text(unit).homeType(style, .regular).opacity(Tokens.metaLine.MUTED_ALPHA)
                    }
                } else {
                    Text(g.0).homeType(style)
                }
            }
        }
        .foregroundStyle(color)
        .lineLimit(1)
    }
}

// MARK: - Activities cards

private struct HeroCard: View {
    @Environment(AppModel.self) private var model
    @Environment(\.homeEdit) private var edit
    @Environment(\.homeScheme) private var scheme
    @Environment(\.zoomNamespace) private var zoomNS
    @State private var zoomTile = ZoomTile()
    var entry: ActivityEntry
    private var album: Album { entry.album }
    private var timeAgo: String { entry.eyebrow }

    /// [year] [N songs] [M min]; the mock keeps its hand-set 48 min.
    private var heroMeta: [(String, String?)] {
        var g: [(String, String?)] = []
        if album.year > 0 { g.append(("\(album.year)", nil)) }
        g.append(("\(album.trackCount)", String(localized: "songs")))
        let mins = album.id.provider == "mock" ? 48 : album.totalSec / 60
        if mins > 0 { g.append(("\(mins)", String(localized: "min"))) }
        return g
    }

    var body: some View {
        let s = scheme!
        let muted = s.onSurfaceVariant
        Button { homeTap(edit) { model.openActivity(entry, tile: zoomTile.key) } } label: {
            HStack(spacing: 14) {
                EntityBackdrop(entity: entry.backdrop, palette: album.palette, seed: album.seed, size: 104, stroke: s.primary)
                    .zoomSource(album.id, entry.entity == .album ? zoomNS : nil, tile: zoomTile.key)
                VStack(alignment: .leading, spacing: 3) {
                    Text(timeAgo).homeType(Tokens.type.labelSmall).foregroundStyle(muted)
                    Text(album.title).homeType(Tokens.type.titleLarge, .semibold).foregroundStyle(s.onSurface)
                    Text(album.artist).homeType(Tokens.type.bodySmall).foregroundStyle(muted)
                    MetaLine(groups: heroMeta, color: muted)
                }
                .lineLimit(1)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(14)
            .homeGlassCard(album.palette, scheme: s, shape: RoundedRectangle(cornerRadius: Tokens.shape.CARD.cg, style: .continuous))
        }
        .buttonStyle(PressScale())
    }
}

private struct SmallCard: View {
    @Environment(AppModel.self) private var model
    @Environment(\.homeEdit) private var edit
    @Environment(\.homeScheme) private var scheme
    var entry: ActivityEntry
    private var album: Album { entry.album }
    @Environment(\.zoomNamespace) private var zoomNS
    @State private var zoomTile = ZoomTile()

    var body: some View {
        let s = scheme!
        Button { homeTap(edit) { model.openActivity(entry, tile: zoomTile.key) } } label: {
            VStack(alignment: .leading, spacing: 4) {
                HStack(alignment: .center, spacing: 10) {
                    EntityBackdrop(entity: entry.backdrop, palette: album.palette, seed: album.seed, size: 52, stroke: s.primary)
                        .zoomSource(album.id, entry.entity == .album ? zoomNS : nil, tile: zoomTile.key)
                    VStack(alignment: .leading, spacing: 0) {
                        Text(entry.typeLabel).homeType(Tokens.type.labelSmall)
                        Text(entry.eyebrow).homeType(Tokens.type.labelSmall)
                    }
                    .foregroundStyle(s.onSurfaceVariant)
                    .lineLimit(1)
                }
                Spacer(minLength: 0)
                // titleSmall SemiBold, leading tightened to 17 so two lines read as one block.
                Text(album.title)
                    .homeType(Tokens.type.titleSmall, .semibold, lineHeight: 17)
                    .foregroundStyle(s.onSurface)
                    .lineLimit(2)
            }
            .padding(10)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
            .homeGlassCard(album.palette, scheme: s, shape: RoundedRectangle(cornerRadius: Tokens.shape.CARD.cg, style: .continuous))
        }
        .buttonStyle(PressScale())
    }
}

private struct WideCard: View {
    @Environment(AppModel.self) private var model
    @Environment(\.homeEdit) private var edit
    @Environment(\.homeScheme) private var scheme
    @Environment(\.zoomNamespace) private var zoomNS
    @State private var zoomTile = ZoomTile()
    var entry: ActivityEntry
    private var album: Album { entry.album }
    private var timeAgo: String { entry.eyebrow }

    var body: some View {
        let s = scheme!
        Button { homeTap(edit) { model.openActivity(entry, tile: zoomTile.key) } } label: {
            HStack(spacing: 12) {
                EntityBackdrop(entity: entry.backdrop, palette: album.palette, seed: album.seed, size: 88, stroke: s.primary)
                    .zoomSource(album.id, entry.entity == .album ? zoomNS : nil, tile: zoomTile.key)
                VStack(alignment: .leading, spacing: 2) {
                    Text(timeAgo).homeType(Tokens.type.labelSmall).foregroundStyle(s.onSurfaceVariant)
                    Text(album.title).homeType(Tokens.type.titleMedium, .semibold).foregroundStyle(s.onSurface)
                    Text(album.artist).homeType(Tokens.type.bodySmall).foregroundStyle(s.onSurfaceVariant)
                }
                .lineLimit(1)
                Spacer(minLength: 0)
            }
            .padding(12)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .homeGlassCard(album.palette, scheme: s, shape: RoundedRectangle(cornerRadius: Tokens.shape.CARD.cg, style: .continuous))
        }
        .buttonStyle(PressScale())
    }
}

private struct StripCard: View {
    @Environment(AppModel.self) private var model
    @Environment(\.homeEdit) private var edit
    @Environment(\.homeScheme) private var scheme
    var entry: ActivityEntry
    private var album: Album { entry.album }
    private var timeAgo: String { entry.eyebrow }

    var body: some View {
        let s = scheme!
        Button { homeTap(edit) { model.openActivity(entry) } } label: {
            HStack(spacing: 10) {
                HStack(spacing: 12) {
                    Text(album.title).homeType(Tokens.type.bodyMedium, .semibold).foregroundStyle(s.onSurface)
                    Text(album.artist).homeType(Tokens.type.bodyMedium).foregroundStyle(s.onSurface.opacity(0.6))
                }
                Spacer(minLength: 0)
                Text(timeAgo).homeType(Tokens.type.labelSmall).foregroundStyle(s.onSurfaceVariant)
            }
            .lineLimit(1)
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .homeGlassCard(album.palette, scheme: s, shape: Capsule())
        }
        .buttonStyle(PressScale())
    }
}

// MARK: - Jump Back In cells

/// Memory mark on a plain album cover: 16 dp disc + 12 dp Cookie12Sided.
private typealias MemoryMark = AlbumMemoryMark

private struct JBICell: View {
    @Environment(AppModel.self) private var model
    @Environment(\.homeEdit) private var edit
    @Environment(\.homeScheme) private var scheme
    @Environment(\.zoomNamespace) private var zoomNS
    @State private var zoomTile = ZoomTile()
    var album: Album
    var mark: MemoryMark?
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        let s = scheme!
        Button { homeTap(edit) { model.open(album, tile: zoomTile.key) } } label: {
            AlbumItem(album: album, size: Tokens.home.JBI_COVER.cg) {
                // albumMemoryMarkInk: palette base, accent in dark mode.
                if let mark { MemoryMarkView(mark: mark, ink: cardInk(album.palette, dark: colorScheme == .dark), bg: s.background) }
            }
            .zoomSource(album.id, zoomNS, tile: zoomTile.key)
        }
        .buttonStyle(PressScale())
    }
}

/// Memory mark: 16 dp disc + 12 dp Cookie12Sided, on the cover's top-trailing corner.
private struct MemoryMarkView: View {
    var mark: MemoryMark
    var ink: Color
    var bg: Color

    var body: some View {
        let m = mark
        return ZStack {
            Circle().fill(bg).frame(width: 16, height: 16)
            switch m {
            case .solid: CookieShape(lobes: 12, depth: 0.12).fill(ink).frame(width: 12, height: 12)
            case .outline: CookieShape(lobes: 12, depth: 0.12).stroke(ink, lineWidth: 1.25).frame(width: 11, height: 11)
            case .dashed:
                CookieShape(lobes: 12, depth: 0.12)
                    .stroke(ink.opacity(0.6), style: StrokeStyle(lineWidth: 1.25, dash: [2, 1.5]))
                    .frame(width: 11, height: 11)
            }
        }
        .padding(8)
    }
}

/// One album on Home: bare cover, then title (GSF SemiBold, ROND 100: iOS
/// decision) and artist, flush as one text cluster. Defaults are the JBI 1×1
/// (HomeWidgetGrid.kt WidgetCoverBlock / WidgetCoverCaption): 5 gap, title
/// 16/22, subtitle bodySmall 12/16.
struct AlbumItem<Mark: View>: View {
    @Environment(\.homeScheme) private var scheme
    var album: Album
    var size: CGFloat
    var titleSize: CGFloat = 16
    var titleLineHeight: CGFloat = 22
    var artistStyle: YoinTokens.TypographyStyle = Tokens.type.bodySmall
    var gap: CGFloat = 5
    @ViewBuilder var mark: () -> Mark

    var body: some View {
        let s = scheme!
        VStack(alignment: .leading, spacing: gap) {
            let onCover = CoverMarks.onCover(provider: album.id.provider)
            EntityBackdrop(entity: .album, palette: album.palette, seed: album.seed, size: size, stroke: s.primary)
                .overlay(alignment: .topTrailing) { if onCover { mark() } }
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 2) {
                    Text(album.title)
                        .font(YoinFont.font(size: titleSize, YoinFont.Axes(weight: 600, round: 100)))
                        .homeLine(titleSize, titleLineHeight)
                        .foregroundStyle(s.onSurface)
                    // A Spotify cover: the mark at the title's end instead (CoverMarks).
                    if !onCover {
                        Spacer(minLength: 0)
                        mark().padding(-8)
                    }
                }
                Text(album.artist)
                    .homeType(artistStyle)
                    .foregroundStyle(s.onSurfaceVariant)
            }
            .lineLimit(1)
        }
        .frame(width: size, alignment: .leading)
    }
}

extension AlbumItem where Mark == EmptyView {
    init(album: Album, size: CGFloat, titleSize: CGFloat = 16, titleLineHeight: CGFloat = 22,
         artistStyle: YoinTokens.TypographyStyle = Tokens.type.bodySmall, gap: CGFloat = 5) {
        self.init(album: album, size: size, titleSize: titleSize, titleLineHeight: titleLineHeight,
                  artistStyle: artistStyle, gap: gap) { EmptyView() }
    }
}

/// A Jump Back In 1×2 (Figma Widget 1×2): the cover and its caption, then
/// the score over its basis and the words. A noted track plays its song.
private struct SignalCard: View {
    @Environment(AppModel.self) private var model
    @Environment(\.homeEdit) private var edit
    @Environment(\.homeScheme) private var scheme
    var signal: JBISignal

    @Environment(\.colorScheme) private var colorScheme

    private var album: Album { signal.album }

    var body: some View {
        let s = scheme!
        let ink = cardInk(album.palette, dark: colorScheme == .dark)
        Button {
            homeTap(edit) {
                // A memory 1×2 opens Memories stopped on its album (Android onOpenMemoryFocus);
                // a noted track plays its song.
                if let song = signal.song {
                    model.play([song])
                } else if signal.kind == .ratedAlbum {
                    model.memories.open(focus: album.id.rawId)
                } else {
                    model.open(album)
                }
            }
        } label: {
            HStack(alignment: .top, spacing: 16) {
                VStack(alignment: .leading, spacing: 5) {
                    EntityBackdrop(entity: .album, palette: album.palette, seed: album.seed, size: Tokens.home.JBI_COVER.cg,
                                   stroke: s.primary)
                    VStack(alignment: .leading, spacing: 0) {
                        Text(signal.title)
                            .font(YoinFont.font(size: 16, YoinFont.Axes(weight: 600, round: 100)))
                            .homeLine(16, 22)
                            .foregroundStyle(s.onSurface)
                        Text(signal.subtitle).homeType(Tokens.type.bodySmall).foregroundStyle(s.onSurfaceVariant)
                    }
                    .lineLimit(1)
                    .frame(width: Tokens.home.JBI_COVER.cg, alignment: .leading)
                }
                VStack(alignment: .leading, spacing: 3) {
                    // Row(verticalAlignment = Bottom): the basis sits 3 dp off the score's line-box bottom.
                    HStack(alignment: .bottom, spacing: 6) {
                        Text(signal.score)
                            .homeType(Tokens.type.headlineSmall, .bold, digits: true)
                        if let basis = signal.basis {
                            Text(basis)
                                .homeType(Tokens.type.labelSmall, digits: true)
                                .opacity(0.85)
                                .padding(.bottom, 3)
                        }
                    }
                    .foregroundStyle(ink)
                    if let comment = signal.comment {
                        // A memory's title in the headline face; a note is body text.
                        if signal.kind == .ratedAlbum {
                            Text(comment)
                                .homeType(Tokens.type.titleMedium, .semibold, size: 17, lineHeight: 24)
                                .foregroundStyle(s.onSurface)
                                .lineLimit(3)
                        } else {
                            Text(comment)
                                .homeType(Tokens.type.bodyMedium)
                                .foregroundStyle(s.onSurface)
                                .lineLimit(3)
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .contentShape(.rect)
        }
        .buttonStyle(PressScale())
    }
}

// MARK: - Recently Added

private struct TrackTile: View {
    @Environment(\.homeScheme) private var scheme
    var album: Album
    var track: Track

    var body: some View {
        let s = scheme!
        HStack(spacing: 8) {
            CoverArt(palette: album.palette, cornerRadius: Tokens.shape.THUMB.cg, seed: album.seed)
                .frame(width: Tokens.home.RECENTLY_ADDED_TRACK_COVER.cg, height: Tokens.home.RECENTLY_ADDED_TRACK_COVER.cg)
            VStack(alignment: .leading, spacing: 0) {
                // bodyMedium at 13 sp Medium (line height stays 20).
                Text(track.title).homeType(Tokens.type.bodyMedium, .medium, size: 13).foregroundStyle(s.onSurface)
                Text(album.artist).homeType(Tokens.type.labelSmall).foregroundStyle(s.onSurfaceVariant)
            }
            .lineLimit(1)
        }
    }
}

private struct AlbumMini: View {
    @Environment(AppModel.self) private var model
    @Environment(\.homeEdit) private var edit
    @Environment(\.homeScheme) private var scheme
    @Environment(\.colorScheme) private var colorScheme
    var album: Album
    var mark: MemoryMark? = nil
    @Environment(\.zoomNamespace) private var zoomNS
    @State private var zoomTile = ZoomTile()

    var body: some View {
        Button { homeTap(edit) { model.open(album, tile: zoomTile.key) } } label: {
            // RecentlyAddedAlbumCard: 82 cover, 6 gap, bodySmall 12/16, labelSmall artist.
            AlbumItem(album: album, size: Tokens.home.RECENTLY_ADDED_ALBUM_COVER.cg, titleSize: 12, titleLineHeight: 16,
                      artistStyle: Tokens.type.labelSmall, gap: 6) {
                if let mark, let s = scheme {
                    MemoryMarkView(mark: mark, ink: cardInk(album.palette, dark: colorScheme == .dark), bg: s.background)
                }
            }
            .zoomSource(album.id, zoomNS, tile: zoomTile.key)
        }
        .buttonStyle(PressScale())
    }
}

/// Your Playlists' card (Android YourPlaylistCard): Recently Added's size and
/// type on the playlist shape, the song count under the name.
private struct PlaylistMini: View {
    @Environment(AppModel.self) private var model
    @Environment(\.homeEdit) private var edit
    @Environment(\.homeScheme) private var scheme
    var item: LibraryItem

    var body: some View {
        let s = scheme!
        let size = Tokens.home.RECENTLY_ADDED_ALBUM_COVER.cg
        Button { homeTap(edit) { model.openPlaylist(item.id.rawId) } } label: {
            VStack(alignment: .leading, spacing: 6) {
                EntityBackdrop(entity: .playlist, palette: item.palette, seed: item.id.rawId.count, size: size,
                               stroke: s.primary)
                VStack(alignment: .leading, spacing: 0) {
                    Text(item.title)
                        .font(YoinFont.font(size: 12, YoinFont.Axes(weight: 600, round: 100)))
                        .homeLine(12, 16)
                        .foregroundStyle(s.onSurface)
                    if let n = item.count {
                        Text(n == 1 ? String(localized: "1 song") : String(localized: "\(n) songs"))
                            .homeType(Tokens.type.labelSmall)
                            .foregroundStyle(s.onSurfaceVariant)
                    }
                }
                .lineLimit(1)
            }
            .frame(width: size, alignment: .leading)
        }
        .buttonStyle(PressScale())
        .accessibilityLabel(item.title)
    }
}

// MARK: - Rediscover

private struct RediscoverCard: View {
    @Environment(AppModel.self) private var model
    @Environment(\.homeEdit) private var edit
    @Environment(\.homeScheme) private var scheme
    var album: Album
    var reason: String
    var away: String
    var score: Double?
    var footnote: (String, String)

    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        let s = scheme!
        let ink = cardInk(album.palette, dark: colorScheme == .dark)
        let container = cardWash(s, album.palette)
        Button { homeTap(edit) { model.open(album) } } label: {
            HStack(spacing: 14) {
                EntityBackdrop(entity: .album, palette: album.palette, seed: album.seed,
                               size: Tokens.home.REDISCOVER_BACKDROP.cg, stroke: s.primary)
                    .overlay(alignment: .bottomTrailing) {
                        if let score, CoverMarks.onCover(provider: album.id.provider) {
                            Text(String(format: "%.1f", score))
                                .homeType(Tokens.type.labelLarge, .bold, digits: true)
                                .foregroundStyle(container)
                                .padding(.horizontal, Tokens.home.REDISCOVER_BADGE_PADDING.cg)
                                .frame(height: Tokens.home.REDISCOVER_BADGE_HEIGHT.cg)
                                .background(ink, in: .capsule)
                                .offset(x: Tokens.home.REDISCOVER_BADGE_HANG.cg)
                        }
                    }
                VStack(alignment: .leading, spacing: 0) {
                    // A Spotify cover: the score heads the text instead (CoverMarks).
                    if let score, !CoverMarks.onCover(provider: album.id.provider) {
                        Text(String(format: "%.1f", score))
                            .homeType(Tokens.type.labelLarge, .bold, digits: true)
                            .foregroundStyle(container)
                            .padding(.horizontal, Tokens.home.REDISCOVER_BADGE_PADDING.cg)
                            .frame(height: Tokens.home.REDISCOVER_BADGE_HEIGHT.cg)
                            .background(ink, in: .capsule)
                            .padding(.bottom, 4)
                    }
                    // A scored card has no reason line; a note card no away line.
                    if !reason.isEmpty { Text(reason).homeType(Tokens.type.labelMedium).foregroundStyle(ink) }
                    if !away.isEmpty { Text(away).homeType(Tokens.type.labelMedium).foregroundStyle(ink) }
                    Text(album.title)
                        .homeType(Tokens.type.titleLarge, .semibold, size: 20, lineHeight: 26)
                        .foregroundStyle(s.onSurface)
                        .padding(.top, 2)
                    Text(album.artist).homeType(Tokens.type.bodyMedium).foregroundStyle(s.onSurfaceVariant)
                    // First-play month and plays in Yoin; either half drops when unknown.
                    let groups: [(String, String?)] = (footnote.0.isEmpty ? [] : [(footnote.0, nil)])
                        + (footnote.1.isEmpty ? [] : [(footnote.1, footnote.1 == "1" ? String(localized: "play") : String(localized: "plays"))])
                    if !groups.isEmpty {
                        MetaLine(groups: groups, color: s.onSurfaceVariant)
                            .padding(.top, 4)
                    }
                }
                .lineLimit(1)
                Spacer(minLength: 0)
            }
            .padding(Tokens.home.REDISCOVER_PADDING.cg)
            .frame(height: Tokens.home.REDISCOVER_CARD_HEIGHT.cg)
            .homeGlassCard(album.palette, scheme: s, shape: RoundedRectangle(cornerRadius: Tokens.shape.CARD.cg, style: .continuous))
        }
        .buttonStyle(PressScale())
    }
}

/// A song you rated or noted, as the album card's smaller sibling (Android RediscoverSongCard):
/// the song's circle backdrop at 76, the away line, title, artist and album, and, with no score
/// to badge, one line of its newest note on a rail. Tapping plays it alone.
private struct RediscoverSongCard: View {
    @Environment(AppModel.self) private var model
    @Environment(\.homeEdit) private var edit
    @Environment(\.homeScheme) private var scheme
    @Environment(\.colorScheme) private var colorScheme
    var entry: PlaylistEntry
    var away: String
    var score: Double?
    var snippet: String?

    /// Android RediscoverSongCardHeight / BackdropSize / CardPadding.
    private static let height: CGFloat = 116, backdrop: CGFloat = 76, padding: CGFloat = 12

    var body: some View {
        let s = scheme!
        let album = entry.album
        let ink = cardInk(album.palette, dark: colorScheme == .dark)
        let container = cardWash(s, album.palette)
        let onCover = CoverMarks.onCover(provider: album.id.provider)
        Button { homeTap(edit) { model.play([entry]) } } label: {
            HStack(spacing: Self.padding) {
                EntityBackdrop(entity: .song, palette: album.palette, seed: album.seed, size: Self.backdrop, stroke: s.primary)
                    .overlay(alignment: .bottomTrailing) { if onCover { badge(ink, container) } }
                VStack(alignment: .leading, spacing: 0) {
                    if !onCover { badge(ink, container).padding(.bottom, 4) }
                    Text(away).homeType(Tokens.type.labelMedium).foregroundStyle(ink)
                    Text(entry.track.title)
                        .homeType(Tokens.type.titleMedium, .semibold, size: 17, lineHeight: 22)
                        .foregroundStyle(s.onSurface)
                        .padding(.top, 2)
                    HStack(spacing: Tokens.metaLine.GROUP_GAP.cg) {
                        if let artist = entry.track.artist, !artist.isEmpty { Text(artist) }
                        if !album.title.isEmpty { Text(album.title).opacity(Tokens.metaLine.MUTED_ALPHA) }
                    }
                    .homeType(Tokens.type.bodyMedium)
                    .foregroundStyle(s.onSurfaceVariant)
                    if let snippet, !snippet.isEmpty {
                        Text(snippet)
                            .homeType(Tokens.type.bodySmall)
                            .foregroundStyle(s.onSurface)
                            .padding(.top, 2)
                            .padding(.leading, 10)
                            .overlay(alignment: .leading) { Capsule().fill(ink).frame(width: 2).padding(.vertical, 2) }
                    }
                }
                .lineLimit(1)
                Spacer(minLength: 0)
            }
            .padding(Self.padding)
            .frame(height: Self.height)
            .homeGlassCard(album.palette, scheme: s, shape: RoundedRectangle(cornerRadius: Tokens.shape.CARD.cg, style: .continuous))
        }
        .buttonStyle(PressScale())
        .accessibilityHint(String(localized: "Play"))
    }

    @ViewBuilder
    private func badge(_ ink: Color, _ container: Color) -> some View {
        if let score {
            Text(String(format: "%.1f", score))
                .homeType(Tokens.type.labelLarge, .bold, digits: true)
                .foregroundStyle(container)
                .padding(.horizontal, Tokens.home.REDISCOVER_BADGE_PADDING.cg)
                .frame(height: Tokens.home.REDISCOVER_BADGE_HEIGHT.cg)
                .background(ink, in: .capsule)
                .offset(x: CoverMarks.onCover(provider: entry.album.id.provider) ? Tokens.home.REDISCOVER_BADGE_HANG.cg : 0)
        }
    }
}

// MARK: - Glass cards

extension View {
    /// Home's cover-color cards as Liquid Glass. Glass needs something to
    /// refract, so the card's own cover sits behind it, scaled up and heavily
    /// blurred, clipped to the card; the glass on top is tinted with the
    /// card wash so text keeps the solid card's contrast.
    func homeGlassCard<S: InsettableShape>(_ palette: CoverPalette, scheme: CoverScheme, shape: S) -> some View {
        let lab = GlassLab.shared
        return background {
            ZStack {
                CoverArt(palette: palette, cornerRadius: 0, seed: 1)
                    .scaleEffect(1.6)
                    .blur(radius: lab.extra("coverBlur"))
                    .opacity(lab.extra("coverStrength"))
                    .clipShape(shape)
                shape.fill(.clear)
                    .labGlass("homeCards", scheme: scheme, brand: .brand(dark: false),
                              auto: cardWash(scheme, palette), in: shape)
            }
            .allowsHitTesting(false)
        }
        // The backdrop doesn't hit-test, so without this only the card's
        // text and art take a tap and its padding and gaps let it fall
        // through: the whole card is the target, like Android's Surface(onClick).
        .contentShape(shape)
    }

}

extension HomeFeed {
    /// Whether [s] has anything to show (an empty section is left out unless Home is being edited).
    /// Jump Back In: its memory / note signal cards count on their own (Android HomeViewModel: the
    /// signals or the grid, parity A-F2).
    func hasContent(_ s: HomeSection) -> Bool {
        switch s {
        case .activities: !activities.isEmpty
        case .jumpBackIn: !jumpBackIn.isEmpty || !signals.isEmpty
        case .recentlyAdded: !recentTracks.isEmpty || !recentAlbums.isEmpty
        case .rediscover: !rediscover.isEmpty
        case .recentlyPlayed: !recentlyPlayed.isEmpty
        case .yourPlaylists: !playlists.isEmpty
        case .otherProfiles: false
        }
    }
}
