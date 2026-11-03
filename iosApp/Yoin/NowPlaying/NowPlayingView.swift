import SwiftUI
import YoinShared

/// Now Playing, ported from Android's phone layout (`CompactPlayingContent` in
/// NowPlayingScreen.kt, heights from NowPlayingBudget.kt, transport from
/// PlaybackControls.kt, pills from BottomPills.kt). Every number is cited in
/// iosApp/docs/fidelity-home-np.md.
///
/// iOS adaptations: the page grows out of the bar's pill; its background is
/// frosted glass over the current, previous and next albums' colors; the
/// transport buttons and the heart are tinted Liquid Glass, and the lyrics run
/// on underneath them. Drag down or tap ⌄ to collapse.
///
/// Open / close morph (phone): the shell mounts this view at the pill's
/// frames with `open == false`, then springs `open` on; closing springs it
/// off and unmounts when the spring lands. The surface, cover and title are
/// matched to the pill (whoever is "open" is the geometry source), so they
/// grow out of it and shrink back into it; the rest fades.
struct NowPlayingView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var colorScheme
    var npNamespace: Namespace.ID
    /// iPad side panel: no sheet morph, no drag-to-dismiss.
    var panel = false
    /// Morph target: true = the full page, false = folded into the bar's pill.
    var open = true

    @State private var dragY: CGFloat = 0
    @State private var tab = 0
    @State private var rating: Double = 6.3
    @State private var favorite = true
    /// One "hands on the controls" state for rating, heart, transport and
    /// the progress bar: while it is on, the progress bar sits in glass.
    /// `-screen np-touch` starts with it (and a held rating) on.
    @State private var interacting = LaunchConfig.baseScreen == "np-touch"
    @State private var ratingHeld = LaunchConfig.baseScreen == "np-touch"
    @State private var touching: Set<String> = []
    @State private var release: Task<Void, Never>?

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
            }
        }
        .offset(y: max(0, dragY))
        .gesture(dismissDrag, isEnabled: !panel)
    }

    // MARK: Morph

    /// Surface, cover and title: in quickly on open; on close they hold
    /// until they are nearly pill-sized, then hand over to the pill.
    private var presenceFade: Animation {
        if model.slowMorph { return open ? .easeOut(duration: 1) : .easeIn(duration: 1.5).delay(4.2) }
        return open ? .spring(response: 0.18, dampingFraction: 1) : Yoin.effects.delay(0.12)
    }

    /// Everything else: in once the page has room, out at once on close.
    private var contentFade: Animation {
        if model.slowMorph { return open ? .easeIn(duration: 2).delay(3.5) : .easeOut(duration: 1) }
        return open ? Yoin.effects.delay(0.08) : .spring(response: 0.2, dampingFraction: 1)
    }

    /// Pill title (15 pt) over the hero title (36 pt).
    private static let titleFoldScale: CGFloat = 15 / 36

    // MARK: Interaction

    /// A control reports a finger down / up. The glass progress bar stays
    /// for a second after the last finger lifts.
    private func touch(_ source: String, _ down: Bool) {
        release?.cancel()
        if down {
            touching.insert(source)
            if !interacting { withAnimation(Yoin.spatial) { interacting = true } }
        } else {
            touching.remove(source)
            guard touching.isEmpty else { return }
            release = Task { @MainActor in
                try? await Task.sleep(for: .seconds(1))
                guard !Task.isCancelled, touching.isEmpty else { return }
                withAnimation(Yoin.spatial) { interacting = false }
            }
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
        .matchedGeometryEffect(id: "np.surface", in: npNamespace, isSource: open)
        .npFade(open, presenceFade)
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
                .npFade(open, contentFade)
            coverRow(budget.cover)
            Spacer().frame(height: budget.coverGap)
            if !budget.oneLineLyric {
                tabs
                    .frame(height: budget.tabs, alignment: .top)
                    .npFade(open, contentFade)
                Spacer().frame(height: budget.tabGap)
            }
            // The lyrics run on under the glass transport; the current line
            // always sits in the clear band above it.
            let clear = budget.lyricWindow
            ZStack(alignment: .top) {
                lyrics(p, clear: clear, under: budget.transport,
                       anchor: budget.oneLineLyric ? 4 : clear * 0.22, fadeTop: !budget.oneLineLyric)
                transport(p)
                    .padding(.top, clear)
            }
            .frame(height: clear + budget.transport, alignment: .top)
            .npFade(open, contentFade)
            if budget.slack > 0 {
                Spacer().frame(height: budget.slack)
            }
            hero(p, inline: budget.inlineHero)
                .frame(height: budget.hero, alignment: budget.inlineHero ? .bottom : .top)
            pills
                .frame(height: budget.pills, alignment: .bottomLeading)
                .npFade(open, contentFade)
        }
        .padding(.horizontal, Tokens.np.COLUMN_PADDING.cg)
        .frame(maxHeight: .infinity, alignment: .top)
    }

    // 1. Top bar: ⌄ + "PLAYING FROM ALBUM" / album name.
    private func topBar(_ p: PlaybackSnapshot) -> some View {
        HStack(spacing: 4) {
            // A glass circle (the transport's secondary glass), so it reads
            // as a control over the page; it rides along with the drag.
            Button { collapse() } label: {
                YoinIcon("chevron_down", size: 26)
                    .foregroundStyle(scheme.onSurface)
                    .labContrast("transportSecondary")
                    .frame(width: 44, height: 44)
                    .contentShape(.circle)
            }
            .buttonStyle(.plain)
            .labGlass("transportSecondary", scheme: scheme, brand: brand,
                      auto: scheme.surfaceContainerHighest, in: Circle())
            .frame(width: 48, height: 48)
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
        return HStack(alignment: .top, spacing: Tokens.np.RATING_GAP.cg) {
            CoverArt(palette: p.album.palette, cornerRadius: 0, seed: p.album.seed)
                .frame(width: cover, height: cover)
                // ContinuousRoundedCornerShape(16dp), smoothing 0.6 ≈ Apple's continuous corners.
                // Folded, it takes the pill cover's 42 pt frame and 6 pt corners.
                .clipShape(.rect(cornerRadius: open ? Tokens.shape.NOW_PLAYING_COVER.cg : 6, style: .continuous))
                .matchedGeometryEffect(id: panel ? "np.cover.panel" : "np.cover", in: npNamespace, isSource: open)
                .npFade(open, presenceFade)
            VStack(spacing: 0) {
                RatingBar(value: $rating, scheme: scheme, held: ratingHeld) { down in
                    withAnimation(Yoin.spatialFast) { ratingHeld = down }
                    touch("rating", down)
                }
                .frame(width: Tokens.np.RATING_BAR_WIDTH.cg, height: max(0, cover - Tokens.np.RATING_BAR_INSET.cg))
                Spacer().frame(height: Tokens.np.RATING_TO_HEART_GAP.cg)
                heart
                Spacer().frame(height: Tokens.np.HEART_BOTTOM_GAP.cg)
            }
            .frame(width: Tokens.np.PHONE_RATING_COLUMN.cg)
            .npFade(open, contentFade)
        }
    }

    private var heart: some View {
        Button { withAnimation(Yoin.spatialFast) { favorite.toggle() } } label: {
            YoinIcon(favorite ? "favorite_filled" : "favorite", size: 24)
                // Untinted heart (Balanced / HIG): "liked" is a colored glyph.
                .foregroundStyle(GlassLab.shared.style("heart").tintOn
                                 ? (favorite ? scheme.onTertiary : scheme.onTertiaryContainer)
                                 : (favorite ? scheme.tertiary : scheme.onSurfaceVariant))
                .frame(width: Tokens.np.HEART_SIZE.cg, height: Tokens.np.HEART_SIZE.cg)
                .contentShape(.circle)
        }
        .buttonStyle(PressReport { touch("heart", $0) })
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
        .frame(height: Tokens.np.TAB_ROW_NOMINAL.cg, alignment: .top)
    }

    // 5. Lyrics. Android: a list with the active line at 22 % of the window
    // (or one line on short screens). iOS: the list continues `under` points
    // further down, behind the glass transport, fading out by its second row.
    private func lyrics(_ p: PlaybackSnapshot, clear: CGFloat, under: CGFloat,
                        anchor: CGFloat, fadeTop: Bool) -> some View {
        let total = clear + under
        let underStrength = GlassLab.shared.extra("lyricsUnder")
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
                // lyricsUnder 0.7 = lyrics run on under the buttons; 0 = they
                // fade out above the transport row (Balanced / HIG presets).
                .init(color: .black, location: max(0, clear - 24) / total),
                .init(color: .black.opacity(min(1, underStrength / 0.7)), location: clear / total),
                .init(color: .black.opacity(underStrength), location: (clear + 56) / total),
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
                        .labContrast("transportPrimary")
                        .padding(.horizontal, 24)
                        .frame(height: 56)
                        .contentShape(.capsule)
                }
                .buttonStyle(PressReport { touch("pause", $0) })
                .labGlass("transportPrimary", scheme: scheme, brand: brand, auto: scheme.primary, in: Capsule())
                .accessibilityLabel("Pause")
                roundButton("skip_next_filled", fill: scheme.primary, ink: scheme.onPrimary, label: "Next")
                Spacer(minLength: 0)
                roundButton("repeat", fill: scheme.tertiaryContainer, ink: scheme.onTertiaryContainer, label: "Repeat")
            }
            .frame(height: 56)
            HStack(spacing: 0) {
                roundButton("skip_previous_filled", fill: scheme.secondary, ink: scheme.onSecondary, label: "Previous")
                Spacer().frame(width: 8)
                progress(p)
            }
            .font(.gsf(14, .medium))
            .monospacedDigit()
            .foregroundStyle(scheme.onSurfaceVariant)
            .frame(height: 56)
        }
        }
    }

    /// 1:14 ~~~ -2:41. While any control is touched it morphs into glass:
    /// a capsule grows out of the bar's line to hold the times and the bar
    /// (iOS 26 Music's scrubber), and shrinks back a second after release.
    /// The 10 pt inset is constant, so the bar never changes width under
    /// the finger.
    private func progress(_ p: PlaybackSnapshot) -> some View {
        HStack(spacing: 0) {
            Text(formatTime(p.positionSec))
                .frame(minWidth: 40, alignment: .leading)
                .offset(y: 6)
            Spacer().frame(width: 6)
            WavyProgressBar(progress: Double(p.positionSec) / Double(p.track.durationSec),
                            duration: TimeInterval(p.track.durationSec), isPlaying: p.isPlaying,
                            tint: scheme.primary, trackTint: scheme.surfaceContainerHighest,
                            onScrubbing: { touch("progress", $0) }) { _ in }
                .frame(height: 40, alignment: .bottom)
            Spacer().frame(width: 6)
            Text("-" + formatTime(p.track.durationSec - p.positionSec))
                .frame(minWidth: 48, alignment: .trailing)
                .offset(y: 6)
        }
        .padding(.horizontal, 10)
        // The row itself carries the glass (as glass content the times and
        // the bar draw over it; a background glass in the transport's
        // container would be lifted above them). The shape does the morph.
        .npGlass("ratingTrack", scheme: scheme, auto: scheme.surfaceVariant, held: true,
                 in: ScrubberGlassShape(grow: interacting ? 1 : 0))
    }

    private func roundButton(_ icon: String, fill: Color, ink: Color, label: String) -> some View {
        // Untinted (Balanced / HIG presets): the glyph goes on-surface.
        let tinted = GlassLab.shared.style("transportSecondary").tintOn
        return Button {} label: {
            YoinIcon(icon, size: 28)
                .foregroundStyle(tinted ? ink : scheme.onSurface)
                .labContrast("transportSecondary")
                .frame(width: 56, height: 56)
                .contentShape(.circle)
        }
        .buttonStyle(PressReport { touch(label, $0) })
        .labGlass("transportSecondary", scheme: scheme, brand: brand, auto: fill, in: Circle())
        .accessibilityLabel(label)
    }

    // 7. Hero: stacked (artist over title) or inline on short screens.
    @ViewBuilder
    private func hero(_ p: PlaybackSnapshot, inline: Bool) -> some View {
        if inline {
            HStack(alignment: .firstTextBaseline, spacing: 12) {
                heroTitle(p)
                    .layoutPriority(1)
                Text(p.album.artist)
                    .font(.gsf(14.4, .medium))
                    .foregroundStyle(scheme.onSurfaceVariant)
                    .npFade(open, contentFade)
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
                    .npFade(open, contentFade)
                heroTitle(p)
                    .frame(height: 44, alignment: .leading)
            }
            .lineLimit(1)
        }
    }

    /// The title is matched to the pill's title by its leading edge; folded,
    /// it scales down to the pill's 15 pt so the glyphs line up.
    private func heroTitle(_ p: PlaybackSnapshot) -> some View {
        Text(p.track.title)
            .font(.gsf(36))
            .foregroundStyle(scheme.onSurface)
            .scaleEffect(x: 1.03, y: 1, anchor: .leading)
            .scaleEffect(open ? 1 : Self.titleFoldScale, anchor: .leading)
            .matchedGeometryEffect(id: panel ? "np.title.panel" : "np.title", in: npNamespace,
                                   properties: .position, anchor: .leading, isSource: open)
            .npFade(open, presenceFade)
    }

    // 8. Bottom pills: Queue · Devices · Write.
    private var pills: some View {
        GlassEffectContainer(spacing: 0) {
            HStack(spacing: 2) {
                pill("queue", "Queue", shape: AnyShape(Capsule()))
                pill("devices", "Devices", shape: AnyShape(RoundedRectangle(cornerRadius: 20)))
                pill("note", "Write", shape: AnyShape(Capsule()))
            }
        }
        .padding(.bottom, 8)
    }

    private func pill(_ icon: String, _ title: String, shape: AnyShape) -> some View {
        Button {} label: {
            HStack(spacing: 4) {
                YoinIcon(icon, size: 15.8)
                Text(title).font(.gsf(12, .medium)).tracking(0.1)
            }
            // The primary glass reads mid-tone in both modes: onPrimary in
            // light; white in dark, where dark onPrimary (and, on glass,
            // onPrimaryContainer too) sank into it.
            .foregroundStyle(colorScheme == .dark ? Color.white : scheme.onPrimary)
            // Same contrast shadow as PAUSE on the same primary glass.
            .labContrast("transportPrimary")
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
                    // The page folds into the pill from where the finger
                    // left it: the offset springs home with the morph.
                    withAnimation(Yoin.spatial) { dragY = 0 }
                    model.setNowPlaying(expanded: false)
                } else {
                    withAnimation(Yoin.spatial) { dragY = 0 }
                }
            }
    }
}

