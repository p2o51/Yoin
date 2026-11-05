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
                case .playlists:
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 124), spacing: 16, alignment: .top)], spacing: 20) {
                        ForEach(lib.playlists, id: \.id) { PlaylistTile(item: $0) }
                    }
                    PhaseNote(phase: lib.phase("playlists"), empty: lib.playlists.isEmpty, emptyText: "No playlists") {
                        Task { await lib.loadPlaylists(force: true) }
                    }
                case .downloads:
                    MacDownloadsList()
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
        .task(id: "\(model.libraryKind.rawValue)|\(model.session.accountKey)") {
            switch model.libraryKind {
            case .albums: await lib.loadAlbums()
            case .artists: await lib.loadArtists()
            case .songs: await lib.loadSongs()
            case .playlists: await lib.loadPlaylists()
            case .downloads: break
            }
        }
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
            ForEach(MacModel.LibraryKind.allCases.filter { $0 != .downloads || model.session.client != nil },
                    id: \.self) { k in
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
        .contextMenu {
            Button("Play") { model.play(playlist: item.id.rawId) }
            Button("Shuffle") { model.play(playlist: item.id.rawId, shuffle: true) }
        }
    }
}
