import SwiftUI

/// Search, the Apple Music iOS 26 way, with Android's content.
///
/// iOS form: the bar's search circle grows into a glass search field at the
/// top (magnifier, field, mic) with a separate glass close circle, a
/// full-width glass segmented control for the scopes below it, the keyboard
/// up at once, and the bottom bar hidden under the keyboard.
///
/// Android content (library/LibrarySearchFilter.kt, LibraryScreen.kt
/// SearchResultsContent): scopes Library and the provider's catalog (here
/// Apple Music); type filters All / Artists / Albums / Songs / Playlists;
/// All is sectioned artists → albums → playlists → songs; Artists and Albums
/// alone are a grid, Songs and Playlists a list; "No results found" when empty.
/// Android has no recent searches, so the empty state only names the scope.
struct SearchView: View {
    enum Scope: String, CaseIterable { case catalog = "Apple Music", library = "Library" }
    enum Filter: String, CaseIterable { case all = "All", artists = "Artists", albums = "Albums", songs = "Songs", playlists = "Playlists" }

    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var colorScheme
    var npNamespace: Namespace.ID
    @FocusState private var focused: Bool
    @State private var query = LaunchConfig.searchQuery
    @State private var scope: Scope = LaunchConfig.screen == "search-catalog" ? .catalog : .library
    @State private var filter: Filter = LaunchConfig.searchFilter
    @Namespace private var segNS
    /// The results column's width (inside the 16 margins), for the 3-column cells.
    @State private var width: CGFloat = 370

    private var scheme: CoverScheme {
        model.isIdle ? .brand(dark: colorScheme == .dark)
                     : CoverScheme(album: model.playback.album, dark: colorScheme == .dark)
    }

    var body: some View {
        ScrollView {
            content
                .frame(maxWidth: .infinity, alignment: .leading)
                .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
                .padding(.horizontal, 16)
                .padding(.top, 8)
                .padding(.bottom, Tokens.search.RESULTS_BOTTOM_RESERVE.cg)
        }
        .scrollDismissesKeyboard(.interactively)
        .scrollEdgeEffectStyle(.soft, for: .top)
        // The header is a safe-area bar so the system soft edge runs under it.
        .safeAreaBar(edge: .top) { header }
        // Android ExpandedFullScreenSearchBar: SearchBarDefaults.colors()
        // container = surfaceContainerHigh of the playing scheme.
        .background(scheme.surfaceContainerHigh.ignoresSafeArea())
        .onAppear { focused = LaunchConfig.searchQuery.isEmpty }
        // Server search: search3 once typing pauses (300 ms).
        .task(id: "\(model.store.generation)|\(query)") {
            guard model.store.isReal else { return }
            try? await Task.sleep(for: .milliseconds(300))
            guard !Task.isCancelled else { return }
            await model.store.runSearch(query)
        }
    }

    // MARK: Header

