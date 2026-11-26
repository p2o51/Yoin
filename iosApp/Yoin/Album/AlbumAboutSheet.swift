import SwiftUI
import YoinShared

/// The album About sheet (Android ui/detail/AlbumAboutSheet.kt, owner + Carbon 2026-11-25), opened from the card
/// or the ✦ circle on the scrapbook: the credits as a dashed receipt (label left, value right), the whole
/// paragraph with Regenerate (undoable from the toast, like a song row's), the questions asked about the album,
/// and an ask field ("Ask <service>", Return or the send button asks). No Write here: everything on this sheet is
/// generated, in the tertiary role like the card.
///
/// Apple Music's own notes are Apple's: credited to Apple Music, never rewritten. Regenerate then redoes only what
/// the AI wrote, the credits, so it sits under the credits instead of on Apple's paragraph.
struct AlbumAboutSheet: View {
    var album: Album
    var content: AlbumAboutContent
    var piece: AlbumAboutPiece?
    var credits: [AlbumCreditItem]
    var scheme: CoverScheme
    /// The last try at writing the About again came back with nothing or failed ("<service> had nothing new").
    var notice: String?
    var onRegenerate: () -> Void
    var onAsk: (String) -> Void

    private var loader: AlbumAboutLoader { .shared }
    private var toast: PlaylistActions { .shared }
    @State private var detent: PresentationDetent = .medium

    var body: some View {
        AlbumAboutSheetBody(album: album, content: content, piece: piece, credits: credits, scheme: scheme,
                            regenerating: loader.isWriting(album), asking: loader.pendingQuestions(album),
                            notice: notice, providerName: AiProviderName.current,
                            onRegenerate: onRegenerate, onAsk: onAsk)
            // The notice takes its own room at the sheet's foot while it is open (the shell's toast sits under it).
            .overlay(alignment: .bottom) {
                if let t = toast.toast {
                    ToastPill(toast: t)
                        .padding(.horizontal, 24)
                        .padding(.bottom, 12)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                        .id(t.id)
                }
            }
            .animation(Yoin.spatial, value: toast.toast)
            .presentationDetents([.medium, .large], selection: $detent)
            .presentationDragIndicator(.visible)
    }
}

/// The sheet's content apart from the sheet itself, so previews and snapshot tests can draw it.
struct AlbumAboutSheetBody: View {
    var album: Album
    var content: AlbumAboutContent
    var piece: AlbumAboutPiece?
    var credits: [AlbumCreditItem]
    var scheme: CoverScheme
    var regenerating: Bool
    var asking: [String]
    var notice: String?
    var providerName: String
    var onRegenerate: () -> Void
    var onAsk: (String) -> Void
    /// False draws the whole content without the scroll view (renders: ImageRenderer draws no scroll content).
    var scrolls = true

    private static let gutter: CGFloat = 24
    private static let receiptLabelWidth: CGFloat = 88

