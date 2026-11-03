import SwiftUI
import UIKit
import YoinShared

/// Album page, page 1 of Android's two-page album detail (AlbumDetailScreen,
/// AlbumSpectrumBar, DetailPullUpReshape, AlbumDetailComponents), phone
/// portrait. Two states on one progress `p`:
/// - Hero (p = 0): header, cover with the Yoin "butterfly" blocks behind it,
///   the score emblem, and the track titles flowing as one paragraph.
/// - Track list (p = 1): pulled up. The cover flies into a 48 pt stamp at the
///   end of the title row, the header turns into the cover's color spectrum
///   with white text, and the list fades in.
///
/// Page 1 ↔ page 2 (Android AlbumPageFlight, AlbumScrapbookPage scrapLayer):
/// the pager follows the finger 1:1. With the hero at rest the cover flies to
/// the scrapbook's cover (taking its tilt) instead of sliding off, the hero
/// text is gone a third of the way in, and each scrapbook piece trails the
/// drag by its own depth and leans a little further.
///
/// iOS adaptations: the back button is a glass circle (Android: a tonal
/// circle), and edge-swipe back still works with the navigation bar hidden
/// and always wins over the pager. The pulled-up top bar is a Glass Lab
/// choice (`detailTopBar`): the spectrum (Android, default), Liquid Glass, or
/// the system's progressive scroll-edge blur.
struct AlbumDetailView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var album: Album
    var padColumn = false
    /// Playlist mode (Android PlaylistDetailScreen): same hero / pull-up /
    /// spectrum, but owner + "Playlist" meta, a ⋮ menu, the stacked-V
    /// backdrop, a Length / Owner / Description block instead of the emblem,
    /// thumb-led track rows, and no scrapbook page.
    var playlist: PlaylistInfo? = nil

    @State private var p: CGFloat = ["album-list", "playlist-list"].contains(LaunchConfig.screen) ? 1
        : LaunchConfig.screen == "album-mid" ? 0.42 : 0
    @State private var dragBase: CGFloat?
    /// Pager: 0 = overview, 1 = scrapbook.
    @State private var page: CGFloat = LaunchConfig.screen.hasPrefix("album-scrapbook") ? 1 : 0
    @State private var pageDrag: CGFloat = 0
    @State private var pageWidth: CGFloat = 402
    /// Ends a pager drag the system cancelled (e.g. the edge swipe took over).
    @GestureState private var pagerLive = false
    private var pageFrac: CGFloat {
        if let f = Self.forcedPageFrac, page == 0, pageDrag == 0 { return f }
        return min(1, max(0, page - pageDrag / max(pageWidth, 1)))
    }
    /// Screenshots: `-pageFrac 0.5` holds the pager mid-swipe.
    private static let forcedPageFrac = UserDefaults.standard.string(forKey: "pageFrac")
        .flatMap(Double.init).map { CGFloat($0) }
    /// The scrapbook cover's resting rect (this page's coordinates) and tilt:
    /// where the hero cover lands (Android AlbumPageFlight.coverTarget).
    @State private var scrapCover: CGRect?
    @State private var scrapCoverTilt: Double = 0
    /// Screenshots: `-listScroll 160` opens the pulled-up list scrolled.
    @State private var listPosition = ScrollPosition(y: CGFloat(UserDefaults.standard.double(forKey: "listScroll")))

    /// Pulled-up top bar (Glass Lab): 0 spectrum, 1 Liquid Glass, 2 progressive blur.
    private var topBar: Int { Int(GlassLab.shared.extra("detailTopBar").rounded()) }
    /// The hero's cover flies to page 2 (hero at rest, an album, motion allowed).
    private var coverFlies: Bool { playlist == nil && !reduceMotion && scrapCover != nil && p < 0.01 }

    /// The cover's own scheme (title, blocks, dots, Play) and the app's
    /// (everything else), as on Android.
    private var s: CoverScheme { CoverScheme(album: album, dark: colorScheme == .dark) }
    private var app: CoverScheme {
        model.isIdle ? .brand(dark: colorScheme == .dark)
                     : CoverScheme(album: model.playback.album, dark: colorScheme == .dark)
    }

    /// The header below the status bar: 4 top + the 52 title row (headlineSmall
    /// 32 + bodyMedium 20) + the page dots (5 + 20) + 6 bottom (AlbumTopHeader).
    /// Playlists have no dots row: 4 + 52 + 6 (PlaylistTopHeader).
    private var headerContent: CGFloat { playlist == nil ? 4 + 52 + 25 + 6 : 4 + 52 + 6 }

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width
            let side = min(Tokens.detail.ALBUM_COVER_WIDTH_FRACTION.cg * w, Tokens.detail.ALBUM_COVER_MAX.cg)
            let band = side + 56
            let e = smooth(p)
            let f = pageFrac
            // The spectrum turns the header white; glass and blur keep its ink.
            let white = topBar == 0 ? smooth(p, 0.25, 0.7) : 0
            // Hero text while its cover flies: gone a third of the way in (albumHeroPageFade).
            let heroFade = coverFlies ? min(1, max(0, 1 - f * 3.5)) : 1
            ZStack(alignment: .topLeading) {
                background
                // Page 0: the bar, the stamp and the hero slide away with it.
                ZStack(alignment: .topLeading) {
                    if playlist != nil {
                        stackedV(w: w, side: side, band: band)
                            .opacity(1 - smooth(p, 0, 0.6))
                    } else {
                        butterfly(w: w, side: side, band: band)
                            .opacity(1 - smooth(p, 0.62, 0.98))
                    }
                    hero(w: w, side: side, band: band)
                        .opacity((1 - e) * heroFade)
                        .offset(y: -e * 15) // Android: −40 px
                        .allowsHitTesting(e < 0.5)
                    trackList(w: w)
                        .opacity(e)
                        .allowsHitTesting(e >= 0.5)
                    switch topBar {
                    case 1: glassBar(w: w, top: geo.safeAreaInsets.top)
                    case 2:
                        // With the track list's own soft scroll edge.
                        ProgressiveEdgeBlur(tint: topTint)
                            .frame(width: w, height: geo.safeAreaInsets.top + headerContent + 4)
                            .offset(y: -geo.safeAreaInsets.top)
                            .opacity(smooth(p, 0.3, 0.85))
                    default: spectrum(w: w, side: side, top: geo.safeAreaInsets.top)
                    }
                }
                .offset(x: -f * w)
                // Page 1: the scrapbook, scrolling under the header (albums only).
                if playlist == nil {
                    AlbumScrapbookView(album: album, settled: page == 1 && pageDrag == 0,
                                       topInset: headerContent, pageOffset: 1 - f, pageWidth: w,
                                       coverInFlight: coverFlies && f < 0.999) { rect, tilt in
                        scrapCover = rect
                        scrapCoverTilt = tilt
                    }
                    // Its own frame is the space its cover reports in (= this ZStack's).
                    .coordinateSpace(.named(AlbumScrapbookView.space))
                    .frame(width: w)
                    // Clip the sides only: it scrolls under the status bar and header.
                    .mask { Rectangle().padding(.vertical, -2000) }
                    .offset(x: (1 - f) * w)
                    // Its top edge: a progressive blur under the header, not a hard cut.
                    ProgressiveEdgeBlur(tint: topTint)
                        .frame(width: w, height: geo.safeAreaInsets.top + headerContent + 4)
                        .offset(x: (1 - f) * w, y: -geo.safeAreaInsets.top)
                }
                header(white: false, w: w)
                    .opacity(1 - white * (1 - f))
                header(white: true, w: w)
                    .opacity(white * (1 - f))
                cover(w: w, side: side, band: band)
            }
            .contentShape(.rect)
            .simultaneousGesture(pullUp(travel: geo.size.height * 0.55))
            .simultaneousGesture(pager(width: w), isEnabled: playlist == nil)
            .onChange(of: pagerLive) { _, live in
                guard !live, pageDrag != 0 else { return }
                withAnimation(Yoin.spatial) { page = pageFrac > 0.5 ? 1 : 0; pageDrag = 0 }
            }
            .onAppear { pageWidth = w }
            .onChange(of: w) { _, now in pageWidth = now }
        }
        .toolbar(.hidden, for: .navigationBar)
        .background(Yoin.surface)
    }

    // MARK: Pieces

    /// The page background's top color.
    private var topTint: Color { app.surfaceContainer.mix(with: s.primary, by: 0.18, in: .perceptual) }

    /// ExpressivePageBackground: [lerp(surfaceContainer, accent, 0.18), background, surfaceContainerLow].
    private var background: some View {
        LinearGradient(colors: [topTint,
                                app.background, app.surfaceContainerLow],
                       startPoint: .top, endPoint: .bottom)
            .ignoresSafeArea()
    }

    private func header(white: Bool, w: CGFloat) -> some View {
        // Playlists: the header glass takes the playlist's own container color,
        // so it reads clean over the colorful page instead of a grey film.
        // Light: primaryContainer + its ink; dark: a high container (the
        // expressive dark primaryContainer is mid-tone) + the primary ink.
        let tinted = playlist != nil && !white
        let dark = colorScheme == .dark
        let ink: Color = white ? .white : !tinted ? app.onSurface : dark ? s.primary : s.onPrimaryContainer
        let glass: Glass = white ? .regular.tint(.white.opacity(0.18)).interactive()
            : !tinted ? .regular.interactive()
            : .regular.tint(dark ? s.surfaceContainerHighest.opacity(0.7) : s.primaryContainer.opacity(0.6)).interactive()
        return VStack(spacing: 0) {
            HStack(spacing: 14) {
                Button { model.back() } label: {
                    YoinIcon("back", size: 22)
                        .foregroundStyle(ink)
                        .frame(width: 40, height: 40)
                        .contentShape(.circle)
                }
                .buttonStyle(.plain)
                .glassEffect(glass, in: .circle)
                .accessibilityLabel("Back")
                VStack(alignment: .leading, spacing: 0) {
                    Text(album.title)
                        .lineLimit(1)
                        .yoinRole(Tokens.type.headlineSmall)
                        .foregroundStyle(white ? .white : s.primary)
                    // DetailMetaLine in bodyMedium, groups 12 apart, no separators:
                    // album [artist] [Album] [year]; playlist [owner] [Playlist].
                    // The kind is Medium in primary. The white twin over the bar:
                    // onSurface white, primary white @ 0.88.
                    let plain: Color = white ? .white : app.onSurface
                    let kind: Color = white ? .white.opacity(0.88) : app.primary
                    HStack(spacing: 12) {
                        if let playlist {
                            Text(playlist.owner).yoinRole(Tokens.type.bodyMedium).foregroundStyle(plain)
                            Text("Playlist").yoinRole(Tokens.type.bodyMedium, weight: .medium).foregroundStyle(kind)
                        } else {
                            Button { model.openArtist(album.artist) } label: {
                                Text(album.artist).yoinRole(Tokens.type.bodyMedium).foregroundStyle(plain)
                            }
                            .buttonStyle(.plain)
                            Text("Album").yoinRole(Tokens.type.bodyMedium, weight: .medium).foregroundStyle(kind)
                            Text(String(album.year)).yoinRole(Tokens.type.bodyMedium).foregroundStyle(plain)
                        }
                    }
                    .lineLimit(1)
                }
                .padding(.trailing, (playlist != nil ? 48 + 12 + 4 : 60) * smooth(p))
                Spacer(minLength: 0)
                if playlist != nil {
                    // ⋮: Cloud backup, Rename, Delete (playlist pages only).
                    Menu {
                        Button("Cloud backup") {}
                        Button("Rename") {}
                        Button("Delete", role: .destructive) {}
                    } label: {
                        YoinIcon("more_vertical", size: 22)
                            .foregroundStyle(ink)
                            .frame(width: 40, height: 40)
                            .contentShape(.circle)
                    }
                    .buttonStyle(.plain)
                    .glassEffect(glass, in: .circle)
                    .frame(width: 48, height: 48)
                    .accessibilityLabel("More actions")
                    .padding(.trailing, -12)
                }
            }
            .frame(height: 52)
            if playlist == nil {
            HStack(spacing: 0) {
                ForEach(0..<2, id: \.self) { i in
                    // Active by distance from the pager fraction.
                    let active = 1 - min(1, abs(pageFrac - CGFloat(i)))
                    let on: Color = white ? .white : s.secondary
                    let off: Color = white ? .white.opacity(0.4) : app.onSurfaceVariant.opacity(0.3)
                    Button { withAnimation(Yoin.spatial) { page = CGFloat(i) } } label: {
                        Circle()
                            .fill(off.mix(with: on, by: active, in: .perceptual))
                            .frame(width: 8, height: 8)
                            .frame(width: 24, height: 20)
                            .contentShape(.rect)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(i == 0 ? "Overview" : "Scrapbook")
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.top, 5)
            }
        }
        .padding(.leading, 8)
        .padding(.trailing, 16)
        .padding(.top, 4)
        .padding(.bottom, 6)
        .frame(width: w)
    }

    /// The real Yoin mark's arms 0 and 2 behind the cover (AlbumSpectrumBar
    /// drawButterfly, Compact pose): box = (0, header bottom, W, band),
    /// scale 1.8 about its centre, translationY −0.1 × band, squash 1.8 about
    /// the hub; arm 0 in the cover's primary, arm 2 in its secondary, centre
    /// lines in surface @ 0.65; clipped above at the header.
    private func butterfly(w: CGFloat, side: CGFloat, band: CGFloat) -> some View {
        YoinArmsCanvas(arms: [(0, s.primary), (2, s.secondary)],
                       lineColor: app.surface.opacity(0.65),
                       scale: 1.8, translationY: -0.1 * band, squashX: 1.8)
            .frame(width: w, height: band)
            .offset(y: headerContent)
            .frame(width: w, height: headerContent + band * 1.8, alignment: .topLeading)
            .mask(alignment: .top) {
                Rectangle().padding(.top, headerContent)
            }
            .allowsHitTesting(false)
    }

    /// Hero cover ↔ 48 pt stamp at the end of the title row, and, with the
    /// hero at rest, hero cover → the scrapbook's cover on the swipe to page 2
    /// (Android AlbumFlyingCover + AlbumPageFlight): toward the page's cover
    /// as much as the hero is at rest (toPage = f × (1 − p)), taking its tilt;
    /// pulled up into the bar, the stamp slides away with page 0.
    private func cover(w: CGFloat, side: CGFloat, band: CGFloat) -> some View {
        let e = smooth(p)
        let f = pageFrac
        let heroRect = CGRect(x: (w - side) / 2, y: headerContent + 28, width: side, height: side)
        // Playlists: the stamp sits 4 pt before the ⋮ (end inset 40 + 16 page).
        let stampRect = CGRect(x: w - (playlist != nil ? 16 + 40 : 16) - 48, y: 4 + 2, width: 48, height: 48)
        let r = CGRect(x: lerp(heroRect.minX, stampRect.minX, e), y: lerp(heroRect.minY, stampRect.minY, e),
                       width: lerp(side, 48, e), height: lerp(side, 48, e))
        let target = playlist == nil && !reduceMotion ? scrapCover : nil
        let toPage = target == nil ? 0 : f * (1 - p)
        let flown = if let target, toPage > 0 {
            lerpRect(r.offsetBy(dx: -f * w * p, dy: 0), target, toPage)
        } else {
            r.offsetBy(dx: -f * w, dy: 0)
        }
        return Button {
            if e > 0.5 { withAnimation(Yoin.spatial) { p = 0 } }
        } label: {
            CoverArt(palette: album.palette, cornerRadius: 0, seed: album.seed)
                .frame(width: flown.width, height: flown.height)
                // Hero 8 → Cover 4 (Android restingCorner / stampCorner).
                .clipShape(.rect(cornerRadius: lerp(Tokens.shape.HERO.cg, Tokens.shape.COVER.cg, e), style: .continuous))
        }
        .buttonStyle(.plain)
        .allowsHitTesting(e > 0.5 && f < 0.5)
        .accessibilityLabel("Show cover")
        .rotationEffect(.degrees(scrapCoverTilt * Double(toPage)))
        // Landed: the scrapbook's own cover (tilted, under its emblem) takes over.
        .opacity(toPage >= 0.999 ? 0 : 1)
        .offset(x: flown.minX, y: flown.minY)
    }

    // MARK: Top bar variants (Glass Lab `detailTopBar`)

    /// Liquid Glass over the header once the list is pulled up, lightly
    /// tinted with the cover's container color; slides away with page 0.
    private func glassBar(w: CGFloat, top: CGFloat) -> some View {
        Color.clear
            .frame(width: w, height: top + headerContent)
            .glassEffect(.regular.tint(s.surfaceContainer.opacity(0.35)),
                         in: UnevenRoundedRectangle(bottomLeadingRadius: 28, bottomTrailingRadius: 28,
                                                    style: .continuous))
            .opacity(smooth(p, 0.3, 0.85))
            .offset(y: -top)
            .allowsHitTesting(false)
    }

    private func hero(w: CGFloat, side: CGFloat, band: CGFloat) -> some View {
        let ratedFlags = album.tracks.map { $0.rating != nil }
        let kind: ScoreEmblem.Kind = album.rating != nil ? .album : .average
        let score = album.rating ?? album.averageRating ?? 0
        return VStack(alignment: .leading, spacing: 0) {
            Spacer().frame(height: headerContent + band + 8)
            if let playlist {
                playlistMeta(playlist).frame(width: side).frame(maxWidth: .infinity)
                Spacer().frame(height: 28)
                Text(flowingTitles)
                    .lineSpacing(AlbumType.lineSpacing(Tokens.type.headlineMedium))
                    .frame(maxWidth: .infinity, alignment: .leading)
            } else {
            // Emblem + comment, as wide as the cover.
            HStack(spacing: 20) {
                HStack(spacing: 10) {
                    ScoreEmblem(score: score, kind: kind, rated: ratedFlags, palette: album.palette, size: 64)
                    if kind == .average { ratedCoverage }
                }
                comment
            }
            .frame(width: side)
            .frame(maxWidth: .infinity)
            Spacer().frame(height: 28)
            HStack(spacing: 12) {
                countLabel
                // AlbumHeroLastPlay: labelMedium Medium tabular, onSurface.
                Text("Yesterday 16:04")
                    .monospacedDigit()
                    .yoinRole(Tokens.type.labelMedium, weight: .medium)
                    .foregroundStyle(app.onSurface)
            }
            Spacer().frame(height: 6)
            Text(flowingTitles)
                .lineSpacing(AlbumType.lineSpacing(Tokens.type.headlineMedium))
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(.horizontal, 16)
        .frame(width: w, alignment: .topLeading)
    }

    /// AlbumTrackCountLabel: a MetaLine in labelMedium of Stat groups 12
    /// apart ("12 tracks", "1 h", "12 min"); each number Medium tabular, its
    /// unit Regular at 60 %, 4 apart. Hours and minutes are separate groups.
    private var countLabel: some View {
        let count = playlist?.entries.count ?? album.tracks.count
        let secs = playlist.map { $0.entries.map(\.track.durationSec).reduce(0, +) }
            ?? album.tracks.map(\.durationSec).reduce(0, +)
        let mins = secs / 60
        var groups: [(String, String)] = [("\(count)", count == 1 ? "track" : "tracks")]
        if mins >= 60 {
            groups.append(("\(mins / 60)", "h"))
            if mins % 60 > 0 { groups.append(("\(mins % 60)", "min")) }
        } else if mins > 0 {
            groups.append(("\(mins)", "min"))
        }
        return HStack(spacing: 12) {
            ForEach(groups, id: \.1) { value, unit in
                HStack(spacing: 4) {
                    Text(value).monospacedDigit().yoinRole(Tokens.type.labelMedium, weight: .medium)
                    Text(unit).yoinRole(Tokens.type.labelMedium, weight: .regular).opacity(0.6)
                }
            }
        }
        .foregroundStyle(app.onSurface)
    }

    /// AlbumRatedCoverage: "2/4 rated" in labelSmall tabular, onSurfaceVariant.
    private var ratedCoverage: some View {
        Text("\(album.ratedCount)/\(album.tracks.count) rated")
            .monospacedDigit()
            .lineLimit(1)
            .yoinRole(Tokens.type.labelSmall)
            .foregroundStyle(app.onSurfaceVariant)
    }

    /// AlbumHeroComment: written, bodyMedium (3 lines) over its date in
    /// labelMedium onSurfaceVariant, 4 apart; not written, a 40 tonal pen.
    @ViewBuilder
    private var comment: some View {
        if let comment = mockComment {
            VStack(alignment: .leading, spacing: 4) {
                Text(comment).lineLimit(3)
                    .yoinRole(Tokens.type.bodyMedium)
                    .foregroundStyle(app.onSurface)
                Text("Yesterday")
                    .yoinRole(Tokens.type.labelMedium)
                    .foregroundStyle(app.onSurfaceVariant)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        } else {
            Button {} label: {
                YoinIcon("edit", size: 18)
                    .foregroundStyle(app.onSecondaryContainer)
                    .frame(width: 40, height: 40)
                    .background(app.secondaryContainer, in: .circle)
            }
            .buttonStyle(PressScale())
            .accessibilityLabel("Write a comment")
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    /// Titles in headlineMedium joined by a space, even = onSurface,
    /// odd = onSurfaceVariant; short titles (≤ 16 units, CJK = 2) are glued
    /// so they wrap as one unit.
    private var flowingTitles: AttributedString {
        var out = AttributedString()
        for (i, t) in album.tracks.enumerated() {
            let units = t.title.unicodeScalars.reduce(0) { $0 + ($1.value > 0x2E80 ? 2 : 1) }
            let title = units <= 16 ? t.title.replacingOccurrences(of: " ", with: "\u{00A0}") : t.title
            var piece = AttributedString(title)
            piece.font = .gsf(28)
            piece.foregroundColor = i.isMultiple(of: 2) ? app.onSurface : app.onSurfaceVariant
            out += piece
            if i < album.tracks.count - 1 {
                var gap = AttributedString(" ")
                gap.font = .gsf(28)
                out += gap
            }
        }
        return out
    }

    private var mockComment: String? {
        album.rating != nil ? "A record for the walk home, warm even on the cold tracks" : nil
    }

    // MARK: Track list (pulled up)

    private func trackList(w: CGFloat) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                countLabel
                    .padding(.leading, 8)
                    .padding(.top, 2)
                    .padding(.bottom, 10)
                if let playlist {
                    ForEach(playlist.entries, id: \.track.id) { e in playlistRow(e) }
                    playlistMeta(playlist)
                        .padding(.horizontal, 8)
                        .padding(.top, 28)
                        .opacity(max(0, (smooth(p) - 0.6) / 0.4))
                } else {
                ForEach(album.tracks) { t in trackRow(t) }
                linerNotes
                    .padding(.horizontal, 8)
                    .padding(.top, 28)
                    .opacity(max(0, (smooth(p) - 0.6) / 0.4))
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 4)
        }
        .scrollPosition($listPosition)
        .contentMargins(.bottom, 112, for: .scrollContent)
        // The header's room as a bar, so the blur variant's soft scroll edge
        // runs under it (spectrum and glass cover it themselves).
        .scrollEdgeEffectStyle(.soft, for: .top)
        .scrollEdgeEffectHidden(topBar != 2, for: .top)
        .safeAreaBar(edge: .top) { AlbumScrapbookView.edgeBar(height: headerContent) }
        .scrollDisabled(p < 1)
        .frame(width: w)
    }

    private func trackRow(_ t: Track) -> some View {
        let playing = model.playback.track.id == t.id
        return HStack(spacing: 12) {
            Group {
                if playing {
                    YoinIcon("equalizer", size: 16).foregroundStyle(s.primary)
                } else {
                    Text("\(t.number)").monospacedDigit()
                        .yoinRole(Tokens.type.labelMedium)
                        .foregroundStyle(app.onSurfaceVariant)
                }
            }
            .frame(minWidth: 18, alignment: .trailing)
            Text(t.title)
                .lineLimit(1)
                .yoinRole(Tokens.type.titleMedium)
                .foregroundStyle(playing ? s.primary : app.onSurface)
                .frame(maxWidth: .infinity, alignment: .leading)
            Text(formatTime(t.durationSec))
                .monospacedDigit()
                .yoinRole(Tokens.type.labelLarge)
                .foregroundStyle(app.onSurfaceVariant)
            let liked = t.rating.map { $0 >= 9 } ?? false
            YoinIcon(liked ? "favorite_filled" : "favorite", size: 16)
                .foregroundStyle(liked ? s.onPrimary : app.onSurfaceVariant)
                .frame(width: 28, height: 28)
                .background {
                    if liked { Circle().fill(s.primary) }
                    else { Circle().strokeBorder(app.outline.opacity(0.5), lineWidth: 1.5) }
                }
                .frame(width: 36, height: 36)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 10)
        .frame(minHeight: 56)
        .contentShape(.rect(cornerRadius: Tokens.shape.LIST_ROW.cg))
    }

    // MARK: Playlist pieces

    /// Android DetailBackdrops stacked V: arm 1 (the upper V) three times,
    /// back to front tertiary / secondary / primary, scale = W × 1.3 / 674
    /// about the arm centre (505, 350), dy = side × (−0.06 + 0.14 i).
    private func stackedV(w: CGFloat, side: CGFloat, band: CGFloat) -> some View {
        Canvas { gc, size in
            let k = size.width * 1.3 / 674
            let colors = [s.tertiary, s.secondary, s.primary]
            for i in 0..<3 {
                var g = gc
                g.translateBy(x: size.width / 2, y: band / 2 + side * (-0.06 + 0.14 * CGFloat(i)))
                g.scaleBy(x: k, y: k)
                g.translateBy(x: -505, y: -350)
                let arm = YoinMarkPaths.arms[1]
                g.fill(arm.fill, with: .color(colors[i]))
                g.fill(arm.line, with: .color(app.surface.opacity(0.65)))
            }
        }
        // Laid out on the band, drawn past it: the front V's lip dips below
        // the band, as on Android (only the Box under the header clips).
        .frame(width: w, height: band * 1.6)
        .offset(y: headerContent)
        .frame(width: w, height: headerContent + band * 1.6, alignment: .topLeading)
        .mask(alignment: .top) { Rectangle().padding(.top, headerContent) }
        .allowsHitTesting(false)
    }

    /// PlaylistHeroMeta: Length (tracks bodyLarge, duration bodyMedium
    /// onSurfaceVariant) | Owner (bodyLarge, + Public / Private bodyMedium),
    /// 4 apart; then the description (bodyMedium, 3 lines) 6 under its label.
    /// Labels: AlbumSectionLabel (labelLarge Medium underlined).
    private func playlistMeta(_ pl: PlaylistInfo) -> some View {
        let mins = pl.entries.map(\.track.durationSec).reduce(0, +) / 60
        return VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 4) {
                    sectionLabel("Length")
                    Text("\(pl.entries.count) tracks").monospacedDigit()
                        .yoinRole(Tokens.type.bodyLarge).foregroundStyle(app.onSurface)
                    Text(mins >= 60 ? "\(mins / 60) h \(mins % 60) min" : "\(mins) min").monospacedDigit()
                        .yoinRole(Tokens.type.bodyMedium).foregroundStyle(app.onSurfaceVariant)
                }
                Spacer()
                VStack(alignment: .leading, spacing: 4) {
                    sectionLabel("Owner")
                    Text(pl.owner).lineLimit(1)
                        .yoinRole(Tokens.type.bodyLarge).foregroundStyle(app.onSurface)
                    if let isPublic = pl.isPublic {
                        Text(isPublic ? "Public" : "Private")
                            .yoinRole(Tokens.type.bodyMedium).foregroundStyle(app.onSurfaceVariant)
                    }
                }
            }
            if let d = pl.description {
                VStack(alignment: .leading, spacing: 6) {
                    sectionLabel("Description")
                    Text(d).lineLimit(3)
                        .lineSpacing(AlbumType.lineSpacing(Tokens.type.bodyMedium))
                        .font(.gsf(Tokens.type.bodyMedium.size.cg))
                        .foregroundStyle(app.onSurface)
                }
            }
        }
    }

    /// AlbumSectionLabel: labelLarge Medium, underlined, onSurface.
    private func sectionLabel(_ t: String) -> some View {
        Text(t).underline().yoinRole(Tokens.type.labelLarge).foregroundStyle(app.onSurface)
    }

    /// PlaylistTrackRow: 44 Thumb (corner 4), 12 gap; titleMedium onSurface
    /// over PlaylistTrackCredit (a bodySmall MetaLine: artist, then the album
    /// muted to 60 %, 12 apart, no separator), 2 apart; the duration in
    /// labelLarge tabular. Padding 8 × 8, no number, no heart.
    private func playlistRow(_ e: PlaylistEntry) -> some View {
        HStack(spacing: 12) {
            CoverArt(palette: e.album.palette, cornerRadius: 0, seed: e.album.seed)
                .frame(width: 44, height: 44)
                .clipShape(.rect(cornerRadius: Tokens.shape.THUMB.cg, style: .continuous))
            VStack(alignment: .leading, spacing: 2) {
                Text(e.track.title).lineLimit(1)
                    .yoinRole(Tokens.type.titleMedium)
                    .foregroundStyle(app.onSurface)
                LibraryMetaLine(parts: [(e.album.artist, false), (e.album.title, true)], scheme: app)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Text(formatTime(e.track.durationSec))
                .monospacedDigit()
                .yoinRole(Tokens.type.labelLarge)
                .foregroundStyle(app.onSurfaceVariant)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 8)
        .contentShape(.rect(cornerRadius: Tokens.shape.LIST_ROW.cg))
    }

    /// The list's liner notes (AlbumHeroMetaBlocks, default arrangement):
    /// Last Play (label, 4, day bodyLarge, 4, time bodyMedium tabular) and the
    /// emblem (+ "X/N rated") across one row, centred; the comment 14 below.
    private var linerNotes: some View {
        let kind: ScoreEmblem.Kind = album.rating != nil ? .album : .average
        return VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .center) {
                VStack(alignment: .leading, spacing: 0) {
                    sectionLabel("Last Play").padding(.bottom, 4)
                    Text("Yesterday").yoinRole(Tokens.type.bodyLarge).foregroundStyle(app.onSurface)
                    Text("16:04").monospacedDigit()
                        .yoinRole(Tokens.type.bodyMedium).foregroundStyle(app.onSurfaceVariant)
                        .padding(.top, 4)
                }
                Spacer()
                HStack(spacing: 10) {
                    ScoreEmblem(score: album.rating ?? album.averageRating ?? 0, kind: kind,
                                rated: album.tracks.map { $0.rating != nil }, palette: album.palette, size: 64)
                    if kind == .average { ratedCoverage }
                }
            }
            comment
        }
    }

    // MARK: Spectrum bar

    /// AlbumSpectrumBar drawSpectrum: each of the 28 strips starts as a slice
    /// of the cover and flies to its slot in the header bar (the shared
    /// spectrum's assignment: strips of one color land side by side)
    /// (q = smoothstep(0.06 + lag, 0.6 + lag, p), lag = 0.22 × slot / 28); the
    /// cover slice fades out as the flat locked color fades in over
    /// smoothstep(0, 0.55, q).
    private func spectrum(w: CGFloat, side: CGFloat, top: CGFloat) -> some View {
        let strips = AlbumSpectrum.strips(for: album)
        let n = AlbumSpectrum.strips
        let slice = side / CGFloat(n)
        let heroX = (w - side) / 2, heroY = headerContent + 28
        let barH = top + headerContent
        return ZStack(alignment: .topLeading) {
            ForEach(0..<n, id: \.self) { i in
                let lag = 0.22 * CGFloat(strips[i].slot) / CGFloat(n)
                let q = smooth(p, 0.06 + lag, 0.6 + lag)
                let flat = smooth(q, 0, 0.55)
                let x = lerp(heroX + CGFloat(i) * slice, CGFloat(strips[i].slot) * w / CGFloat(n), q)
                let y = lerp(heroY, -top, q)
                let sw = lerp(slice, w / CGFloat(n), q) + 1.5
                let sh = lerp(side, barH, q)
                ZStack(alignment: .topLeading) {
                    CoverArt(palette: album.palette, cornerRadius: 0, seed: album.seed)
                        .frame(width: side, height: side)
                        .offset(x: -CGFloat(i) * slice)
                        .frame(width: slice, height: side, alignment: .topLeading)
                        .clipped()
                        .scaleEffect(x: sw / slice, y: sh / side, anchor: .topLeading)
                        .frame(width: sw, height: sh, alignment: .topLeading)
                        .opacity(1 - flat)
                    Rectangle()
                        .fill(strips[i].color)
                        .frame(width: sw, height: sh)
                        .opacity(flat)
                }
                .frame(width: sw, height: sh, alignment: .topLeading)
                .clipped()
                .offset(x: x, y: y)
            }
        }
        .frame(width: w, alignment: .topLeading)
        .opacity(p > 0.001 ? 1 : 0)
        .allowsHitTesting(false)
    }

    // MARK: Pager

    /// Drags starting this close to the left edge belong to the system back swipe.
    private static let backEdge: CGFloat = 24

    private func pager(width: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 14)
            .updating($pagerLive) { _, live, _ in live = true }
            .onChanged { v in
                // Once engaged the page follows the finger 1:1; it engages on a
                // horizontal drag that doesn't start at the back edge and isn't
                // a pull-up. Page 0 dragged right is the system back swipe's job.
                let engaged = pageDrag != 0
                    || (abs(v.translation.width) > abs(v.translation.height) * 1.2
                        && v.startLocation.x > Self.backEdge && dragBase == nil
                        && !(page == 0 && v.translation.width > 0))
                guard engaged else { return }
                pageDrag = min(page * width, max((page - 1) * width, v.translation.width))
            }
            .onEnded { v in
                guard pageDrag != 0 else { return }
                let projected = page - v.predictedEndTranslation.width / width
                withAnimation(Yoin.spatial) {
                    page = projected > 0.5 ? 1 : 0
                    pageDrag = 0
                }
            }
    }

    // MARK: Pull-up gesture (DetailPullUpReshape)

    private func pullUp(travel: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 14)
            .onChanged { v in
                guard page == 0, pageDrag == 0, abs(v.translation.height) > abs(v.translation.width) else { return }
                if dragBase == nil {
                    // Collapsed: the hero takes the drag. Expanded: only a pull-down.
                    if p >= 1 && v.translation.height < 0 { return }
                    dragBase = p
                }
                p = min(1, max(0, (dragBase ?? 0) - v.translation.height / travel))
            }
            .onEnded { v in
                guard let base = dragBase else { return }
                _ = base
                dragBase = nil
                let velocity = -(v.predictedEndTranslation.height - v.translation.height) / travel / 0.25
                let expand = velocity >= 1.6 ? true : velocity <= -1.6 ? false : p > 0.5
                withAnimation(Yoin.spatial) { p = expand ? 1 : 0 }
            }
    }

    // MARK: Math

    private func smooth(_ x: CGFloat, _ a: CGFloat = 0, _ b: CGFloat = 1) -> CGFloat {
        let t = min(1, max(0, (x - a) / (b - a)))
        return t * t * (3 - 2 * t)
    }

    private func lerp(_ a: CGFloat, _ b: CGFloat, _ t: CGFloat) -> CGFloat { a + (b - a) * t }

    private func lerpRect(_ a: CGRect, _ b: CGRect, _ t: CGFloat) -> CGRect {
        CGRect(x: lerp(a.minX, b.minX, t), y: lerp(a.minY, b.minY, t),
               width: lerp(a.width, b.width, t), height: lerp(a.height, b.height, t))
    }
}