/// Android NowPlayingBudget (phone), run from the shared Kotlin
/// (`resolveStageColumnBudget`, YoinShared): nominal → minimum slot heights,
/// lower gaps squeezed first, then upper; then the inline hero, then the
/// one-line lyric. The content minimums are Android's at font scale 1, from
/// YoinTokens.
struct NowPlayingBudget {
    var topBar: CGFloat
    var cover: CGFloat
    var coverGap: CGFloat
    var tabs: CGFloat
    var tabGap: CGFloat
    var transport: CGFloat
    var hero: CGFloat
    var pills: CGFloat
    /// The lyric window (list) or the one-line lyric's slot.
    var lyricWindow: CGFloat
    var inlineHero: Bool
    var oneLineLyric: Bool
    /// Forced short layout on a taller screen: the height Android wouldn't have.
    var slack: CGFloat = 0

    /// `-screen np-short`: the column of the ~700 dp Android phone the
    /// reference shots come from.
    static let referenceShortHeight: CGFloat = 700

    /// Android NowPlayingMetrics at font scale 1 (M3 line heights + YoinTokens).
    nonisolated(unsafe) static let metrics: StageColumnMetrics = {
        let t = Tokens.type, n = Tokens.np
        func f(_ v: Double) -> Float { Float(v) }
        return StageColumnMetrics(
            tabText: f(t.labelLarge.lineHeight),
            transport: f(n.CONTROL_SIZE * 2 + n.CONTROL_ROW_GAP),
            hero: f(t.titleMedium.lineHeight + n.HERO_LINE_GAP + t.displaySmall.lineHeight),
            heroOneLine: f(t.displaySmall.lineHeight),
            pills: f(n.PILL_HEIGHT),
            oneLineContent: f(t.bodyLarge.lineHeight + n.LYRIC_LINE_PADS),
            lyric: LyricWindowMetrics(activeLine: f(t.bodyLarge.lineHeight + n.LYRIC_LINE_PADS),
                                      inactiveLine: f(t.bodyMedium.lineHeight + n.LYRIC_LINE_PADS))
        )
    }()