    private var fromService: Bool { piece?.fromService ?? false }
    private var paragraph: String? {
        if case .paragraph(let text, _, _) = piece { return text }
        return nil
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 6) {
                    YoinIcon("sparkle", size: 14)
                    Text("About").yoinRole(Tokens.type.labelLarge, weight: .semibold)
                }
                .foregroundStyle(scheme.tertiary)
                Text(album.title)
                    .yoinRole(Tokens.type.titleLarge, weight: .semibold)
                    .foregroundStyle(scheme.onSurface)
                    .lineLimit(3)
                    .padding(.top, 4).padding(.bottom, 8)
            }
            .padding(.horizontal, Self.gutter)
            .padding(.top, 28)
            if scrolls {
                ScrollView { sheetContent }
                    .scrollDismissesKeyboard(.interactively)
            } else {
                sheetContent
            }
        }
    }

    private var sheetContent: some View {
        VStack(alignment: .leading, spacing: 0) {
            receipt
            if fromService { noticeLine }
            if paragraph == nil, !regenerating, piece != .pending {
                // Asked before and the AI knew nothing: no empty card, just the way to ask it again.
                generateButton
                noticeLine
            } else {
                paragraphCard
                if !fromService { noticeLine }
            }
            ForEach(asking, id: \.self) { question in pendingAsk(question).transition(.opacity) }
            ForEach(Array(content.asks.enumerated()), id: \.offset) { _, ask in askCard(ask) }
            AlbumAskField(scheme: scheme, hint: AiProviderName.copy(String(localized: "Ask %@"), providerName),
                          onAsk: onAsk)
                .padding(.top, 18)
        }
        .padding(.horizontal, Self.gutter)
        .padding(.bottom, 32)
        .animation(Yoin.spatial, value: asking)
        .animation(Yoin.spatial, value: content.asks.count)
        .animation(Yoin.spatial, value: notice)
        .animation(Yoin.effects, value: regenerating)
    }

    // MARK: Credits receipt

    /// The credits one per line, label left and value right, a dashed rule between (Now Playing's receipt).
    @ViewBuilder
    private var receipt: some View {
        let rows = AlbumCredits.rows(credits)
        if !rows.isEmpty || fromService {
            VStack(alignment: .leading, spacing: 0) {
                ForEach(Array(rows.enumerated()), id: \.offset) { i, row in
                    if i > 0 {
                        Line().stroke(scheme.tertiary.opacity(0.45), style: StrokeStyle(lineWidth: 1, dash: [4, 4]))
                            .frame(height: 1)
                    }
                    HStack(alignment: .firstTextBaseline, spacing: 16) {
                        Text(row.label).yoinRole(Tokens.type.labelLarge)
                            .foregroundStyle(scheme.onSurfaceVariant)
                            .frame(width: Self.receiptLabelWidth, alignment: .leading)
                        Text(row.value).yoinRole(Tokens.type.bodyMedium)
                            .foregroundStyle(scheme.onSurface)
                            .multilineTextAlignment(.trailing)
                            .frame(maxWidth: .infinity, alignment: .trailing)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .padding(.vertical, 10)
                    .accessibilityElement(children: .combine)
                }
                if fromService {
                    // The credits are all the AI wrote: its byline and Regenerate sit under them.
                    HStack(spacing: 0) {
                        if regenerating {
                            ThinkingDots(color: scheme.tertiary)
                        } else {
                            byline(color: scheme.tertiary)
                        }
                        Spacer(minLength: 8)
                        regenerateButton
                    }
                    .padding(.top, 4)
                }
            }
            .padding(.top, 8).padding(.bottom, 4)
        }
    }

    // MARK: Paragraph

    private var paragraphCard: some View {
        VStack(alignment: .leading, spacing: 0) {
            Group {
                if (regenerating && !fromService) || paragraph == nil {
                    ThinkingDots(color: scheme.onTertiaryContainer).padding(.vertical, 8)
                        .transition(.opacity)
                } else {
                    Text(paragraph ?? "")
                        .yoinRole(Tokens.type.bodyLarge)
                        .lineSpacing(YoinType.lineSpacing(size: Tokens.type.bodyLarge.size.cg,
                                                          lineHeight: Tokens.type.bodyLarge.size.cg * 1.6))
                        .foregroundStyle(scheme.onTertiaryContainer)
                        .fixedSize(horizontal: false, vertical: true)
                        .textSelection(.enabled)
                        .transition(.opacity)
                }
            }
            HStack(spacing: 0) {
                if fromService {
                    // Apple Music's own editorial notes, credited to Apple Music, not to an AI.
                    Text(verbatim: "Apple Music").yoinRole(Tokens.type.labelMedium, weight: .semibold).tracking(0.2)
                        .foregroundStyle(scheme.onTertiaryContainer)
                        .padding(.vertical, 10)
                } else {
                    byline(color: scheme.onTertiaryContainer)
                }
                Spacer(minLength: 8)
                if !fromService { regenerateButton }
            }
            .padding(.top, 10)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(scheme.tertiaryContainer, in: .rect(cornerRadius: 20, style: .continuous))
        .padding(.top, 14)
    }

    /// A quiet line under what was just tried: "<service> had nothing new" / "couldn't answer".
    @ViewBuilder
    private var noticeLine: some View {
        if let notice, !regenerating {
            Text(notice)
                .yoinRole(Tokens.type.bodyMedium)
                .foregroundStyle(scheme.onSurfaceVariant)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.top, 10).padding(.horizontal, 4)
                .transition(.opacity.combined(with: .move(edge: .top)))
        }
    }

    /// "Generate with <service>" (the song sheet's empty About offers the same), for an About that came back empty.
    private var generateButton: some View {
        Button(action: onRegenerate) {
            HStack(spacing: 8) {
                YoinIcon("sparkle", size: 18)
                Text(AiProviderName.copy(String(localized: "Generate with %@"), providerName))
                    .yoinRole(Tokens.type.labelLarge).lineLimit(1)
            }
            .foregroundStyle(scheme.onTertiaryContainer)
            .frame(maxWidth: .infinity, minHeight: 48)
            .background(scheme.tertiaryContainer, in: .capsule)
            .contentShape(.capsule)
        }
        .buttonStyle(PressScale())
        .padding(.top, 14)
    }

    private var regenerateButton: some View {
        Button(action: onRegenerate) {
            HStack(spacing: 6) {
                YoinIcon("sparkle", size: 16)
                Text("Regenerate").yoinRole(Tokens.type.labelLarge).lineLimit(1)
            }
            .foregroundStyle(scheme.tertiary)
            .padding(.horizontal, 16)
            .frame(minHeight: 40)
            .background(scheme.surface, in: .capsule)
            .contentShape(.capsule)
        }
        .buttonStyle(PressScale())
        .disabled(regenerating)
        .opacity(regenerating ? 0.38 : 1)
    }

    // MARK: Questions

    private func askCard(_ ask: AlbumAboutAsk) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(ask.question).font(.gsf(13.5)).foregroundStyle(scheme.onTertiaryContainer.opacity(0.75))
            if let title = ask.title {
                Text(title).yoinRole(Tokens.type.titleMedium, weight: .semibold)
                    .foregroundStyle(scheme.onTertiaryContainer)
                    .padding(.top, 6)
            }
            Text(NowPlayingAboutView.markdown(ask.answer))
                .yoinRole(Tokens.type.bodyLarge)
                .lineSpacing(YoinType.lineSpacing(size: Tokens.type.bodyLarge.size.cg,
                                                  lineHeight: Tokens.type.bodyLarge.size.cg * 1.55))
                .foregroundStyle(scheme.onTertiaryContainer)
                .fixedSize(horizontal: false, vertical: true)
                .textSelection(.enabled)
                .padding(.top, 4)
            byline(color: scheme.onTertiaryContainer).padding(.top, 8)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(scheme.tertiaryContainer, in: .rect(cornerRadius: 20, style: .continuous))
        .padding(.top, 14)
    }

    private func pendingAsk(_ question: String) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(question).font(.gsf(13.5)).foregroundStyle(scheme.onTertiaryContainer.opacity(0.75))
            ThinkingDots(color: scheme.onTertiaryContainer).padding(.top, 12).padding(.bottom, 4)
            byline(color: scheme.onTertiaryContainer).padding(.top, 8)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(scheme.tertiaryContainer, in: .rect(cornerRadius: 20, style: .continuous))
        .padding(.top, 14)
    }

    /// Whose words these are: the ✦ and the AI service in use.
    private func byline(color: Color) -> some View {
        HStack(spacing: 4) {
            YoinIcon("sparkle", size: 14)
            Text(verbatim: providerName).yoinRole(Tokens.type.labelMedium, weight: .semibold)
        }
        .foregroundStyle(color)
    }
}

