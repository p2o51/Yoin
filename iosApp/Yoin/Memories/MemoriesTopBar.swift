import SwiftUI

/*
 * The Memories top bar (Android showcase/MemoriesTopBar, twostate4 `.ts-bar`,
 * `.ts-slotB`, `.ts-cov-bar`). Shared over the pager: the "⌃ Home" pill and
 * the page dots, each dot a tap target, the current one larger. Per page,
 * once the diary morph starts: the diary's 40 pt cover and slot B (the album
 * name and ⌄). The bar cover takes over from the flying card cover by alpha;
 * a tap on it, on slot B or on the ⌄ closes the diary. The pill narrows
 * 88 → 36 over the cover's flight to make room for it.
 *
 * Per-page slots ride the pager with a parallax (0.6·W·rel) and fade by
 * 1 − |rel|·2.2. Every per-frame value (pager position, p) is read here, a
 * small view of its own.
 */
struct MemoriesTopBar: View {
    static let height: CGFloat = MemoriesLayoutTokens.barHeight
    static let barCover: CGFloat = 40
    static let pillWidth: CGFloat = 88
    static let pillWidthDiary: CGFloat = 36
    static let slotParallax: CGFloat = 0.6
    static let slotFade: CGFloat = 2.2

    var cards: [MemoryCard]
    var pager: MemoriesPagerModel?
    var position: CGFloat
    var deck: MemoriesDeckModel
    var layout: MemoriesLayout
    var scheme: CoverScheme
    var dark: Bool
    var width: CGFloat
    var onHome: () -> Void
    var onDot: (Int) -> Void

    init(cards: [MemoryCard], position: CGFloat, deck: MemoriesDeckModel, layout: MemoriesLayout,
         scheme: CoverScheme, dark: Bool, width: CGFloat,
         onHome: @escaping () -> Void, onDot: @escaping (Int) -> Void) {
        self.cards = cards
        self.pager = nil
        self.position = position
        self.deck = deck
        self.layout = layout
        self.scheme = scheme
        self.dark = dark
        self.width = width
        self.onHome = onHome
        self.onDot = onDot
    }

    init(cards: [MemoryCard], pager: MemoriesPagerModel, deck: MemoriesDeckModel, layout: MemoriesLayout,
         scheme: CoverScheme, dark: Bool, width: CGFloat,
         onHome: @escaping () -> Void, onDot: @escaping (Int) -> Void) {
        self.cards = cards
        self.pager = pager
        self.position = 0
        self.deck = deck
        self.layout = layout
        self.scheme = scheme
        self.dark = dark
        self.width = width
        self.onHome = onHome
        self.onDot = onDot
    }

    private var diary: MemoriesDiary { deck.diary }

    private var visible: [Int] {
        if let pager { return pager.visibleRange }
        guard !cards.isEmpty else { return [] }
        let lo = max(0, Int(position.rounded(.down)) - 1), hi = min(cards.count - 1, Int(position.rounded(.up)) + 1)
        return lo <= hi ? Array(lo...hi) : []
    }

    /// The per-page slots' end inset: clear of the dot cluster by 12.
    private var slotEnd: CGFloat { layout.dotsEnd + 18 * CGFloat(cards.count) + 19 }

    var body: some View {
        ZStack(alignment: .leading) {
            ForEach(visible, id: \.self) { i in
                if cards.indices.contains(i) {
                    MemoriesTopBarSlotAView(card: cards[i], index: i, pager: pager, fallbackPosition: position,
                                            diary: diary, layout: layout, scheme: scheme,
                                            slotEnd: slotEnd, width: width)
                }
            }
            if layout.twoState {
                ForEach(visible, id: \.self) { i in
                    if cards.indices.contains(i) {
                        MemoriesTopBarSlotBView(card: cards[i], index: i, pager: pager, fallbackPosition: position,
                                                deck: deck, layout: layout, scheme: scheme,
                                                slotEnd: slotEnd, width: width)
                            .opacity(diary.isShown ? 1 : 0)
                    }
                }
            }
            HStack(spacing: 0) {
                MemoriesTopBarPill(cards: cards, pager: pager, fallbackPosition: position,
                                   deck: deck, layout: layout, scheme: scheme, onHome: onHome)
                Spacer(minLength: 12)
                MemoriesTopBarDots(cards: cards, pager: pager, fallbackPosition: position,
                                   dark: dark, onDot: onDot)
            }
            .padding(.leading, layout.pillStart)
            .padding(.trailing, layout.dotsEnd)
        }
        .frame(height: Self.height)
        .contentShape(.rect)
    }

