import SwiftUI
import YoinShared

/// Search, the Apple Music iOS 26 way, with Android's content.
///
/// iOS form: the bar's search circle grows into a glass search field at the
/// top (magnifier, field, clear) with a separate glass close circle, the keyboard
/// up at once, and the bottom bar hidden under the keyboard. Under it,
/// Android's one filter row (scopes | types) in Library's segment chips.
///
/// Android content (library/LibrarySearchFilter.kt, LibraryScreen.kt
/// SearchResultsContent): scopes Library and the provider's catalog (here
/// Apple Music); type filters All / Songs / Albums / Memories / Artists /
/// Playlists (:shared's chips). All is Home's Jump Back In tiles, sections
/// ranked by the hit, the listener's notes and memories from every account
/// merged in (SearchTiles.swift, SearchMemories.swift; parity p3); Memories
/// lists only those; Artists and Albums alone are a grid, Songs and Playlists
/// a list; "No results found" when empty.
/// Android has no recent searches and no voice search: before a query the
/// surface is blank under the chips (SearchResultsContent returns nothing
/// while searchResults is null), and the field's trailing slot is only ever
/// the clear button.
struct SearchView: View {
    enum Scope: String, CaseIterable {
        case catalog = "Apple Music", library = "Library"

        var store: LibraryStore.SearchScope { self == .catalog ? .catalog : .library }
    }
    /// The type chips, in :shared's chip order (`YoinSearchFilter`).
    enum Filter: String, CaseIterable {
        case all = "All", songs = "Songs", albums = "Albums", memories = "Memories", artists = "Artists",
             playlists = "Playlists"

        /// The chip's text in the reader's language (the raw value stays the key).
        var title: String {
            switch self {
            case .all: String(localized: "All")
            case .artists: String(localized: "Artists")
            case .albums: String(localized: "Albums")
            case .songs: String(localized: "Songs")
            case .memories: String(localized: "Memories")
            case .playlists: String(localized: "Playlists")
            }
        }

        /// The one result type a filter lists (nil: All, which never reads more, and Memories, from the ledger).
        var kind: SearchKind? {
            switch self {
            case .all, .memories: nil
            case .artists: .artists
            case .albums: .albums
            case .songs: .songs
            case .playlists: .playlists
            }
        }

        init(_ shared: YoinSearchFilter) {
            self = Filter.allCases.first { $0.shared == shared } ?? .all
        }

        var shared: YoinSearchFilter {
            switch self {
            case .all: .all
            case .songs: .songs
            case .albums: .albums
            case .memories: .memories
            case .artists: .artists
            case .playlists: .playlists
            }
        }

