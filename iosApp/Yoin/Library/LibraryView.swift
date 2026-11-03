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

    private var scheme: CoverScheme {
        model.isIdle ? .brand(dark: colorScheme == .dark)
                     : CoverScheme(album: model.playback.album, dark: colorScheme == .dark)
    }

    private var items: [LibraryItem] {
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
                header
                chips
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
        .background(
            LinearGradient(colors: [scheme.surfaceContainer, scheme.background, scheme.surfaceContainerLow],
                           startPoint: .top, endPoint: .bottom)
            .ignoresSafeArea()
        )
    }

    // MARK: Header: search pill + avatar

    private var header: some View {
        HStack(spacing: 8) {
            Button { withAnimation(Yoin.spatial) { model.searchActive = true } } label: {
                HStack(spacing: 12) {
                    YoinIcon("search", size: 24).foregroundStyle(scheme.onSurfaceVariant)
                    Text("Search library").font(.gsf(16)).foregroundStyle(scheme.onSurfaceVariant)
                    Spacer()
                }
                .padding(.horizontal, 16)
                .frame(height: 56)
                .background(scheme.surfaceContainerHighest, in: .capsule)
                .contentShape(.capsule)
            }
            .buttonStyle(.plain)
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
            HStack(spacing: 8) {
                if filter != nil {
                    Button { withAnimation(Yoin.spatial) { filter = nil } } label: {
                        YoinIcon("close", size: 20)
                            .foregroundStyle(scheme.onSurfaceVariant)
                            .frame(width: 44, height: 44)
                            .background(scheme.surfaceContainerHigh, in: .circle)
                    }
                    .buttonStyle(PressScale())
                    .transition(.scale(scale: 0.6, anchor: .leading).combined(with: .opacity))
                    .accessibilityLabel("Clear filter")
                }
                ForEach([LibraryKind.playlists, .artists, .albums, .songs, .favorites], id: \.self) { kind in
                    let on = filter == kind
                    Button { withAnimation(Yoin.spatial) { filter = on ? nil : kind } } label: {
                        Text(kind.rawValue)
                            .font(.gsf(14, .medium))
                            .foregroundStyle(on ? scheme.onSecondaryContainer : scheme.onSurfaceVariant)
                            .lineLimit(1)
                            .padding(.horizontal, 16)
                            .padding(.vertical, 10)
                            .frame(minHeight: 44)
                            .background(on ? scheme.secondaryContainer : scheme.surfaceContainerHigh,
                                        in: .rect(cornerRadius: 18))
                    }
                    .buttonStyle(PressScale())
                }
            }
            .padding(.leading, filter == nil ? 16 : 8)
            .padding(.trailing, 16)
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
            Button {} label: { Label { Text("Recents") } icon: { YoinIcon("check", size: 18) } }
            Button("Recently added") {}
            Button("Alphabetical") {}
            Button("Creator") {}
        } label: {
            HStack(spacing: 6) {
                Text("Recents").font(.gsf(14, .medium)).foregroundStyle(scheme.onSurface)
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
            if isArtist { model.openArtist(item.title) } else if let album = item.album { model.open(album) }
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