    /// "Nov 2"; with the year when it isn't this one (Android formatMemoryChromeDate).
    static func lastHeard(_ ms: Int64?, now: Date = .now, locale: Locale = .current) -> String? {
        guard let ms else { return nil }
        let date = Date(timeIntervalSince1970: Double(ms) / 1000)
        let sameYear = Calendar.current.component(.year, from: date) == Calendar.current.component(.year, from: now)
        let style = Date.FormatStyle(locale: locale).month(.abbreviated).day()
        return date.formatted(sameYear ? style : style.year())
    }

    private struct LastHeardKey: Hashable {
        var ms: Int64
        var year: Int
        var localeId: String
    }

    @MainActor private static var lastHeardCache: [LastHeardKey: String] = [:]
    @MainActor private static var lastHeardOrder: [LastHeardKey] = []

    @MainActor
    static func cachedLastHeard(_ ms: Int64?, now: Date = .now, locale: Locale = .current) -> String? {
        guard let ms else { return nil }
        let year = Calendar.current.component(.year, from: now)
        let key = LastHeardKey(ms: ms, year: year, localeId: locale.identifier)
        if let hit = lastHeardCache[key] { return hit }
        guard let formatted = lastHeard(ms, now: now, locale: locale) else { return nil }
        lastHeardOrder.append(key)
        if lastHeardOrder.count > 64 {
            let evicted = lastHeardOrder.removeFirst()
            lastHeardCache.removeValue(forKey: evicted)
        }
        lastHeardCache[key] = formatted
        return formatted
    }
}

private struct MemoriesTopBarPill: View {
    var cards: [MemoryCard]
    var pager: MemoriesPagerModel?
    var fallbackPosition: CGFloat
    var deck: MemoriesDeckModel
    var layout: MemoriesLayout
    var scheme: CoverScheme
    var onHome: () -> Void

    private var currentIndex: Int {
        if let pager { return pager.currentIndex }
        return min(max(Int(fallbackPosition.rounded()), 0), max(cards.count - 1, 0))
    }

    private var fp: CGFloat {
        guard layout.twoState, cards.indices.contains(currentIndex) else { return 0 }
        let morph = deck.morph(cards[currentIndex])
        return MemoriesMorph.coverProgress(deck.diary.fraction, frozen: morph.frozen(barHeight: layout.barHeight))
    }

    var body: some View {
        let fp = fp
        let narrow = smoothstep(0, MemoriesMorphTokens.pillNarrowTo, fp)
        let label = 1 - smoothstep(0, MemoriesMorphTokens.pillLabelFadeTo, fp)
        Button(action: onHome) {
            HStack(spacing: 4) {
                YoinIcon("chevron_up", size: 18)
                if label > 0.01 {
                    Text("Home").homeType(Tokens.type.labelLarge, .semibold)
                        .opacity(Double(label))
                        .fixedSize()
                }
            }
            .foregroundStyle(scheme.onSurface)
            .frame(width: MemoriesTopBar.pillWidth + (MemoriesTopBar.pillWidthDiary - MemoriesTopBar.pillWidth) * narrow, height: 36)
            .clipped()
            .background(scheme.surfaceContainerHigh, in: .capsule)
            .frame(height: 48)
            .contentShape(.rect)
        }
        .buttonStyle(PressScale())
        .accessibilityLabel("Close Memories, back to Home")
    }
}

private struct MemoriesTopBarDots: View {
    var cards: [MemoryCard]
    var pager: MemoriesPagerModel?
    var fallbackPosition: CGFloat
    var dark: Bool
    var onDot: (Int) -> Void

