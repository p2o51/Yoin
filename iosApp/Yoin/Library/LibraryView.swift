import SwiftUI
import YoinShared

/// Library root, ported from Android ui/library/LibraryScreen.kt (Compact):
/// search pill + avatar header (no title on Compact), filter chips with the
/// ✕ that appears while one is on, the sort row as the list's first row, and
/// the All grid (3 columns on a phone, more on a wider pane; 16 sides,
/// 12 / 16 gaps) mixing artists (circles) with albums and playlists (bare
/// covers, as on iOS Home). Playlists is a row list with New playlist;
/// Songs a 68 pt row list (the newest albums' songs, page by page, on
/// Subsonic); Favorites starred artists, albums and songs. Numbers are cited
/// in iosApp/docs/fidelity/library.md.
///
/// iOS adaptations: the pill opens the shared Search screen; covers follow
/// the iOS bare-cover rule; the header floats in glass over the grid, the
/// filter chips are the style Glass Lab picks under it (LibraryFilterRow;
/// World: one glass track with a tinted lozenge).
struct LibraryView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.zoomNamespace) private var zoomNS
    @Environment(\.horizontalSizeClass) private var hSize
    @Environment(\.verticalSizeClass) private var vSize
    @State private var width: CGFloat = 370
    @State private var position = ScrollPosition()
    @State private var metrics = ScrollMetrics()
    @State private var awake = LaunchConfig.screen == "library-scroller"
    @State private var held = LaunchConfig.screen == "library-scroller"
    @State private var hideTask: Task<Void, Never>?
    @State private var naming = false
    @State private var newName = ""
    @State private var order = LibraryOrderMemo()

    /// Filter, sorts and positions outlive the page (Android keeps them in
    /// its ViewModel): Home → Library comes back to the same view.
    private var memory: LibraryMemory { .shared }
    private var filter: LibraryKind? { LibraryFilterRow.offered(memory.filter, kinds: filterKinds) }
    /// Android LibraryViewModel: Favorites only where the source has favorites of its own (Subsonic's
    /// starred). Apple Music has none, and Spotify's are its Liked Songs, already the Songs view.
    private var filterKinds: [LibraryKind] {
        LibraryFilterRow.kinds(favorites: !model.store.isReal || model.store.client != nil)
    }
    private var tab: LibraryMemory.Tab { LibraryMemory.Tab(filter) }
    private var sort: LibrarySortOrder { memory.sort(for: tab, offered: sortOptions) }

    /// The grid in rows for the shared fast-scroller mapping (FastScrollMath,
    /// YoinShared): Android's knee mapping, where the thumb tracks the top row
    /// and the last stretch of the track covers the final screen.
    struct ScrollMetrics: Equatable {
        var offset: CGFloat = 0, content: CGFloat = 1, viewport: CGFloat = 1

        func rows(_ items: Int, columns: Int) -> Int32 {
            FastScrollMath.shared.lineCount(itemCount: Int32(items), itemsPerLine: Int32(columns))
        }

        func rowHeight(_ items: Int, columns: Int) -> CGFloat { content / CGFloat(max(1, rows(items, columns: columns))) }

        func rowsPerScreen(_ items: Int, columns: Int) -> Float { Float(viewport / rowHeight(items, columns: columns)) }

        func fraction(_ items: Int, columns: Int) -> CGFloat {
            let row = rowHeight(items, columns: columns)
            return CGFloat(FastScrollMath.shared.handleFraction(
                position: Float(offset / row), lines: rows(items, columns: columns),
                linesPerScreen: rowsPerScreen(items, columns: columns)))
        }

        /// The scroll offset for thumb fraction `f`.
        func offset(for f: CGFloat, _ items: Int, columns: Int) -> CGFloat {
            CGFloat(FastScrollMath.shared.positionFor(
                fraction: Float(f), lines: rows(items, columns: columns),
                linesPerScreen: rowsPerScreen(items, columns: columns))) * rowHeight(items, columns: columns)
        }

        func worthShowing(_ items: Int, columns: Int) -> Bool {
            FastScrollMath.shared.isWorthShowing(itemCount: Int32(items), itemsPerLine: Int32(columns),
                                                 linesPerScreen: rowsPerScreen(items, columns: columns))
        }
    }

    private var scheme: CoverScheme {
        model.isIdle ? .brand(dark: colorScheme == .dark)
                     : CoverScheme(album: model.playback.album, dark: colorScheme == .dark)
    }

    /// Android libraryGridCells: Fixed(3) on a phone's width; past it the
    /// cells stay phone-sized and the count follows the width (min 96, a
    /// landscape phone's short window 100).
    private var columns: Int {
        LibraryGrid.columns(width: width, regularWidth: hSize == .regular, compactHeight: vSize == .compact)
    }
    private var cell: CGFloat { LibraryGrid.cell(width: width, columns: columns) }

    /// Android librarySortOptions: only orders the data can back (see `LibrarySortOptions`).
    private var sortOptions: [LibrarySortOrder] {
        LibrarySortOptions.options(for: filter, playlistsDated: LibrarySortOptions.playlistsDated(model.store.playlists))
    }

    private var sorter: LibrarySorter {
        LibrarySorter(recents: model.store.libraryRecents(), ignoresArticles: model.store.client != nil)
    }

    /// Sorted items for the current view and sort, with the fast scroller's
    /// sections. Built once per view, sort and data change: body runs every
    /// scroll frame, and sorting a few thousand albums each time pinned the
    /// main thread (build 8: 89% CPU, then a watchdog kill).
    private var ordered: LibraryOrderMemo.Value {
        let sort = sort
        return order.value(for: .init(filter: filter, sort: sort, byYou: memory.byYou, real: model.store.isReal)) {
            var base = filtered
            if filter == .playlists, memory.byYou, byYouAvailable {
                base = base.filter { $0.ownedByMe == true }
            }
            let sorter = sorter
            let items = sorter.sorted(base, by: sort)
            return .init(items: items, sections: LibraryScrollIndex.sections(items, sort: sort, sorter: sorter))
        }
    }

    private var items: [LibraryItem] { ordered.items }

    /// Android PlaylistsByYou: offered only when the playlists are mixed
    /// (some the user's, some someone else's), so it never empties the list.
    private var byYouAvailable: Bool { LibraryFilterRow.byYouAvailable(model.store.playlists) }

    private var filtered: [LibraryItem] {
        if model.store.isReal {
            let store = model.store
            switch filter {
            case .none: return store.libraryItems
            case .albums: return store.libraryItems.filter { $0.kind == .album }
            case .artists: return store.artists
            case .playlists: return store.playlists
            case .songs, .favorites: return []
            }
        }
        let all = model.library.libraryItems()
        switch filter {
        case .none: return all
        case .albums: return all.filter { $0.kind == .album }
        case .artists: return all.filter { $0.kind == .artist }
        case .playlists: return all.filter { $0.kind == .playlist }
        case .songs, .favorites: return []
        }
    }

    var body: some View {
        let ordered = ordered
        let shown = ordered.items
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                if filter == .songs {
                    songsTab
                } else if filter == .favorites {
                    // Android FavoritesTabContent: 8 top padding, then the
                    // starred artists, albums and songs under section headers.
                    favoritesList.padding(.top, Tokens.library.GRID_TOP_PADDING.cg)
                } else if filter == .playlists {
                    playlistsTab(shown)
                } else {
                    // Grid contentPadding top 8 (LibraryScreen.kt AllTabContent).
                    gridTab(shown)
                }
            }
            .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
        }
        .contentMargins(.bottom, 24, for: .scrollContent)
        .scrollEdgeEffectStyle(.soft, for: [.top, .bottom])
        .task(id: LoadKey(generation: model.store.generation, filter: filter)) {
            await model.store.loadLibrary(filter)
        }
        .refreshable { if model.store.isReal { await reload(force: true) } }
        // Header and chips stay put (Android: only the grid scrolls) and float
        // over the grid as glass, so they sit in a top safe-area bar and the
        // system soft edge runs under them.
        .safeAreaBar(edge: .top) {
            // Android: the app bar (64), the chips right under it, then the
            // Column's 4 gap. The chips' 4 vertical padding (room for the
            // glass press bloom) overlaps the bar and is that 4 gap.
            // The track styles keep 8 between the field and the track, so
            // the two capsules read as a pair, not one stacked slab.
            VStack(alignment: .leading, spacing: LibraryFilterRow.Style.current.isTrack ? 0 : -4) {
                header
                chips
                    // Search's own chips come up in this band: Library's
                    // leave at once and Search's arrive a beat later (fade
                    // through), never both at half.
                    .opacity(model.searchActive ? 0 : 1)
                    .animation(model.searchActive ? nil : Yoin.effects, value: model.searchActive)
            }
        }
        .scrollPosition($position)
        .onScrollGeometryChange(for: ScrollMetrics.self) {
            ScrollMetrics(offset: $0.contentOffset.y + $0.contentInsets.top, content: $0.contentSize.height,
                          viewport: $0.containerSize.height)
        } action: { _, m in
            metrics = m
            memory.offsets[tab] = m.offset
            wake()
        }
        .overlay {
            // Grid views only (All, Artists, Albums), past three screens.
            if filter == nil || filter == .artists || filter == .albums {
                LibraryFastScroller(
                    fraction: held && LaunchConfig.screen == "library-scroller" && metrics.offset == 0
                        ? 0.45 : metrics.fraction(shown.count, columns: columns),
                    worthShowing: metrics.worthShowing(shown.count, columns: columns),
                    awake: awake, sections: ordered.sections, itemCount: shown.count, columns: columns, scheme: scheme,
                    onDrag: { f in
                        position.scrollTo(y: metrics.offset(for: f, shown.count, columns: columns))
                        wake()
                    },
                    held: $held)
                // Android LibraryGridFastScroller: the grid box (under the 64
                // app bar, 44 chips and 4 gap) plus LibraryScrollerTopInset 8;
                // bottom = floatingBottomGroupContentPadding 108 over the inset,
                // less the 62 the shell already reserves for the bar.
                .padding(.top, 64 + Tokens.library.CHIP_HEIGHT.cg + 4 + Tokens.library.SCROLLER_TOP_INSET.cg
                         + (LibraryFilterRow.Style.current.isTrack ? 4 : 0))
                .padding(.bottom, Tokens.library.BOTTOM_GROUP_PADDING.cg - 62)
            }
        }
        .overlay(alignment: .bottomTrailing) {
            if filter == .playlists && canCreatePlaylist {
                newPlaylistButton.transition(.scale(scale: 0.6).combined(with: .opacity))
            }
        }
        .alert("New playlist", isPresented: $naming) {
            TextField("Name", text: $newName)
            Button("Cancel", role: .cancel) {}
            Button("Create") { PlaylistActions.shared.create(newName, with: [], store: model.store) }
                .disabled(newName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }
        .onAppear {
            if LaunchConfig.screen == "library-scroller" {
                Task { @MainActor in
                    try? await Task.sleep(for: .milliseconds(400))
                    position.scrollTo(y: metrics.offset(for: 0.45, items.count, columns: columns))
                }
            } else if let y = memory.offsets[tab], y > 0 {
                // Back where this view was left (Android keeps each tab's state).
                position.scrollTo(y: y)
            }
        }
        .onChange(of: model.store.generation) { _, _ in memory.offsets = [:] }
        .background(
            LinearGradient(colors: [scheme.surfaceContainer, scheme.background, scheme.surfaceContainerLow],
                           startPoint: .top, endPoint: .bottom)
            .ignoresSafeArea()
        )
    }

    /// Awake while scrolling; asleep 1.6 s after scrolling and holding stop.
    private func wake() {
        awake = true
        hideTask?.cancel()
        hideTask = Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(1600))
            if !Task.isCancelled && !held && LaunchConfig.screen != "library-scroller" { awake = false }
        }
    }

    // MARK: Header: search pill + avatar

    private var header: some View {
        HStack(spacing: 8) {
            Button { model.openSearch(from: .library) } label: {
                HStack(spacing: 12) {
                    // Adaptive label colors: they flip with the glass over dark content.
                    YoinIcon("search", size: 24).foregroundStyle(.secondary)
                    Text("Search library").font(.gsf(16)).foregroundStyle(.secondary)
                    Spacer()
                }
                .padding(.horizontal, 16)
                .frame(height: SearchView.fieldHeight)
                .contentShape(.capsule)
            }
            .buttonStyle(.plain)
            .labSearchGlass(scheme: scheme, in: .capsule)
            // Search's field takes this exact rect. Opened from here the two
            // coincide and the pill simply stays under it; grown from the
            // bar's circle, the pill steps aside so no second field waits here.
            .opacity(model.searchActive && model.searchOrigin == .bar ? 0 : 1)
            .animation(Yoin.effects, value: model.searchActive)
            // The account Library is reading, as on Home (Android: 资料库也要显示当前的账号).
            Button { if !AccountFan.shared.swallowsTap { model.openSettings() } } label: {
                HeaderAccountAvatar(profile: model.profiles.active,
                                    shape: model.profiles.activeId.flatMap { model.profiles.avatarShapes[$0] } ?? 0,
                                    fallback: scheme.primary, onFallback: scheme.onPrimary,
                                    ring: scheme.surfaceContainer)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Account")
            .accessibilityValue(model.profiles.active?.spokenAccount ?? "")
            // Hold and slide onto another account (Android #162).
            .accountQuickSwitch(accounts: model.quickSwitchAccounts, shapes: model.profiles.avatarShapes) { model.quickSwitch(to: $0) }
        }
        .padding(.horizontal, 16)
        .padding(.top, SearchView.headerTop)
        .padding(.bottom, 4)
    }

    // MARK: Chips

    /// Android LibraryFilterRow, in the style Glass Lab picks (see
    /// LibraryFilterRow below; Android-faithful tonal chips by default).
    private var chips: some View {
        LibraryFilterRow(filter: Binding(get: { filter }, set: { select($0) }), kinds: filterKinds,
                         byYou: filter == .playlists && byYouAvailable
                             ? Binding(get: { memory.byYou }, set: { memory.byYou = $0 }) : nil,
                         scheme: scheme, style: LibraryFilterRow.Style.current, overContent: metrics.offset > 1)
    }

    /// Another view: this one's place is kept, the new one's comes back.
    private func select(_ kind: LibraryKind?) {
        memory.offsets[tab] = metrics.offset
        memory.filter = kind
        position.scrollTo(y: memory.offsets[LibraryMemory.Tab(kind)] ?? 0)
    }

    // MARK: All / Artists / Albums grid

    @ViewBuilder
    private func gridTab(_ shown: [LibraryItem]) -> some View {
        if shown.isEmpty && model.store.isReal {
            // Android: no sort row over an empty view; its loading, failure
            // or empty panel instead.
            loadState(empty: true)
        } else if shown.isEmpty {
            emptyPanel
        } else {
            grid(shown).padding(.top, Tokens.library.GRID_TOP_PADDING.cg)
            // All with one list failed: the others stay, its Retry under them.
            if model.store.isReal { loadState(empty: false) }
        }
    }

    private func grid(_ shown: [LibraryItem]) -> some View {
        let gutter = Tokens.library.GRID_GUTTER_H.cg
        return LazyVGrid(columns: Array(repeating: GridItem(.fixed(cell), spacing: gutter, alignment: .top), count: columns),
                         alignment: .leading, spacing: Tokens.library.GRID_GUTTER_V.cg) {
            Section {
                ForEach(shown) { item in
                    gridCell(item, side: cell)
                        .transition(.opacity.animation(Yoin.effects))
                }
            } header: {
                sortRow
            }
        }
        .padding(.horizontal, 16)
        // Android animateItem: arrivals, removals and a new order move on the
        // spatial spring and fade on the effects one.
        .animation(Yoin.spatial, value: shown.map(\.id))
    }

    /// The sort row: label + sort symbol, as the list's first row.
    private var sortRow: some View {
        Menu {
            ForEach(sortOptions, id: \.self) { option in
                Button {
                    withAnimation(Yoin.spatial) { memory.sorts[tab] = option }
                } label: {
                    if option == sort {
                        Label { Text(option.label) } icon: { YoinIcon("check", size: 18) }
                    } else {
                        Text(option.label)
                    }
                }
            }
        } label: {
            HStack(spacing: 6) {
                Text(sort.label).yoinRole(Tokens.type.labelLarge).foregroundStyle(scheme.onSurface)
                YoinIcon("sort", size: 18).foregroundStyle(scheme.onSurfaceVariant)
            }
            .padding(.horizontal, Tokens.library.SORT_ROW_PRESS_BLEED.cg)
            .padding(.vertical, 10)
            .frame(minHeight: 48)
            .contentShape(.rect(cornerRadius: 12))
        }
        .buttonStyle(.plain)
        .offset(x: -Tokens.library.SORT_ROW_PRESS_BLEED.cg)
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityLabel("Sort")
    }

    @ViewBuilder
    private func gridCell(_ item: LibraryItem, side: CGFloat) -> some View {
        let isArtist = item.kind == .artist
        Button { open(item) } label: {
            VStack(alignment: isArtist ? .center : .leading, spacing: 0) {
                EntityBackdrop(entity: isArtist ? .artist : .album, palette: item.palette,
                               seed: item.id.rawId.count, size: side, stroke: scheme.primary)
                    .zoomSource(item.id, zoomNS, tile: "library")
                Spacer().frame(height: Tokens.library.COVER_TO_TITLE.cg)
                // AlbumCard / ArtistGridItem: titleSmall over bodySmall (12).
                if let album = item.album, !isArtist {
                    AlbumVersionTitle(album: album, scheme: scheme) { name in
                        Text(name)
                            .lineLimit(1)
                            .yoinRole(Tokens.type.titleSmall)
                            .foregroundStyle(scheme.onSurface)
                    }
                } else {
                    Text(item.title)
                        .lineLimit(1)
                        .yoinRole(Tokens.type.titleSmall)
                        .foregroundStyle(scheme.onSurface)
                }
                Text(gridSubtitle(item))
                    .lineLimit(1)
                    .yoinRole(Tokens.type.bodySmall)
                    .foregroundStyle(scheme.onSurfaceVariant)
            }
            .frame(width: side, alignment: isArtist ? .center : .leading)
        }
        .buttonStyle(PressScale())
    }

    private func open(_ item: LibraryItem) {
        switch item.kind {
        case .artist: model.openArtist(item.title, id: item.id.provider == "mock" ? nil : item.id.rawId)
        case .playlist: model.openPlaylist(item.id.rawId)
        case .album: if let album = item.album { model.open(album, tile: "library") }
        }
    }

    /// The grid's line under a cover, Spotify's way (51, 2026-11-07): "Album • Artist",
    /// "Single • Artist", "Playlist • owner", "Artist" (round cover) — the grid mixes all three.
    private func gridSubtitle(_ item: LibraryItem) -> String {
        switch item.kind {
        case .artist: String(localized: "Artist")
        case .album: item.album?.kindLine ?? ReleaseKind.album.label
        case .playlist: KindLine.make(String(localized: "Playlist"), item.owner ?? "")
        }
    }

    private func subtitle(_ item: LibraryItem) -> String {
        switch item.kind {
        case .artist:
            // A service that doesn't count an artist's albums (Spotify) says "Artist", not the mock's count.
            guard let n = item.count ?? (item.id.provider == "mock"
                ? model.library.albums().filter { $0.artist == item.title }.count : nil) else { return String(localized: "Artist") }
            return n == 1 ? String(localized: "1 album") : String(localized: "\(n) albums")
        case .album: return item.album?.artist ?? ""
        case .playlist: return String(localized: "Playlist")
        }
    }

    // MARK: Playlists

    /// Android PlaylistsTabContent: the sort row, then PlaylistListItem rows
    /// (16 × 10, 48 Thumb, 12, titleSmall over owner and "N tracks"); the
    /// New playlist button where the source can write playlists.
    @ViewBuilder
    private func playlistsTab(_ shown: [LibraryItem]) -> some View {
        if shown.isEmpty && model.store.isReal {
            loadState(empty: true)
        } else if shown.isEmpty {
            emptyPanel
        } else {
            LazyVStack(alignment: .leading, spacing: 0) {
                sortRow.padding(.horizontal, 16)
                ForEach(shown) { p in
                    PlaylistRow(item: p, scheme: scheme) { open(p) }
                        .transition(.opacity.animation(Yoin.effects))
                }
            }
            .padding(.top, Tokens.library.GRID_TOP_PADDING.cg)
            .animation(Yoin.spatial, value: shown.map(\.id))
            if model.store.isReal { loadState(empty: false) }
        }
    }

    /// Android PlaylistsTabContent's FAB: Subsonic and Spotify write playlists
    /// here (PlaylistActions.create); Apple Music's are made in its own app.
    private var canCreatePlaylist: Bool { PlaylistActions.shared.canCreate(model.store) }

    /// Android ExtendedFloatingActionButton "New playlist": primaryContainer,
    /// + and the label, bottom-end over the list.
    private var newPlaylistButton: some View {
        Button {
            newName = ""
            naming = true
        } label: {
            HStack(spacing: 12) {
                YoinIcon("add", size: 24)
                Text("New playlist").yoinRole(Tokens.type.labelLarge)
            }
            .foregroundStyle(scheme.onPrimaryContainer)
            .padding(.horizontal, 20)
            .frame(height: 56)
            .background(scheme.primaryContainer, in: .rect(cornerRadius: 16, style: .continuous))
        }
        .buttonStyle(PressScale())
        .padding(.trailing, 16)
        .padding(.bottom, 16)
    }

    // MARK: Songs

    /// Android SongsTabContent: the Random mix header only where Songs is a
    /// random sample (the mock library); Subsonic's songs come from the
    /// newest albums a page at a time, read on near the end, with the
    /// loading / retry foot; Apple Music lists its library songs.
    @ViewBuilder
    private var songsTab: some View {
        if model.store.songsAreRandomMix { randomMixHeader }
        songList.padding(.top, Tokens.library.GRID_TOP_PADDING.cg)
        if model.store.isReal { songsFoot }
    }

    /// Android RandomMixHeader: labelLarge onSurfaceVariant, a 20 shuffle in
    /// a 48 button; 16 start, 8 end.
    private var randomMixHeader: some View {
        HStack(spacing: 0) {
            Text("Random mix")
                .yoinRole(Tokens.type.labelLarge)
                .foregroundStyle(scheme.onSurfaceVariant)
            Spacer(minLength: 0)
            Button {
                model.play(songEntries, shuffle: true)
            } label: {
                YoinIcon("shuffle", size: 20)
                    .foregroundStyle(scheme.onSurfaceVariant)
                    .frame(width: 48, height: 48)
                    .contentShape(.circle)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Reshuffle")
        }
        .padding(.leading, 16)
        .padding(.trailing, 8)
    }

    /// Android LibrarySongsFoot: a loading indicator while more can follow,
    /// a Retry once a read failed, nothing at the end.
    @ViewBuilder
    private var songsFoot: some View {
        switch model.store.songsMore {
        case .none: EmptyView()
        case .available, .loading:
            RowSkeleton(scheme: scheme, rows: 2)
                .transition(.opacity.animation(Yoin.effects))
                .onAppear { Task { await model.store.loadMoreSongs() } }
        case .failed:
            RetryRow(scheme: scheme) { Task { await model.store.loadMoreSongs() } }
                .padding(.vertical, 12)
                .transition(.opacity.animation(Yoin.effects))
        }
    }

    /// Songs: the source's songs; mock: three per album.
    /// Favorites: the source's starred songs (mock: the starred tracks),
    /// minus any heart taken off here since (FavoritesStore, shared with NP).
    private var songEntries: [PlaylistEntry] {
        if filter == .favorites { return favoriteEntries }
        guard model.store.isReal else {
            guard LaunchConfig.demoContent else { return [] }
            return model.library.albums().flatMap { a in a.tracks.prefix(3).map { PlaylistEntry(album: a, track: $0) } }
        }
        return model.store.songs
    }

    private var favoriteEntries: [PlaylistEntry] {
        let hearts = FavoritesStore.shared
        let all = model.store.isReal
            ? model.store.favorites
            : !LaunchConfig.demoContent ? []
            : model.library.albums().flatMap { a in a.tracks.map { PlaylistEntry(album: a, track: $0) } }
        return all.filter { e in
            hearts.isFavorite(provider: e.track.id.provider, songId: e.track.id.rawId,
                              known: model.store.isReal ? true : e.track.starred)
        }
    }

    /// Starred artists, minus any star taken off on an artist page since.
    private var favoriteArtists: [LibraryItem] {
        guard model.store.isReal else { return [] }
        return model.store.favoriteArtists.filter { model.store.starredOverride["artist:\($0.id.rawId)"] != false }
    }

    private var favoriteAlbums: [Album] { model.store.isReal ? model.store.favoriteAlbums : [] }

    /// Android FavoritesTabContent: Artists (ArtistListItem), Albums
    /// (AlbumListItem), Songs, each under a SectionHeader (labelLarge
    /// onSurfaceVariant, 16 sides, 18 top, 6 bottom); nothing starred in any
    /// of them = the String(localized: "No favorites yet") panel.
    @ViewBuilder
    private var favoritesList: some View {
        let loaded = !model.store.isReal || model.store.phase("favorites") == .loaded
        let artists = favoriteArtists, albums = favoriteAlbums, songs = favoriteEntries
        if artists.isEmpty && albums.isEmpty && songs.isEmpty && loaded {
            EmptyPanel(text: String(localized: "No favorites yet"), scheme: scheme)
        } else {
            LazyVStack(alignment: .leading, spacing: 0) {
                if !artists.isEmpty {
                    sectionHeader(String(localized: "Artists"))
                    ForEach(artists) { a in
                        LibraryListRow(title: a.title, subtitle: subtitle(a), scheme: scheme) {
                            EntityBackdrop(entity: .artist, palette: a.palette, seed: a.id.rawId.count,
                                           size: Tokens.library.ROW_THUMB.cg)
                        } action: { open(a) }
                        .transition(.opacity.animation(Yoin.effects))
                    }
                }
                if !albums.isEmpty {
                    sectionHeader(String(localized: "Albums"))
                    ForEach(albums) { a in
                        LibraryListRow(title: a.title, subtitle: a.artist, scheme: scheme) {
                            CoverArt(palette: a.palette, cornerRadius: 0, seed: a.seed)
                                .frame(width: Tokens.library.ROW_THUMB.cg, height: Tokens.library.ROW_THUMB.cg)
                                .clipShape(.rect(cornerRadius: Tokens.shape.THUMB.cg, style: .continuous))
                        } action: { model.open(a) }
                        .transition(.opacity.animation(Yoin.effects))
                    }
                }
                if !songs.isEmpty { sectionHeader(String(localized: "Songs")) }
            }
            .animation(Yoin.spatial, value: artists.map(\.id))
            .animation(Yoin.spatial, value: albums.map(\.id))
            songList
        }
    }

    private func sectionHeader(_ title: String) -> some View {
        Text(title)
            .yoinRole(Tokens.type.labelLarge)
            .foregroundStyle(scheme.onSurfaceVariant)
            .padding(.horizontal, 16)
            .padding(.top, 18)
            .padding(.bottom, 6)
            .accessibilityAddTraits(.isHeader)
    }

    @ViewBuilder
    private var songList: some View {
        let entries = songEntries
        if entries.isEmpty && model.store.isReal {
            let key = filter == .favorites ? "favorites" : "songs"
            if model.store.phase(key) == .failed {
                RetryRow(scheme: scheme, message: model.store.failures[key]) { Task { await reload(force: true) } }
            } else if model.store.phase(key) != .loaded {
                RowSkeleton(scheme: scheme)
            } else if filter == .songs {
                EmptyPanel(text: String(localized: "No songs found"), scheme: scheme)
            }
        }
        // Keyed by song and occurrence (a song listed twice is two rows), so
        // arrivals, removals (a heart taken off) and pages move, not cut.
        let keyed = LibraryGrid.occurrenceKeys(entries.map { "\($0.track.id)" })
        LazyVStack(spacing: 0) {
            ForEach(Array(zip(keyed, entries.indices)), id: \.0) { _, i in
                let e = entries[i]
                LibrarySongRow(album: e.album, track: e.track, current: model.playback.track.id == e.track.id,
                               scheme: scheme)
                    .trackRow(for: e) { model.play(entries, startAt: i) }
                    .transition(.opacity.animation(Yoin.effects))
            }
        }
        .animation(Yoin.spatial, value: keyed)
    }

    // MARK: Server load states

    private struct LoadKey: Hashable {
        var generation: Int
        var filter: LibraryKind?
    }

    private var phaseKeys: [String] {
        switch filter {
        case .none: ["albums", "artists", "playlists"]
        case .albums?: ["albums"]
        case .artists?: ["artists"]
        case .playlists?: ["playlists"]
        case .songs?: ["songs"]
        case .favorites?: ["favorites"]
        }
    }

    private func reload(force: Bool) async {
        switch filter {
        case .none:
            async let a: Void = model.store.loadAlbums(force: force)
            async let b: Void = model.store.loadArtists(force: force)
            async let c: Void = model.store.loadPlaylists(force: force)
            _ = await (a, b, c)
        case .albums?: await model.store.loadAlbums(force: force)
        case .artists?: await model.store.loadArtists(force: force)
        case .playlists?: await model.store.loadPlaylists(force: force)
        case .songs?: await model.store.loadSongs(force: force)
        case .favorites?: await model.store.loadFavorites(force: force)
        }
    }

    /// The view's loading, failure and empty states. [empty]: nothing is
    /// shown yet (skeleton, Retry, or the view's empty panel once every list
    /// has answered); otherwise only a failed list's Retry, under the rows.
    @ViewBuilder
    private func loadState(empty: Bool) -> some View {
        let phases = phaseKeys.map { model.store.phase($0) }
        if phases.contains(.failed) {
            RetryRow(scheme: scheme, message: phaseKeys.lazy.compactMap { model.store.failures[$0] }.first) {
                Task { await reload(force: false) }
            }
                .padding(.top, empty ? 0 : 12)
                .transition(.opacity.animation(Yoin.effects))
        } else if empty && phases.contains(where: { $0 != .loaded }) {
            Group {
                if filter == .playlists {
                    RowSkeleton(scheme: scheme)
                } else {
                    GridSkeleton(cell: cell, gutter: Tokens.library.GRID_GUTTER_H.cg, scheme: scheme)
                        .padding(.horizontal, 16)
                }
            }
            .padding(.top, Tokens.library.GRID_TOP_PADDING.cg)
        } else if empty {
            emptyPanel
        }
    }

    /// Android EmptyState, per view (strings_library.xml).
    private var emptyPanel: some View {
        let text = switch filter {
        case nil: String(localized: "Your library is empty")
        case .albums?: String(localized: "No albums found")
        case .artists?: String(localized: "No artists found")
        case .playlists?: String(localized: "No playlists")
        case .songs?: String(localized: "No songs found")
        case .favorites?: String(localized: "No favorites yet")
        }
        return EmptyPanel(text: text, scheme: scheme)
    }
}

/// Android EmptyState: a Card (16) panel 16 in from each side,
/// surfaceContainerHighest at 72 %, bodyLarge onSurfaceVariant, 20 × 18.
struct EmptyPanel: View {
    var text: String
    var scheme: CoverScheme

    var body: some View {
        Text(text)
            .yoinRole(Tokens.type.bodyLarge)
            .foregroundStyle(scheme.onSurfaceVariant)
            .padding(.horizontal, Tokens.library.EMPTY_STATE_PADDING_H.cg)
            .padding(.vertical, Tokens.library.EMPTY_STATE_PADDING_V.cg)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(scheme.surfaceContainerHighest.opacity(Tokens.library.EMPTY_STATE_ALPHA),
                        in: .rect(cornerRadius: Tokens.shape.CARD.cg, style: .continuous))
            .padding(.horizontal, 16)
            .padding(.top, 48)
            .transition(.opacity.animation(Yoin.effects))
    }
}

/// Android ArtistListItem / AlbumListItem: 16 × 10, a 48 artwork, 12,
/// titleSmall over bodySmall onSurfaceVariant.
struct LibraryListRow<Art: View>: View {
    var title: String
    var subtitle: String
    var scheme: CoverScheme
    @ViewBuilder var art: Art
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: Tokens.library.ROW_GAP.cg) {
                art
                VStack(alignment: .leading, spacing: 0) {
                    Text(title).lineLimit(1).yoinRole(Tokens.type.titleSmall).foregroundStyle(scheme.onSurface)
                    Text(subtitle).lineLimit(1).yoinRole(Tokens.type.bodySmall).foregroundStyle(scheme.onSurfaceVariant)
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, Tokens.library.ROW_PADDING_H.cg)
            .padding(.vertical, Tokens.library.ROW_PADDING_V.cg)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
    }
}

