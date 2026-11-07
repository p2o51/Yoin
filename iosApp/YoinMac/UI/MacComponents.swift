import SwiftUI

/// Where a detail page's big title's bottom edge is, in the page (one object per page, so a scroll only
/// redraws what reads it).
@MainActor
@Observable
final class MacDetailTitle {
    var bottom: CGFloat = .greatestFiniteMagnitude
}

extension EnvironmentValues {
    /// Inside a detail page: what it opens goes on top of it (a back stack), not in its place.
    @Entry var inDetailPage = false
    /// A list mixing albums, artists, playlists and songs (51, 2026-11-07, Spotify's way): each line says its
    /// kind first — "Album · artist", "Playlist · 8 songs", "Song · artist", "Artist".
    @Entry var mixedKinds = false
    /// A detail page's big title reports how far down its bottom edge is (in the page), so the page's top bar can
    /// take the title over as it scrolls away.
    @Entry var detailTitle: MacDetailTitle? = nil
    /// 0 → 1 as the page's top bar takes the title over (the big title steps back with it).
    @Entry var detailTitleCollapse: CGFloat = 0
    /// The playing cover's colour roles (Yoin violet before anything plays).
    @Entry var macTint = MacTint(primary: Yoin.violet, onPrimary: .white, container: Yoin.surfaceContainerHigh,
                                 onContainer: Yoin.ink)
}

/// A cover: the server image (CoverArtwork), its placeholder colour until it lands. Solid, no border.
struct MacCover: View {
    var palette: CoverPalette
    var corner: CGFloat = Tokens.shape.COVER.cg
    var circle = false

    var body: some View {
        Group {
            if let ref = palette.cover {
                RemoteCover(ref: ref, placeholder: palette.a)
            } else {
                Rectangle().fill(palette.a)
            }
        }
        .aspectRatio(1, contentMode: .fit)
        .clipShape(circle ? AnyShape(Circle()) : AnyShape(RoundedRectangle(cornerRadius: corner, style: .continuous)))
    }
}

/// A grid album: plain cover, title and artist below. Hover lifts it a little.
struct AlbumTile: View {
    var album: Album
    var subtitle: String?
    @Environment(MacModel.self) private var model
    @Environment(\.inDetailPage) private var inDetail
    @Environment(\.mixedKinds) private var mixed
    @State private var hover = false

