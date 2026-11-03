import SwiftUI

struct HomeView: View {
    @Environment(AppModel.self) private var model
    @State private var width: CGFloat = 370

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 30) {
                header
                activities
                jumpBackIn
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
            .onGeometryChange(for: CGFloat.self) { $0.size.width - 32 } action: { width = $0 }
        }
        .contentMargins(.bottom, 110, for: .scrollContent)
        .scrollEdgeEffectStyle(.soft, for: .all)
        .background(Yoin.surface)
    }

    // MARK: Header — two-line greeting

    private var greeting: String {
        switch Calendar.current.component(.hour, from: .now) {
        case 5..<12: "Good morning"
        case 12..<18: "Good afternoon"
        default: "Good evening"
        }
    }

    private var header: some View {
        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: 2) {
                // Android HomeGreetingTitle: both lines in the rounded cut (ROND 100, Medium).
                Text(greeting)
                    .font(YoinFont.rounded(16, relativeTo: .subheadline))
                    .foregroundStyle(Yoin.inkVariant)
                Text("Home")
                    .font(YoinFont.rounded(30, relativeTo: .largeTitle))
                    .foregroundStyle(Yoin.ink)
            }
            Spacer()
            Button { model.showLanding = true } label: {
                Text("5")
                    .font(.gsf(16, .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 38, height: 38)
                    .background(Yoin.violet, in: CookieShape(lobes: 9, depth: 0.1))
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Account")
        }
        .padding(.top, 6)
    }

    // MARK: Activities

    private var activities: some View {
        let items = model.library.activities()
        return VStack(alignment: .leading, spacing: 12) {
            SectionTitle("Activities")
            ActivityHeroCard(entry: items[0])
            HStack(spacing: 10) {
                ActivitySmallCard(entry: items[1])
                    .frame(width: (width - 10) * 0.4)
                ActivityWideCard(entry: items[2])
            }
            .frame(height: 150)
        }
    }

    // MARK: Jump Back In — 3 columns, one tall 1×2 signal card

    private var jumpBackIn: some View {
        let albums = model.library.jumpBackIn()
        let col = (width - 20) / 3
        let cellH = col + 40
        return VStack(alignment: .leading, spacing: 12) {
            SectionTitle("Jump Back In")
            HStack(alignment: .top, spacing: 10) {
                RatedAlbumCard(album: albums[0])
                    .frame(width: col, height: cellH * 2 + 10)
                VStack(spacing: 10) {
                    HStack(spacing: 10) {
                        JBICell(album: albums[1], side: col)
                        JBICell(album: albums[2], side: col)
                    }
                    HStack(spacing: 10) {
                        JBICell(album: albums[3], side: col)
                        JBICell(album: albums[4], side: col)
                    }
                }
            }
            ForEach(0..<2, id: \.self) { row in
                HStack(alignment: .top, spacing: 10) {
                    ForEach(0..<3, id: \.self) { i in
                        JBICell(album: albums[5 + row * 3 + i], side: col)
                    }
                }
            }
        }
    }
}

struct SectionTitle: View {
    var text: String
    init(_ text: String) { self.text = text }
    var body: some View {
        Text(text)
            .font(.gsf(22, .medium))
            .foregroundStyle(Yoin.ink)
    }
}

// MARK: - Cards (solid Yoin content, no glass)

private struct ActivityHeroCard: View {
    @Environment(AppModel.self) private var model
    @Environment(\.zoomNamespace) private var zoomNS
    var entry: ActivityEntry

