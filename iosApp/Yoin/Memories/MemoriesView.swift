import SwiftUI
import UIKit

/// Memories, the shell-owned upward overlay (AGENTS.md ShellOverlayUp),
/// ported from Android ui/memories (MemoriesScreen, showcase/*).
///
/// Back surface: it retreats upward to Home. The page has two controllers,
/// never fighting: q (MemoriesPresentation, the retreat to Home: the drag up,
/// the ⌃ Home pill, the bar's Home tap, VoiceOver's escape) and p
/// (MemoriesDiary, card ⇄ diary: the Diary button, a pull past the diary's
/// top, the bar as its handle, the bar cover / ⌄, escape at the diary level).
/// One gesture router (MemoriesGestures) hands each drag to exactly one of
/// them at the slop; sideways turns the deck.
///
/// The container's own size picks the tier (MemoriesLayout): the two states
/// on a phone and a Medium column, the spread (exhibit | diary) when wide.
struct MemoriesView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var presentation: MemoriesPresentation

    @State private var deck: MemoriesDeckModel
    @State private var cards: [MemoryCard] = []
    /// The deck's continuous page (0 = first card).
    @State private var position: CGFloat = 0
    @State private var axis: MemoriesDragAxis?
    @State private var route: MemoriesVerticalRoute?
    @State private var pagerAxis: MemoriesDragAxis?
    @State private var dragStartPosition: CGFloat = 0
    @State private var pager = SpringRunner()
    @State private var layout = MemoriesLayouts.layout(width: 390, height: 844)
    @State private var size = CGSize(width: 390, height: 844)
    /// A plain box: it changes every frame of q, and nothing draws from it.
    @State private var surfaceTop = SurfaceTop()
    /// The open the deck was built for. A page pushed over Home (Go to album)
    /// hides Memories without closing it; coming back finds the same open,
    /// so the deck, its position, the diary and the drafts stay as they were.
    @State private var builtFor: Int?

    init(presentation: MemoriesPresentation) {
        self.presentation = presentation
        _deck = State(initialValue: MemoriesDeckModel(presentation: presentation))
    }

    private var scheme: CoverScheme { CoverScheme(seed: model.accent, dark: colorScheme == .dark) }
    private var dark: Bool { colorScheme == .dark }

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width
            ZStack(alignment: .top) {
                MemoriesAurora(palettes: cards.map(\.album.palette), position: position, dark: dark)
                    .background(scheme.background)
                    .ignoresSafeArea()
                if cards.isEmpty {
                    Text("No memories yet")
                        .homeType(Tokens.type.headlineSmall)
                        .foregroundStyle(scheme.onSurface)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    ForEach(visibleIndices, id: \.self) { i in
                        page(i, size: geo.size)
                            .frame(width: w)
                            // Neighbours slide past at the deck's pace; only ±1 is drawn.
                            .offset(x: (CGFloat(i) - position) * w)
                            .accessibilityHidden(i != currentIndex)
                    }
                }
                MemoriesTopBar(cards: cards, position: position, deck: deck, layout: layout, scheme: scheme, dark: dark,
                               width: w, onHome: home, onDot: { turn(to: $0, velocity: 0) })
                    .gesture(pageGesture(zone: .bar))
            }
            .coordinateSpace(.named(MemoryPageMorph.space))
            .contentShape(.rect)
            // The page's own drag: the card body (and the bar band). In the
            // diary the text's scroll has the body; in a spread its left page.
            .gesture(pageGesture(zone: nil), including: layout.twoState && !deck.diary.isDiaryLevel ? .all : .subviews)
            .background(MemoriesAwardDriver(deck: deck, presentation: presentation, keys: cards.map(\.id),
                                            position: position, spread: layout.isSpread))
            // The container's width (adaptive principle 1) and the window's
            // height, system bars included: Memories always runs the window's
            // full height, and the safe area settles late while the page rides
            // in, so the height is read from the window, not the moving frame.
            // Both dimensions are triggers: a height-only resize (an iPad window) must re-lay out too.
            .onGeometryChange(for: CGSize.self) { $0.size } action: { s in
                resize(CGSize(width: s.width, height: Self.windowHeight))
            }
            // Where this surface's top sits in the window: the pose moves it (q), and the bar band is
            // judged in its own frame while the finger is measured in the window's (see pageGesture).
            .onGeometryChange(for: CGFloat.self) { $0.frame(in: .global).minY } action: { surfaceTop.y = $0 }
        }
        .ignoresSafeArea(.keyboard)
        .onAppear {
            resize(CGSize(width: size.width, height: Self.windowHeight))
            // Back from a page pushed over it: the same deck, its cards' data refreshed.
            if builtFor == presentation.openCount { refreshInPlace() } else { rebuild() }
            #if DEBUG
            MemoriesScript.swipe = { _ in
                let start = position
                Task { @MainActor in
                    await MemoriesScript.finger(0.3) { position = start + $0 * 0.4 }
                    turn(to: MemoryDeck.settlePage(position: position, velocity: 1.5, count: cards.count), velocity: 1.5)
                }
            }
            MemoriesScript.deck = deck
            MemoriesScript.current = { cards.indices.contains(currentIndex) ? cards[currentIndex] : nil }
            MemoriesScript.title = { title($0, $1) }
            MemoriesScript.review = { review($0, $1) }
            MemoriesScript.back = { back() }
            // iPad: Home full width (no album column), for the spread's recordings.
            if UserDefaults.standard.bool(forKey: "memoriesNoColumn"), model.padSelection != nil { model.padSelection = nil }
            #endif
        }
        .onChange(of: presentation.openCount) { _, _ in rebuild() }
        // The albums its memories need and the app had no track list for
        // (after a relaunch, a sync): loaded behind the open deck.
        .task(id: presentation.openCount) {
            let open = presentation.openCount
            guard await model.store.hydrateMemoryAlbums(), open == presentation.openCount else { return }
            withAnimation(Yoin.effects) { refreshInPlace() }
        }
        // Titles and narration for the cards not yet tried in this language (MemoryGeneration: once per
        // album and language, never again on the next open); each answer lands on its card in place.
        .task(id: [String(presentation.openCount), GeminiSettings.shared.targetLanguage,
                   GeminiSettings.shared.hasKey ? "key" : ""]) {
            let open = presentation.openCount
            await model.generateMemoryText(in: { cards.map(\.id) }) {
                guard open == presentation.openCount else { return }
                withAnimation(Yoin.effects) { refreshInPlace() }
            }
        }
        .onChange(of: reduceMotion, initial: true) { _, r in deck.diary.reduced = r }
        // VoiceOver's two-finger scrub is iOS's back for an overlay: one level at a time.
        .accessibilityAction(.escape) { back() }
        .accessibilityAddTraits(.isModal)
    }

    @ViewBuilder
    private func page(_ i: Int, size: CGSize) -> some View {
        let card = cards[i]
        if layout.isSpread {
            let cover = MemoriesLayouts.spreadCover(layout, room: self.size.height - layout.barHeight - 120)
            MemoriesSpreadPage(card: card, deck: deck, layout: layout, cover: cover, seal: deck.sealSize, size: size,
                               scheme: scheme, dark: dark, current: i == currentIndex,
                               onOpenAlbum: { open(card) }, onTitle: { title(card, $0) },
                               onReview: { review(card, $0) },
                               leftGesture: pageGesture(zone: .card), horizontalPager: horizontalPager())
        } else {
            MemoryPageView(card: card, deck: deck, layout: layout, scheme: scheme, dark: dark, current: i == currentIndex,
                           onOpenAlbum: { open(card) }, onTitle: { title(card, $0) },
                           onReview: { review(card, $0) },
                           horizontalPager: horizontalPager())
        }
    }

    private var currentIndex: Int { min(max(Int(position.rounded()), 0), max(cards.count - 1, 0)) }

    static var windowHeight: CGFloat {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        return scenes.first { $0.activationState == .foregroundActive }?.keyWindow?.bounds.height
            ?? scenes.first?.keyWindow?.bounds.height ?? 844
    }

    /// The window's bottom inset (the home indicator): the card's buttons keep at least this
    /// much air from the screen's edge.
    static var windowSafeBottom: CGFloat {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        return (scenes.first { $0.activationState == .foregroundActive } ?? scenes.first)?.keyWindow?.safeAreaInsets.bottom ?? 0
    }

    private var visibleIndices: [Int] {
        guard !cards.isEmpty else { return [] }
        let lo = max(0, Int(position.rounded(.down)) - 1), hi = min(cards.count - 1, Int(position.rounded(.up)) + 1)
        return lo <= hi ? Array(lo...hi) : []
    }

    // MARK: The deck

    private func resize(_ s: CGSize) {
        size = s
        let l = MemoriesLayouts.layout(width: s.width, height: s.height)
        guard l != layout else { return }
        let sealChanged = seal(for: l) != deck.sealSize
        layout = l
        deck.router.isSpread = l.isSpread
        // A spread has no diary level: p goes back to the card unseen.
        if l.isSpread { deck.diary.snap(to: 0) }
        if sealChanged, !cards.isEmpty { deck.rebuild(cards, seal: seal(for: l)) }
    }

    private func seal(for l: MemoriesLayout) -> CGFloat {
        guard l.isSpread else { return l.seal }
        return MemoriesLayouts.seal(for: l, cover: MemoriesLayouts.spreadCover(l, room: size.height - l.barHeight - 120))
    }

    /// The deck for this open: the pool rebuilt every time, stopped on the focus, every card on its card face.
    private func rebuild() {
        builtFor = presentation.openCount
        let inputs = model.store.memoryDeckInputs()
        cards = model.voicedMemoryCards(MemoryDeck.deck(inputs, focus: presentation.focusAlbumId), inputs)
        pager.stop()
        position = CGFloat(MemoryDeck.startIndex(cards, focus: presentation.focusAlbumId))
        deck.diary.snap(to: 0)
        deck.editor.end()
        deck.router.cardPresent = !cards.isEmpty
        deck.router.isSpread = layout.isSpread
        deck.rebuild(cards, seal: seal(for: layout))
    }

    /// Every card again from the stores, in place (the deck's order holds
    /// while it is open); memories that now qualify join at the end. An
    /// empty deck is built afresh.
    private func refreshInPlace() {
        guard !cards.isEmpty else { return rebuild() }
        let inputs = model.store.memoryDeckInputs()
        let latest = Dictionary(inputs.memories.map { ($0.albumId, $0) }, uniquingKeysWith: { a, _ in a })
        var next = cards.map { MemoryDeck.card(latest[$0.id] ?? $0.memory, inputs) }
        let have = Set(next.map(\.id))
        next += MemoryDeck.deck(inputs, focus: nil).filter { !have.contains($0.id) }.prefix(max(0, MemoryDeck.limit - next.count))
        next = model.voicedMemoryCards(next, inputs)
        guard next != cards else { return }
        cards = next
        for c in next { deck.refresh(c) }
    }

    /// One card again after a write, in place (the deck's order holds while it is open).
    private func refresh(_ card: MemoryCard) {
        guard let i = cards.firstIndex(where: { $0.id == card.id }) else { return }
        let inputs = model.store.memoryDeckInputs()
        let fresh = model.voicedMemoryCards([MemoryDeck.card(card.memory, inputs)], inputs)[0]
        cards[i] = fresh
        deck.refresh(fresh)
    }

    private func turn(to page: Int, velocity: CGFloat) {
        let key = cards.indices.contains(page) ? cards[page].id : nil
        pager.run(from: position, to: CGFloat(page), velocity: velocity, spring: .yoin(Tokens.motion.expressiveDefaultSpatial)) { v in
            position = v
        } completion: { [deck] in
            deck.awards.onPagerSettled(key)
        }
    }

    // MARK: Actions

    private func open(_ card: MemoryCard) { model.open(card.album) }

    private func title(_ card: MemoryCard, _ outcome: MemoryTitles.Outcome) {
        switch outcome {
        case .unchanged: return
        case .restore: MemoryWrites.saveTitle(card, nil)
        case .save(let t): MemoryWrites.saveTitle(card, t)
        }
        withAnimation(Yoin.effects) { refresh(card) }
    }

    private func review(_ card: MemoryCard, _ text: String) -> Bool {
        guard MemoryWrites.saveReview(card, text: text) else {
            PlaylistActions.shared.show(String(localized: "Couldn't save the review. Your draft is kept."))
            return false
        }
        withAnimation(Yoin.spatial) { refresh(card) }
        return true
    }

    /// NeoDB signs in in Settings: Memories retreats, Settings opens.
    private func home() {
        MemoriesHaptics.click()
        deck.awards.onDismissCommitted()
        presentation.close()
    }

    /// Back, one level at a time: a title being edited, then the diary, then Home.
    private func back() {
        if deck.editor.isEditing { return deck.editor.end() }
        if layout.twoState && deck.diary.isDiaryLevel { return deck.closeDiary() }
        home()
    }

    // MARK: Gestures

    /// The page's one drag: its first movement picks the axis; a vertical
    /// one goes to the router, which picks q or p. [zone] nil = by where it started.
    private func pageGesture(zone fixed: MemoriesDragZone?) -> AnyGesture<DragGesture.Value> {
        // The window's space, not this surface's: q moves it, so a finger measured in its own space
        // read a motion that moved the surface that changed the motion (tracking and the release velocity).
        AnyGesture(DragGesture(minimumDistance: MemoriesBackTokens.touchSlop, coordinateSpace: .global)
            .onChanged { v in
                let router = deck.router
                if axis == nil {
                    router.press()
                    let a = MemoriesRouting.axis(dx: v.translation.width, dy: v.translation.height, slop: 0) ?? .vertical
                    if a == .horizontal && !deck.editor.isEditing && !cards.isEmpty {
                        axis = .horizontal
                        beginPaging()
                    } else {
                        axis = .vertical
                        let zone = fixed ?? (v.startLocation.y - surfaceTop.y < layout.barHeight ? .bar : .card)
                        route = router.begin(zone: zone, translationY: v.translation.height)
                    }
                }
                switch axis {
                case .horizontal: page(v)
                case .vertical: if let route { router.drag(route, translationY: v.translation.height) }
                case nil: break
                }
            }
            .onEnded { v in
                defer { axis = nil; route = nil }
                deck.router.lift()
                switch axis {
                case .horizontal: settlePaging(v)
                case .vertical: if let route { deck.router.release(route, velocityY: v.velocity.height) }
                case nil: break
                }
            })
    }

    /// Sideways over a scroll (the diary, the spread's right page): only a horizontal drag pages.
    private func horizontalPager() -> AnyGesture<DragGesture.Value> {
        AnyGesture(DragGesture(minimumDistance: MemoriesBackTokens.touchSlop, coordinateSpace: .global)
            .onChanged { v in
                if pagerAxis == nil {
                    let a = MemoriesRouting.axis(dx: v.translation.width, dy: v.translation.height, slop: 0) ?? .vertical
                    pagerAxis = a == .horizontal && !deck.editor.isEditing ? .horizontal : .vertical
                    if pagerAxis == .horizontal {
                        deck.router.press()
                        beginPaging()
                    }
                }
                if pagerAxis == .horizontal { page(v) }
            }
            .onEnded { v in
                defer { pagerAxis = nil }
                guard pagerAxis == .horizontal else { return }
                deck.router.lift()
                settlePaging(v)
            })
    }

    private func beginPaging() {
        deck.router.beginHorizontal()
        dragStartPosition = position
        pager.stop()
    }

    /// 1:1, soft past either end.
    private func page(_ v: DragGesture.Value) {
        let raw = dragStartPosition - v.translation.width / max(size.width, 1)
        let last = CGFloat(max(cards.count - 1, 0))
        position = raw < 0 ? -rubber(-raw) : raw > last ? last + rubber(raw - last) : raw
    }

    private func settlePaging(_ v: DragGesture.Value) {
        let velocity = -v.velocity.width / max(size.width, 1)
        turn(to: MemoryDeck.settlePage(position: position, velocity: velocity, count: cards.count), velocity: velocity)
    }

    private func rubber(_ over: CGFloat) -> CGFloat {
        MemoriesBackTokens.diaryRubberBand * (1 - 1 / (1 + over / MemoriesBackTokens.diaryRubberBand))
    }
}