/// Android PlaylistListItem: 16 × 10 padding, 48 Thumb (4), 12, titleSmall
/// over PlaylistMeta (owner, then "N tracks" as a stat).
struct PlaylistRow: View {
    var item: LibraryItem
    var scheme: CoverScheme
    var horizontalPadding: CGFloat = Tokens.library.ROW_PADDING_H.cg
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: Tokens.library.ROW_GAP.cg) {
                CoverArt(palette: item.palette, cornerRadius: 0, seed: 3)
                    .frame(width: Tokens.library.ROW_THUMB.cg, height: Tokens.library.ROW_THUMB.cg)
                    .clipShape(.rect(cornerRadius: Tokens.shape.THUMB.cg, style: .continuous))
                VStack(alignment: .leading, spacing: 0) {
                    Text(item.title)
                        .lineLimit(1)
                        .yoinRole(Tokens.type.titleSmall)
                        .foregroundStyle(scheme.onSurface)
                    meta
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, horizontalPadding)
            .padding(.vertical, Tokens.library.ROW_PADDING_V.cg)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
    }

    /// MetaLine: the owner plain; the count a Stat (number Medium tabular,
    /// unit Regular at 60 %); "Playlist" when the source says neither.
    @ViewBuilder
    private var meta: some View {
        let owner = item.owner?.trimmingCharacters(in: .whitespaces).nilIfEmpty
        let n = item.count ?? item.subtitle.split(separator: " ").compactMap { Int($0) }.first
        if owner == nil && n == nil {
            Text("Playlist").yoinRole(Tokens.type.bodySmall).foregroundStyle(scheme.onSurfaceVariant)
        } else {
            HStack(spacing: Tokens.metaLine.GROUP_GAP.cg) {
                if let owner {
                    Text(owner).lineLimit(1).yoinRole(Tokens.type.bodySmall).foregroundStyle(scheme.onSurface)
                }
                if let n {
                    HStack(spacing: Tokens.metaLine.INNER_GAP.cg) {
                        Text("\(n)").monospacedDigit().yoinRole(Tokens.type.bodySmall, weight: .medium)
                            .foregroundStyle(scheme.onSurface)
                        Text(n == 1 ? "track" : "tracks").yoinRole(Tokens.type.bodySmall)
                            .foregroundStyle(scheme.onSurface.opacity(Tokens.metaLine.MUTED_ALPHA))
                    }
                }
            }
        }
    }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}