    /// `height` = the column's height: from below the status bar to
    /// max(home indicator, 16) above the bottom.
    init(width: CGFloat, height: CGFloat, forceShort: Bool) {
        let resolved = forceShort ? min(height, Self.referenceShortHeight) : height
        let budget = NowPlayingBudgetKt.resolveStageColumnBudget(
            width: Float(width), height: Float(resolved), metrics: Self.metrics, enlarged: nil, previous: nil)
        let r = budget.rest
        topBar = CGFloat(r.topBar)
        cover = CGFloat(r.cover)
        coverGap = CGFloat(r.coverGap)
        tabs = CGFloat(r.tabs)
        tabGap = CGFloat(r.tabGap)
        transport = CGFloat(r.controls)
        hero = CGFloat(r.hero)
        pills = CGFloat(r.accessory)
        inlineHero = budget.heroOneLine
        oneLineLyric = r.lyricFrame == LyricFrame.oneline
        // The one-line lyric is one tappable row; what the slot has beyond
        // that is air, given to the slack (between transport and hero).
        let slot = CGFloat(r.lyricSlot)
        lyricWindow = oneLineLyric ? min(slot, Tokens.np.ONE_LINE_ROW_NOMINAL.cg) : slot
        slack = max(0, height - CGFloat(r.total)) + (slot - lyricWindow)
    }
}

