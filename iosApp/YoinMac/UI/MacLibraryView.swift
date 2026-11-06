import SwiftUI

/// Library: a glass filter rail (Albums / Artists / Playlists / Songs …) over the grids. Albums, Artists and
/// Playlists sort as on iOS (LibrarySort: Recents, Recently added, Alphabetical, Creator — only the orders the
/// data backs), with a jump bar down the right edge for the sort's runs (letters, years, Today / This week).
struct MacLibraryView: View {
    @Environment(MacModel.self) private var model
    @Environment(\.macTint) private var tint
    @AppStorage("librarySort.All") private var allSort = LibrarySortOrder.recents.rawValue
    @AppStorage("librarySort.Albums") private var albumSort = LibrarySortOrder.recents.rawValue
    @AppStorage("librarySort.Artists") private var artistSort = LibrarySortOrder.recents.rawValue
    @AppStorage("librarySort.Playlists") private var playlistSort = LibrarySortOrder.recents.rawValue

    /// Android librarySortOptions: only orders the data can back.
    static func sortOptions(_ kind: MacModel.LibraryKind) -> [LibrarySortOrder] {
        switch kind {
        case .all, .albums: [.recents, .recentlyAdded, .alphabetical, .creator]
        case .artists: [.recents, .alphabetical]
        case .playlists: [.recents, .alphabetical, .creator]
        default: []
        }
    }

    private func sort(_ kind: MacModel.LibraryKind) -> LibrarySortOrder {
        let raw = kind == .all ? allSort : kind == .albums ? albumSort : kind == .artists ? artistSort : playlistSort
        let chosen = LibrarySortOrder(rawValue: raw) ?? .recents
        return Self.sortOptions(kind).contains(chosen) ? chosen : .recents
    }

    private func setSort(_ kind: MacModel.LibraryKind, _ order: LibrarySortOrder) {
        withAnimation(Yoin.spatial) {
            switch kind {
            case .all: allSort = order.rawValue
            case .albums: albumSort = order.rawValue
            case .artists: artistSort = order.rawValue
            default: playlistSort = order.rawValue
            }
        }
    }

    private var sorter: LibrarySorter {
        LibrarySorter(recents: model.libraryRecents(), ignoresArticles: model.session.client != nil)
    }

    /// The kind's items in its order, and the jump bar's runs.
    private func sorted(_ kind: MacModel.LibraryKind) -> (items: [LibraryItem], sections: [LibraryFastScroller.Section]) {
        let lib = model.library
        let albumItems = { lib.albums.map {
            LibraryItem(id: $0.id, kind: .album, title: $0.title, subtitle: $0.artist, palette: $0.palette, album: $0)
        } }
        let raw: [LibraryItem] = switch kind {
        case .all: lib.artists + albumItems() + lib.playlists
        case .albums: albumItems()
        case .artists: lib.artists
        case .playlists: lib.playlists
        default: []
        }
        let sorter = sorter
        let order = sort(kind)
        let items = sorter.sorted(raw, by: order)
        let sections = LibraryScrollIndex.sections(items, sort: order, sorter: sorter)
        return (items, order == .alphabetical || order == .creator ? Self.grouped(sections) : sections)
    }