/// The question field, "Ask <service>" as its hint, and a send button beside it (Now Playing's ask bar: Return
/// asks too).
private struct AlbumAskField: View {
    var scheme: CoverScheme
    var hint: String
    var onAsk: (String) -> Void

    @State private var question = ""
    @FocusState private var focused: Bool

    private var hasQuestion: Bool { !question.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    var body: some View {
        HStack(spacing: 6) {
            TextField(text: $question, prompt: Text(hint).foregroundStyle(scheme.onSurfaceVariant), axis: .vertical) {
                Text(hint)
            }
            .yoinRole(Tokens.type.bodyLarge)
            .foregroundStyle(scheme.onSurface)
            .tint(scheme.tertiary)
            .submitLabel(.send)
            .focused($focused)
            .onChange(of: question) { _, now in
                // A vertical field types the return key in: ask instead.
                guard now.contains("\n") else { return }
                question = now.replacingOccurrences(of: "\n", with: "")
                send()
            }
            .onSubmit(send)
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(scheme.surface, in: .rect(cornerRadius: 16, style: .continuous))
            Button(action: send) {
                Image(systemName: "arrow.up")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(scheme.onTertiaryContainer)
                    .frame(width: 44, height: 44)
                    .background(scheme.tertiaryContainer, in: .circle)
                    .contentShape(.circle)
            }
            .buttonStyle(PressScale())
            .disabled(!hasQuestion)
            .opacity(hasQuestion ? 1 : 0.38)
            .accessibilityLabel(Text(hint))
        }
        .padding(.leading, 10).padding(.trailing, 6).padding(.vertical, 10)
        .background(scheme.surfaceContainerHigh, in: .rect(cornerRadius: 24, style: .continuous))
        .animation(Yoin.effects, value: hasQuestion)
    }

    private func send() {
        let q = question.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return }
        onAsk(q)
        question = ""
        focused = false
    }
}

/// A horizontal line across its frame, for dashed rules.
private struct Line: Shape {
    func path(in rect: CGRect) -> Path {
        Path { p in
            p.move(to: CGPoint(x: 0, y: rect.midY))
            p.addLine(to: CGPoint(x: rect.maxX, y: rect.midY))
        }
    }
}
