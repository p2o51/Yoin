import SwiftUI

/*
 * The diary state of one memory (Android showcase/MemoryDiary, v4.1). One
 * vertical scroll per page, under the bar; from the top:
 *
 *  · the title row: Yoin's title (the AI's or yours, in the serif) with the
 *    groove drawn at 48 pt at its right end; fallback A's row is Yoin's
 *    paragraph beside the 48;
 *  · Yoin's paragraph (no review only);
 *  · your entry: the review, or today's blank page (MemoryDiaryEntry);
 *  · • • • between the review and the liner, 40 pt of air after a blank page;
 *  · the liner: only the rated or noted tracks as 48 pt rows with their notes
 *    under them (a stamp and the words); a tap plays from there;
 *  · the end, 56 pt after the last block: the run-out groove and the two
 *    numerals (plays; days since the first play, only with play history),
 *    Go to album, and the quiet NeoDB line.
 *
 * Every block is fully laid out; the morph only moves and fades them. A pull
 * past the top of the text is p's (DiaryScrollObserver); the text takes no
 * input until the diary is open.
 */

/// Diary geometry. Layout constants, not motion tokens.
enum MemoryDiaryTokens {
    static let emblem: CGFloat = 48
    static let sideInset: CGFloat = 24
    static let topInset: CGFloat = 12
    static let bottomInset: CGFloat = 100
    static let emblemGap: CGFloat = 14
    static let headGap: CGFloat = 28
    static let headGapWithParagraph: CGFloat = 24
    static let paragraphTop: CGFloat = 10
    static let rowMin: CGFloat = 48
    static let rowBleed: CGFloat = 12
    static let notedGroupGap: CGFloat = 8
    static let blankLinerGap: CGFloat = 40
    static let endGap: CGFloat = 56
    static let runOut: CGFloat = 28
    static let runOutBottom: CGFloat = 22
    static let runOutAlpha: CGFloat = 0.45
    static let statGap: CGFloat = 48
    static let goTop: CGFloat = 32
    static let linerColumn: CGFloat = 36
    static let separatorTop: CGFloat = 28
    static let separatorBottom: CGFloat = 20
    static let titleSize: CGFloat = 22
    static let paragraphSize: CGFloat = 16
}

/// The two-state diary of one page.
struct MemoryDiaryView: View {
    var card: MemoryCard
    var deck: MemoriesDeckModel
    var morph: MemoryPageMorph
    var layout: MemoriesLayout
    var scheme: CoverScheme
    var dark: Bool
    var interactive: Bool
    var onOpenAlbum: () -> Void
    var onTitle: (MemoryTitles.Outcome) -> Void
    var onReview: (String) -> Bool
    var onOpenNeoDBSettings: () -> Void
    var horizontalPager: AnyGesture<DragGesture.Value>

    @State private var scroll = ScrollPosition(edge: .top)
    private var diary: MemoriesDiary { deck.diary }
    private var barHeight: CGFloat { layout.barHeight }

