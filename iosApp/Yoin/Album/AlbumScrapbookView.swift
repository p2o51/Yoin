import SwiftUI
import YoinShared

/// Album page 2, the scrapbook (Android detail/AlbumScrapbookPage.kt,
/// AlbumScrapbook.kt), phone width = one lane, every piece a full-width row
/// 28 pt apart on dot paper. Order: opening cluster (cover + emblem, title,
/// Last Play, review, album note) → per track in order: a cluster for tracks
/// with notes or the best score, contact sheets of two for score-only runs →
/// receipt. Tilts come from an FNV-1a hash of "albumId/key" (shared Kotlin; graphics only;
/// layout stays square). A server album shows the listener's own records
/// (AlbumMemoryStore, NotesStore, LocalRatings — filled here or by cloud sync
/// from Android); the mock library keeps its hand-set stand-ins.
///
/// While the pager drags, each piece trails by its depth × 12 % of the page
/// and leans 3° further, settling to its own angle (Android scrapLayer). The
/// page scrolls under the album header with the system's soft scroll edge.
struct AlbumScrapbookView: View {
    var album: Album
    /// The album's plays in Yoin (Data/History); nil = the mock's hand-set ones.
    var plays: LibraryStore.AlbumHistory? = nil
    var settled: Bool

    /// Last Play: the mock's hand-set one, else the newest play here (nil = never).
    private var lastPlay: (day: String, time: String)? {
        plays == nil ? (day: "Yesterday", time: "16:04") : plays?.lastPlay
    }
    /// The album header's height: the page scrolls under it.
    var topInset: CGFloat = 0
    /// The pager as the pieces read it: 0 settled here, 1 a page away.
    var pageOffset: CGFloat = 0
    var pageWidth: CGFloat = 402
    /// The hero cover is flying here: this page's cover waits hidden until it lands.
    var coverInFlight = false
    /// Opens the album's rate + review sheet (the review clipping, the blank pen).
    var onEditReview: (() -> Void)? = nil
    /// The cover's resting rect in `space` and its tilt (the flight's target).
    var onCoverPlaced: (CGRect, Double) -> Void = { _, _ in }

    /// The coordinate space the cover reports in; the host names it.
    static let space = "albumScrapbook"

    /// A top safe-area bar that only holds room for the album header (which
    /// floats above, outside the scroll view). Not `Color.clear`: an empty bar
    /// draws no scroll edge effect.
    static func edgeBar(height: CGFloat) -> some View {
        Rectangle().fill(.white.opacity(0.01)).frame(height: height).allowsHitTesting(false)
    }
    @Environment(\.colorScheme) private var colorScheme
    @Environment(AppModel.self) private var model
    /// The ticket whose track sheet is open (Android ScrapSheetOpen).
    @State private var trackSheet: ScrapSheetOpen?
    /// In the window (a page being set up off screen never takes a focus request).
    @State private var onScreen = false
    /// The Memory title being renamed in place (Android OpeningTitle).
    @State private var renaming = false
    @State private var titleDraft = ""
    @FocusState private var titleFocused: Bool
    /// The stamp (Android CoverWithEmblem, D3 §8): once per album page, the
    /// emblem's award plays when the pager comes to rest here; until then it
    /// waits uncut. No haptics on the album page (D3 §11.12).
    @State private var stamped = false
    /// View in Scrapbook's song: scrolled to, then [pulsed] while it swells.
    @State private var pointedOut: MediaId?
    @State private var pulsed: MediaId?
    @State private var award: ScoreEmblemAward?

    private var s: CoverScheme { CoverScheme(album: album, dark: colorScheme == .dark) }

    /// The Memory title's serif (Android FontFamily.Serif), 22 SemiBold.
    private static let titleFont: UIFont = {
        let base = UIFont.systemFont(ofSize: 22, weight: .semibold)
        return base.fontDescriptor.withDesign(.serif).map { UIFont(descriptor: $0, size: 22) } ?? base
    }()

    // MARK: Records

    private struct Note { var time: String; var text: String; var positionMs: Int64? = nil }

    /// The mock library: hand-set records (screenshots); a server album: the listener's own.
    private var isMock: Bool { album.id.provider == "mock" }
    private var provider: String { album.id.provider }
    private var memories: AlbumMemoryStore { .shared }

    /// Android scrapTitle: the listener's title, else the AI's; nil = none yet.
    private var memoryTitle: String? {
        isMock ? String(localized: "Paper moons and late buses") : memories.memoryTitle(provider: provider, albumId: album.id.rawId)
    }
    private var review: String? {
        if isMock {
            return "Warm even on the cold tracks. The middle stretch from 余韻 to Kites in July is the reason I keep coming back; it sounds like the walk home after the last train."
        }
        let text = memories.album(provider: provider, albumId: album.id.rawId)?.review?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return text?.isEmpty == false ? text : nil
    }
    /// When the review was written (Android reviewAt: "Today", "Oct 5"…).
    private var reviewAt: String? {
        if isMock { return "Yesterday" }
        guard let at = memories.album(provider: provider, albumId: album.id.rawId)?.reviewUpdatedAt else { return nil }
        return HomeHistory.relativeDay(at, now: Int64(Date().timeIntervalSince1970 * 1000))
    }
    /// The album's one note (Android album_notes, the latest row): read-only, never synced.
    private var albumNote: String? {
        isMock ? String(localized: "Bought on a rainy Tuesday in Shimokitazawa.")
            : AlbumNoteStore.shared.text(provider: provider, albumId: album.id.rawId)
    }

    private func notes(_ t: Track) -> [Note]? {
        if isMock {
            switch t.number {
            case 3: return [Note(time: "1:14", text: String(localized: "The echo stays after the song is gone")),
                            Note(time: "2:40", text: String(localized: "That piano drop, every single time"))]
            case 9: return [Note(time: "0:52", text: String(localized: "Sounds like a harbor at 6 a.m."))]
            default: return nil
            }
        }
        let list = NotesStore.shared.notes(provider: provider, trackId: t.id.rawId)
        return list.isEmpty ? nil : list.map {
            Note(time: $0.positionMs.map(NotesStore.formatPosition) ?? "", text: $0.content, positionMs: $0.positionMs)
        }
    }

    /// The song's questions, About paragraph and facts on the paper (Android buildAlbumScrapbook).
    private func about(_ t: Track) -> ScrapAboutPieces {
        #if DEBUG
        // `-aboutDemo` files About rows under the mock song: page 2 shows them (screenshots).
        let demo = SongAboutDemo.mode != nil
        #else
        let demo = false
        #endif
        guard !isMock || demo else { return .none }
        return ScrapAboutPieces.of(SongAboutStore.shared.entries(
            SongAboutStore.key(title: t.title, artist: t.artist ?? album.artist, album: album.title)))
    }

    /// A ticket's tap (Android onSongClick): the album from this song; a note's from its moment. The
    /// song already current isn't restarted: it seeks there and plays on.
    private func play(_ t: Track, at ms: Int64? = nil) {
        guard !isMock, let index = album.tracks.firstIndex(where: { $0.id == t.id }) else { return }
        let current = model.player.currentTrack?.id == t.id.rawId
        if !current {
            model.play(album.tracks.map { PlaylistEntry(album: album, track: $0) }, startAt: index)
        }
        if let ms { model.player.seek(to: Double(ms) / 1000) }
        if current { model.player.resume() }
    }
    private func starred(_ t: Track) -> Bool { isMock ? [2, 3].contains(t.number) : t.starred }
    /// The Bun's inputs from the local stores (AlbumBun: what page 1 reads too).
    private var bun: AlbumBun { AlbumBun.of(album, memories: memories) }
    /// The track's score: this device's (or a synced) rating; Android never shows the server's here.
    private func score(_ t: Track) -> Double? {
        guard let i = album.tracks.firstIndex(where: { $0.id == t.id }) else { return nil }
        return bun.trackScores[i]
    }
    private func playCount(_ t: Track) -> Int { isMock ? t.number * 3 + 5 : (t.playCount ?? 0) }
    /// The album's own score: the synced album rating (Android album_ratings).
    private var albumScore: Double? { bun.albumRating }
    private var averageScore: Double? { bun.average }

