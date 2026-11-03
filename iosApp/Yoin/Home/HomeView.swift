import SwiftUI

/// Home, ported from Android's phone layout (HomeEditorialContent.kt,
/// HomeWidgetGrid.kt, RediscoverSection.kt, HomeGreetingTitle.kt) at feed
/// density N = 2 (content width < 448). Numbers are cited in
/// iosApp/docs/fidelity-home-np.md.
struct HomeView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var colorScheme
    @State private var contentWidth: CGFloat = 370

    /// App-wide tokens follow the playing cover (design.md 颜色系统 3).
    private var scheme: CoverScheme { CoverScheme(seed: model.accent, dark: colorScheme == .dark) }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                header
                activities
                jumpBackIn
                recentlyAdded
                rediscover
            }
            .padding(.horizontal, 16)
            .onGeometryChange(for: CGFloat.self) { $0.size.width - 32 } action: { contentWidth = $0 }
        }
        .contentMargins(.bottom, 108, for: .scrollContent)
        // `-screen home-bottom` / `home-tile-bottom` open scrolled to the end (screenshots).
        .defaultScrollAnchor(LaunchConfig.screen.hasSuffix("-bottom") ? .bottom : .top)
        .scrollEdgeEffectStyle(.soft, for: .all)
        .background(
            LinearGradient(colors: [scheme.surfaceContainer, scheme.background, scheme.surfaceContainerLow],
                           startPoint: .top, endPoint: .bottom)
            .ignoresSafeArea()
        )
        .environment(\.homeScheme, scheme)
    }

    // MARK: Header (HomeGreetingTitle.kt)

    private var greeting: String {
        switch Calendar.current.component(.hour, from: .now) {
        case 5..<11: "Good morning"
        case 11..<18: "Good afternoon"
        default: "Good evening"
        }
    }

    private var header: some View {
        HStack(alignment: .center, spacing: 2) {
            VStack(alignment: .leading, spacing: 0) {
                // titleMedium 16/24 and headlineMedium 28/36, both GSF Rounded Medium (ROND 100).
                Text(greeting)
                    .font(YoinFont.rounded(16, relativeTo: .subheadline))
                    .foregroundStyle(scheme.onSurfaceVariant)
                    .frame(height: 24)
                Text("Home")
                    .font(YoinFont.rounded(28, relativeTo: .largeTitle))
                    .foregroundStyle(scheme.onSurface)
                    .frame(height: 36)
            }
            .lineLimit(1)
            .padding(.trailing, 24)
            Spacer(minLength: 0)
            Button { model.showLanding = true } label: {
                // 32 dp avatar in a 48 dp target; profile 0 = Cookie6Sided.
                Text("5")
                    .font(.gsf(14, .semibold))
                    .foregroundStyle(scheme.onPrimary)
                    .frame(width: 32, height: 32)
                    .background(BackdropColors(model.playback.album.palette).accent.mix(with: scheme.primary, by: 0.5),
                                in: CookieShape(lobes: 6, depth: 0.16))
                    .padding(1.5)
                    .background(scheme.surfaceContainer, in: CookieShape(lobes: 6, depth: 0.16))
                    .frame(width: 48, height: 48)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Account")
        }
        .frame(minHeight: 48)
        .padding(.top, 12)
    }

    // MARK: Activities (bento, phone preset L)

    private var activities: some View {
        let a = model.library.albums()
        let small = contentWidth > 0 ? (contentWidth - 10) / 3 : 120
        return VStack(alignment: .leading, spacing: 10) {
            HomeSectionTitle("Activities").padding(.bottom, 6)
            HeroCard(album: a[0], timeAgo: "12m ago")
            HStack(spacing: 10) {
                SmallCard(album: a[3], entity: .artist, typeLabel: "Artist", timeAgo: "2h ago", title: a[3].artist)
                    .frame(width: small)
                WideCard(album: a[1], timeAgo: "1d ago")
            }
            .frame(height: 118)
            StripCard(title: a[11].title, subtitle: a[11].artist, timeAgo: "3d ago")
        }
    }

    // MARK: Jump Back In (3 columns, two 1×2 signal cards)

    private var jumpBackIn: some View {
        let a = model.library.jumpBackIn()
        let col = (contentWidth - 24) / 3
        return VStack(alignment: .leading, spacing: 16) {
            HomeSectionTitle("Jump Back In")
            VStack(alignment: .leading, spacing: 16) {
                HStack(alignment: .top, spacing: 12) {
                    SignalCard(album: a[0], kind: .ratedAlbum).frame(width: col * 2 + 12, alignment: .leading)
                    JBICell(album: a[1], mark: .solid).frame(width: col, alignment: .leading)
                }
                HStack(alignment: .top, spacing: 12) {
                    JBICell(album: a[2], mark: nil).frame(width: col, alignment: .leading)
                    SignalCard(album: a[5], kind: .notedTrack).frame(width: col * 2 + 12, alignment: .leading)
                }
                ForEach(0..<2, id: \.self) { row in
                    HStack(alignment: .top, spacing: 12) {
                        ForEach(0..<3, id: \.self) { i in
                            let idx = 3 + row * 3 + i
                            JBICell(album: a[idx], mark: idx == 4 ? .outline : idx == 7 ? .dashed : nil)
                                .frame(width: col, alignment: .leading)
                        }
                    }
                }
            }
        }
    }

    // MARK: Recently Added (2×2 track grid, then album cards)

    private var recentlyAdded: some View {
        let a = model.library.albums()
        let gridWidth = min(340, max(226.8, (contentWidth - 14) * 2.6 / 3.6))
        return VStack(alignment: .leading, spacing: 12) {
            HomeSectionTitle("Recently Added")
            ScrollView(.horizontal) {
                HStack(alignment: .top, spacing: 14) {
                    VStack(alignment: .leading, spacing: 14) {
                        ForEach(0..<2, id: \.self) { r in
                            HStack(spacing: 8) {
                                ForEach(0..<2, id: \.self) { c in
                                    let al = a[[9, 6, 2, 8][r * 2 + c]]
                                    TrackTile(album: al, track: al.tracks[r + c])
                                        .frame(width: (gridWidth - 8) / 2, alignment: .leading)
                                }
                            }
                        }
                    }
                    .frame(width: gridWidth)
                    ForEach([10, 4, 7, 5, 2, 8], id: \.self) { i in
                        AlbumMini(album: a[i])
                    }
                }
                .padding(.horizontal, 16)
            }
            .scrollIndicators(.hidden)
            .padding(.horizontal, -16)
        }
    }

    // MARK: Rediscover (peeking shelf)

    private var rediscover: some View {
        let a = model.library.albums()
        return VStack(alignment: .leading, spacing: 12) {
            HomeSectionTitle("Rediscover")
            ScrollView(.horizontal) {
                HStack(spacing: 12) {
                    RediscoverCard(album: a[6], reason: "Reviewed", away: "7 months away", score: 8.8,
                                   footnote: ("2020.11", "14"))
                        .frame(width: contentWidth * 0.86)
                    RediscoverCard(album: a[8], reason: "3 notes", away: "1 year away", score: nil,
                                   footnote: ("2019.04", "9"))
                        .frame(width: contentWidth * 0.86)
                }
                .padding(.horizontal, 16)
            }
            .scrollIndicators(.hidden)
            .padding(.horizontal, -16)
        }
    }
}

