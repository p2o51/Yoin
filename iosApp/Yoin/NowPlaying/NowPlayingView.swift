import SwiftUI

/// Now Playing, ported from Android's phone layout (`CompactPlayingContent` in
/// NowPlayingScreen.kt, heights from NowPlayingBudget.kt, transport from
/// PlaybackControls.kt, pills from BottomPills.kt). Every number is cited in
/// iosApp/docs/fidelity-home-np.md.
///
/// iOS adaptations: the page grows out of the bar's pill; its background is
/// frosted glass over the current, previous and next albums' colors; the
/// transport buttons and the heart are tinted Liquid Glass, and the lyrics run
/// on underneath them. Drag down or tap ⌄ to collapse.
struct NowPlayingView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var colorScheme
    var npNamespace: Namespace.ID
    /// iPad side panel: no sheet morph, no drag-to-dismiss.
    var panel = false

    @State private var dragY: CGFloat = 0
    @State private var tab = 0
    @State private var rating: Double = 6.3
    @State private var favorite = true
    @State private var shown = false

    private var palette: CoverPalette { model.playback.album.palette }
    private var scheme: CoverScheme { CoverScheme(album: model.playback.album, dark: colorScheme == .dark) }
    /// Full page. The radius stays under the iPhone's screen-corner radius
    /// (62 let Home peek out at the bottom corners); the display mask rounds
    /// the rest.
    private var baseShape: RoundedRectangle {
        RoundedRectangle(cornerRadius: lab.style("npBackground").cornerRadius ?? 44, style: .continuous)
    }
    private var lab: GlassLab { GlassLab.shared }
    private var brand: CoverScheme { .brand(dark: colorScheme == .dark) }
    // Glass parameters live in GlassLab (debug panel); its defaults are the
    // shipped values: transport/heart tint 55 %, colored shadow 18 %, glyph
    // contrast shadow 22 %, container spacing 3.

    var body: some View {
        // The background sits outside the GeometryReader so it reaches the
        // screen's bottom edge (inside, it stopped at the home indicator inset).
        ZStack {
            if panel {
                pageGradient.ignoresSafeArea()
            } else {
                base
            }
            GeometryReader { geo in
                content(geo)
                    .frame(maxWidth: model.isPad ? 520 : .infinity)
                    .frame(width: geo.size.width, height: geo.size.height)
                    .opacity(shown ? 1 : 0)
            }
        }
        .offset(y: max(0, dragY))
        .gesture(dismissDrag, isEnabled: !panel)
        .onAppear {
            let anim: Animation = model.slowMorph ? .easeIn(duration: 2).delay(4.5) : Yoin.effects.delay(0.08)
            withAnimation(anim) { shown = true }
        }
    }

    // MARK: Background (iOS)

    /// Android page background: vertical gradient surfaceContainer → background
    /// (NowPlayingAuroraBackground.kt:402). Used by the iPad panel.
    private var pageGradient: LinearGradient {
        LinearGradient(colors: [scheme.surfaceContainer, scheme.background], startPoint: .top, endPoint: .bottom)
    }

    /// Frosted glass over the albums around this one: previous album's color
    /// at the top edge, next album's at the bottom, this cover in the middle.
    private var base: some View {
        ZStack {
            neighbourAmbient.clipShape(baseShape)
            baseShape.fill(.clear)
                .labGlass("npBackground", scheme: scheme, brand: brand, auto: scheme.background, in: baseShape)
        }
        .matchedGeometryEffect(id: "np.surface", in: npNamespace)
        .ignoresSafeArea()
    }

    /// Colors come from each album's own scheme (Palette seed → MCU
    /// SchemeExpressive): this one's primary in the middle, the previous
    /// album's at the top edge, the next album's at the bottom.
    private var neighbourAmbient: some View {
        let albums = model.library.albums()
        let dark = colorScheme == .dark
        let previous = CoverScheme(album: albums[11], dark: dark)
        let next = CoverScheme(album: albums[1], dark: dark)
        return ZStack {
            scheme.background
            Circle().fill(scheme.primary.opacity(lab.extra("ambientStrength")))
                .frame(width: 360).blur(radius: lab.extra("ambientBlur"))
            VStack {
                Ellipse().fill(previous.primaryContainer.opacity(lab.extra("neighbourStrength"))).frame(height: 300).offset(y: -90).blur(radius: 50)
                Spacer()
                Ellipse().fill(next.primaryContainer.opacity(lab.extra("neighbourStrength"))).frame(height: 320).offset(y: 110).blur(radius: 50)
            }
        }
    }

    // MARK: Content (Android CompactPlayingContent)

    private func content(_ geo: GeometryProxy) -> some View {
        let p = model.playback
        let budget = NowPlayingBudget(
            width: min(geo.size.width, model.isPad ? 520 : geo.size.width),
            height: geo.size.height - max(geo.safeAreaInsets.bottom, 16) + geo.safeAreaInsets.bottom,
            forceShort: model.npForceShort
        )
        return VStack(alignment: .leading, spacing: 0) {
            topBar(p)
                .frame(height: budget.topBar)
            coverRow(budget.cover)
            Spacer().frame(height: budget.coverGap)
            if !budget.oneLineLyric {
                tabs
                Spacer().frame(height: 4)
            }
            // The lyrics run on under the glass transport; the current line
            // always sits in the clear band above it.
            let clear = budget.oneLineLyric ? 44 : budget.lyricWindow
            ZStack(alignment: .top) {
                lyrics(p, clear: clear, under: budget.transport,
                       anchor: budget.oneLineLyric ? 4 : clear * 0.22, fadeTop: !budget.oneLineLyric)
                transport(p)
                    .padding(.top, clear)
            }
            .frame(height: clear + budget.transport, alignment: .top)
            if budget.slack > 0 {
                Spacer().frame(height: budget.slack)
            }
            hero(p, inline: budget.inlineHero)
                .frame(height: budget.hero, alignment: budget.inlineHero ? .bottom : .top)
            pills
                .frame(height: budget.pills, alignment: .bottomLeading)
        }
        .padding(.horizontal, 24)
        .frame(maxHeight: .infinity, alignment: .top)
    }

    // 1. Top bar: ⌄ + "PLAYING FROM ALBUM" / album name.
    private func topBar(_ p: PlaybackSnapshot) -> some View {
        HStack(spacing: 4) {
            Button { collapse() } label: {
                YoinIcon("chevron_down", size: 28)
                    .foregroundStyle(scheme.onSurfaceVariant)
                    .frame(width: 48, height: 48)
                    .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Collapse Now Playing")
            VStack(alignment: .leading, spacing: 0) {
                Text("PLAYING FROM ALBUM")
                    .font(.gsf(11, .medium))
                    .tracking(0.1)
                    .foregroundStyle(scheme.onSurfaceVariant.opacity(0.55))
                Text(p.album.title)
                    .font(.gsf(12, .medium))
                    .tracking(0.1)
                    .foregroundStyle(scheme.onSurface)
            }
            .lineLimit(1)
            .padding(.vertical, 2)
            .padding(.horizontal, 4)
            Spacer(minLength: 0)
        }
        // Android: page padding 24 + 4 at the start, and the 48 dp icon button
        // centers its 28 dp glyph, so the glyph starts 10 dp further in.
        .padding(.leading, 4)
    }

    // 2. Cover + rating column.
    private func coverRow(_ cover: CGFloat) -> some View {
        let p = model.playback
        return HStack(alignment: .top, spacing: 12) {
            CoverArt(palette: p.album.palette, cornerRadius: 0, seed: p.album.seed)
                .frame(width: cover, height: cover)
                // ContinuousRoundedCornerShape(16dp), smoothing 0.6 ≈ Apple's continuous corners.
                .clipShape(.rect(cornerRadius: 16, style: .continuous))
                .matchedGeometryEffect(id: panel ? "np.cover.panel" : "np.cover", in: npNamespace)
            VStack(spacing: 0) {
                RatingBar(value: $rating, scheme: scheme)
                    .frame(width: 48, height: max(0, cover - 58))
                Spacer().frame(height: 8)
                heart
                Spacer().frame(height: 6)
            }
            .frame(width: 56)
        }
    }

    private var heart: some View {
        Button { withAnimation(Yoin.spatialFast) { favorite.toggle() } } label: {
            YoinIcon(favorite ? "favorite_filled" : "favorite", size: 24)
                .foregroundStyle(favorite ? scheme.onTertiary : scheme.onTertiaryContainer)
                .frame(width: 44, height: 44)
                .contentShape(.circle)
        }
        .buttonStyle(.plain)
        .labGlass("heart", scheme: scheme, brand: brand,
                  auto: favorite ? scheme.tertiary : scheme.tertiaryContainer, in: Circle())
        .accessibilityLabel(favorite ? "Remove from favorites" : "Add to favorites")
    }

    // 4. Text tabs.
    private var tabs: some View {
        HStack(spacing: 12) {
            ForEach(Array(["Lyrics", "About", "Note"].enumerated()), id: \.offset) { i, title in
                let on = tab == i
                Button { withAnimation(Yoin.spatialFast) { tab = i } } label: {
                    Text(title)
                        .font(.gsf(14, on ? .bold : .medium))
                        .foregroundStyle(scheme.onSurfaceVariant.opacity(on ? 1 : 0.5))
                        .scaleEffect(x: on ? 1.08 : 1, y: 1, anchor: .leading)
                }
                .buttonStyle(.plain)
            }
        }
        .frame(height: 30, alignment: .top)
    }

    // 5. Lyrics. Android: a list with the active line at 22 % of the window
    // (or one line on short screens). iOS: the list continues `under` points
    // further down, behind the glass transport, fading out by its second row.
    private func lyrics(_ p: PlaybackSnapshot, clear: CGFloat, under: CGFloat,
                        anchor: CGFloat, fadeTop: Bool) -> some View {
        let total = clear + under
        return VStack(alignment: .leading, spacing: 2) {
            ForEach(Array(p.lyrics.enumerated()), id: \.offset) { i, line in
                let d = abs(i - p.activeLyric)
                let active = d == 0
                Text(line)
                    .font(active ? YoinFont.rounded(16, semibold: true) : .gsf(14))
                    .foregroundStyle(active ? scheme.primary : scheme.onSurfaceVariant)
                    .opacity(active ? 1 : (d == 1 ? 0.55 : d == 2 ? 0.40 : 0.28))
                    .scaleEffect(active ? 1 : 0.94, anchor: .leading)
                    .lineLimit(1)
                    .padding(.vertical, 4)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        // Lines are ≈ 24 + 8 + 2 tall; scroll so the active line's top sits at `anchor`.
        .offset(y: anchor - 4 - CGFloat(p.activeLyric) * 30)
        .frame(height: total, alignment: .top)
        .clipped()
        .mask(
            LinearGradient(stops: [
                .init(color: fadeTop ? .clear : .black, location: 0),
                .init(color: .black, location: fadeTop ? min(0.12, 16 / total) : 0),
                .init(color: .black, location: clear / total),
                .init(color: .black.opacity(GlassLab.shared.extra("lyricsUnder")), location: (clear + 56) / total),
                .init(color: .clear, location: (clear + 100) / total),
            ], startPoint: .top, endPoint: .bottom)
        )
        .allowsHitTesting(false)
    }

    // 6. Transport: [PAUSE][Next] … [Repeat] / [Prev] 1:14 ~~~ -2:41
    private func transport(_ p: PlaybackSnapshot) -> some View {
        // One glass layer: Pause, Next and Prev sit 4–8 pt apart. A 3 pt
        // container spacing keeps them separate at rest (10 bridged Pause and
        // Prev into one blob) and lets neighbours blend as interactive glass
        // swells on press.
        GlassEffectContainer(spacing: lab.extra("containerSpacing")) {
        VStack(spacing: 4) {
            HStack(spacing: 8) {
                Button {} label: {
                    Text("PAUSE")
                        .font(.gsf(19.8, .bold))
                        .tracking(0.5)
                        .scaleEffect(x: 1.06, y: 1)
                        .foregroundStyle(scheme.onPrimary)
                        .labContrast("transport")
                        .padding(.horizontal, 24)
                        .frame(height: 56)
                        .contentShape(.capsule)
                }
                .buttonStyle(.plain)
                .labGlass("transport", scheme: scheme, brand: brand, auto: scheme.primary, in: Capsule())
                .accessibilityLabel("Pause")
                roundButton("skip_next_filled", fill: scheme.primary, ink: scheme.onPrimary, label: "Next")
                Spacer(minLength: 0)
                roundButton("repeat", fill: scheme.tertiaryContainer, ink: scheme.onTertiaryContainer, label: "Repeat")
            }
            .frame(height: 56)
            HStack(spacing: 0) {
                roundButton("skip_previous_filled", fill: scheme.secondary, ink: scheme.onSecondary, label: "Previous")
                Spacer().frame(width: 8)
                Text(formatTime(p.positionSec))
                    .frame(minWidth: 44, alignment: .leading)
                    .offset(y: 6)
                Spacer().frame(width: 8)
                WavyProgressBar(progress: Double(p.positionSec) / Double(p.track.durationSec),
                                duration: TimeInterval(p.track.durationSec), isPlaying: p.isPlaying,
                                tint: scheme.primary, trackTint: scheme.surfaceContainerHighest) { _ in }
                    .frame(height: 40, alignment: .bottom)
                Spacer().frame(width: 8)
                Text("-" + formatTime(p.track.durationSec - p.positionSec))
                    .frame(minWidth: 52, alignment: .trailing)
                    .offset(y: 6)
            }
            .font(.gsf(14, .medium))
            .monospacedDigit()
            .foregroundStyle(scheme.onSurfaceVariant)
            .frame(height: 56)
        }
        }
    }

    private func roundButton(_ icon: String, fill: Color, ink: Color, label: String) -> some View {
        Button {} label: {
            YoinIcon(icon, size: 28)
                .foregroundStyle(ink)
                .labContrast("transport")
                .frame(width: 56, height: 56)
                .contentShape(.circle)
        }
        .buttonStyle(.plain)
        .labGlass("transport", scheme: scheme, brand: brand, auto: fill, in: Circle())
        .accessibilityLabel(label)
    }

    // 7. Hero: stacked (artist over title) or inline on short screens.
    @ViewBuilder
    private func hero(_ p: PlaybackSnapshot, inline: Bool) -> some View {
        if inline {
            HStack(alignment: .firstTextBaseline, spacing: 12) {
                Text(p.track.title)
                    .font(.gsf(36))
                    .foregroundStyle(scheme.onSurface)
                    .scaleEffect(x: 1.03, y: 1, anchor: .leading)
                    .layoutPriority(1)
                Text(p.album.artist)
                    .font(.gsf(14.4, .medium))
                    .foregroundStyle(scheme.onSurfaceVariant)
            }
            .lineLimit(1)
            .padding(.bottom, 12)
        } else {
            VStack(alignment: .leading, spacing: 2) {
                Text(p.album.artist)
                    .font(.gsf(14.4, .medium))
                    .foregroundStyle(scheme.onSurfaceVariant)
                    .frame(height: 24, alignment: .leading)
                    .scaleEffect(x: 1.02, y: 1, anchor: .leading)
                Text(p.track.title)
                    .font(.gsf(36))
                    .foregroundStyle(scheme.onSurface)
                    .frame(height: 44, alignment: .leading)
                    .scaleEffect(x: 1.03, y: 1, anchor: .leading)
            }
            .lineLimit(1)
        }
    }

    // 8. Bottom pills: Queue · Devices · Write.
    private var pills: some View {
        HStack(spacing: 2) {
            pill("queue", "Queue", shape: AnyShape(Capsule()))
            pill("devices", "Devices", shape: AnyShape(RoundedRectangle(cornerRadius: 20)))
            pill("note", "Write", shape: AnyShape(Capsule()))
        }
        .padding(.bottom, 8)
    }

    private func pill(_ icon: String, _ title: String, shape: AnyShape) -> some View {
        Button {} label: {
            HStack(spacing: 4) {
                YoinIcon(icon, size: 15.8)
                Text(title).font(.gsf(12, .medium)).tracking(0.1)
            }
            .foregroundStyle(scheme.onPrimary)
            .padding(.horizontal, 13.2)
            .frame(minWidth: 58, minHeight: 44)
            .labGlass("npPills", scheme: scheme, brand: brand, auto: scheme.primary, in: shape)
        }
        .buttonStyle(PressScale())
    }

    // MARK: Gesture

    private func collapse() {
        if panel {
            withAnimation(Yoin.spatial) { model.npPanel = false }
        } else {
            model.setNowPlaying(expanded: false)
        }
    }

    private var dismissDrag: some Gesture {
        DragGesture(minimumDistance: 12)
            .onChanged { v in
                // 1:1 down, rubber-band up.
                let t = v.translation.height
                dragY = t > 0 ? t : -pow(-t, 0.7)
            }
            .onEnded { v in
                if v.translation.height > 140 || v.predictedEndTranslation.height > 380 {
                    model.setNowPlaying(expanded: false)
                    dragY = 0
                } else {
                    withAnimation(Yoin.spatial) { dragY = 0 }
                }
            }
    }
}

/// Android NowPlayingBudget (phone): nominal → minimum slot heights, lower
/// gaps squeezed first, then upper; inline hero, then the one-line lyric when
/// two clean lyric lines still don't fit.
struct NowPlayingBudget {
    var topBar: CGFloat = 56
    var cover: CGFloat
    var coverGap: CGFloat = 16
    var transport: CGFloat = 148
    var hero: CGFloat = 86
    var pills: CGFloat = 68
    var lyricWindow: CGFloat = 0
    var inlineHero = false
    var oneLineLyric = false
    /// Forced short layout on a taller screen: the height Android wouldn't have.
    var slack: CGFloat = 0

    /// `height` = the column's height: from below the status bar to
    /// max(home indicator, 16) above the bottom.
    init(width: CGFloat, height: CGFloat, forceShort: Bool) {
        cover = min(width - 48 - 12 - 56, 312)
        let tabs: CGFloat = 30 + 4
        let twoLines: CGFloat = 12 * 2 + 2 * 32 + 2
        if forceShort {
            // What a ~700 dp tall Android phone resolves to (the reference shots):
            // every gap at its minimum, inline hero, one-line lyric.
            topBar = 48; coverGap = 8; transport = 124; hero = 56; pills = 52
            inlineHero = true; oneLineLyric = true
            cover = min(cover, height - (topBar + coverGap + 44 + transport + hero + pills))
            slack = max(0, height - (topBar + cover + coverGap + 44 + transport + hero + pills))
            return
        }
        var left: CGFloat { height - (topBar + cover + coverGap + tabs + transport + hero + pills) }
        if left < twoLines {
            // 1) lower gaps on one shared fraction, 2) then the upper ones.
            let f = min(1, (twoLines - left) / ((148 - 124) + (86 - 74) + (68 - 52)))
            transport -= 24 * f; hero -= 12 * f; pills -= 16 * f
        }
        if left < twoLines {
            let g = min(1, (twoLines - left) / 16)
            topBar -= 8 * g; coverGap -= 8 * g
        }
        if left < twoLines { inlineHero = true; hero = 56 }
        if left < twoLines {
            oneLineLyric = true
            cover = max(0, cover - (44 - tabs - left))
        }
        lyricWindow = oneLineLyric ? 0 : left
    }
}

/// Android RatingSlider: 48 dp capsule, primary fill from the bottom, number
/// (titleLarge, tabular) at the top in onSurfaceVariant, onPrimary once the
/// fill passes 80 % of the height.
struct RatingBar: View {
    @Binding var value: Double
    var scheme: CoverScheme

    var body: some View {
        GeometryReader { geo in
            let h = geo.size.height
            let fraction = value / 10
            ZStack(alignment: .top) {
                // Track: glass tinted with the container role, so the
                // background shows through; the fill (the score) stays solid.
                Capsule().fill(.clear)
                    .labGlass("ratingTrack", scheme: scheme, brand: .brand(dark: false),
                              auto: scheme.surfaceVariant, in: Capsule())
                Capsule()
                    .fill(scheme.primary.opacity(GlassLab.shared.extra("ratingFill")))
                    .frame(height: max(48, h * fraction))
                    .frame(maxHeight: .infinity, alignment: .bottom)
                Text(value >= 10 ? "10" : String(format: "%.1f", value))
                    .font(.gsf(22))
                    .monospacedDigit()
                    .foregroundStyle(fraction > 0.8 ? scheme.onPrimary : scheme.onSurfaceVariant)
                    .frame(minWidth: 36)
                    .padding(.top, 8)
            }
            .contentShape(.capsule)
            .gesture(
                DragGesture(minimumDistance: 0).onChanged { v in
                    let raw = Double((1 - v.location.y / h) * 10)
                    value = (min(10.0, max(0.0, raw)) * 10).rounded() / 10
                }
            )
            .animation(Yoin.spatialFast, value: value)
        }
        .accessibilityElement()
        .accessibilityLabel("Rating")
        .accessibilityValue(String(format: "%.1f", value))
    }
}
