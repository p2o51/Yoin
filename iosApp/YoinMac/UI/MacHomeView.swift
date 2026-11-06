import SwiftUI

/// Home: greeting and title, then the server's lists (Recently Played, Recently Added, a random shelf
/// and a few random songs). Simpler than the phone's Home for P0.
struct MacHomeView: View {
    @Environment(MacModel.self) private var model

    private var greeting: String {
        switch Calendar.current.component(.hour, from: .now) {
        case 5..<12: "Good morning"
        case 12..<18: "Good afternoon"
        default: "Good evening"
        }
    }

    @State private var editing = false

    /// Whose Home this is (iOS `homeScope`, provider|account): the layout is kept, and synced, per account.
    private var scope: String { "\(model.currentProvider)|\(model.session.accountKey)" }

    /// One iOS Home section, drawn from what the Mac has.
    @ViewBuilder private func section(_ k: HomeSection, home: MacLibrary.Home) -> some View {
        switch k {
        case .recentlyPlayed:
            if !home.recentlyPlayed.isEmpty {
                MacSectionTitle(text: k.title)
                Shelf(albums: home.recentlyPlayed)
            }
        case .jumpBackIn:
            if !home.randomSongs.isEmpty {
                MacSectionTitle(text: k.title)
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 260), spacing: 16)], spacing: 4) {
                    ForEach(Array(home.randomSongs.enumerated()), id: \.offset) { i, e in
                        TrackRow(entry: e, showCover: true) { model.play(home.randomSongs, startAt: i) }
                    }
                }
            }
        case .activities:
            let memories = model.memories()
            if !memories.isEmpty {
                MacSectionTitle(text: k.title)
                // What you've played on this Mac: how often, and when last.
                AlbumGrid(albums: memories.map(\.album)) { a in
                    guard let s = memories.first(where: { $0.album.id == a.id })?.stats else { return a.artist }
                    let when = Date(timeIntervalSince1970: Double(s.lastPlayedAt) / 1000)
                        .formatted(.relative(presentation: .named))
                    return "\(s.playCount) \(s.playCount == 1 ? "play" : "plays")  \(when)"
                }
            }
        case .recentlyAdded:
            if !home.recentlyAdded.isEmpty {
                MacSectionTitle(text: k.title)
                AlbumGrid(albums: home.recentlyAdded)
            }
        case .rediscover:
            // iOS's rule: albums you rated or wrote about and haven't played in a while; else the server's
            // random shelf.
            let inputs = model.historyInputs()
            let picks = HomeHistory.rediscover(HomeHistory.memories(inputs), now: inputs.now, exclude: [])
            let albums = picks.isEmpty ? home.random : picks.map(\.album)
            if !albums.isEmpty {
                MacSectionTitle(text: k.title)
                AlbumGrid(albums: albums) { a in
                    picks.first(where: { $0.album.id == a.id }).map { $0.reason.isEmpty ? a.artist : $0.reason } ?? a.artist
                }
            }
        case .yourPlaylists:
            let lists = model.library.playlists
            if !lists.isEmpty {
                MacSectionTitle(text: k.title)
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 132), spacing: 16, alignment: .top)], spacing: 20) {
                    ForEach(lists.prefix(12), id: \.id) { PlaylistTile(item: $0) }
                }
            }
        case .otherProfiles:
            EmptyView()
        }
    }

    var body: some View {
        let home = model.library.home
        let layout = HomeLayoutStore.shared.layout(scope)
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                PageHeader(eyebrow: greeting, title: "Home")
                    .overlay(alignment: .bottomTrailing) {
                        Button { editing = true } label: {
                            Text("Edit").font(.gsf(13.5, .medium)).foregroundStyle(Yoin.inkVariant)
                                .padding(.horizontal, 12).frame(height: 30).contentShape(Capsule())
                        }
                        .buttonStyle(.plain)
                        .popover(isPresented: $editing, arrowEdge: .bottom) { HomeEditor(scope: scope) }
                    }
                if home.recentlyPlayed.isEmpty && home.recentlyAdded.isEmpty {
                    PhaseNote(phase: model.library.phase("home"), empty: true, emptyText: "Nothing on this server yet") {
                        Task { await model.library.loadHome(force: true) }
                    }
                }
                ForEach(layout.shownSections, id: \.self) { section($0, home: home) }
            }
            .padding(.horizontal, 32)
            .padding(.bottom, 142)
        }
        .scrollIndicators(.automatic)
        .task(id: model.session.accountKey) {
            await model.library.loadHome()
            // The song menu's Add to Playlist lists them.
            await model.library.loadPlaylists()
            await model.library.loadWritablePlaylists()
        }
    }
}