        /// A section's own chip (All's Show all).
        init(_ section: YoinSearchSection) {
            self = section == .songs ? .songs : section == .albums ? .albums : section == .artists ? .artists : .playlists
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
    /// The listener's notes and memories the query met, every account's (the ledger, local: before the service).
    @State private var memories = SearchMemories()
    /// Another account's note or memory asks before it switches, once (Home's and All Memories' notice).
    @State private var switchGate = AccountSwitchGate()
    /// The result songs (by position and id) whose "Apple Music doesn't stream" reason is open.
    @State private var notStreamedReasons: Set<String> = []
    @Environment(\.horizontalSizeClass) private var hSize
    @Environment(\.verticalSizeClass) private var vSize
    @State private var scrollPosition = ScrollPosition()
    /// Android LibrarySearchScrollMemory: one position per (scope, filter)
    /// for the current query, so All → Songs → All lands where All was left.
    /// Another query forgets them all.
    @MainActor private final class OffsetBox { var value: CGFloat = 0 }

    @State private var savedOffsets: [String: CGFloat] = [:]
    @State private var offsetBox = OffsetBox()
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
    @State private var hasMounted = false
    @State private var needsPhoneReset = false
    @State private var focusTask: Task<Void, Never>?
    @State private var closeResetTask: Task<Void, Never>?
    @Namespace private var morph
    private var showsField: Bool { grown || model.searchOrigin != .bar }
    private var isPadHosted: Bool { onClose != nil }

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
        .offeredWidth {
            let next = max(0, $0 - 32)
            if abs(next - width) > 0.5 { width = next }
        }
        // Empty query has no results or empty-state card to center above the keyboard:
        // ignoring keyboard safe-area avoids 120 Hz ScrollView/GeometryReader relayouts on open/close.
        .ignoresSafeArea(query.isEmpty ? .keyboard : [], edges: .bottom)
        .scrollPosition($scrollPosition)
        .onScrollGeometryChange(for: CGFloat.self) { $0.contentOffset.y + $0.contentInsets.top } action: { _, y in
            offsetBox.value = y
            let under = y > 1
            if under != scrolledUnder { scrolledUnder = under }
        }
        .onChange(of: query) { _, _ in if !savedOffsets.isEmpty { savedOffsets = [:] } }
        .opacity(staged ? 1 : 0)
        .animation(Self.fadeThrough, value: staged)
        .overlay { if showsNoResults { noResults.transition(.opacity.animation(Yoin.effects)) } }
        .scrollDismissesKeyboard(.interactively)
        .scrollEdgeEffectStyle(.soft, for: .top)
        // The header is a safe-area bar so the system soft edge runs under it, and the album page's top
        // (TopEdgeBlur) with it, in Search's own colour as its surface comes up.
        .safeAreaBar(edge: .top) {
            header.background(alignment: .top) {
                TopEdgeBlur(tint: scheme.surfaceContainerHigh)
                    .opacity(staged ? 1 : 0)
                    .animation(Yoin.effects, value: staged)
            }
        }
        // Android ExpandedFullScreenSearchBar: SearchBarDefaults.colors()
        // container = surfaceContainerHigh of the playing scheme.
        .background(scheme.surfaceContainerHigh.opacity(staged ? 1 : 0).ignoresSafeArea())
        .animation(Yoin.effects, value: staged)
        .overlay { circleStandIn }
        .onAppear { hasMounted = true }
        .onChange(of: model.store.searchesCatalog) { _, searchesCatalog in
            if model.store.isReal, !searchesCatalog, scope == .catalog {
                scope = .library
            }
        }
        // However Search closes, the field folds back into the circle as it fades.
        .onChange(of: model.searchActive) { _, active in
            focusTask?.cancel()
            closeResetTask?.cancel()
            if active {
                if !isPadHosted, needsPhoneReset {
                    var t = Transaction()
                    t.disablesAnimations = true
                    withTransaction(t) { resetPhoneSession() }
                }
                if hasMounted {
                    beginStagedOpen()
                } else {
                    focusTask = Task { await start() }
                }
                return
            }
            focused = false
            withAnimation(Yoin.spatial) { grown = false }
            // iPad's PadShell intentionally retains Search's query and results across visits (`onClose != nil`).
            // Only the phone's resident SearchView resets once its fold-out fade completes.
            guard !isPadHosted else { return }
            needsPhoneReset = true
            closeResetTask = Task { @MainActor in
                try? await Task.sleep(for: .milliseconds(Int(420 / LaunchConfig.animationSpeed)))
                guard !Task.isCancelled, !model.searchActive else { return }
                var t = Transaction()
                t.disablesAnimations = true
                withTransaction(t) { resetPhoneSession() }
            }
        }
        // Open in Yoin hands an open Search its text.
        .onReceive(NotificationCenter.default.publisher(for: .yoinSearchStartQuery)) { _ in takeStartQuery() }
        // `-searchFocused YES`: screenshots of typing, the keyboard up over results.
        .task { if model.searchActive, !staged { await start() } }
        .task {
            // Screenshots: `-screen search-scrolled` runs the results under the chips.
            guard LaunchConfig.searchScrolled else { return }
            try? await Task.sleep(for: .milliseconds(600))
            scrollPosition.scrollTo(y: 380)
        }
        // Source search once typing pauses (300 ms); a scope change asks again. The ledger is local: its hits
        // land first (Android observeSearch), every account's whatever the scope.
        .task(id: "\(model.store.generation)|\(scope)|\(query)") {
            let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmed.isEmpty {
                memories.clear()
                if model.store.isReal { await model.store.runSearch("", scope: scope.store) }
                return
            }
            try? await Task.sleep(for: .milliseconds(300))
            guard !Task.isCancelled else { return }
            memories.run(trimmed, ledger: MemorySearchLedger.current(model: model), activeProfileId: model.searchProfileId)
            guard model.store.isReal else { return }
            await model.store.runSearch(query, scope: scope.store)
        }
    }