/// Edge-swipe back keeps working on pushed pages that hide the navigation
/// bar, and always wins: the page's own drags (the album pager, SwiftUI drag
/// gestures, scroll views) wait for it to fail, which it does at once for any
/// touch that doesn't start at the edge.
extension UINavigationController: @retroactive UIGestureRecognizerDelegate {
    override open func viewDidLoad() {
        super.viewDidLoad()
        interactivePopGestureRecognizer?.delegate = self
    }

    public func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        viewControllers.count > 1
    }

    public func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                                  shouldBeRequiredToFailBy other: UIGestureRecognizer) -> Bool {
        gestureRecognizer === interactivePopGestureRecognizer && viewControllers.count > 1
            && other.view?.isDescendant(of: view) == true
    }
}

/// Line boxes for wrapping text in Compose's type roles. SwiftUI's
/// `lineSpacing` adds to the font's own line (Google Sans Flex: 1.252 × size),
/// so the role's line height minus that is the extra.
enum AlbumType {
    static let gsfNaturalLine: CGFloat = 1.252

    static func lineSpacing(size: CGFloat, lineHeight: CGFloat) -> CGFloat {
        max(0, lineHeight - size * gsfNaturalLine)
    }

    static func lineSpacing(_ role: YoinTokens.TypographyStyle) -> CGFloat {
        lineSpacing(size: role.size.cg, lineHeight: role.lineHeight.cg)
    }
}
