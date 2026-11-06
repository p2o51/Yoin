import SwiftUI

// One memory's page in the two states (Android MemoriesShowcase's page,
// MemoryCardFace, MemoryPageMorph): the card face and the diary under it,
// both laid out complete; p only moves and fades their pieces. Anchors are
// measured at rest (outside each piece's own transform) in the page's
// coordinate space; every per-frame read happens in a layer modifier, so a
// frame of p re-renders the layers, not the page.

/// The deck's shared parts for one Memories open: the two controllers and
/// the router, the award lifecycle and its emblems, the title editor, the
/// drafts, and each page's morph.
@MainActor
final class MemoriesDeckModel {
    let presentation: MemoriesPresentation
    let diary = MemoriesDiary()
    let router: MemoriesGestureRouter
    private(set) var awards = MemoriesAwardLifecycle()
    let editor = MemoryTitleEditor()
    let drafts = MemoryDiaryDrafts()
    private var morphs: [String: MemoryPageMorph] = [:]
    private var cardAwards: [String: EmblemAwardTarget] = [:]
    private var diaryAwards: [String: DiaryEmblemTarget] = [:]
    /// The card emblem's size the awards were built for.
    private(set) var sealSize: CGFloat = 0

    init(presentation: MemoriesPresentation) {
        self.presentation = presentation
        router = MemoriesGestureRouter(reveal: presentation, diary: diary)
        router.awards = awards
        router.titleEditing = { [editor] in editor.isEditing }
    }

    func morph(_ card: MemoryCard) -> MemoryPageMorph {
        if let m = morphs[card.id] { return m }
        let m = MemoryPageMorph()
        morphs[card.id] = m
        return m
    }

    /// A new deck (an open, a rebuild): a new award lifecycle, every card waiting.
    func rebuild(_ cards: [MemoryCard], seal: CGFloat) {
        awards = MemoriesAwardLifecycle()
        router.awards = awards
        cardAwards = [:]
        diaryAwards = [:]
        sealSize = seal
        for c in cards { register(c) }
    }

    /// One card's data changed (a write): its emblems follow the new score.
    func refresh(_ card: MemoryCard) {
        if let a = cardAwards[card.id]?.award,
           a.drives(score: card.score ?? 0, kind: card.emblemKind, rated: card.rated, size: sealSize) { return }
        register(card)
    }

    private func register(_ c: MemoryCard) {
        let card = EmblemAwardTarget(ScoreEmblemAward(score: c.score ?? 0, kind: c.emblemKind, rated: c.rated, size: sealSize))
        let dem = DiaryEmblemTarget(ScoreEmblemAward(score: c.score ?? 0, kind: c.emblemKind, rated: c.rated,
                                                     size: MemoryDiaryTokens.emblem, haptics: false))
        cardAwards[c.id] = card
        diaryAwards[c.id] = dem
        awards.register(c.id, card)
        awards.registerDiary(c.id, dem)
    }

    func cardAward(_ c: MemoryCard) -> ScoreEmblemAward? { cardAwards[c.id]?.award }
    func diaryEmblem(_ c: MemoryCard) -> DiaryEmblemTarget? { diaryAwards[c.id] }

    // MARK: p's buttons

    /// The Diary button: the award on this card stops (and counts); NeoDB's state is read.
    func openDiary(_ card: MemoryCard) {
        MemoriesHaptics.click()
        editor.end()
        awards.onDiaryOpening(card.id)
        AlbumNeoDBSync.shared.sheetOpened(card.album)
        diary.animate(to: 1)
    }

    /// The bar cover, slot B, ⌄ and back at the diary level.
    func closeDiary() {
        editor.end()
        diary.animate(to: 0)
    }
}

/// One page's anchors (page pt, at rest) and its diary scroll.
@MainActor
@Observable
final class MemoryPageMorph {
    nonisolated static let space = "memoryPage"

