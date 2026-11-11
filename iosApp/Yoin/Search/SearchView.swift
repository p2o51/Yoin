import SwiftUI

/// Search, the Apple Music iOS 26 way, with Android's content.
///
/// iOS form: the bar's search circle grows into a glass search field at the
/// top (magnifier, field, clear) with a separate glass close circle, the keyboard
/// up at once, and the bottom bar hidden under the keyboard. Under it,
/// Android's one filter row (scopes | types) in Library's segment chips.
///
/// Android content (library/LibrarySearchFilter.kt, LibraryScreen.kt
/// SearchResultsContent): scopes Library and the provider's catalog (here
/// Apple Music); type filters All / Artists / Albums / Songs / Playlists;
/// All is sectioned artists → albums → playlists → songs; Artists and Albums
/// alone are a grid, Songs and Playlists a list; "No results found" when empty.
/// Android has no recent searches and no voice search: before a query the
/// surface is blank under the chips (SearchResultsContent returns nothing
/// while searchResults is null), and the field's trailing slot is only ever
/// the clear button.
struct SearchView: View {
    enum Scope: String, CaseIterable {
        case catalog = "Apple Music", library = "Library"

        var store: LibraryStore.SearchScope { self == .catalog ? .catalog : .library }
    }
    enum Filter: String, CaseIterable {
        case all = "All", artists = "Artists", albums = "Albums", songs = "Songs", playlists = "Playlists"

        /// The chip's text in the reader's language (the raw value stays the key).
        var title: String {
            switch self {
            case .all: String(localized: "All")
            case .artists: String(localized: "Artists")
            case .albums: String(localized: "Albums")
            case .songs: String(localized: "Songs")
            case .playlists: String(localized: "Playlists")
            }
        }

        /// The one result type a filter lists (nil: All, which never reads more).
        var kind: SearchKind? {
            switch self {
            case .all: nil
            case .artists: .artists
            case .albums: .albums
            case .songs: .songs
            case .playlists: .playlists
            }
        }
    }

    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var colorScheme
    var npNamespace: Namespace.ID
    /// iPad: Search is a tab of its own (PadShell), and ✕ leaves it for the
    /// tab it came from. Nil on the phone, where ✕ folds Search away.
    var onClose: (() -> Void)? = nil
    @FocusState private var focused: Bool
    @State private var query = LaunchConfig.searchQuery
    @State private var scope: Scope = LaunchConfig.screen == "search-catalog" || AppModel.shared.searchStartsOnCatalog
        ? .catalog : .library
    @State private var filter: Filter = LaunchConfig.searchFilter
    /// The results column's width (inside the 16 margins), for the 3-column cells.
    @State private var width: CGFloat = 370
    /// The + / ✓ of Apple Music's song results.
    @State private var libraryAdder = SongLibraryAdder()
    @Environment(\.horizontalSizeClass) private var hSize
    @Environment(\.verticalSizeClass) private var vSize
    @State private var scrollPosition = ScrollPosition()
    /// Android LibrarySearchScrollMemory: one position per (scope, filter)
    /// for the current query, so All → Songs → All lands where All was left.
    /// Another query forgets them all.
    @State private var savedOffsets: [String: CGFloat] = [:]
    @State private var offset: CGFloat = 0
    /// Results have scrolled under the pinned header (scrollLitTrack).
    @State private var scrolledUnder = false
    /// The open staging: false on the first frame, then true on the effects
    /// spring. The field never waits on it (it is the object that moves); the
    /// surface fades in over the page, the rest a beat behind it, so the
    /// page's own header and chips never read through Search's (fade-through).
    @State private var staged = false
    /// The field has grown out of the bar's circle (false: the circle
    /// stand-in shows where the bar's circle rests). Library's pill is the
    /// field already, so from there it starts grown.
    @State private var grown = false
    @Namespace private var morph
    private var showsField: Bool { grown || model.searchOrigin != .bar }

    /// Search's own content arrives a beat after its surface (fade through).
    @MainActor static var fadeThrough: Animation {
        Yoin.effects.delay(0.08 / LaunchConfig.animationSpeed)
    }