    var body: some View {
        HStack(spacing: 0) {
            ForEach(Array(cards.enumerated()), id: \.offset) { i, card in
                let color = MemoryTones.cached(for: card, dark: dark).dot
                MemoriesTopBarDot(index: i, color: color, pager: pager,
                                  fallbackPosition: fallbackPosition) {
                    onDot(i)
                }
            }
        }
    }
}

private struct MemoriesTopBarDot: View {
    var index: Int
    var color: Color
    var pager: MemoriesPagerModel?
    var fallbackPosition: CGFloat
    var onTap: () -> Void

    var body: some View {
        let pos = pager?.position ?? fallbackPosition
        let near = max(0, 1 - abs(CGFloat(index) - pos))
        Circle()
            .fill(color.opacity(0.35 + 0.65 * near))
            .frame(width: 7 + 5 * near, height: 7 + 5 * near)
            .frame(width: 18, height: 48)
            .contentShape(.rect)
            .pointerHighlight()
            .onTapGesture(perform: onTap)
            .accessibilityLabel("Memory \(index + 1)")
            .accessibilityAddTraits(.isButton)
    }
}

private struct MemoriesTopBarSlotAView: View {
    var card: MemoryCard
    var index: Int
    var pager: MemoriesPagerModel?
    var fallbackPosition: CGFloat
    var diary: MemoriesDiary
    var layout: MemoriesLayout
    var scheme: CoverScheme
    var slotEnd: CGFloat
    var width: CGFloat

    var body: some View {
        let heard = MemoriesTopBar.cachedLastHeard(card.lastHeardAt)
        VStack(alignment: .leading, spacing: 0) {
            Text("Memories")
                .font(.gsf(15, .semibold))
                .foregroundStyle(scheme.onSurface)
            if let heard {
                Text("Last heard \(heard)")
                    .font(.gsf(12, .medium))
                    .foregroundStyle(scheme.onSurfaceVariant)
            }
        }
        .lineLimit(1)
        .padding(.leading, layout.slotAStart)
        .padding(.trailing, slotEnd)
        .frame(width: width, height: MemoriesTopBar.height, alignment: .leading)
        .modifier(SlotAMotionModifier(index: index, pager: pager, fallbackPosition: fallbackPosition,
                                      diary: diary, twoState: layout.twoState, width: width))
        .allowsHitTesting(false)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(heard.map { String(localized: "Memories, last heard \($0)") } ?? String(localized: "Memories"))
        .accessibilityAddTraits(.isHeader)
    }
}

private struct SlotAMotionModifier: ViewModifier {
    var index: Int
    var pager: MemoriesPagerModel?
    var fallbackPosition: CGFloat
    var diary: MemoriesDiary
    var twoState: Bool
    var width: CGFloat

    func body(content: Content) -> some View {
        let pos = pager?.position ?? fallbackPosition
        let currentIndex = pager?.currentIndex ?? Int(fallbackPosition.rounded())
        let rel = CGFloat(index) - pos
        let page = max(0, 1 - abs(rel) * MemoriesTopBar.slotFade)
        let p = twoState ? min(max(diary.fraction, 0), 1) : 0
        let a = diary.reduced ? MemoriesMorph.reducedCardAlpha(p) : 1 - smoothstep(0, MemoriesMorphTokens.slotAFadeTo, p)
        content
            .offset(y: diary.reduced ? 0 : -MemoriesMorphTokens.slotALift * p)
            .opacity(Double(a))
            .offset(x: rel * width * MemoriesTopBar.slotParallax)
            .opacity(Double(page))
            .accessibilityHidden(index != currentIndex || p >= 0.5)
    }
}

private struct MemoriesTopBarSlotBView: View {
    var card: MemoryCard
    var index: Int
    var pager: MemoriesPagerModel?
    var fallbackPosition: CGFloat
    var deck: MemoriesDeckModel
    var layout: MemoriesLayout
    var scheme: CoverScheme
    var slotEnd: CGFloat
    var width: CGFloat

    private var diary: MemoriesDiary { deck.diary }

