import SwiftUI

/// Library root, ported from Android ui/library/LibraryScreen.kt (Compact):
/// search pill + avatar header (no title on Compact), filter chips with the
/// ✕ that appears while one is on, the sort row as the grid's first row, and
/// the All grid fixed at 3 columns (16 sides, 12 / 16 gaps) mixing artists
/// (circles) with albums and playlists (bare covers, as on iOS Home). Songs
/// is a 68 pt row list. Numbers are cited in iosApp/docs/fidelity-home-np.md.
///
/// iOS adaptations: the pill opens the shared Search screen; covers follow
/// the iOS bare-cover rule; the fast scroller is not built yet.
struct LibraryView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.zoomNamespace) private var zoomNS
    @State private var filter: LibraryKind? = LaunchConfig.screen == "library-albums" ? .albums
        : LaunchConfig.screen == "library-songs" ? .songs : nil
    @State private var width: CGFloat = 370
    @State private var sort = "Recents"
    @State private var position = ScrollPosition()
    @State private var metrics = ScrollMetrics()
    @State private var awake = LaunchConfig.screen == "library-scroller"
    @State private var held = LaunchConfig.screen == "library-scroller"
    @State private var hideTask: Task<Void, Never>?

    struct ScrollMetrics: Equatable {
        var offset: CGFloat = 0, content: CGFloat = 1, viewport: CGFloat = 1
        var fraction: CGFloat { max(0, min(1, offset / max(1, content - viewport))) }
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
        case "Recently added": return base.sorted { ($0.album?.year ?? 0) > ($1.album?.year ?? 0) }
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
            VStack(alignment: .leading, spacing: 4) {
                Group {
                    if filter == .songs || filter == .favorites {
                        songList
                    } else {
                        grid
                    }
                }
                .padding(.top, 8)
            }
            .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
        }
        .contentMargins(.bottom, 24, for: .scrollContent)
        .scrollEdgeEffectStyle(.soft, for: [.top, .bottom])
        // Header and chips stay put (Android: only the grid scrolls) and float
        // over the grid as glass, so they sit in a top safe-area bar and the
        // system soft edge runs under them.
        .safeAreaBar(edge: .top) {
            VStack(alignment: .leading, spacing: 4) {
                header
                chips
            }
            .padding(.bottom, 4)
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
                    fraction: held && LaunchConfig.screen == "library-scroller" && metrics.offset == 0 ? 0.45 : metrics.fraction,
                    worthShowing: metrics.content > metrics.viewport * 3,
                    awake: awake, sections: sections, itemCount: items.count, columns: 3, scheme: scheme,
                    onDrag: { f in
                        position.scrollTo(y: f * max(0, metrics.content - metrics.viewport))
                        wake()
                    },
                    held: $held)
                .padding(.top, 136) // below the header and chips
                .padding(.bottom, 96)
            }
        }
        .onAppear {
            if LaunchConfig.screen == "library-scroller" {
                Task { @MainActor in
                    try? await Task.sleep(for: .milliseconds(400))
                    position.scrollTo(y: 0.45 * max(0, metrics.content - metrics.viewport))
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
            Button { withAnimation(Yoin.spatial) { model.searchActive = true } } label: {
                HStack(spacing: 12) {
                    // Adaptive label colors: they flip with the glass over dark content.
                    YoinIcon("search", size: 24).foregroundStyle(.secondary)
                    Text("Search library").font(.gsf(16)).foregroundStyle(.secondary)
                    Spacer()
                }
                .padding(.horizontal, 16)
                .frame(height: 56)
                .contentShape(.capsule)
            }
            .buttonStyle(.plain)
            .glassEffect(.regular.interactive(), in: .capsule)
            Button {} label: {
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
        .padding(.top, 4)
        .padding(.bottom, 4)
    }

    // MARK: Chips

    private var chips: some View {
        ScrollView(.horizontal) {
            GlassEffectContainer(spacing: 4) { HStack(spacing: 8) {
                if filter != nil {
                    Button { withAnimation(Yoin.spatial) { filter = nil } } label: {
                        YoinIcon("close", size: 20)
                            .foregroundStyle(.primary)
                            .frame(width: 44, height: 44)
                            .contentShape(.circle)
                    }
                    .buttonStyle(.plain)
                    .glassEffect(.regular.interactive(), in: .circle)
                    .transition(.scale(scale: 0.6, anchor: .leading).combined(with: .opacity))
                    .accessibilityLabel("Clear filter")
                }
                ForEach([LibraryKind.playlists, .artists, .albums, .songs, .favorites], id: \.self) { kind in
                    let on = filter == kind
                    Button { withAnimation(Yoin.spatial) { filter = on ? nil : kind } } label: {
                        Text(kind.rawValue)
                            .font(.gsf(14, on ? .semibold : .medium))
                            .foregroundStyle(on ? AnyShapeStyle(scheme.primary) : AnyShapeStyle(.primary))
                            .lineLimit(1)
                            .padding(.horizontal, 16)
                            .padding(.vertical, 10)
                            .frame(minHeight: 44)
                            .background { if on { SelectionPill(cornerRadius: 14) } }
                            .contentShape(.rect(cornerRadius: 18))
                    }
                    .buttonStyle(.plain)
                    // Pinned chips float over the grid: untinted glass; the
                    // selected one in the bar's accent style.
                    .glassEffect(.regular.interactive(), in: .rect(cornerRadius: 18))
                }
            }
            .padding(.leading, filter == nil ? 16 : 8)
            .padding(.trailing, 16)
            .padding(.vertical, 4) }
        }
        .scrollIndicators(.hidden)
        .mask(
            LinearGradient(stops: [.init(color: .black, location: 0), .init(color: .black, location: 0.94),
                                   .init(color: .clear, location: 1)], startPoint: .leading, endPoint: .trailing)
        )
    }

    // MARK: All / Artists / Albums / Playlists grid

    private var grid: some View {
        let cell = (width - 32 - 24) / 3
        return LazyVGrid(columns: Array(repeating: GridItem(.fixed(cell), spacing: 12, alignment: .top), count: 3),
                         alignment: .leading, spacing: 16) {
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
                Text(sort).font(.gsf(14, .medium)).foregroundStyle(scheme.onSurface)
                YoinIcon("sort", size: 18).foregroundStyle(scheme.onSurfaceVariant)
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 10)
            .frame(minHeight: 48)
            .contentShape(.rect(cornerRadius: 12))
        }
        .buttonStyle(.plain)
        .offset(x: -8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityLabel("Sort")
    }

    @ViewBuilder
    private func gridCell(_ item: LibraryItem, side: CGFloat) -> some View {
        let isArtist = item.kind == .artist
        Button {
            if isArtist { model.openArtist(item.title) }
            else if item.kind == .playlist { model.openPlaylist(item.id.rawId) }
            else if let album = item.album { model.open(album) }
        } label: {
            VStack(alignment: isArtist ? .center : .leading, spacing: 0) {
                EntityBackdrop(entity: isArtist ? .artist : .album, palette: item.palette,
                               seed: item.id.rawId.count, size: side, stroke: scheme.primary)
                    .zoomSource(item.id, zoomNS)
                Spacer().frame(height: 5)
                Text(item.title)
                    .font(.gsf(14, .medium))
                    .foregroundStyle(scheme.onSurface)
                    .lineLimit(1)
                Text(subtitle(item))
                    .font(.gsf(12))
                    .foregroundStyle(scheme.onSurfaceVariant)
                    .lineLimit(1)
            }
            .frame(width: side, alignment: isArtist ? .center : .leading)
        }
        .buttonStyle(PressScale())
    }

    private func subtitle(_ item: LibraryItem) -> String {
        switch item.kind {
        case .artist:
            let n = model.library.albums().filter { $0.artist == item.title }.count
            return n == 1 ? "1 album" : "\(n) albums"
        case .album: return item.album?.artist ?? ""
        case .playlist: return "Playlist"
        }
    }

    // MARK: Songs

    private var songList: some View {
        let tracks = model.library.albums().flatMap { a in a.tracks.prefix(3).map { (a, $0) } }
        return LazyVStack(spacing: 0) {
            ForEach(tracks, id: \.1.id) { a, t in
                let current = model.playback.track.id == t.id
                HStack(spacing: 12) {
                    CoverArt(palette: a.palette, cornerRadius: 0, seed: a.seed)
                        .frame(width: 48, height: 48)
                        .clipShape(.rect(cornerRadius: 4, style: .continuous))
                    VStack(alignment: .leading, spacing: 0) {
                        HStack(spacing: 0) {
                            if current {
                                YoinIcon("equalizer", size: 16).foregroundStyle(scheme.primary).padding(.trailing, 6)
                            }
                            Text(t.title).font(.gsf(14, .medium))
                                .foregroundStyle(current ? scheme.primary : scheme.onSurface)
                        }
                        Text("\(a.artist) · \(a.title)").font(.gsf(12)).foregroundStyle(scheme.onSurfaceVariant)
                    }
                    .lineLimit(1)
                    Spacer(minLength: 6)
                    Text(formatTime(t.durationSec))
                        .font(.gsf(14, .medium)).monospacedDigit()
                        .foregroundStyle(scheme.onSurfaceVariant)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
            }
        }
    }
}