/// Library's grid geometry, shared with Search (Android libraryGridCells).
enum LibraryGrid {
    /// Fixed 3 in a phone's window (Compact); in a wider window (regular
    /// width, or ≥ 600) the count follows the pane's [width] at a 96 minimum
    /// cell, and in a short landscape window (compact height) at 100; 12
    /// gaps, inside the 16 margins. Android decides by the window, counts by
    /// the pane.
    static func columns(width: CGFloat, regularWidth: Bool, compactHeight: Bool) -> Int {
        let gutter = Tokens.library.GRID_GUTTER_H.cg
        let available = width - 32
        if compactHeight { return max(3, Int((available + gutter) / (100 + gutter))) }
        guard regularWidth || width >= 600 else { return 3 }
        return max(3, Int((available + gutter) / (96 + gutter)))
    }

    /// A cell's side for [columns] across [width] (16 margins, 12 gaps).
    static func cell(width: CGFloat, columns: Int) -> CGFloat {
        let gutter = Tokens.library.GRID_GUTTER_H.cg
        return (width - 32 - CGFloat(columns - 1) * gutter) / CGFloat(columns)
    }

    /// Unique row keys: each id, and "#2", "#3"… for its repeats.
    static func occurrenceKeys(_ ids: [String]) -> [String] {
        var seen: [String: Int] = [:]
        return ids.map { id in
            let n = (seen[id] ?? 0) + 1
            seen[id] = n
            return n == 1 ? id : "\(id)#\(n)"
        }
    }
}