// MARK: - Scheme plumbing

private struct HomeSchemeKey: EnvironmentKey {
    static let defaultValue: CoverScheme? = nil
}

extension EnvironmentValues {
    var homeScheme: CoverScheme? {
        get { self[HomeSchemeKey.self] }
        set { self[HomeSchemeKey.self] = newValue }
    }
}

/// Android card wash: container = lerp(surfaceContainerLow, backdrop base, 0.30).
func cardWash(_ scheme: CoverScheme, _ palette: CoverPalette) -> Color {
    scheme.surfaceContainerLow.mix(with: BackdropColors(palette).base, by: 0.30, in: .perceptual)
}

/// Section title: titleLarge SemiBold at 18 sp (line height 28).
struct HomeSectionTitle: View {
    @Environment(\.homeScheme) private var scheme
    var text: String
    init(_ text: String) { self.text = text }
    var body: some View {
        Text(text)
            .font(.gsf(18, .semibold))
            .foregroundStyle(scheme?.onSurface ?? Yoin.ink)
            .frame(height: 28, alignment: .leading)
    }
}

/// Android MetaLine: groups 12 dp apart, number Medium + 4 dp + unit at 60 %.
private struct MetaLine: View {
    var groups: [(String, String?)]
    var color: Color
    var body: some View {
        HStack(spacing: 12) {
            ForEach(Array(groups.enumerated()), id: \.offset) { _, g in
                HStack(spacing: 4) {
                    Text(g.0).font(.gsf(11, .medium)).monospacedDigit()
                    if let unit = g.1 { Text(unit).font(.gsf(11, .medium)).opacity(0.6) }
                }
            }
        }
        .foregroundStyle(color)
        .lineLimit(1)
    }
}