    var body: some View {
        ScrollViewReader { proxy in
        ScrollView {
            VStack(alignment: .leading, spacing: Tokens.detail.SCRAPBOOK_PIECE_GAP.cg) {
                opening
                ForEach(Array(blocks.enumerated()), id: \.offset) { i, b in
                    block(b, mirrored: (i + mirrorSeed).isMultiple(of: 2))
                }
                // Android: the receipt only once the album has played here.
                if plays.map({ $0.playCount > 0 }) ?? true {
                    receipt
                        .frame(maxWidth: .infinity)
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 4)
            .padding(.bottom, 112)
            .background { DotPaper(color: s.outlineVariant.opacity(colorScheme == .dark ? 0.35 : 0.45)) }
        }
        .scrollIndicators(.hidden)
        // The header's room as a bar: pieces fade under the status bar with
        // the system's soft edge; the host lays a ProgressiveEdgeBlur over
        // the header band, so nothing meets it in a hard cut.
        .scrollEdgeEffectStyle(.soft, for: .top)
        .safeAreaBar(edge: .top) { Self.edgeBar(height: topInset) }
        .defaultScrollAnchor(LaunchConfig.screen == "album-scrapbook-bottom" ? .bottom : .top)
        .onChange(of: awardKey, initial: true) { _, _ in
            // Built from the emblem's own inputs; rebuilt (at rest once stamped) when they change.
            let a = ScoreEmblemAward(score: emblemScore, kind: emblemKind, rated: emblemRated,
                                     size: Tokens.detail.SCRAPBOOK_BADGE.cg, haptics: false)
            if !stamped { a.setPending(true) }
            award = a
            stampIfSettled()
        }
        .onChange(of: settled) { _, _ in
            stampIfSettled()
            openFocus()
        }
        .onAppear {
            onScreen = true
            openFocus()
        }
        .onDisappear { onScreen = false }
        .onChange(of: ScrapbookFocusRequest.shared.pending) { _, _ in openFocus() }
        .onChange(of: album.tracks.map(\.id)) { _, _ in openFocus() }
        .onChange(of: pointedOut) { _, id in
            // View in Scrapbook: the song's piece comes into view and swells a touch, then settles.
            guard let id else { return }
            withAnimation(Yoin.spatial) { proxy.scrollTo(id, anchor: UnitPoint(x: 0.5, y: 0.3)) }
            Task {
                try? await Task.sleep(for: .milliseconds(650))
                withAnimation(Yoin.spatial) { pulsed = id }
                try? await Task.sleep(for: .milliseconds(320))
                withAnimation(Yoin.spatial) { pulsed = nil }
                pointedOut = nil
            }
        }
        .sheet(item: $trackSheet) { open in
            ScrapTrackSheet(track: open.track, album: album, scheme: s, store: model.store,
                            scoreEditable: !isMock, initialEditing: open.editing, initialTab: open.tab,
                            onPlayFrom: { ms in play(open.track, at: ms) })
                .reportsSheetRise()
        }
        }
    }

    /// Now Playing's "Manage in Scrapbook" (ScrapbookFocusRequest): once the
    /// pager has come to rest here, the song's sheet opens at that row's editor.
    private func openFocus() {
        guard settled, ScrapbookFocusRequest.shared.pending != nil else { return }
        // Next turn: a sheet state written during the page's first update is dropped.
        Task {
            guard onScreen, let focus = ScrapbookFocusRequest.shared.take(for: album.tracks.map(\.id)),
                  let t = album.tracks.first(where: { $0.id == focus.songId }) else { return }
            guard focus.openSheet else { return pointedOut = t.id }
            trackSheet = ScrapSheetOpen(track: t, editing: focus.edit)
        }
    }

    // MARK: Stamp

    private var emblemKind: ScoreEmblem.Kind { bun.kind }
    private var emblemScore: Double { bun.score }
    private var emblemRated: [Bool] { bun.rated }
    private var awardKey: String { "\(emblemScore)|\(emblemKind)|\(emblemRated)" }

    private func stampIfSettled() {
        guard settled, !stamped, let award else { return }
        award.play()
        stamped = true
    }

    // MARK: Opening cluster

    private var opening: some View {
        let kind = emblemKind
        let cover = Tokens.detail.SCRAPBOOK_COVER.cg
        let badge = Tokens.detail.SCRAPBOOK_BADGE.cg
        let overhangX = Tokens.detail.SCRAPBOOK_BADGE_OVERHANG_X.cg
        // A Spotify cover: the stamp beside it, level with its foot (CoverMarks).
        let onCover = CoverMarks.onCover(provider: provider)
        let stampX = onCover ? overhangX * badge : badge + CoverMarks.gap
        return VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .top, spacing: 0) {
                ZStack(alignment: .bottomTrailing) {
                    // Where the hero's cover lands: the slot's resting rect,
                    // measured outside the piece's own drift and tilt (Android
                    // restRect), so the target holds still while the pager moves.
                    Color.clear
                        .frame(width: cover, height: cover)
                        .onGeometryChange(for: CGRect.self) { $0.frame(in: .named(Self.space)) } action: {
                            onCoverPlaced($0, tilt("cover", Tilt.cover))
                        }
                        .overlay {
                            CoverArt(palette: album.palette, cornerRadius: 0, seed: album.seed)
                                .frame(width: cover, height: cover)
                                .clipShape(.rect(cornerRadius: Tokens.shape.HERO.cg, style: .continuous))
                                // Hidden until the hero's cover has landed: one cover on screen, never two.
                                .opacity(coverInFlight ? 0 : 1)
                                .modifier(layer(tilt("cover", Tilt.cover), depth: 0.25))
                        }
                        .frame(width: cover, height: cover + Tokens.detail.SCRAPBOOK_BADGE_OVERHANG_Y.cg * badge, alignment: .top)
                    ScoreEmblem(score: emblemScore, kind: kind, rated: emblemRated,
                                palette: album.palette, size: badge,
                                // nothing rated: the empty mould, no ceremony (GrooveScript tier 0)
                                award: kind == .unrated ? nil : award, onCover: onCover)
                        .offset(x: stampX)
                        .modifier(layer(0, depth: 0.6))
                }
                Spacer().frame(width: stampX + 8)
                VStack(alignment: .leading, spacing: 0) {
                    // OpeningTitle: the serif (a title someone or the AI wrote),
                    // 22 SemiBold, line 1.25 em (27.5).
                    if let memoryTitle {
                        openingTitle(memoryTitle)
                    }
                    // AlbumScrapLastPlay: AlbumSectionLabel (labelLarge
                    // underlined), 4, then titleMedium tabular.
                    if let lastPlay {
                        Text("Last Play")
                            .underline()
                            .yoinRole(Tokens.type.labelLarge)
                            .foregroundStyle(s.onSurface)
                            .padding(.top, 16).padding(.bottom, 4)
                        Text("\(lastPlay.day) \(lastPlay.time)")
                            .monospacedDigit()
                            .lineLimit(1)
                            .yoinRole(Tokens.type.titleMedium)
                            .foregroundStyle(s.onSurface)
                    }
                }
                .padding(.top, 4)
            }
            // Title present → the review goes below, full width.
            if let review {
            Button { onEditReview?() } label: {
            VStack(alignment: .leading, spacing: 6) {
                // ReviewClipping: GSF 15, line 1.55 em; its date in labelMedium.
                Text(review)
                    .font(.gsf(15)).lineSpacing(YoinType.lineSpacing(size: 15, lineHeight: 15 * 1.55))
                    .foregroundStyle(s.onPrimaryContainer)
                if let reviewAt {
                    Text(reviewAt)
                        .yoinRole(Tokens.type.labelMedium)
                        .foregroundStyle(s.onPrimaryContainer.opacity(0.6))
                }
            }
            .padding(.horizontal, 16).padding(.vertical, 14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(s.primaryContainer, in: .rect(cornerRadius: Tokens.shape.CARD.cg, style: .continuous))
            .contentShape(.rect(cornerRadius: Tokens.shape.CARD.cg, style: .continuous))
            }
            .buttonStyle(.plain)
            .disabled(isMock || onEditReview == nil)
            .accessibilityLabel("Edit comment")
            .modifier(layer(tilt("review", Tilt.paper), depth: 0.35))
            } else if !isMock, let onEditReview {
                // BlankReview: a 48 dashed circle with the pen, at the row's start.
                Button(action: onEditReview) {
                    YoinIcon("edit", size: 20)
                        .foregroundStyle(s.onSurface)
                        .frame(width: 48, height: 48)
                        .overlay {
                            Circle().strokeBorder(s.outline, style: StrokeStyle(lineWidth: 1.5, dash: [5, 5]))
                        }
                        .contentShape(.circle)
                }
                .buttonStyle(PressScale())
                .accessibilityLabel("Write a comment")
                .modifier(layer(tilt("review", Tilt.paper), depth: 0.35))
                .padding(.top, 8)
            }
            // Album note sticky: own row on the end side, tucks 6 over the review.
            if let albumNote {
            Text(albumNote)
                // userTextStyle at 14.5: line 1.5 em.
                .font(.gsf(14.5)).lineSpacing(YoinType.lineSpacing(size: 14.5, lineHeight: 14.5 * 1.5))
                .foregroundStyle(s.onSecondaryContainer)
                .padding(.leading, 18).padding(.trailing, 18).padding(.top, 14).padding(.bottom, 18)
                .frame(width: min(0.72 * 368, Tokens.detail.SCRAPBOOK_STICKY_MAX_WIDTH.cg), alignment: .leading)
                .background { StickyNote(fill: s.secondaryContainer,
                                         flap: s.secondaryContainer.mix(with: s.secondary, by: 0.32, in: .perceptual)) }
                .modifier(layer(-sign(tilt("review", Tilt.paper)) * abs(tilt("albumnote", Tilt.paper)), depth: 0.45))
                .frame(maxWidth: .infinity, alignment: .trailing)
                .padding(.top, -Tokens.detail.SCRAPBOOK_STICKY_TUCK.cg)
                .accessibilityLabel("Album note: \(albumNote)")
            }
        }
    }

