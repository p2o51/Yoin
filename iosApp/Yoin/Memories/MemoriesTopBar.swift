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
    var position: CGFloat
    var deck: MemoriesDeckModel
    var layout: MemoriesLayout
    var scheme: CoverScheme
    var dark: Bool
    var width: CGFloat
    var onHome: () -> Void
    var onDot: (Int) -> Void

    private var diary: MemoriesDiary { deck.diary }

    private var currentIndex: Int { min(max(Int(position.rounded()), 0), max(cards.count - 1, 0)) }

    /// The current page's cover flight (0 in a spread: it has no p).
    private var fp: CGFloat {
        guard layout.twoState, cards.indices.contains(currentIndex) else { return 0 }
        let morph = deck.morph(cards[currentIndex])
        return MemoriesMorph.coverProgress(diary.fraction, frozen: morph.frozen(barHeight: layout.barHeight))
    }

    var body: some View {
        let fp = fp
        ZStack(alignment: .leading) {
            if layout.twoState && diary.isShown {
                ForEach(visible, id: \.self) { i in slotB(i) }
            }
            HStack(spacing: 0) {
                pill(fp)
                Spacer(minLength: 12)
                dots
            }
            .padding(.leading, layout.pillStart)
            .padding(.trailing, layout.dotsEnd)
        }
        .frame(height: Self.height)
        .contentShape(.rect)
    }

    private var visible: [Int] {
        guard !cards.isEmpty else { return [] }
        let lo = max(0, Int(position.rounded(.down)) - 1), hi = min(cards.count - 1, Int(position.rounded(.up)) + 1)
        return lo <= hi ? Array(lo...hi) : []
    }

    private func pill(_ fp: CGFloat) -> some View {
        let narrow = smoothstep(0, MemoriesMorphTokens.pillNarrowTo, fp)
        let label = 1 - smoothstep(0, MemoriesMorphTokens.pillLabelFadeTo, fp)
        return Button(action: onHome) {
            HStack(spacing: 4) {
                YoinIcon("chevron_up", size: 18)
                if label > 0.01 {
                    Text("Home").homeType(Tokens.type.labelLarge, .semibold)
                        .opacity(Double(label))
                        .fixedSize()
                }
            }
            .foregroundStyle(scheme.onSurface)
            .frame(width: Self.pillWidth + (Self.pillWidthDiary - Self.pillWidth) * narrow, height: 36)
            .clipped()
            .background(scheme.surfaceContainerHigh, in: .capsule)
            .frame(height: 48)
            .contentShape(.rect)
        }
        .buttonStyle(PressScale())
        .accessibilityLabel("Close Memories, back to Home")
    }

    private var dots: some View {
        HStack(spacing: 0) {
            ForEach(Array(cards.enumerated()), id: \.offset) { i, card in
                // 7 pt, the current one 12, by how near the deck is to it.
                let near = max(0, 1 - abs(CGFloat(i) - position))
                let color = MemoryTones(palette: card.album.palette, dark: dark).dot
                Circle()
                    .fill(color.opacity(0.35 + 0.65 * near))
                    .frame(width: 7 + 5 * near, height: 7 + 5 * near)
                    .frame(width: 18, height: 48)
                    .contentShape(.rect)
                    .onTapGesture { onDot(i) }
                    .accessibilityLabel("Memory \(i + 1)")
                    .accessibilityAddTraits(.isButton)
            }
        }
    }

    /// Card [i]'s bar cover and slot B, with the pager's parallax.
    private func slotB(_ i: Int) -> some View {
        let card = cards[i]
        let morph = deck.morph(card)
        let rel = CGFloat(i) - position
        let page = max(0, 1 - abs(rel) * Self.slotFade)
        let p = diary.fraction
        let fpi = MemoriesMorph.coverProgress(p, frozen: morph.frozen(barHeight: layout.barHeight))
        let coverAlpha = diary.reduced ? MemoriesMorph.reducedDiaryAlpha(p) : MemoriesMorph.barCoverShown(fpi)
        let b = smoothstep(MemoriesMorphTokens.slotBFadeFrom, MemoriesMorphTokens.slotBFadeTo, p)
        return HStack(spacing: 14) {
            CoverArt(palette: card.album.palette, cornerRadius: MemoriesMorphTokens.coverCornerBar, seed: card.album.seed)
                .frame(width: Self.barCover, height: Self.barCover)
                .opacity(Double(coverAlpha))
            HStack(spacing: 6) {
                Text(card.album.title)
                    .font(.gsf(15, .semibold))
                    .foregroundStyle(scheme.onSurface)
                    .lineLimit(1)
                    .pageFrame { r in if i == currentIndex, abs(rel) < 0.01 { morph.barTitle = r } }
                YoinIcon("chevron_down", size: 18)
                    .foregroundStyle(scheme.onSurfaceVariant)
            }
            .opacity(Double(b))
            .offset(y: (1 - b) * -MemoriesMorphTokens.slotBDrop)
        }
        .padding(.leading, layout.barCoverStart)
        // Clear of the dot cluster.
        .padding(.trailing, layout.dotsEnd + 18 * CGFloat(cards.count) + 19)
        .frame(width: width, height: Self.height, alignment: .leading)
        .contentShape(.rect)
        .onTapGesture { if i == currentIndex, diary.isDiaryLevel { deck.closeDiary() } }
        .offset(x: rel * width * Self.slotParallax)
        .opacity(Double(page))
        .allowsHitTesting(i == currentIndex && diary.isDiaryLevel)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Close diary, \(card.album.title)")
        .accessibilityAddTraits(.isButton)
        .accessibilityHidden(!(i == currentIndex && diary.isDiaryLevel))
    }
}
