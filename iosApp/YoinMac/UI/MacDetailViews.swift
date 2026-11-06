import SwiftUI

/// A detail page, as the right column or pushed full width. One back language: the glass button at the
/// top (✕ closes the column, ‹ goes back to the root), Esc does the same.
struct MacDetailPage: View {
    var detail: MacModel.Detail
    var asColumn: Bool
    @Environment(MacModel.self) private var model

    var body: some View {
        ZStack(alignment: .topLeading) {
            switch detail {
            case .album(let a): MacAlbumDetail(seed: a, leadingInset: asColumn ? 0 : 0)
            case .artist(let id, let name): MacArtistDetail(id: id, name: name)
            case .playlist(let id, let title): MacPlaylistDetail(id: id, title: title)
            }
            GlassCircleButton(icon: asColumn && model.details.count <= 1 ? "close" : "back") { model.closeDetail() }
                .padding(.top, 12)
                // Pushed full width, the button sits after the traffic lights (header breathing).
                .padding(.leading, asColumn ? 8 : 86)
                .help(asColumn ? "Close" : "Back")
        }
    }
}

struct MacAlbumDetail: View {
    var seed: Album
    var leadingInset: CGFloat
    @Environment(MacModel.self) private var model
    @Environment(\.macTint) private var tint

    private var album: Album { model.library.album(seed.id.rawId) ?? seed }
    @State private var rating_ = false
    @State private var page = MacLaunch.screen == "scrapbook" ? 1 : 0

    var body: some View {
        let a = album
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                DetailHeader(palette: a.palette, eyebrow: kindLabel(a), title: a.title, subtitle: a.artist,
                             meta: meta(a)) {
                    if let id = a.artistId { model.open(.artist(id: id, name: a.artist)) }
                }
                HStack(spacing: 10) {
                    DetailAction(title: "Play", icon: "play_filled", prominent: true) { model.play(album: a) }
                    DetailAction(title: "Shuffle", icon: "shuffle", prominent: false) { model.play(album: a, shuffle: true) }
                    if model.session.client != nil {
                        let status = OfflineDownloads.shared.status(OfflineDownloads.albumKey(a.id.rawId))
                        DetailAction(title: OfflineDownloads.rowLabel(status), icon: OfflineDownloads.rowIcon(status),
                                     prominent: false) { model.toggleDownload(album: a) }
                    }
                    let rating = AlbumMemoryStore.shared.album(provider: a.id.provider, albumId: a.id.rawId)?.rating ?? 0
                    DetailAction(title: rating > 0 ? String(format: "%.1f", rating) : "Rate", icon: "star",
                                 prominent: false) { rating_ = true }
                }
                .sheet(isPresented: $rating_) { MacAlbumRateSheet(album: a).environment(\.macTint, tint) }
                .padding(.bottom, 18)
                // The album's two pages (iOS: the pager): its songs, and its scrapbook.
                Picker("", selection: $page.animation(Yoin.spatial)) {
                    Text("Songs").tag(0)
                    Text("Scrapbook").tag(1)
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                .fixedSize()
                .padding(.bottom, 16)
                if page == 1 {
                    MacAlbumScrapbook(album: a) { rating_ = true }
                        .transition(.opacity.combined(with: .move(edge: .trailing)))
                } else {
                let entries = a.tracks.map { PlaylistEntry(album: a, track: $0) }
                let discs = Set(a.tracks.compactMap(\.disc))
                VStack(spacing: 2) {
                    ForEach(Array(entries.enumerated()), id: \.offset) { i, e in
                        if discs.count > 1, e.track.disc != (i > 0 ? entries[i - 1].track.disc : nil) {
                            Text("Disc \(e.track.disc ?? 1)").font(.gsf(13, .semibold)).foregroundStyle(Yoin.inkVariant)
                                .frame(maxWidth: .infinity, alignment: .leading).padding(.top, i == 0 ? 0 : 14).padding(.leading, 10)
                        }
                        TrackRow(entry: e, showCover: false) { model.play(entries, startAt: i) }
                    }
                }
                if a.tracks.isEmpty {
                    PhaseNote(phase: model.library.phase("album:\(a.id.rawId)"), empty: true, emptyText: "No tracks") {
                        Task { await model.library.loadAlbum(a.id.rawId, force: true) }
                    }
                }
                }
            }
            .padding(.horizontal, 32)
            .padding(.top, 60)
            .padding(.bottom, 142)
        }
        .task(id: seed.id) { await model.library.loadAlbum(seed.id.rawId) }
    }

    private func kindLabel(_ a: Album) -> String {
        [a.kind.rawValue, a.year > 0 ? String(a.year) : nil].compactMap { $0 }.joined(separator: "  ")
    }

    private func meta(_ a: Album) -> String {
        let n = a.trackCount
        let mins = a.totalSec / 60
        var parts = ["\(n) \(n == 1 ? "song" : "songs")", "\(mins) min"]
        if let g = a.genre, !g.isEmpty { parts.append(g) }
        return parts.joined(separator: "   ")
    }
}

