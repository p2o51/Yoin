import SwiftUI

/// ⌘K: a glass search box over a dimmed window (51, 11-06: the panel is a hero). As Android's Library search:
/// the account's library or the service's catalog (Spotify / Apple Music), a type filter (All, Artists,
/// Albums, Songs, Playlists), each type's further pages, a loading line and Retry. The query, filter and scope
/// stay for the next ⌘K (opening a result and coming back finds them where they were).
struct MacSearchOverlay: View {
    @Environment(MacModel.self) private var model
    @State private var hits = MacLibrary.SearchHits()
    /// The search [hits] answer: a page only goes on hits of the same query, filter and scope.
    @State private var hitsKey: String?
    @State private var failed: String?
    @State private var loading = false
    @State private var loadingMore = false
    @State private var retry = 0
    @FocusState private var focused: Bool

    private var searchKey: String {
        "\(model.searchQuery)|\(model.searchKind?.rawValue ?? "all")|\(model.searchScope.rawValue)|\(model.session.accountKey)|\(retry)"
    }

    var body: some View {
        @Bindable var model = model
        ZStack(alignment: .top) {
            Rectangle().fill(.black.opacity(0.18)).ignoresSafeArea()
                .onTapGesture { model.back() }
            VStack(alignment: .leading, spacing: 0) {
                TextField(model.library.hasCatalog && model.searchScope == .library
                          ? "Search your library" : "Search albums, artists, songs, playlists",
                          text: $model.searchQuery)
                    .textFieldStyle(.plain)
                    .font(.gsf(16))
                    .focused($focused)
                    .padding(.horizontal, 18)
                    .frame(height: 46)
                    .background(Capsule().fill(Yoin.surface.opacity(0.6)))
                    .onSubmit { playFirstSong() }
                filters.padding(.top, 8).padding(.horizontal, 4)
                if !model.searchQuery.trimmingCharacters(in: .whitespaces).isEmpty {
                    ScrollView {
                        results.padding(.top, 4).padding(.horizontal, 4)
                    }
                    .frame(maxHeight: 420)
                }
            }
            .padding(10)
            .frame(width: 640)
            .glassEffect(.regular, in: RoundedRectangle(cornerRadius: 24, style: .continuous)) // 51, 11-06: ⌘K panel is a hero, glass allowed
            .padding(.top, 56)
        }
        .onAppear { focused = true }
        .task(id: searchKey) {
            let q = model.searchQuery
            let key = searchKey
            guard !q.trimmingCharacters(in: .whitespaces).isEmpty else { hits = .init(); hitsKey = key; failed = nil; return }
            try? await Task.sleep(for: .milliseconds(220))
            guard !Task.isCancelled else { return }
            loading = true
            defer { loading = false }
            do {
                let kinds = model.searchKind.map { [$0] } ?? SearchKind.allCases
                let r = try await model.library.search(q, scope: model.searchScope, kinds: kinds)
                guard !Task.isCancelled else { return }
                hits = r
                hitsKey = key
                failed = nil
            } catch is CancellationError {
            } catch {
                guard !Task.isCancelled else { return }
                failed = MacSession.message(for: error)
            }
        }
    }

    // MARK: Filters

    private var kindsOffered: [SearchKind] {
        // Subsonic servers search no playlists.
        model.library.hasCatalog ? [.artists, .albums, .songs, .playlists] : [.artists, .albums, .songs]
    }