    // MARK: Memory title (Android OpeningTitle)

    /// The listener renamed it (the sparkle then restores the AI's).
    private var userTitle: String? { memories.userTitle(provider: provider, albumId: album.id.rawId) }
    private var aiTitle: String? {
        let t = memories.memory(provider: provider, entityId: album.id.rawId)?.title?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return t?.isEmpty == false ? t : nil
    }
    /// Matches Android AlbumMemoryTitleStore's cap.
    private static let titleMaxLength = 80

    /// The serif title beside the cover (a title someone or the AI wrote;
    /// 22 SemiBold, line 1.25 em). Tap to rename in place: the line becomes
    /// the field, Return or leaving it saves, blank goes back to Yoin's
    /// title; once renamed, the sparkle restores the AI title. No outlined
    /// field, no labels.
    @ViewBuilder
    private func openingTitle(_ title: String) -> some View {
        let lineGap = max(0, 22 * 1.25 - Self.titleFont.lineHeight)
        if renaming {
            HStack(alignment: .top, spacing: 4) {
                TextField("", text: $titleDraft, axis: .vertical)
                    .font(Font(Self.titleFont))
                    .lineSpacing(lineGap)
                    .foregroundStyle(s.onSurface)
                    .tint(s.primary)
                    .focused($titleFocused)
                    .submitLabel(.done)
                    .textInputAutocapitalization(.sentences)
                    .onChange(of: titleDraft) { _, text in
                        // One line of meaning: Return saves; pasted breaks become spaces.
                        if text.hasSuffix("\n") && !text.dropLast().contains("\n") {
                            titleDraft = String(text.dropLast())
                            titleFocused = false
                        } else if text.contains("\n") || text.count > Self.titleMaxLength {
                            titleDraft = String(text.replacingOccurrences(of: "\n", with: " ").prefix(Self.titleMaxLength))
                        }
                    }
                if userTitle != nil, aiTitle != nil {
                    Button {
                        renaming = false
                        setTitle(nil)
                    } label: {
                        YoinIcon("sparkle", size: 20)
                            .foregroundStyle(s.onSurfaceVariant)
                            .frame(width: 40, height: 40)
                            .contentShape(.circle)
                    }
                    .buttonStyle(PressScale())
                    .accessibilityLabel("Restore title")
                }
            }
            .onAppear { titleFocused = true }
            .onChange(of: titleFocused) { was, now in
                if was, !now { commitTitle(was: title) }
            }
        } else {
            Text(title)
                .font(Font(Self.titleFont))
                .lineSpacing(lineGap)
                .foregroundStyle(s.onSurface)
                .contentShape(.rect)
                .onTapGesture {
                    guard !isMock else { return }
                    titleDraft = userTitle ?? title
                    renaming = true
                }
                .accessibilityAddTraits(isMock ? [] : .isButton)
                .accessibilityHint(isMock ? "" : String(localized: "Rename"))
        }
    }

    private func commitTitle(was title: String) {
        guard renaming else { return }
        renaming = false
        let text = titleDraft.replacingOccurrences(of: "\n", with: " ").trimmingCharacters(in: .whitespacesAndNewlines)
        guard text != title else { return }
        setTitle(text.isEmpty ? nil : String(text.prefix(Self.titleMaxLength)))
    }

    /// nil = Yoin's title again (the user title's row goes; sync carries the delete).
    private func setTitle(_ text: String?) {
        withAnimation(Yoin.effects) {
            if let text {
                memories.putTitle(.init(provider: provider, albumId: album.id.rawId, title: text,
                                        updatedAt: Int64(Date().timeIntervalSince1970 * 1000)))
            } else {
                memories.removeTitle(provider: provider, albumId: album.id.rawId)
            }
        }
        CloudSync.shared.localChanged()
    }

    // MARK: Track blocks

    private enum Block { case cluster(Track), sheet([Track]) }

    /// Tracks with notes or the best score get a cluster; runs of score-only
    /// tracks become contact sheets of two; untouched tracks are skipped.
    private var blocks: [Block] {
        var out: [Block] = []
        var run: [Track] = []
        func flush() {
            var i = 0
            while i < run.count { out.append(.sheet(Array(run[i..<min(i + 2, run.count)]))); i += 2 }
            run.removeAll()
        }
        for t in album.tracks {
            let a = about(t)
            if notes(t) != nil || t.id == bestTrack?.id || a.needsCluster {
                flush(); out.append(.cluster(t))
            } else if score(t) != nil || a.touches {
                run.append(t)
            }
        }
        flush()
        return out
    }