    var cover: CGRect = .zero
    var seal: CGRect = .zero
    var cardTitle: CGRect = .zero
    var cardTitleFont: CGFloat = 26
    /// The diary title at scroll 0 (nil: fallback A, the album name flies to the bar).
    var diaryTitle: CGRect?
    var diaryTitleFont: CGFloat = 22
    var dem: CGRect = .zero
    /// The top of the diary's first line at scroll 0 (the blocks' lag reads it).
    var diaryTop: CGFloat = 0
    /// The diary's scroll (0 = top).
    var scrollY: CGFloat = 0
    /// A frozen collapse keeps its timing until p reaches 0 or 1.
    var frozenHold = false

    /// The bar cover's centre and its slot B title (page pt).
    var barCover: CGPoint = .zero
    var barTitle: CGRect = .zero

    /// The diary title has scrolled under the bar (Android `so ≥ thr`).
    func frozen(barHeight: CGFloat) -> Bool {
        if frozenHold { return true }
        let top = diaryTitle?.minY ?? diaryTop
        return scrollY > 0 && scrollY >= top - barHeight
    }

    var lag: CGFloat {
        MemoriesMorph.blockLag(cardTitleTop: cardTitle.minY, diaryTop: diaryTitle?.minY ?? diaryTop,
                               cardTitleHeight: diaryTitle == nil ? cardTitle.height : nil)
    }
}

/// A piece's transform from p, read in its own layer.
struct MorphLayer: ViewModifier {
    var diary: MemoriesDiary
    var anchor: UnitPoint = .center
    var pose: (CGFloat) -> MorphPose

    func body(content: Content) -> some View {
        let s = pose(diary.fraction)
        content
            .scaleEffect(s.scale, anchor: anchor)
            .offset(x: s.dx, y: s.dy)
            .opacity(Double(min(max(s.alpha, 0), 1)))
    }
}

extension View {
    func morphLayer(_ diary: MemoriesDiary, anchor: UnitPoint = .center, _ pose: @escaping (CGFloat) -> MorphPose) -> some View {
        modifier(MorphLayer(diary: diary, anchor: anchor, pose: pose))
    }

    /// This view's rest frame in the page (call it after the view's own layer).
    func pageFrame(_ apply: @escaping (CGRect) -> Void) -> some View {
        onGeometryChange(for: CGRect.self) { $0.frame(in: .named(MemoryPageMorph.space)) } action: { apply($0) }
    }
}

/// The card's numbers per tier (Android MemoryCardMetrics), pt.
struct MemoryCardMetrics {
    var cover: CGFloat
    var seal: CGFloat
    var air1: CGFloat
    var short: Bool
    /// Medium: the card's column; phone: the page.
    var column: CGFloat?

    init(layout l: MemoriesLayout) {
        cover = l.cover
        seal = l.seal
        air1 = l.air1
        short = l.short
        column = l.tier == .medium ? MemoriesLayoutTokens.mediumCardColumn : nil
    }
}

// MARK: - The card

/// The card state of one memory (Android MemoryCardFace): the exhibit — air,
/// the cover with the groove emblem on its lower-right corner, the title, the
/// album row — and the teaser at the bottom: the excerpt, then
/// [Diary | Go to album]. Flat, no cover border. Each piece rides p in its own layer.
struct MemoryCardFace: View {
    var card: MemoryCard
    var deck: MemoriesDeckModel
    var morph: MemoryPageMorph
    var metrics: MemoryCardMetrics
    var scheme: CoverScheme
    var dark: Bool
    var interactive: Bool
    var onOpenAlbum: () -> Void
    var onTitle: (MemoryTitles.Outcome) -> Void

    private var diary: MemoriesDiary { deck.diary }
    private var barHeight: CGFloat { MemoriesLayoutTokens.barHeight }
    static func titleFont(_ kind: MemoryCard.TitleKind) -> CGFloat { kind == .album ? 24 : 26 }

    var body: some View {
        let tones = MemoryTones(palette: card.album.palette, dark: dark)
        VStack(spacing: 0) {
            Color.clear.frame(height: barHeight + metrics.air1)
            exhibit(tones)
            Spacer(minLength: 24)
            teaser(tones)
                .morphLayer(diary) { p in diary.reduced ? MorphPose() : MemoriesMorph.teaser(p) }
                .padding(.bottom, metrics.short ? 28 : 40)
        }
        .padding(.horizontal, 28)
        .frame(maxWidth: metrics.column ?? .infinity)
        .frame(maxWidth: .infinity)
        // The card's own pull-down: the whole card rides p below 0 (its rubber band).
        .morphLayer(diary) { p in
            var s = MorphPose(dy: p < 0 ? -p * MemoriesBackTokens.diaryMorphDistance : 0)
            if diary.reduced { s.alpha = MemoriesMorph.reducedCardAlpha(p) }
            return s
        }
        .allowsHitTesting(interactive)
        .accessibilityHidden(!interactive)
    }