    private var scheme: CoverScheme {
        model.isIdle ? .brand(dark: colorScheme == .dark)
                     : CoverScheme(album: model.playback.album, dark: colorScheme == .dark)
    }

    var body: some View {
        ScrollView {
            content
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16)
                .padding(.top, 8)
                .padding(.bottom, Tokens.search.RESULTS_BOTTOM_RESERVE.cg)
        }
        // The content's width is the offered width less the 16 margins (see `offeredWidth`).
        .offeredWidth { width = max(0, $0 - 32) }
        .scrollPosition($scrollPosition)
        .onScrollGeometryChange(for: CGFloat.self) { $0.contentOffset.y + $0.contentInsets.top } action: { _, y in
            offset = y
            scrolledUnder = y > 1
        }
        .onChange(of: query) { _, _ in savedOffsets = [:] }
        .opacity(staged ? 1 : 0)
        .animation(Self.fadeThrough, value: staged)
        .overlay { if showsNoResults { noResults.transition(.opacity.animation(Yoin.effects)) } }
        .scrollDismissesKeyboard(.interactively)
        .scrollEdgeEffectStyle(.soft, for: .top)
        // The header is a safe-area bar so the system soft edge runs under it.
        .safeAreaBar(edge: .top) { header }
        // Android ExpandedFullScreenSearchBar: SearchBarDefaults.colors()
        // container = surfaceContainerHigh of the playing scheme.
        .background(scheme.surfaceContainerHigh.opacity(staged ? 1 : 0).ignoresSafeArea())
        .animation(Yoin.effects, value: staged)
        .overlay { circleStandIn }
        // However Search closes, the field folds back into the circle as it fades.
        .onChange(of: model.searchActive) { _, active in
            // Built ahead under a finger (prewarm): the open starts on the tap.
            if active {
                Task { await start() }
                return
            }
            focused = false
            withAnimation(Yoin.spatial) { grown = false }
        }
        // `-searchFocused YES`: screenshots of typing, the keyboard up over results.
        .task { if model.searchActive { await start() } }
        .task {
            // Screenshots: `-screen search-scrolled` runs the results under the chips.
            guard LaunchConfig.searchScrolled else { return }
            try? await Task.sleep(for: .milliseconds(600))
            scrollPosition.scrollTo(y: 380)
        }
        // Source search once typing pauses (300 ms); a scope change asks again.
        .task(id: "\(model.store.generation)|\(scope)|\(query)") {
            guard model.store.isReal else { return }
            try? await Task.sleep(for: .milliseconds(300))
            guard !Task.isCancelled else { return }
            await model.store.runSearch(query, scope: scope.store)
        }
    }

    /// The open: on the first frame Search is shown (on mount, or on the tap
    /// when it was built ahead), then everything animates in.
    private func start() async {
        // Built ahead from the bar: the open may have asked for the catalog since.
        if model.searchStartsOnCatalog { scope = .catalog }
        // After the first frame: from there everything animates in.
        staged = true
        let focus = LaunchConfig.searchQuery.isEmpty || UserDefaults.standard.bool(forKey: "searchFocused")
        guard model.searchOrigin == .bar, !grown else {
            // From Library's pill: nothing travels, but let the surface
            // come in before the keyboard's first-load stall.
            guard focus else { return }
            try? await Task.sleep(for: .milliseconds(Int(Self.morphSettleMs / 2 / LaunchConfig.animationSpeed)))
            if model.searchActive { focused = true }
            return
        }
        // The keyboard comes up once the field has landed: bringing it up
        // stalls the main thread for a few frames, and mid-morph that froze
        // the field half-way up the screen, then it jumped to the header.
        // (A fixed beat, the spatial spring's settle: a completion
        // callback fired before the morph had even started.)
        withAnimation(Yoin.spatial) { grown = true }
        guard focus else { return }
        try? await Task.sleep(for: .milliseconds(Int(Self.morphSettleMs / LaunchConfig.animationSpeed)))
        if model.searchActive { focused = true }
    }

    // MARK: No results

    private var showsNoResults: Bool {
        guard !query.isEmpty else { return false }
        let r = results
        let shownEmpty = switch filter {
        case .all: r.albums.isEmpty && r.tracks.isEmpty && r.artists.isEmpty && r.playlists.isEmpty
        case .artists: r.artists.isEmpty
        case .albums: r.albums.isEmpty
        case .songs: r.tracks.isEmpty
        case .playlists: r.playlists.isEmpty
        }
        guard shownEmpty else { return false }
        return !model.store.isReal || realState == .loaded
    }

    /// What the source says about this query and scope (Android
    /// SearchResultsContent): loading while it is asked (or the 300 ms pause
    /// before), the failure with its Retry, else its results. Results and a
    /// failure for another query or scope are never shown under this one.
    private var realState: LibraryStore.Phase {
        let store = model.store
        let request = LibraryStore.SearchRequest(query: query.trimmingCharacters(in: .whitespacesAndNewlines),
                                                 scope: store.searchesCatalog ? scope.store : .library)
        guard store.searchRequest == request else { return .loading }
        return store.searchPhase == .idle ? .loading : store.searchPhase
    }

    /// Android EmptyState: a Card (16) panel 16 in from each side,
    /// surfaceContainerHighest at 72 %, bodyLarge onSurfaceVariant, padding
    /// 20 × 18, centred in the results area (the search surface yields to
    /// the keyboard, so the centre is above it). Labels only, no prompt.
    private var noResults: some View {
        Text("No results found")
            .yoinRole(Tokens.type.bodyLarge)
            .foregroundStyle(scheme.onSurfaceVariant)
            .padding(.horizontal, Tokens.library.EMPTY_STATE_PADDING_H.cg)
            .padding(.vertical, Tokens.library.EMPTY_STATE_PADDING_V.cg)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(scheme.surfaceContainerHighest.opacity(Tokens.library.EMPTY_STATE_ALPHA),
                        in: .rect(cornerRadius: Tokens.shape.CARD.cg, style: .continuous))
            .padding(.horizontal, 16)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .allowsHitTesting(false)
    }

    // MARK: Header

    private var header: some View {
        // Android LibrarySearchFilterRow sits 8 under the field (its vertical 8).
        VStack(spacing: Tokens.search.FILTER_ROW_PADDING_V.cg) {
            // No GlassEffectContainer: inside one, a child's opacity doesn't
            // reach its glass, and the ✕ has to fade in on its own.
            HStack(spacing: 8) {
                // Same field as Library's header pill (icon 24, 12 gap,
                // 16 type, 56 tall, 16 in), so from there nothing moves.
                HStack(spacing: 12) {
                    YoinIcon("search", size: 24).foregroundStyle(Yoin.inkVariant)
                    TextField(scope == .library ? String(localized: "Search library") : String(localized: "Search \(catalogName)"), text: $query)
                        .font(.gsf(16))
                        .focused($focused)
                        .submitLabel(.search)
                    // Android InputField trailingIcon: Close (clear) once
                    // there is text, nothing before. No mic: Android has
                    // no voice search.
                    if !query.isEmpty {
                        Button { query = "" } label: {
                            YoinIcon("close", size: 16).foregroundStyle(Yoin.inkVariant)
                                .frame(width: 32, height: 32)
                                .contentShape(.circle)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Clear search")
                        .transition(.opacity.animation(Yoin.effects))
                    }
                }
                .padding(.horizontal, 16)
                .frame(height: Self.fieldHeight)
                .labSearchGlass(scheme: scheme, in: .capsule)
                // Until it has grown, it rides on the circle stand-in.
                // Swapped with the stand-in in one frame, at the circle's size,
                // where the magnifier sits at the same centre: one object.
                // Closing, the field itself folds back (the stand-in stays out).
                // (Inside the matched effect: a nil animation outside it would
                // cancel the morph too.)
                .opacity(showsField || !model.searchActive ? 1 : 0)
                .animation(nil, value: showsField)
                .matchedGeometryEffect(id: "search.field", in: morph, isSource: showsField)

                Button { close() } label: {
                    YoinIcon("close", size: 20)
                        .foregroundStyle(Yoin.ink)
                        .frame(width: 48, height: 48)
                        .contentShape(.circle)
                }
                .buttonStyle(.plain)
                .labSearchGlass(scheme: scheme, in: .circle)
                .opacity(staged ? 1 : 0)
                .animation(Self.fadeThrough, value: staged)
                .accessibilityLabel("Close search")
            }
            // A Subsonic server has no catalog apart from the library, so
            // no scope pair there.
            filterRow
                // SegmentChipRow keeps 4 above and below for its press
                // scale; Android's row sits exactly 8 under the field and 8
                // over the results, so give those 4s back here.
                .padding(.vertical, -4)
                .opacity(staged ? 1 : 0)
                .animation(Self.fadeThrough, value: staged)
        }
        .padding(.horizontal, 16)
        // Library's header: 4 over the field (its 64 app bar), so the two
        // fields share one rect.
        .padding(.top, Self.headerTop)
        .padding(.bottom, Tokens.search.FILTER_ROW_PADDING_V.cg)
    }

    /// About when Yoin.spatial (response 0.42, damping 0.78) has settled.
    static let morphSettleMs = 520.0

    /// Android SearchBarDefaults.InputFieldHeight, for Library's pill and this field alike.
    static let fieldHeight: CGFloat = 56
    /// The 4 over the field in the 64 app bar (Library header, this header).
    static let headerTop: CGFloat = 4

    /// Android LibrarySearchFilterRow: one full-bleed row of segment chips,
    /// the scope pair (catalog | library, only where the provider searches a
    /// catalog), a hairline, then the result types. Same chips as Library's
    /// filters (SegmentChipRow, in the style Glass Lab picks): the chip that
    /// is on is secondaryContainer, as ExpressiveSegmentChip is everywhere.
    private var filterRow: some View {
        SegmentChipRow(
            groups: (model.store.isReal && !model.store.searchesCatalog ? [] : [Scope.allCases.map(SearchChip.scope)])
                + [availableFilters.map(SearchChip.filter)],
            selected: [.scope(scope), .filter(filter)],
            label: { $0.label(catalog: catalogName) },
            onTap: { chip in
                savedOffsets[positionKey] = offset
                withAnimation(Yoin.spatial) {
                    switch chip {
                    case .scope(let s): scope = s
                    case .filter(let f): filter = f
                    }
                }
                scrollPosition.scrollTo(y: savedOffsets[positionKey] ?? 0)
            },
            scheme: scheme,
            style: .current,
            overContent: scrolledUnder
        )
        // Full bleed: the resting first chip sits on the results' 16 edge,
        // scrolled chips run out under the screen's edges.
        .padding(.horizontal, -16)
    }

    /// Android searchFiltersFor: Playlists only where search returns them
    /// (Apple Music, in either scope; not Subsonic's search3).
    private var availableFilters: [Filter] {
        // Spotify returns playlists in both scopes (Codex N14); a Subsonic server has none.
        model.store.isReal && !model.store.searchesCatalog
            ? Filter.allCases.filter { $0 != .playlists } : Filter.allCases
    }

    /// The provider whose whole catalog the catalog scope searches.
    private var catalogName: String { model.store.spotify != nil ? String(localized: "Spotify") : "Apple Music" }

    private var positionKey: String { "\(scope)|\(filter)" }

    private enum SearchChip: Hashable {
        case scope(Scope), filter(Filter)

        func label(catalog: String) -> String {
            switch self {
            case .scope(let s): s == .catalog ? catalog : String(localized: "Library")
            case .filter(let f): f.title
            }
        }
    }

    // MARK: Content

    @ViewBuilder
    private var content: some View {
        if query.isEmpty {
            // Android: nothing until there is a query (no icon, no prompt;
            // the field's placeholder already names the scope).
            Color.clear.frame(height: 1)
        } else {
            let r = results
            let shownEmpty = switch filter {
            case .all: r.albums.isEmpty && r.tracks.isEmpty && r.artists.isEmpty && r.playlists.isEmpty
            case .artists: r.artists.isEmpty
            case .albums: r.albums.isEmpty
            case .songs: r.tracks.isEmpty
            case .playlists: r.playlists.isEmpty
            }
            if model.store.isReal && realState == .failed {
                RetryRow(scheme: scheme) { Task { await model.store.runSearch(query, scope: scope.store) } }
                    .transition(.opacity.animation(Yoin.effects))
            } else if model.store.isReal && realState != .loaded {
                RowSkeleton(scheme: scheme, rows: 5)
                    .transition(.opacity.animation(Yoin.effects))
            } else if shownEmpty {
                // Drawn by `noResults`, centred in what the header and the
                // keyboard leave of the screen.
                Color.clear.frame(height: 1)
            } else {
                VStack(alignment: .leading, spacing: 0) {
                    switch filter {
                    case .all: allResults(r)
                    case .artists: artistGrid(r.artists)
                    case .albums: grid(r.albums.map { ($0, false) })
                    case .songs: songList(r.tracks)
                    case .playlists: playlistList(r.playlists)
                    }
                    if let kind = filter.kind { searchFoot(kind) }
                }
            }
        }
    }

    /// Android LibrarySongsFoot under a type's own list: a loading indicator while Spotify
    /// has more (reading the next page as it shows), a Retry once a read failed, nothing at the end.
    @ViewBuilder
    private func searchFoot(_ kind: SearchKind) -> some View {
        switch model.store.searchMore(kind) {
        case .none: EmptyView()
        case .available, .loading:
            // The id outermost: each page's foot is a new view, so its onAppear asks for the page after
            // (an id inside the onAppear left the foot's appearance as it was: only page 2 was read).
            RowSkeleton(scheme: scheme, rows: 2)
                .transition(.opacity.animation(Yoin.effects))
                .onAppear { Task { await model.store.loadMoreSearch(kind) } }
                .id(model.store.search?.offsets[kind])
        case .failed:
            RetryRow(scheme: scheme) { Task { await model.store.loadMoreSearch(kind) } }
                .padding(.vertical, 12)
                .transition(.opacity.animation(Yoin.effects))
        }
    }

    /// An artist result: the mock borrows its first album's cover.
    struct ArtistHit: Identifiable {
        var id: MediaId
        var name: String
        var serverId: String?
        var palette: CoverPalette
        var seed: Int
        /// nil: the service doesn't say (Spotify, Apple Music): the row says "Artist", not "0 albums".
        var albumCount: Int?
    }

    private struct Results {
        var artists: [ArtistHit] = []
        var albums: [Album] = []
        var playlists: [LibraryItem] = []
        var tracks: [(Album, Track)] = []
    }

    /// Mock search over the mock library. The catalog scope pretends to be
    /// the whole service, so it searches everything with artist names too.
    private var results: Results {
        if model.store.isReal {
            guard realState == .loaded, let hits = model.store.search else { return Results() }
            return Results(artists: hits.artists.map {
                               ArtistHit(id: $0.id, name: $0.title, serverId: $0.id.rawId, palette: $0.palette,
                                         seed: 0, albumCount: $0.count)
                           },
                           albums: hits.albums, playlists: hits.playlists,
                           tracks: hits.songs.map { ($0.album, $0.track) })
        }
        // No source and no demo arguments: nothing to search.
        guard LaunchConfig.demoContent else { return Results() }
        let q = query.lowercased()
        let all = model.library.albums()
        var r = Results()
        var seenArtists = Set<String>()
        for a in all where a.artist.lowercased().contains(q) && seenArtists.insert(a.artist).inserted {
            r.artists.append(ArtistHit(id: a.id, name: a.artist, serverId: nil, palette: a.palette, seed: a.seed,
                                       albumCount: all.filter { $0.artist == a.artist }.count))
        }
        r.albums = all.filter { $0.title.lowercased().contains(q) || (scope == .catalog && $0.artist.lowercased().contains(q)) }
        r.tracks = all.flatMap { a in a.tracks.filter { $0.title.lowercased().contains(q) }.map { (a, $0) } }
        r.playlists = model.library.libraryItems().filter { $0.kind == .playlist && $0.title.lowercased().contains(q) }
        if scope == .library {
            // Mock "in the library": every other album.
            r.albums = r.albums.filter { (Int($0.id.rawId.dropFirst(2)) ?? 0) % 2 == 1 }
            r.tracks = Array(r.tracks.prefix(6))
        }
        return r
    }

    /// Android SearchAllResults: artists, albums, playlists, songs, each
    /// under a SectionHeader; rows touch (no list spacing).
    @ViewBuilder
    private func allResults(_ r: Results) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            if !r.artists.isEmpty {
                sectionHeader(String(localized: "Artists"))
                ForEach(r.artists) { a in artistRow(a) }
            }
            if !r.albums.isEmpty {
                sectionHeader(String(localized: "Albums"))
                // Library's columns (3 on Compact), chunked into rows with no
                // vertical gap (a LazyColumn of Rows, spacedBy 12 across).
                albumRows(r.albums)
            }
            if !r.playlists.isEmpty {
                sectionHeader(String(localized: "Playlists"))
                playlistList(r.playlists)
            }
            if !r.tracks.isEmpty {
                sectionHeader(String(localized: "Songs"))
                songList(r.tracks)
            }
        }
    }

    /// Android SectionHeader: labelLarge onSurfaceVariant, padding 16 / 18 top / 6 bottom.
    private func sectionHeader(_ title: String) -> some View {
        Text(title)
            .yoinRole(Tokens.type.labelLarge)
            .foregroundStyle(scheme.onSurfaceVariant)
            .padding(.top, Tokens.search.SECTION_HEADER_TOP.cg)
            .padding(.bottom, Tokens.search.SECTION_HEADER_BOTTOM.cg)
    }

    /// Library's grid (LibraryGrid): 3 columns on a phone, more on a wider
    /// pane at Library's cell size. [width] is inside the 16 margins.
    private var columns: Int {
        LibraryGrid.columns(width: width + 32, regularWidth: hSize == .regular, compactHeight: vSize == .compact)
    }
    private var cell: CGFloat { LibraryGrid.cell(width: width + 32, columns: columns) }

    /// Android ArtistListItem: 16 × 10 padding, 48 circle, 12, titleSmall,
    /// then 8 and the album-count meta pill.
    private func artistRow(_ a: ArtistHit) -> some View {
        Button { openArtist(a) } label: {
            HStack(spacing: 0) {
                EntityBackdrop(entity: .artist, palette: a.palette, seed: a.seed, size: Tokens.library.ROW_THUMB.cg)
                Spacer().frame(width: Tokens.library.ROW_GAP.cg)
                Text(a.name)
                    .lineLimit(1)
                    .yoinRole(Tokens.type.titleSmall)
                    .foregroundStyle(scheme.onSurface)
                Spacer(minLength: 8)
                // No count from the service: no pill (the section already says Artists).
                if a.albumCount != nil { metaPill(albumCount(a.albumCount)) }
            }
            .padding(.vertical, Tokens.library.ROW_PADDING_V.cg)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
    }

    private func albumCount(_ n: Int?) -> String {
        guard let n else { return String(localized: "Artist") }
        return n == 1 ? String(localized: "1 album") : String(localized: "\(n) albums")
    }

    private func openArtist(_ a: ArtistHit) {
        focused = false
        withAnimation(Yoin.spatial) { model.searchActive = false }
        model.openArtist(a.name, id: a.serverId)
    }

    /// Artists alone: the Library grid's circles.
    private func artistGrid(_ artists: [ArtistHit]) -> some View {
        LazyVGrid(columns: Array(repeating: GridItem(.fixed(cell), spacing: Tokens.library.GRID_GUTTER_H.cg, alignment: .top), count: columns),
                  alignment: .leading, spacing: Tokens.library.GRID_GUTTER_V.cg) {
            ForEach(artists) { a in
                Button { openArtist(a) } label: {
                    VStack(alignment: .center, spacing: 0) {
                        EntityBackdrop(entity: .artist, palette: a.palette, seed: a.seed, size: cell, stroke: scheme.primary)
                        Spacer().frame(height: Tokens.library.COVER_TO_TITLE.cg)
                        Text(a.name).lineLimit(1).yoinRole(Tokens.type.titleSmall).foregroundStyle(scheme.onSurface)
                        Text(albumCount(a.albumCount)).lineLimit(1).yoinRole(Tokens.type.bodySmall)
                            .foregroundStyle(scheme.onSurfaceVariant)
                    }
                    .frame(width: cell, alignment: .center)
                }
                .buttonStyle(PressScale())
            }
        }
    }

    /// Android ExpressiveMetaPill: labelMedium onSurfaceVariant, padding 10 × 6,
    /// full round, surfaceContainerHighest at 90 %.
    private func metaPill(_ text: String) -> some View {
        Text(text)
            .lineLimit(1)
            .yoinRole(Tokens.type.labelMedium)
            .foregroundStyle(scheme.onSurfaceVariant)
            .padding(.horizontal, Tokens.library.META_PILL_PADDING_H.cg)
            .padding(.vertical, Tokens.library.META_PILL_PADDING_V.cg)
            .background(scheme.surfaceContainerHighest.opacity(Tokens.library.META_PILL_ALPHA), in: .capsule)
    }

    private func albumRows(_ albums: [Album]) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(stride(from: 0, to: albums.count, by: columns)), id: \.self) { i in
                HStack(alignment: .top, spacing: Tokens.library.GRID_GUTTER_H.cg) {
                    ForEach(albums[i..<min(i + columns, albums.count)]) { a in gridCell(a, isArtist: false) }
                }
            }
        }
    }

    /// Android SearchResultsGrid: the Library grid (LibraryGrid columns, 12 / 16).
    private func grid(_ items: [(Album, Bool)]) -> some View {
        LazyVGrid(columns: Array(repeating: GridItem(.fixed(cell), spacing: Tokens.library.GRID_GUTTER_H.cg, alignment: .top), count: columns),
                  alignment: .leading, spacing: Tokens.library.GRID_GUTTER_V.cg) {
            ForEach(items, id: \.0.id) { a, isArtist in gridCell(a, isArtist: isArtist) }
        }
    }

    /// AlbumCard / ArtistGridItem: cover, 5, titleSmall, bodySmall (12).
    private func gridCell(_ a: Album, isArtist: Bool) -> some View {
        Button {
            if isArtist {
                focused = false
                withAnimation(Yoin.spatial) { model.searchActive = false }
                model.openArtist(a.artist, id: a.artistId)
            } else {
                focused = false
                withAnimation(Yoin.spatial) { model.searchActive = false }
                model.open(a)
            }
        } label: {
            VStack(alignment: isArtist ? .center : .leading, spacing: 0) {
                EntityBackdrop(entity: isArtist ? .artist : .album, palette: a.palette, seed: a.seed, size: cell,
                               stroke: scheme.primary)
                Spacer().frame(height: Tokens.library.COVER_TO_TITLE.cg)
                Text(isArtist ? a.artist : a.title)
                    .lineLimit(1)
                    .yoinRole(Tokens.type.titleSmall)
                    .foregroundStyle(scheme.onSurface)
                Text(isArtist ? albumCount(model.library.albums().filter { $0.artist == a.artist }.count) : a.artist)
                    .lineLimit(1)
                    .yoinRole(Tokens.type.bodySmall)
                    .foregroundStyle(scheme.onSurfaceVariant)
            }
            .frame(width: cell, alignment: isArtist ? .center : .leading)
        }
        .buttonStyle(PressScale())
    }

    /// The + / ✓ of a result song, where the service adds songs to a library (Android SearchSongRow's
    /// canAddToLibrary): Apple Music's.
    private func libraryAdd(_ track: Track) -> LibrarySongRow.LibraryAddControl? {
        guard model.store.apple != nil, track.id.provider == AppleMusicMapping.provider else { return nil }
        let id = track.id.rawId
        return .init(membership: libraryAdder.state(id), working: libraryAdder.working.contains(id),
                     feedback: libraryAdder.feedback[id]) { [libraryAdder] in Task { await libraryAdder.add(id) } }
    }

    /// Android SearchSongRow = SongListItem (Library's song row).
    private func songList(_ tracks: [(Album, Track)]) -> some View {
        let ids = tracks.filter { $0.1.id.provider == AppleMusicMapping.provider }.map(\.1.id.rawId)
        return VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(tracks.enumerated()), id: \.offset) { i, item in
                LibrarySongRow(album: item.0, track: item.1, current: model.playback.track.id == item.1.id,
                               scheme: scheme, horizontalPadding: 0, libraryAdd: libraryAdd(item.1))
                    .trackRow(for: PlaylistEntry(album: item.0, track: item.1)) {
                        model.play(tracks.map { PlaylistEntry(album: $0.0, track: $0.1) }, startAt: i)
                    }
            }
        }
        .task(id: "\(model.store.generation)|\(ids.joined(separator: ","))") {
            libraryAdder.use(model.store.apple)
            await libraryAdder.refresh(ids)
        }
    }

    /// Android PlaylistListItem: 16 × 10 padding, 48 Thumb (4), 12,
    /// titleSmall over PlaylistMeta (owner, then "N tracks" as a stat).
    private func playlistList(_ items: [LibraryItem]) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(items) { p in
                Button {
                    withAnimation(Yoin.spatial) { model.searchActive = false }
                    model.openPlaylist(p.id.rawId)
                } label: {
                    HStack(spacing: Tokens.library.ROW_GAP.cg) {
                        CoverArt(palette: p.palette, cornerRadius: 0, seed: 3)
                            .frame(width: Tokens.library.ROW_THUMB.cg, height: Tokens.library.ROW_THUMB.cg)
                            .clipShape(.rect(cornerRadius: Tokens.shape.THUMB.cg, style: .continuous))
                        VStack(alignment: .leading, spacing: 0) {
                            Text(p.title)
                                .lineLimit(1)
                                .yoinRole(Tokens.type.titleSmall)
                                .foregroundStyle(scheme.onSurface)
                            playlistMeta(p)
                        }
                        Spacer(minLength: 0)
                    }
                    .padding(.vertical, Tokens.library.ROW_PADDING_V.cg)
                    .contentShape(.rect)
                }
                .buttonStyle(.plain)
            }
        }
    }

    /// MetaLine Stat: the number in Medium, tabular; the unit Regular at 60 %.
    @ViewBuilder
    private func playlistMeta(_ p: LibraryItem) -> some View {
        if let n = p.subtitle.split(separator: " ").compactMap({ Int($0) }).first {
            HStack(spacing: Tokens.metaLine.INNER_GAP.cg) {
                Text("\(n)").monospacedDigit().yoinRole(Tokens.type.bodySmall, weight: .medium)
                    .foregroundStyle(scheme.onSurface)
                Text(n == 1 ? "track" : "tracks").yoinRole(Tokens.type.bodySmall)
                    .foregroundStyle(scheme.onSurface.opacity(Tokens.metaLine.MUTED_ALPHA))
            }
        } else {
            Text("Playlist").yoinRole(Tokens.type.bodySmall).foregroundStyle(scheme.onSurfaceVariant)
        }
    }

    private func close() {
        // The keyboard goes down first, on this frame (a ~30 ms stall), so the
        // fold's first frames don't carry it.
        if focused { AppModel.dismissKeyboard() }
        focused = false
        if let onClose { return onClose() }
        // The field folds back into the circle as Search fades out.
        withAnimation(Yoin.spatial) {
            grown = false
            model.searchActive = false
        }
    }

    /// The bar's search circle, drawn where it rests, while the field hasn't
    /// grown out of it yet (and again as it folds back). Same glyph and size,
    /// matched with the field: one object changes shape.
    /// Always mounted, the source role handed back and forth: a removed
    /// source stays a source through its fade, and the field stopped
    /// mid-way until it was gone (the 11-04 "ghost field").
    @ViewBuilder
    private var circleStandIn: some View {
        let r = model.searchCircleFrame
        if r.width > 0 {
            GeometryReader { g in
                let o = g.frame(in: .global).origin
                YoinIcon("search", size: 24)
                    .foregroundStyle(Yoin.ink)
                    .frame(width: r.width, height: r.height)
                    .labSearchGlass(scheme: scheme, in: .capsule)
                    .opacity(!showsField && model.searchActive ? 1 : 0)
                    .animation(nil, value: showsField)
                    .matchedGeometryEffect(id: "search.field", in: morph, isSource: !showsField)
                    .padding(.leading, r.minX - o.x)
                    .padding(.top, r.minY - o.y)
            }
            .ignoresSafeArea()
            .allowsHitTesting(false)
        }
    }
}