    private var bestTrack: Track? {
        album.tracks.filter { (score($0) ?? 0) >= 9 }.max { (score($0) ?? 0) < (score($1) ?? 0) }
    }

    private var mirrorSeed: Int { ScrapbookTilt.shared.startsMirrored(seed: album.id.rawId) ? 1 : 0 }

    @ViewBuilder
    private func block(_ b: Block, mirrored: Bool) -> some View {
        switch b {
        case .cluster(let t):
            VStack(alignment: mirrored ? .trailing : .leading, spacing: -Tokens.detail.SCRAPBOOK_PIECE_OVERLAP.cg) {
                ticket(t, width: min(280, max(200, 0.66 * 368)), mini: false)
                    .zIndex(0)
                if let ns = notes(t) {
                    notesClipping(t, ns)
                        .frame(maxWidth: .infinity, alignment: mirrored ? .leading : .trailing)
                        .zIndex(1)
                }
                let a = about(t)
                if let ask = a.ask {
                    askCard(t, ask, more: a.moreAsks)
                        .frame(maxWidth: .infinity, alignment: mirrored ? .trailing : .leading)
                        .padding(.top, Tokens.detail.SCRAPBOOK_PIECE_OVERLAP.cg + 6)
                        .zIndex(2)
                } else if let line = a.aboutLine {
                    aboutCard(t, line)
                        .frame(maxWidth: .infinity, alignment: mirrored ? .trailing : .leading)
                        .padding(.top, Tokens.detail.SCRAPBOOK_PIECE_OVERLAP.cg + 6)
                        .zIndex(2)
                }
                ForEach(Array(a.tags.enumerated()), id: \.offset) { i, tag in
                    factTag(t, tag, index: i, extra: i == a.tags.count - 1 ? a.hiddenTags : 0)
                        .frame(maxWidth: .infinity, alignment: mirrored ? .leading : .trailing)
                        .padding(.horizontal, 12)
                        .padding(.top, Tokens.detail.SCRAPBOOK_PIECE_OVERLAP.cg + (i == 0 ? 8 : 6))
                        .zIndex(3)
                }
            }
            .frame(maxWidth: .infinity, alignment: mirrored ? .trailing : .leading)
            .padding(.top, 6)
        case .sheet(let ts):
            if ts.count == 2 {
                HStack(alignment: .top, spacing: 14) {
                    ticket(ts[0], width: nil, mini: true)
                    ticket(ts[1], width: nil, mini: true).padding(.top, 12)
                }
                .padding(.top, 6)
            } else {
                ticket(ts[0], width: min(220, max(168, 0.52 * 368)), mini: true)
                    .frame(maxWidth: .infinity, alignment: mirrored ? .trailing : .leading)
                    .padding(.top, 6)
            }
        }
    }

