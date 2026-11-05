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
struct NowPlayingView: View, Equatable {
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

    /// Its inputs; state, environment and the model it observes update it as usual.
    nonisolated static func == (a: NowPlayingView, b: NowPlayingView) -> Bool {
        a.npNamespace == b.npNamespace && a.panel == b.panel && a.open == b.open
    }

    @State private var dragY: CGFloat = 0
    @State private var tab = 0
    /// Android's detail HorizontalPager: a horizontal drag across the page
    /// moves Lyrics / About / Note under the finger (points, + = toward the
    /// previous page); the tabs and the bar under the page follow it.
    @State private var pagerDrag: CGFloat = 0
    @State private var pagerWidth: CGFloat = 1
    /// Android NowPlayingStageMode (NowPlayingStage.swift): compact, the
    /// expanded detail page, or the focused cover.
    @State private var stage: NowPlayingStage = .compact
    /// Android short focus (NowPlayingBudget.ShortFocus): the cover tap's
    /// focus where the regular one isn't offered. Compact only.
    @State private var shortFocus = false
    /// The expanded Note page's order (Android NoteSortMode).
    @State private var noteSort: NotesStore.SortMode = .timeline
    @State private var rating: Double? = 6.3
    @State private var favorite = true
    /// One "hands on the controls" state for rating, heart, transport and
    /// the progress bar: while it is on, the progress bar sits in glass.
    /// `-screen np-touch` starts with it (and a held rating) on.
    @State private var interacting = LaunchConfig.baseScreen == "np-touch"
    @State private var ratingHeld = LaunchConfig.baseScreen == "np-touch"
    /// The song a held rating gesture belongs to (set on touch-down).
    @State private var ratingTarget: PlayableTrack?
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
    @State private var parkedDraft: (words: String, anchorMs: Int64?, target: NoteTarget?)?
    /// The song the draft is written FOR (Android NoteDraft.target): bound
    /// with its moment when writing begins, held across a skip until saved,
    /// cleared or re-aligned to now. Save files it there, not on whatever
    /// plays by then.
    @State private var draftTarget: NoteTarget?
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
    @State private var lyricsEditTarget: PlayableTrack?
    @State private var lyricsShare: LyricsShareSnapshot?
    /// The transport row is on screen (Android stageControlsLive): off once
    /// the expanded stage has settled over it, on as soon as it collapses.
    @State private var transportLive = true
    /// Android auto-immersive lyric tools (LyricToolsIdleState): stepped away.
    @State private var lyricToolsIdle = false
    @State private var lyricToolsTouchedAt = Date()
    /// Which way the lyric stream moves on a song change (forward in the queue = up).
    @State private var lyricsDirection = LyricsDirection()

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
        withSheets
            .onChange(of: engineTrack.map(NowPlayingData.key)) {
                // Android: another song, the Ask bar starts over.
                about.resetAsk()
                ratingTargetReplaced()
            }
            .onChange(of: draft) { old, new in draftChanged(from: old, to: new) }
            .task(id: lyricToolsIdleEligible) { await trackLyricToolsIdle(lyricToolsIdleEligible) }
            // Any touch on the player re-arms the count (passive: it never
            // takes a touch from anything).
            .background(TouchWatcher { wakeLyricTools() })
    }

    // MARK: Lyric tools idle (Android LyricToolsIdle.kt)

    /// Playing synced lyrics that follow the playhead, on the expanded
    /// Lyrics page, not selecting lines (Android lyricToolsIdleEligible).
    private var lyricToolsIdleEligible: Bool {
        guard model.player.isPlaying, stage == .expanded, tab == NowPlayingDetailPage.lyrics.rawValue,
              lyricsFollow, !lyricsSelecting, let l = loadedLyrics else { return false }
        return l.synced
    }

    /// Android LyricToolsIdleMs (3 s) and its 200 ms poll; a fresh count
    /// each time eligibility returns. Not eligible: the tools are back.
    private func trackLyricToolsIdle(_ eligible: Bool) async {
        if lyricToolsIdle { withAnimation(Self.lyricToolsSpring) { lyricToolsIdle = false } }
        guard eligible else { return }
        lyricToolsTouchedAt = .now
        while !Task.isCancelled {
            let elapsed = Date.now.timeIntervalSince(lyricToolsTouchedAt)
            if elapsed >= 3 {
                if !lyricToolsIdle { withAnimation(Self.lyricToolsSpring) { lyricToolsIdle = true } }
                try? await Task.sleep(for: .milliseconds(200))
            } else {
                try? await Task.sleep(for: .seconds(3 - elapsed))
            }
        }
    }

    private func wakeLyricTools() {
        lyricToolsTouchedAt = .now
        if lyricToolsIdle { withAnimation(Self.lyricToolsSpring) { lyricToolsIdle = false } }
    }

    /// The slot folds on the stage's spring (it is a stage reshape); the
    /// tools' alpha rides the same transaction.
    private static var lyricToolsSpring: Animation { Yoin.stageSettle }

    /// The page, its keyboard tracking, its sheets and their song resets.
    private var withSheets: some View {
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
            QueueSheet().reportsSheetRise()
        }
        // Every sheet NP presents reports its rise, so the page recedes under
        // it (World depth) as it does under the Queue.
        // The lyric tools' sheets (Android LyricsSearchSheet / LyricsApplyDialog / LyricsShareSheet).
        .sheet(isPresented: Binding(get: { LyricsActions.shared.search.isOpen },
                                    set: { if !$0 { LyricsActions.shared.dismissSearch() } })) {
            LyricsSearchSheet(scheme: scheme, onSelect: { match in
                // The song the search was opened for, never the live one.
                if let t = LyricsActions.shared.search.target { LyricsActions.shared.applySearchResult(match, for: t) }
            }, onClose: { LyricsActions.shared.dismissSearch() })
            .reportsSheetRise()
            .presentationDetents([.large])
        }
        .sheet(isPresented: $lyricsEditing) {
            LyricsEditSheet(scheme: scheme, initialText: lyricsEditTarget.map(editableLyrics) ?? "", onApply: { text in
                lyricsEditing = false
                // The song the editor was opened on, never the live one.
                if let t = lyricsEditTarget { LyricsActions.shared.applyEdited(text, for: t) }
            }, onCancel: { lyricsEditing = false })
            .reportsSheetRise()
            .presentationDetents([.medium, .large])
        }
        .sheet(item: $lyricsShare) { snap in
            LyricsShareSheet(snapshot: snap, scheme: scheme) { lyricsShare = nil }
                .reportsSheetRise()
                .presentationDetents([.large])
        }
        // Select mode, the lyric search and the editor belong to one song's
        // lyrics (Android resets them on a song change).
        .onChange(of: engineTrack.map(NowPlayingData.key)) { _, _ in
            leaveLyricsSelect(follow: true)
            if LyricsActions.shared.search.isOpen { LyricsActions.shared.dismissSearch() }
            lyricsEditing = false
        }
        .onChange(of: stage) { _, s in if s != .expanded { leaveLyricsSelect(follow: true) } }
        .onChange(of: tab) { _, t in if t != NowPlayingDetailPage.lyrics.rawValue { leaveLyricsSelect(follow: false) } }
        .task(id: engineTrack.map(NowPlayingData.key)) {
            guard let t = engineTrack else { return }
            await data.prepare(t, client: model.session.client, apple: model.store.apple)
        }
        // The next song's lyrics, once this one has settled (a skip cancels it).
        .task(id: model.player.upNextTrack.map(NowPlayingData.key)) {
            guard let next = model.player.upNextTrack else { return }
            try? await Task.sleep(for: .seconds(2))
            guard !Task.isCancelled else { return }
            await data.prefetchLyrics(next, client: model.session.client)
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
            if let s = NowPlayingStageLaunch.stage { withAnimation(Yoin.stageSettle) { stage = s } }
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
                    withAnimation(Yoin.stageSettle) { tab = NowPlayingDetailPage.note.rawValue; stage = .expanded }
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
        .task(id: stage == .expanded) {
            // Collapse starts: the wave runs again at once. Expanded: it
            // stops once the reshape has settled (the row has faded away).
            guard stage == .expanded else { transportLive = true; return }
            try? await Task.sleep(for: .seconds(0.6 / max(LaunchConfig.animationSpeed, 0.05)))
            if !Task.isCancelled, stage == .expanded { transportLive = false }
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
        // The pager's target page (Android targetPage): the tabs and the
        // bar under the page switch half way through a swipe.
        let page = NowPlayingDetailPage(rawValue: shownTab) ?? .lyrics
        // Expanded (Android ExpandedTopBarHeight …): the cover docks into the
        // top bar, the transport steps away, the page takes what is left.
        let topBarH = expanded ? NowPlayingExpanded.topBar : pose.topBar
        let coverGap = expanded ? NowPlayingExpanded.coverGap : pose.coverGap
        let tabsH = expanded ? NowPlayingExpanded.tabRow : pose.tabs
        let tabGap = expanded ? NowPlayingExpanded.tabGap : pose.tabGap
        let transportH = expanded ? 0 : (short?.transport ?? pose.transport)
        let accessory = expanded
            ? (page == .note || page == .about ? NowPlayingExpanded.writeBar + NowPlayingExpanded.writeBarGap
                // Idle (Android auto-immersive): the tools step away and
                // their slot folds; the title sinks, the lyrics grow.
                : page == .lyrics && engineTrack != nil
                    ? (lyricToolsIdle ? NowPlayingExpanded.bareAccessory : NowPlayingExpanded.lyricTools)
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
                              ? Self.oneLineAnchor(clear)
                              : clear * Tokens.np.LYRICS_ANCHOR_FRACTION.cg, fadeTop: !oneLine, oneLine: oneLine)
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
            // The whole window takes the swipe, empty parts included.
            .contentShape(.rect)
            // Not in focus, nor on the one-line row (Android: swipe once the
            // lyric page has room), nor while selecting, writing or asking.
            .gesture(pagerPan(enabled: stage != .focus && !(oneLine && stage == .compact)
                                  && !lyricsSelecting && !composing && !asking))
            .npFade(open, contentFade)
            Spacer().frame(height: slack)
            hero(p, inline: budget.inlineHero)
                // Short focus folds the title away.
                .opacity(short != nil ? 0 : 1)
                .allowsHitTesting(short == nil)
                // StageHeightSlot: content at the top, air below.
                .frame(height: short != nil ? 0 : pose.hero, alignment: .top)
            accessorySlot(page: page, expanded: expanded)
                // The expanded page's bar rides the swipe (Android's
                // accessory pager, at most half a page either way); the
                // pills at rest belong to every page and stay.
                .offset(x: expanded ? -(pagerPosition - CGFloat(shownTab)) * pagerStride : 0)
                .frame(height: accessory, alignment: .bottomLeading)
                .overlay(alignment: .bottom) {
                    // Android lyricToolsWakeGuard: while the tools are away,
                    // the first tap where they stood only brings them back
                    // (the title sank into that band).
                    if lyricToolsIdle {
                        Color.clear
                            .frame(height: NowPlayingExpanded.lyricTools)
                            .contentShape(.rect)
                            .onTapGesture { wakeLyricTools() }
                            .accessibilityHidden(true)
                    }
                }
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
            routeLink(albumRoute(p), label: "Go to album") {
                VStack(alignment: .leading, spacing: 0) {
                    // PlayingFromLabel: labelSmall kind over labelMedium name.
                    Text("PLAYING FROM ALBUM")
                        .yoinRole(Tokens.type.labelSmall)
                        .foregroundStyle(scheme.onSurfaceVariant.opacity(0.55))
                    Text(p.album.title)
                        .yoinRole(Tokens.type.labelMedium)
                        .foregroundStyle(scheme.onSurface)
                }
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
                RatingBar(value: ratingBinding, scheme: scheme, held: ratingHeld,
                          gestureKey: engineTrack.map(NowPlayingData.key) ?? "mock") { down in
                    // One spring lifts and settles the bar (scale, glass, shadow).
                    withAnimation(Yoin.spatial) { ratingHeld = down }
                    touch("rating", down)
                    if down {
                        ratingTarget = engineTrack
                    } else if let t = ratingTarget {
                        // Committed to the song the finger went down on.
                        data.commitRating(t, client: model.session.client)
                        ratingTarget = nil
                    }
                }
                .frame(width: Tokens.np.RATING_BAR_WIDTH.cg, height: max(0, cover - Tokens.np.RATING_BAR_INSET.cg))
                Spacer().frame(height: Tokens.np.RATING_TO_HEART_GAP.cg)
                heart
                Spacer().frame(height: Tokens.np.HEART_BOTTOM_GAP.cg)
            }
            // World: pressing the rating lifts the heart with it; their glass
            // shares one container, so the two shapes reach for each other.
            .background(alignment: .top) {
                if heartLifts {
                    RatingHeartLift(lifted: ratingHeld, scheme: scheme,
                                    bar: CGSize(width: Tokens.np.RATING_BAR_WIDTH.cg,
                                                height: max(0, cover - Tokens.np.RATING_BAR_INSET.cg)),
                                    gap: Tokens.np.RATING_TO_HEART_GAP.cg, heart: Tokens.np.HEART_SIZE.cg,
                                    heartTint: heartSurfaceTint)
                }
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

    /// The engine's song: this device's (or a synced) rating, nil = never
    /// rated (Android reads local_ratings only, never the server's stars);
    /// the mock: the screenshot value.
    private var ratingBinding: Binding<Double?> {
        guard let t = engineTrack else { return $rating }
        return Binding(get: { data.rating(t) }, set: { data.dragRating($0, ratingTarget ?? t) })
    }

    /// The song changed under a held rating (auto-advance): Android cancels
    /// the replaced item's gesture without a commit. The draft is dropped, the
    /// bar settles, and RatingBar ignores the rest of that touch.
    private func ratingTargetReplaced() {
        guard let t = ratingTarget else { return }
        data.discardDraftRating(t)
        ratingTarget = nil
        withAnimation(Yoin.spatial) { ratingHeld = false }
        touch("rating", false)
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
        .modifier(HeartSurface(world: heartLifts, lifted: ratingHeld, rest: heartSurfaceTint,
                               surface: surface, scheme: scheme, brand: brand,
                               auto: favorite ? scheme.tertiary : scheme.tertiaryContainer))
        .scaleEffect(heartLifts && ratingHeld ? RatingLift.scale : 1)
        .animation(Yoin.effects, value: favorite)
        .accessibilityLabel(heartLabel(favorite))
    }

    /// World (liftInFlow, tonal heart): the heart rises into glass with the
    /// rating bar while the bar is held. Pressing the heart alone only toggles.
    private var heartLifts: Bool {
        lab.extra("liftInFlow") > 0.5 && lab.style("heart").variant == .none
            && !(isFavorite && lab.extra("heartLikedAccent") > 0.5)
    }

    /// The heart's own surface colour (liked: tertiary), kept in the glass.
    private var heartSurfaceTint: Color {
        let favorite = isFavorite
        let st = lab.style("heart")
        let o = st.tintOn ? lab.tintOpacity("heart", dark: colorScheme == .dark) : 0
        return st.role.color(in: scheme, brand: brand, auto: favorite ? scheme.tertiary : scheme.tertiaryContainer)
            .opacity(o)
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
                let on = shownTab == i
                Button { settlePager(on: i) } label: {
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
                    let on = shownTab == page.rawValue
                    Button { settlePager(on: page.rawValue) } label: {
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
                    .opacity(lyricToolsIdle ? 0 : 1)
                    .allowsHitTesting(!lyricToolsIdle)
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
    private func editableLyrics(_ t: PlayableTrack) -> String {
        guard case .lines(let l) = data.lyricsState(t) else { return "" }
        return LyricsRules.editableText(l.lyrics)
    }

    /// The editor opens on this song (and only ever applies to it).
    private func openLyricsEditor(_ t: PlayableTrack) {
        lyricsEditTarget = t
        lyricsEditing = true
    }

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
                        openLyricsEditor(t)
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
                            openLyricsEditor(t)
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
        // No PlaybackPosition around it: a list built inside that closure kept the follow / recenter
        // values of the render that made the closure, so a scroll never stopped following and
        // Recenter never reached the list. It reads the playhead itself.
        return ExpandedLyricsList(
                lines: loaded.lines, times: times, translations: translations, loaded: loaded,
                follow: lyricsFollow && !lyricsSelecting, recenterTick: lyricsRecenterTick,
                selecting: lyricsSelecting, selected: lyricsSelected, limitNudge: lyricsLimitNudge > 0,
                scheme: scheme,
                // Browsing for lines in select mode isn't "stop following".
                onUserScroll: {
                    if !lyricsSelecting { lyricsFollow = false }
                    wakeLyricTools()
                },
                onTap: { i in lyricLineTapped(i, times: times, synced: loaded.synced) }
            )
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
        withAnimation(Yoin.stageSettle) {
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
        withAnimation(Yoin.stageSettle) {
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
            withAnimation(Yoin.stageSettle) { about.askCollapse() }
        }
        guard stage != .compact else { return collapse() }
        if editingNoteId != nil { endEditing() }
        composerFocused = false
        withAnimation(Yoin.stageSettle) {
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
                           anchor: CGFloat, fadeTop: Bool, oneLine: Bool) -> some View {
        // Focus's one-line preview of synced lyrics is the compact list
        // itself, the active line alone, moved to the preview's anchor: one
        // lyric object travels (a separate preview tree cross-fading with
        // the list drew the line twice at two anchors).
        let solo = stage == .focus && preview && soloLyricsAvailable
        return ZStack(alignment: .topLeading) {
            switch stage {
            case .expanded:
                detailPager { pg in
                    expandedPage(p, page: pg, height: clear)
                }
                .transition(.opacity)
            case .focus where preview && !solo:
                lyricPreview(p)
                    .frame(height: clear)
                    .transition(.opacity)
            case .focus:
                compactPage(p, page: page, clear: clear, under: under,
                            anchor: solo ? Self.oneLineAnchor(clear) : anchor,
                            fadeTop: solo ? false : fadeTop, wraps: !oneLine && !solo, solo: solo)
                    .transition(.opacity)
            case .compact:
                // The one-line row is not a pager page (Android: swipe only
                // once the lyric page has room).
                detailPager { pg in
                    compactPage(p, page: pg, clear: clear, under: under, anchor: anchor,
                                fadeTop: fadeTop, wraps: !oneLine, solo: false)
                }
                .transition(.opacity)
            }
        }
    }

    // MARK: Detail pager (Android HorizontalPager over Lyrics / About / Note)

    /// Where the pager is: the settled tab, less the finger's drag in pages.
    private var pagerPosition: CGFloat { CGFloat(tab) - pagerDrag / max(pagerWidth, 1) }

    /// The page the pager is heading for: the tabs show it.
    private var shownTab: Int {
        min(max(Int(pagerPosition.rounded()), 0), NowPlayingDetailPage.allCases.count - 1)
    }

    /// One page across: the window plus the column's two margins, so a
    /// neighbour comes in from the screen's edge, not from inside the margin.
    private var pagerStride: CGFloat { pagerWidth + Tokens.np.COLUMN_PADDING.cg * 2 }

    /// A tab tap slides to its page on the same spring as a released swipe.
    private func settlePager(on page: Int) {
        withAnimation(Yoin.spatial) {
            tab = page
            pagerDrag = 0
        }
    }

    /// The three pages side by side, moved by the pager's position; the one
    /// on screen and its neighbours are built (Android beyondViewportPageCount 1).
    private func detailPager<Page: View>(@ViewBuilder page: @escaping (NowPlayingDetailPage) -> Page) -> some View {
        let pos = pagerPosition
        return ZStack(alignment: .topLeading) {
            ForEach(NowPlayingDetailPage.allCases, id: \.self) { pg in
                let d = CGFloat(pg.rawValue) - pos
                if abs(d) < 1.5 {
                    page(pg)
                        .offset(x: d * pagerStride)
                        .allowsHitTesting(pg.rawValue == shownTab)
                        .accessibilityHidden(pg.rawValue != shownTab)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .topLeading)
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { pagerWidth = max($0, 1) }
    }

    /// The pager's swipe, on the whole window (its tap-to-open layer and the
    /// transport over it included): horizontal drags only, so the lyrics'
    /// own scroll and the page's drag-down keep the vertical ones.
    private func pagerPan(enabled: Bool) -> AlbumAxisPan {
        AlbumAxisPan(
            claims: { axis, _ in axis == .horizontal && enabled },
            changed: { _, t in pagerDrag = pagerRubberBand(t.x) },
            ended: { _, v, cancelled in pagerReleased(velocity: cancelled ? 0 : v.x, cancelled: cancelled) }
        )
    }

    /// Past the first or last page the drag resists (a rubber band).
    private func pagerRubberBand(_ dx: CGFloat) -> CGFloat {
        let last = CGFloat(NowPlayingDetailPage.allCases.count - 1)
        let raw = CGFloat(tab) - dx / max(pagerWidth, 1)
        guard raw < 0 || raw > last else { return dx }
        let edge = raw < 0 ? CGFloat(tab) : CGFloat(tab) - last
        let past = abs(dx) - abs(edge * pagerWidth)
        let eased = pow(max(past, 0), 0.7)
        return (dx < 0 ? -1 : 1) * (abs(edge * pagerWidth) + eased)
    }

    /// Release: a fling or a drag past a third of a page turns it; the
    /// pager lands on the spatial spring either way.
    private func pagerReleased(velocity: CGFloat, cancelled: Bool) {
        let last = NowPlayingDetailPage.allCases.count - 1
        var target = tab
        if !cancelled {
            let moved = -pagerDrag / max(pagerWidth, 1)
            if velocity < -400 || moved > 0.35 { target = tab + 1 }
            if velocity > 400 || moved < -0.35 { target = tab - 1 }
        }
        target = min(max(target, 0), last)
        if target != tab { UISelectionFeedbackGenerator().selectionChanged() }
        settlePager(on: target)
    }

    /// OneLineLyricRow: the 32 pt line (bodyLarge + its pads) centered in
    /// its slot; the anchor is the line's top.
    private static func oneLineAnchor(_ clear: CGFloat) -> CGFloat {
        (clear - Tokens.type.bodyLarge.lineHeight.cg - 8) / 2 + 4
    }

    /// Synced lines to show in focus (or the mock's list).
    private var soloLyricsAvailable: Bool {
        guard let t = engineTrack else { return true }
        guard case .lines(let loaded) = data.lyricsState(t) else { return false }
        return loaded.synced
    }

    @ViewBuilder
    private func compactPage(_ p: PlaybackSnapshot, page: NowPlayingDetailPage, clear: CGFloat, under: CGFloat,
                             anchor: CGFloat, fadeTop: Bool, wraps: Bool, solo: Bool) -> some View {
        switch page {
        case .lyrics:
            lyricsHandoff(height: clear) {
                lyrics(p, clear: clear, under: under, anchor: anchor, fadeTop: fadeTop, wraps: wraps, solo: solo)
            }
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
            lyricsHandoff(height: height) {
                if let t = engineTrack, case .lines(let loaded) = data.lyricsState(t) {
                    expandedLyrics(t, loaded)
                        // New lyrics (a search / edit applied) open as a new list.
                        .id(ObjectIdentifier(loaded.lyrics))
                        .frame(height: height, alignment: .top)
                } else {
                    lyrics(p, clear: height, under: 0, anchor: height * Tokens.np.LYRICS_ANCHOR_FRACTION.cg,
                           fadeTop: true)
                }
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
                        anchor: CGFloat, fadeTop: Bool, wraps: Bool = true, solo: Bool = false) -> some View {
        if let t = engineTrack {
            switch data.lyricsState(t) {
            case .loading:
                // Android LyricsDisplay: the loading indicator at the window's start.
                // It waits a beat: a song skipped past (two Next taps) loads in a moment,
                // and a spinner over the leaving song's sliding lines read as a stray row.
                DelayedSpinner(tint: scheme.primary)
                    .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
                    .frame(height: clear, alignment: .center)
                    .accessibilityLabel("Loading lyrics")
            case .none:
                // Android LyricsDisplay: the empty label at the window's start.
                windowLabel("No lyrics available", clear: clear)
            case .lines(let loaded) where loaded.synced:
                // Only this subtree reads the position; the line index is
                // derived per tick and the lines re-render when it moves.
                lyricWindow(clear: clear, under: under, fadeTop: fadeTop) {
                    PlaybackPosition { position, duration in
                        LyricLinesView(lines: loaded.lines, active: loaded.activeIndex(positionSec: position),
                                       anchor: anchor, primary: scheme.primary,
                                       onSurfaceVariant: scheme.onSurfaceVariant, wraps: wraps, solo: solo,
                                       outro: lyricsOutro(loaded, positionSec: position, durationSec: duration))
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
                               primary: scheme.primary, onSurfaceVariant: scheme.onSurfaceVariant,
                               wraps: wraps, solo: solo)
            }
        }
    }

    /// Android LyricsDisplay's outro for the playing song: nil before its
    /// window. Past the hand-over the next song's list will open exactly
    /// where its line now sits, so the song change swaps in place.
    private func lyricsOutro(_ l: NowPlayingData.LoadedLyrics, positionSec: Double,
                             durationSec: Double) -> LyricLinesView.Outro? {
        guard l.synced, durationSec > 0 else { return nil }
        let pos = Int64(positionSec * 1000), dur = Int64(durationSec * 1000)
        let last = l.lyrics.lines.last.map { Int64($0.timeMs) }
        let next = data.upNext(model.player.upNextTrack)
        let timing = next != nil
            ? UpNextTiming.forUpNext(lastLineStartMs: last, nextTimed: true, durationMs: dur)
            : UpNextTiming.lastLineOutro(lastLineStartMs: last, durationMs: dur)
        guard let timing else { return nil }
        let p = timing.progress(pos)
        guard p > 0 else { return nil }
        let handing = timing.handingOver(pos)
        // The swap check tolerates one 250 ms tick (Android UpNextHandoffTickSlackMs).
        lyricsDirection.inPlaceNext = pos >= timing.handoffAtMs - 300 ? next?.key : nil
        return .init(stretch: handing ? 0 : p, reveal: p, handingOver: handing, next: next)
    }

    /// Android LyricsTrackTransition + the lyricsLoaded swap: a new song's
    /// lyrics rise in from below while the finished song's drift up and
    /// dissolve (reversed stepping back in the queue); loading → lines rise
    /// a short way into place while the indicator fades. Each side is its
    /// own subtree, so the leaving song keeps showing its own lines.
    private func lyricsHandoff<C: View>(height: CGFloat, @ViewBuilder _ content: () -> C) -> some View {
        let key = engineTrack.map(NowPlayingData.key) ?? "mock"
        let ready: Bool = {
            guard let t = engineTrack, case .loading = data.lyricsState(t) else { return true }
            return false
        }()
        lyricsDirection.update(key: key, index: model.player.currentIndex)
        return ZStack(alignment: .topLeading) {
            ZStack(alignment: .topLeading) {
                content()
                    .id(ready)
                    .transition(.asymmetric(insertion: .offset(y: height / 12).combined(with: .opacity),
                                            removal: .opacity))
            }
            .animation(Yoin.spatial, value: ready)
            .id(key)
            .transition(LyricsTrackSlide(direction: lyricsDirection, distance: height / 4))
        }
        // After the outro's hand-over the next song's line already sits where
        // its list opens: the swap is a cut between two identical frames (an
        // animated removal showed the old list again on the way out).
        .animation(lyricsDirection.inPlace ? nil : Yoin.spatial, value: key)
        // Also when the song change arrives inside another animated update.
        .transaction(value: key) { t in
            if lyricsDirection.inPlace {
                t.animation = nil
                t.disablesAnimations = true
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
                // PLAY ⇄ PAUSE (Android PlaybackControls): one spring reshapes
                // the pill — the word's box and the side padding (16 ⇄ 24) on
                // the Standard fast spatial spring — while the word swaps
                // weight and stretch (0.97 ⇄ 1.06, 1.10 held). The pill and
                // NEXT are one connected group: the pressed key gains 15 % of
                // its width from the other. Keyed on the state itself, so
                // every source animates (button, lock screen, headphones, the
                // engine stopping on its own). One Button, one glass: the
                // glass shape follows the frame, nothing is rebuilt.
                let playing = p.isPlaying
                let pausePressed = touching.contains("pause"), nextPressed = touching.contains("Next")
                let word = Self.playWordWidth(playing: playing)
                let pad: CGFloat = playing ? 24 : 16
                let playRest = word + pad * 2
                let grow = Self.transportKeyExpandedRatio
                let playWidth = playRest + (pausePressed ? grow * playRest : 0) - (nextPressed ? grow * control : 0)
                let nextWidth = control + (nextPressed ? grow * control : 0) - (pausePressed ? grow * playRest : 0)
                Button { model.player.togglePlayPause() } label: {
                    playPauseLabel(playing: playing, pressed: pausePressed)
                        .frame(width: word)
                        .padding(.horizontal, pad)
                        .frame(width: max(0, playWidth), height: 56)
                        .contentShape(.capsule)
                }
                .buttonStyle(PressReport { touch("pause", $0) })
                .labGlass("transportPrimary", scheme: scheme, brand: brand, auto: scheme.primary, in: Capsule())
                .accessibilityLabel(playing ? "Pause" : "Play")
                .animation(Yoin.standardFastSpatial, value: playing)
                .animation(Yoin.standardDefaultSpatial, value: pausePressed)
                .animation(Yoin.standardDefaultSpatial, value: nextPressed)
                // PREVIOUS's place in this row, opening as it arrives.
                .padding(.leading, short ? control + gap : 0)
                roundButton("skip_next_filled", fill: scheme.primary, ink: scheme.onPrimary, label: "Next",
                            width: max(0, nextWidth)) {
                    model.player.next()
                }
                .animation(Yoin.standardFastSpatial, value: playing)
                .animation(Yoin.standardDefaultSpatial, value: pausePressed)
                .animation(Yoin.standardDefaultSpatial, value: nextPressed)
                Spacer(minLength: 0)
                // Android PlayModeButton: a circle; long-press splits it
                // into shuffle | repeat, each segment one axis.
                PlayModeButton(shuffle: model.player.shuffle, repeatMode: model.player.repeatMode,
                               scheme: scheme, brand: brand,
                               onCycle: { model.player.cyclePlayMode() },
                               onShuffle: { model.player.setShuffle($0) },
                               onRepeat: { model.player.setRepeat($0) },
                               onPress: { touch("Play mode", $0) })
            }
            .frame(height: 56)
            // The progress row's place: the row itself is drawn over the
            // container (below), so its glass stays behind the times and the bar.
            Color.clear.frame(height: secondRow)
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
        .overlay(alignment: .bottom) {
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
                            live: transportLive,
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
        // The glass is a layer of its own BEHIND the row, outside the
        // transport's glass container: the times and the bar are drawn on
        // top of it, not as its content (as content they were refracted and
        // tinted with it, "inside" the glass). The shape does the morph. The
        // row sits where Android's does; the capsule reaches 4 pt past its
        // leading edge (clear of Previous) and 10 pt past its end.
        .background {
            Color.clear
                .npGlass("ratingTrack", scheme: scheme, auto: scheme.surfaceVariant, held: true,
                         in: ScrubberGlassShape(grow: scrubberLifted ? 1 : 0, leading: 4, trailing: 10))
                // Keyed on the lift itself: it can turn on without
                // `interacting` changing (scrub during another control's
                // one-second hold), and must still grow / fold on the spring.
                .animation(Yoin.spatial, value: scrubberLifted)
                .allowsHitTesting(false)
        }
    }

    /// The PLAY / PAUSE word (English) or the morphing symbol (every other
    /// language, Android wordLabel), in a box the pill sizes from the word.
    @ViewBuilder
    private func playPauseLabel(playing: Bool, pressed: Bool) -> some View {
        let stretch: CGFloat = pressed ? 1.10 : (playing ? 1.06 : 0.97)
        let ink = colorScheme == .dark && !lab.denseAccent("transportPrimary", dark: true) ? Color.white : scheme.onPrimary
        Group {
            if Self.spellsPlayWord {
                // titleLarge × 0.9; PAUSE Bold with 0.5 tracking, PLAY Medium
                // with none. Laid out at its own width (it never sizes the
                // pill); the stretch grows from its leading edge.
                Text(playing ? "PAUSE" : "PLAY")
                    .font(.gsf(19.8, playing ? .bold : .medium))
                    .tracking(playing ? 0.5 : 0)
                    // The word swaps outright (Android Text): no cross-fade
                    // of PLAY over PAUSE while the pill reshapes.
                    .contentTransition(.identity)
                    .fixedSize()
                    .scaleEffect(x: stretch, y: 1, anchor: .leading)
            } else {
                YoinIcon(playing ? "pause_filled" : "play_filled", size: 28)
                    .contentTransition(.symbolEffect(.replace))
                    .scaleEffect(stretch)
            }
        }
        .foregroundStyle(ink)
        .labContrast("transportPrimary")
        // The stretch: fast while held, default otherwise (Android textStretchSpec).
        .animation(pressed ? Yoin.standardFastSpatial : Yoin.standardDefaultSpatial, value: stretch)
        .frame(maxWidth: .infinity)
    }

    /// Android: only English spells the word; every other language (one
    /// added later included) shows the symbol. Read from the language the app
    /// runs in, as Android's configuration locale is (the iOS app is English
    /// only today, so a Chinese phone still reads English UI and the word).
    private static var spellsPlayWord: Bool {
        let lang = Bundle.main.preferredLocalizations.first ?? "en"
        return Locale(identifier: lang).language.languageCode?.identifier == "en"
    }

    /// M3 ButtonGroupDefaults.ExpandedRatio (Android TransportKeyExpandedRatio).
    private static let transportKeyExpandedRatio: CGFloat = 0.15

    /// The pill's word box: the English word measured in its own style (other
    /// languages use the same box, so the shape never changes with language).
    static func playWordWidth(playing: Bool) -> CGFloat {
        let name = playing ? "GoogleSansFlex-Bold" : "GoogleSansFlex-Medium"
        let font = UIFont(name: name, size: 19.8) ?? .systemFont(ofSize: 19.8, weight: playing ? .bold : .medium)
        let text = NSAttributedString(string: playing ? "PAUSE" : "PLAY",
                                      attributes: [.font: font, .kern: playing ? 0.5 : 0])
        return ceil(text.size().width)
    }

    /// The scrubber's glass: any control touched (iOS 26 Music), or (World,
    /// R1) only the progress bar itself while it is scrubbed.
    private var scrubberLifted: Bool {
        lab.extra("liftInFlow") > 0.5 ? interacting && scrubbing : interacting
    }

    private func roundButton(_ icon: String, fill: Color, ink: Color, label: String, badge: String? = nil,
                             width: CGFloat = 56, action: @escaping () -> Void) -> some View {
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
                .frame(width: width, height: 56)
                .contentShape(.capsule)
        }
        .buttonStyle(PressReport { touch(label, $0) })
        // A capsule: a circle at 56 × 56, the pill a press widens it into.
        .labGlass("transportSecondary", scheme: scheme, brand: brand, auto: fill, in: Capsule())
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
                    routeLink(albumRoute(p), label: "Go to album") { heroTitle(p) }
                        .layoutPriority(1)
                    routeLink(artistRoute(p), label: "Go to artist") {
                        Text(p.album.artist)
                            .font(.gsf(14.4, .medium))
                            .foregroundStyle(scheme.onSurfaceVariant)
                            .animation(Self.heroStretchSpring) {
                                $0.scaleEffect(x: artistStretch(p), y: 1, anchor: .leading)
                            }
                    }
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
                routeLink(artistRoute(p), label: "Go to artist") {
                    Text(p.album.artist)
                        .font(.gsf(14.4, .medium))
                        .foregroundStyle(scheme.onSurfaceVariant)
                        .frame(height: 24, alignment: .leading)
                        .animation(Self.heroStretchSpring) {
                            $0.scaleEffect(x: artistStretch(p), y: 1, anchor: .leading)
                        }
                }
                .npFade(open, contentFade)
                routeLink(albumRoute(p), label: "Go to album") { heroTitle(p) }
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
            .animation(Self.heroStretchSpring) { $0.scaleEffect(x: titleStretch(p), y: 1, anchor: .leading) }
            .scaleEffect(open ? 1 : Self.titleFoldScale, anchor: .leading)
            .matchedGeometryEffect(id: panel ? "np.title.panel" : "np.title", in: npNamespace,
                                   properties: .position, anchor: .leading, isSource: open)
            .npFade(open, presenceFade)
            .npMorphAnchor(.title)
    }

    // MARK: Hero stretch and routes (Android titleStretchScale / artistStretchScale,
    // rememberNowPlayingRouteInteraction)

    /// PAUSE / NEXT held (Android transportPressed).
    private var transportPressed: Bool { touching.contains("pause") || touching.contains("Next") }

    /// Pressed 1.05, playing 1.03, paused 0.97 (Android, Full motion).
    private func titleStretch(_ p: PlaybackSnapshot) -> CGFloat {
        if UIAccessibility.isReduceMotionEnabled {
            return transportPressed ? 1.02 : p.isPlaying ? 1.01 : 0.99
        }
        return transportPressed ? 1.05 : p.isPlaying ? 1.03 : 0.97
    }

    private func artistStretch(_ p: PlaybackSnapshot) -> CGFloat {
        if UIAccessibility.isReduceMotionEnabled {
            return transportPressed ? 1.015 : p.isPlaying ? 1.008 : 0.992
        }
        return transportPressed ? 1.04 : p.isPlaying ? 1.02 : 0.98
    }

    /// Android heroStretchSpec: the Expressive slow spatial spring.
    private static var heroStretchSpring: Animation {
        let s = Tokens.motion.expressiveSlowSpatial
        let k = s.stiffness
        return Animation.interpolatingSpring(mass: 1, stiffness: k, damping: s.damping * 2 * k.squareRoot())
            .speed(LaunchConfig.animationSpeed)
    }

    /// The song's album, when it has a page (not a lone song's stand-in).
    private func albumRoute(_ p: PlaybackSnapshot) -> (() -> Void)? {
        let album = p.album
        guard !album.id.rawId.isEmpty, !album.id.rawId.hasPrefix("song:") else { return nil }
        return { [model] in
            Self.leaveForRoute(model)
            model.open(album)
        }
    }

    /// The song's artist, when it can be resolved to a page.
    private func artistRoute(_ p: PlaybackSnapshot) -> (() -> Void)? {
        let album = p.album
        guard !album.artist.isEmpty, album.artistId != nil || album.id.provider == "mock" else { return nil }
        return { [model] in
            Self.leaveForRoute(model)
            model.openArtist(album.artist, id: album.artistId)
        }
    }

    /// Now Playing folds away under the page it opens.
    private static func leaveForRoute(_ model: AppModel) {
        if model.npExpanded { model.setNowPlaying(expanded: false) }
        if model.npPanel { withAnimation(Yoin.spatial) { model.npPanel = false } }
    }

    /// A label that opens a page (Android route interaction: it swells under
    /// the finger, on the fast spatial spring). Plain text when there is none.
    @ViewBuilder
    private func routeLink<L: View>(_ route: (() -> Void)?, label: String,
                                    @ViewBuilder _ content: () -> L) -> some View {
        if let route {
            Button(action: route) { content() }
                .buttonStyle(RoutePressStyle())
                .accessibilityHint(label)
        } else {
            content()
        }
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
            // Gemini and World: a neutral glass floor, so on-surface ink.
            .foregroundStyle(lab.neutralTint("npPills") ? scheme.onSurface
                             : lab.style("npPills").role == .primaryContainer ? scheme.onPrimaryContainer
                             : colorScheme == .dark ? Color.white : scheme.onPrimary)
            // Same contrast shadow as PAUSE on glass; a solid pill needs none.
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
        if draft.isEmpty {
            draftAnchorMs = playheadMs
            draftTarget = currentNoteTarget
        }
        withAnimation(Yoin.stageSettle) {
            tab = NowPlayingDetailPage.note.rawValue
            stage = .expanded
            composing = true
        }
        composerFocused = true
    }

    /// The Note page's long-press Edit: the composer opens on the note's own
    /// words and moment (an unsaved new draft is parked meanwhile).
    private func startEditing(_ note: SongNote) {
        if editingNoteId == nil, !draft.isEmpty { parkedDraft = (draft, draftAnchorMs, draftTarget) }
        withAnimation(Yoin.stageSettle) {
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
                draftTarget = parked.target
            } else {
                draft = ""
                draftAnchorMs = nil
                draftTarget = nil
            }
            parkedDraft = nil
            composing = false
        }
    }

    private func saveNote() {
        if let id = editingNoteId { return saveEdit(id) }
        let target = draftTarget ?? currentNoteTarget
        guard NotesStore.shared.add(provider: target.provider, trackId: target.rawId, content: draft,
                                    positionMs: draftAnchorMs, title: target.title, artist: target.artist) != nil
        else { return }
        notesSaved += 1
        draft = ""
        // The keyboard stays up for the next line, filed at the next moment
        // of what plays now.
        draftAnchorMs = playheadMs
        draftTarget = currentNoteTarget
    }

    /// The song on screen as a note target.
    private var currentNoteTarget: NoteTarget {
        let key = currentNoteKey
        let p = model.playback
        return NoteTarget(provider: key.provider, rawId: key.rawId, title: p.track.title, artist: p.album.artist)
    }

    /// Android NoteDraft.edit: an unbound draft, or an emptied one still bound
    /// to a song that has ended, binds to what plays with its first word.
    private func draftChanged(from old: String, to new: String) {
        guard editingNoteId == nil, old.isEmpty, !new.isEmpty else { return }
        let current = currentNoteTarget
        if draftTarget == nil || draftTarget?.sameSong(current) == false {
            draftTarget = current
            draftAnchorMs = playheadMs
        }
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
                         onRealign: {
                             // "This moment": to now, and to the song playing now (words kept).
                             withAnimation(Yoin.spatialFast) { draftAnchorMs = playheadMs }
                             if editingNoteId == nil { draftTarget = currentNoteTarget }
                         },
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
/// iOS: held, the whole control reads as a glass slider (iOS 26 sliders):
/// it lifts a touch larger into interactive glass, the fill drawn inset so
/// it sits inside the glass capsule. World (`liftInFlow`): a tonal slot at
/// rest that physically lifts off the stage while held (RatingLift).
///
/// Touch-down sets the score at the finger at once (Android RatingSlider)
/// and the drag carries on from there. nil is "never rated": an empty bar
/// with no number; the first touch rates it, the number fading / scaling in
/// (effects) as the fill grows from nothing (spatial). There is no gesture
/// back to unrated.
struct RatingBar: View {
    @Binding var value: Double?
    var scheme: CoverScheme
    var held = false
    /// The song the bar rates. A touch belongs to the song it went down on:
    /// if this changes mid-touch, the rest of the touch is ignored (no value,
    /// no release; the owner cancels).
    var gestureKey: String = ""
    @Environment(\.colorScheme) private var colorScheme
    /// Finger down / up.
    var onHold: (Bool) -> Void = { _ in }

    @State private var down = false
    @State private var downKey: String?

    /// The bar's number: none while unrated, "10" at the top, else one decimal.
    static func label(_ value: Double?) -> String? {
        guard let value else { return nil }
        return value >= 10 ? "10" : String(format: "%.1f", locale: Locale(identifier: "en_US_POSIX"), value)
    }

    var body: some View {
        GeometryReader { geo in
            let h = geo.size.height
            let fraction = (value ?? 0) / 10
            let crossover = Tokens.np.RATING_LABEL_CROSSOVER
            let inset: CGFloat = held ? 4 : 0
            ZStack(alignment: .top) {
                // Track: glass tinted with the container role, so the
                // background shows through; the fill (the score) stays solid.
                if GlassLab.shared.extra("liftInFlow") > 0.5 {
                    // The glass and shadow live in the column (RatingHeartLift).
                    RatingLift(lifted: held, scheme: scheme)
                } else {
                    Capsule().fill(.clear)
                        .npGlass("ratingTrack", scheme: scheme, auto: scheme.surfaceVariant, held: held, in: Capsule())
                }
                if value == nil {
                    // Unrated: the empty slot must still read as a bar. The
                    // rest tint sank into dark backgrounds (nearly invisible),
                    // so the empty track carries a tonal fill and an outline
                    // of its own (no number, no fill height).
                    Capsule()
                        .fill(scheme.surfaceVariant.opacity(colorScheme == .dark ? 0.32 : 0.22))
                        .overlay(Capsule().strokeBorder(scheme.outline.opacity(colorScheme == .dark ? 0.55 : 0.35),
                                                        lineWidth: 1.5))
                        .transition(.opacity.animation(Yoin.effects))
                        .allowsHitTesting(false)
                }
                Capsule()
                    // Held, the fill goes full strength (World rests it lower).
                    .fill(scheme.primary.opacity(held ? 1 : GlassLab.shared.extra("ratingFill")))
                    // Unrated: no fill at all; rated, at least the 48 pt cap.
                    .frame(height: value == nil ? 0 : max(48 - inset * 2, (h - inset * 2) * fraction))
                    .frame(maxHeight: .infinity, alignment: .bottom)
                    .padding(inset)
                    .animation(Yoin.spatial, value: value == nil)
                // titleLarge (22/28) tabular; the color crosses from
                // onSurfaceVariant to onPrimary as the fill goes 80 → 100 %.
                if let label = Self.label(value) {
                    Text(label)
                        .yoinRole(Tokens.type.titleLarge)
                        .monospacedDigit()
                        .foregroundStyle(scheme.onSurfaceVariant.mix(with: scheme.onPrimary,
                                                                     by: min(1, max(0, (fraction - crossover) / (1 - crossover)))))
                        .frame(minWidth: 36)
                        .padding(.top, 8)
                        .transition(.opacity.combined(with: .scale(scale: 0.6, anchor: .top)).animation(Yoin.effects))
                }
            }
            .contentShape(.capsule)
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { v in
                        if !down {
                            down = true
                            downKey = gestureKey
                            onHold(true)
                        }
                        guard downKey == gestureKey else { return }
                        let raw = Double((1 - v.location.y / h) * 10)
                        value = (min(10.0, max(0.0, raw)) * 10).rounded() / 10
                    }
                    .onEnded { _ in
                        let own = downKey == gestureKey
                        down = false
                        downKey = nil
                        if own { onHold(false) }
                    }
            )
            .animation(Yoin.spatialFast, value: value)
        }
        .scaleEffect(held ? RatingLift.scale : 1)
        .accessibilityElement()
        .accessibilityLabel("Rating")
        .accessibilityValue(Self.label(value) ?? "Not rated")
    }
}

/// World's rating track (Glass Lab "ratingTrack" + liftInFlow): the tonal
/// slot at rest. While held it fades out and RatingHeartLift's glass, behind
/// it in the rating column, carries the bar. The caller scales the bar by
/// `scale` under the same spring, so lift and settle are one motion.
struct RatingLift: View {
    var lifted: Bool
    var scheme: CoverScheme

    /// The lift's scale (and the settle's, back to 1).
    static let scale: CGFloat = 1.06

    var body: some View {
        let lab = GlassLab.shared
        let tint = lab.style("ratingTrack").role.color(in: scheme, brand: .brand(dark: false), auto: scheme.surfaceVariant)
        Capsule()
            .fill(tint.opacity(lab.extra("liftRestOpacity")))
            .opacity(lifted ? 0 : 1)
            .animation(Yoin.effects, value: lifted)
    }
}

/// World: the rating bar and the heart lift off the stage together (51,
/// 2026-11-04). Both glass shapes sit in ONE GlassEffectContainer whose
/// spacing reaches across the 8 pt gap, so while lifted they bulge toward
/// each other and blend at the near edges (Liquid Glass's two glasses
/// interacting), then part and settle back on release. The glass is
/// inserted / removed (materialize), never switched to `.identity`, which
/// snapped off in one frame; the shadows, from the one light straight above
/// (R4), exist only while lifted. Scale and glass ride the caller's spring.
struct RatingHeartLift: View {
    var lifted: Bool
    var scheme: CoverScheme
    var bar: CGSize
    var gap: CGFloat
    var heart: CGFloat
    /// The heart's surface colour (liked: tertiary), kept inside its glass.
    var heartTint: Color

    /// Wider than the gap, so the two glasses merge while lifted.
    static let spacing: CGFloat = 24

    var body: some View {
        let lab = GlassLab.shared
        let st = lab.style("ratingTrack")
        let tint = st.role.color(in: scheme, brand: .brand(dark: false), auto: scheme.surfaceVariant)
        let s = lifted ? RatingLift.scale : 1
        ZStack(alignment: .top) {
            // Shadows under the glass: blurred shapes straight down.
            VStack(spacing: gap) {
                Capsule().frame(width: bar.width, height: bar.height).scaleEffect(s)
                Circle().frame(width: heart, height: heart).scaleEffect(s)
            }
            .foregroundStyle(.black.opacity(st.shadowOpacity))
            .blur(radius: st.shadowRadius / 2)
            .offset(y: st.shadowY)
            .opacity(lifted ? 1 : 0)
            // Lifting, the shadow waits for the glass to materialize over it
            // (else it greys the bar for a frame or two); settling, it goes at once.
            .animation(lifted ? Yoin.effects.delay(0.12) : Yoin.effects, value: lifted)
            GlassEffectContainer(spacing: Self.spacing) {
                VStack(spacing: gap) {
                    ZStack {
                        if lifted {
                            Capsule().fill(.clear)
                                .glassEffect(.regular.tint(tint.opacity(st.tintOpacity)).interactive(), in: .capsule)
                                .glassEffectTransition(.materialize)
                        }
                    }
                    .frame(width: bar.width, height: bar.height)
                    .scaleEffect(s)
                    ZStack {
                        if lifted {
                            Circle().fill(.clear)
                                .glassEffect(.regular.tint(heartTint.opacity(0.8)).interactive(), in: .circle)
                                .glassEffectTransition(.materialize)
                        }
                    }
                    .frame(width: heart, height: heart)
                    .scaleEffect(s)
                }
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

/// The NP heart's surface: World's tonal circle at rest, faded out while the
/// rating lift carries the heart in glass (RatingHeartLift); elsewhere the
/// plain Glass Lab surface.
private struct HeartSurface: ViewModifier {
    var world: Bool
    var lifted: Bool
    var rest: Color
    var surface: String
    var scheme: CoverScheme
    var brand: CoverScheme
    var auto: Color

    func body(content: Content) -> some View {
        if world {
            content.background {
                // Lifted, half the tonal circle stays over the glass (same
                // tint), so the glass reads through it yet the heart keeps its
                // colour and brightness: no pale frame while the glass
                // dematerializes, no dark-mode jump on release.
                // Back to whole at once on release: the glass under it goes
                // in one frame, and a circle still fading in let the dark
                // page through for ~0.1 s (dark mode read as a dim blink).
                Circle().fill(rest)
                    .opacity(lifted ? 0.5 : 1)
                    .animation(lifted ? Yoin.effects : nil, value: lifted)
            }
        } else {
            content.labGlass(surface, scheme: scheme, brand: brand, auto: auto, in: Circle())
        }
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

/// Now Playing's route labels: 1.08 under the finger, from the leading edge.
struct RoutePressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 1.08 : 1, anchor: .leading)
            .animation(Yoin.spatialFast, value: configuration.isPressed)
            .contentShape(.rect)
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


/// Android PlayModeButton (owner 2026-10-17 / 10-21): a round button whose
/// symbol shows the mode; a tap steps the cycle (repeat all → shuffle →
/// shuffle + one → one). A long-press splits it into two segments —
/// shuffle | repeat — each tap switching only its own axis (repeat steps
/// all → one → off, the only way to repeat off); left alone for 3 s, or
/// long-pressed again, it merges back. Width and the symbols' travel ride
/// one spatial spring, colours the effects spring. iOS: one glass capsule
/// that widens (two adjacent glasses in the transport's container would
/// bridge into one blob); the lit segment is a solid primary inset in it.
struct PlayModeButton: View {
    var shuffle: Bool
    var repeatMode: RepeatMode
    var scheme: CoverScheme
    var brand: CoverScheme
    var onCycle: () -> Void
    var onShuffle: (Bool) -> Void
    var onRepeat: (RepeatMode) -> Void
    var onPress: (Bool) -> Void

    @State private var split = false
    /// One more per segment tap: the merge waits for a quiet spell.
    @State private var splitTouches = 0

    static let size: CGFloat = 56
    /// Android playModeNaturalWidth: control × 1.6 + the 2 dp gap.
    static let splitWidth: CGFloat = size * 1.6 + 2
    static let holdSeconds: Double = 3

    private var tinted: Bool {
        GlassLab.shared.style("transportSecondary").tintOn && !GlassLab.shared.neutralTint("transportSecondary")
    }
    private var ink: Color { tinted ? scheme.onTertiaryContainer : scheme.onSurface }

    var body: some View {
        let width = split ? Self.splitWidth : Self.size
        let segment = (Self.splitWidth - 2) / 2
        ZStack {
            if split {
                HStack(spacing: 2) {
                    half(lit: shuffle, leading: true, width: segment) {
                        glyph("shuffle", one: false, off: false)
                    }
                    .onTapGesture { tick(); onShuffle(!shuffle) }
                    .accessibilityElement()
                    .accessibilityLabel("Shuffle")
                    .accessibilityValue(shuffle ? "On" : "Off")
                    .accessibilityAddTraits(.isButton)
                    half(lit: repeatMode != .all, leading: false, width: segment) {
                        glyph("repeat", one: repeatMode == .one, off: repeatMode == .off)
                    }
                    .onTapGesture { tick(); onRepeat(repeatMode.next) }
                    .accessibilityElement()
                    .accessibilityLabel("Repeat")
                    .accessibilityValue(repeatMode == .one ? "One" : repeatMode == .off ? "Off" : "All")
                    .accessibilityAddTraits(.isButton)
                }
                .transition(.opacity.combined(with: .scale(scale: 0.8)))
            } else {
                glyph(shuffle ? "shuffle" : "repeat", one: repeatMode == .one, off: repeatMode == .off)
                    .foregroundStyle(ink)
                    .frame(width: Self.size, height: Self.size)
                    .contentShape(.capsule)
                    .onTapGesture { onCycle() }
                    .accessibilityElement()
                    .accessibilityLabel("Play mode")
                    .accessibilityValue(modeDescription)
                    .accessibilityAddTraits(.isButton)
                    .accessibilityAction(named: "Split into shuffle and repeat") { setSplit(true) }
                    .transition(.opacity.combined(with: .scale(scale: 0.8)))
            }
        }
        .frame(width: width, height: Self.size)
        .onLongPressGesture(minimumDuration: 0.4, perform: {
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            setSplit(!split)
        }, onPressingChanged: { onPress($0) })
        .labGlass("transportSecondary", scheme: scheme, brand: brand, auto: scheme.tertiaryContainer, in: Capsule())
        .animation(Yoin.spatial, value: split)
        .animation(Yoin.effects, value: shuffle)
        .animation(Yoin.effects, value: repeatMode)
        .task(id: split ? splitTouches : -1) {
            guard split else { return }
            try? await Task.sleep(for: .seconds(Self.holdSeconds / max(LaunchConfig.animationSpeed, 0.05)))
            if !Task.isCancelled { setSplit(false) }
        }
    }

    private var modeDescription: String {
        let r = repeatMode == .one ? "repeat one" : repeatMode == .off ? "repeat off" : "repeat all"
        return shuffle ? "Shuffle, " + r : r.prefix(1).uppercased() + r.dropFirst()
    }

    private func setSplit(_ on: Bool) {
        splitTouches = 0
        split = on
    }

    private func tick() {
        UISelectionFeedbackGenerator().selectionChanged()
        splitTouches += 1
    }

    /// One segment: outer corners round, inner ones nearly square (Android
    /// PlayModeInnerCornerRatio 0.18); lit, a solid primary inset.
    private func half<G: View>(lit: Bool, leading: Bool, width: CGFloat, @ViewBuilder _ glyph: () -> G) -> some View {
        let outer = Self.size / 2, inner = Self.size * 0.18
        let shape = UnevenRoundedRectangle(topLeadingRadius: leading ? outer : inner,
                                           bottomLeadingRadius: leading ? outer : inner,
                                           bottomTrailingRadius: leading ? inner : outer,
                                           topTrailingRadius: leading ? inner : outer, style: .continuous)
        return glyph()
            .foregroundStyle(lit ? scheme.onPrimary : ink)
            .frame(width: width, height: Self.size)
            .background { shape.fill(scheme.primary).padding(3).opacity(lit ? 1 : 0) }
            .contentShape(shape)
    }

    /// The mode's symbol: repeat one wears a small 1, repeat off is struck
    /// through (drawn in on the effects spring).
    private func glyph(_ icon: String, one: Bool, off: Bool) -> some View {
        YoinIcon(icon, size: 28)
            .overlay(alignment: .bottomTrailing) {
                if one && icon == "repeat" {
                    Text("1").font(.gsf(10, .bold)).offset(x: 4, y: 2)
                }
            }
            .overlay {
                Capsule()
                    .frame(width: 2, height: 30)
                    .rotationEffect(.degrees(-45))
                    .scaleEffect(y: off && icon == "repeat" ? 1 : 0.001)
                    .opacity(off && icon == "repeat" ? 1 : 0)
            }
            .labContrast("transportSecondary")
    }
}

/// A loading indicator that shows only once loading has taken a beat (Android's
/// lyrics wait the same before their indicator).
private struct DelayedSpinner: View {
    var tint: Color
    @State private var shown = false
    /// Under this, a load reads as instant.
    static let delay: Duration = .milliseconds(400)

    var body: some View {
        ProgressView()
            .tint(tint)
            .opacity(shown ? 1 : 0)
            .animation(Yoin.effects, value: shown)
            .task {
                try? await Task.sleep(for: Self.delay)
                shown = true
            }
    }
}