struct MacArtistDetail: View {
    var id: String
    var name: String
    @Environment(MacModel.self) private var model

    var body: some View {
        let page = model.library.artistPage(id)
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                DetailHeader(palette: page?.palette ?? MacArtistDetail.placeholder(id), eyebrow: "Artist",
                             title: page?.name ?? name, subtitle: nil,
                             meta: page.map { "\($0.releases.count) \($0.releases.count == 1 ? "release" : "releases")" } ?? "",
                             circle: true, onSubtitle: {})
                if let page, let first = page.releases.first {
                    HStack(spacing: 10) {
                        DetailAction(title: "Play", icon: "play_filled", prominent: true) { model.play(album: first) }
                        DetailAction(title: "Shuffle", icon: "shuffle", prominent: false) { model.play(album: first, shuffle: true) }
                    }
                    .padding(.bottom, 8)
                }
                if let page, !page.mostPlayed.isEmpty {
                    MacSectionTitle(text: "Most Played")
                    VStack(spacing: 2) {
                        ForEach(Array(page.mostPlayed.enumerated()), id: \.offset) { i, e in
                            TrackRow(entry: e, showCover: true) { model.play(page.mostPlayed, startAt: i) }
                        }
                    }
                }
                if let page, !page.releases.isEmpty {
                    MacSectionTitle(text: "Releases")
                    AlbumGrid(albums: page.releases, minimum: 124) { a in
                        [a.year > 0 ? String(a.year) : nil, a.kind.rawValue].compactMap { $0 }.joined(separator: "  ")
                    }
                }
                if page == nil {
                    PhaseNote(phase: model.library.phase("artist:\(id)"), empty: true, emptyText: "") {
                        Task { await model.library.loadArtist(id, force: true) }
                    }
                }
            }
            .padding(.horizontal, 32)
            .padding(.top, 60)
            .padding(.bottom, 142)
        }
        .task(id: id) { await model.library.loadArtist(id) }
    }

    static func placeholder(_ key: String) -> CoverPalette {
        let (a, b, c) = SubsonicMapping.placeholder(key)
        return CoverPalette(a: a, b: b, c: c)
    }
}

/// Cover and titles side by side, wrapping under each other in a narrow column.
struct DetailHeader: View {
    var palette: CoverPalette
    var eyebrow: String
    var title: String
    var subtitle: String?
    var meta: String
    var circle = false
    var onSubtitle: () -> Void
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        // Stacked, nothing may be wider than the column: an overflowing header would centre the whole
        // page and push its margins (and the rows' right edge) out of the column.
        ViewThatFits(in: .horizontal) {
            HStack(alignment: .bottom, spacing: 24) { cover.frame(width: 220); titles.frame(minWidth: 220, alignment: .leading) }
            VStack(alignment: .leading, spacing: 18) { cover.frame(maxWidth: 200); titles }
        }
        .padding(.bottom, 22)
    }

    private var cover: some View {
        MacCover(palette: palette, corner: Tokens.shape.HERO.cg, circle: circle)
            .shadow(color: palette.a.opacity(0.35), radius: 20, y: 14)
    }

    private var titles: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(eyebrow).font(.gsf(13)).foregroundStyle(Yoin.inkVariant)
            Text(title).font(.gsf(30, .semibold)).foregroundStyle(Yoin.ink).lineLimit(3)
                .fixedSize(horizontal: false, vertical: true)
            if let subtitle {
                Button(action: onSubtitle) {
                    Text(subtitle).font(.gsf(16, .semibold))
                        .foregroundStyle(MacTint.of(palette.cover?.url, dark: scheme == .dark).primary)
                }
                .buttonStyle(.plain)
            }
            Text(meta).font(.gsf(13)).foregroundStyle(Yoin.inkVariant)
        }
    }
}

