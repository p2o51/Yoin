import SwiftUI

/*
 * The spread (Android showcase/MemoriesSpread): a wide container's two pages,
 * no morph and no diary level. The left page is the exhibit (the cover with
 * its emblem, the title, the album line, Yoin's paragraph, Go to album); the
 * right page is the diary from your own entry on, centred while it is short.
 * No divider; ≥ 64 pt between them. The whole deck uses one cover size, and
 * every card's cover starts at the same y, so a swipe never moves the exhibit.
 *
 * Input: a vertical drag on the left page is Home's (q, the body rule); the
 * right page scrolls, and a new upward drag from its end is q; sideways turns
 * the deck anywhere. Back goes Home (there is no diary level).
 */
struct MemoriesSpreadPage: View {
    var card: MemoryCard
    var deck: MemoriesDeckModel
    var layout: MemoriesLayout
    var cover: CGFloat
    var seal: CGFloat
    var size: CGSize
    var scheme: CoverScheme
    var dark: Bool
    var current: Bool
    var onOpenAlbum: () -> Void
    var onTitle: (MemoryTitles.Outcome) -> Void
    var onReview: (String) -> Bool
    var leftGesture: AnyGesture<DragGesture.Value>
    var horizontalPager: AnyGesture<DragGesture.Value>

    /// Right page: 40 in from the left page.
    private static let rightInset: CGFloat = 40

    var body: some View {
        let tones = MemoryTones(palette: card.album.palette, dark: dark)
        HStack(alignment: .top, spacing: 0) {
            left(tones)
                .frame(width: layout.leftPage)
                .frame(maxHeight: .infinity)
                .contentShape(.rect)
                .gesture(leftGesture, including: current ? .all : .subviews)
            right(tones)
                .frame(maxWidth: .infinity)
        }
        .padding(.top, layout.barHeight)
        .allowsHitTesting(current)
        .accessibilityHidden(!current)
    }

    private func left(_ tones: MemoryTones) -> some View {
        let album = card.titleKind == .album
        let size = album ? 26.0 : 30.0
        let font: Font = album ? .gsf(size, .semibold) : .system(size: size, weight: .semibold, design: .serif)
        let spacing = size * 0.3 - 6
        let onCover = CoverMarks.onCover(provider: card.album.id.provider)
        return VStack(spacing: 0) {
            Spacer(minLength: 24)
            CoverArt(palette: card.album.palette, cornerRadius: Tokens.shape.HERO.cg, seed: card.album.seed)
                .frame(width: cover, height: cover)
                .overlay(alignment: .topLeading) {
                    ScoreEmblem(score: card.score ?? 0, kind: card.emblemKind, rated: card.rated,
                                palette: card.album.palette, size: seal, award: deck.cardAward(card),
                                neutrals: scheme, onCover: onCover)
                        // A Spotify cover: the stamp under its right edge, clear of it (CoverMarks).
                        .padding(.leading, onCover ? cover - seal * 0.7 : cover - seal)
                        .padding(.top, onCover ? cover - seal * 0.76 : cover + CoverMarks.gap)
                }
                .padding(.bottom, onCover ? seal * 0.24 : seal + CoverMarks.gap)
            MemoryTitleSlot(card: card, surface: .spread, editor: deck.editor, scheme: scheme, tones: tones, font: font,
                            lineSpacing: spacing, centred: true, enabled: current, onCommit: onTitle) {
                Text(card.title)
                    .font(font)
                    .lineSpacing(spacing)
                    .multilineTextAlignment(.center)
                    .lineLimit(3)
                    .foregroundStyle(scheme.onSurface)
                    .accessibilityAddTraits(.isHeader)
            }
            .frame(maxWidth: layout.leftPage - 64)
            .padding(.top, 40)
            MemoryTitleEditRow(card: card, surface: .spread, editor: deck.editor, scheme: scheme, tones: tones,
                               centred: true, onCommit: onTitle)
                .frame(maxWidth: layout.leftPage - 64)
            if let paragraph = card.paragraph {
                DiaryParagraph(text: paragraph, scheme: scheme, centred: true)
                    .frame(maxWidth: min(layout.leftPage - 64, 420))
                    .padding(.top, 14)
            }
            VStack(spacing: 2) {
                if !album {
                    Text(card.album.title).font(.gsf(17, .semibold)).foregroundStyle(tones.ink).lineLimit(2)
                }
                HStack(spacing: Tokens.metaLine.GROUP_GAP.cg) {
                    if !card.album.artist.isEmpty { Text(card.album.artist) }
                    if card.album.year > 0 { Text(String(card.album.year)).monospacedDigit() }
                }
                .font(.gsf(13, .medium))
                .foregroundStyle(scheme.onSurfaceVariant)
                .lineLimit(1)
            }
            .multilineTextAlignment(.center)
            .padding(.top, 18)
            Button(action: onOpenAlbum) {
                Text("Go to album")
                    .font(.gsf(15, .semibold))
                    .foregroundStyle(tones.onButton)
                    .padding(.horizontal, 24)
                    .frame(height: 48)
                    .background(tones.button, in: .capsule)
            }
            .buttonStyle(PressScale())
            .padding(.top, 30)
            Spacer(minLength: 56)
        }
        .ignoresSafeArea(.keyboard)
    }

    private func right(_ tones: MemoryTones) -> some View {
        GeometryReader { geo in
            ScrollView {
                DiaryBlocks(card: card, deck: deck, scheme: scheme, tones: tones, interactive: current, showGo: false,
                            firstBlock: 0, onOpenAlbum: onOpenAlbum, onReview: onReview)
                    .frame(width: layout.measure)
                    .padding(.vertical, 24)
                    .frame(maxWidth: .infinity, minHeight: geo.size.height, alignment: .leading)
                    .padding(.leading, Self.rightInset)
                    .background(DiaryScrollObserver(router: deck.router, active: current, spread: true))
            }
            .scrollIndicators(.hidden)
            .scrollEdgeEffectStyle(.soft, for: [.top, .bottom])
            .scrollDismissesKeyboard(.interactively)
            .simultaneousGesture(horizontalPager, including: current ? .all : .subviews)
        }
    }
}
