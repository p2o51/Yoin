import SwiftUI
import YoinShared

/*
 * The album About on the scrapbook paper (Android ui/detail/AlbumAboutCard.kt, owner + Carbon 2026-11-25): the
 * credits line under the title and the editorial card after the opening. The sheet behind the card is
 * AlbumAboutSheet.swift.
 *
 * Colours (51, 2026-11-25): what the listener writes is primary; AI and objective text is tertiary. On the card
 * every mark (✦, "About", "Read all", the dots) is in the card's own ink, onTertiaryContainer: the tertiary mark
 * is too faint on a cover's tertiaryContainer (Android device check, dark theme).
 */

/// The paragraph lines the paper shows; the rest is a tap away (owner: 3–4, so the page doesn't grow).
enum AlbumAboutLayout {
    static let cardLines = 4
    /// A card as wide as a reading column, never the whole iPad page.
    static let cardMaxWidth: CGFloat = 560
    /// Air above the cover and the title beside it (Android ScrapbookLayout.OpeningTopGap, 51 2026-11-25).
    static let openingTopGap: CGFloat = 10
    /// The empty About's dashed circle (the blank review's pattern).
    static let emptyMark: CGFloat = 48
}

/// The credits line (`AlbumCredits.line`): bodyMedium at 13.5, line 1.5 em, onSurfaceVariant. Its text keeps
/// :shared's no-break spaces and word joiners, so Core Text breaks it where Android does. Nothing when no item
/// is known.
struct AlbumCreditsLineView: View {
    var items: [AlbumCreditItem]
    var scheme: CoverScheme

    var body: some View {
        if let line = AlbumCredits.line(items) {
            Text(line.text)
                .font(.gsf(13.5))
                .lineSpacing(YoinType.lineSpacing(size: 13.5, lineHeight: 13.5 * 1.5))
                .foregroundStyle(scheme.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: AlbumAboutLayout.cardMaxWidth, alignment: .leading)
                .accessibilityLabel(line.plain)
        }
    }
}

/// The paragraph on a tertiary card, at most four lines with "Read all" when it runs on (or questions wait in
/// the sheet); the AI's dots while it is first written. A tap opens the About sheet. Apple Music's own notes say
/// whose they are and go without the ✦.
struct AlbumAboutParagraphCard: View {
    var text: String?
    var fromService: Bool
    var hasQuestions: Bool
    var albumName: String
    var scheme: CoverScheme
    var onOpen: () -> Void

    @State private var overflowing = false

    var body: some View {
        let ink = scheme.onTertiaryContainer
        Button(action: onOpen) {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 0) {
                    if !fromService {
                        YoinIcon("sparkle", size: 14).foregroundStyle(ink)
                        Spacer().frame(width: 6)
                    }
                    Text("About").yoinRole(Tokens.type.labelLarge, weight: .semibold).tracking(0.2)
                        .foregroundStyle(ink)
                    if fromService {
                        Spacer().frame(width: 10)
                        Text(verbatim: "Apple Music").yoinRole(Tokens.type.labelMedium).tracking(0.2)
                            .foregroundStyle(ink.opacity(0.8))
                    }
                }
                if let text {
                    Text(text)
                        .font(.gsf(14.5))
                        .lineSpacing(YoinType.lineSpacing(size: 14.5, lineHeight: 14.5 * 1.55))
                        .foregroundStyle(ink)
                        .lineLimit(AlbumAboutLayout.cardLines)
                        .truncationMode(.tail)
                        .multilineTextAlignment(.leading)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background { TruncationProbe(text: text, lines: AlbumAboutLayout.cardLines, overflowing: $overflowing) }
                        .padding(.top, 6)
                    if overflowing || hasQuestions {
                        Text("Read all").yoinRole(Tokens.type.labelLarge, weight: .semibold)
                            .foregroundStyle(ink)
                            .padding(.top, 6)
                    }
                } else {
                    ThinkingDots(color: ink).padding(.top, 12).padding(.bottom, 4)
                }
            }
            .padding(.leading, 16).padding(.trailing, 16).padding(.top, 12).padding(.bottom, 14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(scheme.tertiaryContainer, in: .rect(cornerRadius: Tokens.shape.CARD.cg, style: .continuous))
            .contentShape(.rect(cornerRadius: Tokens.shape.CARD.cg, style: .continuous))
        }
        .buttonStyle(.plain).pointerHighlight()
        .disabled(text == nil)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(String(localized: "About \(albumName): \(text ?? "")"))
        .accessibilityHint(Text("Read all"))
        .accessibilityAddTraits(.isButton)
    }
}

/// The album's About was asked for and came back empty: no card, only a 48 pt dashed circle with the ✦ (the blank
/// review's pattern, in the generated text's tertiary). It opens the About sheet, where the AI can be asked again or
/// asked a question. No words of its own: VoiceOver reads "About".
struct AlbumAboutEmptyMark: View {
    var scheme: CoverScheme
    var onOpen: () -> Void

    var body: some View {
        Button(action: onOpen) {
            YoinIcon("sparkle", size: 20)
                .foregroundStyle(scheme.tertiary)
                .frame(width: AlbumAboutLayout.emptyMark, height: AlbumAboutLayout.emptyMark)
                .overlay {
                    Circle().strokeBorder(scheme.tertiary, style: StrokeStyle(lineWidth: 1.5, dash: [5, 5]))
                }
                .contentShape(.circle)
        }
        .buttonStyle(PressScale())
        .accessibilityLabel(Text("About"))
    }
}

/// Whether [text] runs past [lines] at the width it is laid out in: a hidden copy measured both ways.
private struct TruncationProbe: View {
    var text: String
    var lines: Int
    @Binding var overflowing: Bool

    var body: some View {
        GeometryReader { geo in
            let style = { (t: Text) in
                t.font(.gsf(14.5)).lineSpacing(YoinType.lineSpacing(size: 14.5, lineHeight: 14.5 * 1.55))
            }
            ZStack(alignment: .topLeading) {
                style(Text(text)).lineLimit(lines)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(width: geo.size.width, alignment: .leading)
                    .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { clipped in
                        limited = clipped
                        update()
                    }
                style(Text(text))
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(width: geo.size.width, alignment: .leading)
                    .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { whole in
                        full = whole
                        update()
                    }
            }
            .hidden()
        }
        .accessibilityHidden(true)
    }

    @State private var limited: CGFloat = 0
    @State private var full: CGFloat = 0

    private func update() {
        let runsOn = full > limited + 1
        if runsOn != overflowing { overflowing = runsOn }
    }
}