    var body: some View {
        let tones = MemoryTones(palette: card.album.palette, dark: dark)
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                DiaryHead(card: card, deck: deck, morph: morph, scheme: scheme, tones: tones, enabled: interactive,
                          onTitle: onTitle)
                    .pageFrame { r in if morph.scrollY < 0.5 { morph.diaryTop = r.minY } }
                DiaryBlocks(card: card, deck: deck, scheme: scheme, tones: tones, interactive: interactive,
                            showGo: true, firstBlock: card.paragraph != nil && card.review == nil && card.titleKind != .album ? 1 : 0,
                            onOpenAlbum: onOpenAlbum, onReview: onReview, onOpenNeoDBSettings: onOpenNeoDBSettings) { k, view in
                    AnyView(view.morphLayer(diary) { p in blockPose(p, k: k) })
                }
            }
            .padding(.horizontal, MemoryDiaryTokens.sideInset)
            .padding(.top, MemoryDiaryTokens.topInset)
            .padding(.bottom, MemoryDiaryTokens.bottomInset)
            .frame(maxWidth: layout.tier == .medium ? layout.diaryColumn : .infinity)
            .frame(maxWidth: .infinity)
            .background(DiaryScrollObserver(router: deck.router, active: interactive, spread: false))
        }
        .contentMargins(.top, barHeight, for: .scrollContent)
        .scrollPosition($scroll)
        .scrollIndicators(.hidden)
        .scrollEdgeEffectStyle(.soft, for: [.top, .bottom])
        .scrollDisabled(!interactive)
        .scrollDismissesKeyboard(.interactively)
        .onScrollGeometryChange(for: CGFloat.self) { $0.contentOffset.y + $0.contentInsets.top } action: { _, y in
            morph.scrollY = max(0, y)
        }
        .simultaneousGesture(horizontalPager, including: interactive ? .all : .subviews)
        .modifier(DiaryViewport(diary: diary))
        .allowsHitTesting(interactive)
        .accessibilityHidden(!interactive)
        // A collapse from a scrolled title is frozen until it lands; the text
        // returns to its top unseen at p < .05.
        .onChange(of: diary.target) { _, to in
            if to == 0, morph.frozen(barHeight: barHeight) { morph.frozenHold = true }
        }
        .onChange(of: diary.isShown) { _, shown in
            if !shown { resetScroll() }
        }
        .onChange(of: diary.isDiaryLevel) { _, level in
            if !level, morph.frozenHold { resetScroll() }
            if level, diary.target == 1 { morph.frozenHold = false }
        }
    }

    private func resetScroll() {
        scroll.scrollTo(edge: .top)
        morph.scrollY = 0
        morph.frozenHold = false
    }

    private func blockPose(_ p: CGFloat, k: Int) -> MorphPose {
        if diary.reduced { return MorphPose() }
        let frozen = morph.frozen(barHeight: barHeight)
        return MorphPose(dy: MemoriesMorph.blockOffset(p, k: k, lag: morph.lag, frozen: frozen)
                            + MemoriesMorph.frozenSink(p, lag: MemoriesMorphTokens.blockLagExtra, frozen: frozen),
                         alpha: MemoriesMorph.blockAlpha(p, k: k, frozen: frozen))
    }
}

/// The whole diary under Reduce Motion: alpha only (from p .5).
private struct DiaryViewport: ViewModifier {
    var diary: MemoriesDiary
    func body(content: Content) -> some View {
        content.opacity(diary.reduced ? Double(MemoriesMorph.reducedDiaryAlpha(diary.fraction)) : 1)
    }
}

/// The title row (and Yoin's paragraph under it), the 48 at its end.
struct DiaryHead: View {
    var card: MemoryCard
    var deck: MemoriesDeckModel
    var morph: MemoryPageMorph
    var scheme: CoverScheme
    var tones: MemoryTones
    var enabled: Bool
    var onTitle: (MemoryTitles.Outcome) -> Void

    private var diary: MemoriesDiary { deck.diary }
    private var paragraph: String? { card.review == nil ? card.paragraph : nil }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if card.titleKind != .album {
                HStack(alignment: .center, spacing: MemoryDiaryTokens.emblemGap) {
                    title
                        .frame(maxWidth: .infinity, alignment: .leading)
                    emblem
                }
                MemoryTitleEditRow(card: card, surface: .diary, editor: deck.editor, scheme: scheme, tones: tones,
                                   centred: false, onCommit: onTitle)
                if let paragraph {
                    DiaryParagraph(text: paragraph, scheme: scheme)
                        .padding(.top, MemoryDiaryTokens.paragraphTop)
                        .morphLayer(diary) { p in blockPose(p, k: 0) }
                }
            } else {
                // Fallback A: the album name already flew to the bar; the paragraph heads the row.
                HStack(alignment: .top, spacing: MemoryDiaryTokens.emblemGap) {
                    Group {
                        if let paragraph {
                            DiaryParagraph(text: paragraph, scheme: scheme)
                        } else {
                            HStack(spacing: Tokens.metaLine.GROUP_GAP.cg) {
                                if !card.album.artist.isEmpty { Text(card.album.artist) }
                                if card.album.year > 0 { Text(String(card.album.year)).monospacedDigit() }
                            }
                            .font(.gsf(15, .medium))
                            .foregroundStyle(scheme.onSurfaceVariant)
                            .lineLimit(1)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .morphLayer(diary) { p in blockPose(p, k: 0) }
                    emblem
                }
                .onAppear { morph.diaryTitle = nil }
            }
        }
        .padding(.bottom, paragraph != nil ? MemoryDiaryTokens.headGapWithParagraph : MemoryDiaryTokens.headGap)
    }