    private func ticket(_ t: Track, width: CGFloat?, mini: Bool) -> some View {
        let best = t.id == bestTrack?.id
        let fill = best ? s.primary : s.surfaceContainerHigh
        let ink = best ? s.onPrimary : s.onSurface
        let scoreText = score(t).map { String(format: "%.1f", $0) } ?? ""
        return HStack(spacing: 0) {
            Text(String(format: "%02d", t.number))
                .font(.gsf(mini ? 16 : 18, .medium)).monospacedDigit()
                .foregroundStyle(ink.opacity(0.72))
                .frame(width: Tokens.detail.SCRAPBOOK_TICKET_STUB.cg)
            VStack(alignment: .leading, spacing: 3) {
                // titleMedium SemiBold at 15 / 16, line 1.25 em.
                Text(t.title).font(.gsf(mini ? 15 : 16, .semibold)).foregroundStyle(ink).lineLimit(2)
                    .lineSpacing(YoinType.lineSpacing(size: mini ? 15 : 16, lineHeight: (mini ? 15 : 16) * 1.25))
                // Duration and plays on one row; when it doesn't fit, plays
                // wrap under the duration (both kept). Mini tickets trail the score.
                // ScrapTicketMetaLine: Stat groups in labelMedium at 12,
                // tracking 0: numbers Medium tabular, the unit Regular at 60 %.
                let duration = Text(formatTime(t.durationSec)).font(.gsf(12, .medium))
                let plays = HStack(spacing: 4) {
                    Text("\(playCount(t))").font(.gsf(12, .medium))
                    Text("plays").font(.gsf(12)).opacity(0.6)
                }
                let miniScore = Text(scoreText).font(.gsf(15, .semibold)).foregroundStyle(best ? ink : s.primary)
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 12) {
                        duration; plays
                        if mini { Spacer(minLength: 4); miniScore }
                    }
                    VStack(alignment: .leading, spacing: 2) {
                        HStack(spacing: 12) {
                            duration
                            if mini { Spacer(minLength: 4); miniScore }
                        }
                        plays
                    }
                }
                .lineLimit(1)
                .monospacedDigit()
                .frame(minHeight: 16)
                .foregroundStyle(ink.opacity(0.72))
            }
            // The body takes the rest (weight 1): a mini ticket's score sits at
            // its end, 12 from the edge; a full one's column ends 8 before the score.
            .padding(.leading, 13).padding(.vertical, 10).padding(.trailing, mini ? 12 : 8)
            .frame(maxWidth: .infinity, alignment: .leading)
            if !mini {
                Text(scoreText)
                    .font(.gsf(best ? 22 : 16, .semibold)).monospacedDigit()
                    .foregroundStyle(best ? ink : s.primary)
                    .padding(.trailing, 14)
            }
        }
        .frame(minHeight: mini ? 56 : 64)
        .frame(width: width)
        .frame(maxWidth: width == nil ? .infinity : nil)
        .background { TicketShape().fill(fill) }
        .overlay(alignment: .leading) {
            // Perforation at the stub.
            let stub = Tokens.detail.SCRAPBOOK_TICKET_STUB.cg
            Path { p in p.move(to: CGPoint(x: stub, y: 8)); p.addLine(to: CGPoint(x: stub, y: 200)) }
                .stroke(ink.opacity(0.24), style: StrokeStyle(lineWidth: 1.5, dash: [4, 4]))
                .mask(Rectangle().padding(.vertical, 8))
        }
        .overlay(alignment: .topLeading) {
            if starred(t) {
                YoinIcon("favorite_filled", size: 26)
                    .foregroundStyle(s.primary)
                    // Rides on the ticket's layer: only a little extra lag of its own.
                    .modifier(layer(tilt("\(t.number)/heart", Tilt.sticker), depth: 0.1))
                    .offset(x: -9, y: -10)
            }
        }
        .contentShape(TicketShape())
        // Android Ticket: a tap plays the album from the song, a long press opens its sheet (score,
        // About, Notes). The mock's are hand-set and do neither.
        .onTapGesture { play(t) }
        .onLongPressGesture { if !isMock { trackSheet = ScrapSheetOpen(track: t) } }
        .accessibilityAddTraits(isMock ? [] : .isButton)
        .accessibilityAction(named: "Score and edit") { if !isMock { trackSheet = ScrapSheetOpen(track: t) } }
        .scaleEffect(pulsed == t.id ? 1.05 : 1)
        .modifier(layer(tilt("\(t.number)/tk", Tilt.ticket), depth: 0.5))
        .id(t.id)
    }

    private func notesClipping(_ t: Track, _ ns: [Note]) -> some View {
        ZStack(alignment: .leading) {
            Capsule().fill(s.primary).frame(width: 3).padding(.vertical, 14).padding(.leading, 11)
            VStack(alignment: .leading, spacing: 0) {
                ForEach(Array(ns.prefix(ScrapbookRules.notesShown).enumerated()), id: \.offset) { _, n in
                    // A note plays the song from its moment (Android NoteLine).
                    Button { play(t, at: n.positionMs) } label: {
                        HStack(alignment: .firstTextBaseline, spacing: 0) {
                            Text(n.time).font(.gsf(12, .medium)).monospacedDigit().foregroundStyle(s.primary)
                                .frame(width: 42, alignment: .leading)
                            // userTextStyle: GSF 15, line 1.5 em.
                            Text(n.text).font(.gsf(15))
                                .lineSpacing(YoinType.lineSpacing(size: 15, lineHeight: 15 * 1.5))
                                .foregroundStyle(s.onSurface)
                                .multilineTextAlignment(.leading)
                        }
                        .padding(.vertical, 4)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .contentShape(.rect)
                    }
                    .buttonStyle(.plain)
                    .disabled(isMock)
                    .accessibilityLabel(n.time.isEmpty ? String(localized: "Note: \(n.text)") : String(localized: "Note at \(n.time): \(n.text)"))
                }
                if ns.count > ScrapbookRules.notesShown {
                    // Android "+N notes": the rest in the sheet's Notes tab.
                    Button { trackSheet = ScrapSheetOpen(track: t, tab: .notes) } label: {
                        Text(ScrapbookText.hiddenNotes(ns.count - ScrapbookRules.notesShown))
                            .yoinRole(Tokens.type.labelLarge, weight: .semibold)
                            .foregroundStyle(s.primary)
                            .padding(.leading, 42).padding(.top, 6).padding(.bottom, 4).padding(.trailing, 8)
                            .contentShape(.rect)
                    }
                    .buttonStyle(.plain)
                    .disabled(isMock)
                }
            }
            .padding(.leading, 24).padding(.trailing, 12).padding(.vertical, 8)
        }
        .frame(width: min(330, 0.78 * 368), alignment: .leading)
        .background(s.surfaceContainerHighest, in: .rect(cornerRadius: Tokens.shape.CARD.cg, style: .continuous))
        .modifier(layer(-sign(tilt("\(t.number)/tk", Tilt.ticket)) * abs(tilt("\(t.number)/nt", Tilt.paper)), depth: 0.4))
    }

    // MARK: About on the paper (Android AskCard / AboutCard / FactTag)

    /// A question asked in Now Playing: the listener's words, Gemini's title and whole sentences of
    /// its answer; "Read all" (and +N more questions) when the sheet holds more. Opens the sheet.
    private func askCard(_ t: Track, _ ask: ScrapAboutPieces.Ask, more: Int) -> some View {
        let excerpt = ScrapbookText.answerExcerpt(ask.answer, budget: ScrapbookRules.answerBudget)
        let tiltValue = tilt("\(t.number)/qa", Tilt.card)
        return Button { trackSheet = ScrapSheetOpen(track: t) } label: {
            VStack(alignment: .leading, spacing: 0) {
                Text(ask.question)
                    .font(.gsf(14)).lineSpacing(YoinType.lineSpacing(size: 14, lineHeight: 14 * 1.45))
                    .foregroundStyle(s.onTertiaryContainer.opacity(0.8))
                if let title = ask.title {
                    Text(title).font(.gsf(16, .semibold))
                        .foregroundStyle(s.onTertiaryContainer)
                        .padding(.top, 8)
                }
                Text(Self.bold(excerpt.text))
                    .font(.gsf(14.5)).lineSpacing(YoinType.lineSpacing(size: 14.5, lineHeight: 14.5 * 1.55))
                    .foregroundStyle(s.onTertiaryContainer)
                    .padding(.top, 4)
                if excerpt.truncated || more > 0 {
                    HStack(spacing: 8) {
                        // Android colors it askMark (tertiary); on a light container that read as nothing.
                        Text("Read all").yoinRole(Tokens.type.labelLarge, weight: .semibold)
                            .foregroundStyle(s.onTertiaryContainer)
                        if more > 0 {
                            Text("+\(more)").font(.gsf(11, .medium)).monospacedDigit()
                                .foregroundStyle(s.onTertiary)
                                .padding(.horizontal, 6).padding(.vertical, 1)
                                .background(s.tertiary, in: .capsule)
                        }
                    }
                    .padding(.top, 6)
                }
            }
            .multilineTextAlignment(.leading)
            .padding(.horizontal, 16).padding(.top, 16).padding(.bottom, 12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(s.tertiaryContainer, in: .rect(cornerRadius: Tokens.shape.CARD.cg, style: .continuous))
            .overlay(alignment: .topLeading) {
                Text("Q").font(.gsf(14, .semibold)).foregroundStyle(s.onTertiary)
                    .frame(width: 28, height: 28)
                    .background(s.tertiary, in: .circle)
                    .rotationEffect(.degrees(tiltValue * 6))
                    .offset(x: -11, y: -12)
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Asked about \(t.title): \(ask.title ?? ask.question)")
        .accessibilityHint("Read all")
        .frame(maxWidth: 380)
        .containerRelativeFrame(.horizontal) { w, _ in min(w * 0.92, 380) }
        .modifier(layer(tiltValue, depth: 0.35))
    }

    /// The About paragraph's first sentence, for a song with About but no question.
    private func aboutCard(_ t: Track, _ line: String) -> some View {
        Button { trackSheet = ScrapSheetOpen(track: t) } label: {
            VStack(alignment: .leading, spacing: 6) {
                Text("About").yoinRole(Tokens.type.labelLarge, weight: .semibold)
                    .foregroundStyle(s.onTertiaryContainer.opacity(0.8))
                Text(Self.bold(line))
                    .font(.gsf(14.5)).lineSpacing(YoinType.lineSpacing(size: 14.5, lineHeight: 14.5 * 1.55))
                    .foregroundStyle(s.onTertiaryContainer)
            }
            .multilineTextAlignment(.leading)
            .padding(.horizontal, 16).padding(.vertical, 12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(s.tertiaryContainer, in: .rect(cornerRadius: Tokens.shape.CARD.cg, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("About \(t.title): \(line)")
        .accessibilityHint("Read all")
        .containerRelativeFrame(.horizontal) { w, _ in min(w * 0.92, 380) }
        .modifier(layer(tilt("\(t.number)/qa", Tilt.card), depth: 0.35))
    }

    /// A short fact as a tag (the last one says how many more the sheet has).
    private func factTag(_ t: Track, _ tag: ScrapAboutPieces.Tag, index: Int, extra: Int) -> some View {
        Button { trackSheet = ScrapSheetOpen(track: t) } label: {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(tag.label).font(.gsf(11.5, .medium)).foregroundStyle(s.onSecondaryContainer.opacity(0.75))
                Text(extra > 0 ? String(localized: "\(tag.value)  +\(extra)") : tag.value)
                    .yoinRole(Tokens.type.labelLarge)
                    .foregroundStyle(s.onSecondaryContainer)
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)
            }
            .padding(.horizontal, 14).padding(.vertical, 7)
            // A capped radius, not a capsule: a long value wraps (Android FactTagShape).
            .background(s.secondaryContainer, in: .rect(cornerRadius: 16, style: .continuous))
        }
        .buttonStyle(.plain)
        .frame(maxWidth: 340, alignment: .leading)
        .modifier(layer(tilt("\(t.number)/fact\(index)", Tilt.tag), depth: 0.7))
    }

    /// `**bold**` runs as bold (Android markdownBoldAnnotatedString).
    private static func bold(_ text: String) -> AttributedString {
        (try? AttributedString(markdown: text, options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace)))
            ?? AttributedString(text)
    }

    // MARK: Receipt

    private var receipt: some View {
        var rows: [(String, String)] = [("Plays in Yoin", "42"), ("Days since first play", "18"),
                                        ("Last played", "Yesterday")]
        if let plays {
            // AlbumScrapbook receiptLines: plays here, then the first / last play when known.
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            rows = [("Plays in Yoin", "\(plays.playCount)")]
            if let first = plays.firstPlayedAt {
                rows.append(("Days since first play", "\(HomeHistory.calendarDays(from: first, to: now))"))
            }
            if let last = plays.lastPlayedAt { rows.append(("Last played", HomeHistory.relativeDay(last, now: now))) }
        }
        if isMock {
            rows += [("Rated", "\(album.ratedCount) / \(album.tracks.count)"), ("Notes", "4")]
        } else {
            // Android receiptLines: rated tracks on this page, track notes (+ the album note), questions asked.
            let noteCount = album.tracks.reduce(0) { $0 + (notes($1)?.count ?? 0) } + (albumNote == nil ? 0 : 1)
            let asked = album.tracks.reduce(0) { n, t in
                n + SongAboutStore.shared.entries(SongAboutStore.key(title: t.title, artist: t.artist ?? album.artist,
                                                                     album: album.title))
                    .filter { $0.kind == SongAboutStore.Kind.ask }.count
            }
            rows += [("Rated", "\(album.tracks.filter { score($0) != nil }.count) / \(album.tracks.count)"),
                     ("Notes", "\(noteCount)"), ("Asked", "\(asked)")]
        }
        return VStack(alignment: .leading, spacing: 0) {
            Text("\(album.title) — \(album.artist)".uppercased())
                .font(.system(size: 11.5, weight: .medium, design: .monospaced))
                .foregroundStyle(s.onSurface.opacity(0.7))
                .padding(.bottom, 6)
            ForEach(rows, id: \.0) { label, value in
                HStack(alignment: .lastTextBaseline, spacing: 0) {
                    Text(label)
                    Path { p in p.move(to: CGPoint(x: 0, y: 8)); p.addLine(to: CGPoint(x: 400, y: 8)) }
                        .stroke(s.onSurface.opacity(0.3), style: StrokeStyle(lineWidth: 1.5, lineCap: .round, dash: [0.1, 4]))
                        .frame(height: 12)
                        .padding(.horizontal, 6)
                        .clipped()
                    Text(value)
                }
                .font(.system(size: 12.5, design: .monospaced))
                .foregroundStyle(s.onSurface)
                .frame(height: 24)
            }
        }
        .padding(.leading, 18).padding(.trailing, 18).padding(.top, 16).padding(.bottom, 24)
        .frame(maxWidth: 330)
        .background { ReceiptShape().fill(s.surfaceContainer) }
        .modifier(layer(tilt("receipt", Tilt.paper), depth: 0.35))
    }

    // MARK: Tilt

    /// The tilt sets and the FNV-1a hash are the shared Kotlin (ScrapbookTilt, YoinShared).
    private enum Tilt {
        static var paper: KotlinFloatArray { ScrapbookTilt.shared.PAPER }
        static var ticket: KotlinFloatArray { ScrapbookTilt.shared.TICKET }
        static var sticker: KotlinFloatArray { ScrapbookTilt.shared.STICKER }
        static var cover: KotlinFloatArray { ScrapbookTilt.shared.COVER }
        static var card: KotlinFloatArray { ScrapbookTilt.shared.CARD }
        static var tag: KotlinFloatArray { ScrapbookTilt.shared.TAG }
    }

    private func tilt(_ key: String, _ set: KotlinFloatArray) -> Double {
        Double(ScrapbookTilt.shared.tilt(seed: album.id.rawId, key: key, set: set))
    }

    private func sign(_ x: Double) -> Double { x < 0 ? -1 : 1 }

    private func layer(_ tilt: Double, depth: CGFloat) -> ScrapLayer {
        ScrapLayer(tilt: tilt, depth: depth, offset: pageOffset, width: pageWidth)
    }
}

/// A piece's resting tilt plus its share of the pager drag (Android
/// scrapLayer): it trails by depth × 12 % of the page and leans 3° further
/// while away. Transform only; layout and hit areas stay square.
private struct ScrapLayer: ViewModifier {
    static var share: CGFloat { Tokens.detail.SCRAPBOOK_PARALLAX_SHARE.cg }
    static var leanDegrees: Double { Tokens.detail.SCRAPBOOK_LEAN_DEGREES }
    var tilt: Double
    var depth: CGFloat
    var offset: CGFloat
    var width: CGFloat

    func body(content: Content) -> some View {
        let lean = tilt == 0 ? 0 : (tilt < 0 ? -1 : 1) * Self.leanDegrees * Double(offset)
        content
            .rotationEffect(.degrees(tilt + lean))
            .offset(x: offset * width * depth * Self.share)
    }
}

// MARK: - Shapes

/// Card-cornered (16) ticket with 7 pt half-circle notches top and bottom at
/// the stub's edge (x = 48).
struct TicketShape: Shape {
    func path(in r: CGRect) -> Path {
        let card = Path(roundedRect: r, cornerRadius: Tokens.shape.CARD.cg, style: .continuous)
        let x = r.minX + Tokens.detail.SCRAPBOOK_TICKET_STUB.cg, n = Tokens.detail.SCRAPBOOK_TICKET_NOTCH.cg
        var notches = Path()
        notches.addEllipse(in: CGRect(x: x - n, y: r.minY - n, width: 2 * n, height: 2 * n))
        notches.addEllipse(in: CGRect(x: x - n, y: r.maxY - n, width: 2 * n, height: 2 * n))
        return card.subtracting(notches)
    }
}

/// Sticky note: 16 pt corners, bottom-right corner cut from (w−17, h) to
/// (w, h−17), with the folded flap drawn in the flap color.
struct StickyNote: View {
    var fill: Color
    var flap: Color
    var body: some View {
        GeometryReader { g in
            let w = g.size.width, h = g.size.height, c = Tokens.shape.CARD.cg
            ZStack {
                Path { p in
                    p.move(to: CGPoint(x: c, y: 0))
                    p.addLine(to: CGPoint(x: w - c, y: 0))
                    p.addQuadCurve(to: CGPoint(x: w, y: c), control: CGPoint(x: w, y: 0))
                    p.addLine(to: CGPoint(x: w, y: h - 17))
                    p.addLine(to: CGPoint(x: w - 17, y: h))
                    p.addLine(to: CGPoint(x: c, y: h))
                    p.addQuadCurve(to: CGPoint(x: 0, y: h - c), control: CGPoint(x: 0, y: h))
                    p.addLine(to: CGPoint(x: 0, y: c))
                    p.addQuadCurve(to: CGPoint(x: c, y: 0), control: .zero)
                }
                .fill(fill)
                Path { p in
                    p.move(to: CGPoint(x: w - 17, y: h))
                    p.addLine(to: CGPoint(x: w - 17, y: h - 13))
                    p.addQuadCurve(to: CGPoint(x: w - 13, y: h - 17), control: CGPoint(x: w - 17, y: h - 17))
                    p.addLine(to: CGPoint(x: w, y: h - 17))
                    p.closeSubpath()
                }
                .fill(flap)
            }
        }
    }
}

/// Receipt: 20 pt top corners, zig-zag bottom (≈14 pt teeth, 8 pt deep).
struct ReceiptShape: Shape {
    func path(in r: CGRect) -> Path {
        var p = Path()
        let teeth = max(1, Int((r.width / 14).rounded()))
        let tw = r.width / CGFloat(teeth)
        // Circular 20 pt arcs, as Compose's arcTo.
        let c = min(20, min(r.width, r.height) / 2)
        p.move(to: CGPoint(x: r.minX, y: r.minY + c))
        p.addArc(center: CGPoint(x: r.minX + c, y: r.minY + c), radius: c,
                 startAngle: .degrees(180), endAngle: .degrees(270), clockwise: false)
        p.addLine(to: CGPoint(x: r.maxX - c, y: r.minY))
        p.addArc(center: CGPoint(x: r.maxX - c, y: r.minY + c), radius: c,
                 startAngle: .degrees(270), endAngle: .degrees(0), clockwise: false)
        p.addLine(to: CGPoint(x: r.maxX, y: r.maxY - 8))
        for i in (0..<teeth).reversed() {
            let x0 = r.minX + CGFloat(i) * tw
            p.addLine(to: CGPoint(x: x0 + tw / 2, y: r.maxY))
            p.addLine(to: CGPoint(x: x0, y: r.maxY - 8))
        }
        p.closeSubpath()
        return p
    }
}

/// 16 pt tiles with a 1.2 pt dot at the centre.
struct DotPaper: View {
    var color: Color
    var body: some View {
        let pitch = Tokens.detail.SCRAPBOOK_DOT_SPACING.cg, dot = Tokens.detail.SCRAPBOOK_DOT_RADIUS.cg
        Canvas { gc, size in
            var y: CGFloat = pitch / 2
            while y < size.height {
                var x: CGFloat = pitch / 2
                while x < size.width + pitch {
                    gc.fill(Path(ellipseIn: CGRect(x: x - dot, y: y - dot, width: 2 * dot, height: 2 * dot)), with: .color(color))
                    x += pitch
                }
                y += pitch
            }
        }
        .padding(.horizontal, -16)
    }
}

/// A progressive blur for content passing under a floating header: a thin
/// material plus the page's own color, solid at the top and fading out over
/// the lower 45 %. Draws only; touches pass through.
struct ProgressiveEdgeBlur: View {
    var tint: Color

    var body: some View {
        ZStack {
            Rectangle().fill(.thinMaterial)
            Rectangle().fill(tint.opacity(0.72))
        }
        .mask {
            LinearGradient(stops: [.init(color: .black, location: 0), .init(color: .black, location: 0.55),
                                   .init(color: .black.opacity(0.6), location: 0.75), .init(color: .clear, location: 1)],
                           startPoint: .top, endPoint: .bottom)
        }
        .allowsHitTesting(false)
    }
}

/// A ticket's score (Android AlbumScrapbookTrackSheet, score row, minimal):
/// the number pill ("—" unrated), a horizontal bar that rates where the
/// finger lands and commits on release, and Clear, the one place a song goes
/// back to never rated (51, 2026-11-04). Clear offers Undo in a dark pill.
/// `embedded`: the head of the track sheet (ScrapTrackSheet), which brings
/// its own sheet chrome; the Undo then rides the shell's toast.
struct ScrapScoreSheet: View {
    var track: Track
    var scheme: CoverScheme
    var store: LibraryStore
    var embedded = false

    @State private var draft: Double?
    @State private var undo: (value: Double, id: UUID)?

    private var ratings: LocalRatings { .shared }
    private var stored: Double? { ratings.rating(provider: track.id.provider, songId: track.id.rawId) }
    private var live: Double? { draft ?? stored }

    var body: some View {
        if embedded {
            score
        } else {
            score
                .padding(.horizontal, 24)
                .padding(.top, 28)
                .frame(maxHeight: .infinity, alignment: .top)
                .overlay(alignment: .bottom) { undoPill }
                .background(scheme.surfaceContainerLow)
                .animation(Yoin.spatial, value: undo?.id)
        }
    }

    private var score: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(spacing: 12) {
                Text(String(format: "%02d", track.number))
                    .font(.gsf(14, .medium)).monospacedDigit()
                    .foregroundStyle(scheme.onSurfaceVariant)
                Text(track.title)
                    .font(.gsf(22, .semibold)).lineLimit(1)
                    .foregroundStyle(scheme.onSurface)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Text(RatingBar.label(live) ?? "—")
                    .font(.gsf(16, .semibold)).monospacedDigit()
                    .foregroundStyle(scheme.onPrimaryContainer)
                    .padding(.horizontal, 16)
                    .frame(minWidth: 60, minHeight: 40)
                    .background(scheme.primaryContainer, in: .capsule)
                    .contentTransition(.numericText())
                    .accessibilityLabel(live == nil ? String(localized: "Not rated") : "Score \(RatingBar.label(live) ?? "")")
            }
            HStack(spacing: 8) {
                ScrapScoreBar(value: live, scheme: scheme, onChange: { draft = $0 }, onCommit: commit)
                // Its width is held when it fades, so clearing doesn't shift the bar.
                Button("Clear", action: clear)
                    .font(.gsf(15, .medium))
                    .foregroundStyle(scheme.primary)
                    .frame(width: 64)
                    .opacity(live == nil ? 0 : 1)
                    .disabled(live == nil)
                    .animation(Yoin.effects, value: live == nil)
            }
        }
    }

    @ViewBuilder
    private var undoPill: some View {
        if let u = undo {
            HStack(spacing: 16) {
                Text("Rating cleared").font(.gsf(15, .medium))
                Button("Undo") { restore(u.value) }
                    .font(.gsf(15, .semibold))
                    .foregroundStyle(scheme.inversePrimary)
            }
            .foregroundStyle(scheme.inverseOnSurface)
            .padding(.horizontal, 20)
            .frame(height: 48)
            .background(scheme.inverseSurface, in: .capsule)
            .padding(.bottom, 12)
            .transition(.move(edge: .bottom).combined(with: .opacity))
            .id(u.id)
        }
    }

    private func commit() {
        guard let v = draft else { return }
        ratings.set(v, provider: track.id.provider, songId: track.id.rawId)
        draft = nil
        Task { _ = await store.setRating(track, rating: v) }
    }

    private func clear() {
        guard let was = stored else { return }
        draft = nil
        withAnimation(Yoin.spatial) {
            ratings.clear(provider: track.id.provider, songId: track.id.rawId)
        }
        // Subsonic: 0 stars removes the server's rating.
        Task { _ = await store.setRating(track, rating: nil) }
        if embedded {
            PlaylistActions.shared.show(String(localized: "Rating cleared"), action: .init(label: String(localized: "Undo")) { restore(was) })
            return
        }
        let id = UUID()
        undo = (was, id)
        Task {
            try? await Task.sleep(for: .seconds(4))
            if undo?.id == id { undo = nil }
        }
    }

    private func restore(_ v: Double) {
        undo = nil
        withAnimation(Yoin.spatial) {
            ratings.set(v, provider: track.id.provider, songId: track.id.rawId)
        }
        Task { _ = await store.setRating(track, rating: v) }
    }
}

/// The sheet's horizontal score bar (Android RatingSlider, horizontal): the
/// primary fill from the leading edge, empty while unrated; touch-down rates
/// where the finger lands, release commits.
private struct ScrapScoreBar: View {
    var value: Double?
    var scheme: CoverScheme
    var onChange: (Double) -> Void
    var onCommit: () -> Void

    var body: some View {
        GeometryReader { g in
            let w = g.size.width
            ZStack(alignment: .leading) {
                Capsule().fill(scheme.surfaceContainerHighest)
                Capsule().fill(scheme.primary)
                    .frame(width: value == nil ? 0 : max(48, w * (value ?? 0) / 10))
                    .animation(Yoin.spatial, value: value == nil)
            }
            .animation(Yoin.spatialFast, value: value)
            .contentShape(.capsule)
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { v in
                        let raw = Double(v.location.x / max(w, 1) * 10)
                        onChange((min(10, max(0, raw)) * 10).rounded() / 10)
                    }
                    .onEnded { _ in onCommit() }
            )
        }
        .frame(height: 48)
        .accessibilityElement()
        .accessibilityLabel("Score")
        .accessibilityValue(RatingBar.label(value) ?? String(localized: "Not rated"))
    }
}