    /// The jump bar's groups for a by-name order: # and A–Z, then one each for kana (あ), Hangul (가) and
    /// CJK (汉) — one entry per character there was noise (40 single ideographs). Each group jumps to its
    /// first item in the list.
    static func grouped(_ sections: [LibraryFastScroller.Section]) -> [LibraryFastScroller.Section] {
        func group(_ label: String) -> String {
            guard let u = label.unicodeScalars.first else { return "#" }
            switch u.value {
            case 0x3040...0x30FF, 0x31F0...0x31FF, 0xFF66...0xFF9D: return "あ"
            case 0x1100...0x11FF, 0x3130...0x318F, 0xAC00...0xD7AF: return "가"
            case 0x3400...0x4DBF, 0x4E00...0x9FFF, 0xF900...0xFAFF, 0x20000...0x2FFFF: return "汉"
            default:
                let folded = label.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: nil).uppercased()
                if let c = folded.first, c.isASCII, c.isLetter { return String(c) }
                return "#"
            }
        }
        var first: [String: Int] = [:]
        for s in sections {
            let g = group(s.label)
            first[g] = min(first[g] ?? .max, s.start)
        }
        func rank(_ g: String) -> Int {
            switch g {
            case "#": 0
            case "あ": 100
            case "가": 101
            case "汉": 102
            default: Int(g.unicodeScalars.first!.value)
            }
        }
        return first.keys.sorted { rank($0) < rank($1) }.map { .init(label: $0, start: first[$0]!) }
    }

    var body: some View {
        @Bindable var model = model
        let lib = model.library
        let kind = model.libraryKind
        let list = sorted(kind)
        ScrollViewReader { reader in
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    PageHeader(eyebrow: nil, title: "Library")
                    // Room for the floating rail (one or two rows).
                    Color.clear.frame(height: max(64, railHeight + 16))
                    switch kind {
                    case .all:
                        // Android's All: artists, albums and playlists in one grid, in the chosen order.
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: 124), spacing: 16, alignment: .top)], spacing: 20) {
                            ForEach(Array(list.items.enumerated()), id: \.element.id) { i, item in
                                Group {
                                    switch item.kind {
                                    case .album: if let a = item.album { AlbumTile(album: a) }
                                    case .artist: ArtistTile(item: item)
                                    case .playlist: PlaylistTile(item: item)
                                    }
                                }
                                .id(Self.anchor(i))
                            }
                        }
                        PhaseNote(phase: lib.phase("albums"), empty: list.items.isEmpty, emptyText: "Nothing in your library yet") {
                            Task { await lib.loadAlbums(force: true) }
                        }
                    case .favorites:
                        MacFavoritesView()
                    case .albums:
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: 124), spacing: 16, alignment: .top)], spacing: 20) {
                            ForEach(Array(list.items.enumerated()), id: \.element.id) { i, item in
                                if let a = item.album { AlbumTile(album: a).id(Self.anchor(i)) }
                            }
                        }
                        PhaseNote(phase: lib.phase("albums"), empty: lib.albums.isEmpty, emptyText: "No albums") {
                            Task { await lib.loadAlbums(force: true) }
                        }
                    case .artists:
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: 120), spacing: 16, alignment: .top)], spacing: 20) {
                            ForEach(Array(list.items.enumerated()), id: \.element.id) { i, item in
                                ArtistTile(item: item).id(Self.anchor(i))
                            }
                        }
                        PhaseNote(phase: lib.phase("artists"), empty: lib.artists.isEmpty, emptyText: "No artists") {
                            Task { await lib.loadArtists(force: true) }
                        }
                    case .playlists:
                        if model.session.client != nil || model.session.isSpotify {
                            NewPlaylistButton().padding(.bottom, 16)
                        }
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: 124), spacing: 16, alignment: .top)], spacing: 20) {
                            ForEach(Array(list.items.enumerated()), id: \.element.id) { i, item in
                                PlaylistTile(item: item).id(Self.anchor(i))
                            }
                        }
                        PhaseNote(phase: lib.phase("playlists"), empty: lib.playlists.isEmpty, emptyText: "No playlists") {
                            Task { await lib.loadPlaylists(force: true) }
                        }
                    case .memories:
                        MacMemoriesGrid()
                    case .downloads:
                        MacDownloadsList()
                    case .songs:
                        LazyVStack(spacing: 2) {
                            ForEach(Array(lib.songs.enumerated()), id: \.offset) { i, e in
                                TrackRow(entry: e, showCover: true) { model.play(lib.songs, startAt: i) }
                                    .onAppear {
                                        // Near the end: the next albums' songs (Subsonic's pager).
                                        if i >= lib.songs.count - 12, lib.songsMore == .available {
                                            Task { await lib.loadMoreSongs() }
                                        }
                                    }
                            }
                        }
                        switch lib.songsMore {
                        case .loading:
                            ProgressView().controlSize(.small).frame(maxWidth: .infinity).padding(.vertical, 16)
                        case .failed:
                            Button("Couldn't load more songs. Retry") { Task { await lib.loadMoreSongs() } }
                                .buttonStyle(.link).frame(maxWidth: .infinity).padding(.vertical, 16)
                        default:
                            EmptyView()
                        }
                        PhaseNote(phase: lib.phase("songs"), empty: lib.songs.isEmpty, emptyText: "No songs") {
                            Task { await lib.loadSongs(force: true) }
                        }
                    }
                }
                .padding(.horizontal, 32)
                .padding(.trailing, list.sections.count > 1 ? 24 : 0)
                .padding(.bottom, 142)
            }
            .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { pageWidth = $0 }
            .overlay(alignment: .trailing) {
                // Only where a jump means something (letters, years) and the column has room for the bar.
                if list.sections.count > 1, list.items.count > 24, sort(kind) != .recents, pageWidth >= 560 {
                    JumpBar(sections: list.sections) { start in
                        withAnimation(Yoin.spatial) { reader.scrollTo(Self.anchor(start), anchor: .top) }
                    }
                    .padding(.trailing, 6)
                    .padding(.top, 168)
                    .padding(.bottom, 142)
                    .transition(.opacity)
                }
            }
        }
        .overlay(alignment: .topLeading) {
            // The rail and the sort keep their size: side by side when they fit, the sort under the rail in a
            // narrow column, and the rail a menu of the current tab when even it doesn't fit. Never squeezed.
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 10) {
                    FilterRail(selection: $model.libraryKind).fixedSize()
                    sortMenu(kind)
                }
                VStack(alignment: .leading, spacing: 8) {
                    FilterRail(selection: $model.libraryKind).fixedSize()
                    sortMenu(kind)
                }
                HStack(spacing: 8) {
                    kindMenu
                    sortMenu(kind)
                }
                VStack(alignment: .leading, spacing: 8) {
                    kindMenu
                    sortMenu(kind)
                }
            }
            .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { railHeight = $0 }
            .padding(.horizontal, 32)
            .padding(.top, 108)
            .animation(Yoin.effects, value: kind)
        }
        .task(id: "\(model.libraryKind.rawValue)|\(model.session.accountKey)") {
            switch model.libraryKind {
            case .all:
                async let a: Void = lib.loadAlbums()
                async let r: Void = lib.loadArtists()
                async let p: Void = lib.loadPlaylists()
                _ = await (a, r, p)
            case .favorites: await lib.loadFavorites()
            case .albums: await lib.loadAlbums()
            case .artists: await lib.loadArtists()
            case .songs: await lib.loadSongs()
            case .playlists: await lib.loadPlaylists()
            case .downloads, .memories: break
            }
        }
    }

    static func anchor(_ i: Int) -> String { "library-item-\(i)" }

    @State private var railHeight: CGFloat = 48
    @State private var pageWidth: CGFloat = 800

    /// The rail as one menu button naming the tab (a column too narrow for the rail).
    private var kindMenu: some View {
        Menu {
            ForEach(model.libraryKinds, id: \.self) { k in
                Button(k.rawValue) { withAnimation(Yoin.spatialFast) { model.libraryKind = k } }
            }
        } label: {
            HStack(spacing: 6) {
                Text(model.libraryKind.rawValue).font(.gsf(13.5, .semibold)).lineLimit(1)
                YoinIcon("chevron_down", size: 14)
            }
            .foregroundStyle(Yoin.ink)
            .padding(.horizontal, 14).padding(.vertical, 8)
        }
        .menuStyle(.button).buttonStyle(.plain).menuIndicator(.hidden).fixedSize()
        .glassEffect(.regular.interactive(), in: .capsule)
    }

    @ViewBuilder
    private func sortMenu(_ kind: MacModel.LibraryKind) -> some View {
        let options = Self.sortOptions(kind)
        if !options.isEmpty {
            Menu {
                Picker("Sort", selection: Binding(get: { sort(kind) }, set: { setSort(kind, $0) })) {
                    ForEach(options, id: \.self) { Text($0.label).tag($0) }
                }
                .pickerStyle(.inline)
            } label: {
                HStack(spacing: 6) {
                    YoinIcon("sort", size: 16)
                    Text(sort(kind).label).font(.gsf(13.5)).lineLimit(1)
                }
                .foregroundStyle(Yoin.ink)
                .padding(.horizontal, 14).padding(.vertical, 8)
            }
            .menuStyle(.button).buttonStyle(.plain).menuIndicator(.hidden).fixedSize()
            .glassEffect(.regular.interactive(), in: .capsule)
            .transition(.opacity)
        }
    }
}