    private var barHeight: CGFloat { MemoriesLayoutTokens.barHeight }

    private func blockPose(_ p: CGFloat, k: Int) -> MorphPose {
        if diary.reduced { return MorphPose() }
        let frozen = morph.frozen(barHeight: barHeight)
        return MorphPose(dy: MemoriesMorph.blockOffset(p, k: k, lag: morph.lag, frozen: frozen)
                            + MemoriesMorph.frozenSink(p, lag: MemoriesMorphTokens.blockLagExtra, frozen: frozen),
                         alpha: MemoriesMorph.blockAlpha(p, k: k, frozen: frozen))
    }

    private var title: some View {
        let font = Font.system(size: MemoryDiaryTokens.titleSize, weight: .medium, design: .serif)
        let spacing = MemoryDiaryTokens.titleSize * 0.3 - 4
        return MemoryTitleSlot(card: card, surface: .diary, editor: deck.editor, scheme: scheme, tones: tones, font: font,
                               lineSpacing: spacing, centred: false, enabled: enabled, onCommit: onTitle) {
            Text(card.title)
                .font(font)
                .lineSpacing(spacing)
                .foregroundStyle(scheme.onSurface)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)
        }
        .morphLayer(diary, anchor: .top) { p in titlePose(p) }
        .pageFrame { r in
            guard morph.scrollY < 0.5, deck.diary.fraction >= 0.999 || morph.diaryTitle == nil else { return }
            morph.diaryTitle = r
            morph.diaryTitleFont = MemoryDiaryTokens.titleSize
        }
    }

    private func titlePose(_ p: CGFloat) -> MorphPose {
        if diary.reduced { return MorphPose() }
        let frozen = morph.frozen(barHeight: barHeight)
        if frozen { return MorphPose(dy: MemoriesMorph.frozenSink(p, lag: MemoriesMorphTokens.blockLagExtra, frozen: true),
                                     alpha: MemoriesMorph.blockAlpha(p, k: 0, frozen: true)) }
        guard let d = morph.diaryTitle, morph.cardTitle != .zero else { return MorphPose(alpha: smoothstep(0.4, 0.8, p)) }
        let t = MemoriesMorph.titleTravel(p, frozen: false)
        return MemoriesMorph.textPair(card: TextAnchor(cx: morph.cardTitle.midX, y: morph.cardTitle.minY, font: morph.cardTitleFont),
                                      diary: TextAnchor(cx: d.midX, y: d.minY, font: morph.diaryTitleFont), t: t).diary
    }

    @ViewBuilder
    private var emblem: some View {
        let target = deck.diaryEmblem(card)
        DiaryEmblem(card: card, target: target, scheme: scheme)
            .morphLayer(diary) { p in demPose(p) }
            .pageFrame { r in
                guard morph.scrollY < 0.5, deck.diary.fraction >= 0.999 || morph.dem == .zero else { return }
                morph.dem = r
            }
    }

    private func demPose(_ p: CGFloat) -> MorphPose {
        if diary.reduced { return MorphPose() }
        let frozen = morph.frozen(barHeight: barHeight)
        if frozen { return MorphPose(dy: MemoriesMorph.frozenSink(p, lag: MemoriesMorphTokens.blockLagExtra, frozen: true),
                                     alpha: MemoriesMorph.blockAlpha(p, k: 0, frozen: true)) }
        guard morph.seal != .zero, morph.dem != .zero else { return MorphPose(alpha: smoothstep(0.5, 0.9, p)) }
        let te = MemoriesMorph.titleTravel(p, frozen: false)
        return MemoriesMorph.emblemPose(te: te, sealCenter: center(morph.seal), demCenter: center(morph.dem),
                                        seal: morph.seal.width, dem: morph.dem.width).dem
    }
}

