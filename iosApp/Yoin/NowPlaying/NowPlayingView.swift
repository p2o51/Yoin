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
    @State private var airPlay = AirPlayRouteTrigger()
    /// The note composer (the Write pill) and its draft: words, and the
    /// song moment they are filed at.
    @State private var composing = false
    @State private var draft = ""
    @State private var draftAnchorMs: Int64?
    @State private var notesSaved = 0
    @FocusState private var composerFocused: Bool
    /// The page's geometry before the keyboard came up (see `page`).
    @State private var frozenGeometry: PageGeometry?
    @State private var keyboardHeight: CGFloat = 0

    /// The engine's song; nil = the mock snapshot (screenshots) as before.
    private var engineTrack: PlayableTrack? { model.player.currentTrack }
    private var data: NowPlayingData { .shared }

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
        // The page doesn't move for the keyboard; the composer sits on it
        // (see `page`).
        page
            .offset(y: max(0, dragY))
            .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillChangeFrameNotification)) { n in
                guard let end = n.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect,
                      let screen = (n.object as? UIScreen) ?? UIApplication.shared.connectedScenes
                        .compactMap({ ($0 as? UIWindowScene)?.screen }).first else { return }
                withAnimation(Yoin.spatial) { keyboardHeight = max(0, screen.bounds.maxY - end.minY) }
            }
            .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)) { _ in
                withAnimation(Yoin.spatial) { keyboardHeight = 0 }
            }
        .gesture(dismissDrag, isEnabled: !panel)
        .sheet(isPresented: Binding(get: { model.queueSheetOpen }, set: { model.queueSheetOpen = $0 })) {
            QueueSheet()
        }
        .task(id: engineTrack.map(NowPlayingData.key)) {
            guard let t = engineTrack else { return }
            await data.prepare(t, client: model.session.client)
        }
        #if DEBUG
        .task { await NowPlayingDebugQueue.startIfRequested(model) }
        .task {
            guard !panel, NowPlayingDebugQueue.tab != nil || NowPlayingDebugQueue.compose != nil else { return }
            try? await Task.sleep(for: .seconds(4))
            if let t = NowPlayingDebugQueue.tab { withAnimation(Yoin.spatial) { tab = t } }
            if let words = NowPlayingDebugQueue.compose {
                startWriting()
                draft = words
            }
        }
        #endif
        .onChange(of: composerFocused) { _, focused in
            // Keyboard down over an empty draft: back to the pills.
            if !focused && draft.isEmpty { withAnimation(Yoin.spatial) { composing = false } }
        }
        .task {
            // Screenshot aid: `-screen np-airplay` taps Devices once the page is up.
            guard LaunchConfig.baseScreen == "np-airplay", !panel else { return }
            try? await Task.sleep(for: .seconds(1.5))
            airPlay.present()
        }
    }

    /// The background sits outside the GeometryReader so it reaches the
    /// screen's bottom edge (inside, it stopped at the home indicator inset).
    private var page: some View {
        ZStack {
            if panel {
                pageGradient.ignoresSafeArea()
            } else {
                base
            }
            GeometryReader { geo in
                // With the keyboard up the shell hands this page a shorter
                // frame (and drops its top inset); the page keeps the
                // geometry it had before the keyboard, pinned to the screen.
                let live = PageGeometry(size: geo.size, top: geo.safeAreaInsets.top,
                                        bottom: geo.safeAreaInsets.bottom, minY: geo.frame(in: .global).minY)
                let g = composing ? (frozenGeometry ?? live) : live
                content(size: g.size, bottomInset: g.bottom)
                    .frame(maxWidth: model.isPad ? 520 : .infinity)
                    .frame(width: g.size.width, height: g.size.height)
                    .overlay(alignment: .bottom) {
                        if composing {
                            composerLayer
                                // The frame ends `g.bottom` above the screen's
                                // edge; the keyboard's top is `keyboardHeight` up.
                                .padding(.bottom, max(0, keyboardHeight - g.bottom))
                                .ignoresSafeArea(.keyboard)
                                .transition(.move(edge: .bottom).combined(with: .opacity))
                        }
                    }
                    .offset(y: g.minY - live.minY)
                    .onChange(of: live, initial: true) { _, now in
                        if !composing { frozenGeometry = now }
                    }
            }
        }
    }

    struct PageGeometry: Equatable {
        var size: CGSize
        var top: CGFloat
        var bottom: CGFloat
        var minY: CGFloat
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

    private func content(size: CGSize, bottomInset: CGFloat) -> some View {
        let p = model.playback
        let budget = NowPlayingBudget(
            width: min(size.width, model.isPad ? 520 : size.width),
            height: size.height - max(bottomInset, 16) + bottomInset,
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
                lyricArea(p, clear: clear, under: budget.transport,
                       anchor: budget.oneLineLyric
                           // OneLineLyricRow: the 32 pt line centered in its slot.
                           ? (clear - Tokens.type.bodyLarge.lineHeight.cg - 8) / 2 + 4
                           : clear * Tokens.np.LYRICS_ANCHOR_FRACTION.cg, fadeTop: !budget.oneLineLyric)
                transport(p)
                    .padding(.top, clear)
            }
            .frame(height: clear + budget.transport, alignment: .top)
            .npFade(open, contentFade)
            if budget.slack > 0 {
                Spacer().frame(height: budget.slack)
            }
            hero(p, inline: budget.inlineHero)
                // StageHeightSlot: content at the top, air below.
                .frame(height: budget.hero, alignment: .top)
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
                // Android StageTopBar: 28 dp ChevronDown in onSurfaceVariant.
                YoinIcon("chevron_down", size: Tokens.np.CHEVRON_GLYPH.cg)
                    .foregroundStyle(scheme.onSurfaceVariant)
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
                // PlayingFromLabel: labelSmall kind over labelMedium name.
                Text("PLAYING FROM ALBUM")
                    .yoinRole(Tokens.type.labelSmall)
                    .foregroundStyle(scheme.onSurfaceVariant.opacity(0.55))
                Text(p.album.title)
                    .yoinRole(Tokens.type.labelMedium)
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
                RatingBar(value: ratingBinding, scheme: scheme, held: ratingHeld) { down in
                    withAnimation(Yoin.spatialFast) { ratingHeld = down }
                    touch("rating", down)
                    if !down, let t = engineTrack { data.commitRating(t, client: model.session.client) }
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

    /// The engine's song: its rating (local, else the server's stars × 2);
    /// the mock: the screenshot value.
    private var ratingBinding: Binding<Double> {
        guard let t = engineTrack else { return $rating }
        return Binding(get: { data.rating(t) }, set: { data.dragRating($0, t) })
    }

    private var isFavorite: Bool {
        guard let t = engineTrack else { return favorite }
        return data.isFavorite(t)
    }

    private func toggleFavorite() {
        withAnimation(Yoin.spatialFast) {
            if let t = engineTrack {
                data.toggleFavorite(t, client: model.session.client)
            } else {
                favorite.toggle()
            }
        }
    }

    private var heart: some View {
        let favorite = isFavorite
        return Button { toggleFavorite() } label: {
            YoinIcon(favorite ? "favorite_filled" : "favorite", size: 24)
                // Android FavoriteButton: liked = onTertiary heart on a
                // tertiary circle, else onTertiaryContainer on
                // tertiaryContainer. Liked, the glass carries the tertiary
                // fill (like PAUSE carries primary) and the glyph stays
                // legible on it in both modes; unliked, plain glass.
                .foregroundStyle(favorite
                                 ? (colorScheme == .dark ? Color.white : scheme.onTertiary)
                                 : (heartTinted ? scheme.onTertiaryContainer : scheme.onSurfaceVariant))
                .labContrast(favorite ? "transportPrimary" : "heart")
                .frame(width: Tokens.np.HEART_SIZE.cg, height: Tokens.np.HEART_SIZE.cg)
                .contentShape(.circle)
        }
        .buttonStyle(PressReport { touch("heart", $0) })
        .labGlass(favorite ? "transportPrimary" : "heart", scheme: scheme, brand: brand,
                  auto: favorite ? scheme.tertiary : scheme.tertiaryContainer, in: Circle())
        .animation(Yoin.effects, value: favorite)
        .accessibilityLabel(favorite ? "Remove from favorites" : "Add to favorites")
    }

    /// The lab's heart style is tinted (Current preset): unliked takes
    /// tertiaryContainer's on-color, as on Android.
    private var heartTinted: Bool { lab.style("heart").tintOn }

    // 4. Text tabs.
    private var tabs: some View {
        HStack(spacing: Tokens.np.TAB_GAP.cg) {
            ForEach(Array(["Lyrics", "About", "Note"].enumerated()), id: \.offset) { i, title in
                let on = tab == i
                Button { withAnimation(Yoin.spatialFast) { tab = i } } label: {
                    // CompactTextTabs: labelLarge (Bold when selected).
                    Text(title)
                        .yoinRole(Tokens.type.labelLarge, weight: on ? .bold : nil)
                        .foregroundStyle(scheme.onSurfaceVariant.opacity(on ? 1 : 0.5))
                        .scaleEffect(x: on ? Tokens.np.TAB_SELECTED_STRETCH.cg : 1, y: 1, anchor: .leading)
                }
                .buttonStyle(.plain)
            }
        }
        .frame(height: Tokens.np.TAB_ROW_NOMINAL.cg, alignment: .top)
    }

    // 5. Lyrics / Note window. Android: a list with the active line at 22 %
    // of the window (or one line on short screens). iOS: the list continues
    // `under` points further down, behind the glass transport, fading out by
    // its second row.
    private func lyricArea(_ p: PlaybackSnapshot, clear: CGFloat, under: CGFloat,
                           anchor: CGFloat, fadeTop: Bool) -> some View {
        ZStack(alignment: .topLeading) {
            if tab == 2 {
                noteWindow(clear: clear)
                    .transition(.opacity)
            } else {
                lyrics(p, clear: clear, under: under, anchor: anchor, fadeTop: fadeTop)
                    .transition(.opacity)
            }
        }
        .animation(Yoin.effects, value: tab == 2)
    }

    @ViewBuilder
    private func lyrics(_ p: PlaybackSnapshot, clear: CGFloat, under: CGFloat,
                        anchor: CGFloat, fadeTop: Bool) -> some View {
        if let t = engineTrack {
            switch data.lyricsState(t) {
            case .loading:
                Color.clear.frame(height: clear)
            case .none:
                // Android LyricsDisplay: the empty label at the window's start.
                windowLabel("No lyrics available", clear: clear)
            case .lines(let loaded) where loaded.synced:
                // Only this subtree reads the position; the line index is
                // derived per tick and the lines re-render when it moves.
                lyricWindow(clear: clear, under: under, fadeTop: fadeTop) {
                    PlaybackPosition { position, _ in
                        LyricLinesView(lines: loaded.lines, active: loaded.activeIndex(positionSec: position),
                                       anchor: anchor, primary: scheme.primary,
                                       onSurfaceVariant: scheme.onSurfaceVariant)
                            .equatable()
                    }
                }
            case .lines(let loaded):
                lyricWindow(clear: clear, under: under, fadeTop: fadeTop, interactive: true) {
                    UnsyncedLyricsView(lines: loaded.lines, onSurfaceVariant: scheme.onSurfaceVariant)
                }
            }
        } else {
            lyricWindow(clear: clear, under: under, fadeTop: fadeTop) {
                LyricLinesView(lines: p.lyrics, active: p.activeLyric, anchor: anchor,
                               primary: scheme.primary, onSurfaceVariant: scheme.onSurfaceVariant)
            }
        }
    }

    /// A plain label at the window's start (bodyMedium, onSurfaceVariant).
    private func windowLabel(_ text: String, clear: CGFloat) -> some View {
        Text(text)
            .yoinRole(Tokens.type.bodyMedium)
            .foregroundStyle(scheme.onSurfaceVariant)
            .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
            .frame(height: clear, alignment: .center)
    }

    /// Android NoteCompactPane: the song's notes as lyric-like lines; empty,
    /// the label opens the composer.
    @ViewBuilder
    private func noteWindow(clear: CGFloat) -> some View {
        let key = currentNoteKey
        let notes = NotesStore.shared.notes(provider: key.provider, trackId: key.rawId)
        if notes.isEmpty {
            Button { startWriting() } label: {
                windowLabel("Write", clear: clear).contentShape(.rect)
            }
            .buttonStyle(.plain)
        } else {
            PlaybackPosition { position, _ in
                NoteLinesView(notes: notes,
                              activeId: NotesStore.currentAnchoredNoteId(notes, positionMs: Int64(position * 1000)),
                              primary: scheme.primary, onSurfaceVariant: scheme.onSurfaceVariant)
                    .equatable()
            }
            .frame(height: clear)
            .mask(
                LinearGradient(stops: [
                    .init(color: .clear, location: 0),
                    .init(color: .black, location: min(0.2, 16 / max(clear, 1))),
                    .init(color: .black, location: max(0, 1 - 24 / max(clear, 1))),
                    .init(color: .clear, location: 1),
                ], startPoint: .top, endPoint: .bottom)
            )
            .allowsHitTesting(false)
        }
    }

    private func lyricWindow<C: View>(clear: CGFloat, under: CGFloat, fadeTop: Bool, interactive: Bool = false,
                                      @ViewBuilder _ lines: () -> C) -> some View {
        let total = clear + under
        let underStrength = GlassLab.shared.extra("lyricsUnder")
        return lines()
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
        .allowsHitTesting(interactive)
    }

    // 6. Transport: [PAUSE][Next] … [Repeat] / [Prev] 1:14 ~~~ -2:41
    private func transport(_ p: PlaybackSnapshot) -> some View {
        // One glass layer: Pause, Next and Prev sit 4–8 pt apart. A 3 pt
        // container spacing keeps them separate at rest (10 bridged Pause and
        // Prev into one blob) and lets neighbours blend as interactive glass
        // swells on press.
        GlassEffectContainer(spacing: lab.extra("containerSpacing")) {
        VStack(spacing: 4) {
            HStack(spacing: Tokens.np.TRANSPORT_GAP.cg) {
                Button { model.player.togglePlayPause() } label: {
                    // titleLarge × 0.9, Bold, 0.5 tracking; the playing
                    // stretch (1.06) grows from the word's leading edge.
                    Text(p.isPlaying ? "PAUSE" : "PLAY")
                        .font(.gsf(19.8, .bold))
                        .tracking(0.5)
                        .scaleEffect(x: 1.06, y: 1, anchor: .leading)
                        // Same rule as the pills on the same primary glass:
                        // dark onPrimary sank into it.
                        .foregroundStyle(colorScheme == .dark ? Color.white : scheme.onPrimary)
                        .labContrast("transportPrimary")
                        .padding(.horizontal, 24)
                        .frame(height: 56)
                        .contentShape(.capsule)
                }
                .buttonStyle(PressReport { touch("pause", $0) })
                .labGlass("transportPrimary", scheme: scheme, brand: brand, auto: scheme.primary, in: Capsule())
                .accessibilityLabel(p.isPlaying ? "Pause" : "Play")
                roundButton("skip_next_filled", fill: scheme.primary, ink: scheme.onPrimary, label: "Next") {
                    model.player.next()
                }
                Spacer(minLength: 0)
                roundButton(model.player.shuffle ? "shuffle" : "repeat", fill: scheme.tertiaryContainer,
                            ink: scheme.onTertiaryContainer, label: "Play mode", badge: playModeBadge) {
                    model.player.cyclePlayMode()
                }
            }
            .frame(height: 56)
            HStack(spacing: 0) {
                roundButton("skip_previous_filled", fill: scheme.secondary, ink: scheme.onSecondary, label: "Previous") {
                    model.player.previous()
                }
                Spacer().frame(width: Tokens.np.TRANSPORT_GAP.cg)
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
    /// The capsule is drawn outside the row, so the bar never changes width
    /// under the finger and rests at Android's width.
    private func progress(_ p: PlaybackSnapshot) -> some View {
        // The position is read inside PlaybackPosition, so the engine's ticks
        // redraw this row only.
        PlaybackPosition { position, duration in
        HStack(spacing: 0) {
            // PlaybackTimeLabel: labelLarge tabular, min 44 / 52, 8 apart.
            Text(formatTime(Int(position)))
                .frame(minWidth: Tokens.np.TIME_LABEL_MIN_START.cg, alignment: .leading)
                .offset(y: 6)
            Spacer().frame(width: Tokens.np.TIME_LABEL_GAP.cg)
            WavyProgressBar(progress: position / duration,
                            duration: duration, isPlaying: p.isPlaying,
                            tint: scheme.primary, trackTint: scheme.surfaceContainerHighest,
                            onScrubbing: { touch("progress", $0) }) { fraction in
                model.player.seek(to: fraction * duration)
            }
                .frame(height: 40, alignment: .bottom)
            Spacer().frame(width: Tokens.np.TIME_LABEL_GAP.cg)
            Text("-" + formatTime(max(0, Int(duration) - Int(position))))
                .frame(minWidth: Tokens.np.TIME_LABEL_MIN_END.cg, alignment: .trailing)
                .offset(y: 6)
        }
        }
        // The row itself carries the glass (as glass content the times and
        // the bar draw over it; a background glass in the transport's
        // container would be lifted above them). The shape does the morph.
        // The row sits where Android's does; the capsule reaches 4 pt past
        // its leading edge (clear of Previous) and 10 pt past its end.
        .npGlass("ratingTrack", scheme: scheme, auto: scheme.surfaceVariant, held: true,
                 in: ScrubberGlassShape(grow: interacting ? 1 : 0, leading: 4, trailing: 10))
    }

    /// Repeat one wears a small 1; repeat off dims the glyph.
    private var playModeBadge: String? {
        switch model.player.repeatMode {
        case .one: "1"
        case .off: "off"
        case .all: nil
        }
    }

    private func roundButton(_ icon: String, fill: Color, ink: Color, label: String, badge: String? = nil,
                             action: @escaping () -> Void) -> some View {
        // Untinted (Balanced / HIG presets): the glyph goes on-surface.
        let tinted = GlassLab.shared.style("transportSecondary").tintOn
        return Button(action: action) {
            YoinIcon(icon, size: 28)
                .foregroundStyle(tinted ? ink : scheme.onSurface)
                .opacity(badge == "off" ? 0.45 : 1)
                .overlay(alignment: .bottomTrailing) {
                    if badge == "1" {
                        Text("1").font(.gsf(10, .bold)).foregroundStyle(tinted ? ink : scheme.onSurface)
                            .offset(x: 4, y: 2)
                    }
                }
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
            // One row: title, then the artist on its baseline 12 after it,
            // the artist capped at 40 % of the row (HeroOneLineArtistShare).
            GeometryReader { g in
                HStack(alignment: .firstTextBaseline, spacing: Tokens.np.HERO_ONE_LINE_GAP.cg) {
                    heroTitle(p)
                        .layoutPriority(1)
                    Text(p.album.artist)
                        .font(.gsf(14.4, .medium))
                        .foregroundStyle(scheme.onSurfaceVariant)
                        .scaleEffect(x: 1.02, y: 1, anchor: .leading)
                        .frame(maxWidth: g.size.width * Tokens.np.HERO_ONE_LINE_ARTIST_SHARE.cg, alignment: .leading)
                        // Measured first (capped); the title takes the rest.
                        .layoutPriority(2)
                        .npFade(open, contentFade)
                    Spacer(minLength: 0)
                }
                .lineLimit(1)
            }
            .frame(height: Tokens.type.displaySmall.lineHeight.cg)
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
                pill("queue", "Queue", shape: AnyShape(Capsule())) { model.queueSheetOpen = true }
                pill("devices", "Devices", shape: AnyShape(RoundedRectangle(cornerRadius: 20))) { airPlay.present() }
                    // The system route picker, invisible, over the pill: a
                    // finger lands on its own button (the forwarding in
                    // `present()` is the fallback).
                    .overlay { AirPlayRoutePicker(trigger: airPlay) }
                pill("note", "Write", shape: AnyShape(Capsule())) { startWriting() }
            }
        }
    }

    private func pill(_ icon: String, _ title: String, shape: AnyShape,
                      action: @escaping () -> Void) -> some View {
        Button(action: action) {
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

    // MARK: Notes

    /// Notes are keyed by the song on screen: the engine's, else the mock's.
    private var currentNoteKey: MediaId {
        if let t = engineTrack { return MediaId(provider: t.provider, rawId: t.id) }
        return model.mockPlayback.track.id
    }

    private var playheadMs: Int64 {
        let sec = engineTrack != nil ? model.player.progress.positionSec : Double(model.mockPlayback.positionSec)
        return Int64((sec * 1000).rounded())
    }

    /// The Write pill (Android `noteBar.requestWriting()` + the Note page):
    /// the Note window comes up and the composer takes the keyboard, filed
    /// at the moment writing began.
    private func startWriting() {
        if draft.isEmpty { draftAnchorMs = playheadMs }
        withAnimation(Yoin.spatial) {
            tab = 2
            composing = true
        }
        composerFocused = true
    }

    private func saveNote() {
        let key = currentNoteKey
        let p = model.playback
        guard NotesStore.shared.add(provider: key.provider, trackId: key.rawId, content: draft,
                                    positionMs: draftAnchorMs, title: p.track.title, artist: p.album.artist) != nil
        else { return }
        notesSaved += 1
        draft = ""
        // The keyboard stays up for the next line, filed at the next moment.
        draftAnchorMs = playheadMs
    }

    /// The composer over the page's foot, above the keyboard. A tap above it
    /// puts the keyboard away.
    private var composerLayer: some View {
        VStack(spacing: 0) {
            Color.clear
                .contentShape(.rect)
                .onTapGesture { composerFocused = false; if draft.isEmpty { withAnimation(Yoin.spatial) { composing = false } } }
            NoteComposer(scheme: scheme, text: $draft, anchorMs: draftAnchorMs, focus: $composerFocused,
                         onRealign: { withAnimation(Yoin.spatialFast) { draftAnchorMs = playheadMs } },
                         onSave: saveNote)
                .padding(.horizontal, Tokens.np.COLUMN_PADDING.cg - 8)
                .padding(.bottom, 8)
                .background(alignment: .bottom) {
                    // Lifts the card off the transport behind it.
                    LinearGradient(colors: [scheme.background.opacity(0), scheme.background.opacity(0.85)],
                                   startPoint: .top, endPoint: .bottom)
                        .padding(.top, -48)
                        .ignoresSafeArea()
                }
        }
        .sensoryFeedback(.success, trigger: notesSaved)
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
            let crossover = Tokens.np.RATING_LABEL_CROSSOVER
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
                // titleLarge (22/28) tabular; the color crosses from
                // onSurfaceVariant to onPrimary as the fill goes 80 → 100 %.
                Text(value >= 10 ? "10" : String(format: "%.1f", value))
                    .yoinRole(Tokens.type.titleLarge)
                    .monospacedDigit()
                    .foregroundStyle(scheme.onSurfaceVariant.mix(with: scheme.onPrimary,
                                                                 by: min(1, max(0, (fraction - crossover) / (1 - crossover)))))
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
    /// How far the grown capsule reaches past the row's ends.
    var leading: CGFloat = 0
    var trailing: CGFloat = 0

    var animatableData: CGFloat {
        get { grow }
        set { grow = newValue }
    }

    func path(in rect: CGRect) -> Path {
        let g = max(0, grow)
        let inset = (1 - min(g, 1)) * 50
        let h = 42 * g
        let lead = leading * min(g, 1), trail = trailing * min(g, 1)
        let r = CGRect(x: rect.minX - lead + inset, y: rect.midY + 9 - h / 2,
                       width: max(0, rect.width + lead + trail - inset * 2), height: h)
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