/// The Mac's fast scroller (iOS LibraryFastScroller): the sort's runs down the right edge, a click jumps
/// there. Too many to fit, it keeps every n-th and the last.
private struct JumpBar: View {
    var sections: [LibraryFastScroller.Section]
    var jump: (Int) -> Void
    @Environment(\.macTint) private var tint
    @State private var hovered: Int?

    var body: some View {
        GeometryReader { g in
            let room = max(1, Int(g.size.height / 16))
            let step = max(1, Int(ceil(Double(sections.count) / Double(room))))
            let shown = sections.enumerated().filter { $0.offset % step == 0 || $0.offset == sections.count - 1 }
            VStack(spacing: 0) {
                ForEach(shown, id: \.offset) { i, section in
                    Button { jump(section.start) } label: {
                        Text(section.label)
                            .font(.gsf(10.5, hovered == i ? .semibold : .medium))
                            .foregroundStyle(hovered == i ? tint.primary : Yoin.inkVariant)
                            .lineLimit(1)
                            .frame(minWidth: 18, minHeight: 16)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .onHover { hovered = $0 ? i : (hovered == i ? nil : hovered) }
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .frame(width: 44)
    }
}

/// The glass segment rail floating over the grid.
struct FilterRail: View {
    @Binding var selection: MacModel.LibraryKind
    @Environment(MacModel.self) private var model
    @Environment(\.macTint) private var tint
    @Namespace private var ns

    var body: some View {
        HStack(spacing: 2) {
            // Downloads are a Subsonic thing (Apple Music doesn't let an app store its songs).
            ForEach(model.libraryKinds, id: \.self) { k in
                Button { withAnimation(Yoin.spatialFast) { selection = k } } label: {
                    Text(k.rawValue)
                        .lineLimit(1).fixedSize()
                        .font(.gsf(13.5, selection == k ? .semibold : .regular))
                        .foregroundStyle(selection == k ? tint.onPrimary : Yoin.ink)
                        .padding(.horizontal, 16).padding(.vertical, 8)
                        .background {
                            if selection == k {
                                Capsule().fill(tint.primary.opacity(0.85)).matchedGeometryEffect(id: "rail", in: ns)
                            }
                        }
                        .contentShape(Capsule())
                }
                .buttonStyle(.plain)
            }
        }
        .padding(4)
        .glassEffect(.regular, in: .capsule)
    }
}

/// Library › Downloads: every album, playlist and song kept on this Mac, newest first, with its state.
struct MacDownloadsList: View {
    @Environment(MacModel.self) private var model
    @Environment(\.macTint) private var tint

    var body: some View {
        let offline = OfflineDownloads.shared
        let entries = offline.entries
        VStack(alignment: .leading, spacing: 2) {
            if entries.isEmpty {
                Text("Albums and songs you download play without a connection. Right-click one to download it.")
                    .font(.gsf(14)).foregroundStyle(Yoin.inkVariant)
                    .padding(.top, 8)
            } else {
                Text("\(entries.count) \(entries.count == 1 ? "download" : "downloads")  "
                     + ByteCountFormatter.string(fromByteCount: offline.bytes, countStyle: .file))
                    .font(.gsf(13)).foregroundStyle(Yoin.inkVariant)
                    .padding(.bottom, 8)
            }
            ForEach(entries) { e in
                HStack(spacing: 12) {
                    YoinIcon(e.collection.kind == .song ? "music_note" : e.collection.kind == .album ? "album" : "playlist",
                             size: 20)
                        .foregroundStyle(tint.primary)
                        .frame(width: 40, height: 40)
                        .background(RoundedRectangle(cornerRadius: Tokens.shape.THUMB.cg, style: .continuous)
                            .fill(tint.container.opacity(0.6)))
                    VStack(alignment: .leading, spacing: 1) {
                        Text(e.collection.title).font(.gsf(14, .medium)).foregroundStyle(Yoin.ink).lineLimit(1)
                        Text(subtitle(e)).font(.gsf(12.5)).foregroundStyle(Yoin.inkVariant).lineLimit(1)
                    }
                    Spacer(minLength: 8)
                    if e.done > 0 {
                        Button { model.playDownload(e.collection.key) } label: {
                            YoinIcon("play_filled", size: 18).foregroundStyle(tint.primary)
                                .frame(width: 32, height: 32).contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .help("Play")
                    }
                    Button {
                        if case .failed = e.status { model.retryDownload(e) }
                        else { offline.rowTapped(e.status, key: e.collection.key) {} }
                    } label: {
                        YoinIcon(OfflineDownloads.rowIcon(e.status), size: 18).foregroundStyle(Yoin.inkVariant)
                            .frame(width: 32, height: 32).contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .help(OfflineDownloads.rowLabel(e.status))
                }
                .padding(.horizontal, 10).padding(.vertical, 6)
            }
        }
    }

    private func subtitle(_ e: OfflineDownloads.Entry) -> String {
        let state: String = switch e.status {
        case .downloaded: ByteCountFormatter.string(fromByteCount: e.bytes, countStyle: .file)
        case .downloading(let done, let total): "Downloading \(done)/\(total)"
        case .failed: "Some songs failed"
        case .none: ""
        }
        return [e.collection.subtitle, state].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: "  ")
    }
}

/// A playlist in the grid: its cover (a rounded square), name and song count.
struct PlaylistTile: View {
    var item: LibraryItem
    @Environment(MacModel.self) private var model
    @State private var hover = false
    @State private var targeted = false

    private var writable: Bool { MacDrag.accepts(item, model: model) }

    var body: some View {
        Button { model.open(.playlist(id: item.id.rawId, title: item.title)) } label: {
            VStack(alignment: .leading, spacing: 0) {
                MacCover(palette: item.palette)
                    .scaleEffect(hover ? 1.03 : 1)
                Text(item.title).font(.gsf(14, .semibold)).foregroundStyle(Yoin.ink).lineLimit(1).padding(.top, 8)
                Text(item.count.map { "\($0) \($0 == 1 ? "song" : "songs")" } ?? item.subtitle)
                    .font(.gsf(12.5)).foregroundStyle(Yoin.inkVariant).lineLimit(1)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { h in withAnimation(Yoin.spatialFast) { hover = h } }
        .draggable(MacDrag.playlist(item, scope: model.session.accountKey))
        // Songs, albums or playlists dropped here go into it (a writable playlist of the same account).
        .dropDestination(for: YoinDragItem.self) { items, _ in
            guard writable else { return false }
            MacDrag.add(items, toPlaylist: item.id.rawId, model: model)
            return true
        } isTargeted: { t in withAnimation(Yoin.effects) { targeted = t && writable } }
        // The drop target lights up behind the tile, never on its cover (Spotify art carries nothing).
        .background {
            if targeted {
                RoundedRectangle(cornerRadius: Tokens.shape.CARD.cg, style: .continuous)
                    .fill(Yoin.violet.opacity(0.18))
                    .padding(-8)
                    .allowsHitTesting(false)
            }
        }
        .contextMenu {
            Button("Play") { model.play(playlist: item.id.rawId) }
            Button("Shuffle") { model.play(playlist: item.id.rawId, shuffle: true) }
            if model.session.client != nil {
                Divider()
                Button(OfflineDownloads.rowLabel(OfflineDownloads.shared.status(OfflineDownloads.playlistKey(item.id.rawId)))) {
                    model.toggleDownload(playlist: item.id.rawId, title: item.title)
                }
            }
            Divider()
            MacShareMenuItem(content: MacShare.playlist(item))
        }
    }
}

/// Library › Playlists: a new, empty playlist on the account in use.
private struct NewPlaylistButton: View {
    @Environment(MacModel.self) private var model
    @State private var naming = false
    @State private var name = ""
    @State private var error: String?

    var body: some View {
        HStack(spacing: 12) {
            DetailAction(title: "New Playlist", icon: "add", prominent: false) { name = ""; naming = true }
            if let error { Text(error).font(.gsf(13)).foregroundStyle(.red) }
        }
        .alert("New Playlist", isPresented: $naming) {
            TextField("Name", text: $name)
            Button("Create") {
                let n = name.trimmingCharacters(in: .whitespaces)
                guard !n.isEmpty else { return }
                Task {
                    error = nil
                    do {
                        if let id = try await model.library.createPlaylist(name: n) {
                            model.open(.playlist(id: id, title: n))
                        }
                    } catch {
                        self.error = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
                    }
                }
            }
            Button("Cancel", role: .cancel) {}
        }
    }
}

/// Library › Favorites (a Subsonic server's starred; Android FavoritesTabContent): artists, albums, then songs.
private struct MacFavoritesView: View {
    @Environment(MacModel.self) private var model

    var body: some View {
        let lib = model.library
        VStack(alignment: .leading, spacing: 18) {
            if !lib.favoriteArtists.isEmpty {
                MacSectionTitle(text: "Artists")
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 120), spacing: 16, alignment: .top)], spacing: 20) {
                    ForEach(lib.favoriteArtists, id: \.id) { ArtistTile(item: $0) }
                }
            }
            if !lib.favoriteAlbums.isEmpty {
                MacSectionTitle(text: "Albums")
                AlbumGrid(albums: lib.favoriteAlbums, minimum: 124)
            }
            if !lib.favoriteSongs.isEmpty {
                MacSectionTitle(text: "Songs")
                LazyVStack(spacing: 2) {
                    ForEach(Array(lib.favoriteSongs.enumerated()), id: \.offset) { i, e in
                        TrackRow(entry: e, showCover: true) { model.play(lib.favoriteSongs, startAt: i) }
                    }
                }
            }
            PhaseNote(phase: lib.phase("favorites"),
                      empty: lib.favoriteArtists.isEmpty && lib.favoriteAlbums.isEmpty && lib.favoriteSongs.isEmpty,
                      emptyText: "Nothing starred yet") {
                Task { await lib.loadFavorites(force: true) }
            }
        }
    }
}