/// Android RatingSlider: 48 dp capsule, primary fill from the bottom, number
/// (titleLarge, tabular) at the top in onSurfaceVariant, onPrimary once the
/// fill passes 80 % of the height.
///
/// iOS: the track is glass. Held, the whole control reads as a glass
/// slider (iOS 26 sliders): interactive glass, a touch larger, and the fill
/// drawn inset so it sits inside the glass capsule.
struct RatingBar: View {
    @Binding var value: Double
    var scheme: CoverScheme
    var held = false
    /// Finger down / up.
    var onHold: (Bool) -> Void = { _ in }

    var body: some View {
        GeometryReader { geo in
            let h = geo.size.height
            let fraction = value / 10
            let inset: CGFloat = held ? 4 : 0
            ZStack(alignment: .top) {
                // Track: glass tinted with the container role, so the
                // background shows through; the fill (the score) stays solid.
                Capsule().fill(.clear)
                    .npGlass("ratingTrack", scheme: scheme, auto: scheme.surfaceVariant, held: held, in: Capsule())
                Capsule()
                    .fill(scheme.primary.opacity(GlassLab.shared.extra("ratingFill")))
                    .frame(height: max(48 - inset * 2, (h - inset * 2) * fraction))
                    .frame(maxHeight: .infinity, alignment: .bottom)
                    .padding(inset)
                Text(value >= 10 ? "10" : String(format: "%.1f", value))
                    .font(.gsf(22))
                    .monospacedDigit()
                    .foregroundStyle(fraction > 0.8 ? scheme.onPrimary : scheme.onSurfaceVariant)
                    .frame(minWidth: 36)
                    .padding(.top, 8)
            }
            .contentShape(.capsule)
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { v in
                        if !held { onHold(true) }
                        let raw = Double((1 - v.location.y / h) * 10)
                        value = (min(10.0, max(0.0, raw)) * 10).rounded() / 10
                    }
                    .onEnded { _ in onHold(false) }
            )
            .animation(Yoin.spatialFast, value: value)
        }
        .scaleEffect(held ? 1.08 : 1)
        .accessibilityElement()
        .accessibilityLabel("Rating")
        .accessibilityValue(String(format: "%.1f", value))
    }
}

