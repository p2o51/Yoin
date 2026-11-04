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
    /// Phone: the shell's pill ⇄ page morph hosts this page (it draws the
    /// background, owns the drag-down and flies the cover / title).
    @Environment(\.npMorph) private var npMorph
    /// Morph target: true = the full page, false = folded into the bar's pill.
    var open = true

    @State private var dragY: CGFloat = 0
    @State private var tab = 0
    /// Android NowPlayingStageMode (NowPlayingStage.swift): compact, the
    /// expanded detail page, or the focused cover.
    @State private var stage: NowPlayingStage = .compact
    /// Android short focus (NowPlayingBudget.ShortFocus): the cover tap's
    /// focus where the regular one isn't offered. Compact only.
    @State private var shortFocus = false
    /// The expanded Note page's order (Android NoteSortMode).
    @State private var noteSort: NotesStore.SortMode = .timeline
    @State private var rating: Double = 6.3
    @State private var favorite = true
    /// One "hands on the controls" state for rating, heart, transport and
    /// the progress bar: while it is on, the progress bar sits in glass.
    /// `-screen np-touch` starts with it (and a held rating) on.
    @State private var interacting = LaunchConfig.baseScreen == "np-touch"
    @State private var ratingHeld = LaunchConfig.baseScreen == "np-touch"
    @State private var touching: Set<String> = []
    /// The progress bar is (or was last) the control touched.
    @State private var scrubbing = false
    @State private var release: Task<Void, Never>?
    @State private var airPlay = AirPlayRouteTrigger()
    /// The note composer (the Write pill) and its draft: words, and the
    /// song moment they are filed at.
    @State private var composing = false
    @State private var draft = ""
    @State private var draftAnchorMs: Int64?
    /// Editing a saved note (the Note page's long-press Edit): the composer
    /// holds its words and moment; Save writes them back, Cancel leaves it.
    /// A new note's unsaved draft waits in `parkedDraft` meanwhile.
    @State private var editingNoteId: String?
    @State private var parkedDraft: (words: String, anchorMs: Int64?)?
    @State private var notesSaved = 0
    @FocusState private var composerFocused: Bool
    /// The Ask Gemini bar's question (Android `AskGeminiBar` draft) and its field.
    @State private var askDraft = ""
    @FocusState private var askFieldFocused: Bool
    /// The page's geometry before the keyboard came up (see `page`).
    @State private var frozenGeometry: PageGeometry?
    @State private var keyboardHeight: CGFloat = 0
    /// The expanded Lyrics page (Android NowPlayingScreen's lyricsAutoScroll /
    /// lyricsRecenterTick and LyricsSelectionState): following the playhead,
    /// the Recenter key's tick, select mode and its pick, the editor and the
    /// share card.
    @State private var lyricsFollow = true
    @State private var lyricsRecenterTick = 0
    @State private var lyricsSelecting = false
    @State private var lyricsSelected: Set<Int> = []
    @State private var lyricsLimitNudge = 0
    @State private var lyricsEditing = false
    @State private var lyricsShare: LyricsShareSnapshot?

    /// The engine's song; nil = the mock snapshot (screenshots) as before.
    private var engineTrack: PlayableTrack? { model.player.currentTrack }
    private var data: NowPlayingData { .shared }
    private var about: SongAboutLoader { .shared }
    /// The Ask bar is open (its field over the keyboard).
    private var asking: Bool { about.askState == .focused }
    /// The keyboard is up for the composer or the Ask bar: the page keeps its geometry.
    private var keyboardOwned: Bool { composing || asking }

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
        // iPad Full only; on the phone the morph owns the drag.
        .gesture(dismissDrag, isEnabled: !panel && npMorph == nil)
        // Gemini preset: dim the page under the Queue sheet so its controls
        // don't bleed through the sheet glass (0 in the other presets).
        .overlay {
            Color.black.opacity(model.queueSheetOpen ? lab.extra("sheetPresenterDim") : 0)
                .ignoresSafeArea()
                .allowsHitTesting(false)
                .animation(Yoin.effects, value: model.queueSheetOpen)
        }
        .sheet(isPresented: Binding(get: { model.queueSheetOpen }, set: { model.queueSheetOpen = $0 })) {
            QueueSheet()
        }
        // The lyric tools' sheets (Android LyricsSearchSheet / LyricsApplyDialog / LyricsShareSheet).
        .sheet(isPresented: Binding(get: { LyricsActions.shared.search.isOpen },
                                    set: { if !$0 { LyricsActions.shared.dismissSearch() } })) {
            LyricsSearchSheet(scheme: scheme, onSelect: { match in
                if let t = engineTrack { LyricsActions.shared.applySearchResult(match, for: t) }
            }, onClose: { LyricsActions.shared.dismissSearch() })
            .presentationDetents([.large])
        }
        .sheet(isPresented: $lyricsEditing) {
            LyricsEditSheet(scheme: scheme, initialText: editableLyrics, onApply: { text in
                lyricsEditing = false
                if let t = engineTrack { LyricsActions.shared.applyEdited(text, for: t) }
            }, onCancel: { lyricsEditing = false })
            .presentationDetents([.medium, .large])
        }
        .sheet(item: $lyricsShare) { snap in
            LyricsShareSheet(snapshot: snap, scheme: scheme) { lyricsShare = nil }
                .presentationDetents([.large])
        }
        // Select mode belongs to one song's lyrics on the expanded page.
        .onChange(of: engineTrack.map(NowPlayingData.key)) { _, _ in leaveLyricsSelect(follow: true) }
        .onChange(of: stage) { _, s in if s != .expanded { leaveLyricsSelect(follow: true) } }
        .onChange(of: tab) { _, t in if t != NowPlayingDetailPage.lyrics.rawValue { leaveLyricsSelect(follow: false) } }
        .task(id: engineTrack.map(NowPlayingData.key)) {
            guard let t = engineTrack else { return }
            await data.prepare(t, client: model.session.client, apple: model.store.apple)
        }
        .task(id: tab == NowPlayingDetailPage.about.rawValue ? engineTrack.map(NowPlayingData.key) : nil) {
            // Android fetches About when its page is first shown.
            guard tab == NowPlayingDetailPage.about.rawValue, let t = engineTrack else { return }
            await data.loadAbout(t, client: model.session.client)
        }
        #if DEBUG
        .task { await NowPlayingDebugQueue.startIfRequested(model) }
        .task {
            guard !panel, NowPlayingStageLaunch.page != nil || NowPlayingStageLaunch.stage != nil
                    || NowPlayingStageLaunch.focusDemo || NowPlayingStageLaunch.shortFocus
                    || NowPlayingDebugQueue.compose != nil || NowPlayingDebugQueue.editNote else { return }
            try? await Task.sleep(for: .seconds(4))
            if let page = NowPlayingStageLaunch.page { withAnimation(Yoin.spatial) { tab = page.rawValue } }
            if let s = NowPlayingStageLaunch.stage { withAnimation(Yoin.spatial) { stage = s } }
            // `-npFocusDemo YES`: the cover tap, in and out, for recordings.
            if NowPlayingStageLaunch.focusDemo {
                for _ in 0..<4 {
                    coverTapped(focusOffered: true)
                    try? await Task.sleep(for: .seconds(2.5))
                }
            }
            // `-npShortFocus YES`: the short focus (with `-screen np-short`).
            if NowPlayingStageLaunch.shortFocus { withAnimation(Yoin.spatial) { shortFocus = true } }
            if let words = NowPlayingDebugQueue.compose {
                startWriting()
                draft = words
            }
            // `-npEditNote YES`: the long-press menu's Edit on the first note.
            if NowPlayingDebugQueue.editNote {
                let key = currentNoteKey
                if NotesStore.shared.notes(provider: key.provider, trackId: key.rawId).isEmpty {
                    NotesStore.shared.add(provider: key.provider, trackId: key.rawId, content: "Bass walks up here",
                                          positionMs: 34_000, title: model.playback.track.title,
                                          artist: model.playback.album.artist)
                }
                if let first = NotesStore.shared.notes(provider: key.provider, trackId: key.rawId).first {
                    withAnimation(Yoin.spatial) { tab = NowPlayingDetailPage.note.rawValue; stage = .expanded }
                    try? await Task.sleep(for: .seconds(1))
                    startEditing(first)
                    if let words = NowPlayingDebugQueue.editWords { draft = words }
                    // `-npEditSave YES`: Save, two seconds later.
                    if NowPlayingDebugQueue.editSave {
                        try? await Task.sleep(for: .seconds(2))
                        saveNote()
                    }
                }
            }
        }
        #endif
        .onChange(of: "\(currentNoteKey.provider):\(currentNoteKey.rawId)") {
            // The song moved on under an edit: the edit is left as it was.
            if editingNoteId != nil { endEditing() }
        }
        .onChange(of: stage) { _, s in
            // Android: short focus belongs to the resting player.
            if s != .compact { shortFocus = false }
        }
        .onChange(of: engineTrack.map(NowPlayingData.key)) {
            // Android: another song, the Ask bar starts over.
            about.resetAsk()
        }
        .onChange(of: about.askState) { _, s in
            // Android: back to idle (answered, or put away) empties the draft.
            if s == .idle { askDraft = "" }
        }
        .onChange(of: askFieldFocused) { _, focused in
            // Keyboard down unsent: the bar folds back (Android onCollapseRequest).
            if !focused, asking { withAnimation(Yoin.spatial) { about.askCollapse() } }
        }
        .onChange(of: composerFocused) { _, focused in
            // Keyboard down over an empty draft: back to the pills.
            if !focused && draft.isEmpty && editingNoteId == nil { withAnimation(Yoin.spatial) { composing = false } }
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
            } else if npMorph == nil {
                base
            }
            GeometryReader { geo in
                // With the keyboard up the shell hands this page a shorter
                // frame (and drops its top inset); the page keeps the
                // geometry it had before the keyboard, pinned to the screen.
                let live = PageGeometry(size: geo.size, top: geo.safeAreaInsets.top,
                                        bottom: geo.safeAreaInsets.bottom, minY: geo.frame(in: .global).minY)
                let g = keyboardOwned ? (frozenGeometry ?? live) : live
                content(size: g.size, bottomInset: g.bottom)
                    .frame(maxWidth: model.isPad ? 520 : .infinity)
                    .frame(width: g.size.width, height: g.size.height)
                    .overlay(alignment: .bottom) {
                        if asking {
                            askLayer
                                .padding(.bottom, max(0, keyboardHeight - g.bottom))
                                .ignoresSafeArea(.keyboard)
                                .transition(.move(edge: .bottom).combined(with: .opacity))
                        } else if composing {
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
                        if !keyboardOwned { frozenGeometry = now }
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
            scrubbing = source == "progress"
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
        NowPlayingAmbient(scheme: scheme)
    }

    // MARK: Content (Android CompactPlayingContent)

    private func content(size: CGSize, bottomInset: CGFloat) -> some View {
        let p = model.playback
        let column = size.height - max(bottomInset, 16) + bottomInset
        let budget = NowPlayingBudget(
            width: min(size.width, model.isPad ? 520 : size.width),
            height: column,
            forceShort: model.npForceShort
        )
        // A stale focus (the window can no longer grow the cover) reads as compact.
        let focusOffered = budget.focus != nil
        let stage: NowPlayingStage = self.stage == .focus && !focusOffered ? .compact : self.stage
        let pose = stage == .focus ? (budget.focus ?? budget.rest) : budget.rest
        let expanded = stage == .expanded
        // Short focus: only where the regular focus isn't offered, at rest.
        let shortOffer = budget.shortFocus(columnWidth: min(size.width, model.isPad ? 520 : size.width)
                                           - Tokens.np.COLUMN_PADDING.cg * 2)
        let short = shortFocus && stage == .compact ? shortOffer : nil
        let page = NowPlayingDetailPage(rawValue: tab) ?? .lyrics
        // Expanded (Android ExpandedTopBarHeight …): the cover docks into the
        // top bar, the transport steps away, the page takes what is left.
        let topBarH = expanded ? NowPlayingExpanded.topBar : pose.topBar
        let coverGap = expanded ? NowPlayingExpanded.coverGap : pose.coverGap
        let tabsH = expanded ? NowPlayingExpanded.tabRow : pose.tabs
        let tabGap = expanded ? NowPlayingExpanded.tabGap : pose.tabGap
        let transportH = expanded ? 0 : (short?.transport ?? pose.transport)
        let accessory = expanded
            ? (page == .note || page == .about ? NowPlayingExpanded.writeBar + NowPlayingExpanded.writeBarGap
                : page == .lyrics && engineTrack != nil ? NowPlayingExpanded.lyricTools
                : NowPlayingExpanded.bareAccessory)
            : pose.pills
        let slack = expanded ? 0 : (short?.slack ?? pose.slack)
        let coverH = short?.cover ?? pose.cover
        let clear = expanded
            ? max(0, column - topBarH - coverGap - tabsH - tabGap - pose.hero - accessory)
            : pose.lyricWindow
        let oneLine = pose.oneLineLyric
        let restCover = budget.rest.cover
        return VStack(alignment: .leading, spacing: 0) {
            topBar(p, docked: expanded)
                .frame(height: topBarH)
                .npFade(open, contentFade)
            coverRow(expanded ? restCover : coverH, retreat: stage != .compact || short != nil,
                     dock: expanded && restCover > 0 ? CoverDock(cover: restCover, topBar: NowPlayingExpanded.topBar) : nil,
                     tappable: focusOffered || shortOffer != nil || stage != .compact) {
                coverTapped(focusOffered: focusOffered, shortOffered: shortOffer != nil)
            }
            // Short focus centres the grown cover.
            .frame(maxWidth: .infinity, alignment: short != nil ? .top : .topLeading)
            // Expanded, the row folds away and the cover flies on to the bar.
            .frame(height: expanded ? 0 : coverH, alignment: .top)
            .zIndex(1)
            Spacer().frame(height: coverGap)
            tabsSlot(expanded: expanded)
                .frame(height: tabsH, alignment: .top)
                // Expanded, the clip stands off so the glass tabs' own edge
                // light and shadow aren't cut to a hard line.
                .clipShape(Rectangle().inset(by: expanded ? -64 : 0))
                .npFade(open, contentFade)
            Spacer().frame(height: tabGap)
            // The lyrics run on under the glass transport; the current line
            // always sits in the clear band above it.
            ZStack(alignment: .top) {
                lyricArea(p, page: page, stage: stage, preview: pose.preview, clear: clear, under: transportH,
                          anchor: oneLine
                              // OneLineLyricRow: the 32 pt line centered in its slot.
                              ? (clear - Tokens.type.bodyLarge.lineHeight.cg - 8) / 2 + 4
                              : clear * Tokens.np.LYRICS_ANCHOR_FRACTION.cg, fadeTop: !oneLine)
                // The small About window takes its own taps (its Settings /
                // Retry buttons first, Android SongInfoDisplay in a tap-to-open box).
                if !expanded && !unsyncedLyricsShown(page, stage: stage) && !(page == .about && stage == .compact) {
                    // The small window (and the focused line) open the page.
                    Color.clear
                        .frame(height: clear)
                        .contentShape(.rect)
                        .onTapGesture { openPage(stage == .focus ? .lyrics : page) }
                        .accessibilityAddTraits(.isButton)
                        .accessibilityLabel(stage == .focus ? "Lyrics" : page.title)
                }
                transport(p, short: short != nil)
                    .padding(.top, clear)
                    .opacity(expanded ? 0 : 1)
                    .offset(y: expanded ? 48 : 0)
                    .allowsHitTesting(!expanded)
            }
            .frame(height: clear + transportH, alignment: .top)
            .npFade(open, contentFade)
            Spacer().frame(height: slack)
            hero(p, inline: budget.inlineHero)
                // Short focus folds the title away.
                .opacity(short != nil ? 0 : 1)
                .allowsHitTesting(short == nil)
                // StageHeightSlot: content at the top, air below.
                .frame(height: short != nil ? 0 : pose.hero, alignment: .top)
            accessorySlot(page: page, expanded: expanded)
                .frame(height: accessory, alignment: .bottomLeading)
                .npFade(open, contentFade)
        }
        .padding(.horizontal, Tokens.np.COLUMN_PADDING.cg)
        .frame(maxHeight: .infinity, alignment: .top)
    }

    // 1. Top bar: ⌄ + "PLAYING FROM ALBUM" / album name. Expanded (Android
    // StageTopBar docked): the chevron turns to point back, the cover lands
    // in the 48 pt slot after it and the label steps 10 aside.
    private func topBar(_ p: PlaybackSnapshot, docked: Bool) -> some View {
        HStack(spacing: 4) {
            // A glass circle (the transport's secondary glass), so it reads
            // as a control over the page; it rides along with the drag.
            Button { stageBack() } label: {
                // Android StageTopBar: 28 dp ChevronDown in onSurfaceVariant.
                YoinIcon("chevron_down", size: Tokens.np.CHEVRON_GLYPH.cg)
                    .rotationEffect(.degrees(docked ? 90 : 0))
                    .foregroundStyle(scheme.onSurfaceVariant)
                    .labContrast("transportSecondary")
                    .frame(width: 44, height: 44)
                    .contentShape(.circle)
            }
            .buttonStyle(.plain)
            .labGlass("transportSecondary", scheme: scheme, brand: brand,
                      auto: scheme.surfaceContainerHighest, in: Circle())
            .frame(width: 48, height: 48)
            .accessibilityLabel(stage == .compact ? "Collapse Now Playing" : "Back")
            // DockedCoverSlot: the cover row flies the cover into it.
            Spacer().frame(width: docked ? NowPlayingExpanded.dockedSlot : 0)
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
            .offset(x: docked ? 10 : 0)
            Spacer(minLength: 0)
        }
        // Android: page padding 24 + 4 at the start, and the 48 dp icon button
        // centers its 28 dp glyph, so the glyph starts 10 dp further in.
        .padding(.leading, 4)
    }

    // 2. Cover + rating column.
    /// `retreat`: focus / expanded fold the rating column away (Android
    /// ratingRetreatProgress); `dock`: expanded flies the cover into the top bar.
    private func coverRow(_ cover: CGFloat, retreat: Bool, dock: CoverDock?, tappable: Bool,
                          onTap: @escaping () -> Void) -> some View {
        let p = model.playback
        return HStack(alignment: .top, spacing: retreat ? 0 : Tokens.np.RATING_GAP.cg) {
            CoverArt(palette: p.album.palette, cornerRadius: 0, seed: p.album.seed)
                .frame(width: cover, height: cover)
                // ContinuousRoundedCornerShape(16dp), smoothing 0.6 ≈ Apple's continuous corners.
                // Folded, it takes the pill cover's 42 pt frame and 6 pt corners.
                .clipShape(.rect(cornerRadius: open ? Tokens.shape.NOW_PLAYING_COVER.cg : 6, style: .continuous))
                // World: the cover is a physical object (contact shadow).
                .labCoverShadow()
                .matchedGeometryEffect(id: panel ? "np.cover.panel" : "np.cover", in: npNamespace, isSource: open)
                .npFade(open, presenceFade)
                .npMorphAnchor(.cover)
                .modifier(CoverStageEffect(dock: dock, tappable: tappable,
                                           label: stage == .focus ? "Show lyrics and rating" : "Focus artwork",
                                           onTap: onTap))
                // Android NowPlayingCoverMenu: long-press = the song's sheet (+ Keep screen on).
                .trackMenu(for: PlaylistEntry(album: p.album, track: p.track), context: .nowPlaying)
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
            .offset(x: retreat ? 72 : 0)
            .opacity(retreat ? 0 : 1)
            .allowsHitTesting(!retreat)
            .frame(width: retreat ? 0 : Tokens.np.PHONE_RATING_COLUMN.cg, alignment: .leading)
            .npFade(open, contentFade)
        }
        .background(alignment: .topLeading) {
            NowPlayingFocusField(scheme: scheme, size: cover, on: stage == .focus)
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
                data.toggleFavorite(t, client: model.session.client, apple: model.store.apple)
            } else {
                favorite.toggle()
            }
        }
    }

    private var heart: some View {
        let favorite = isFavorite
        // The liked heart borrows PAUSE's accent surface, or (World) keeps
        // its own in-flow tonal surface.
        let surface = favorite && lab.extra("heartLikedAccent") > 0.5 ? "transportPrimary" : "heart"
        return Button { toggleFavorite() } label: {
            YoinIcon(heartIcon(favorite), size: 24)
                // Android FavoriteButton: liked = onTertiary heart on a
                // tertiary circle, else onTertiaryContainer on
                // tertiaryContainer. Liked, the glass carries the tertiary
                // fill (like PAUSE carries primary) and the glyph stays
                // legible on it in both modes; unliked, plain glass.
                .foregroundStyle(favorite
                                 ? (colorScheme == .dark && !lab.denseAccent(surface, dark: true)
                                    ? Color.white : scheme.onTertiary)
                                 : (heartTinted ? scheme.onTertiaryContainer : scheme.onSurfaceVariant))
                .labContrast(surface)
                .frame(width: Tokens.np.HEART_SIZE.cg, height: Tokens.np.HEART_SIZE.cg)
                .contentShape(.circle)
        }
        .buttonStyle(PressReport { touch("heart", $0) })
        .labGlass(surface, scheme: scheme, brand: brand,
                  auto: favorite ? scheme.tertiary : scheme.tertiaryContainer, in: Circle())
        .animation(Yoin.effects, value: favorite)
        .accessibilityLabel(heartLabel(favorite))
    }

    /// Apple Music's button is add to library (Android TrackLibraryButton): + / ✓.
    private var heartIsLibrary: Bool { engineTrack?.provider == AppleMusicMapping.provider }

    private func heartIcon(_ on: Bool) -> String {
        heartIsLibrary ? (on ? "check" : "add") : (on ? "favorite_filled" : "favorite")
    }

    private func heartLabel(_ on: Bool) -> String {
        heartIsLibrary ? (on ? "In library" : "Add to library") : (on ? "Remove from favorites" : "Add to favorites")
    }

    /// The lab's heart style is tinted (Current preset): unliked takes
    /// tertiaryContainer's on-color, as on Android.
    private var heartTinted: Bool { lab.style("heart").tintOn && !lab.neutralTint("heart") }

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

    /// Android StageTabs, expanded: the three pages as a button group. They
    /// float over the page as it scrolls, so they're the NP pills' glass:
    /// the selected one primary-tinted (label as on the pills), the others
    /// primaryContainer-tinted.
    private var expandedTabs: some View {
        GlassEffectContainer(spacing: 8) {
            HStack(spacing: 8) {
                ForEach(NowPlayingDetailPage.allCases, id: \.self) { page in
                    let on = tab == page.rawValue
                    Button { withAnimation(Yoin.spatial) { tab = page.rawValue } } label: {
                        Text(page.title)
                            .yoinRole(Tokens.type.labelLarge, weight: on ? .bold : .medium)
                            // Dark: white on both (the pills' rule; the container
                            // on-colors sink into the tinted glass).
                            // World: one accent per layer, the others neutral glass.
                            .foregroundStyle(!on && lab.oneAccentPerLayer ? scheme.onSurface
                                             : colorScheme == .dark ? Color.white
                                             : on ? scheme.onPrimary : scheme.onPrimaryContainer)
                            .labContrast(on ? "transportPrimary" : "npPills")
                            .frame(maxWidth: .infinity)
                            .frame(height: NowPlayingExpanded.tabRow)
                            // The unselected thinner, so the selection reads in light too.
                            .npPillGlass("npFloating",
                                         tint: on ? scheme.primary
                                             : lab.oneAccentPerLayer ? scheme.surfaceContainerHighest
                                             : scheme.primaryContainer,
                                         strength: on ? 1 : 0.6, in: Capsule())
                            .contentShape(.capsule)
                            .animation(Yoin.effects, value: on)
                    }
                    .buttonStyle(PressScale())
                    .accessibilityAddTraits(on ? .isSelected : [])
                }
            }
        }
    }

    /// The text tabs at rest, the button group expanded (Android StageTabs).
    private func tabsSlot(expanded: Bool) -> some View {
        ZStack(alignment: .topLeading) {
            tabs
                .opacity(expanded ? 0 : 1)
                .offset(y: expanded ? -6 : 0)
                .allowsHitTesting(!expanded)
            expandedTabs
                .opacity(expanded ? 1 : 0)
                .offset(y: expanded ? 0 : 8)
                .allowsHitTesting(expanded)
        }
    }

    /// The pills at rest; the expanded Note page's write bar.
    private func accessorySlot(page: NowPlayingDetailPage, expanded: Bool) -> some View {
        ZStack(alignment: .bottomLeading) {
            pills
                .opacity(expanded ? 0 : 1)
                .allowsHitTesting(!expanded)
            if expanded && page == .note {
                NoteWriteBarCapsule(scheme: scheme) { startWriting() }
                    .opacity(composing ? 0 : 1)
                    .transition(.opacity.combined(with: .offset(y: 12)))
            }
            if expanded && page == .lyrics, let t = engineTrack {
                lyricTools(t)
                    .transition(.opacity.combined(with: .offset(y: 12)))
            }
            if expanded && page == .about {
                AskGeminiBar(state: about.askState, scheme: scheme, onFocus: startAsking,
                             onDismissError: { about.dismissAskError() })
                    .opacity(asking ? 0 : 1)
                    .transition(.opacity.combined(with: .offset(y: 12)))
            }
        }
    }

    // MARK: Lyric tools (Android LyricsTools / LyricsActionBar / LyricsSelectionBar)

    private var loadedLyrics: NowPlayingData.LoadedLyrics? {
        guard let t = engineTrack, case .lines(let l) = data.lyricsState(t) else { return nil }
        return l
    }

    /// The editor opens on the song's lyrics as LRC (empty when there are none).
    private var editableLyrics: String { loadedLyrics.map { LyricsRules.editableText($0.lyrics) } ?? "" }

    /// The four tools, or — in select mode — the selection keys, in the same
    /// slot and footprint, cross-faded on the effects spring.
    @ViewBuilder
    private func lyricTools(_ t: PlayableTrack) -> some View {
        let actions = LyricsActions.shared
        let loaded = loadedLyrics
        let hasLines = !(loaded?.lines.isEmpty ?? true)
        let idle = actions.inFlight == nil
        ZStack(alignment: .bottomLeading) {
            if lyricsSelecting {
                LyricKeyRow(keys: [
                    LyricKey(icon: "close", label: "Done selecting lyrics", emphasized: true) {
                        withAnimation(Yoin.effects) { leaveLyricsSelect(follow: true) }
                    },
                    LyricKey(icon: "copy", label: "Copy selected lyrics", enabled: !lyricsSelected.isEmpty) {
                        copySelectedLyrics(t)
                    },
                    LyricKey(icon: "share", label: "Share selected lyrics as an image",
                             enabled: !lyricsSelected.isEmpty) { shareSelectedLyrics(t) },
                    LyricKey(icon: "edit", label: "Edit lyrics") {
                        withAnimation(Yoin.effects) { leaveLyricsSelect(follow: true) }
                        lyricsEditing = true
                    },
                ], scheme: scheme)
                .transition(.opacity.combined(with: .scale(scale: 0.92, anchor: .leading)))
            } else {
                LyricKeyRow(keys: [
                    LyricKey(icon: "search", label: "Search lyrics", enabled: idle) {
                        actions.openSearch(t)
                    },
                    LyricKey(icon: "translate", label: "Translate lyrics", enabled: idle && hasLines,
                             busy: actions.inFlight == .translate) {
                        withAnimation(Yoin.spatial) { actions.translate(t, lines: loaded?.lines ?? []) }
                    },
                    // ✓ = select lines; with no lines it opens the editor (paste lyrics).
                    LyricKey(icon: "check", label: hasLines ? "Select lyrics" : "Add lyrics", enabled: idle) {
                        if hasLines {
                            withAnimation(Yoin.effects) {
                                lyricsSelected = []
                                lyricsSelecting = true
                            }
                        } else {
                            lyricsEditing = true
                        }
                    },
                    LyricKey(icon: "recenter", label: "Return to current line",
                             enabled: idle && !lyricsFollow && (loaded?.synced ?? false)) {
                        lyricsFollow = true
                        lyricsRecenterTick += 1
                    },
                ], scheme: scheme)
                .transition(.opacity.combined(with: .scale(scale: 0.92, anchor: .leading)))
            }
        }
        .animation(Yoin.effects, value: lyricsSelecting)
    }

    /// Android LyricsFullscreenPane on the expanded page.
    private func expandedLyrics(_ t: PlayableTrack, _ loaded: NowPlayingData.LoadedLyrics) -> some View {
        let actions = LyricsActions.shared
        let translations = actions.shownTranslations(t, count: loaded.lines.count)
        let times = loaded.lyrics.lines.map(\.timeMs)
        return PlaybackPosition { position, _ in
            ExpandedLyricsList(
                lines: loaded.lines, times: times, translations: translations, synced: loaded.synced,
                active: loaded.activeIndex(positionSec: position),
                follow: lyricsFollow && !lyricsSelecting, recenterTick: lyricsRecenterTick,
                selecting: lyricsSelecting, selected: lyricsSelected, limitNudge: lyricsLimitNudge > 0,
                scheme: scheme,
                // Browsing for lines in select mode isn't "stop following".
                onUserScroll: { if !lyricsSelecting { lyricsFollow = false } },
                onTap: { i in lyricLineTapped(i, times: times, synced: loaded.synced) }
            )
        }
    }

    private func lyricLineTapped(_ i: Int, times: [Int64], synced: Bool) {
        if lyricsSelecting {
            let step = LyricsRules.nextSelection(lyricsSelected, tapped: i)
            if step.hitLimit {
                UINotificationFeedbackGenerator().notificationOccurred(.error)
                lyricsLimitNudge += 1
                let tick = lyricsLimitNudge
                Task { @MainActor in
                    try? await Task.sleep(for: .milliseconds(1800))
                    if lyricsLimitNudge == tick { lyricsLimitNudge = 0 }
                }
            } else {
                lyricsLimitNudge = 0
                UISelectionFeedbackGenerator().selectionChanged()
                lyricsSelected = step.selected
            }
            return
        }
        // Tap a timed line: seek there and follow again.
        guard synced, i < times.count, engineTrack != nil else { return }
        lyricsFollow = true
        lyricsRecenterTick += 1
        model.player.seek(to: Double(times[i]) / 1000)
    }

    private func leaveLyricsSelect(follow: Bool) {
        lyricsSelecting = false
        lyricsSelected = []
        lyricsLimitNudge = 0
        if follow { lyricsFollow = true }
    }

    private func pickedLyrics(_ t: PlayableTrack) -> (lines: [String], translations: [String?], starts: Set<Int>)? {
        guard let loaded = loadedLyrics else { return nil }
        let all = LyricsActions.shared.shownTranslations(t, count: loaded.lines.count)
        let picked = lyricsSelected.filter { $0 >= 0 && $0 < loaded.lines.count }.sorted()
        guard !picked.isEmpty else { return nil }
        return (picked.map { loaded.lines[$0] }, picked.map { all[$0] },
                LyricsRules.passageStarts(lyricsSelected, lineCount: loaded.lines.count))
    }

    /// Android onCopyClick: the text with its credit, then leave the mode.
    private func copySelectedLyrics(_ t: PlayableTrack) {
        guard let p = pickedLyrics(t) else { return }
        UIPasteboard.general.string = LyricsRules.clipboardText(lines: p.lines, translations: p.translations,
                                                                passageStarts: p.starts, title: t.title, artist: t.artist)
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        PlaylistActions.shared.show(p.lines.count == 1 ? "Copied 1 line" : "Copied \(p.lines.count) lines")
        withAnimation(Yoin.effects) { leaveLyricsSelect(follow: true) }
    }

    /// Android openShare: the pick frozen into the share card.
    private func shareSelectedLyrics(_ t: PlayableTrack) {
        guard let p = pickedLyrics(t) else { return }
        lyricsShare = LyricsShareSnapshot(lines: p.lines, translations: p.translations, passageStarts: p.starts,
                                          title: t.title, artist: t.artist)
    }

    // MARK: Stage

    /// Cover tap (Android CompactPlayingContent): focus ⇄ rest where focus
    /// exists; the docked cover goes back to focus (or rest).
    private func coverTapped(focusOffered: Bool, shortOffered: Bool = false) {
        withAnimation(Yoin.spatial) {
            if stage == .compact, !focusOffered {
                // Android short focus: tap in, tap again to leave.
                if shortOffered || shortFocus { shortFocus.toggle() }
                return
            }
            switch stage {
            case .focus:
                stage = .compact
            case .expanded:
                tab = NowPlayingDetailPage.lyrics.rawValue
                stage = focusOffered ? .focus : .compact
            case .compact:
                guard focusOffered else { return }
                tab = NowPlayingDetailPage.lyrics.rawValue
                stage = .focus
            }
        }
    }

    /// The small window opens its page; an empty Note page opens on the composer.
    private func openPage(_ page: NowPlayingDetailPage) {
        let key = currentNoteKey
        if page == .note, NotesStore.shared.notes(provider: key.provider, trackId: key.rawId).isEmpty {
            startWriting()
            return
        }
        withAnimation(Yoin.spatial) {
            tab = page.rawValue
            stage = .expanded
        }
    }

    /// The Ask bar opens over the keyboard (Android onAskBarFocused, then the
    /// field takes focus once it is in the tree).
    private func startAsking() {
        withAnimation(Yoin.spatial) { about.askFocused() }
        guard asking else { return }
        Task { @MainActor in askFieldFocused = true }
    }

    /// The open Ask bar over the page's foot, above the keyboard. A tap
    /// above it puts the keyboard away (and the bar folds back).
    private var askLayer: some View {
        VStack(spacing: 0) {
            Color.clear
                .contentShape(.rect)
                .onTapGesture {
                    askFieldFocused = false
                    withAnimation(Yoin.spatial) { about.askCollapse() }
                }
            AskGeminiField(scheme: scheme, text: $askDraft, focus: $askFieldFocused) { question in
                guard let t = engineTrack else { return }
                withAnimation(Yoin.spatial) {
                    about.ask(title: t.title, artist: t.artist, album: t.album, question: question)
                }
            }
            .padding(.horizontal, Tokens.np.COLUMN_PADDING.cg)
            .padding(.bottom, 8)
        }
    }

    /// The chevron: back to the resting player, or (at rest) collapse.
    private func stageBack() {
        if asking {
            askFieldFocused = false
            withAnimation(Yoin.spatial) { about.askCollapse() }
        }
        guard stage != .compact else { return collapse() }
        if editingNoteId != nil { endEditing() }
        composerFocused = false
        withAnimation(Yoin.spatial) {
            if draft.isEmpty { composing = false }
            stage = .compact
        }
    }

    /// Unsynced lyrics scroll by hand in the small window: their own tap opens the page.
    private func unsyncedLyricsShown(_ page: NowPlayingDetailPage, stage: NowPlayingStage) -> Bool {
        guard page == .lyrics, stage == .compact, let t = engineTrack,
              case .lines(let loaded) = data.lyricsState(t) else { return false }
        return !loaded.synced
    }

    /// The current song's note moments as fractions of the bar.
    private func noteAnchors(duration: Double) -> [Double] {
        guard duration > 0 else { return [] }
        let key = currentNoteKey
        return NotesStore.shared.notes(provider: key.provider, trackId: key.rawId)
            .compactMap { $0.positionMs.map { Double($0) / 1000 / duration } }
    }

    // 5. The detail page. Compact: the small window (Lyrics / About / Note),
    // a list with the active line at 22 % of the window (or one line on
    // short screens) that runs on `under` points further down, behind the
    // glass transport. Focus: the current line under the grown cover.
    // Expanded: the whole page.
    private func lyricArea(_ p: PlaybackSnapshot, page: NowPlayingDetailPage, stage: NowPlayingStage,
                           preview: Bool, clear: CGFloat, under: CGFloat,
                           anchor: CGFloat, fadeTop: Bool) -> some View {
        ZStack(alignment: .topLeading) {
            switch stage {
            case .expanded:
                expandedPage(p, page: page, height: clear)
                    .id(page)
                    .transition(.opacity)
            case .focus where preview:
                lyricPreview(p)
                    .frame(height: clear)
                    .transition(.opacity)
            default:
                compactPage(p, page: page, clear: clear, under: under, anchor: anchor, fadeTop: fadeTop)
                    .id(page)
                    .transition(.opacity)
            }
        }
        .animation(Yoin.effects, value: page)
    }

    @ViewBuilder
    private func compactPage(_ p: PlaybackSnapshot, page: NowPlayingDetailPage, clear: CGFloat, under: CGFloat,
                             anchor: CGFloat, fadeTop: Bool) -> some View {
        switch page {
        case .lyrics:
            lyrics(p, clear: clear, under: under, anchor: anchor, fadeTop: fadeTop)
        case .about:
            // Android SongInfoDisplay: the facts, read-only, fading out at the foot.
            NowPlayingAboutView(state: aboutState(p), expanded: false, scheme: scheme)
                .frame(height: clear, alignment: .top)
                .contentShape(.rect)
                .onTapGesture { openPage(.about) }
                .accessibilityAction(named: "About") { openPage(.about) }
        case .note:
            noteWindow(clear: clear)
        }
    }

    @ViewBuilder
    private func expandedPage(_ p: PlaybackSnapshot, page: NowPlayingDetailPage, height: CGFloat) -> some View {
        switch page {
        case .lyrics:
            if let t = engineTrack, case .lines(let loaded) = data.lyricsState(t) {
                expandedLyrics(t, loaded)
                    // New lyrics (a search / edit applied) open as a new list.
                    .id(ObjectIdentifier(loaded.lyrics))
                    .frame(height: height, alignment: .top)
            } else {
                lyrics(p, clear: height, under: 0, anchor: height * Tokens.np.LYRICS_ANCHOR_FRACTION.cg, fadeTop: true)
            }
        case .about:
            NowPlayingAboutView(state: aboutState(p), expanded: true, scheme: scheme, bottomPadding: 48)
                .frame(height: height, alignment: .top)
        case .note:
            notePage
                .frame(height: height, alignment: .top)
        }
    }

    private func aboutState(_ p: PlaybackSnapshot) -> NowPlayingData.AboutState {
        guard let t = engineTrack else { return .ready(SongFacts(album: p.album, track: p.track)) }
        return data.aboutState(t)
    }

    /// Android NoteFullscreenPane over the store: seek, align and delete go
    /// straight to it; the list shows the draft while writing.
    private var notePage: some View {
        let key = currentNoteKey
        let notes = NotesStore.shared.notes(provider: key.provider, trackId: key.rawId)
        return NowPlayingNotePage(
            notes: NotesStore.sorted(notes, noteSort), sort: $noteSort, scheme: scheme,
            draftAnchorMs: draftAnchorMs, writing: composing,
            playheadMs: { playheadMs },
            onSeek: { ms in if engineTrack != nil { model.player.seek(to: Double(ms) / 1000) } },
            onDelete: { NotesStore.shared.delete(id: $0) },
            onRestore: { NotesStore.shared.restore($0) },
            onRealign: { NotesStore.shared.realign(id: $0, positionMs: $1) },
            editingId: editingNoteId,
            onEdit: { startEditing($0) }
        )
    }

    /// Android OneLineLyricPreview: the current line alone, the song's title
    /// before the first one.
    @ViewBuilder
    private func lyricPreview(_ p: PlaybackSnapshot) -> some View {
        if let t = engineTrack {
            if case .lines(let loaded) = data.lyricsState(t), loaded.synced {
                PlaybackPosition { position, _ in
                    let i = loaded.activeIndex(positionSec: position)
                    OneLineLyricLine(text: i >= 0 && i < loaded.lines.count && !loaded.lines[i].isEmpty
                                     ? loaded.lines[i] : t.title, primary: scheme.primary)
                        .equatable()
                }
            } else {
                OneLineLyricLine(text: t.title, primary: scheme.primary)
            }
        } else {
            OneLineLyricLine(text: p.lyrics.indices.contains(p.activeLyric) ? p.lyrics[p.activeLyric] : p.track.title,
                             primary: scheme.primary)
        }
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
                        .onTapGesture { if stage == .compact { openPage(.lyrics) } }
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
    /// the label (a tap opens the composer).
    @ViewBuilder
    private func noteWindow(clear: CGFloat) -> some View {
        let key = currentNoteKey
        let notes = NotesStore.shared.notes(provider: key.provider, trackId: key.rawId)
        if notes.isEmpty {
            windowLabel("Write", clear: clear)
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
        .mask {
            if under <= 0 {
                // Expanded: nothing runs under it; the foot dissolves.
                NowPlayingAboutView.fade(top: fadeTop ? 16 : 0, bottom: 64)
            } else {
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
            }
        }
        .allowsHitTesting(interactive)
    }

    // 6. Transport: [PAUSE][Next] … [Repeat] / [Prev] 1:14 ~~~ -2:41
    /// `short` (Android short focus, prevToTopRow = 1): PREVIOUS glides up
    /// to the top row's start (PLAY and NEXT make room) and the progress bar
    /// takes the second row alone, 36 pt tall. One button the whole way, so
    /// one press state and one glass.
    private func transport(_ p: PlaybackSnapshot, short: Bool = false) -> some View {
        let control = Tokens.np.CONTROL_SIZE.cg
        let gap = Tokens.np.TRANSPORT_GAP.cg
        let rowGap = Tokens.np.CONTROL_ROW_GAP.cg
        let secondRow = short ? NowPlayingBudget.shortFocusProgressRow : control
        // One glass layer: Pause, Next and Prev sit 4–8 pt apart. A 3 pt
        // container spacing keeps them separate at rest (10 bridged Pause and
        // Prev into one blob) and lets neighbours blend as interactive glass
        // swells on press.
        return GlassEffectContainer(spacing: lab.extra("containerSpacing")) {
        VStack(spacing: rowGap) {
            HStack(spacing: gap) {
                Button { model.player.togglePlayPause() } label: {
                    // titleLarge × 0.9, Bold, 0.5 tracking; the playing
                    // stretch (1.06) grows from the word's leading edge.
                    Text(p.isPlaying ? "PAUSE" : "PLAY")
                        .font(.gsf(19.8, .bold))
                        .tracking(0.5)
                        .scaleEffect(x: 1.06, y: 1, anchor: .leading)
                        // Same rule as the pills on the same primary glass:
                        // dark onPrimary sank into thin glass (Balanced);
                        // dense glass (Gemini) carries it.
                        .foregroundStyle(colorScheme == .dark && !lab.denseAccent("transportPrimary", dark: true)
                                         ? Color.white : scheme.onPrimary)
                        .labContrast("transportPrimary")
                        .padding(.horizontal, 24)
                        .frame(height: 56)
                        .contentShape(.capsule)
                }
                .buttonStyle(PressReport { touch("pause", $0) })
                .labGlass("transportPrimary", scheme: scheme, brand: brand, auto: scheme.primary, in: Capsule())
                .accessibilityLabel(p.isPlaying ? "Pause" : "Play")
                // PREVIOUS's place in this row, opening as it arrives.
                .padding(.leading, short ? control + gap : 0)
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
                // PREVIOUS's slot narrows to nothing as the button rises.
                Spacer().frame(width: short ? 0 : control + gap)
                progress(p)
            }
            .font(.gsf(14, .medium))
            .monospacedDigit()
            .foregroundStyle(scheme.onSurfaceVariant)
            .frame(height: secondRow)
        }
        .overlay(alignment: .topLeading) {
            // PREVIOUS: at the start of the progress row at rest, at the
            // start of the top row in short focus.
            roundButton("skip_previous_filled", fill: scheme.secondary, ink: scheme.onSecondary, label: "Previous") {
                model.player.previous()
            }
            .offset(y: short ? 0 : control + rowGap + (secondRow - control) / 2)
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
                            noteAnchors: noteAnchors(duration: duration),
                            noteInk: colorScheme == .dark ? Color.white : scheme.onPrimary,
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
                 in: ScrubberGlassShape(grow: scrubberLifted ? 1 : 0, leading: 4, trailing: 10))
    }

    /// The scrubber's glass: any control touched (iOS 26 Music), or (World,
    /// R1) only the progress bar itself while it is scrubbed.
    private var scrubberLifted: Bool {
        lab.extra("liftInFlow") > 0.5 ? interacting && scrubbing : interacting
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
        // Untinted (Balanced / HIG presets) or a neutral floor (Gemini):
        // the glyph goes on-surface.
        let tinted = GlassLab.shared.style("transportSecondary").tintOn
            && !GlassLab.shared.neutralTint("transportSecondary")
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
            .npMorphAnchor(.title)
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
            // Gemini preset: a neutral floor, so on-surface ink.
            // World: a solid primaryContainer pill, its own on-color.
            .foregroundStyle(lab.neutralTint("npPills") ? scheme.onSurface
                             : lab.style("npPills").role == .primaryContainer ? scheme.onPrimaryContainer
                             : colorScheme == .dark ? Color.white : scheme.onPrimary)
            // Same contrast shadow as PAUSE on the same primary glass; a
            // tonal pill (World) needs none.
            .labContrast(lab.style("npPills").variant == .none ? "npPills" : "transportPrimary")
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
            tab = NowPlayingDetailPage.note.rawValue
            stage = .expanded
            composing = true
        }
        composerFocused = true
    }

    /// The Note page's long-press Edit: the composer opens on the note's own
    /// words and moment (an unsaved new draft is parked meanwhile).
    private func startEditing(_ note: SongNote) {
        if editingNoteId == nil, !draft.isEmpty { parkedDraft = (draft, draftAnchorMs) }
        withAnimation(Yoin.spatial) {
            editingNoteId = note.id
            draft = note.content
            draftAnchorMs = note.positionMs
            tab = NowPlayingDetailPage.note.rawValue
            stage = .expanded
            composing = true
        }
        composerFocused = true
    }

    /// Save on an edit: the words (and the moment, when the stamp was
    /// tapped to align it to now) go back to the note.
    private func saveEdit(_ id: String) {
        // Deleted meanwhile: nothing to write back to.
        guard let before = NotesStore.shared.notes.first(where: { $0.id == id }) else { return endEditing() }
        guard NotesStore.shared.update(id: id, content: draft) != nil else { return }
        if let ms = draftAnchorMs, ms != before.positionMs { NotesStore.shared.realign(id: id, positionMs: ms) }
        notesSaved += 1
        endEditing()
    }

    /// Back from an edit: the keyboard goes down; a parked new draft comes
    /// back to the composer, waiting under the Write bar.
    private func endEditing() {
        composerFocused = false
        withAnimation(Yoin.spatial) {
            editingNoteId = nil
            if let parked = parkedDraft {
                draft = parked.words
                draftAnchorMs = parked.anchorMs
            } else {
                draft = ""
                draftAnchorMs = nil
            }
            parkedDraft = nil
            composing = false
        }
    }

    private func saveNote() {
        if let id = editingNoteId { return saveEdit(id) }
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
                .onTapGesture {
                    composerFocused = false
                    if draft.isEmpty && editingNoteId == nil { withAnimation(Yoin.spatial) { composing = false } }
                }
            NoteComposer(scheme: scheme, text: $draft, anchorMs: draftAnchorMs, focus: $composerFocused,
                         onRealign: { withAnimation(Yoin.spatialFast) { draftAnchorMs = playheadMs } },
                         onSave: saveNote,
                         onCancel: editingNoteId == nil ? nil : { endEditing() })
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
                    // Held, the fill goes full strength (World rests it lower).
                    .fill(scheme.primary.opacity(held ? 1 : GlassLab.shared.extra("ratingFill")))
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

    /// The NP pills' glass (Glass Lab "npPills": variant, tint opacity,
    /// shadow) in another tint, for the expanded page's floating controls
    /// (tabs, Ask Gemini, About's buttons). `strength` thins the tint (Ask
    /// Gemini loading). Non-glass presets get the solid fill they had.
    @ViewBuilder
    func npPillGlass<S: Shape>(_ id: String = "npPills", tint: Color, strength: Double = 1, shadow: Bool = true,
                               in shape: S) -> some View {
        let st = GlassLab.shared.style(id)
        Group {
            switch st.variant {
            case .regular, .clear:
                let base: Glass = st.variant == .clear ? .clear : .regular
                let tinted = st.tintOn ? base.tint(tint.opacity(st.tintOpacity * strength)) : base
                glassEffect(st.interactive ? tinted.interactive() : tinted, in: shape)
            case .none, .material:
                background(shape.fill(tint.opacity(strength)))
            }
        }
        .shadow(color: GlassLab.shared.shadowColor(tint).opacity(shadow ? st.shadowOpacity : 0),
                radius: st.shadowRadius, y: st.shadowY)
    }

    /// A Glass Lab surface that turns into interactive glass while `held`,
    /// with its tint thinned so the glass itself carries the look. One
    /// `glassEffect` either way, so holding doesn't rebuild the glass.
    /// Non-glass variants (Apple HIG preset) fall back to `labGlass`.
    @ViewBuilder
    func npGlass<S: Shape>(_ id: String, scheme: CoverScheme, auto: Color, held: Bool, in shape: S) -> some View {
        let st = GlassLab.shared.style(id)
        if GlassLab.shared.extra("liftInFlow") > 0.5 {
            // World: tonal at rest, lifted into glass while held.
            labLift(id, lifted: held, scheme: scheme, auto: auto, in: shape)
        } else {
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
}