    private func frozen() -> Bool { morph.frozen(barHeight: barHeight) }

    private func exhibit(_ tones: MemoryTones) -> some View {
        let cover = metrics.cover, seal = metrics.seal
        let onCover = CoverMarks.onCover(provider: card.album.id.provider)
        return VStack(spacing: 0) {
            CoverArt(palette: card.album.palette, cornerRadius: 0, seed: card.album.seed)
                .frame(width: cover, height: cover)
                .modifier(CoverMorph(diary: diary, morph: morph, cover: cover, barHeight: barHeight))
                .pageFrame { r in if abs(diary.fraction) < 0.001 { morph.cover = r } }
                .overlay(alignment: .topLeading) {
                    // The emblem hangs off the lower-right corner by 0.3 / 0.24 of its size.
                    ScoreEmblem(score: card.score ?? 0, kind: card.emblemKind, rated: card.rated,
                                palette: card.album.palette, size: seal, award: deck.cardAward(card),
                                neutrals: scheme, onCover: onCover)
                        .morphLayer(diary) { p in sealPose(p) }
                        .pageFrame { r in if abs(diary.fraction) < 0.001 { morph.seal = r } }
                        // A Spotify cover: the stamp under its right edge, clear of it (CoverMarks).
                        .padding(.leading, onCover ? cover - seal * 0.7 : cover - seal)
                        .padding(.top, onCover ? cover - seal * 0.76 : cover + CoverMarks.gap)
                }
                .padding(.bottom, onCover ? seal * 0.24 : seal + CoverMarks.gap)
            title(tones)
                .padding(.top, metrics.short ? 22 : 30)
            albumRow(tones)
                .morphLayer(diary) { p in diary.reduced ? MorphPose() : MemoriesMorph.albumRow(p) }
                .padding(.top, card.titleKind == .album ? 6 : (metrics.short ? 16 : 20))
                .frame(maxWidth: 340)
            MemoryTitleEditRow(card: card, surface: .card, editor: deck.editor, scheme: scheme, tones: tones,
                               centred: true, onCommit: onTitle)
                .frame(maxWidth: 340)
        }
    }

    private func sealPose(_ p: CGFloat) -> MorphPose {
        if diary.reduced { return MorphPose() }
        if frozen() { return MorphPose(alpha: smoothstep(MemoriesMorphTokens.frozenCardFadeFrom, MemoriesMorphTokens.frozenCardFadeTo, 1 - p) ) }
        guard morph.dem != .zero, morph.seal != .zero else { return MorphPose(alpha: 1 - smoothstep(0, 0.4, p)) }
        let te = MemoriesMorph.titleTravel(p, frozen: false)
        return MemoriesMorph.emblemPose(te: te, sealCenter: center(morph.seal), demCenter: center(morph.dem),
                                        seal: morph.seal.width, dem: morph.dem.width).seal
    }

    private func titlePose(_ p: CGFloat) -> MorphPose {
        if diary.reduced { return MorphPose() }
        if frozen() { return MorphPose(alpha: smoothstep(MemoriesMorphTokens.frozenCardFadeFrom, MemoriesMorphTokens.frozenCardFadeTo, 1 - p)) }
        let t = MemoriesMorph.titleTravel(p, frozen: false)
        let from = TextAnchor(cx: morph.cardTitle.midX, y: morph.cardTitle.minY, font: morph.cardTitleFont)
        if let d = morph.diaryTitle {
            return MemoriesMorph.textPair(card: from, diary: TextAnchor(cx: d.midX, y: d.minY, font: morph.diaryTitleFont), t: t).card
        }
        // Fallback A: the album name flies to the bar's album name and is gone by .72.
        let bar = TextAnchor(cx: morph.barTitle.midX, y: morph.barTitle.minY, font: 15)
        var s = MemoriesMorph.textPair(card: from, diary: bar, t: t).card
        s.alpha = 1 - smoothstep(MemoriesMorphTokens.albumTitleFadeFrom, MemoriesMorphTokens.albumTitleFadeTo, t)
        return s
    }