    var body: some View {
        let morph = deck.morph(card)
        let currentIndex = pager?.currentIndex ?? Int(fallbackPosition.rounded())
        let isCurrent = index == currentIndex
        HStack(spacing: 14) {
            CoverArt(palette: card.album.palette, cornerRadius: MemoriesMorphTokens.coverCornerBar, seed: card.album.seed)
                .frame(width: MemoriesTopBar.barCover, height: MemoriesTopBar.barCover)
                .modifier(SlotBCoverAlphaModifier(diary: diary, morph: morph, barHeight: layout.barHeight))
            HStack(spacing: 6) {
                VStack(alignment: .leading, spacing: 0) {
                    Text(card.album.title)
                        .font(.gsf(15, .semibold))
                        .foregroundStyle(scheme.onSurface)
                        .pageFrame { r in
                            let pos = pager?.position ?? fallbackPosition
                            let active = pager?.currentIndex ?? Int(fallbackPosition.rounded())
                            if index == active, abs(CGFloat(index) - pos) < 0.01 { morph.barTitle = r }
                        }
                    // Artist, then the year at 60 %.
                    HStack(spacing: Tokens.metaLine.GROUP_GAP.cg) {
                        if !card.album.artist.isEmpty { Text(card.album.artist).foregroundStyle(scheme.onSurface) }
                        if card.album.year > 0 {
                            Text(String(card.album.year)).monospacedDigit().foregroundStyle(scheme.onSurface.opacity(0.6))
                        }
                    }
                    .font(.gsf(12, .medium))
                }
                .lineLimit(1)
                YoinIcon("chevron_down", size: 18)
                    .foregroundStyle(scheme.onSurfaceVariant)
            }
            .modifier(SlotBTextMotionModifier(diary: diary))
        }
        .padding(.leading, layout.barCoverStart)
        // Clear of the dot cluster.
        .padding(.trailing, slotEnd)
        .frame(width: width, height: MemoriesTopBar.height, alignment: .leading)
        .contentShape(.rect)
        .onTapGesture {
            let active = pager?.currentIndex ?? Int(fallbackPosition.rounded())
            if index == active, diary.isDiaryLevel { deck.closeDiary() }
        }
        .modifier(SlotBPageParallaxModifier(index: index, pager: pager, fallbackPosition: fallbackPosition, width: width))
        .allowsHitTesting(isCurrent && diary.isDiaryLevel)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Close diary, \(card.album.title)")
        .accessibilityAddTraits(.isButton)
        .accessibilityHidden(!(isCurrent && diary.isDiaryLevel))
    }
}

private struct SlotBCoverAlphaModifier: ViewModifier {
    var diary: MemoriesDiary
    var morph: MemoryPageMorph
    var barHeight: CGFloat

    func body(content: Content) -> some View {
        let p = diary.fraction
        let fpi = MemoriesMorph.coverProgress(p, frozen: morph.frozen(barHeight: barHeight))
        let coverAlpha = diary.reduced ? MemoriesMorph.reducedDiaryAlpha(p) : MemoriesMorph.barCoverShown(fpi)
        content.opacity(Double(coverAlpha))
    }
}

private struct SlotBTextMotionModifier: ViewModifier {
    var diary: MemoriesDiary

    func body(content: Content) -> some View {
        let b = smoothstep(MemoriesMorphTokens.slotBFadeFrom, MemoriesMorphTokens.slotBFadeTo, diary.fraction)
        content
            .opacity(Double(b))
            .offset(y: (1 - b) * -MemoriesMorphTokens.slotBDrop)
    }
}

private struct SlotBPageParallaxModifier: ViewModifier {
    var index: Int
    var pager: MemoriesPagerModel?
    var fallbackPosition: CGFloat
    var width: CGFloat

    func body(content: Content) -> some View {
        let pos = pager?.position ?? fallbackPosition
        let rel = CGFloat(index) - pos
        let page = max(0, 1 - abs(rel) * MemoriesTopBar.slotFade)
        content
            .offset(x: rel * width * MemoriesTopBar.slotParallax)
            .opacity(Double(page))
    }
}