/// Library's sorted lists, kept until something the build read changes
/// (LibraryStore.home's pattern): the build runs under observation tracking
/// and the first change it saw bumps [revision], which the view reads.
@MainActor
@Observable
final class LibraryOrderMemo {
    struct Key: Equatable {
        var filter: LibraryKind?
        var sort: LibrarySortOrder
        var byYou: Bool
        var real: Bool
    }

    struct Value {
        var items: [LibraryItem]
        var sections: [LibraryFastScroller.Section]
    }

    private var revision = 0
    @ObservationIgnored private var cached: (key: Key, revision: Int, value: Value)?

    func value(for key: Key, build: () -> Value) -> Value {
        let revision = revision
        if let cached, cached.key == key, cached.revision == revision { return cached.value }
        let value = withObservationTracking(build) { [weak self] in
            // Called before the change lands: rebuild on the next turn.
            DispatchQueue.main.async { self?.revision &+= 1 }
        }
        cached = (key, revision, value)
        return value
    }
}

/// Library's retained view state (Android LibraryViewModel holds it): the
/// view picked, each view's sort, each view's scroll offset, By You.
@MainActor
@Observable
final class LibraryMemory {
    static let shared = LibraryMemory()

    enum Tab: Hashable {
        case all, albums, artists, playlists, songs, favorites