/// The 48 pt groove by the diary title: uncut while its card waits, a nod when met here.
private struct DiaryEmblem: View {
    var card: MemoryCard
    var target: DiaryEmblemTarget?
    var scheme: CoverScheme
    @State private var nod: CGFloat = 1

    var body: some View {
        ScoreEmblem(score: card.score ?? 0, kind: card.emblemKind, rated: card.rated, palette: card.album.palette,
                    size: MemoryDiaryTokens.emblem, award: target?.award, neutrals: scheme)
            .scaleEffect(nod)
            .onChange(of: target?.nods ?? 0) { _, _ in
                withAnimation(Yoin.spatialFast) { nod = 1.14 } completion: {
                    withAnimation(Yoin.spatial) { nod = 1 }
                }
            }
    }
}

/// Yoin's paragraph: how you listened and the question, in one breath.
struct DiaryParagraph: View {
    var text: String
    var scheme: CoverScheme
    var centred = false

    var body: some View {
        Text(text)
            .font(.gsf(MemoryDiaryTokens.paragraphSize))
            .lineSpacing(DiaryType.lineSpacing(MemoryDiaryTokens.paragraphSize, 1.6))
            .foregroundStyle(scheme.onSurfaceVariant)
            .multilineTextAlignment(centred ? .center : .leading)
            .fixedSize(horizontal: false, vertical: true)
    }
}

/// The diary from your entry on: the entry, • • • (or air), the liner, the
/// run-out groove and the foot. Shared by the two-state diary and the
/// spread's right page. [block] gives the k-th block its layer (the morph's
/// staggered rise; none in the spread).
struct DiaryBlocks: View {
    var card: MemoryCard
    var deck: MemoriesDeckModel
    var scheme: CoverScheme
    var tones: MemoryTones
    var interactive: Bool
    var showGo: Bool
    var firstBlock: Int
    var onOpenAlbum: () -> Void
    var onReview: (String) -> Bool
    var onOpenNeoDBSettings: () -> Void
    var block: (Int, AnyView) -> AnyView = { _, v in v }

    @Environment(AppModel.self) private var model

    var body: some View {
        let liner = !card.diaryTracks.isEmpty
        let footer = MemoryDeck.footer(plays: card.playCount, firstPlayedAt: card.firstPlayedAt,
                                       now: Int64(Date().timeIntervalSince1970 * 1000))
        let k0 = firstBlock
        VStack(alignment: .leading, spacing: 0) {
            block(k0, AnyView(MemoryDiaryEntry(card: card, tones: tones, scheme: scheme, drafts: deck.drafts,
                                               enabled: interactive, onSave: onReview)))
            if liner {
                if card.review != nil {
                    block(k0 + 1, AnyView(separator))
                } else {
                    Color.clear.frame(height: MemoryDiaryTokens.blankLinerGap)
                }
                block(k0 + 2, AnyView(linerView))
            }
            Color.clear.frame(height: MemoryDiaryTokens.endGap)
            if footer != nil { block(k0 + 3, AnyView(runOut)) }
            block(k0 + 4, AnyView(foot(footer)))
        }
    }