    var body: some View {
        Button { model.open(.album(album), push: inDetail) } label: {
            VStack(alignment: .leading, spacing: 0) {
                MacCover(palette: album.palette)
                    .scaleEffect(hover ? 1.03 : 1)
                    .shadow(color: album.palette.a.opacity(hover ? 0.35 : 0), radius: 12, y: 8)
                Text(album.title)
                    .font(.gsf(14, .semibold))
                    .foregroundStyle(Yoin.ink)
                    .lineLimit(1)
                    .padding(.top, 8)
                Text(subtitle ?? (mixed ? "Album · \(album.artist)" : album.artist))
                    .font(.gsf(12.5))
                    .foregroundStyle(Yoin.inkVariant)
                    .lineLimit(1)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { h in withAnimation(Yoin.spatialFast) { hover = h } }
        .draggable(MacDrag.album(album, scope: model.session.accountKey))
        .contextMenu {
            Button("Play") { model.play(album: album) }
            Button("Shuffle") { model.play(album: album, shuffle: true) }
            Button("Play Next") { model.queue(album: album, next: true) }
            Button("Add to Queue") { model.queue(album: album, next: false) }
            if model.session.client != nil {
                Divider()
                Button(OfflineDownloads.rowLabel(OfflineDownloads.shared.status(OfflineDownloads.albumKey(album.id.rawId)))) {
                    model.toggleDownload(album: album)
                }
            }
            if let id = album.artistId {
                Divider()
                Button("Go to Artist") { model.open(.artist(id: id, name: album.artist), push: inDetail) }
            }
            Divider()
            MacShareMenuItem(content: MacShare.album(album))
        }
    }
}

/// A round artist cell.
struct ArtistTile: View {
    var item: LibraryItem
    @Environment(MacModel.self) private var model
    @Environment(\.inDetailPage) private var inDetail
    @State private var hover = false

    @Environment(\.mixedKinds) private var mixed

    var body: some View {
        Button { model.open(.artist(id: item.id.rawId, name: item.title), push: inDetail) } label: {
            VStack(spacing: 8) {
                MacCover(palette: item.palette, circle: true)
                    .scaleEffect(hover ? 1.03 : 1)
                Text(item.title).font(.gsf(14, .semibold)).foregroundStyle(Yoin.ink).lineLimit(1)
                if mixed {
                    Text("Artist").font(.gsf(12.5)).foregroundStyle(Yoin.inkVariant)
                } else if let n = item.count {
                    Text(n == 1 ? "1 album" : "\(n) albums").font(.gsf(12.5)).foregroundStyle(Yoin.inkVariant)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { h in withAnimation(Yoin.spatialFast) { hover = h } }
    }
}

/// A track row: single click selects, double click plays, hover shows the play glyph and the heart,
/// right click opens the song menu.
struct TrackRow: View {
    var entry: PlaylistEntry
    var showCover: Bool
    /// On an editable playlist's page: the song menu's Remove from Playlist.
    var removeFromPlaylist: (() -> Void)? = nil
    var play: () -> Void
    @Environment(MacModel.self) private var model
    @Environment(\.macTint) private var tint
    @State private var hover = false

    private var id: String { entry.track.id.rawId }
    private var isCurrent: Bool { model.player.currentTrack?.id == id }
    private var selected: Bool { model.selectedTrack == id }
    private var starred: Bool { model.library.isStarred(entry.track) }

    var body: some View {
        HStack(spacing: 12) {
            if showCover {
                MacCover(palette: entry.album.palette, corner: Tokens.shape.THUMB.cg)
                    .frame(width: 40, height: 40)
                    .overlay {
                        // Spotify's artwork may carry nothing drawn on it (its guidelines).
                        if (isCurrent || hover), CoverMarks.onCover(provider: entry.track.id.provider),
                           entry.album.palette.cover?.isSpotify != true {
                            ZStack {
                                RoundedRectangle(cornerRadius: Tokens.shape.THUMB.cg).fill(.black.opacity(0.35))
                                if isCurrent { EqualizerBars(playing: model.player.isPlaying, color: .white) }
                                else { YoinIcon("play_filled", size: 16).foregroundStyle(.white) }
                            }
                        }
                    }
            } else {
                ZStack {
                    if isCurrent {
                        EqualizerBars(playing: model.player.isPlaying, color: tint.primary)
                    } else if hover {
                        YoinIcon("play_filled", size: 16).foregroundStyle(tint.primary)
                    } else {
                        Text("\(entry.track.number)").font(.gsf(13).monospacedDigit()).foregroundStyle(Yoin.ink3)
                    }
                }
                .frame(width: 24, alignment: .trailing)
            }
            VStack(alignment: .leading, spacing: 1) {
                Text(entry.track.title)
                    .font(.gsf(14, isCurrent ? .semibold : .regular))
                    .foregroundStyle(isCurrent ? tint.primary : Yoin.ink)
                    .lineLimit(1)
                if showCover || entry.track.artist != nil {
                    Text(showCover ? entry.track.artist ?? entry.album.artist : entry.track.artist ?? "")
                        .font(.gsf(12.5)).foregroundStyle(Yoin.inkVariant).lineLimit(1)
                }
            }
            Spacer(minLength: 8)
            Button { model.library.toggleStar(songId: id, current: starred) } label: {
                YoinIcon(starred ? "favorite_filled" : "favorite", size: 18)
                    .foregroundStyle(starred ? tint.primary : Yoin.ink3)
            }
            .buttonStyle(.plain)
            .opacity(hover || starred ? 1 : 0)
            if OfflineDownloads.shared.isDownloaded(songId: id) {
                YoinIcon("download_done", size: 15).foregroundStyle(Yoin.ink3).help("Downloaded")
            }
            Text(formatTime(entry.track.durationSec))
                .font(.gsf(13).monospacedDigit()).foregroundStyle(Yoin.ink3)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, showCover ? 6 : 9)
        .background(
            RoundedRectangle(cornerRadius: Tokens.shape.LIST_ROW.cg, style: .continuous)
                .fill(tint.primary.opacity(selected ? 0.16 : hover ? 0.08 : 0))
        )
        .contentShape(Rectangle())
        .onHover { hover = $0 }
        .draggable(MacDrag.song(entry, scope: model.session.accountKey))
        .onTapGesture(count: 2) { play() }
        .onTapGesture { model.selectedTrack = id }
        .contextMenu {
            SongMenu(entry: entry, play: play)
            if let removeFromPlaylist {
                Divider()
                Button("Remove from Playlist", role: .destructive, action: removeFromPlaylist)
            }
        }
        .animation(Yoin.effects, value: hover)
        .animation(Yoin.effects, value: selected)
    }
}

/// The song menu (the phone's long-press sheet): Play, Play next, Add to queue, Like, Go to album / artist.
struct SongMenu: View {
    var entry: PlaylistEntry
    var play: () -> Void
    @Environment(MacModel.self) private var model
    @Environment(\.inDetailPage) private var inDetail

    var body: some View {
        let starred = model.library.isStarred(entry.track)
        let id = entry.track.id.rawId
        let rating = model.library.rating(entry.track) ?? 0
        // The phone's long-press sheet, in its order: queue, like, rate, then where to go.
        Button("Play") { play() }
        Button("Play Next") { model.playNext(entry) }
        Button("Add to Queue") { model.addToQueue(entry) }
        Divider()
        if model.session.isAppleMusic {
            // Apple Music's heart is the library, which a song can join but not leave from here.
            Button(starred ? "In Library" : "Add to Library") { model.library.toggleStar(songId: id, current: starred) }
                .disabled(starred)
        } else {
            Button(starred ? "Unlike" : "Like") { model.library.toggleStar(songId: id, current: starred) }
        }
        rateMenu(id: id, rating: rating)
        if model.session.client != nil || model.session.isSpotify {
            Menu("Add to Playlist") {
                ForEach(model.library.writablePlaylists, id: \.id) { p in
                    Button(p.title) {
                        Task { try? await model.library.add(songIds: [id], toPlaylist: p.id.rawId) }
                    }
                }
                if model.library.writablePlaylists.isEmpty { Text("No playlists you can add to") }
            }
        }
        if model.session.client != nil {
            Button(OfflineDownloads.rowLabel(OfflineDownloads.shared.status(OfflineDownloads.songKey(id)))) {
                model.toggleDownload(song: entry)
            }
        }
        Divider()
        if !entry.album.id.rawId.hasPrefix("song:") {
            Button("Go to Album") { model.open(.album(entry.album), push: inDetail) }
        }
        if let id = entry.album.artistId {
            Button("Go to Artist") { model.open(.artist(id: id, name: entry.album.artist), push: inDetail) }
        }
        Divider()
        MacShareMenuItem(content: MacShare.song(entry.track, album: entry.album))
    }

    @ViewBuilder private func rateMenu(id: String, rating: Double) -> some View {
        Menu("Rate") {
            ForEach((1...5).reversed(), id: \.self) { stars in
                Toggle(String(format: "%.1f", Double(stars * 2)), isOn: Binding(
                    get: { Int((rating / 2).rounded()) == stars },
                    set: { on in model.library.setRating(provider: entry.track.id.provider, songId: id,
                                                         on ? Double(stars * 2) : nil) }))
            }
            Divider()
            Button("Clear Rating") { model.library.setRating(provider: entry.track.id.provider, songId: id, nil) }
                .disabled(rating == 0)
        }
    }
}

/// Three bars that keep moving while the song plays (Yoin's motion rule: playing things never freeze).
struct EqualizerBars: View {
    var playing: Bool
    var color: Color

    var body: some View {
        TimelineView(.animation(minimumInterval: 1 / 30, paused: !playing)) { ctx in
            let t = ctx.date.timeIntervalSinceReferenceDate
            HStack(alignment: .bottom, spacing: 2) {
                ForEach(0..<3, id: \.self) { i in
                    let phase = t * 6.3 + Double(i) * 2.1
                    Capsule().fill(color)
                        .frame(width: 3, height: playing ? 3 + 9 * (0.5 + 0.5 * sin(phase)) : 3)
                }
            }
            .frame(height: 12, alignment: .bottom)
        }
    }
}

/// A round glass control floating over content.
struct GlassCircleButton: View {
    var icon: String
    var size: CGFloat = 34
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            YoinIcon(icon, size: size * 0.52)
                .foregroundStyle(Yoin.ink)
                .frame(width: size, height: size)
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .glassEffect(.regular.interactive(), in: .circle)
    }
}

/// Section heading on Home and the detail pages.
struct MacSectionTitle: View {
    var text: String
    var body: some View {
        Text(text).font(.gsf(19, .semibold)).foregroundStyle(Yoin.ink)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, 26).padding(.bottom, 12)
    }
}

/// Loading / failure / empty, centred in a page.
struct PhaseNote: View {
    var phase: MacLibrary.Phase
    var empty: Bool
    var emptyText: String
    var retry: () -> Void

    var body: some View {
        Group {
            switch phase {
            case .loading where empty, .idle where empty:
                ProgressView().controlSize(.small)
            case .failed(let m) where empty:
                VStack(spacing: 10) {
                    Text(m).font(.gsf(14)).foregroundStyle(Yoin.inkVariant)
                    Button("Try Again", action: retry)
                }
            case .loaded where empty:
                Text(emptyText).font(.gsf(14)).foregroundStyle(Yoin.inkVariant)
            default:
                EmptyView()
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 40)
    }
}

/// One line that scrolls when it doesn't fit (Android MarqueeText): 1.2 s before it starts, about 30 pt/s, a
/// copy following a third of the width behind, 1.8 s between loops, 18 pt fades at both clipped edges. A line
/// that fits stands still, with no fades.
struct MacMarqueeText: View {
    var text: String
    var font: Font
    var color: Color
    @State private var contentWidth: CGFloat = 0
    @State private var containerWidth: CGFloat = 0
    @State private var offset: CGFloat = 0

    private var overflows: Bool { containerWidth > 0 && contentWidth > containerWidth + 0.5 }
    private var gap: CGFloat { containerWidth / 3 }

    var body: some View {
        // The slot: one line's height and whatever width it's given (its own text never widens it).
        Text(text).font(font).lineLimit(1)
            .hidden()
            .frame(maxWidth: .infinity, alignment: .leading)
            .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { containerWidth = $0 }
            .overlay(alignment: .leading) {
                HStack(spacing: gap) {
                    label
                    if overflows { label }
                }
                .fixedSize()
                .offset(x: -offset)
            }
            .clipped()
            .mask { fades }
            .background {
                label.fixedSize().hidden()
                    .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { contentWidth = $0 }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(text)
            .task(id: "\(text)|\(Int(contentWidth))|\(Int(containerWidth))") { await run() }
    }

    private var label: some View {
        Text(text).font(font).foregroundStyle(color).lineLimit(1).fixedSize()
    }

    @ViewBuilder private var fades: some View {
        if overflows {
            HStack(spacing: 0) {
                LinearGradient(colors: [.clear, .black], startPoint: .leading, endPoint: .trailing).frame(width: 18)
                Color.black
                LinearGradient(colors: [.black, .clear], startPoint: .leading, endPoint: .trailing).frame(width: 18)
            }
        } else {
            Color.black
        }
    }

    /// Runs while the line overflows (Yoin's rule: a playing marquee never freezes); a new text starts over.
    private func run() async {
        offset = 0
        guard overflows else { return }
        try? await Task.sleep(for: .milliseconds(1200))
        while !Task.isCancelled {
            let distance = contentWidth + gap
            let seconds = Double(distance) / 30
            withAnimation(.linear(duration: seconds)) { offset = distance }
            try? await Task.sleep(for: .seconds(seconds))
            guard !Task.isCancelled else { return }
            // The copy now stands where the line began: back without a jump.
            offset = 0
            try? await Task.sleep(for: .milliseconds(1800))
        }
    }
}