    private func title(_ tones: MemoryTones) -> some View {
        let album = card.titleKind == .album
        let size = Self.titleFont(card.titleKind)
        let font: Font = album ? .gsf(size, .semibold) : .system(size: size, weight: .semibold, design: .serif)
        let spacing = album ? size * 0.3 - 6 : size * 0.36 - 7
        return MemoryTitleSlot(card: card, surface: .card, editor: deck.editor, scheme: scheme, tones: tones, font: font,
                               lineSpacing: spacing, centred: true, enabled: interactive, onCommit: onTitle) {
            Text(card.title)
                .font(font)
                .lineSpacing(spacing)
                .multilineTextAlignment(.center)
                .lineLimit(album ? 2 : 3)
                .foregroundStyle(scheme.onSurface)
                .accessibilityAddTraits(.isHeader)
        }
        .frame(maxWidth: 340)
        .morphLayer(diary, anchor: .top) { p in titlePose(p) }
        .pageFrame { r in
            guard abs(diary.fraction) < 0.001 else { return }
            morph.cardTitle = r
            morph.cardTitleFont = size
        }
    }

    private func albumRow(_ tones: MemoryTones) -> some View {
        VStack(spacing: 2) {
            if card.titleKind != .album {
                Text(card.album.title)
                    .font(.gsf(17, .semibold))
                    .foregroundStyle(tones.ink)
                    .lineLimit(2)
                    .multilineTextAlignment(.center)
            }
            HStack(spacing: Tokens.metaLine.GROUP_GAP.cg) {
                if !card.album.artist.isEmpty { Text(card.album.artist) }
                if card.album.year > 0 { Text(String(card.album.year)).monospacedDigit() }
            }
            .font(.gsf(13, .medium))
            .foregroundStyle(scheme.onSurfaceVariant)
            .lineLimit(1)
        }
    }

    private func teaser(_ tones: MemoryTones) -> some View {
        VStack(spacing: 16) {
            if let e = card.excerpt { excerpt(e, tones) }
            HStack(spacing: 10) {
                Button { deck.openDiary(card) } label: {
                    HStack(spacing: 8) {
                        YoinIcon("note", size: 18)
                        Text("Diary").font(.gsf(15, .semibold))
                        if card.noteCount > 0 {
                            Text("\(card.noteCount)")
                                .font(.gsf(12, .medium)).monospacedDigit()
                                .opacity(0.78)
                        }
                    }
                    .foregroundStyle(tones.ink)
                    .padding(.leading, 16).padding(.trailing, 20)
                    .frame(height: 48)
                    .background(tones.ink.opacity(0.14), in: .capsule)
                    .contentShape(.capsule)
                }
                .buttonStyle(PressScale())
                .accessibilityLabel(card.noteCount > 0 ? String(localized: "Diary, \(card.noteCount) notes") : String(localized: "Diary"))
                Button(action: onOpenAlbum) {
                    Text("Go to album")
                        .font(.gsf(15, .semibold))
                        .foregroundStyle(tones.onButton)
                        .padding(.horizontal, 24)
                        .frame(height: 48)
                        .background(tones.button, in: .capsule)
                }
                .buttonStyle(PressScale())
            }
        }
    }

    private func excerpt(_ e: MemoryCard.Excerpt, _ tones: MemoryTones) -> some View {
        VStack(spacing: 6) {
            Text("“\(e.text)”")
                .font(.gsf(e.text.count < 60 ? 22 : 17, e.text.count < 60 ? .medium : .regular))
                .lineSpacing(e.text.count < 60 ? 22 * 0.4 - 6 : 17 * 0.65 - 5)
                .multilineTextAlignment(.center)
                .foregroundStyle(scheme.onSurface)
                .lineLimit(4)
                .frame(maxWidth: 324)
            HStack(spacing: 8) {
                Text(e.kind == .review ? String(localized: "Your review") : String(localized: "Your note"))
                    .homeType(Tokens.type.labelSmall)
                    .foregroundStyle(tones.ink)
                if let d = e.detail {
                    Text(d).font(.gsf(12, .medium)).monospacedDigit().foregroundStyle(scheme.onSurface.opacity(0.6))
                }
            }
            .lineLimit(1)
        }
    }
}

