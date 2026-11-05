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
                }
                .padding(.bottom, 18)
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
            .padding(.horizontal, 28)
            .padding(.top, 60)
            .padding(.bottom, 130)
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
            .padding(.horizontal, 28)
            .padding(.top, 60)
            .padding(.bottom, 130)
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
        ViewThatFits(in: .horizontal) {
            HStack(alignment: .bottom, spacing: 24) { cover.frame(width: 220); titles }
            VStack(alignment: .leading, spacing: 18) { cover.frame(width: 200); titles }
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
        .frame(minWidth: 220, alignment: .leading)
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