        init(_ kind: LibraryKind?) {
            switch kind {
            case nil: self = .all
            case .albums?: self = .albums
            case .artists?: self = .artists
            case .playlists?: self = .playlists
            case .songs?: self = .songs
            case .favorites?: self = .favorites
            }
        }
    }

    var filter: LibraryKind? = switch LaunchConfig.screen {
    case "library-albums": .albums
    case "library-songs": .songs
    case "library-artists": .artists
    case "library-playlists": .playlists
    case "library-favorites": .favorites
    default: nil
    }
    var sorts: [Tab: LibrarySortOrder] = [:]
    var byYou = false
    @ObservationIgnored var offsets: [Tab: CGFloat] = [:]

    /// The tab's chosen order while still offered, else the default (Recents).
    func sort(for tab: Tab, offered: [LibrarySortOrder]) -> LibrarySortOrder {
        if let chosen = sorts[tab], offered.contains(chosen) { return chosen }
        return offered.first ?? .recents
    }
}

// MARK: Filter row

/// Android ui/library/LibraryFilterRow.kt: one chip per view, none on = All.
/// With a chip on, a ✕ (44, the page margin from the edge) unfolds in front
/// of the row and turns it off again, as does tapping the chip that is on.
/// Playlists' By You sub-chip follows when the playlists are mixed.
struct LibraryFilterRow: View {
    typealias Style = SegmentChipStyle