    private var header: some View {
        // Android LibrarySearchFilterRow sits 8 under the field (its vertical 8).
        VStack(spacing: Tokens.search.FILTER_ROW_PADDING_V.cg) {
            GlassEffectContainer(spacing: 8) {
                HStack(spacing: 8) {
                    HStack(spacing: 8) {
                        YoinIcon("search", size: 20).foregroundStyle(Yoin.inkVariant)
                        TextField(scope == .library ? "Search library" : "Search Apple Music", text: $query)
                            .font(.gsf(17))
                            .focused($focused)
                            .submitLabel(.search)
                        if !query.isEmpty {
                            Button { query = "" } label: {
                                YoinIcon("close", size: 16).foregroundStyle(Yoin.inkVariant)
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel("Clear")
                        } else {
                            YoinIcon("mic", size: 20).foregroundStyle(Yoin.inkVariant)
                        }
                    }
                    .padding(.horizontal, 16)
                    .frame(height: 48)
                    .glassEffect(.regular.interactive(), in: .capsule)
                    .matchedGeometryEffect(id: "search.surface", in: npNamespace)

                    Button { close() } label: {
                        YoinIcon("close", size: 20)
                            .foregroundStyle(Yoin.ink)
                            .frame(width: 48, height: 48)
                            .contentShape(.circle)
                    }
                    .buttonStyle(.plain)
                    .glassEffect(.regular.interactive(), in: .circle)
                    .accessibilityLabel("Close search")
                }
            }
            // A Subsonic server has no catalog apart from the library.
            if !model.store.isReal { scopes }
            if !query.isEmpty { filters }
        }
        .padding(.horizontal, 16)
        .padding(.bottom, Tokens.search.FILTER_ROW_PADDING_V.cg)
    }

    /// Full-width glass segmented control; the selected scope carries the
    /// accent in the glass (Search = primary role; owner, 2026-11-04).
    private var scopes: some View {
        GlassEffectContainer(spacing: 0) {
            HStack(spacing: 0) {
                ForEach(Scope.allCases, id: \.self) { s in
                    let on = scope == s
                    Button { withAnimation(Yoin.spatial) { scope = s } } label: {
                        Text(s.rawValue)
                            .lineLimit(1)
                            .yoinRole(Tokens.type.labelLarge)
                            .foregroundStyle(on ? scheme.onPrimary : scheme.onSurface)
                            .frame(maxWidth: .infinity, minHeight: 40)
                            .contentShape(.capsule)
                            .animation(Yoin.effects, value: on)
                    }
                    .buttonStyle(.plain)
                    .background {
                        if on {
                            Capsule().fill(.clear)
                                .glassEffect(.regular.tint(scheme.primary.opacity(0.85)).interactive(), in: .capsule)
                                .glassEffectID("scope", in: segNS)
                        }
                    }
                }
            }
            .padding(4)
            .glassEffect(.regular, in: .capsule)
        }
    }

    /// Android's type chips (ExpressiveSegmentChip: 44 high, 18 corners,
    /// labelLarge, 16 × 10), shown once there is a query; the one on carries
    /// primary in the glass.
    private var filters: some View {
        ScrollView(.horizontal) {
            GlassEffectContainer(spacing: 4) {
                HStack(spacing: Tokens.library.CHIP_GAP.cg) {
                    ForEach(Filter.allCases, id: \.self) { f in
                        let on = filter == f
                        Button { withAnimation(Yoin.spatial) { filter = f } } label: {
                            LibraryChipLabel(text: f.rawValue, on: on, onAccent: scheme.onPrimary)
                        }
                        .buttonStyle(.plain)
                        .libraryChipGlass(on: on, accent: scheme.primary)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 4)
            }
        }
        .scrollIndicators(.hidden)
        // Full bleed like Android's row: chips scroll out under the edges.
        .padding(.horizontal, -16)
    }

    // MARK: Content

    @ViewBuilder
    private var content: some View {
        if query.isEmpty {
            VStack(spacing: 10) {
                YoinIcon("search", size: 44).foregroundStyle(scheme.onSurfaceVariant)
                Text(scope == .library || model.store.isReal ? "Search library" : "Search Apple Music")
                    .font(.gsf(20, .semibold))
                    .foregroundStyle(scheme.onSurface)
            }
            .frame(maxWidth: .infinity)
            .padding(.top, 140)
        } else {
            let r = results
            let shownEmpty = switch filter {
            case .all: r.albums.isEmpty && r.tracks.isEmpty && r.artists.isEmpty && r.playlists.isEmpty
            case .artists: r.artists.isEmpty
            case .albums: r.albums.isEmpty
            case .songs: r.tracks.isEmpty
            case .playlists: r.playlists.isEmpty
            }
            if shownEmpty && model.store.isReal && model.store.searchPhase == .failed {
                RetryRow(scheme: scheme) { Task { await model.store.runSearch(query) } }
            } else if shownEmpty && model.store.isReal && model.store.search?.query != query.trimmingCharacters(in: .whitespacesAndNewlines) {
                RowSkeleton(scheme: scheme, rows: 5)
            } else if shownEmpty {
                // Android EmptyState: a Card (16) panel, surfaceContainerHighest
                // at 72 %, bodyLarge onSurfaceVariant, padding 20 × 18,
                // centred in the results area.
                Text("No results found")
                    .yoinRole(Tokens.type.bodyLarge)
                    .foregroundStyle(scheme.onSurfaceVariant)
                    .padding(.horizontal, Tokens.library.EMPTY_STATE_PADDING_H.cg)
                    .padding(.vertical, Tokens.library.EMPTY_STATE_PADDING_V.cg)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(scheme.surfaceContainerHighest.opacity(Tokens.library.EMPTY_STATE_ALPHA),
                                in: .rect(cornerRadius: Tokens.shape.CARD.cg, style: .continuous))
                    .containerRelativeFrame(.vertical) { h, _ in h * 0.6 }
            } else {
                switch filter {
                case .all: allResults(r)
                case .artists: artistGrid(r.artists)
                case .albums: grid(r.albums.map { ($0, false) })
                case .songs: songList(r.tracks)
                case .playlists: playlistList(r.playlists)
                }
            }
        }
    }

    /// An artist result: the mock borrows its first album's cover.
    struct ArtistHit: Identifiable {
        var id: MediaId
        var name: String
        var serverId: String?
        var palette: CoverPalette
        var seed: Int
        var albumCount: Int
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
            guard let hits = model.store.search else { return Results() }
            return Results(artists: hits.artists.map {
                               ArtistHit(id: $0.id, name: $0.title, serverId: $0.id.rawId, palette: $0.palette,
                                         seed: 0, albumCount: $0.count ?? 0)
                           },
                           albums: hits.albums, playlists: hits.playlists,
                           tracks: hits.songs.map { ($0.album, $0.track) })
        }
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
                sectionHeader("Artists")
                ForEach(r.artists) { a in artistRow(a) }
            }
            if !r.albums.isEmpty {
                sectionHeader("Albums")
                // Fixed 3 columns on Compact, chunked into rows with no
                // vertical gap (a LazyColumn of Rows, spacedBy 12 across).
                albumRows(r.albums)
            }
            if !r.playlists.isEmpty {
                sectionHeader("Playlists")
                playlistList(r.playlists)
            }
            if !r.tracks.isEmpty {
                sectionHeader("Songs")
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

    /// Library's Compact cell: (width − 32 − 24) / 3.
    private var cell: CGFloat { (width - 2 * Tokens.library.GRID_GUTTER_H.cg) / 3 }

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
                metaPill(albumCount(a.albumCount))
            }
            .padding(.vertical, Tokens.library.ROW_PADDING_V.cg)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
    }