    var body: some View {
        let al = entry.album
        Button { model.open(al) } label: {
            HStack(spacing: 16) {
                CoverArt(palette: al.palette, cornerRadius: 8, seed: al.seed)
                    .frame(width: 140, height: 140)
                    .zoomSource(al.id, zoomNS)
                VStack(alignment: .leading, spacing: 6) {
                    Text(entry.eyebrow)
                        .font(.gsf(13, .medium))
                        .foregroundStyle(al.palette.onContainer.opacity(0.8))
                    Text(al.title)
                        .font(.gsf(22, .semibold))
                        .foregroundStyle(al.palette.onContainer)
                        .lineLimit(2)
                    Text(al.artist)
                        .font(.gsf(15))
                        .foregroundStyle(al.palette.onContainer.opacity(0.8))
                    Spacer(minLength: 0)
                    HStack {
                        Spacer()
                        YoinIcon("play_filled", size: 22)
                            .foregroundStyle(.white)
                            .frame(width: 48, height: 48)
                            .background(al.palette.tint, in: .circle)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(14)
            .frame(height: 168)
            .background(al.palette.container, in: .rect(cornerRadius: 28))
        }
        .buttonStyle(PressScale())
    }
}

private struct ActivitySmallCard: View {
    @Environment(AppModel.self) private var model
    var entry: ActivityEntry

    var body: some View {
        let al = entry.album
        Button { model.open(al) } label: {
            VStack(alignment: .leading, spacing: 8) {
                CoverArt(palette: al.palette, cornerRadius: 200, seed: al.seed)
                    .frame(width: 64, height: 64)
                Spacer(minLength: 0)
                Text(al.artist)
                    .font(.gsf(16, .semibold))
                    .foregroundStyle(al.palette.onContainer)
                    .lineLimit(1)
                Text(entry.eyebrow)
                    .font(.gsf(13))
                    .foregroundStyle(al.palette.onContainer.opacity(0.75))
            }
            .padding(14)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
            .background(al.palette.container, in: .rect(cornerRadius: 24))
        }
        .buttonStyle(PressScale())
    }
}

private struct ActivityWideCard: View {
    @Environment(AppModel.self) private var model
    @Environment(\.zoomNamespace) private var zoomNS
    var entry: ActivityEntry

    var body: some View {
        let al = entry.album
        Button { model.open(al) } label: {
            VStack(alignment: .leading, spacing: 8) {
                CoverArt(palette: al.palette, cornerRadius: 6, seed: al.seed)
                    .frame(width: 64, height: 64)
                    .zoomSource(al.id, zoomNS)
                Spacer(minLength: 0)
                Text(al.title)
                    .font(.gsf(16, .semibold))
                    .foregroundStyle(al.palette.onContainer)
                    .lineLimit(1)
                Text("\(entry.eyebrow) · \(al.artist)")
                    .font(.gsf(13))
                    .foregroundStyle(al.palette.onContainer.opacity(0.75))
                    .lineLimit(1)
            }
            .padding(14)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
            .background(al.palette.container, in: .rect(cornerRadius: 24))
        }
        .buttonStyle(PressScale())
    }
}

private struct RatedAlbumCard: View {
    @Environment(AppModel.self) private var model
    @Environment(\.zoomNamespace) private var zoomNS
    var album: Album

    var body: some View {
        Button { model.open(album) } label: {
            VStack(alignment: .leading, spacing: 8) {
                CoverArt(palette: album.palette, cornerRadius: 6, seed: album.seed)
                    .aspectRatio(1, contentMode: .fit)
                    .zoomSource(album.id, zoomNS)
                    .overlay(alignment: .bottomTrailing) {
                        CookieBadge(score: album.rating ?? 0, palette: album.palette, size: 46, rings: 0)
                            .offset(x: 6, y: 12)
                    }
                Spacer(minLength: 0)
                Text("Rated")
                    .font(.gsf(12, .medium))
                    .foregroundStyle(album.palette.onContainer.opacity(0.75))
                Text(album.title)
                    .font(.gsf(15, .semibold))
                    .foregroundStyle(album.palette.onContainer)
                    .lineLimit(3)
                Text(album.artist)
                    .font(.gsf(12))
                    .foregroundStyle(album.palette.onContainer.opacity(0.75))
                    .lineLimit(1)
            }
            .padding(10)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .background(album.palette.container, in: .rect(cornerRadius: 20))
        }
        .buttonStyle(PressScale())
    }
}

struct JBICell: View {
    @Environment(AppModel.self) private var model
    @Environment(\.zoomNamespace) private var zoomNS
    var album: Album
    var side: CGFloat

    var body: some View {
        Button { model.open(album) } label: {
            VStack(alignment: .leading, spacing: 4) {
                CoverArt(palette: album.palette, cornerRadius: 6, seed: album.seed)
                    .frame(width: side, height: side)
                    .zoomSource(album.id, zoomNS)
                Text(album.title)
                    .font(.gsf(13, .medium))
                    .foregroundStyle(Yoin.ink)
                Text(album.artist)
                    .font(.gsf(12))
                    .foregroundStyle(Yoin.inkVariant)
            }
            .lineLimit(1)
            .frame(width: side, alignment: .leading)
        }
        .buttonStyle(PressScale())
    }
}

/// Spring press feedback for solid content.
struct PressScale: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.96 : 1)
            .animation(Yoin.spatialFast, value: configuration.isPressed)
    }
}

/// Scalloped cookie rating badge; optional rings = one groove per track.
struct CookieBadge: View {
    var score: Double
    var palette: CoverPalette
    var size: CGFloat = 84
    var rings: Int = 0
    var ratedRings: Int = 0
    /// Album rating (wght 690) vs. track average (640), as GrooveType.
    var albumRating = true

    var body: some View {
        ZStack {
            CookieShape()
                .fill(palette.tint)
            if rings > 0 {
                ForEach(0..<rings, id: \.self) { i in
                    let f = 0.42 + 0.46 * CGFloat(i) / CGFloat(max(rings - 1, 1))
                    Circle()
                        .stroke(.white.opacity(i < ratedRings ? 0.28 : 0.12),
                                style: StrokeStyle(lineWidth: 0.8, dash: i < ratedRings ? [] : [2, 3]))
                        .frame(width: size * f, height: size * f)
                }
            }
            let label = String(format: "%.1f", score)
            Text(label)
                .font(YoinFont.score(label, size: size * 0.32, albumRating: albumRating))
                .foregroundStyle(.white)
        }
        .frame(width: size, height: size)
        .accessibilityLabel(String(format: "Rating %.1f", score))
    }
}