    @Binding var filter: LibraryKind?
    /// The views offered ([kinds(favorites:)]).
    var kinds: [LibraryKind] = LibraryFilterRow.kinds(favorites: true)
    /// Playlists' By You sub-chip (Android LibraryByYouChip), where the
    /// playlists are mixed; nil hides it.
    var byYou: Binding<Bool>? = nil
    var scheme: CoverScheme
    var style: Style
    var overContent = true

    static func kinds(favorites: Bool) -> [LibraryKind] {
        [.playlists, .artists, .albums, .songs] + (favorites ? [.favorites] : [])
    }

    /// A view kept from another account that this one doesn't offer reads as All.
    static func offered(_ kind: LibraryKind?, kinds: [LibraryKind]) -> LibraryKind? {
        kind.flatMap { kinds.contains($0) ? $0 : nil }
    }

    /// Android PlaylistsByYou: offered only when the playlists are mixed (some the user's, some
    /// someone else's, by the source's ownedByMe), so it never empties the list.
    static func byYouAvailable(_ playlists: [LibraryItem]) -> Bool {
        playlists.contains { $0.ownedByMe == true } && playlists.contains { $0.ownedByMe == false }
    }

    enum Chip: Hashable {
        case kind(LibraryKind), byYou

        var label: String {
            switch self {
            case .kind(let k): k.title
            case .byYou: String(localized: "By You")
            }
        }
    }

    var body: some View {
        SegmentChipRow(
            groups: [kinds.map(Chip.kind)] + (byYou == nil ? [] : [[Chip.byYou]]),
            selected: (filter.map { [Chip.kind($0)] } ?? []) + (byYou?.wrappedValue == true ? [.byYou] : []),
            label: \.label,
            onTap: { chip in
                withAnimation(Yoin.spatial) {
                    switch chip {
                    case .kind(let kind): filter = filter == kind ? nil : kind
                    case .byYou: byYou?.wrappedValue.toggle()
                    }
                }
            },
            onClear: filter == nil ? nil : { withAnimation(Yoin.spatial) { filter = nil } },
            scheme: scheme,
            style: style,
            overContent: overContent
        )
    }
}

// MARK: Shared with Search

/// The look Glass Lab › Filter chips picks (`-libraryFilterStyle 0…4`) for
/// every segment chip row (Library's filters, Search's scopes and types):
/// - glassTrack (World's default): the HIG segmented look. One neutral glass
///   capsule (the search field's glass) holds the chips as plain labels; the
///   chip that is on is a lozenge of tinted glass inside it, concentric with
///   the track, that slides from chip to chip. The row's one accent is the
///   selected Library tab's (secondary); in Search the scope pair's lozenge
///   is a quiet neutral one, so the result type is the only accent.
/// - glassPills: every chip its own glass capsule (Android's 44 / 18 / 8
///   gaps), the chip that is on in the same tinted glass, all in one
///   GlassEffectContainer.
/// - scrollLitTrack: glassTrack, but the track's glass materializes only
///   while content has scrolled under the row; at rest the labels sit flat
///   on the page (R1: no glass until something moves under it).
/// - android: ExpressiveSegmentChip as is: solid 44-high chips, corner 18,
///   surfaceContainerHigh with onSurfaceVariant, on = secondaryContainer
///   with onSecondaryContainer; the ✕ is a tonal circle. No glass.
/// - slidingTonal: the android chips, but the secondaryContainer fill slides
///   from chip to chip instead of cross-fading in place.
enum SegmentChipStyle: Int {
    case glassTrack = 0, android = 1, slidingTonal = 2, glassPills = 3, scrollLitTrack = 4

    /// Glass Lab's choice; Android-faithful when unset.
    @MainActor static var current: SegmentChipStyle {
        SegmentChipStyle(rawValue: Int((GlassLab.shared.extras["libraryFilterStyle"] ?? 1).rounded())) ?? .android
    }

    /// The chips sit in one capsule track.
    var isTrack: Bool { self == .glassTrack || self == .scrollLitTrack }
    /// The chips (or their track) are glass, the ✕ a glass circle.
    var isGlass: Bool { isTrack || self == .glassPills }
}

/// Android ExpressiveSegmentRow of ExpressiveSegmentChips: single-choice
/// groups of chips, 8 apart, a 1 × 24 outlineVariant hairline between groups
/// (Search's scope pair | result types). An optional ✕ ([onClear]) unfolds
/// in front and stays put while the chips scroll; the chips' start inset
/// gives way from 16 to the 8 gap after it. The row fades only at an edge it
/// has been scrolled past (Android horizontalEdgeFadeOnScroll, 24), and a
/// chip turned on scrolls itself into view (rememberRevealOnArrival).
struct SegmentChipRow<ID: Hashable>: View {
    var groups: [[ID]]
    /// The chips that are on, at most one per group.
    var selected: [ID]
    var label: (ID) -> String
    var onTap: (ID) -> Void
    var onClear: (() -> Void)?
    var scheme: CoverScheme
    var style: SegmentChipStyle
    /// Content has scrolled under the row (scrollLitTrack lights the track).
    var overContent = true

    @State private var fadeLeading = false
    @State private var fadeTrailing = false
    /// Each chip's frame in the row's content, for the sliding pill and the
    /// reveal; the scroll view's offset, width and content width.
    @State private var frames: [ID: CGRect] = [:]
    @State private var view = CGSize.zero
    @State private var offset: CGFloat = 0
    @State private var contentWidth: CGFloat = 0
    @State private var position = ScrollPosition()
    /// The chips on at launch are brought into view once, without motion.
    @State private var arrived = false
    @Namespace private var glassNS
    @Environment(\.colorScheme) private var colorScheme

    private var height: CGFloat { Tokens.library.CHIP_HEIGHT.cg }
    private var corner: CGFloat { Tokens.library.CHIP_CORNER.cg }
    private var gap: CGFloat { Tokens.library.CHIP_GAP.cg }
    /// Android ChipRevealMargin / the edge fade's width.
    private let fade: CGFloat = 24
    /// Android SearchFilterDividerHeight.
    private let hairline: CGFloat = 24
    /// The track's inset around the lozenge: 44 track, 36 lozenge (corner
    /// 18, Android's chip corner), concentric.
    private let trackInset: CGFloat = 4
    private var showsClear: Bool { onClear != nil }
    private var trackLit: Bool { style == .glassTrack || (style == .scrollLitTrack && overContent) }

    var body: some View {
        Group {
            if style == .glassPills {
                GlassEffectContainer(spacing: gap / 2) { row }
            } else {
                row
            }
        }
        .sensoryFeedback(.selection, trigger: selected)
    }

    private var row: some View {
        HStack(spacing: 0) {
            if showsClear {
                clearButton
                    .padding(.leading, 16)
                    .transition(.scale(scale: 0.6, anchor: .leading).combined(with: .opacity))
            }
            if style.isTrack {
                scroller
                    .clipShape(.capsule)
                    // The track glass sits behind the labels, in no container
                    // (a container lifts background glass over them; as the
                    // glass's content they'd lose the edge fade's mask).
                    .background { Color.clear.modifier(TrackGlass(lit: trackLit, scheme: scheme)) }
                    .animation(Yoin.effects, value: trackLit)
                    .padding(.leading, showsClear ? gap : 16)
                    .padding(.trailing, 16)
            } else {
                scroller
            }
        }
        .padding(.vertical, 4)
    }