    private var filters: some View {
        HStack(spacing: 6) {
            chip("All", on: model.searchKind == nil) { model.searchKind = nil }
            ForEach(kindsOffered, id: \.self) { k in
                chip(Self.label(k), on: model.searchKind == k) { model.searchKind = model.searchKind == k ? nil : k }
            }
            Spacer(minLength: 8)
            if model.library.hasCatalog {
                Picker("", selection: Binding(get: { model.searchScope }, set: { model.searchScope = $0 })) {
                    Text("Library").tag(MacLibrary.SearchScope.library)
                    Text(model.session.isSpotify ? "Spotify" : "Apple Music").tag(MacLibrary.SearchScope.catalog)
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                .fixedSize()
            }
        }
    }

    private func chip(_ title: String, on: Bool, _ action: @escaping () -> Void) -> some View {
        Button(action: { withAnimation(Yoin.effects) { action() } }) {
            Text(title).font(.gsf(12.5, on ? .semibold : .regular)).lineLimit(1).fixedSize()
                .foregroundStyle(on ? Yoin.surface : Yoin.ink)
                .padding(.horizontal, 12).frame(height: 28)
                .background(Capsule().fill(on ? Yoin.ink : Yoin.surface.opacity(0.55)))
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
    }

    static func label(_ k: SearchKind) -> String {
        switch k {
        case .artists: "Artists"
        case .albums: "Albums"
        case .songs: "Songs"
        case .playlists: "Playlists"
        }
    }

    // MARK: Results

    @ViewBuilder private var results: some View {
        VStack(alignment: .leading, spacing: 2) {
            if let failed {
                HStack {
                    Text(failed).font(.gsf(13)).foregroundStyle(Yoin.inkVariant).lineLimit(2)
                    Spacer()
                    Button("Retry") { retry += 1 }.buttonStyle(.link)
                }
                .padding(8)
            } else if loading && hits.isEmpty {
                HStack(spacing: 8) { ProgressView().controlSize(.small); Text("Searching…").font(.gsf(13)) }
                    .foregroundStyle(Yoin.inkVariant).padding(8)
            } else if hits.isEmpty && !loading {
                Text("No results").font(.gsf(13)).foregroundStyle(Yoin.inkVariant).padding(8)
            }
            let only = model.searchKind
            if only == nil || only == .artists, !hits.artists.isEmpty {
                section(.artists, count: hits.artists.count)
                ForEach(capped(hits.artists, .artists), id: \.id) { a in
                    row(palette: a.palette, circle: true, title: a.title, subtitle: "Artist") {
                        model.searchOpen = false
                        model.open(.artist(id: a.id.rawId, name: a.title))
                    }
                }
            }
            if only == nil || only == .albums, !hits.albums.isEmpty {
                section(.albums, count: hits.albums.count)
                ForEach(capped(hits.albums, .albums), id: \.id) { a in
                    row(palette: a.palette, circle: false, title: a.title, subtitle: a.artist) {
                        model.searchOpen = false
                        model.open(.album(a))
                    }
                }
            }
            if only == nil || only == .songs, !hits.songs.isEmpty {
                section(.songs, count: hits.songs.count)
                let shown = capped(hits.songs, .songs)
                ForEach(Array(shown.enumerated()), id: \.offset) { i, e in
                    row(palette: e.album.palette, circle: false, title: e.track.title,
                        subtitle: e.track.artist ?? e.album.artist) {
                        model.play(hits.songs, startAt: i)
                    }
                    .contextMenu { SongMenu(entry: e) { model.play(hits.songs, startAt: i) } }
                }
            }
            if only == nil || only == .playlists, !hits.playlists.isEmpty {
                section(.playlists, count: hits.playlists.count)
                ForEach(capped(hits.playlists, .playlists), id: \.id) { p in
                    row(palette: p.palette, circle: false, title: p.title, subtitle: p.owner ?? "Playlist") {
                        model.searchOpen = false
                        model.open(.playlist(id: p.id.rawId, title: p.title))
                    }
                }
            }
            // The filtered type's next page.
            if let only, hits.more.contains(only), hitsKey == searchKey, !loading {
                HStack {
                    Spacer()
                    if loadingMore { ProgressView().controlSize(.small) }
                    else { Button("Load More") { Task { await loadMore(only) } }.buttonStyle(.link) }
                    Spacer()
                }
                .padding(.vertical, 8)
            }
        }
    }

    /// "All" shows a few of each; a type's own filter shows them all.
    private func capped<T>(_ list: [T], _ kind: SearchKind) -> ArraySlice<T> {
        model.searchKind == nil ? list.prefix(kind == .songs ? 8 : 5) : list[...]
    }

    private func section(_ kind: SearchKind, count: Int) -> some View {
        HStack {
            Text(Self.label(kind).uppercased()).font(.gsf(12, .semibold)).foregroundStyle(Yoin.inkVariant).tracking(0.5)
            Spacer()
            if model.searchKind == nil, count > (kind == .songs ? 8 : 5) || hits.more.contains(kind) {
                Button("Show All") { withAnimation(Yoin.effects) { model.searchKind = kind } }
                    .buttonStyle(.link).font(.gsf(12))
            }
        }
        .padding(.horizontal, 8).padding(.top, 10).padding(.bottom, 4)
    }

    private func loadMore(_ kind: SearchKind) async {
        let key = searchKey
        guard !loadingMore, !loading, hitsKey == key else { return }
        loadingMore = true
        defer { loadingMore = false }
        do {
            let page = try await model.library.search(model.searchQuery, scope: model.searchScope, kinds: [kind],
                                                      offset: hits.next[kind] ?? 0)
            guard key == searchKey else { return }
            hits.append(page)
        } catch {
            guard key == searchKey else { return }
            failed = MacSession.message(for: error)
        }
    }

    private func row(palette: CoverPalette, circle: Bool, title: String, subtitle: String,
                     action: @escaping () -> Void) -> some View {
        SearchRow(palette: palette, circle: circle, title: title, subtitle: subtitle, action: action)
    }

    private func playFirstSong() {
        if !hits.songs.isEmpty { model.play(hits.songs, startAt: 0) }
        else if let a = hits.albums.first { model.searchOpen = false; model.open(.album(a)) }
    }
}

private struct SearchRow: View {
    var palette: CoverPalette
    var circle: Bool
    var title: String
    var subtitle: String
    var action: () -> Void
    @Environment(\.macTint) private var tint
    @State private var hover = false

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                MacCover(palette: palette, corner: Tokens.shape.THUMB.cg, circle: circle).frame(width: 40, height: 40)
                VStack(alignment: .leading, spacing: 1) {
                    Text(title).font(.gsf(14)).foregroundStyle(Yoin.ink).lineLimit(1)
                    Text(subtitle).font(.gsf(12.5)).foregroundStyle(Yoin.inkVariant).lineLimit(1)
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 8).padding(.vertical, 5)
            .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(tint.primary.opacity(hover ? 0.1 : 0)))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { hover = $0 }
    }
}