// MARK: - Activities cards

private struct HeroCard: View {
    @Environment(AppModel.self) private var model
    @Environment(\.homeScheme) private var scheme
    @Environment(\.zoomNamespace) private var zoomNS
    var album: Album
    var timeAgo: String

    var body: some View {
        let s = scheme!
        let muted = s.onSurfaceVariant
        Button { model.open(album) } label: {
            HStack(spacing: 14) {
                EntityBackdrop(entity: .album, palette: album.palette, seed: album.seed, size: 104, stroke: s.primary)
                    .zoomSource(album.id, zoomNS)
                VStack(alignment: .leading, spacing: 3) {
                    Text(timeAgo).font(.gsf(11, .medium)).foregroundStyle(muted)
                    Text(album.title).font(.gsf(22, .semibold)).foregroundStyle(s.onSurface)
                    Text(album.artist).font(.gsf(12)).foregroundStyle(muted)
                    MetaLine(groups: [("\(album.year)", nil), ("\(album.tracks.count)", "songs"), ("48", "min")],
                             color: muted)
                }
                .lineLimit(1)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(14)
            .background(cardWash(s, album.palette), in: .rect(cornerRadius: 16, style: .continuous))
        }
        .buttonStyle(PressScale())
    }
}

private struct SmallCard: View {
    @Environment(AppModel.self) private var model
    @Environment(\.homeScheme) private var scheme
    var album: Album
    var entity: EntityBackdrop.Entity
    var typeLabel: String
    var timeAgo: String
    var title: String

    var body: some View {
        let s = scheme!
        Button { model.open(album) } label: {
            VStack(alignment: .leading, spacing: 4) {
                HStack(alignment: .top, spacing: 10) {
                    EntityBackdrop(entity: entity, palette: album.palette, seed: album.seed, size: 52, stroke: s.primary)
                    VStack(alignment: .leading, spacing: 0) {
                        Text(typeLabel)
                        Text(timeAgo)
                    }
                    .font(.gsf(11, .medium))
                    .foregroundStyle(s.onSurfaceVariant)
                    .lineLimit(1)
                }
                Spacer(minLength: 0)
                Text(title)
                    .font(.gsf(14, .semibold))
                    .lineSpacing(0)
                    .foregroundStyle(s.onSurface)
                    .lineLimit(2)
            }
            .padding(10)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
            .background(cardWash(s, album.palette), in: .rect(cornerRadius: 16, style: .continuous))
        }
        .buttonStyle(PressScale())
    }
}

private struct WideCard: View {
    @Environment(AppModel.self) private var model
    @Environment(\.homeScheme) private var scheme
    @Environment(\.zoomNamespace) private var zoomNS
    var album: Album
    var timeAgo: String

    var body: some View {
        let s = scheme!
        Button { model.open(album) } label: {
            HStack(spacing: 12) {
                EntityBackdrop(entity: .album, palette: album.palette, seed: album.seed, size: 88, stroke: s.primary)
                    .zoomSource(album.id, zoomNS)
                VStack(alignment: .leading, spacing: 2) {
                    Text(timeAgo).font(.gsf(11, .medium)).foregroundStyle(s.onSurfaceVariant)
                    Text(album.title).font(.gsf(16, .semibold)).foregroundStyle(s.onSurface)
                    Text(album.artist).font(.gsf(12)).foregroundStyle(s.onSurfaceVariant)
                }
                .lineLimit(1)
                Spacer(minLength: 0)
            }
            .padding(12)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(cardWash(s, album.palette), in: .rect(cornerRadius: 16, style: .continuous))
        }
        .buttonStyle(PressScale())
    }
}

private struct StripCard: View {
    @Environment(\.homeScheme) private var scheme
    var title: String
    var subtitle: String
    var timeAgo: String