    private var clearButton: some View {
        Button { onClear?() } label: {
            YoinIcon("close", size: Tokens.library.CLEAR_ICON.cg)
                .foregroundStyle(style.isGlass ? AnyShapeStyle(.primary) : AnyShapeStyle(scheme.onSurfaceVariant))
                .frame(width: height, height: height)
                .background {
                    if !style.isGlass { Circle().fill(scheme.surfaceContainerHigh) }
                }
                .contentShape(.circle)
        }
        .buttonStyle(PressScale())
        .modifier(GlassIf(on: style.isGlass, scheme: scheme))
        .accessibilityLabel("Clear filter")
    }

    private var scroller: some View {
        ScrollView(.horizontal) {
            HStack(spacing: style.isTrack ? 0 : gap) {
                ForEach(Array(groups.enumerated()), id: \.offset) { i, group in
                    if i > 0 { divider }
                    ForEach(group, id: \.self) { id in
                        chip(id, accent: i == groups.count - 1)
                            .onGeometryChange(for: CGRect.self) { $0.frame(in: .named(Self.space)) } action: {
                                frames[id] = $0
                                revealOnArrival()
                            }
                    }
                }
            }
            // The sliding styles draw every fill in one layer under all the
            // labels, so the pill never passes over a neighbour's label. The
            // frames are the HStack's own, so the inset giving way to the ✕
            // carries fills and labels together.
            .background(alignment: .topLeading) {
                if style == .slidingTonal { slidingFills } else if style.isTrack { lozenges }
            }
            .coordinateSpace(.named(Self.space))
            .padding(.leading, leadingInset)
            .padding(.trailing, style.isTrack ? trackInset : 16)
        }
        .scrollIndicators(.hidden)
        .scrollPosition($position)
        .onScrollGeometryChange(for: [CGFloat].self) { g in
            [g.contentOffset.x + g.contentInsets.leading, g.containerSize.width, g.contentSize.width]
        } action: { _, m in
            offset = m[0]; view.width = m[1]; contentWidth = m[2]
            let edges = (offset > 1, offset + view.width < contentWidth - 1)
            if edges != (fadeLeading, fadeTrailing) {
                withAnimation(Yoin.effects) { fadeLeading = edges.0; fadeTrailing = edges.1 }
            }
            revealOnArrival()
        }
        .mask { edgeFade }
        .onChange(of: selected) { old, new in
            for id in new where !old.contains(id) {
                reveal(id)
                // Again as a ✕ lands: unfolding in front of the row, it narrows it.
                Task { @MainActor in
                    try? await Task.sleep(for: .milliseconds(380))
                    if selected.contains(id) { reveal(id) }
                }
            }
        }
    }

    /// Fades only an edge the row has been scrolled past. In the track the
    /// fade runs out to the rim, so a label dissolves into the capsule's
    /// round end instead of being cut by it; it eases (no hard start).
    private var edgeFade: some View {
        let w = style.isTrack ? fade + 8 : fade
        let stops: [Gradient.Stop] = [.init(color: .clear, location: 0), .init(color: .black.opacity(0.35), location: 0.35),
                                      .init(color: .black.opacity(0.85), location: 0.7), .init(color: .black, location: 1)]
        return HStack(spacing: 0) {
            LinearGradient(stops: stops, startPoint: .leading, endPoint: .trailing)
                .frame(width: fadeLeading ? w : 0)
            Rectangle()
            LinearGradient(stops: stops, startPoint: .trailing, endPoint: .leading)
                .frame(width: fadeTrailing ? w : 0)
        }
        .padding(.vertical, -8)
    }

    /// Between groups (Search's scope pair | result types). In the track a
    /// short, faint rule with room either side, so it reads as a pause
    /// rather than a stroke; Android's 1 × 24 outlineVariant elsewhere.
    private var divider: some View {
        Rectangle()
            .fill(style.isTrack ? scheme.onSurface.opacity(colorScheme == .dark ? 0.22 : 0.16) : scheme.outlineVariant)
            .frame(width: 1, height: style.isTrack ? 18 : hairline)
            .padding(.horizontal, style.isTrack ? 8 : 0)
    }

    /// Scrolls only as far as it takes to bring the chip whole into view,
    /// the fade's width clear of either end.
    private func reveal(_ id: ID, animated: Bool = true) {
        guard let r = frames[id], view.width > 0 else { return }
        let f = r.offsetBy(dx: leadingInset, dy: 0)
        let maxOffset = max(contentWidth - view.width, 0)
        var target = offset
        if f.minX < offset + fade { target = f.minX - fade }
        else if f.maxX > offset + view.width - fade { target = f.maxX + fade - view.width }
        target = min(max(target, 0), maxOffset)
        guard abs(target - offset) > 0.5 else { return }
        if animated {
            withAnimation(Yoin.spatial) { position.scrollTo(x: target) }
        } else {
            position.scrollTo(x: target)
        }
    }

    /// Opening on a chip that is on past the edge (Search's type, say)
    /// starts scrolled to it.
    private func revealOnArrival() {
        guard !arrived, view.width > 0, contentWidth > 0,
              selected.allSatisfy({ frames[$0] != nil }) else { return }
        arrived = true
        if let last = selected.last { reveal(last, animated: false) }
    }

    private static var space: String { "segmentChips" }

    /// 16 at rest; the 8 gap after the ✕ while it shows. The track's own
    /// inset inside it.
    private var leadingInset: CGFloat {
        style.isTrack ? trackInset : (showsClear ? gap : 16)
    }

    /// `accent`: the chip's group is the row's last (the one accent; an
    /// earlier group, Search's scope pair, gets a quiet lozenge in a track).
    private func chip(_ id: ID, accent: Bool) -> some View {
        let on = selected.contains(id)
        let pillShape = RoundedRectangle(cornerRadius: corner, style: .continuous)
        return Button { onTap(id) } label: {
            Text(label(id))
                .lineLimit(1)
                .yoinRole(Tokens.type.labelLarge)
                .foregroundStyle(ink(on: on, accent: accent))
                .animation(Yoin.effects, value: on)
                .padding(.horizontal, Tokens.library.CHIP_PADDING_H.cg)
                .padding(.vertical, Tokens.library.CHIP_PADDING_V.cg)
                .frame(minHeight: style.isTrack ? height - trackInset * 2 : height)
                .background {
                    if style == .android {
                        RoundedRectangle(cornerRadius: corner, style: .circular)
                            .fill(on ? scheme.secondaryContainer : scheme.surfaceContainerHigh)
                            .animation(Yoin.effects, value: on)
                    }
                }
                .modifier(ChipPillGlass(on: style == .glassPills ? on : nil, accent: accent,
                                        scheme: scheme, shape: pillShape))
                .padding(.vertical, style.isTrack ? trackInset : 0)
                .contentShape(.rect)
        }
        .buttonStyle(PressScale())
        .accessibilityAddTraits(on ? .isSelected : [])
    }

    private func ink(on: Bool, accent: Bool) -> Color {
        switch style {
        case .android, .slidingTonal: on ? scheme.onSecondaryContainer : scheme.onSurfaceVariant
        default: on && accent ? scheme.onSecondary : scheme.onSurface
        }
    }

    /// slidingTonal: a surfaceContainerHigh fill on every chip, then one
    /// pill per group. A group's pill moves to its chip that is on with the
    /// selection's spatial spring.
    private var slidingFills: some View {
        ZStack(alignment: .topLeading) {
            ForEach(groups.flatMap { $0 }, id: \.self) { id in
                if let f = frames[id] { fill(scheme.surfaceContainerHigh, f) }
            }
            ForEach(Array(groups.enumerated()), id: \.offset) { _, group in
                if let on = group.first(where: selected.contains), let f = frames[on] {
                    fill(scheme.secondaryContainer, f)
                        .transition(.opacity.animation(Yoin.effects))
                }
            }
        }
    }