    private var separator: some View {
        HStack(spacing: 10) {
            ForEach(0..<3, id: \.self) { _ in
                Circle().fill(tones.ink.opacity(MemoryDiaryTokens.runOutAlpha)).frame(width: 4, height: 4)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.top, MemoryDiaryTokens.separatorTop)
        .padding(.bottom, MemoryDiaryTokens.separatorBottom)
        .accessibilityHidden(true)
    }

    private var linerView: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(card.diaryTracks.enumerated()), id: \.element.id) { i, group in
                if i > 0, !card.diaryTracks[i - 1].notes.isEmpty {
                    Color.clear.frame(height: MemoryDiaryTokens.notedGroupGap)
                }
                trackGroup(group)
            }
        }
        .frame(maxWidth: 520, alignment: .leading)
    }

    private func playing(_ t: MemoryCard.DiaryTrack) -> Bool {
        model.player.currentTrack?.id == t.track.id.rawId
    }

    private func trackGroup(_ g: MemoryCard.DiaryTrack) -> some View {
        let rated = g.rating != nil
        let isPlaying = playing(g)
        return VStack(alignment: .leading, spacing: 0) {
            Button {
                MemoriesHaptics.soft(0.4)
                play(g, at: nil)
            } label: {
                HStack(spacing: 0) {
                    Text("\(g.track.number)")
                        .font(.gsf(13, .medium)).monospacedDigit()
                        .foregroundStyle(isPlaying ? tones.ink : scheme.onSurfaceVariant)
                        .frame(width: MemoryDiaryTokens.linerColumn, alignment: .leading)
                    Text(g.track.title)
                        .font(.gsf(16, rated ? .semibold : .medium))
                        .foregroundStyle(rated ? scheme.onSurface : scheme.onSurfaceVariant)
                        .lineLimit(1)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    if let r = g.rating {
                        Text(String(format: "%.1f", (r * 10).rounded() / 10))
                            .font(.gsf(15, .semibold)).monospacedDigit()
                            .foregroundStyle(tones.ink)
                            .padding(.leading, 16)
                    }
                }
                .padding(.horizontal, MemoryDiaryTokens.rowBleed)
                .frame(minHeight: MemoryDiaryTokens.rowMin)
                .contentShape(.rect)
            }
            .buttonStyle(DiaryRowStyle(scheme: scheme))
            .padding(.horizontal, -MemoryDiaryTokens.rowBleed)
            .disabled(!interactive)
            .animation(Yoin.effects, value: isPlaying)
            .accessibilityLabel(g.rating.map { "Track \(g.track.number), \(g.track.title), rated \(String(format: "%.1f", $0))" }
                ?? String(localized: "Track \(g.track.number), \(g.track.title)"))
            ForEach(g.notes) { n in
                Button {
                    MemoriesHaptics.soft(0.4)
                    play(g, at: n.positionMs)
                } label: {
                    HStack(alignment: .firstTextBaseline, spacing: 0) {
                        Text(n.positionMs.map(MemoryDiaryFormat.stamp) ?? "")
                            .font(.gsf(12, .medium)).monospacedDigit()
                            .foregroundStyle(tones.ink)
                            .frame(width: MemoryDiaryTokens.linerColumn, alignment: .leading)
                        Text(n.content.trimmingCharacters(in: .whitespacesAndNewlines))
                            .font(.gsf(DiaryType.note))
                            .lineSpacing(DiaryType.lineSpacing(DiaryType.note, DiaryType.noteLine))
                            .foregroundStyle(scheme.onSurface)
                            .multilineTextAlignment(.leading)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .padding(.horizontal, MemoryDiaryTokens.rowBleed)
                    .padding(.vertical, 8)
                    .contentShape(.rect)
                }
                .buttonStyle(DiaryRowStyle(scheme: scheme))
                .padding(.horizontal, -MemoryDiaryTokens.rowBleed)
                .disabled(!interactive)
                .accessibilityLabel(n.positionMs.map { "Note at \(MemoryDiaryFormat.stamp($0)): \(n.content)" } ?? String(localized: "Note: \(n.content)"))
            }
        }
    }

    /// A row plays its track from the start; a note from its moment. The
    /// song already current is not restarted: it seeks there and plays on if
    /// it was paused (Android `requestSeek`). Another song's seek is held by
    /// the output until the song is ready (AppleMusicAudioOutput).
    private func play(_ g: MemoryCard.DiaryTrack, at ms: Int64?) {
        let album = card.album
        let current = model.player.currentTrack?.id == g.track.id.rawId
        if !current {
            model.play(album.tracks.map { PlaylistEntry(album: album, track: $0) }, startAt: g.index)
        }
        if let ms { model.player.seek(to: Double(ms) / 1000) }
        if current { model.player.resume() }
    }

    /// The record's run-out groove: three hairline rings over the numerals.
    private var runOut: some View {
        ZStack {
            ForEach(0..<3, id: \.self) { i in
                Circle().stroke(tones.ink.opacity(MemoryDiaryTokens.runOutAlpha), lineWidth: 1)
                    .frame(width: MemoryDiaryTokens.runOut - CGFloat(i) * 8, height: MemoryDiaryTokens.runOut - CGFloat(i) * 8)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.bottom, MemoryDiaryTokens.runOutBottom)
        .accessibilityHidden(true)
    }

    private func foot(_ footer: (plays: Int, days: Int)?) -> some View {
        VStack(spacing: 0) {
            if let footer, let first = card.firstPlayedAt {
                HStack(spacing: MemoryDiaryTokens.statGap) {
                    stat("\(footer.plays)", footer.plays == 1 ? String(localized: "play") : String(localized: "plays"))
                    stat("\(footer.days)", footer.days == 1 ? String(localized: "day since \(MemoryDiaryFormat.day(first))")
                                                            : String(localized: "days since \(MemoryDiaryFormat.day(first))"))
                }
            }
            if showGo {
                Button(action: onOpenAlbum) {
                    Text("Go to album")
                        .font(.gsf(15, .semibold))
                        .foregroundStyle(tones.onButton)
                        .padding(.horizontal, 24)
                        .frame(height: 48)
                        .background(tones.button, in: .capsule)
                }
                .buttonStyle(PressScale())
                .disabled(!interactive)
                .padding(.top, footer != nil ? MemoryDiaryTokens.goTop : 0)
            }
            DiaryNeoDBLine(album: card.album, scheme: scheme, interactive: interactive, onSignIn: onOpenNeoDBSettings)
                .padding(.top, 8)
        }
        .frame(maxWidth: .infinity)
    }

    private func stat(_ numeral: String, _ caption: String) -> some View {
        VStack(spacing: 2) {
            Text(numeral).font(.gsf(28, .medium)).monospacedDigit().foregroundStyle(scheme.onSurface)
            Text(caption).font(.gsf(12, .medium)).foregroundStyle(scheme.onSurfaceVariant).lineLimit(1)
        }
        .accessibilityElement(children: .combine)
    }
}

/// A liner row's press ground (no ripple on iOS: a quiet tonal wash).
private struct DiaryRowStyle: ButtonStyle {
    var scheme: CoverScheme
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(scheme.onSurface.opacity(configuration.isPressed ? 0.06 : 0), in: .rect(cornerRadius: 12, style: .continuous))
            .animation(Yoin.effects, value: configuration.isPressed)
    }
}

