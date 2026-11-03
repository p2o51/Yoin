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

    private var scheme: CoverScheme {
        model.isIdle ? .brand(dark: colorScheme == .dark)
                     : CoverScheme(album: model.playback.album, dark: colorScheme == .dark)
    }

    var body: some View {
        ScrollView {
            content
                .padding(.horizontal, 16)
                .padding(.top, 8)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .scrollDismissesKeyboard(.interactively)
        .scrollEdgeEffectStyle(.soft, for: .top)
        // The header is a safe-area bar so the system soft edge runs under it.
        .safeAreaBar(edge: .top) { header }
        .background(Yoin.surface.ignoresSafeArea())
        .onAppear { focused = LaunchConfig.searchQuery.isEmpty }
    }

    // MARK: Header

    private var header: some View {
        VStack(spacing: 10) {
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
            scopes
            if !query.isEmpty { filters }
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 8)
    }

    /// Full-width glass segmented control; the selected scope is a lighter pill.
    private var scopes: some View {
        HStack(spacing: 0) {
            ForEach(Scope.allCases, id: \.self) { s in
                Button { withAnimation(Yoin.spatial) { scope = s } } label: {
                    Text(s.rawValue)
                        .font(.gsf(15, scope == s ? .semibold : .medium))
                        .foregroundStyle(scope == s ? scheme.primary : Yoin.ink)
                        .frame(maxWidth: .infinity, minHeight: 40)
                        .background {
                            if scope == s {
                                // Same selection pill as the bottom bar: grey on light glass.
                                Capsule()
                                    .fill(colorScheme == .dark ? Color.white.opacity(0.16) : Color.black.opacity(0.07))
                                    .matchedGeometryEffect(id: "seg", in: segNS)
                            }
                        }
                        .contentShape(.capsule)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(4)
        .glassEffect(.regular, in: .capsule)
    }

    /// Android's type chips, shown once there is a query.
    private var filters: some View {
        ScrollView(.horizontal) {
            HStack(spacing: 8) {
                ForEach(Filter.allCases, id: \.self) { f in
                    let on = filter == f
                    Button { withAnimation(Yoin.spatial) { filter = f } } label: {
                        Text(f.rawValue)
                            .font(.gsf(14, .medium))
                            .foregroundStyle(on ? scheme.onPrimary : Yoin.ink)
                            .padding(.horizontal, 14)
                            .frame(height: 34)
                            .background(on ? scheme.primary : Yoin.surfaceContainerHigh, in: .capsule)
                    }
                    .buttonStyle(PressScale())
                }
            }
        }
        .scrollIndicators(.hidden)
    }

    // MARK: Content

    @ViewBuilder
    private var content: some View {
        if query.isEmpty {
            VStack(spacing: 10) {
                YoinIcon("search", size: 44).foregroundStyle(Yoin.inkVariant)
                Text(scope == .library ? "Search library" : "Search Apple Music")
                    .font(.gsf(20, .semibold))
                    .foregroundStyle(Yoin.ink)
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
            if shownEmpty {
                Text("No results found")
                    .font(.gsf(16))
                    .foregroundStyle(Yoin.inkVariant)
                    .frame(maxWidth: .infinity)
                    .padding(.top, 140)
            } else {
                switch filter {
                case .all: allResults(r)
                case .artists: grid(r.artists.map { ($0, true) })
                case .albums: grid(r.albums.map { ($0, false) })
                case .songs: songList(r.tracks)
                case .playlists: playlistList(r.playlists)
                }
            }
        }
    }

    private struct Results {
        var artists: [Album] = []   // artist rows use the artist's first album palette
        var albums: [Album] = []
        var playlists: [LibraryItem] = []
        var tracks: [(Album, Track)] = []
    }

    /// Mock search over the mock library. The catalog scope pretends to be
    /// the whole service, so it searches everything with artist names too.
    private var results: Results {
        let q = query.lowercased()
        let all = model.library.albums()
        var r = Results()
        var seenArtists = Set<String>()
        for a in all where a.artist.lowercased().contains(q) && seenArtists.insert(a.artist).inserted {
            r.artists.append(a)
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

    @ViewBuilder
    private func allResults(_ r: Results) -> some View {
        VStack(alignment: .leading, spacing: 18) {
            if !r.artists.isEmpty {
                section("Artists") {
                    ForEach(r.artists) { a in artistRow(a) }
                }
            }
            if !r.albums.isEmpty {
                section("Albums") { grid(r.albums.map { ($0, false) }) }
            }
            if !r.playlists.isEmpty {
                section("Playlists") { playlistList(r.playlists) }
            }
            if !r.tracks.isEmpty {
                section("Songs") { songList(r.tracks) }
            }
        }
    }

    private func section<C: View>(_ title: String, @ViewBuilder _ c: () -> C) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title).font(.gsf(18, .semibold)).foregroundStyle(Yoin.ink)
            c()
        }
    }

    private func artistRow(_ a: Album) -> some View {
        Button {
            focused = false
            withAnimation(Yoin.spatial) { model.searchActive = false }
            model.openArtist(a.artist)
        } label: {
            HStack(spacing: 12) {
                EntityBackdrop(entity: .artist, palette: a.palette, seed: a.seed, size: 48)
                Text(a.artist).font(.gsf(16, .medium)).foregroundStyle(Yoin.ink)
                Spacer()
            }
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
    }

    private func grid(_ items: [(Album, Bool)]) -> some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 104), spacing: 12, alignment: .top)],
                  alignment: .leading, spacing: 16) {
            ForEach(items, id: \.0.id) { a, isArtist in
                Button { model.open(a) } label: {
                    VStack(alignment: isArtist ? .center : .leading, spacing: 6) {
                        EntityBackdrop(entity: isArtist ? .artist : .album, palette: a.palette, seed: a.seed, size: 104)
                        Text(isArtist ? a.artist : a.title)
                            .font(YoinFont.font(size: 14, YoinFont.Axes(weight: 600, round: 100)))
                            .foregroundStyle(Yoin.ink)
                        if !isArtist {
                            Text(a.artist).font(.gsf(12)).foregroundStyle(Yoin.inkVariant)
                        }
                    }
                    .lineLimit(1)
                    .frame(width: 104, alignment: isArtist ? .center : .leading)
                }
                .buttonStyle(PressScale())
            }
        }
    }

    /// Android SearchSongRow: 48 cover, 12 gap, title over artist · album.
    private func songList(_ tracks: [(Album, Track)]) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            ForEach(tracks, id: \.1.id) { a, t in
                HStack(spacing: 12) {
                    EntityBackdrop(entity: .album, palette: a.palette, seed: a.seed, size: 48)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(t.title).font(.gsf(16)).foregroundStyle(Yoin.ink)
                        Text("\(a.artist)  \(a.title)").font(.gsf(14)).foregroundStyle(Yoin.inkVariant)
                    }
                    .lineLimit(1)
                    Spacer()
                }
                .padding(.vertical, 6)
            }
        }
    }

    private func playlistList(_ items: [LibraryItem]) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            ForEach(items) { p in
                Button {
                    withAnimation(Yoin.spatial) { model.searchActive = false }
                    model.openPlaylist(p.id.rawId)
                } label: { HStack(spacing: 12) {
                    EntityBackdrop(entity: .playlist, palette: p.palette, seed: 3, size: 48)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(p.title).font(.gsf(16)).foregroundStyle(Yoin.ink)
                        Text(p.subtitle).font(.gsf(14)).foregroundStyle(Yoin.inkVariant)
                    }
                    Spacer()
                }
                .contentShape(.rect) }
                .buttonStyle(.plain)
                .padding(.vertical, 6)
            }
        }
    }

    private func close() {
        focused = false
        withAnimation(Yoin.spatial) { model.searchActive = false }
    }
}