    /// Open in Yoin's shared text (no music link in it): the field opens holding it, taken once.
    private func takeStartQuery() {
        guard let start = model.searchStartQuery else { return }
        model.searchStartQuery = nil
        if query != start { query = start }
    }

    private func resetPhoneSession() {
        needsPhoneReset = false
        staged = false
        if LaunchConfig.searchQuery.isEmpty, !query.isEmpty { query = "" }
        let defaultScope: Scope = LaunchConfig.screen == "search-catalog" ? .catalog : .library
        if scope != defaultScope { scope = defaultScope }
        if filter != LaunchConfig.searchFilter { filter = LaunchConfig.searchFilter }
        if !savedOffsets.isEmpty { savedOffsets = [:] }
        if !notStreamedReasons.isEmpty { notStreamedReasons = [] }
        if scrolledUnder { scrolledUnder = false }
        offsetBox.value = 0
        scrollPosition.scrollTo(edge: .top)
    }

    static func resolvedOpenScope(
        current: Scope,
        isPadHosted: Bool,
        startsOnCatalog: Bool,
        canSearchCatalog: Bool,
        launchScreen: String = LaunchConfig.screen
    ) -> Scope {
        if isPadHosted {
            if startsOnCatalog, canSearchCatalog { return .catalog }
            if !canSearchCatalog, current == .catalog { return .library }
            return current
        }
        return ((startsOnCatalog || launchScreen == "search-catalog") && canSearchCatalog) ? .catalog : .library
    }

