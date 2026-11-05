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

    var body: some View {
        let home = model.library.home
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                PageHeader(eyebrow: greeting, title: "Home")
                if home.recentlyPlayed.isEmpty && home.recentlyAdded.isEmpty {
                    PhaseNote(phase: model.library.phase("home"), empty: true, emptyText: "Nothing on this server yet") {
                        Task { await model.library.loadHome(force: true) }
                    }
                }
                if !home.recentlyPlayed.isEmpty {
                    MacSectionTitle(text: "Recently Played")
                    Shelf(albums: home.recentlyPlayed)
                }
                if !home.randomSongs.isEmpty {
                    MacSectionTitle(text: "Pick Up a Song")
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 260), spacing: 16)], spacing: 4) {
                        ForEach(Array(home.randomSongs.enumerated()), id: \.offset) { i, e in
                            TrackRow(entry: e, showCover: true) { model.play(home.randomSongs, startAt: i) }
                        }
                    }
                }
                if !home.recentlyAdded.isEmpty {
                    MacSectionTitle(text: "Recently Added")
                    AlbumGrid(albums: home.recentlyAdded)
                }
                if !home.random.isEmpty {
                    MacSectionTitle(text: "Rediscover")
                    AlbumGrid(albums: home.random)
                }
            }
            .padding(.horizontal, 32)
            .padding(.bottom, 130)
        }
        .scrollIndicators(.automatic)
        .task {
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