/// NeoDB's state for the album, one quiet line (Android DiaryNeoDbLine). Sync
/// is automatic (a saved review goes on its own), so it mostly reports:
/// "Synced to NeoDB". A waiting change is "Sync to NeoDB" (tappable), a failed
/// push the status and Retry. Signed out, "Sign in to NeoDB" opens Settings
/// (Android hides the line there and offers sign-in on a push instead). The
/// 48 pt slot is kept, so the diary's end never jumps as it reads.
struct DiaryNeoDBLine: View {
    var album: Album
    var scheme: CoverScheme
    var interactive: Bool
    var onSignIn: () -> Void

    private var state: AlbumNeoDBState { AlbumNeoDBSync.shared.state(album) }

    var body: some View {
        let s = state
        ZStack {
            switch s {
            case .failed:
                HStack(spacing: 4) {
                    Text("Couldn't sync to NeoDB").foregroundStyle(scheme.onSurfaceVariant)
                    Button("Retry") { Task { await AlbumNeoDBSync.shared.retry(album) } }
                        .foregroundStyle(scheme.primary)
                        .padding(.horizontal, 12)
                        .frame(minHeight: 48)
                        .contentShape(.rect)
                }
            case .pending:
                Button("Sync to NeoDB") { Task { await AlbumNeoDBSync.shared.retry(album) } }
                    .foregroundStyle(scheme.primary)
            case .signedOut:
                Button("Sign in to NeoDB", action: onSignIn)
                    .foregroundStyle(scheme.primary)
            case .syncing:
                Text("Syncing to NeoDB…").foregroundStyle(scheme.onSurfaceVariant)
            case .synced:
                Text("Synced to NeoDB").foregroundStyle(scheme.onSurfaceVariant)
            case .unknown, .idle:
                Color.clear
            }
        }
        .font(.gsf(13, .medium))
        .buttonStyle(.plain)
        .disabled(!interactive)
        .padding(.horizontal, 16)
        .frame(height: 48)
        .contentShape(.rect)
        .animation(Yoin.effects, value: s)
        .onAppear { AlbumNeoDBSync.shared.sheetOpened(album) }
    }
}