    var body: some View {
        let s = scheme!
        HStack(spacing: 10) {
            HStack(spacing: 12) {
                Text(title).font(.gsf(14, .semibold)).foregroundStyle(s.onSurface)
                Text(subtitle).font(.gsf(14)).foregroundStyle(s.onSurface.opacity(0.6))
            }
            Spacer(minLength: 0)
            Text(timeAgo).font(.gsf(11, .medium)).foregroundStyle(s.onSurfaceVariant)
        }
        .lineLimit(1)
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(s.surfaceContainerLow.mix(with: s.primary, by: 0.10, in: .perceptual), in: .capsule)
    }
}

// MARK: - Jump Back In cells

/// Memory mark on a plain album cover: 16 dp disc + 12 dp Cookie12Sided.
private enum MemoryMark { case solid, outline, dashed }

private struct JBICell: View {
    @Environment(AppModel.self) private var model
    @Environment(\.homeScheme) private var scheme
    @Environment(\.zoomNamespace) private var zoomNS
    var album: Album
    var mark: MemoryMark?

    var body: some View {
        let s = scheme!
        Button { model.open(album) } label: {
            AlbumItem(album: album, size: 100) {
                if let mark { markView(mark, ink: BackdropColors(album.palette).base, bg: s.background) }
            }
            .zoomSource(album.id, zoomNS)
        }
        .buttonStyle(PressScale())
    }

    /// Memory mark: 16 dp disc + 12 dp Cookie12Sided, on the cover's top-trailing corner.
    private func markView(_ m: MemoryMark, ink: Color, bg: Color) -> some View {
        ZStack {
            Circle().fill(bg).frame(width: 16, height: 16)
            switch m {
            case .solid: CookieShape(lobes: 12, depth: 0.12).fill(ink).frame(width: 12, height: 12)
            case .outline: CookieShape(lobes: 12, depth: 0.12).stroke(ink, lineWidth: 1.25).frame(width: 11, height: 11)
            case .dashed:
                CookieShape(lobes: 12, depth: 0.12)
                    .stroke(ink.opacity(0.6), style: StrokeStyle(lineWidth: 1.25, dash: [2, 1.5]))
                    .frame(width: 11, height: 11)
            }
        }
        .padding(8)
    }
}

/// One album on Home: cover plus title (GSF SemiBold, ROND 100) and artist.
/// A (default): bare cover on the page. B (`-homeAlbum tile`): the text sits in
/// a light tile of the cover color under the cover, same corner family.
struct AlbumItem<Mark: View>: View {
    @Environment(\.homeScheme) private var scheme
    var album: Album
    var size: CGFloat
    var titleSize: CGFloat = 16
    @ViewBuilder var mark: () -> Mark

    var body: some View {
        let s = scheme!
        let tile = LaunchConfig.homeAlbumTile
        VStack(alignment: .leading, spacing: tile ? 0 : 6) {
            EntityBackdrop(entity: .album, palette: album.palette, seed: album.seed, size: size, stroke: s.primary)
                .overlay(alignment: .topTrailing) { mark() }
            VStack(alignment: .leading, spacing: 0) {
                Text(album.title)
                    .font(YoinFont.font(size: titleSize, YoinFont.Axes(weight: 600, round: 100)))
                    .foregroundStyle(s.onSurface)
                Text(album.artist)
                    .font(.gsf(titleSize - 4))
                    .foregroundStyle(s.onSurfaceVariant)
            }
            .lineLimit(1)
            .padding(.horizontal, tile ? 8 : 0)
            .padding(.top, tile ? 8 : 0)
            .padding(.bottom, tile ? 9 : 0)
            .frame(width: size, alignment: .leading)
        }
        .background {
            if tile {
                UnevenRoundedRectangle(topLeadingRadius: EntityBackdrop.corner(size),
                                       bottomLeadingRadius: EntityBackdrop.corner(size),
                                       bottomTrailingRadius: EntityBackdrop.corner(size),
                                       topTrailingRadius: EntityBackdrop.corner(size), style: .continuous)
                    .fill(cardWash(s, album.palette))
            }
        }
        .frame(width: size, alignment: .leading)
    }
}

extension AlbumItem where Mark == EmptyView {
    init(album: Album, size: CGFloat, titleSize: CGFloat = 16) {
        self.init(album: album, size: size, titleSize: titleSize) { EmptyView() }
    }
}

private struct SignalCard: View {
    enum Kind { case ratedAlbum, notedTrack }
    @Environment(AppModel.self) private var model
    @Environment(\.homeScheme) private var scheme
    var album: Album
    var kind: Kind

