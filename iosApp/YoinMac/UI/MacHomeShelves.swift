import SwiftUI

struct MacActivityBento: View {
    var entries: [MacHomeActivity]

    private var hero: MacHomeActivity? {
        entries.first {
            switch $0.content { case .album, .playlist: true; default: false }
        }
    }

    var body: some View {
        ViewThatFits(in: .horizontal) {
            if let hero {
                HStack(alignment: .top, spacing: 20) {
                    MacActivityTile(entry: hero).frame(width: 220)
                    // The rest at ordinary tile size beside the hero (never stretched to the column).
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 132, maximum: 168), spacing: 16, alignment: .top)],
                              alignment: .leading, spacing: 16) {
                        ForEach(entries.filter { $0.id != hero.id }.prefix(8)) { MacActivityTile(entry: $0) }
                    }
                    .frame(minWidth: 280, alignment: .topLeading)
                }
                .frame(minWidth: 520)
            }
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 132, maximum: 168), alignment: .top)], alignment: .leading,
                      spacing: 20) {
                ForEach(entries) { MacActivityTile(entry: $0) }
            }
        }
        .animation(Yoin.spatial, value: entries)
    }
}

private struct MacActivityTile: View {
    var entry: MacHomeActivity

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            switch entry.content {
            case .album(let album): AlbumTile(album: album)
            case .artist(let artist): ArtistTile(item: artist)
            case .playlist(let playlist): PlaylistTile(item: playlist)
            default: EmptyView()
            }
            if entry.at > 0 {
                Text(HomeHistory.timeAgo(entry.at, now: HistoryStore.shared.nowMs))
                    .font(.gsf(11.5)).foregroundStyle(Yoin.inkVariant)
            }
        }
    }
}

struct MacJumpBackInGrid: View {
    var entries: [MacHomeContent]

    var body: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 260), alignment: .top)], alignment: .leading, spacing: 16) {
            ForEach(entries) { MacHomeEntryCard(content: $0) }
        }
        .animation(Yoin.spatial, value: entries)
    }
}

private struct MacHomeEntryCard: View {
    var content: MacHomeContent
    @Environment(MacModel.self) private var model
    @State private var hover = false
    @State private var memory: MemoryCard?

    private var label: (title: String, subtitle: String, kind: String, icon: String, palette: CoverPalette) {
        switch content {
        case .album(let a): (a.title, a.artist, "Album", "album", a.palette)
        case .artist(let a): (a.title, a.subtitle, "Artist", "artist", a.palette)
        // The kind line already says Playlist: the count alone (no "Playlist · n songs").
        case .playlist(let p): (p.title, p.count.map { "\($0) \($0 == 1 ? "song" : "songs")" } ?? "", "Playlist", "playlist",
                                p.palette)
        case .song(let s): (s.track.title, s.track.artist ?? s.album.artist, "Song", "music_note", s.album.palette)
        case .signal(let s): (s.title, s.subtitle, s.kind == .ratedAlbum ? "Memory" : "Note", "album", s.album.palette)
        }
    }