    private func albumCount(_ n: Int) -> String {
        n == 1 ? "1 album" : "\(n) albums"
    }

    private func openArtist(_ a: ArtistHit) {
        focused = false
        withAnimation(Yoin.spatial) { model.searchActive = false }
        model.openArtist(a.name, id: a.serverId)
    }

    /// Artists alone: the Library grid's circles.
    private func artistGrid(_ artists: [ArtistHit]) -> some View {
        LazyVGrid(columns: Array(repeating: GridItem(.fixed(cell), spacing: Tokens.library.GRID_GUTTER_H.cg, alignment: .top), count: 3),
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
            ForEach(Array(stride(from: 0, to: albums.count, by: 3)), id: \.self) { i in
                HStack(alignment: .top, spacing: Tokens.library.GRID_GUTTER_H.cg) {
                    ForEach(albums[i..<min(i + 3, albums.count)]) { a in gridCell(a, isArtist: false) }
                }
            }
        }
    }

    /// Android SearchResultsGrid: the Library grid (3 fixed columns, 12 / 16).
    private func grid(_ items: [(Album, Bool)]) -> some View {
        LazyVGrid(columns: Array(repeating: GridItem(.fixed(cell), spacing: Tokens.library.GRID_GUTTER_H.cg, alignment: .top), count: 3),
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

    /// Android SearchSongRow = SongListItem (Library's song row).
    private func songList(_ tracks: [(Album, Track)]) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(tracks.enumerated()), id: \.offset) { i, item in
                LibrarySongRow(album: item.0, track: item.1, current: model.playback.track.id == item.1.id,
                               scheme: scheme, horizontalPadding: 0)
                    .onTapGesture {
                        model.play(tracks.map { PlaylistEntry(album: $0.0, track: $0.1) }, startAt: i)
                    }
            }
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
        focused = false
        withAnimation(Yoin.spatial) { model.searchActive = false }
    }
}