/// The progress row's glass: at 0 an empty sliver on the bar's line, at 1
/// a 42 pt capsule across the row, centered on the times and the bar
/// (they sit 9 pt below the 56 pt row's middle).
struct ScrubberGlassShape: Shape {
    var grow: CGFloat

    var animatableData: CGFloat {
        get { grow }
        set { grow = newValue }
    }

    func path(in rect: CGRect) -> Path {
        let g = max(0, grow)
        let inset = (1 - min(g, 1)) * 50
        let h = 42 * g
        let r = CGRect(x: rect.minX + inset, y: rect.midY + 9 - h / 2,
                       width: max(0, rect.width - inset * 2), height: h)
        return Capsule().path(in: r)
    }
}

/// A plain button that reports press down / up (Now Playing's shared
/// "interacting" state).
struct PressReport: ButtonStyle {
    var onPress: (Bool) -> Void

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .onChange(of: configuration.isPressed) { _, pressed in onPress(pressed) }
    }
}

extension View {
    /// Morph opacity with its own animation, leaving the transaction's
    /// spring to the geometry.
    func npFade(_ visible: Bool, _ animation: Animation) -> some View {
        self.animation(animation) { $0.opacity(visible ? 1 : 0) }
    }

    /// A Glass Lab surface that turns into interactive glass while `held`,
    /// with its tint thinned so the glass itself carries the look. One
    /// `glassEffect` either way, so holding doesn't rebuild the glass.
    /// Non-glass variants (Apple HIG preset) fall back to `labGlass`.
    @ViewBuilder
    func npGlass<S: Shape>(_ id: String, scheme: CoverScheme, auto: Color, held: Bool, in shape: S) -> some View {
        let st = GlassLab.shared.style(id)
        switch st.variant {
        case .regular, .clear:
            let tint = st.role.color(in: scheme, brand: .brand(dark: false), auto: auto)
            let base: Glass = st.variant == .clear ? .clear : .regular
            let tinted = st.tintOn ? base.tint(tint.opacity(st.tintOpacity * (held ? 0.6 : 1))) : base
            glassEffect(held || st.interactive ? tinted.interactive() : tinted, in: shape)
        case .none, .material:
            labGlass(id, scheme: scheme, brand: .brand(dark: false), auto: auto, in: shape)
        }
    }
}
