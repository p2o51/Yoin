import SwiftUI
import YoinShared

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

    /// Signed in: the server's shelves (nil while the first load runs);
    /// signed out: the mock shelf the screenshots use.
    private var feed: HomeFeed? { model.store.isReal ? model.store.home : MockLibrary.shared.homeFeed() }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                header
                if let feed {
                    if !feed.activities.isEmpty { activities(feed.activities) }
                    if !feed.jumpBackIn.isEmpty { jumpBackIn(feed) }
                    if !feed.recentTracks.isEmpty || !feed.recentAlbums.isEmpty { recentlyAdded(feed) }
                    if !feed.rediscover.isEmpty { rediscover(feed.rediscover) }
                } else if model.store.homePhase == .failed {
                    RetryRow(scheme: scheme) { Task { await model.store.loadHome(force: true) } }
                } else {
                    HomeSkeleton(width: contentWidth, scheme: scheme)
                }
            }
            .padding(.horizontal, 16)
            .onGeometryChange(for: CGFloat.self) { $0.size.width - 32 } action: { contentWidth = $0 }
        }
        .contentMargins(.bottom, 24, for: .scrollContent)
        // `-screen home-bottom` opens scrolled to the end (screenshots).
        .defaultScrollAnchor(LaunchConfig.screen == "home-bottom" ? .bottom : .top)
        .scrollEdgeEffectStyle(.soft, for: [.top, .bottom])
        .background(
            LinearGradient(colors: [scheme.surfaceContainer, scheme.background, scheme.surfaceContainerLow],
                           startPoint: .top, endPoint: .bottom)
            .ignoresSafeArea()
        )
        .environment(\.homeScheme, scheme)
        .task(id: model.store.generation) { await model.store.loadHome() }
        .refreshable { if model.store.isReal { await model.store.loadHome(force: true) } }
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
                    // An easy way into Glass Lab on a real phone (besides the
                    // three-finger long-press).
                    .onLongPressGesture { withAnimation(Yoin.spatial) { GlassLab.shared.panelOpen = true } }
            }
            .lineLimit(1)
            .padding(.trailing, Tokens.home.HEADER_TITLE_BREATHING.cg)
            Spacer(minLength: 0)
            Button { model.openSettings() } label: {
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

    private func activities(_ a: [ActivityEntry]) -> some View {
        let gap = Tokens.home.ACTIVITY_ROW_GAP.cg
        let small = contentWidth > 0 ? (contentWidth - gap) / 3 : 120
        return VStack(alignment: .leading, spacing: gap) {
            HomeSectionTitle("Activities").padding(.bottom, 6)
            HeroCard(album: a[0].album, timeAgo: a[0].eyebrow)
            if a.count > 1 {
                HStack(spacing: gap) {
                    SmallCard(album: a[1].album, entity: .artist, typeLabel: "Artist", timeAgo: a[1].eyebrow,
                              title: a[1].album.artist)
                        .frame(width: small)
                    if a.count > 2 { WideCard(album: a[2].album, timeAgo: a[2].eyebrow) }
                }
                .frame(height: Tokens.home.ACTIVITY_ROW_HEIGHT.cg)
            }
            if a.count > 3 { StripCard(album: a[3].album, timeAgo: a[3].eyebrow) }
        }
    }

    // MARK: Jump Back In (3 columns, two 1×2 signal cards)

    @ViewBuilder
    private func jumpBackIn(_ feed: HomeFeed) -> some View {
        if feed.layout == .mock { mockJumpBackIn(feed.jumpBackIn) } else { serverJumpBackIn(feed.jumpBackIn) }
    }

    /// A server's shelf: plain cells, three to a row.
    private func serverJumpBackIn(_ a: [Album]) -> some View {
        let colGap = Tokens.home.JBI_COLUMN_GAP.cg
        let col = (contentWidth - 2 * colGap) / 3
        return VStack(alignment: .leading, spacing: Tokens.home.JBI_ROW_GAP.cg) {
            HomeSectionTitle("Jump Back In")
            ForEach(Array(stride(from: 0, to: a.count, by: 3)), id: \.self) { i in
                HStack(alignment: .top, spacing: colGap) {
                    ForEach(a[i..<min(i + 3, a.count)]) { album in
                        JBICell(album: album, mark: nil).frame(width: col, alignment: .leading)
                    }
                }
            }
        }
    }

    private func mockJumpBackIn(_ a: [Album]) -> some View {
        // Compose Row weights (HomeWidgetGrid.kt phone path): each row splits
        // what its own 12 dp gaps leave, so a 1×2 row (one gap) gets
        // (C − 12) × 2/3 + (C − 12)/3 and a three-cover row (C − 24)/3 each.
        let colGap = Tokens.home.JBI_COLUMN_GAP.cg
        let col = (contentWidth - 2 * colGap) / 3
        let pairUnit = (contentWidth - colGap) / 3
        return VStack(alignment: .leading, spacing: Tokens.home.JBI_ROW_GAP.cg) {
            HomeSectionTitle("Jump Back In")
            VStack(alignment: .leading, spacing: Tokens.home.JBI_ROW_GAP.cg) {
                HStack(alignment: .top, spacing: colGap) {
                    SignalCard(album: a[0], kind: .ratedAlbum).frame(width: pairUnit * 2, alignment: .leading)
                    JBICell(album: a[1], mark: .solid).frame(width: pairUnit, alignment: .leading)
                }
                HStack(alignment: .top, spacing: colGap) {
                    JBICell(album: a[2], mark: nil).frame(width: pairUnit, alignment: .leading)
                    SignalCard(album: a[5], kind: .notedTrack).frame(width: pairUnit * 2, alignment: .leading)
                }
                ForEach(0..<2, id: \.self) { row in
                    HStack(alignment: .top, spacing: colGap) {
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

    private func recentlyAdded(_ feed: HomeFeed) -> some View {
        let tiles = feed.recentTracks
        let gridWidth = min(340, max(226.8, (contentWidth - 14) * 2.6 / 3.6))
        let tileGap = Tokens.home.RECENTLY_ADDED_TILE_GAP.cg
        return VStack(alignment: .leading, spacing: 12) {
            HomeSectionTitle("Recently Added")
            ScrollView(.horizontal) {
                HStack(alignment: .top, spacing: 14) {
                    if !tiles.isEmpty {
                        VStack(alignment: .leading, spacing: 14) {
                            ForEach(Array(stride(from: 0, to: min(tiles.count, 4), by: 2)), id: \.self) { r in
                                HStack(spacing: tileGap) {
                                    ForEach(r..<min(r + 2, tiles.count), id: \.self) { i in
                                        TrackTile(album: tiles[i].album, track: tiles[i].track)
                                            .frame(width: (gridWidth - tileGap) / 2, alignment: .leading)
                                            .onTapGesture { model.play(tiles, startAt: i) }
                                    }
                                }
                            }
                        }
                        .frame(width: gridWidth, alignment: .topLeading)
                    }
                    ForEach(Array(feed.recentAlbums.enumerated()), id: \.offset) { _, album in
                        AlbumMini(album: album)
                    }
                }
                .padding(.horizontal, 16)
            }
            .scrollIndicators(.hidden)
            .padding(.horizontal, -16)
        }
    }

    // MARK: Rediscover (peeking shelf)

    private func rediscover(_ cards: [RediscoverEntry]) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HomeSectionTitle("Rediscover")
            ScrollView(.horizontal) {
                HStack(spacing: Tokens.home.REDISCOVER_GAP.cg) {
                    ForEach(Array(cards.enumerated()), id: \.offset) { _, c in
                        RediscoverCard(album: c.album, reason: c.reason, away: c.away, score: c.score,
                                       footnote: (c.footnoteDate, c.footnotePlays))
                            .frame(width: contentWidth * 0.86)
                    }
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
    scheme.surfaceContainerLow.mix(with: BackdropColors(palette).base, by: Tokens.home.CARD_WASH_FRACTION, in: .perceptual)
}

/// Android's cover-tone accent (ActivityCardColors.ink, the JBI score rule):
/// the palette base reads on a light surface but sinks into a dark one, so
/// dark mode takes the brighter accent.
func cardInk(_ palette: CoverPalette, dark: Bool) -> Color {
    let b = BackdropColors(palette)
    return dark ? b.accent : b.base
}

// MARK: - Type (helpers in Theme/TypeRoles.swift)

/// Section title: titleLarge SemiBold at 18 sp (line height 28).
struct HomeSectionTitle: View {
    @Environment(\.homeScheme) private var scheme
    var text: String
    init(_ text: String) { self.text = text }
    var body: some View {
        Text(text)
            .homeType(Tokens.type.titleLarge, .semibold, size: 18)
            .foregroundStyle(scheme?.onSurface ?? Yoin.ink)
            .lineLimit(1)
    }
}

/// Android MetaLine (component/MetaLine.kt): groups 12 dp apart, never joined
/// by a glyph. A Stat is its number in Medium (tabular) + 4 dp + its unit in
/// Normal at 60 %; a Plain group takes the line's own style.
private struct MetaLine: View {
    var groups: [(String, String?)]
    var style: YoinTokens.TypographyStyle = Tokens.type.labelSmall
    var color: Color
    var body: some View {
        HStack(spacing: Tokens.metaLine.GROUP_GAP.cg) {
            ForEach(Array(groups.enumerated()), id: \.offset) { _, g in
                if let unit = g.1 {
                    HStack(spacing: Tokens.metaLine.INNER_GAP.cg) {
                        Text(g.0).homeType(style, .medium, digits: true)
                        Text(unit).homeType(style, .regular).opacity(Tokens.metaLine.MUTED_ALPHA)
                    }
                } else {
                    Text(g.0).homeType(style)
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

    /// [year] [N songs] [M min]; the mock keeps its hand-set 48 min.
    private var heroMeta: [(String, String?)] {
        var g: [(String, String?)] = []
        if album.year > 0 { g.append(("\(album.year)", nil)) }
        g.append(("\(album.trackCount)", "songs"))
        let mins = album.id.provider == "mock" ? 48 : album.totalSec / 60
        if mins > 0 { g.append(("\(mins)", "min")) }
        return g
    }

    var body: some View {
        let s = scheme!
        let muted = s.onSurfaceVariant
        Button { model.open(album) } label: {
            HStack(spacing: 14) {
                EntityBackdrop(entity: .album, palette: album.palette, seed: album.seed, size: 104, stroke: s.primary)
                    .zoomSource(album.id, zoomNS)
                VStack(alignment: .leading, spacing: 3) {
                    Text(timeAgo).homeType(Tokens.type.labelSmall).foregroundStyle(muted)
                    Text(album.title).homeType(Tokens.type.titleLarge, .semibold).foregroundStyle(s.onSurface)
                    Text(album.artist).homeType(Tokens.type.bodySmall).foregroundStyle(muted)
                    MetaLine(groups: heroMeta, color: muted)
                }
                .lineLimit(1)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(14)
            .homeGlassCard(album.palette, scheme: s, shape: RoundedRectangle(cornerRadius: Tokens.shape.CARD.cg, style: .continuous))
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
        Button {
            if entity == .artist { model.openArtist(album.artist, id: album.artistId) } else { model.open(album) }
        } label: {
            VStack(alignment: .leading, spacing: 4) {
                HStack(alignment: .center, spacing: 10) {
                    EntityBackdrop(entity: entity, palette: album.palette, seed: album.seed, size: 52, stroke: s.primary)
                    VStack(alignment: .leading, spacing: 0) {
                        Text(typeLabel).homeType(Tokens.type.labelSmall)
                        Text(timeAgo).homeType(Tokens.type.labelSmall)
                    }
                    .foregroundStyle(s.onSurfaceVariant)
                    .lineLimit(1)
                }
                Spacer(minLength: 0)
                // titleSmall SemiBold, leading tightened to 17 so two lines read as one block.
                Text(title)
                    .homeType(Tokens.type.titleSmall, .semibold, lineHeight: 17)
                    .foregroundStyle(s.onSurface)
                    .lineLimit(2)
            }
            .padding(10)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
            .homeGlassCard(album.palette, scheme: s, shape: RoundedRectangle(cornerRadius: Tokens.shape.CARD.cg, style: .continuous))
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
                    Text(timeAgo).homeType(Tokens.type.labelSmall).foregroundStyle(s.onSurfaceVariant)
                    Text(album.title).homeType(Tokens.type.titleMedium, .semibold).foregroundStyle(s.onSurface)
                    Text(album.artist).homeType(Tokens.type.bodySmall).foregroundStyle(s.onSurfaceVariant)
                }
                .lineLimit(1)
                Spacer(minLength: 0)
            }
            .padding(12)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .homeGlassCard(album.palette, scheme: s, shape: RoundedRectangle(cornerRadius: Tokens.shape.CARD.cg, style: .continuous))
        }
        .buttonStyle(PressScale())
    }
}

private struct StripCard: View {
    @Environment(AppModel.self) private var model
    @Environment(\.homeScheme) private var scheme
    var album: Album
    var timeAgo: String

    var body: some View {
        let s = scheme!
        Button { model.open(album) } label: {
            HStack(spacing: 10) {
                HStack(spacing: 12) {
                    Text(album.title).homeType(Tokens.type.bodyMedium, .semibold).foregroundStyle(s.onSurface)
                    Text(album.artist).homeType(Tokens.type.bodyMedium).foregroundStyle(s.onSurface.opacity(0.6))
                }
                Spacer(minLength: 0)
                Text(timeAgo).homeType(Tokens.type.labelSmall).foregroundStyle(s.onSurfaceVariant)
            }
            .lineLimit(1)
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .homeGlassCard(album.palette, scheme: s, shape: Capsule())
        }
        .buttonStyle(PressScale())
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
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        let s = scheme!
        Button { model.open(album) } label: {
            AlbumItem(album: album, size: Tokens.home.JBI_COVER.cg) {
                // albumMemoryMarkInk: palette base, accent in dark mode.
                if let mark { markView(mark, ink: cardInk(album.palette, dark: colorScheme == .dark), bg: s.background) }
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

/// One album on Home: bare cover, then title (GSF SemiBold, ROND 100: iOS
/// decision) and artist, flush as one text cluster. Defaults are the JBI 1×1
/// (HomeWidgetGrid.kt WidgetCoverBlock / WidgetCoverCaption): 5 gap, title
/// 16/22, subtitle bodySmall 12/16.
struct AlbumItem<Mark: View>: View {
    @Environment(\.homeScheme) private var scheme
    var album: Album
    var size: CGFloat
    var titleSize: CGFloat = 16
    var titleLineHeight: CGFloat = 22
    var artistStyle: YoinTokens.TypographyStyle = Tokens.type.bodySmall
    var gap: CGFloat = 5
    @ViewBuilder var mark: () -> Mark

    var body: some View {
        let s = scheme!
        VStack(alignment: .leading, spacing: gap) {
            EntityBackdrop(entity: .album, palette: album.palette, seed: album.seed, size: size, stroke: s.primary)
                .overlay(alignment: .topTrailing) { mark() }
            VStack(alignment: .leading, spacing: 0) {
                Text(album.title)
                    .font(YoinFont.font(size: titleSize, YoinFont.Axes(weight: 600, round: 100)))
                    .homeLine(titleSize, titleLineHeight)
                    .foregroundStyle(s.onSurface)
                Text(album.artist)
                    .homeType(artistStyle)
                    .foregroundStyle(s.onSurfaceVariant)
            }
            .lineLimit(1)
        }
        .frame(width: size, alignment: .leading)
    }
}

extension AlbumItem where Mark == EmptyView {
    init(album: Album, size: CGFloat, titleSize: CGFloat = 16, titleLineHeight: CGFloat = 22,
         artistStyle: YoinTokens.TypographyStyle = Tokens.type.bodySmall, gap: CGFloat = 5) {
        self.init(album: album, size: size, titleSize: titleSize, titleLineHeight: titleLineHeight,
                  artistStyle: artistStyle, gap: gap) { EmptyView() }
    }
}

private struct SignalCard: View {
    enum Kind { case ratedAlbum, notedTrack }
    @Environment(AppModel.self) private var model
    @Environment(\.homeScheme) private var scheme
    var album: Album
    var kind: Kind

    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        let s = scheme!
        let ink = cardInk(album.palette, dark: colorScheme == .dark)
        Button { model.open(album) } label: {
            HStack(alignment: .top, spacing: 16) {
                VStack(alignment: .leading, spacing: 5) {
                    EntityBackdrop(entity: .album, palette: album.palette, seed: album.seed, size: Tokens.home.JBI_COVER.cg,
                                   stroke: s.primary)
                    VStack(alignment: .leading, spacing: 0) {
                        Text(kind == .ratedAlbum ? album.title : album.tracks[0].title)
                            .font(YoinFont.font(size: 16, YoinFont.Axes(weight: 600, round: 100)))
                            .homeLine(16, 22)
                            .foregroundStyle(s.onSurface)
                        Text(album.artist).homeType(Tokens.type.bodySmall).foregroundStyle(s.onSurfaceVariant)
                    }
                    .lineLimit(1)
                    .frame(width: Tokens.home.JBI_COVER.cg, alignment: .leading)
                }
                VStack(alignment: .leading, spacing: 3) {
                    // Row(verticalAlignment = Bottom): the basis sits 3 dp off the score's line-box bottom.
                    HStack(alignment: .bottom, spacing: 6) {
                        Text(kind == .ratedAlbum ? String(format: "%.1f", album.rating ?? 0) : "8.0")
                            .homeType(Tokens.type.headlineSmall, .bold, digits: true)
                        Text(kind == .ratedAlbum ? "Oct 28" : "Nov 1")
                            .homeType(Tokens.type.labelSmall, digits: true)
                            .opacity(0.85)
                            .padding(.bottom, 3)
                    }
                    .foregroundStyle(ink)
                    if kind == .ratedAlbum {
                        Text("A field that stays bright after dark")
                            .homeType(Tokens.type.titleMedium, .semibold, size: 17, lineHeight: 24)
                            .foregroundStyle(s.onSurface)
                            .lineLimit(3)
                    } else {
                        Text("The bridge at 2:10 sounds like summer ending")
                            .homeType(Tokens.type.bodyMedium)
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
            CoverArt(palette: album.palette, cornerRadius: Tokens.shape.THUMB.cg, seed: album.seed)
                .frame(width: Tokens.home.RECENTLY_ADDED_TRACK_COVER.cg, height: Tokens.home.RECENTLY_ADDED_TRACK_COVER.cg)
            VStack(alignment: .leading, spacing: 0) {
                // bodyMedium at 13 sp Medium (line height stays 20).
                Text(track.title).homeType(Tokens.type.bodyMedium, .medium, size: 13).foregroundStyle(s.onSurface)
                Text(album.artist).homeType(Tokens.type.labelSmall).foregroundStyle(s.onSurfaceVariant)
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
            // RecentlyAddedAlbumCard: 82 cover, 6 gap, bodySmall 12/16, labelSmall artist.
            AlbumItem(album: album, size: Tokens.home.RECENTLY_ADDED_ALBUM_COVER.cg, titleSize: 12, titleLineHeight: 16,
                      artistStyle: Tokens.type.labelSmall, gap: 6)
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

    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        let s = scheme!
        let ink = cardInk(album.palette, dark: colorScheme == .dark)
        let container = cardWash(s, album.palette)
        Button { model.open(album) } label: {
            HStack(spacing: 14) {
                EntityBackdrop(entity: .album, palette: album.palette, seed: album.seed,
                               size: Tokens.home.REDISCOVER_BACKDROP.cg, stroke: s.primary)
                    .overlay(alignment: .bottomTrailing) {
                        if let score {
                            Text(String(format: "%.1f", score))
                                .homeType(Tokens.type.labelLarge, .bold, digits: true)
                                .foregroundStyle(container)
                                .padding(.horizontal, Tokens.home.REDISCOVER_BADGE_PADDING.cg)
                                .frame(height: Tokens.home.REDISCOVER_BADGE_HEIGHT.cg)
                                .background(ink, in: .capsule)
                                .offset(x: Tokens.home.REDISCOVER_BADGE_HANG.cg)
                        }
                    }
                VStack(alignment: .leading, spacing: 0) {
                    Text(reason).homeType(Tokens.type.labelMedium).foregroundStyle(ink)
                    Text(away).homeType(Tokens.type.labelMedium).foregroundStyle(ink)
                    Text(album.title)
                        .homeType(Tokens.type.titleLarge, .semibold, size: 20, lineHeight: 26)
                        .foregroundStyle(s.onSurface)
                        .padding(.top, 2)
                    Text(album.artist).homeType(Tokens.type.bodyMedium).foregroundStyle(s.onSurfaceVariant)
                    MetaLine(groups: [(footnote.0, nil), (footnote.1, "plays")], color: s.onSurfaceVariant)
                        .padding(.top, 4)
                }
                .lineLimit(1)
                Spacer(minLength: 0)
            }
            .padding(Tokens.home.REDISCOVER_PADDING.cg)
            .frame(height: Tokens.home.REDISCOVER_CARD_HEIGHT.cg)
            .homeGlassCard(album.palette, scheme: s, shape: RoundedRectangle(cornerRadius: Tokens.shape.CARD.cg, style: .continuous))
        }
        .buttonStyle(PressScale())
    }
}

// MARK: - Glass cards

extension View {
    /// Home's cover-color cards as Liquid Glass. Glass needs something to
    /// refract, so the card's own cover sits behind it, scaled up and heavily
    /// blurred, clipped to the card; the glass on top is tinted with the
    /// card wash so text keeps the solid card's contrast.
    func homeGlassCard<S: InsettableShape>(_ palette: CoverPalette, scheme: CoverScheme, shape: S) -> some View {
        let lab = GlassLab.shared
        return background {
            ZStack {
                CoverArt(palette: palette, cornerRadius: 0, seed: 1)
                    .scaleEffect(1.6)
                    .blur(radius: lab.extra("coverBlur"))
                    .opacity(lab.extra("coverStrength"))
                    .clipShape(shape)
                shape.fill(.clear)
                    .labGlass("homeCards", scheme: scheme, brand: .brand(dark: false),
                              auto: cardWash(scheme, palette), in: shape)
            }
            .allowsHitTesting(false)
        }
    }

}