/// Drives the award lifecycle from the deck (Android MemoriesAwardEffects):
/// q, p and the pager read here, in a view of their own, so their frames
/// re-render only this. A change of inputs replaces a pending lift delay.
private struct MemoriesAwardDriver: View {
    var deck: MemoriesDeckModel
    var presentation: MemoriesPresentation
    var keys: [String]
    var position: CGFloat
    var spread: Bool
    @State private var pending: Task<Void, Never>?

    var body: some View {
        let diary = deck.diary
        let i = min(max(Int(position.rounded()), 0), max(keys.count - 1, 0))
        let inputs = MemoriesAwardInputs(
            cardKey: keys.indices.contains(i) ? keys[i] : nil,
            fingerDown: deck.awards.fingerDown,
            nearCard: MemoriesAwardInputs.near(position: position, card: i),
            revealIn: MemoriesAwardInputs.revealIn(presentation.fraction),
            cardState: spread || MemoriesAwardInputs.cardState(diary.fraction),
            diaryState: !spread && MemoriesAwardInputs.diaryState(diary.fraction, settling: diary.isSettling))
        Color.clear
            .onChange(of: inputs, initial: true) { _, now in
                pending?.cancel()
                let awards = deck.awards
                guard let wait = awards.inputsChanged(now) else { return }
                pending = Task { @MainActor in
                    if wait > 0 { try? await Task.sleep(for: .milliseconds(wait)) }
                    guard !Task.isCancelled else { return }
                    awards.start(now)
                }
            }
    }
}