    private func fill(_ color: Color, _ f: CGRect) -> some View {
        RoundedRectangle(cornerRadius: corner, style: .circular)
            .fill(color)
            .frame(width: f.width, height: f.height)
            .offset(x: f.minX, y: f.minY)
    }

    /// The track styles' lozenges: one per group, tinted glass sliding
    /// between that group's chips with the selection's spring (the frame
    /// and offset animate; the glass keeps its identity per group, so it
    /// moves rather than reappears). Turning a group off dematerializes it.
    private var lozenges: some View {
        // Their own container (labels stay out of it, drawn over them), so
        // a lozenge morphs by its ID and materializes in and out.
        GlassEffectContainer {
        ZStack(alignment: .topLeading) {
            ForEach(Array(groups.enumerated()), id: \.offset) { i, group in
                if let on = group.first(where: selected.contains), let f = frames[on] {
                    let accent = i == groups.count - 1
                    let r = f.insetBy(dx: 0, dy: trackInset)
                    Capsule().fill(.clear)
                        .glassEffect(accent
                                     ? .regular.tint(scheme.secondary.opacity(lozengeOpacity)).interactive()
                                     : .regular.tint(scheme.surfaceContainerHighest.opacity(0.7)).interactive(),
                                     in: .capsule)
                        .glassEffectID("lozenge\(i)", in: glassNS)
                        .glassEffectTransition(.materialize)
                        .frame(width: r.width, height: r.height)
                        .offset(x: r.minX, y: r.minY)
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        }
    }

    /// The bar's selected tab strength (World 0.85), so the lozenge and the
    /// Library tab read as one accent.
    private var lozengeOpacity: Double {
        GlassLab.shared.tintOpacity("barSelected", dark: colorScheme == .dark)
    }
}

/// The track: the search field's glass (one pinned header), or none yet
/// (scrollLitTrack at rest).
private struct TrackGlass: ViewModifier {
    var lit: Bool
    var scheme: CoverScheme

    func body(content: Content) -> some View {
        let o = GlassLab.shared.extra("searchChromeTint")
        let glass: Glass = o > 0 ? .regular.tint(scheme.surfaceContainerHighest.opacity(o)) : .regular
        content.glassEffect(lit ? glass : .identity, in: .capsule)
    }
}

/// glassPills: each chip its own glass, the one that is on in the accent.
private struct ChipPillGlass<S: Shape>: ViewModifier {
    /// nil: not the pills style.
    var on: Bool?
    var accent: Bool
    var scheme: CoverScheme
    var shape: S
    @Environment(\.colorScheme) private var colorScheme

    func body(content: Content) -> some View {
        if let on {
            content.glassEffect(
                on && accent ? .regular.tint(scheme.secondary.opacity(GlassLab.shared.tintOpacity("barSelected", dark: colorScheme == .dark))).interactive()
                    : on ? .regular.tint(scheme.surfaceContainerHighest.opacity(0.9)).interactive()
                    : .regular.tint(scheme.surfaceContainerHighest.opacity(GlassLab.shared.extra("searchChromeTint"))).interactive(),
                in: shape)
        } else {
            content
        }
    }
}

/// Interactive circle glass for the glass-track ✕ only.
private struct GlassIf: ViewModifier {
    var on: Bool
    var scheme: CoverScheme
    func body(content: Content) -> some View {
        if on { content.labSearchGlass(scheme: scheme, in: .circle) } else { content }
    }
}

/// Android component/SongListItem.kt: 16 × 10 padding, 48 Thumb cover
/// (corner 4), 12 gap; titleSmall (primary with the equalizer when current)
/// over MetaLine (bodySmall: artist onSurface, 12, album onSurface at 60 %);
/// 6, then the duration in labelLarge tabular, onSurfaceVariant. 68 tall.
struct LibrarySongRow: View {
    var album: Album
    var track: Track
    var current: Bool
    var scheme: CoverScheme
    var horizontalPadding: CGFloat = Tokens.library.ROW_PADDING_H.cg
    /// Search results of a service that adds to a library: the + / ✓ at the row's end (Android SearchSongRow).
    var libraryAdd: LibraryAddControl? = nil

    /// What the + / ✓ shows and does for this row.
    struct LibraryAddControl {
        var membership: LibraryMembership
        var working: Bool
        var feedback: LibraryAddFeedback?
        var add: () -> Void
    }

    private var hasNote: Bool {
        track.id.provider != "mock" && !NotesStore.shared.notes(provider: track.id.provider, trackId: track.id.rawId).isEmpty
    }

    var body: some View {
        HStack(spacing: Tokens.library.ROW_GAP.cg) {
            CoverArt(palette: album.palette, cornerRadius: 0, seed: album.seed)
                .frame(width: Tokens.library.ROW_THUMB.cg, height: Tokens.library.ROW_THUMB.cg)
                .clipShape(.rect(cornerRadius: Tokens.shape.THUMB.cg, style: .continuous))
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 0) {
                    if current {
                        YoinIcon("equalizer", size: 16).foregroundStyle(scheme.primary).padding(.trailing, 6)
                    }
                    Text(track.title)
                        .lineLimit(1)
                        .yoinRole(Tokens.type.titleSmall)
                        .foregroundStyle(current ? scheme.primary : scheme.onSurface)
                    // Android SongListItem: the song has notes (12, primary at 72 %, 6 after the title).
                    if hasNote {
                        YoinIcon("note", size: 12)
                            .foregroundStyle(scheme.primary.opacity(0.72))
                            .padding(.leading, 6)
                            .accessibilityLabel("Has note")
                    }
                }
                // The song's own artist, else the album's (a compilation's rows).
                LibraryMetaLine(parts: [(track.artist ?? album.artist, false), (album.title, true)], scheme: scheme)
            }
            Spacer(minLength: 6)
            Text(formatTime(track.durationSec))
                .monospacedDigit()
                .yoinRole(Tokens.type.labelLarge)
                .foregroundStyle(scheme.onSurfaceVariant)
            if let libraryAdd {
                SongLibraryButton(membership: libraryAdd.membership, working: libraryAdd.working, scheme: scheme,
                                  action: libraryAdd.add)
                    .padding(.trailing, -8)
            }
        }
        .padding(.horizontal, horizontalPadding)
        .padding(.vertical, Tokens.library.ROW_PADDING_V.cg)
        .contentShape(.rect)
        .overlay(alignment: .bottomLeading) { addedFeedback }
    }

    /// The inline answer under the row once the song was added or failed (Android: bodySmall, primary /
    /// error, indented to the title).
    @ViewBuilder private var addedFeedback: some View {
        if let f = libraryAdd?.feedback {
            Text(f.message)
                .yoinRole(Tokens.type.bodySmall)
                .foregroundStyle(f.isError ? Color.red : libraryAdd?.membership == .added ? scheme.primary : scheme.onSurfaceVariant)
                .padding(.leading, horizontalPadding + Tokens.library.ROW_THUMB.cg + Tokens.library.ROW_GAP.cg)
                .padding(.bottom, 1)
                .transition(.opacity)
        }
    }
}

/// Android component/MetaLine.kt: groups 12 apart, no separator glyph; a
/// muted group is the ink at 60 %. bodySmall, ink onSurface.
struct LibraryMetaLine: View {
    var parts: [(String, Bool)]
    var scheme: CoverScheme

    var body: some View {
        HStack(spacing: Tokens.metaLine.GROUP_GAP.cg) {
            ForEach(Array(parts.enumerated()), id: \.offset) { _, part in
                Text(part.0)
                    .lineLimit(1)
                    .yoinRole(Tokens.type.bodySmall)
                    .foregroundStyle(scheme.onSurface.opacity(part.1 ? Tokens.metaLine.MUTED_ALPHA : 1))
            }
        }
    }
}
