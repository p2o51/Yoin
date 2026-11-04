import SwiftUI
import YoinShared

/// Library root, ported from Android ui/library/LibraryScreen.kt (Compact):
/// search pill + avatar header (no title on Compact), filter chips with the
/// ✕ that appears while one is on, the sort row as the grid's first row, and
/// the All grid fixed at 3 columns (16 sides, 12 / 16 gaps) mixing artists
/// (circles) with albums and playlists (bare covers, as on iOS Home). Songs
/// is a 68 pt row list under the Random mix header. Numbers are cited in
/// iosApp/docs/fidelity/library.md.
///
/// iOS adaptations: the pill opens the shared Search screen; covers follow
/// the iOS bare-cover rule; the header floats in glass over the grid, the
/// filter chips are the style Glass Lab picks under it (LibraryFilterRow;
/// World: one glass track with a tinted lozenge).
struct LibraryView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.zoomNamespace) private var zoomNS
    @State private var filter: LibraryKind? = switch LaunchConfig.screen {
    case "library-albums": .albums
    case "library-songs": .songs
    case "library-artists": .artists
    case "library-playlists": .playlists
    case "library-favorites": .favorites
    default: nil
    }
    @State private var width: CGFloat = 370
    @State private var sort = "Recents"
    @State private var position = ScrollPosition()
    @State private var metrics = ScrollMetrics()
    @State private var awake = LaunchConfig.screen == "library-scroller"
    @State private var held = LaunchConfig.screen == "library-scroller"
    @State private var hideTask: Task<Void, Never>?

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

    /// Sorted items for the current sort (mock: Recents keeps library order).
    private var items: [LibraryItem] {
        let base = filtered
        switch sort {
        case "Alphabetical": return base.sorted { $0.title.localizedCompare($1.title) == .orderedAscending }
        case "Creator": return base.sorted { creator($0).localizedCompare(creator($1)) == .orderedAscending }
        case "Recently added":
            if model.store.isReal { return base.sorted { ($0.album?.created ?? "") > ($1.album?.created ?? "") } }
            return base.sorted { ($0.album?.year ?? 0) > ($1.album?.year ?? 0) }
        default: return base
        }
    }

    private func creator(_ i: LibraryItem) -> String { i.kind == .artist ? i.title : (i.album?.artist ?? "") }

    /// Fast-scroller sections per sort (Android LibraryIndex / LibraryScrollIndex).
    private var sections: [LibraryFastScroller.Section] {
        var out: [LibraryFastScroller.Section] = []
        for (i, item) in items.enumerated() {
            let label: String
            switch sort {
            case "Alphabetical": label = String(item.title.prefix(1)).uppercased()
            case "Creator": label = String(creator(item).prefix(1)).uppercased()
            case "Recently added": label = item.album.map { String($0.year) } ?? "#"
            default: label = i < 3 ? "Today" : i < 8 ? "This week" : i < 16 ? "This month" : "Not opened"
            }
            if out.last?.label != label { out.append(.init(label: label, start: i)) }
        }
        return out
    }

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
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                if filter == .songs {
                    // Android SongsTabContent: Random mix header, then the
                    // list's 8 top padding.
                    randomMixHeader
                    songList.padding(.top, Tokens.library.GRID_TOP_PADDING.cg)
                } else if filter == .favorites {
                    // Android FavoritesTabContent: 8 top padding, then the
                    // starred songs under their "Songs" section header.
                    favoritesList.padding(.top, Tokens.library.GRID_TOP_PADDING.cg)
                } else {
                    // Grid contentPadding top 8 (LibraryScreen.kt AllTabContent).
                    grid.padding(.top, Tokens.library.GRID_TOP_PADDING.cg)
                    if items.isEmpty && model.store.isReal { loadState }
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
            wake()
        }
        .overlay {
            // Grid views only (All, Artists, Albums), past three screens.
            if filter == nil || filter == .artists || filter == .albums {
                LibraryFastScroller(
                    fraction: held && LaunchConfig.screen == "library-scroller" && metrics.offset == 0
                        ? 0.45 : metrics.fraction(items.count, columns: 3),
                    worthShowing: metrics.worthShowing(items.count, columns: 3),
                    awake: awake, sections: sections, itemCount: items.count, columns: 3, scheme: scheme,
                    onDrag: { f in
                        position.scrollTo(y: metrics.offset(for: f, items.count, columns: 3))
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
        .onAppear {
            if LaunchConfig.screen == "library-scroller" {
                Task { @MainActor in
                    try? await Task.sleep(for: .milliseconds(400))
                    position.scrollTo(y: metrics.offset(for: 0.45, items.count, columns: 3))
                }
            }
        }
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
            Button { model.openSettings() } label: {
                Text("5")
                    .font(.gsf(14, .semibold))
                    .foregroundStyle(scheme.onPrimary)
                    .frame(width: 32, height: 32)
                    .background(scheme.primary, in: CookieShape(lobes: 6, depth: 0.16))
                    .padding(1.5)
                    .background(scheme.surfaceContainer, in: CookieShape(lobes: 6, depth: 0.16))
                    .frame(width: 48, height: 48)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Account")
        }
        .padding(.horizontal, 16)
        .padding(.top, SearchView.headerTop)
        .padding(.bottom, 4)
    }

    // MARK: Chips

    /// Android LibraryFilterRow, in the style Glass Lab picks (see
    /// LibraryFilterRow below; Android-faithful tonal chips by default).
    private var chips: some View {
        LibraryFilterRow(filter: $filter, scheme: scheme, style: LibraryFilterRow.Style.current,
                         overContent: metrics.offset > 1)
    }

    // MARK: All / Artists / Albums / Playlists grid

    private var grid: some View {
        let gutter = Tokens.library.GRID_GUTTER_H.cg
        let cell = (width - 32 - 2 * gutter) / 3
        return LazyVGrid(columns: Array(repeating: GridItem(.fixed(cell), spacing: gutter, alignment: .top), count: 3),
                         alignment: .leading, spacing: Tokens.library.GRID_GUTTER_V.cg) {
            Section {
                ForEach(items) { item in
                    gridCell(item, side: cell)
                }
            } header: {
                sortRow
            }
        }
        .padding(.horizontal, 16)
        .animation(Yoin.spatial, value: filter)
    }

    /// The sort row: label + sort symbol, as the grid's first row.
    private var sortRow: some View {
        Menu {
            ForEach(["Recents", "Recently added", "Alphabetical", "Creator"], id: \.self) { option in
                Button {
                    withAnimation(Yoin.spatial) { sort = option }
                } label: {
                    if option == sort {
                        Label { Text(option) } icon: { YoinIcon("check", size: 18) }
                    } else {
                        Text(option)
                    }
                }
            }
        } label: {
            HStack(spacing: 6) {
                Text(sort).yoinRole(Tokens.type.labelLarge).foregroundStyle(scheme.onSurface)
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
        Button {
            if isArtist { model.openArtist(item.title, id: item.id.provider == "mock" ? nil : item.id.rawId) }
            else if item.kind == .playlist { model.openPlaylist(item.id.rawId) }
            else if let album = item.album { model.open(album) }
        } label: {
            VStack(alignment: isArtist ? .center : .leading, spacing: 0) {
                EntityBackdrop(entity: isArtist ? .artist : .album, palette: item.palette,
                               seed: item.id.rawId.count, size: side, stroke: scheme.primary)
                    .zoomSource(item.id, zoomNS)
                Spacer().frame(height: Tokens.library.COVER_TO_TITLE.cg)
                // AlbumCard / ArtistGridItem: titleSmall over bodySmall (12).
                Text(item.title)
                    .lineLimit(1)
                    .yoinRole(Tokens.type.titleSmall)
                    .foregroundStyle(scheme.onSurface)
                Text(subtitle(item))
                    .lineLimit(1)
                    .yoinRole(Tokens.type.bodySmall)
                    .foregroundStyle(scheme.onSurfaceVariant)
            }
            .frame(width: side, alignment: isArtist ? .center : .leading)
        }
        .buttonStyle(PressScale())
    }

    private func subtitle(_ item: LibraryItem) -> String {
        switch item.kind {
        case .artist:
            let n = item.count ?? model.library.albums().filter { $0.artist == item.title }.count
            return n == 1 ? "1 album" : "\(n) albums"
        case .album: return item.album?.artist ?? ""
        case .playlist: return "Playlist"
        }
    }

    // MARK: Songs

    /// Android RandomMixHeader: labelLarge onSurfaceVariant, a 20 shuffle in
    /// a 48 button; 16 start, 8 end.
    private var randomMixHeader: some View {
        HStack(spacing: 0) {
            Text("Random mix")
                .yoinRole(Tokens.type.labelLarge)
                .foregroundStyle(scheme.onSurfaceVariant)
            Spacer(minLength: 0)
            Button {
                if filter == .songs { Task { await model.store.loadSongs(force: true) } }
                else { model.play(songEntries, shuffle: true) }
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

    /// Songs: the server's random mix; mock: three per album.
    /// Favorites: the source's starred songs (mock: the starred tracks),
    /// minus any heart taken off here since (FavoritesStore, shared with NP).
    private var songEntries: [PlaylistEntry] {
        if filter == .favorites { return favoriteEntries }
        guard model.store.isReal else {
            return model.library.albums().flatMap { a in a.tracks.prefix(3).map { PlaylistEntry(album: a, track: $0) } }
        }
        return model.store.songs
    }

    private var favoriteEntries: [PlaylistEntry] {
        let hearts = FavoritesStore.shared
        let all = model.store.isReal
            ? model.store.favorites
            : model.library.albums().flatMap { a in a.tracks.map { PlaylistEntry(album: a, track: $0) } }
        return all.filter { e in
            hearts.isFavorite(provider: e.track.id.provider, songId: e.track.id.rawId,
                              known: model.store.isReal ? true : e.track.starred)
        }
    }

    /// Android FavoritesTabContent (songs): SectionHeader "Songs" (labelLarge
    /// onSurfaceVariant, 16 sides, 18 top, 6 bottom) over the rows; nothing
    /// starred = the "No favorites yet" panel.
    @ViewBuilder
    private var favoritesList: some View {
        let loaded = !model.store.isReal || model.store.phase("favorites") == .loaded
        if favoriteEntries.isEmpty && loaded {
            Text("No favorites yet")
                .yoinRole(Tokens.type.bodyLarge)
                .foregroundStyle(scheme.onSurfaceVariant)
                .padding(.horizontal, 20)
                .padding(.vertical, 18)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(scheme.surfaceContainerHighest.opacity(0.72),
                            in: .rect(cornerRadius: Tokens.shape.CARD.cg, style: .continuous))
                .padding(.horizontal, 16)
                .padding(.top, 48)
                .transition(.opacity)
        } else {
            if !favoriteEntries.isEmpty {
                Text("Songs")
                    .yoinRole(Tokens.type.labelLarge)
                    .foregroundStyle(scheme.onSurfaceVariant)
                    .padding(.horizontal, 16)
                    .padding(.top, 18)
                    .padding(.bottom, 6)
                    .accessibilityAddTraits(.isHeader)
            }
            songList
        }
    }

    @ViewBuilder
    private var songList: some View {
        let entries = songEntries
        if entries.isEmpty && model.store.isReal {
            let key = filter == .favorites ? "favorites" : "songs"
            if model.store.phase(key) == .failed {
                RetryRow(scheme: scheme) { Task { await reload(force: true) } }
            } else if model.store.phase(key) != .loaded {
                RowSkeleton(scheme: scheme)
            }
        }
        LazyVStack(spacing: 0) {
            ForEach(Array(entries.enumerated()), id: \.offset) { i, e in
                LibrarySongRow(album: e.album, track: e.track, current: model.playback.track.id == e.track.id,
                               scheme: scheme)
                    .onTapGesture { model.play(entries, startAt: i) }
                    .trackMenu(for: e)
            }
        }
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
            await model.store.loadAlbums(force: force)
            await model.store.loadArtists(force: force)
            await model.store.loadPlaylists(force: force)
        case .albums?: await model.store.loadAlbums(force: force)
        case .artists?: await model.store.loadArtists(force: force)
        case .playlists?: await model.store.loadPlaylists(force: force)
        case .songs?: await model.store.loadSongs(force: force)
        case .favorites?: await model.store.loadFavorites(force: force)
        }
    }

    @ViewBuilder
    private var loadState: some View {
        let phases = phaseKeys.map { model.store.phase($0) }
        if phases.contains(.failed) {
            RetryRow(scheme: scheme) { Task { await reload(force: false) } }
        } else if phases.contains(where: { $0 != .loaded }) {
            let gutter = Tokens.library.GRID_GUTTER_H.cg
            GridSkeleton(cell: (width - 32 - 2 * gutter) / 3, gutter: gutter, scheme: scheme)
                .padding(.horizontal, 16)
        }
    }
}

// MARK: Filter row

/// Android ui/library/LibraryFilterRow.kt: one chip per view, none on = All.
/// With a chip on, a ✕ (44, the page margin from the edge) unfolds in front
/// of the row and turns it off again, as does tapping the chip that is on.
/// Playlists' By You sub-chip has no iOS data yet, so it is not here.
struct LibraryFilterRow: View {
    typealias Style = SegmentChipStyle

    @Binding var filter: LibraryKind?
    var scheme: CoverScheme
    var style: Style
    var overContent = true

    private static let kinds: [LibraryKind] = [.playlists, .artists, .albums, .songs, .favorites]

    var body: some View {
        SegmentChipRow(
            groups: [Self.kinds],
            selected: filter.map { [$0] } ?? [],
            label: \.rawValue,
            onTap: { kind in withAnimation(Yoin.spatial) { filter = filter == kind ? nil : kind } },
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
                }
                LibraryMetaLine(parts: [(album.artist, false), (album.title, true)], scheme: scheme)
            }
            Spacer(minLength: 6)
            Text(formatTime(track.durationSec))
                .monospacedDigit()
                .yoinRole(Tokens.type.labelLarge)
                .foregroundStyle(scheme.onSurfaceVariant)
        }
        .padding(.horizontal, horizontalPadding)
        .padding(.vertical, Tokens.library.ROW_PADDING_V.cg)
        .contentShape(.rect)
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