/// Play / Shuffle on a detail page.
struct DetailAction: View {
    var title: String
    var icon: String
    var prominent: Bool
    var action: () -> Void
    @Environment(\.macTint) private var tint

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                YoinIcon(icon, size: 18)
                Text(title).font(.gsf(14, .semibold))
            }
            .foregroundStyle(prominent ? tint.onPrimary : Yoin.ink)
            .padding(.horizontal, 18).frame(height: 40)
            .background(Capsule().fill(prominent ? tint.primary : tint.container.opacity(0.7)))
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
    }
}

/// A playlist page: the album page's header and actions over its songs, each with its own cover.
struct MacPlaylistDetail: View {
    var id: String
    var title: String
    @Environment(MacModel.self) private var model

    var body: some View {
        let p = model.library.playlist(id)
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                DetailHeader(palette: p?.palette ?? MacArtistDetail.placeholder(id), eyebrow: "Playlist",
                             title: p?.title ?? title, subtitle: p?.owner.isEmpty == false ? p?.owner : nil,
                             meta: p.map(meta) ?? "") {}
                if let p, !p.entries.isEmpty {
                    HStack(spacing: 10) {
                        DetailAction(title: "Play", icon: "play_filled", prominent: true) { model.play(p.entries) }
                        DetailAction(title: "Shuffle", icon: "shuffle", prominent: false) {
                            model.play(playlist: id, shuffle: true)
                        }
                    }
                    .padding(.bottom, 18)
                    VStack(spacing: 2) {
                        ForEach(Array(p.entries.enumerated()), id: \.offset) { i, e in
                            TrackRow(entry: e, showCover: true,
                                     removeFromPlaylist: model.library.canEdit(p) && !model.library.busyPlaylists.contains(id) ? {
                                         Task { await write { try await model.library.remove(entryAt: i, fromPlaylist: id) } }
                                     } : nil) { model.play(p.entries, startAt: i) }
                        }
                    }
                }
                if let error {
                    Text(error).font(.gsf(13)).foregroundStyle(.red).padding(.top, 10)
                }
                if p?.tracksUnavailable == true {
                    Text("Spotify doesn't hand over the songs of someone else's playlist.")
                        .font(.gsf(14)).foregroundStyle(Yoin.inkVariant).padding(.top, 8)
                } else if p == nil || p?.entries.isEmpty == true {
                    PhaseNote(phase: model.library.phase("playlist:\(id)"), empty: true, emptyText: "No songs") {
                        Task { await model.library.loadPlaylist(id, force: true) }
                    }
                }
            }
            .padding(.horizontal, 32)
            .padding(.top, 60)
            .padding(.bottom, 142)
        }
        .task(id: id) { await model.library.loadPlaylist(id) }
        .overlay(alignment: .topTrailing) {
            if let p, model.library.canManage(p) {
                Menu {
                    Button("Rename…") { newName = p.title; renaming = true }
                    Button(model.session.isSpotify ? "Remove from Library" : "Delete Playlist", role: .destructive) {
                        let gen = model.library.accountGeneration
                        Task {
                            await write { try await model.library.deletePlaylist(id) }
                            // Only this page, and only if it's still the one showing for the same account.
                            if gen == model.library.accountGeneration, case .playlist(id, _)? = model.detail {
                                model.closeDetail()
                            }
                        }
                    }
                } label: {
                    YoinIcon("more_horizontal", size: 18).foregroundStyle(Yoin.ink).frame(width: 34, height: 34)
                }
                .menuStyle(.button).buttonStyle(.plain).menuIndicator(.hidden).fixedSize()
                .glassEffect(.regular.interactive(), in: .circle)
                .padding(.top, 12).padding(.trailing, 16)
            }
        }
        .alert("Rename Playlist", isPresented: $renaming) {
            TextField("Name", text: $newName)
            Button("Rename") {
                let name = newName.trimmingCharacters(in: .whitespaces)
                guard !name.isEmpty else { return }
                Task { await write { try await model.library.renamePlaylist(id, to: name) } }
            }
            Button("Cancel", role: .cancel) {}
        }
    }

    @State private var renaming = false
    @State private var newName = ""
    @State private var error: String?

    private func write(_ body: () async throws -> Void) async {
        error = nil
        do { try await body() } catch { self.error = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription }
    }

    private func meta(_ p: PlaylistInfo) -> String {
        let n = p.entries.count
        let mins = p.entries.reduce(0) { $0 + $1.track.durationSec } / 60
        return "\(n) \(n == 1 ? "song" : "songs")   \(mins) min"
    }
}
