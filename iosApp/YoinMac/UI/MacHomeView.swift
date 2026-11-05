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

    @AppStorage("homeLayout") private var layoutRaw = ""
    @State private var editing = false

    @ViewBuilder private func section(_ k: HomeLayout.Section, home: MacLibrary.Home) -> some View {
        switch k {
        case .recentlyPlayed:
            if !home.recentlyPlayed.isEmpty {
                MacSectionTitle(text: k.title)
                Shelf(albums: home.recentlyPlayed)
            }
        case .pickUp:
            if !home.randomSongs.isEmpty {
                MacSectionTitle(text: k.title)
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 260), spacing: 16)], spacing: 4) {
                    ForEach(Array(home.randomSongs.enumerated()), id: \.offset) { i, e in
                        TrackRow(entry: e, showCover: true) { model.play(home.randomSongs, startAt: i) }
                    }
                }
            }
        case .memories:
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
            if !home.random.isEmpty {
                MacSectionTitle(text: k.title)
                AlbumGrid(albums: home.random)
            }
        }
    }

    var body: some View {
        let home = model.library.home
        let layout = HomeLayout(raw: layoutRaw)
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                PageHeader(eyebrow: greeting, title: "Home")
                    .overlay(alignment: .bottomTrailing) {
                        Button { editing = true } label: {
                            Text("Edit").font(.gsf(13.5, .medium)).foregroundStyle(Yoin.inkVariant)
                                .padding(.horizontal, 12).frame(height: 30).contentShape(Capsule())
                        }
                        .buttonStyle(.plain)
                        .popover(isPresented: $editing, arrowEdge: .bottom) { HomeEditor(layout: $layoutRaw) }
                    }
                if home.recentlyPlayed.isEmpty && home.recentlyAdded.isEmpty {
                    PhaseNote(phase: model.library.phase("home"), empty: true, emptyText: "Nothing on this server yet") {
                        Task { await model.library.loadHome(force: true) }
                    }
                }
                ForEach(layout.visible, id: \.self) { section($0, home: home) }
            }
            .padding(.horizontal, 32)
            .padding(.bottom, 130)
        }
        .scrollIndicators(.automatic)
        .task(id: model.session.accountKey) {
            await model.library.loadHome()
            // The song menu's Add to Playlist lists them.
            await model.library.loadPlaylists()
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

/// Home's sections, their order and which are shown (iOS Home editing, kept in the defaults).
struct HomeLayout {
    enum Section: String, CaseIterable, Hashable {
        case recentlyPlayed, pickUp, memories, recentlyAdded, rediscover

        var title: String {
            switch self {
            case .recentlyPlayed: "Recently Played"
            case .pickUp: "Pick Up a Song"
            case .memories: "Memories"
            case .recentlyAdded: "Recently Added"
            case .rediscover: "Rediscover"
            }
        }
    }

    var order: [Section]
    var hidden: Set<Section>

    /// "pickUp,-memories,…": the order, a minus for a hidden one; sections added later go at the end.
    init(raw: String) {
        var order: [Section] = []
        var hidden: Set<Section> = []
        for part in raw.split(separator: ",") {
            let off = part.hasPrefix("-")
            guard let s = Section(rawValue: String(off ? part.dropFirst() : part[...])), !order.contains(s) else { continue }
            order.append(s)
            if off { hidden.insert(s) }
        }
        order += Section.allCases.filter { !order.contains($0) }
        self.order = order
        self.hidden = hidden
    }

    var visible: [Section] { order.filter { !hidden.contains($0) } }
    var raw: String { order.map { (hidden.contains($0) ? "-" : "") + $0.rawValue }.joined(separator: ",") }
}

/// The Edit popover: drag to reorder, switch a section off.
private struct HomeEditor: View {
    @Binding var layout: String

    var body: some View {
        let current = HomeLayout(raw: layout)
        VStack(alignment: .leading, spacing: 8) {
            Text("Home").font(.gsf(15, .semibold))
            List {
                ForEach(current.order, id: \.self) { s in
                    Toggle(s.title, isOn: Binding(
                        get: { !current.hidden.contains(s) },
                        set: { on in
                            var l = current
                            if on { l.hidden.remove(s) } else { l.hidden.insert(s) }
                            layout = l.raw
                        }))
                    .font(.gsf(14))
                }
                .onMove { from, to in
                    var l = current
                    l.order.move(fromOffsets: from, toOffset: to)
                    layout = l.raw
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .frame(height: CGFloat(HomeLayout.Section.allCases.count) * 30 + 8)
            Button("Reset") { layout = "" }.font(.gsf(13)).buttonStyle(.plain).foregroundStyle(Yoin.inkVariant)
        }
        .padding(14)
        .frame(width: 260)
    }
}