/// The big page title, clear of the traffic lights.
struct PageHeader: View {
    var eyebrow: String?
    var title: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            if let eyebrow { Text(eyebrow).font(.gsf(16)).foregroundStyle(Yoin.inkVariant) }
            Text(title).font(.gsf(30, .semibold)).foregroundStyle(Yoin.ink)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.top, 58)
        .padding(.bottom, 4)
    }
}

/// Albums in an adaptive grid.
struct AlbumGrid: View {
    var albums: [Album]
    var minimum: CGFloat = 132
    var subtitle: ((Album) -> String)?

    var body: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: minimum), spacing: 16, alignment: .top)], spacing: 20) {
            ForEach(albums, id: \.id) { a in AlbumTile(album: a, subtitle: subtitle?(a)) }
        }
    }
}

/// One horizontal row of covers, full bleed to the page edges (no mid-page cut).
struct Shelf: View {
    var albums: [Album]

    var body: some View {
        ScrollView(.horizontal) {
            LazyHStack(alignment: .top, spacing: 16) {
                ForEach(albums, id: \.id) { a in AlbumTile(album: a).frame(width: 150) }
            }
            .scrollTargetLayout()
        }
        .contentMargins(.horizontal, 32, for: .scrollContent)
        .scrollIndicators(.never)
        .padding(.horizontal, -32)
        .mask(
            LinearGradient(stops: [.init(color: .clear, location: 0), .init(color: .black, location: 0.025),
                                   .init(color: .black, location: 0.975), .init(color: .clear, location: 1)],
                           startPoint: .leading, endPoint: .trailing)
                .padding(.horizontal, -32)
        )
    }
}

/// The Edit popover (iOS Home editing on HomeLayoutStore, synced): drag to reorder, switch a section off.
private struct HomeEditor: View {
    var scope: String

    var body: some View {
        let store = HomeLayoutStore.shared
        let layout = store.layout(scope)
        let rows = layout.sections.filter(\.section.supportedOnIOS)
        VStack(alignment: .leading, spacing: 8) {
            Text("Home").font(.gsf(15, .semibold))
            List {
                ForEach(rows, id: \.section) { (r: HomeSectionState) in
                    Toggle(isOn: Binding<Bool>(
                        get: { r.enabled },
                        set: { on in store.save(store.layout(scope).withEnabled(r.section, on), scope: scope) })) {
                        VStack(alignment: .leading, spacing: 1) {
                            Text(r.section.title).font(.gsf(14))
                            Text(r.section.supportingText).font(.gsf(11.5)).foregroundStyle(Yoin.inkVariant)
                        }
                    }
                }
                .onMove { from, to in
                    var order = rows.map(\.section)
                    order.move(fromOffsets: from, toOffset: to)
                    let current = store.layout(scope)
                    let enabled = order.filter { s in current.enabledSections.contains(s) }
                    store.save(current.withEnabledOrder(enabled), scope: scope)
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .frame(height: CGFloat(rows.count) * 44 + 8)
            Button("Reset") { store.save(store.layout(scope).reset(), scope: scope) }
                .font(.gsf(13)).buttonStyle(.plain).foregroundStyle(Yoin.inkVariant)
        }
        .padding(14)
        .frame(width: 300)
    }
}