// MARK: - Tones (Android MemoryPalette)

/// One album's card tones (Android `MemoryPalette.fromBackdrop(...).tones(dark)`):
/// a direct sRGB lerp of the backdrop base, held in the ink lightness window.
struct MemoryTones {
    var ink: Color
    var button: Color
    var onButton: Color
    var dot: Color

    init(palette: CoverPalette, dark: Bool) {
        let base = Self.hold(UIColor(BackdropColors(palette).base), min: 0.22, max: 0.46)
        let deep = Self.mix(base, .black, 0.55), soft = Self.mix(base, .white, 0.8)
        if dark {
            let lift = Self.mix(base, soft, 0.6)
            ink = Color(lift); button = Color(lift); onButton = Color(deep); dot = Color(lift)
        } else {
            ink = Color(base); button = Color(base); onButton = .white; dot = Color(base)
        }
    }

    static func rgb(_ c: UIColor) -> (CGFloat, CGFloat, CGFloat) {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        c.getRed(&r, green: &g, blue: &b, alpha: &a)
        return (r, g, b)
    }

    static func mix(_ a: UIColor, _ b: UIColor, _ t: CGFloat) -> UIColor {
        let x = rgb(a), y = rgb(b)
        return UIColor(red: x.0 + (y.0 - x.0) * t, green: x.1 + (y.1 - x.1) * t, blue: x.2 + (y.2 - x.2) * t, alpha: 1)
    }