func center(_ r: CGRect) -> CGPoint { CGPoint(x: r.midX, y: r.midY) }

/// The flying cover: its pose and its corner (8 → 4 as shown), from p.
private struct CoverMorph: ViewModifier {
    var diary: MemoriesDiary
    var morph: MemoryPageMorph
    var cover: CGFloat
    var barHeight: CGFloat

    func body(content: Content) -> some View {
        let p = diary.fraction
        let s: MorphPose = {
            if diary.reduced { return MorphPose(corner: MemoriesMorphTokens.coverCornerCard) }
            let fp = MemoriesMorph.coverProgress(p, frozen: morph.frozen(barHeight: barHeight))
            guard morph.cover != .zero else { return MorphPose(alpha: 1 - fp, corner: MemoriesMorphTokens.coverCornerCard) }
            return MemoriesMorph.coverPose(fp: fp, coverCenter: center(morph.cover), barCenter: morph.barCover,
                                           cover: cover, barCover: MemoriesTopBar.barCover)
        }()
        content
            .clipShape(.rect(cornerRadius: s.corner, style: .continuous))
            .scaleEffect(s.scale)
            .offset(x: s.dx, y: s.dy)
            .opacity(Double(min(max(s.alpha, 0), 1)))
    }
}

// MARK: - The two-state page

/// One page: the card and, once p moves, the diary under it.
struct MemoryPageView: View {
    var card: MemoryCard
    var deck: MemoriesDeckModel
    var layout: MemoriesLayout
    var scheme: CoverScheme
    var dark: Bool
    /// This is the deck's current page.
    var current: Bool
    var onOpenAlbum: () -> Void
    var onTitle: (MemoryTitles.Outcome) -> Void
    var onReview: (String) -> Bool
    var onOpenNeoDBSettings: () -> Void
    var horizontalPager: AnyGesture<DragGesture.Value>

    @State private var warm = false
    /// Seconds after a page appears before its hidden diary is built.
    static let warmDelay: Double = 0.6

    private var diary: MemoriesDiary { deck.diary }

    var body: some View {
        let morph = deck.morph(card)
        ZStack(alignment: .top) {
            MemoryCardFace(card: card, deck: deck, morph: morph, metrics: MemoryCardMetrics(layout: layout),
                           scheme: scheme, dark: dark, interactive: current && !diary.isDiaryLevel,
                           onOpenAlbum: onOpenAlbum, onTitle: onTitle)
                .ignoresSafeArea(.keyboard)
            // The current page's diary is laid out under its card (hidden) once
            // Memories is in place: its anchors are measured before p first
            // moves, and the first open never pays for building it mid-flight.
            // Not while q moves (an open or a retreat must not pay for it either).
            if diary.isShown || (current && warm && deck.presentation.isOpen) {
                MemoryDiaryView(card: card, deck: deck, morph: morph, layout: layout, scheme: scheme, dark: dark,
                                interactive: current && (diary.isDiaryLevel || diary.isPulling), onOpenAlbum: onOpenAlbum,
                                onTitle: onTitle, onReview: onReview, onOpenNeoDBSettings: onOpenNeoDBSettings,
                                horizontalPager: horizontalPager)
            }
        }
        .coordinateSpace(.named(MemoryPageMorph.space))
        .task {
            // After the open's spring has done most of its travel.
            try? await Task.sleep(for: .seconds(MemoryPageView.warmDelay / LaunchConfig.animationSpeed))
            warm = true
        }
        .onAppear {
            morph.barCover = CGPoint(x: layout.barCoverStart + MemoriesTopBar.barCover / 2, y: layout.barHeight / 2)
        }
        .onChange(of: layout) { _, l in
            morph.barCover = CGPoint(x: l.barCoverStart + MemoriesTopBar.barCover / 2, y: l.barHeight / 2)
        }
    }
}