    var body: some View {
        let label = self.label
        Button(action: open) {
            HStack(alignment: .top, spacing: 14) {
                // A circle is an artist; everything else is a rounded square.
                MacCover(palette: label.palette, circle: { if case .artist = content { return true }; return false }())
                    .frame(width: 76, height: 76)
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 5) {
                        YoinIcon(label.icon, size: 12)
                        Text(label.kind).font(.gsf(11.5))
                    }
                    .foregroundStyle(Yoin.inkVariant)
                    Text(label.title).font(.gsf(14, .semibold)).foregroundStyle(Yoin.ink).lineLimit(2)
                    Text(label.subtitle).font(.gsf(12.5)).foregroundStyle(Yoin.inkVariant).lineLimit(1)
                    if case .signal(let signal) = content {
                        Text(signal.score).font(.gsf(19, .medium).monospacedDigit()).foregroundStyle(Yoin.ink)
                        if let basis = signal.basis {
                            Text(basis).font(.gsf(11.5)).foregroundStyle(Yoin.inkVariant).lineLimit(1)
                        }
                        if let comment = signal.comment {
                            Text(comment).font(.gsf(13)).foregroundStyle(Yoin.ink).lineLimit(3)
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(12)
            .frame(maxWidth: .infinity, minHeight: 100, alignment: .topLeading)
            .background(Yoin.surfaceContainerHigh.opacity(hover ? 0.8 : 0.45),
                        in: RoundedRectangle(cornerRadius: Tokens.shape.CARD.cg))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { value in withAnimation(Yoin.effects) { hover = value } }
        .sheet(item: $memory) { MacMemoryDiary(card: $0) }
    }

    private func open() {
        switch content {
        case .album(let a): model.open(.album(a))
        case .artist(let a): model.open(.artist(id: a.id.rawId, name: a.title))
        case .playlist(let p): model.open(.playlist(id: p.id.rawId, title: p.title))
        case .song(let s): model.play([s])
        case .signal(let signal):
            if let song = signal.song { model.play([song]) }
            else if let card = model.memoryCards().first(where: { $0.album.id == signal.album.id }) { memory = card }
            else { model.open(.album(signal.album)) }
        }
    }
}

struct MacRecentlyAddedShelf: View {
    var albums: [Album]
    var songs: [PlaylistEntry]
    @Environment(MacModel.self) private var model

    var body: some View {
        ScrollView(.horizontal) {
            HStack(alignment: .top, spacing: 20) {
                if !songs.isEmpty {
                    LazyHGrid(rows: [GridItem(.fixed(76)), GridItem(.fixed(76))], spacing: 16) {
                        ForEach(Array(songs.enumerated()), id: \.element.track.id) { index, entry in
                            TrackRow(entry: entry, showCover: true) { model.play(songs, startAt: index) }
                                .frame(width: 260)
                        }
                    }
                    .frame(height: 160)
                }
                ForEach(albums) { AlbumTile(album: $0).frame(width: 150) }
            }
        }
        .contentMargins(.horizontal, 32, for: .scrollContent)
        .scrollIndicators(.never)
        .padding(.horizontal, -32)
    }
}

struct MacOtherAccountsShelf: View {
    var entries: [MacOtherAccountItem]
    @Environment(MacModel.self) private var model
    @AppStorage("homeOtherProfilesSwitchNoticeSeen") private var noticeSeen = false
    @State private var pending: MacOtherAccountItem?

    var body: some View {
        ScrollView(.horizontal) {
            LazyHStack(alignment: .top, spacing: 16) {
                ForEach(entries) { item in
                    Button {
                        if noticeSeen { model.play(otherAccount: item) }
                        else { pending = item }
                    } label: {
                        VStack(alignment: .leading, spacing: 6) {
                            MacCover(palette: item.palette)
                            Text(item.record.title).font(.gsf(14, .semibold)).foregroundStyle(Yoin.ink).lineLimit(1)
                            Text(item.record.subtitle).font(.gsf(12.5)).foregroundStyle(Yoin.inkVariant).lineLimit(1)
                            HStack(spacing: 5) {
                                YoinIcon(item.record.entity == .playlist ? "playlist" : "album", size: 12)
                                Text(item.profileName).font(.gsf(11.5)).lineLimit(1)
                            }
                            .foregroundStyle(Yoin.inkVariant)
                        }
                        .frame(width: 150)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .help("Switch and play")
                }
            }
        }
        .scrollIndicators(.never)
        .animation(Yoin.spatial, value: entries)
        .confirmationDialog("Switch to \(pending?.profileName ?? "")?", isPresented: Binding(
            get: { pending != nil }, set: { if !$0 { pending = nil } }), titleVisibility: .visible) {
                if let item = pending {
                    Button("Switch and play") {
                        noticeSeen = true
                        pending = nil
                        model.play(otherAccount: item)
                    }
                }
                Button("Cancel", role: .cancel) { pending = nil }
            } message: {
                Text("To play \(pending?.record.title ?? ""), Yoin switches to that account first. What's playing now stops.")
            }
    }
}

struct MacHomeLoadNote: View {
    var key: String
    var label: String
    @Environment(MacModel.self) private var model

    var body: some View {
        switch model.library.phase(key) {
        case .loading:
            HStack(spacing: 8) {
                ProgressView().controlSize(.small)
                Text(label).font(.gsf(12.5)).foregroundStyle(Yoin.inkVariant)
            }
            .padding(.vertical, 8)
            .transition(.opacity)
        case .failed(let message):
            HStack(spacing: 10) {
                Text(label).font(.gsf(12.5, .medium))
                Text(message).font(.gsf(12.5)).foregroundStyle(Yoin.inkVariant)
                Button("Retry") { Task { await model.library.loadHome(force: true) } }
                    .font(.gsf(12.5)).buttonStyle(.plain)
            }
            .padding(.vertical, 8)
            .transition(.opacity)
        default: EmptyView()
        }
    }
}