    /// Synchronous open path when SearchView was already prewarmed or resident in the hierarchy:
    /// starts the circle-to-field morph and surface fade-through on frame 0 without an async hop.
    private func beginStagedOpen() {
        let canSearchCatalog = !model.store.isReal || model.store.searchesCatalog
        let targetScope = Self.resolvedOpenScope(
            current: scope,
            isPadHosted: isPadHosted,
            startsOnCatalog: model.searchStartsOnCatalog,
            canSearchCatalog: canSearchCatalog
        )
        if scope != targetScope { scope = targetScope }
        takeStartQuery()
        staged = true
        let focus = LaunchConfig.searchQuery.isEmpty || UserDefaults.standard.bool(forKey: "searchFocused")
        let fromBar = model.searchOrigin == .bar && !grown
        if fromBar {
            withAnimation(Yoin.spatial) { grown = true }
        }
        guard focus else { return }
        let waitMs = Int((fromBar ? Self.morphSettleMs : Self.morphSettleMs / 2) / LaunchConfig.animationSpeed)
        focusTask = Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(waitMs))
            guard !Task.isCancelled, model.searchActive else { return }
            focused = true
        }
    }

    /// Cold-mount open path: waits one turn so `circleStandIn` establishes its source anchor first.
    private func start() async {
        beginStagedOpen()
    }

    // MARK: No results

    private var showsNoResults: Bool {
        guard !query.isEmpty, filter != .memories else { return false }
        let r = results
        let shownEmpty = switch filter {
        case .all: r.isEmpty && !hasMemories
        case .memories: false
        case .artists: r.artists.isEmpty
        case .albums: r.albums.isEmpty
        case .songs: r.tracks.isEmpty
        case .playlists: r.playlists.isEmpty
        }
        guard shownEmpty else { return false }
        // All waits for the ledger too: it may answer what the service doesn't.
        if filter == .all, !memoriesAnswered { return false }
        return !model.store.isReal || realState == .loaded
    }

    private var trimmedQuery: String { query.trimmingCharacters(in: .whitespacesAndNewlines) }

    /// The ledger has answered the query on screen.
    private var memoriesAnswered: Bool { !trimmedQuery.isEmpty && memories.query == trimmedQuery }

    /// The ledger met the query on screen.
    private var hasMemories: Bool { memoriesAnswered && !memories.isEmpty }

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
    @ViewBuilder
    private var noResults: some View {
        if !model.store.isReal && model.activeNeedsReconnect {
            // Nothing can be searched until the account is signed in again: not "No results".
            ReconnectPanel(scheme: scheme)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        } else {
            noResultsLabel
        }
    }

    private var noResultsLabel: some View {
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
                        .buttonStyle(.plain).pointerHighlight()
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
                .buttonStyle(.plain).pointerHighlight()
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
                savedOffsets[positionKey] = offsetBox.value
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

    /// Android searchFiltersFor (:shared's `filters`): Playlists only where search returns them
    /// (Apple Music and Spotify, in either scope, Codex N14; not Subsonic's search3).
    private var availableFilters: [Filter] {
        let searchesPlaylists = !(model.store.isReal && !model.store.searchesCatalog)
        return YoinMemorySearch.companion.filters(canSearchPlaylists: searchesPlaylists, canReadPlaylists: true,
                                                  savedLibrary: scope == .library).map(Filter.init)
    }

    /// The service's name for "N less relevant from …" (Android searchProviderName): the streaming services,
    /// whose search ranks the whole catalog; nil for a Subsonic server and the mock.
    private var providerName: String? {
        guard model.store.isReal else { return nil }
        if model.store.spotify != nil { return String(localized: "Spotify") }
        return model.store.apple != nil ? "Apple Music" : nil
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
            case .all: r.isEmpty && !hasMemories
            case .memories: false
            case .artists: r.artists.isEmpty
            case .albums: r.albums.isEmpty
            case .songs: r.tracks.isEmpty
            case .playlists: r.playlists.isEmpty
            }
            // The ledger answers on its own: All shows its hits while the service is still asked, Memories never waits.
            let waitsForService = filter != .memories && !(filter == .all && hasMemories)
            if filter == .memories {
                memoriesList(r)
            } else if model.store.isReal && realState == .failed {
                RetryRow(
                    scheme: scheme,
                    message: model.store.searchFailure,
                    details: model.store.failureReports["search"]
                ) {
                    Task { await model.store.runSearch(query, scope: scope.store) }
                }
                    .transition(.opacity.animation(Yoin.effects))
            } else if model.store.isReal && realState != .loaded && waitsForService {
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
                    case .memories: EmptyView()
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
            RetryRow(
                scheme: scheme,
                details: model.store.failureReports["searchMore:\(kind.rawValue)"] ?? model.store.failureReports["search"]
            ) { Task { await model.store.loadMoreSearch(kind) } }
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

    /// Mock search over the mock library. The catalog scope pretends to be
    /// the whole service, so it searches everything with artist names too.
    private var results: SearchResultSet {
        if model.store.isReal {
            guard realState == .loaded, let hits = model.store.search else { return SearchResultSet() }
            return SearchResultSet(artists: hits.artists.map {
                               ArtistHit(id: $0.id, name: $0.title, serverId: $0.id.rawId, palette: $0.palette,
                                         seed: 0, albumCount: $0.count)
                           },
                           albums: hits.albums, playlists: hits.playlists,
                           tracks: hits.songs.map { ($0.album, $0.track) })
        }
        // No source and no demo arguments: nothing to search.
        guard LaunchConfig.demoContent else { return SearchResultSet() }
        let q = query.lowercased()
        let all = model.library.albums()
        var r = SearchResultSet()
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

    /// The All page (Android SearchAllTiles): the best match, then each section as Home's tiles, best first, the
    /// listener's notes and memories merged into Songs and Albums; Show all opens a section's own chip.
    private func allResults(_ r: SearchResultSet) -> some View {
        SearchAllTiles(page: memories.page(r.names, query: trimmedQuery, columns: tileColumns), query: trimmedQuery,
                       results: r, memories: memories, providerName: providerName, width: width, columns: tileColumns,
                       scheme: scheme, actions: tileActions(r), gate: switchGate)
    }

    /// The Memories chip: every note and memory the query met, or the line that says none did.
    @ViewBuilder
    private func memoriesList(_ r: SearchResultSet) -> some View {
        let page = memories.page(r.names, query: trimmedQuery, columns: tileColumns)
        if !page.memories.isEmpty {
            SearchMemoriesList(page: page, query: trimmedQuery, results: r, memories: memories, width: width,
                               columns: tileColumns, scheme: scheme, actions: tileActions(r), gate: switchGate)
        } else if memoriesAnswered {
            SearchNoMemories(scheme: scheme)
                .frame(minHeight: 240)
        } else {
            Color.clear.frame(height: 1)
        }
    }

    /// The All page's tile columns: Home's three on a phone, the Library grid's count wider (iPad, Mac).
    private var tileColumns: Int {
        SearchMemories.columns(width: width + 32, regularWidth: hSize == .regular, compactHeight: vSize == .compact)
    }

    /// Leaves Search for what a tile opens (the phone folds it away; iPad's tab gives way the same).
    private func leaveSearch() {
        AppModel.dismissKeyboard()
        focused = false
        withAnimation(Yoin.spatial) { model.searchActive = false }
    }

    /// What the tiles do (Android SearchTileActions): a song plays the songs found from it, a note plays from its
    /// second or opens its album (noteTarget), an album with a memory opens on its scrapbook.
    private func tileActions(_ r: SearchResultSet) -> SearchTileActions {
        SearchTileActions(
            onSong: { i in
                guard r.tracks.indices.contains(i) else { return }
                switch NotStreamedRow.tap(r.tracks[i].1) {
                case .play: model.play(r.tracks.map { PlaylistEntry(album: $0.0, track: $0.1) }, startAt: i)
                case .explain: PlaylistActions.shared.show(String(localized: "Apple Music doesn't stream this song in your region yet."))
                }
            },
            onNote: { note, song, anchor in
                let entry = song.flatMap { r.tracks.indices.contains($0) ? PlaylistEntry(album: r.tracks[$0].0, track: r.tracks[$0].1) : nil }
                model.openSearchNote(note, song: entry, gate: switchGate, anchor: anchor, leave: leaveSearch)
            },
            onAlbum: { i, memory in
                guard r.albums.indices.contains(i) else { return }
                leaveSearch()
                model.openSearchAlbum(r.albums[i], holdsMemory: memory)
            },
            onLedgerAlbum: { album, anchor in
                model.openSearchLedgerAlbum(album, gate: switchGate, anchor: anchor, leave: leaveSearch)
            },
            onArtist: { i in if r.artists.indices.contains(i) { openArtist(r.artists[i]) } },
            onPlaylist: { i in
                guard r.playlists.indices.contains(i) else { return }
                leaveSearch()
                model.openPlaylist(r.playlists[i].id.rawId)
            },
            onShowAll: { section in select(Filter(section)) })
    }

    /// Switches the type chip, keeping each chip's place (Android LibrarySearchScrollMemory).
    private func select(_ next: Filter) {
        savedOffsets[positionKey] = offsetBox.value
        withAnimation(Yoin.spatial) { filter = next }
        scrollPosition.scrollTo(y: savedOffsets[positionKey] ?? 0)
    }

    /// Library's grid (LibraryGrid): 3 columns on a phone, more on a wider
    /// pane at Library's cell size. [width] is inside the 16 margins.
    private var columns: Int {
        LibraryGrid.columns(width: width + 32, regularWidth: hSize == .regular, compactHeight: vSize == .compact)
    }
    private var cell: CGFloat { LibraryGrid.cell(width: width + 32, columns: columns) }

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
                SecondaryEntityButton(
                    target: .artist(name: a.name, id: a.serverId),
                    onOpen: { openArtist(a) },
                    onPrimaryTap: { openArtist(a) }
                ) {
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

    /// Android SearchResultsGrid: the Library grid (LibraryGrid columns, 12 / 16).
    private func grid(_ items: [(Album, Bool)]) -> some View {
        LazyVGrid(columns: Array(repeating: GridItem(.fixed(cell), spacing: Tokens.library.GRID_GUTTER_H.cg, alignment: .top), count: columns),
                  alignment: .leading, spacing: Tokens.library.GRID_GUTTER_V.cg) {
            ForEach(items, id: \.0.id) { a, isArtist in gridCell(a, isArtist: isArtist) }
        }
    }

    /// AlbumCard / ArtistGridItem: cover, 5, titleSmall, bodySmall (12). An album drags out onto the queue
    /// or a playlist, as Library's cells do; an artist doesn't.
    @ViewBuilder
    private func gridCell(_ a: Album, isArtist: Bool) -> some View {
        if isArtist {
            gridButton(a, isArtist: true)
        } else {
            gridButton(a, isArtist: false).yoinDraggable { YoinDrag.album(a, model: model) }
        }
    }

    private func gridButton(_ a: Album, isArtist: Bool) -> some View {
        let openAction: () -> Void = {
            if isArtist {
                focused = false
                withAnimation(Yoin.spatial) { model.searchActive = false }
                model.openArtist(a.artist, id: a.artistId)
            } else {
                focused = false
                withAnimation(Yoin.spatial) { model.searchActive = false }
                model.open(a)
            }
        }
        return SecondaryEntityButton(
            target: isArtist ? .artist(name: a.artist, id: a.artistId) : .album(a),
            onOpen: openAction,
            onPrimaryTap: openAction
        ) {
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
                let key = "\(i)|\(item.1.id)"
                let explain = { withAnimation(Yoin.spatial) { NotStreamedRow.toggle(key, in: &notStreamedReasons) } }
                LibrarySongRow(album: item.0, track: item.1, current: model.playback.track.id == item.1.id,
                               scheme: scheme, horizontalPadding: 0, libraryAdd: libraryAdd(item.1),
                               reasonShown: notStreamedReasons.contains(key), explain: explain)
                    .trackRow(for: PlaylistEntry(album: item.0, track: item.1)) {
                        // A song Apple won't stream doesn't play: its tap says why (Android SearchSongRow).
                        switch NotStreamedRow.tap(item.1) {
                        case .play: model.play(tracks.map { PlaylistEntry(album: $0.0, track: $0.1) }, startAt: i)
                        case .explain: explain()
                        }
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
                SecondaryEntityButton(
                    target: .playlist(p),
                    onOpen: {
                        withAnimation(Yoin.spatial) { model.searchActive = false }
                        model.openPlaylist(p.id.rawId)
                    },
                    onPrimaryTap: {
                        withAnimation(Yoin.spatial) { model.searchActive = false }
                        model.openPlaylist(p.id.rawId)
                    }
                ) {
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
                .buttonStyle(.plain).pointerHighlight()
                // As Library's playlist row: dragged onto the queue; a playlist the user can write takes
                // songs, albums and playlists dropped on it.
                .yoinDraggable { YoinDrag.playlist(p, model: model) }
                .yoinDropTarget(in: RoundedRectangle(cornerRadius: Tokens.shape.LIST_ROW.cg, style: .continuous),
                                ink: scheme.primary, accepts: YoinDrag.accepts(p, model: model)) {
                    YoinDrag.add($0, to: p, model: model)
                }
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
