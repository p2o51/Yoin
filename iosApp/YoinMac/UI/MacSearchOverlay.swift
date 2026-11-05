import SwiftUI

/// ⌘K: a glass search box over a dimmed window; albums, artists and songs from search3.
struct MacSearchOverlay: View {
    @Environment(MacModel.self) private var model
    @State private var query = ""
    @State private var hits = MacLibrary.SearchHits()
    @State private var failed: String?
    @FocusState private var focused: Bool

    var body: some View {
        ZStack(alignment: .top) {
            Rectangle().fill(.black.opacity(0.18)).ignoresSafeArea()
                .onTapGesture { model.back() }
            VStack(spacing: 0) {
                TextField("Search albums, artists, songs", text: $query)
                    .textFieldStyle(.plain)
                    .font(.gsf(16))
                    .focused($focused)
                    .padding(.horizontal, 18)
                    .frame(height: 46)
                    .background(Capsule().fill(Yoin.surface.opacity(0.6)))
                    .onSubmit { playFirstSong() }
                if !query.isEmpty {
                    ScrollView {
                        results.padding(.top, 8).padding(.horizontal, 4)
                    }
                    .frame(maxHeight: 400)
                }
            }
            .padding(10)
            .frame(width: 620)
            .glassEffect(.regular, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
            .padding(.top, 56)
        }
        .onAppear { focused = true }
        .task(id: query) {
            try? await Task.sleep(for: .milliseconds(220))
            guard !Task.isCancelled else { return }
            do {
                let r = try await model.library.search(query)
                guard !Task.isCancelled else { return }
                hits = r
                failed = nil
            } catch {
                failed = MacSession.message(for: error)
            }
        }
    }

    @ViewBuilder private var results: some View {
        VStack(alignment: .leading, spacing: 2) {
            if let failed { Text(failed).font(.gsf(13)).foregroundStyle(Yoin.inkVariant).padding(8) }
            if hits.isEmpty && failed == nil {
                Text("No results").font(.gsf(13)).foregroundStyle(Yoin.inkVariant).padding(8)
            }
            if !hits.artists.isEmpty {
                heading("ARTISTS")
                ForEach(hits.artists, id: \.id) { a in
                    row(palette: a.palette, circle: true, title: a.title, subtitle: "Artist") {
                        model.searchOpen = false
                        model.open(.artist(id: a.id.rawId, name: a.title))
                    }
                }
            }
            if !hits.albums.isEmpty {
                heading("ALBUMS")
                ForEach(hits.albums, id: \.id) { a in
                    row(palette: a.palette, circle: false, title: a.title, subtitle: a.artist) {
                        model.searchOpen = false
                        model.open(.album(a))
                    }
                }
            }
            if !hits.songs.isEmpty {
                heading("SONGS")
                ForEach(Array(hits.songs.enumerated()), id: \.offset) { i, e in
                    row(palette: e.album.palette, circle: false, title: e.track.title,
                        subtitle: e.track.artist ?? e.album.artist) {
                        model.play(hits.songs, startAt: i)
                    }
                    .contextMenu { SongMenu(entry: e) { model.play(hits.songs, startAt: i) } }
                }
            }
        }
    }

    private func heading(_ t: String) -> some View {
        Text(t).font(.gsf(12, .semibold)).foregroundStyle(Yoin.inkVariant).tracking(0.5)
            .padding(.horizontal, 8).padding(.top, 10).padding(.bottom, 4)
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
