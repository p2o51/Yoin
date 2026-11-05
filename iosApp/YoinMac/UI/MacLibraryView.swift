import SwiftUI

/// Library: a glass filter rail (Albums / Artists / Songs) over the grids.
struct MacLibraryView: View {
    @Environment(MacModel.self) private var model
    @Environment(\.macTint) private var tint

    var body: some View {
        @Bindable var model = model
        let lib = model.library
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                PageHeader(eyebrow: nil, title: "Library")
                Color.clear.frame(height: 64)
                switch model.libraryKind {
                case .albums:
                    AlbumGrid(albums: lib.albums, minimum: 124)
                    PhaseNote(phase: lib.phase("albums"), empty: lib.albums.isEmpty, emptyText: "No albums") {
                        Task { await lib.loadAlbums(force: true) }
                    }
                case .artists:
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 120), spacing: 16, alignment: .top)], spacing: 20) {
                        ForEach(lib.artists, id: \.id) { ArtistTile(item: $0) }
                    }
                    PhaseNote(phase: lib.phase("artists"), empty: lib.artists.isEmpty, emptyText: "No artists") {
                        Task { await lib.loadArtists(force: true) }
                    }
                case .songs:
                    LazyVStack(spacing: 2) {
                        ForEach(Array(lib.songs.enumerated()), id: \.offset) { i, e in
                            TrackRow(entry: e, showCover: true) { model.play(lib.songs, startAt: i) }
                        }
                    }
                    PhaseNote(phase: lib.phase("songs"), empty: lib.songs.isEmpty, emptyText: "No songs") {
                        Task { await lib.loadSongs(force: true) }
                    }
                }
            }
            .padding(.horizontal, 32)
            .padding(.bottom, 130)
        }
        .overlay(alignment: .topLeading) {
            FilterRail(selection: $model.libraryKind)
                .padding(.leading, 32)
                .padding(.top, 108)
        }
        .task(id: model.libraryKind) {
            switch model.libraryKind {
            case .albums: await lib.loadAlbums()
            case .artists: await lib.loadArtists()
            case .songs: await lib.loadSongs()
            }
        }
    }
}

/// The glass segment rail floating over the grid.
struct FilterRail: View {
    @Binding var selection: MacModel.LibraryKind
    @Environment(\.macTint) private var tint
    @Namespace private var ns

    var body: some View {
        HStack(spacing: 2) {
            ForEach(MacModel.LibraryKind.allCases, id: \.self) { k in
                Button { withAnimation(Yoin.spatialFast) { selection = k } } label: {
                    Text(k.rawValue)
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