    /// `holdLightness`: moved straight toward black or white into [min, max] (HSL L).
    static func hold(_ c: UIColor, min lo: CGFloat, max hi: CGFloat) -> UIColor {
        let (r, g, b) = rgb(c)
        let l = (Swift.max(r, g, b) + Swift.min(r, g, b)) / 2
        if l > hi { return mix(c, .black, 1 - hi / l) }
        if l < lo { return mix(c, .white, (lo - l) / (1 - l)) }
        return c
    }
}

// MARK: - Aurora

/// Android memoriesAuroraBackground: three soft radial blooms in the current
/// memory's palette, turned by the deck's position (not the clock): a swipe
/// re-tints and moves the field, a deck at rest draws nothing new.
private struct MemoriesAurora: View {
    var palettes: [CoverPalette]
    var position: CGFloat
    var dark: Bool

    var body: some View {
        Canvas { gc, size in
            guard !palettes.isEmpty, size.width > 0 else { return }
            let at = min(max(position, 0), CGFloat(palettes.count - 1))
            let from = Int(at.rounded(.down)), to = min(from + 1, palettes.count - 1)
            let f = at - CGFloat(from)
            let a = BackdropColors(palettes[from]), b = BackdropColors(palettes[to])
            let base = a.base.mix(with: b.base, by: f), accent = a.accent.mix(with: b.accent, by: f)
            let blooms = [base, accent, base.mix(with: accent, by: 0.5)]
            let w = size.width, h = size.height, maxDim = max(w, h)
            let cap: CGFloat = dark ? 0.26 : 0.2
            let flow = position * 0.18
            let breath: CGFloat = 0.5 + 0.5 * CGFloat(sin(Double(position) * 1.9))
            let anchors = [CGPoint(x: w * 0.24, y: h * 0.22), CGPoint(x: w * 0.78, y: h * 0.44), CGPoint(x: w * 0.34, y: h * 0.80)]
            if dark { gc.blendMode = .screen }
            for i in 0..<3 {
                let angle = 2 * .pi * flow * (0.6 + 0.4 * CGFloat(i)) + CGFloat(i) * 2 * .pi / 3
                let center = CGPoint(x: anchors[i].x + cos(angle) * w * 0.2, y: anchors[i].y + sin(angle * 1.3) * h * 0.16)
                let wobble = sin(2 * .pi * breath + CGFloat(i))
                let depth = 1 - CGFloat(i) * 0.18
                let color = blooms[i].mix(with: blooms[(i + 1) % 3], by: Double(0.5 + 0.5 * sin(2 * CGFloat.pi * flow + CGFloat(i))))
                let radius = maxDim * (0.5 + 0.12 * wobble) * depth
                let alpha = cap * (0.8 + 0.2 * wobble) * (0.72 + 0.28 * depth)
                let rect = CGRect(x: center.x - radius, y: center.y - radius, width: radius * 2, height: radius * 2)
                gc.fill(Path(ellipseIn: rect), with: .radialGradient(
                    Gradient(colors: [color.opacity(alpha), color.opacity(0)]),
                    center: center, startRadius: 0, endRadius: radius))
            }
        }
        .allowsHitTesting(false)
    }
}

/// The surface's top edge in the window, written per frame without invalidating the view.
@MainActor
private final class SurfaceTop {
    var y: CGFloat = 0
}