    var body: some View {
        let s = scheme!
        let ink = BackdropColors(album.palette).base
        Button { model.open(album) } label: {
            HStack(alignment: .top, spacing: 16) {
                VStack(alignment: .leading, spacing: 5) {
                    EntityBackdrop(entity: .album, palette: album.palette, seed: album.seed, size: 100,
                                   stroke: s.primary)
                    VStack(alignment: .leading, spacing: 0) {
                        Text(kind == .ratedAlbum ? album.title : album.tracks[0].title)
                            .font(YoinFont.font(size: 16, YoinFont.Axes(weight: 600, round: 100)))
                            .foregroundStyle(s.onSurface)
                        Text(album.artist).font(.gsf(12)).foregroundStyle(s.onSurfaceVariant)
                    }
                    .lineLimit(1)
                    .frame(width: 100, alignment: .leading)
                }
                VStack(alignment: .leading, spacing: 3) {
                    HStack(alignment: .lastTextBaseline, spacing: 6) {
                        Text(kind == .ratedAlbum ? String(format: "%.1f", album.rating ?? 0) : "8.0")
                            .font(.gsf(24, .bold))
                            .monospacedDigit()
                        Text(kind == .ratedAlbum ? "Oct 28" : "Nov 1")
                            .font(.gsf(11, .medium))
                            .monospacedDigit()
                            .opacity(0.85)
                            .padding(.bottom, 3)
                    }
                    .foregroundStyle(ink)
                    if kind == .ratedAlbum {
                        Text("A field that stays bright after dark")
                            .font(.gsf(17, .semibold))
                            .foregroundStyle(s.onSurface)
                            .lineLimit(3)
                    } else {
                        Text("The bridge at 2:10 sounds like summer ending")
                            .font(.gsf(14))
                            .foregroundStyle(s.onSurface)
                            .lineLimit(3)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .buttonStyle(PressScale())
    }
}

// MARK: - Recently Added

private struct TrackTile: View {
    @Environment(\.homeScheme) private var scheme
    var album: Album
    var track: Track

    var body: some View {
        let s = scheme!
        HStack(spacing: 8) {
            CoverArt(palette: album.palette, cornerRadius: 4, seed: album.seed)
                .frame(width: 52, height: 52)
            VStack(alignment: .leading, spacing: 0) {
                Text(track.title).font(.gsf(13, .medium)).foregroundStyle(s.onSurface)
                Text(album.artist).font(.gsf(11, .medium)).foregroundStyle(s.onSurfaceVariant)
            }
            .lineLimit(1)
        }
    }
}

private struct AlbumMini: View {
    @Environment(AppModel.self) private var model
    var album: Album

    var body: some View {
        Button { model.open(album) } label: {
            AlbumItem(album: album, size: 100, titleSize: 14)
        }
        .buttonStyle(PressScale())
    }
}

// MARK: - Rediscover

private struct RediscoverCard: View {
    @Environment(AppModel.self) private var model
    @Environment(\.homeScheme) private var scheme
    var album: Album
    var reason: String
    var away: String
    var score: Double?
    var footnote: (String, String)

    var body: some View {
        let s = scheme!
        let ink = BackdropColors(album.palette).base
        let container = cardWash(s, album.palette)
        Button { model.open(album) } label: {
            HStack(spacing: 14) {
                EntityBackdrop(entity: .album, palette: album.palette, seed: album.seed, size: 104, stroke: s.primary)
                    .overlay(alignment: .bottomTrailing) {
                        if let score {
                            Text(String(format: "%.1f", score))
                                .font(.gsf(14, .bold))
                                .monospacedDigit()
                                .foregroundStyle(container)
                                .padding(.horizontal, 8)
                                .frame(height: 24)
                                .background(ink, in: .capsule)
                                .offset(x: 6)
                        }
                    }
                VStack(alignment: .leading, spacing: 0) {
                    Text(reason).font(.gsf(12, .medium)).foregroundStyle(ink)
                    Text(away).font(.gsf(12, .medium)).foregroundStyle(ink)
                    Text(album.title)
                        .font(.gsf(20, .semibold))
                        .foregroundStyle(s.onSurface)
                        .padding(.top, 2)
                    Text(album.artist).font(.gsf(14)).foregroundStyle(s.onSurfaceVariant)
                    MetaLine(groups: [(footnote.0, nil), (footnote.1, "plays")], color: s.onSurfaceVariant)
                        .padding(.top, 4)
                }
                .lineLimit(1)
                Spacer(minLength: 0)
            }
            .padding(14)
            .frame(height: 132)
            .background(container, in: .rect(cornerRadius: 16, style: .continuous))
        }
        .buttonStyle(PressScale())
    }
}