/// The album's rate + review sheet (Android AlbumRateSheet): "Rate" and the
/// album at the top left beside the emblem, the score bar in the cover's
/// colors, then the review. A score commits on release (0 = no album
/// rating, the emblem goes back to the tracks' average); the words save
/// when the sheet closes. No Clear here: clearing a song's score lives in
/// the scrapbook's score sheet. Written to AlbumMemoryStore (Android
/// album_ratings) and handed to cloud sync; the closed sheet hands changes
/// to NeoDB when signed in (AlbumNeoDBLine.swift: the last line, the push).
struct AlbumRateSheet: View {
    var album: Album
    var scheme: CoverScheme

    @State private var draft: Double?
    @State private var dragging = false
    @State private var text = ""
    @FocusState private var focused: Bool
    @Environment(AppModel.self) private var model: AppModel?
    @Environment(\.dismiss) private var dismiss

    private var memories: AlbumMemoryStore { .shared }
    /// NeoDB's line and push (AlbumNeoDBLine.swift).
    private var neo: AlbumNeoDBSync { .shared }
    private var neoState: AlbumNeoDBState { neo.state(album) }
    private var record: AlbumMemoryStore.AlbumRecord? {
        memories.album(provider: album.id.provider, albumId: album.id.rawId)
    }
    /// The album's own score; nil = not rated (album_ratings keeps 0 for that).
    private var stored: Double? { record.flatMap { $0.rating > 0 ? $0.rating : nil } }
    private var live: Double? {
        guard let draft else { return stored }
        return draft > 0 ? draft : nil
    }
    private var bun: AlbumBun {
        var b = AlbumBun.of(album)
        b.albumRating = live
        return b
    }
    private var open: Bool { focused || !text.isEmpty }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                HStack(alignment: .top, spacing: 16) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Rate")
                            .yoinRole(Tokens.type.labelLarge)
                            .foregroundStyle(scheme.onSurfaceVariant)
                        Text(album.title)
                            .lineLimit(3)
                            .yoinRole(Tokens.type.titleLarge, weight: .semibold)
                            .foregroundStyle(scheme.onSurface)
                    }
                    .padding(.top, 8)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityElement(children: .combine)
                    .accessibilityAddTraits(.isHeader)
                    // Lifts and leans with the score while the bar is held.
                    ScoreEmblem(score: bun.score, kind: bun.kind, rated: bun.rated, palette: album.palette,
                                size: 120, neutrals: scheme)
                        .scaleEffect(dragging ? 1.06 : 1)
                        .rotationEffect(.degrees(dragging ? ((live ?? 0) - 5) * 1.4 : 0))
                        .animation(Yoin.spatial, value: dragging)
                }
                ScrapScoreBar(value: live, scheme: scheme, onChange: { v in
                    draft = v
                    dragging = true
                }, onCommit: commit)
                reviewField
                if neoState.isShown {
                    AlbumNeoDBLine(state: neoState, scheme: scheme, onSignIn: openNeoDBSettings,
                                   onRetry: { Task { await neo.retry(album) } })
                        .transition(.opacity)
                }
            }
            .animation(Yoin.spatial, value: neoState.isShown)
            .padding(.horizontal, 24)
            .padding(.top, 28)
            .padding(.bottom, 20)
        }
        .scrollDismissesKeyboard(.interactively)
        .background(scheme.surfaceContainerLow)
        .onAppear {
            text = record?.review ?? ""
            neo.sheetOpened(album)
        }
        .onChange(of: text) { _, _ in neo.edited(album, unsavedWords: normalizedText != record?.review) }
        .onDisappear {
            saveReview()
            let album = album
            Task { await AlbumNeoDBSync.shared.sheetClosed(album) }
        }
    }

    private var normalizedText: String? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    /// "Sign in to sync": Settings, where NeoDB signs in (Android opens Settings › NeoDB).
    private func openNeoDBSettings() {
        dismiss()
        model?.openSettings()
    }

    /// AlbumReviewField: a borderless field on surfaceContainerHigh, 28
    /// corners closed, 20 once open (a spatial spring); "Review" as its hint.
    private var reviewField: some View {
        VStack(alignment: .leading, spacing: 8) {
            TextField("", text: $text, prompt: Text("Review").foregroundStyle(scheme.onSurfaceVariant), axis: .vertical)
                .lineLimit(3...)
                .font(.gsf(Tokens.type.bodyLarge.size.cg))
                .lineSpacing(YoinType.lineSpacing(Tokens.type.bodyLarge))
                .foregroundStyle(scheme.onSurface)
                .tint(scheme.primary)
                .focused($focused)
            // Where the words go on NeoDB: a short comment (with its count) or a review.
            if !text.isEmpty {
                AlbumReviewRoute(text: text, scheme: scheme)
                    .transition(.opacity)
            }
        }
        .animation(Yoin.spatial, value: text.isEmpty)
        .padding(.horizontal, 20)
        .padding(.vertical, 16)
        .background(scheme.surfaceContainerHigh,
                    in: .rect(cornerRadius: open ? 20 : 28, style: .continuous))
        .animation(Yoin.spatial, value: open)
    }

    private var now: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    private func commit() {
        dragging = false
        guard let v = draft else { return }
        draft = nil
        let was = record
        withAnimation(Yoin.effects) {
            memories.putAlbum(.init(provider: album.id.provider, albumId: album.id.rawId,
                                    rating: min(10, max(0, v)), review: was?.review,
                                    reviewUpdatedAt: was?.reviewUpdatedAt, updatedAt: now))
        }
        NeoDBPendingStore.shared.markRatingChanged(provider: album.id.provider, albumId: album.id.rawId)
        neo.edited(album, unsavedWords: normalizedText != record?.review)
        CloudSync.shared.localChanged()
    }

    /// Android setAlbumReview: trimmed, blank = none; the written date moves
    /// only when the words do.
    private func saveReview() {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let normalized = trimmed.isEmpty ? nil : trimmed
        let was = record
        guard normalized != was?.review, was != nil || normalized != nil else { return }
        memories.putAlbum(.init(provider: album.id.provider, albumId: album.id.rawId,
                                rating: was?.rating ?? 0, review: normalized,
                                reviewUpdatedAt: normalized == nil ? nil : now, updatedAt: now))
        NeoDBPendingStore.shared.markReviewChanged(provider: album.id.provider, albumId: album.id.rawId)
        CloudSync.shared.localChanged()
    }
}
