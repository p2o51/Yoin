import SwiftUI
import UIKit
import YoinShared

/*
 * The share images (Android ShareCards.kt / LyricsShareCard.kt, measurements from the prototypes in
 * design/hifi/src/components/share.js). Every image lays out at its logical size — Card 360 pt wide (exported
 * 1440 px, its height following the content), Story 360 × 640 (exported 1080 × 1920) — with fixed type (font
 * scale 1, no Dynamic Type), and exports as a full rectangle: the Card is its own colour to every edge, square;
 * a Story's backdrop fills around its card, or the layout itself is the poster. Same family everywhere: 24
 * padding, the 44 cover + title / artist head, the Yoin mark at the foot, GSF rounded SemiBold for hero text.
 */

/// How a layout's body is framed: the Card format, a card on a Story's backdrop, or a whole Story poster.
enum ShareFrame { case card, storyCard, poster }

/// The image's logical sizes (the shared constants, as points).
enum ShareMetrics {
    static var cardWidth: CGFloat { CGFloat(YoinShareCards.shared.cardDesignWidthDp) }
    static var story: CGSize {
        CGSize(width: CGFloat(YoinShareCards.shared.storyDesignWidthDp),
               height: CGFloat(YoinShareCards.shared.storyDesignHeightDp))
    }
    /// The frame a Story's content zooms into, clear of the chrome stories draw over the image.
    static var storyFrame: CGSize {
        CGSize(width: story.width - 2 * CGFloat(YoinShareCards.shared.storyMarginHorizontalDp),
               height: story.height - 2 * CGFloat(YoinShareCards.shared.storyMarginVerticalDp))
    }

    static let cardPadding: CGFloat = 24
    static let posterPadding: CGFloat = 28
    static let posterCardMinHeight: CGFloat = 400
    static let verticalCardMinHeight: CGFloat = 470
    /// A Story card's corners (Android YoinContainerShapes.Panel).
    static let panel: CGFloat = 20
    /// The translation's footnote / bottom line: low contrast.
    static let quiet: Double = 0.55
    /// Translation lines under the lyrics.
    static let translationAlpha: Double = 0.76
    /// At least this between the mark's "Yoin" and the sign.
    static let signGap: CGFloat = 16
}

/// What every body draws with: the content, the tone's colours on the playing scheme, the frame and the type.
@MainActor
struct ShareCtx {
    let render: ShareRender
    let layout: ShareLayout
    let format: ShareFormat
    let frame: ShareFrame
    let colors: ShareCardColors
    let scheme: CoverScheme
    let script: YoinCJKScript

    var song: ShareSong { render.song }

    func gsf(_ size: CGFloat, _ weight: Font.Weight = .regular) -> Font {
        Font(YoinFont.staticUIFont(size: size, weight: weight, script: script))
    }

    /// Android GoogleSansFlexRounded (ROND 100) at SemiBold, or Medium.
    func rounded(_ size: CGFloat, semibold: Bool = true) -> Font {
        let t = semibold ? Tokens.type.roundedSemiBold : Tokens.type.roundedMedium
        return Font(YoinFont.uiFont(size: size, YoinFont.Axes(weight: CGFloat(t.weight), width: t.width.cg, round: t.round.cg),
                                    script: script))
    }

    /// GoogleSansFlexRounded at Bold: a paragraph's **bold** over rounded SemiBold.
    func roundedBold(_ size: CGFloat) -> Font {
        let t = Tokens.type.roundedSemiBold
        return Font(YoinFont.uiFont(size: size, YoinFont.Axes(weight: 750, width: t.width.cg, round: t.round.cg), script: script))
    }

    /// Google Sans Flex at 80% width, Regular: the ticket stub's quiet time.
    func narrow(_ size: CGFloat) -> Font {
        Font(YoinFont.uiFont(size: size, YoinFont.Axes(weight: 400, width: 80), script: script))
    }

    /// A line of words in [font], [lineHeight] tall per line (Compose's line box).
    func text(_ s: String, _ font: Font, size: CGFloat, lineHeight: CGFloat, color: Color, tracking: CGFloat = 0,
              lines: Int? = nil, align: TextAlignment = .leading) -> some View {
        Text(verbatim: s)
            .font(font)
            .tracking(tracking)
            .foregroundStyle(color)
            .lineLimit(lines)
            .truncationMode(.tail)
            .multilineTextAlignment(align)
            .homeLine(size, lineHeight)
            .fixedSize(horizontal: false, vertical: true)
    }

    /// An M3 role (YoinTokens.Typography) at a fixed size.
    func role(_ s: String, _ style: YoinTokens.TypographyStyle, _ weight: Font.Weight? = nil, size: CGFloat? = nil,
              lineHeight: CGFloat? = nil, color: Color, lines: Int? = nil, align: TextAlignment = .leading,
              tracking: CGFloat? = nil) -> some View {
        let sz = size ?? style.size.cg
        return text(s, gsf(sz, weight ?? Font.Weight(gsf: style.weight)), size: sz,
                    lineHeight: lineHeight ?? style.lineHeight.cg, color: color,
                    tracking: tracking ?? style.letterSpacing.cg, lines: lines, align: align)
    }

    /// An AI paragraph: its **bold** spans bold (as Now Playing's About draws them), the rest in [font].
    func paragraph(_ s: String, _ font: Font, bold: Font, size: CGFloat, lineHeight: CGFloat, color: Color,
                   tracking: CGFloat = 0) -> some View {
        var a = NowPlayingAboutView.markdown(s)
        for run in a.runs {
            a[run.range].font = run.inlinePresentationIntent?.contains(.stronglyEmphasized) == true ? bold : font
        }
        return Text(a)
            .tracking(tracking)
            .foregroundStyle(color)
            .homeLine(size, lineHeight)
            .fixedSize(horizontal: false, vertical: true)
    }

    /// Hero text: GSF rounded SemiBold at [size], [lineHeight] × the size per line.
    func hero(_ s: String, size: CGFloat, lineHeight: CGFloat, color: Color, semibold: Bool = true) -> some View {
        text(s, rounded(size, semibold: semibold), size: size, lineHeight: size * lineHeight, color: color)
    }
}

// MARK: - The image

/// One share image at its logical size in [format] and [tone], on the playing [scheme].
struct ShareImage: View {
    var render: ShareRender
    var layout: ShareLayout
    var format: ShareFormat
    var tone: ShareTone
    var scheme: CoverScheme

    var body: some View {
        let colors = tone.colors(scheme, role: layout.tab.role)
        Group {
            if format == .story {
                ShareStory(ctx: ctx(.storyCard, colors))
                    .frame(width: ShareMetrics.story.width, height: ShareMetrics.story.height)
            } else {
                ShareBody(ctx: ctx(.card, colors))
                    .frame(width: ShareMetrics.cardWidth)
                    .fixedSize(horizontal: false, vertical: true)
                    // The ground reaches a hair past the edges: a height that isn't a whole pixel never leaves a
                    // see-through last row in the PNG.
                    .background { (layout == .noteclipping ? scheme.surface : colors.card).padding(-1) }
            }
        }
        .environment(\.dynamicTypeSize, .large)
        .environment(\.layoutDirection, .leftToRight)
    }

    private func ctx(_ frame: ShareFrame, _ colors: ShareCardColors) -> ShareCtx {
        let c = render.content
        var words = [c.song.title, c.song.artist]
        words += (c.lyrics?.lines ?? []).map(\.text)
        if let n = c.note { words.append(NoteMarkupRender.words(n.content)) }
        if let a = c.about { words += [a.paragraph ?? ""] + a.credits.map(\.value) }
        return ShareCtx(render: render, layout: layout, format: format, frame: frame, colors: colors, scheme: scheme,
                        script: YoinCJKScript.detect(forLines: words))
    }
}

/// The 9:16 story: a poster edge to edge, or the card centred on the tone's backdrop, zoomed to fit.
private struct ShareStory: View {
    let ctx: ShareCtx

    var body: some View {
        let poster = ctx.layout.storyIsPoster
        let paper = ctx.layout == .noteclipping
        let maxZoom = CGFloat(YoinShareCards.shared.storyMaxZoom(layout: ctx.layout))
        let inner = ShareCtx(render: ctx.render, layout: ctx.layout, format: ctx.format,
                             frame: poster ? .poster : .storyCard, colors: ctx.colors, scheme: ctx.scheme,
                             script: ctx.script)
        ZStack {
            Rectangle().fill(poster ? (paper ? ctx.scheme.surface : ctx.colors.card) : ctx.colors.backdrop)
            if paper { ShareDotPaper(dot: ctx.scheme.outlineVariant) }
            // A long note or pick zooms out rather than ever losing a line; a poster's foot sits at the bottom.
            StoryZoomLayout(frame: ShareMetrics.storyFrame, maxZoom: maxZoom, fillHeight: poster) {
                ShareBody(ctx: inner).zoomedToFit(ShareMetrics.storyFrame)
            }
            .frame(width: ShareMetrics.storyFrame.width, height: ShareMetrics.storyFrame.height)
        }
    }
}

/// Lays its content out at the frame's width / zoom (wider when zooming out, so lines wrap less) and has it
/// drawn back at the zoom ([View.zoomedToFit]): the largest zoom up to [maxZoom] whose height fits, by the shared
/// search (`YoinShareCards.storyZoom`, `storyFits`). A plain shrink would narrow a card to a sliver for a long
/// pick; this keeps it the frame's width. All in one layout pass, so `ImageRenderer` draws it settled.
/// [fillHeight] stretches the content to the frame's height at that zoom (a poster's foot sits at the bottom).
struct StoryZoomLayout: Layout {
    var frame: CGSize
    var maxZoom: CGFloat
    var fillHeight: Bool

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize { frame }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        guard let child = subviews.first else { return }
        let placed = Self.resolve(frame: frame, maxZoom: maxZoom, fillHeight: fillHeight) { width in
            child.sizeThatFits(ProposedViewSize(width: width, height: nil)).height
        }
        child.place(at: CGPoint(x: bounds.midX, y: bounds.midY), anchor: .center,
                    proposal: ProposedViewSize(width: placed.size.width, height: placed.size.height))
    }

    /// The zoom and the size the content is laid out at (before it is drawn back at the zoom). [measure] gives
    /// the content's height at a width.
    static func resolve(frame: CGSize, maxZoom: CGFloat, fillHeight: Bool,
                        measure: @escaping (CGFloat) -> CGFloat) -> (zoom: CGFloat, size: CGSize) {
        let cards = YoinShareCards.shared
        let zoom = CGFloat(cards.storyZoom(maxZoom: Float(maxZoom)) { z in
            let height = measure(frame.width / CGFloat(z.floatValue))
            let px = height.isFinite ? Int32(min(max(height.rounded(.up), 0), CGFloat(Int32.max))) : Int32.max
            return KotlinBoolean(bool: cards.storyFits(heightPx: px, zoom: z.floatValue,
                                                       frameHeightPx: Int32(frame.height)))
        })
        let width = frame.width / zoom
        let height = measure(width)
        return (zoom, CGSize(width: width, height: fillHeight ? max(height, frame.height / zoom) : height))
    }
}

extension View {
    /// Drawn back at the zoom [StoryZoomLayout] laid it out for: the frame's width over the width it was given;
    /// even the furthest zoom can fall short (a pathological pick), and then the height decides.
    func zoomedToFit(_ frame: CGSize) -> some View {
        visualEffect { content, proxy in
            let size = proxy.size
            let scale = size.width > 0 && size.height > 0 ? min(frame.width / size.width, frame.height / size.height) : 1
            return content.scaleEffect(scale)
        }
    }
}

// MARK: - Bodies

private struct ShareBody: View {
    let ctx: ShareCtx

    var body: some View {
        switch ctx.layout {
        case .lyricsclassic: ClassicBody(ctx: ctx)
        case .lyricsposter: PosterBody(ctx: ctx)
        case .lyricsplayer: PlayerBody(ctx: ctx)
        case .lyricsvertical: VerticalBody(ctx: ctx)
        case .aboutreceipt: ReceiptBody(ctx: ctx)
        case .aboutquote: QuoteBody(ctx: ctx)
        case .aboutliner: LinerBody(ctx: ctx)
        case .notebig: NoteBigBody(ctx: ctx)
        case .noteclipping: ClippingBody(ctx: ctx)
        default: TimelineBody(ctx: ctx)
        }
    }
}

extension View {
    /// The body's own ground: the card colour edge to edge (Card), a Panel-rounded card (Story), none (poster).
    @ViewBuilder
    fileprivate func frameGround(_ frame: ShareFrame, _ card: Color, padding: CGFloat = ShareMetrics.cardPadding) -> some View {
        switch frame {
        case .card: self.padding(padding).background(card)
        case .storyCard:
            self.padding(padding)
                .background(card, in: RoundedRectangle(cornerRadius: ShareMetrics.panel, style: .continuous))
        case .poster: self
        }
    }
}

// MARK: Shared pieces

/// A cover (loaded before the image draws); a quiet tile of the ink without one.
private struct ShareCover: View {
    var image: UIImage?
    var size: CGFloat
    var tint: Color
    var corner: CGFloat = CGFloat(Tokens.shape.THUMB)

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: corner, style: .continuous)
        Group {
            if let image {
                Image(uiImage: image).resizable().scaledToFill()
            } else {
                shape.fill(tint.opacity(0.16))
            }
        }
        .frame(width: size, height: size)
        .clipShape(shape)
    }
}

/// Cover + title + artist, as on the lyrics card.
private struct ShareHead: View {
    let ctx: ShareCtx
    var coverSize: CGFloat = 44
    var trailing: String? = nil

    var body: some View {
        HStack(spacing: 12) {
            ShareCover(image: ctx.render.cover, size: coverSize, tint: ctx.colors.content)
            VStack(alignment: .leading, spacing: 0) {
                ctx.role(ctx.song.title, Tokens.type.titleSmall, .semibold, color: ctx.colors.content, lines: 1)
                let by = [ctx.song.artist.isBlank ? nil : ctx.song.artist, trailing].compactMap { $0 }
                    .joined(separator: " · ")
                if !by.isEmpty {
                    ctx.role(by, Tokens.type.bodySmall, color: ctx.colors.secondary, lines: 1)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

/// The app's monochrome mark, scaled to fill its slot.
private struct ShareMark: View {
    var tint: Color
    var size: CGFloat = 22

    var body: some View {
        YoinMonochromeMark()
            .fill(tint, style: FillStyle(eoFill: true))
            .frame(width: size * YoinMonochromeMark.glyphScale, height: size * YoinMonochromeMark.glyphScale)
            .frame(width: size, height: size)
    }
}

/// The mark + "Yoin", then, at the end, who wrote the words ("由 Gemini 整理", "写于 …").
private struct ShareFoot: View {
    let ctx: ShareCtx
    var sign: AnyView? = nil
    var ink: Color? = nil

    var body: some View {
        let ink = ink ?? ctx.colors.content
        HStack(spacing: 0) {
            ShareMark(tint: ink)
            ctx.role(ShareStrings.brand, Tokens.type.labelLarge, .semibold, color: ink, lines: 1)
                .padding(.leading, 6)
                .fixedSize()
            // The sign keeps its distance from the mark and wraps (right-aligned) rather than ever being cut: a
            // story zoomed in lays the card out narrower.
            if let sign {
                sign.frame(maxWidth: .infinity, alignment: .trailing).padding(.leading, ShareMetrics.signGap)
            } else {
                Spacer(minLength: 0)
            }
        }
        .frame(maxWidth: .infinity)
    }
}

/// "✦ 由 Gemini 整理": the active AI service signs About's words; none over the service's credits alone.
@MainActor
private func aiSign(_ ctx: ShareCtx) -> AnyView? {
    guard ctx.render.content.about?.aiContent == true else { return nil }
    return AnyView(HStack(spacing: 4) {
        YoinIcon("sparkle", size: 14).foregroundStyle(ctx.colors.secondary)
        ctx.role(ShareStrings.aiSign(ctx.render.aiName), Tokens.type.labelMedium, .regular, color: ctx.colors.secondary,
                 align: .trailing)
    })
}

/// "写于 2026年7月18日": the note's date signs the listener's words.
@MainActor
private func noteSign(_ ctx: ShareCtx, _ note: ShareNote) -> AnyView {
    AnyView(ctx.role(ShareStrings.written(ShareStrings.noteDate(note.createdAt, locale: ctx.render.locale)),
                     Tokens.type.labelMedium, .regular, color: ctx.colors.secondary, align: .trailing))
}

/// ▶ 3:46 (and an optional end, quieter) on a 12% pill of the ink.
private struct ShareTimeChip: View {
    let ctx: ShareCtx
    var text: String
    var end: String? = nil

    var body: some View {
        HStack(spacing: 0) {
            YoinIcon("play_filled", size: 12).foregroundStyle(ctx.colors.content)
            Text(verbatim: text).font(ctx.gsf(13, .semibold)).foregroundStyle(ctx.colors.content)
                .padding(.leading, 4)
            if let end {
                Text(verbatim: " – \(end)").font(ctx.gsf(13, .medium)).foregroundStyle(ctx.colors.content.opacity(0.6))
            }
        }
        .lineLimit(1)
        .fixedSize()
        .padding(.leading, 8)
        .padding(.trailing, 10)
        .frame(height: 26)
        .background(ctx.colors.content.opacity(0.12), in: Capsule())
    }
}

/// Places its child so the child's centre sits at [fraction] of the width, kept inside it.
private struct CentredAtLayout: Layout {
    var fraction: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let child = subviews.first?.sizeThatFits(.unspecified) ?? .zero
        return CGSize(width: proposal.width ?? child.width, height: child.height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        guard let child = subviews.first else { return }
        let size = child.sizeThatFits(.unspecified)
        let x = min(max(bounds.width * fraction - size.width / 2, 0), max(bounds.width - size.width, 0))
        child.place(at: CGPoint(x: bounds.minX + x.rounded(.down), y: bounds.minY), proposal: ProposedViewSize(size))
    }
}

/// A child no wider than [maxWidth], as wide as it wants below that (Android widthIn(max)).
private struct MaxWidthLayout: Layout {
    var maxWidth: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = min(proposal.width ?? maxWidth, maxWidth)
        return subviews.first?.sizeThatFits(ProposedViewSize(width: width, height: nil)) ?? .zero
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        subviews.first?.place(at: bounds.origin, proposal: ProposedViewSize(bounds.size))
    }
}

/// The receipt's dash (About): 4 on, 3 off, 1 pt.
private struct DashedRule: View {
    var color: Color

    var body: some View {
        Canvas { gc, size in
            var p = Path()
            p.move(to: CGPoint(x: 0, y: size.height / 2))
            p.addLine(to: CGPoint(x: size.width, y: size.height / 2))
            gc.stroke(p, with: .color(color), style: StrokeStyle(lineWidth: 1, dash: [4, 3]))
        }
        .frame(height: 1)
    }
}

/// The scrapbook's dot paper: a 16 pt grid of 1.2 pt dots in outlineVariant.
private struct ShareDotPaper: View {
    var dot: Color

    var body: some View {
        Canvas { gc, size in
            let step: CGFloat = 16, r: CGFloat = 1.2
            var y = step / 2
            while y < size.height {
                var x = step / 2
                while x < size.width {
                    gc.fill(Path(ellipseIn: CGRect(x: x - r, y: y - r, width: 2 * r, height: 2 * r)),
                            with: .color(dot.opacity(0.7)))
                    x += step
                }
                y += step
            }
        }
    }
}

/// The lyric lines (Classic, Player): each in the card's line style, its translation under it when shown.
private struct LyricLines: View {
    let ctx: ShareCtx
    let pick: ShareLyrics
    var passages = true

    var body: some View {
        let size = CGFloat(YoinShareCards.shared.cardLineSizeSp(lines: pick.lines.map(\.text)))
        let starts = Set(pick.passageStarts.map(\.intValue))
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(pick.lines.enumerated()), id: \.offset) { i, line in
                // A skipped stretch of the song reads as a stanza break.
                let gap: CGFloat = i == 0 ? 0 : (passages && starts.contains(i) ? 20 : 6)
                VStack(alignment: .leading, spacing: 0) {
                    ctx.hero(line.text, size: size, lineHeight: 1.3, color: ctx.colors.content)
                    if pick.showTranslation, let t = line.translation, !t.isBlank {
                        ctx.role(t, Tokens.type.bodyMedium, .medium,
                                 color: ctx.colors.content.opacity(ShareMetrics.translationAlpha))
                            .padding(.top, 2)
                    }
                }
                .padding(.top, gap)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

// MARK: Lyrics

/// Classic: cover + title, the lines in the rounded cut the current lyric line uses, the mark.
private struct ClassicBody: View {
    let ctx: ShareCtx

    var body: some View {
        if let pick = ctx.render.content.lyrics {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 12) {
                    if ctx.render.hasCover {
                        ShareCover(image: ctx.render.cover, size: 44, tint: ctx.colors.content)
                    }
                    VStack(alignment: .leading, spacing: 0) {
                        ctx.role(ctx.song.title, Tokens.type.titleSmall, .semibold, color: ctx.colors.content, lines: 1)
                        if !ctx.song.artist.isBlank {
                            ctx.role(ctx.song.artist, Tokens.type.bodySmall, color: ctx.colors.secondary, lines: 1)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                LyricLines(ctx: ctx, pick: pick).padding(.top, 20)
                HStack(spacing: 6) {
                    ShareMark(tint: ctx.colors.content)
                    ctx.role(ShareStrings.brand, Tokens.type.labelLarge, .semibold, color: ctx.colors.content, lines: 1)
                }
                .padding(.top, 24)
            }
            .frameGround(ctx.frame, ctx.colors.card)
        }
    }
}

/// Poster: the first line at display size, up to three more small, the head at the foot, the translations on one
/// quiet line at the very bottom.
private struct PosterBody: View {
    let ctx: ShareCtx

    var body: some View {
        if let pick = ctx.render.content.lyrics, let poster = YoinShareCards.shared.poster(pick: pick) {
            let story = ctx.frame == .poster
            let lead = CGFloat(poster.leadSizeSp) + (story ? CGFloat(YoinShareCards.shared.posterStoryLeadBoostSp) : 0)
            VStack(alignment: .leading, spacing: 0) {
                VStack(alignment: .leading, spacing: 0) {
                    Text(verbatim: "“")
                        .font(.system(size: 72, weight: .bold, design: .serif))
                        .foregroundStyle(ctx.colors.content.opacity(0.35))
                        .fixedSize()
                        .frame(height: 44, alignment: .top)
                    ctx.hero(poster.lead.text, size: lead, lineHeight: 1.18, color: ctx.colors.content)
                        .padding(.top, 8)
                    ForEach(Array(poster.rest.enumerated()), id: \.offset) { i, line in
                        ctx.hero(line.text, size: story ? 20 : 18, lineHeight: 1.35,
                                 color: ctx.colors.content.opacity(0.82), semibold: false)
                            .padding(.top, i == 0 ? 16 : 4)
                    }
                }
                Spacer(minLength: 0)
                VStack(alignment: .leading, spacing: 0) {
                    HStack(spacing: 0) {
                        ShareCover(image: ctx.render.cover, size: 44, tint: ctx.colors.content)
                        VStack(alignment: .leading, spacing: 0) {
                            ctx.role(ctx.song.title, Tokens.type.titleSmall, .semibold, color: ctx.colors.content,
                                     lines: 1)
                            let by = [ctx.song.artist.isBlank ? nil : ctx.song.artist, poster.leadTime]
                                .compactMap { $0 }.joined(separator: " · ")
                            ctx.role(by, Tokens.type.bodySmall, color: ctx.colors.secondary, lines: 1)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.leading, 12)
                        ShareMark(tint: ctx.colors.content)
                    }
                    // The translation: one small, quiet line at the very bottom (owner 2026-11-25).
                    if let translation = poster.translation {
                        ctx.role(translation, Tokens.type.bodySmall,
                                 color: ctx.colors.content.opacity(ShareMetrics.quiet))
                            .padding(.top, 14)
                    }
                }
                .padding(.top, 28)
            }
            .frame(maxWidth: .infinity, minHeight: ctx.frame == .card ? ShareMetrics.posterCardMinHeight : nil,
                   maxHeight: story ? .infinity : nil, alignment: .topLeading)
            .frameGround(ctx.frame, ctx.colors.card, padding: ShareMetrics.posterPadding)
        }
    }
}

/// Player: the lines over a progress bar marking where they sit in the song.
private struct PlayerBody: View {
    let ctx: ShareCtx

    var body: some View {
        if let pick = ctx.render.content.lyrics {
            let duration = ctx.song.durationMs
            VStack(alignment: .leading, spacing: 0) {
                ShareHead(ctx: ctx)
                // The lines alone — the per-line times are gone (owner 2026-11-25); the bar says where they are.
                LyricLines(ctx: ctx, pick: pick, passages: false).padding(.top, 20)
                if let excerpt = YoinShareCards.shared.playerExcerpt(pick: pick, durationMs: duration) {
                    PlayerBar(ctx: ctx, excerpt: excerpt, durationMs: duration).padding(.top, 18)
                }
                ShareFoot(ctx: ctx).padding(.top, 24)
            }
            .frameGround(ctx.frame, ctx.colors.card)
        }
    }
}

/// The track, the excerpt as a solid bar (≥ 6% of it), its time range in a chip above, 0:00 / length.
private struct PlayerBar: View {
    let ctx: ShareCtx
    let excerpt: ShareExcerpt
    let durationMs: Int64

    var body: some View {
        let cards = YoinShareCards.shared
        let start = CGFloat(excerpt.start), end = CGFloat(excerpt.end)
        VStack(spacing: 0) {
            CentredAtLayout(fraction: CGFloat(excerpt.centre)) {
                ShareTimeChip(ctx: ctx, text: cards.time(ms: excerpt.startMs), end: cards.time(ms: excerpt.endMs))
            }
            .frame(maxWidth: .infinity, minHeight: 34, maxHeight: 34, alignment: .topLeading)
            Canvas { gc, size in
                let track: CGFloat = 4
                gc.fill(Path(roundedRect: CGRect(x: 0, y: (size.height - track) / 2, width: size.width, height: track),
                             cornerRadius: track / 2), with: .color(ctx.colors.content.opacity(0.24)))
                gc.fill(Path(roundedRect: CGRect(x: size.width * start, y: 0, width: size.width * (end - start),
                                                 height: size.height), cornerRadius: size.height / 2),
                        with: .color(ctx.colors.content))
            }
            .frame(height: 8)
            Ends(ctx: ctx, durationMs: durationMs).padding(.top, 6)
        }
    }
}

/// 0:00 and the song's length under a bar.
private struct Ends: View {
    let ctx: ShareCtx
    let durationMs: Int64

    var body: some View {
        HStack {
            Text(verbatim: YoinShareCards.shared.time(ms: 0))
            Spacer(minLength: 8)
            Text(verbatim: YoinShareCards.shared.time(ms: durationMs))
        }
        .font(ctx.gsf(11, .medium))
        .foregroundStyle(ctx.colors.content.opacity(0.6))
        .lineLimit(1)
    }
}

/// 竖排: CJK lines set top to bottom, right to left; the title and artist beside them; the translation as the
/// 「歌词大意」 footnote, the far left column.
private struct VerticalBody: View {
    let ctx: ShareCtx

    var body: some View {
        if let pick = ctx.render.content.lyrics {
            let cards = YoinShareCards.shared
            let translations = cards.verticalGist(pick: pick)
            let plan = cards.verticalLyricsPlan(pick: pick)
            let size = CGFloat(plan.fontSizeSp)
            let columnHeight = CGFloat(cards.verticalColumnHeightDp)
            VStack(alignment: .leading, spacing: 0) {
                HStack(alignment: .top, spacing: 0) {
                    if !translations.isEmpty {
                        VerticalText(ctx: ctx, text: ShareStrings.gist(translations), size: 11,
                                     color: ctx.colors.content.opacity(ShareMetrics.quiet), columnHeight: columnHeight,
                                     weight: .regular)
                            .padding(.trailing, 10)
                    }
                    // Read right to left: the title, then the artist to its left.
                    if !ctx.song.artist.isBlank {
                        VerticalText(ctx: ctx, text: ctx.song.artist, size: 12, color: ctx.colors.secondary,
                                     columnHeight: columnHeight, weight: .regular)
                            .padding(.trailing, 4)
                    }
                    VerticalText(ctx: ctx, text: ctx.song.title, size: 14, color: ctx.colors.content,
                                 columnHeight: columnHeight, weight: .semibold)
                    Spacer(minLength: 0)
                    // Right to left: the first line is the rightmost column.
                    HStack(alignment: .top, spacing: size * CGFloat(cards.verticalColumnAdvance - 1)) {
                        ForEach(Array(plan.columns.reversed().enumerated()), id: \.offset) { _, column in
                            VerticalColumn(ctx: ctx, text: column, size: size, color: ctx.colors.content,
                                           weight: .semibold, rounded: true)
                        }
                    }
                }
                .frame(maxWidth: .infinity)
                Spacer(minLength: 0)
                HStack(spacing: 0) {
                    ShareCover(image: ctx.render.cover, size: 36, tint: ctx.colors.content)
                    Spacer(minLength: 0)
                    ShareMark(tint: ctx.colors.content)
                    ctx.role(ShareStrings.brand, Tokens.type.labelLarge, .semibold, color: ctx.colors.content, lines: 1)
                        .padding(.leading, 6)
                }
                .padding(.top, 20)
            }
            .frame(maxWidth: .infinity, minHeight: ctx.frame == .card ? ShareMetrics.verticalCardMinHeight : nil,
                   maxHeight: ctx.frame == .poster ? .infinity : nil, alignment: .topLeading)
            .frameGround(ctx.frame, ctx.colors.card, padding: ShareMetrics.posterPadding)
        }
    }
}

/// Short text set vertically: CJK stacked character by character, anything else turned on its side.
private struct VerticalText: View {
    let ctx: ShareCtx
    var text: String
    var size: CGFloat
    var color: Color
    var columnHeight: CGFloat
    var weight: Font.Weight

    var body: some View {
        if let columns = YoinShareCards.shared.verticalTextColumns(text: text, sizeSp: Float(size),
                                                                   columnHeightDp: Float(columnHeight)) {
            HStack(alignment: .top, spacing: size * 0.3) {
                ForEach(Array(columns.reversed().enumerated()), id: \.offset) { _, column in
                    VerticalColumn(ctx: ctx, text: column, size: size, color: color, weight: weight, rounded: false)
                }
            }
        } else {
            SidewaysLayout(maxLength: columnHeight) {
                Text(verbatim: text).font(ctx.gsf(size, weight)).foregroundStyle(color)
                    .fixedSize(horizontal: false, vertical: true)
                    .rotationEffect(.degrees(90))
            }
        }
    }
}

/// One column of characters, top to bottom, CJK punctuation in its vertical form.
private struct VerticalColumn: View {
    let ctx: ShareCtx
    var text: String
    var size: CGFloat
    var color: Color
    var weight: Font.Weight
    var rounded: Bool

    var body: some View {
        let font = rounded ? ctx.rounded(size, semibold: weight == .semibold) : ctx.gsf(size, weight)
        let shown = YoinShareCards.shared.verticalText(text: text)
        VStack(spacing: 0) {
            ForEach(Array(shown.enumerated()), id: \.offset) { _, c in
                Text(verbatim: String(c)).font(font).foregroundStyle(color).lineLimit(1).fixedSize()
                    .frame(width: size * 1.1, height: size * CGFloat(YoinShareCards.shared.verticalCharAdvance))
            }
        }
    }
}

/// Lays a line of text on its side (reading top to bottom), [maxLength] long at most.
private struct SidewaysLayout: Layout {
    var maxLength: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let s = subviews.first?.sizeThatFits(ProposedViewSize(width: maxLength, height: nil)) ?? .zero
        return CGSize(width: s.height, height: s.width)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        guard let child = subviews.first else { return }
        let s = child.sizeThatFits(ProposedViewSize(width: maxLength, height: nil))
        child.place(at: CGPoint(x: bounds.midX, y: bounds.midY), anchor: .center, proposal: ProposedViewSize(s))
    }
}

// MARK: About (tertiary)

/// Receipt: the credits as a receipt (dashed rules), then the paragraph.
private struct ReceiptBody: View {
    let ctx: ShareCtx

    var body: some View {
        if let about = ctx.render.content.about {
            VStack(alignment: .leading, spacing: 0) {
                ShareHead(ctx: ctx)
                if !about.credits.isEmpty {
                    VStack(spacing: 0) {
                        ForEach(Array(about.credits.enumerated()), id: \.offset) { i, credit in
                            if i > 0 { DashedRule(color: ctx.colors.content.opacity(0.3)) }
                            HStack(alignment: .top, spacing: 0) {
                                ctx.role(credit.label, Tokens.type.labelMedium, color: ctx.colors.secondary)
                                    .padding(.top, 2)
                                    .padding(.trailing, 12)
                                    .frame(width: 88, alignment: .leading)
                                ctx.role(credit.value, Tokens.type.bodyMedium, .medium, color: ctx.colors.content,
                                         align: .trailing)
                                    .frame(maxWidth: .infinity, alignment: .trailing)
                            }
                            .padding(.vertical, 8)
                        }
                    }
                    .padding(.top, 12)
                }
                if let paragraph = about.paragraph {
                    ctx.role(about.heading, Tokens.type.titleSmall, .semibold, color: ctx.colors.content)
                        .padding(.top, 16)
                    ctx.paragraph(paragraph, ctx.gsf(14), bold: ctx.gsf(14, .bold), size: 14, lineHeight: 22,
                                  color: ctx.colors.content, tracking: Tokens.type.bodyMedium.letterSpacing.cg)
                        .padding(.top, 6)
                }
                ShareFoot(ctx: ctx, sign: aiSign(ctx)).padding(.top, 24)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .frameGround(ctx.frame, ctx.colors.card)
        }
    }
}

/// Quote: the paragraph as the hero, the credits as chips.
private struct QuoteBody: View {
    let ctx: ShareCtx

    var body: some View {
        if let about = ctx.render.content.about {
            VStack(alignment: .leading, spacing: 0) {
                // The tag: ✦ + the paragraph's heading, inverted.
                HStack(spacing: 5) {
                    YoinIcon("sparkle", size: 14).foregroundStyle(ctx.colors.card)
                    ctx.role(about.heading, Tokens.type.labelLarge, .semibold, color: ctx.colors.card, lines: 1)
                }
                .padding(.leading, 10)
                .padding(.trailing, 12)
                .frame(height: 28)
                .background(ctx.colors.content, in: Capsule())
                if let paragraph = about.paragraph {
                    ctx.paragraph(paragraph, ctx.rounded(19), bold: ctx.roundedBold(19), size: 19, lineHeight: 19 * 1.5,
                                  color: ctx.colors.content)
                        .padding(.top, 18)
                }
                if !about.credits.isEmpty {
                    // The chips flow onto as many lines as they need, each as wide as its whole value (owner
                    // 2026-11-26: the AI's credits run long and were cut to "Re…"; Android #360 FlowRow).
                    ChipFlow(spacing: 6, lineSpacing: 6) {
                        ForEach(Array(about.credits.prefix(Int(YoinShareCards.shared.quoteCredits)).enumerated()),
                                id: \.offset) { _, credit in
                            CreditChip(ctx: ctx, credit: credit)
                        }
                    }
                    .padding(.top, 18)
                }
                Rectangle().fill(ctx.colors.content.opacity(0.18)).frame(height: 1)
                    .padding(.top, 20).padding(.bottom, 16)
                ShareHead(ctx: ctx, coverSize: 32)
                ShareFoot(ctx: ctx, sign: aiSign(ctx)).padding(.top, 24)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .frameGround(ctx.frame, ctx.colors.card)
        }
    }
}

/// A credit as a chip: the label, then the whole value — a value wider than the card wraps inside the chip.
private struct CreditChip: View {
    let ctx: ShareCtx
    let credit: ShareCredit

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 6) {
            ctx.role(credit.label, Tokens.type.labelSmall, color: ctx.colors.secondary, lines: 1).fixedSize()
            ctx.role(credit.value, Tokens.type.labelLarge, .semibold, color: ctx.colors.content)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 5)
        .background(ctx.colors.content.opacity(0.1), in: RoundedRectangle(cornerRadius: 8, style: .continuous))
    }
}

/// Rows of chips, wrapping when the width runs out (Android FlowRow). Each chip is offered the whole row's width,
/// so one wider than the card wraps its value inside itself instead of being cut.
private struct ChipFlow: Layout {
    var spacing: CGFloat
    var lineSpacing: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        let rows = arrange(subviews, width: width)
        let used = rows.map(\.width).max() ?? 0
        let height = rows.map(\.height).reduce(0, +) + lineSpacing * CGFloat(max(0, rows.count - 1))
        return CGSize(width: proposal.width.map { min($0, used) } ?? used, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var y = bounds.minY
        for row in arrange(subviews, width: bounds.width) {
            var x = bounds.minX
            for (i, size) in zip(row.items, row.sizes) {
                subviews[i].place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
                x += size.width + spacing
            }
            y += row.height + lineSpacing
        }
    }

    private struct Row { var items: [Int] = []; var sizes: [CGSize] = []; var width: CGFloat = 0; var height: CGFloat = 0 }

    private func arrange(_ subviews: Subviews, width: CGFloat) -> [Row] {
        var rows: [Row] = []
        var row = Row()
        for i in subviews.indices {
            let size = subviews[i].sizeThatFits(ProposedViewSize(width: width.isFinite ? width : nil, height: nil))
            if !row.items.isEmpty, row.width + spacing + size.width > width {
                rows.append(row)
                row = Row()
            }
            row.width = row.items.isEmpty ? size.width : row.width + spacing + size.width
            row.height = max(row.height, size.height)
            row.items.append(i)
            row.sizes.append(size)
        }
        if !row.items.isEmpty { rows.append(row) }
        return rows
    }
}

/// Liner notes: a large title, the credits in a 2 × 2 grid, the paragraph.
private struct LinerBody: View {
    let ctx: ShareCtx

    var body: some View {
        if let about = ctx.render.content.about {
            let cells = Array(about.credits.prefix(Int(YoinShareCards.shared.linerCredits)))
            VStack(alignment: .leading, spacing: 0) {
                VStack(alignment: .leading, spacing: 0) {
                    // The head (owner 2026-11-26, Android #360): "About" as written (sentence case, 关于) sits right
                    // on the title, and the cover's top lines up with the eyebrow + title + artist block beside it.
                    HStack(alignment: .top, spacing: 16) {
                        VStack(alignment: .leading, spacing: 0) {
                            ctx.role(about.heading, Tokens.type.labelLarge, .semibold, color: ctx.colors.secondary)
                            ctx.hero(ctx.song.title, size: 36, lineHeight: 1.12, color: ctx.colors.content)
                                .padding(.top, 2)
                            if !ctx.song.artist.isBlank {
                                ctx.role(ctx.song.artist, Tokens.type.bodyMedium, color: ctx.colors.secondary)
                                    .padding(.top, 6)
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        ShareCover(image: ctx.render.cover, size: 72, tint: ctx.colors.content, corner: 8)
                    }
                    if !cells.isEmpty {
                        VStack(spacing: 0) {
                            ForEach(Array(stride(from: 0, to: cells.count, by: 2)), id: \.self) { i in
                                HStack(alignment: .top, spacing: 16) {
                                    LinerCell(ctx: ctx, credit: cells[i])
                                    if i + 1 < cells.count {
                                        LinerCell(ctx: ctx, credit: cells[i + 1])
                                    } else {
                                        Color.clear.frame(maxWidth: .infinity, maxHeight: 0)
                                    }
                                }
                            }
                        }
                        .padding(.top, 20)
                    }
                    if let paragraph = about.paragraph {
                        ctx.paragraph(paragraph, ctx.gsf(14), bold: ctx.gsf(14, .bold), size: 14, lineHeight: 22,
                                      color: ctx.colors.content, tracking: Tokens.type.bodyMedium.letterSpacing.cg)
                            .padding(.top, 12)
                    }
                }
                Spacer(minLength: 0)
                ShareFoot(ctx: ctx, sign: aiSign(ctx)).padding(.top, 24)
            }
            .frame(maxWidth: .infinity, maxHeight: ctx.frame == .poster ? .infinity : nil, alignment: .topLeading)
            .frameGround(ctx.frame, ctx.colors.card)
        }
    }
}

private struct LinerCell: View {
    let ctx: ShareCtx
    let credit: ShareCredit

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ctx.role(credit.label, Tokens.type.labelSmall, color: ctx.colors.secondary)
            ctx.role(credit.value, Tokens.type.titleMedium, .semibold, color: ctx.colors.content).padding(.top, 2)
        }
        .padding(.top, 10)
        .padding(.bottom, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .overlay(alignment: .top) { Rectangle().fill(ctx.colors.content.opacity(0.22)).frame(height: 1) }
    }
}

// MARK: Note (primary)

/// The width a note's text is laid out in, for a mention pill to fit: the body's width less [inset]. A Story
/// card may zoom in to its max (the narrowest layout), so its pills are cut for that.
@MainActor
private func noteLineWidth(_ ctx: ShareCtx, inset: CGFloat) -> CGFloat {
    let body: CGFloat = switch ctx.format {
    case .story: ShareMetrics.storyFrame.width / CGFloat(YoinShareCards.shared.storyMaxZoom(layout: ctx.layout))
    default: ShareMetrics.cardWidth
    }
    return body - inset
}

/// Big type: the note in large type, like the lyrics card.
private struct NoteBigBody: View {
    let ctx: ShareCtx

    var body: some View {
        if let note = ctx.render.content.note {
            VStack(alignment: .leading, spacing: 0) {
                ShareHead(ctx: ctx)
                if let at = note.positionMs?.int64Value {
                    ShareTimeChip(ctx: ctx, text: YoinShareCards.shared.time(ms: at)).padding(.top, 20)
                }
                // Lighter than a lyric line, so the note's own **bold** reads.
                ShareNoteText(text: note.content, size: 21, weight: .regular, lineHeight: 1.38,
                              color: ctx.colors.content, lineWidth: noteLineWidth(ctx, inset: 48),
                              covers: ctx.render.mentionCovers)
                    .padding(.top, note.positionMs != nil ? 12 : 20)
                ShareFoot(ctx: ctx, sign: noteSign(ctx, note)).padding(.top, 24)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .frameGround(ctx.frame, ctx.colors.card)
        }
    }
}

/// Clipping: the note as a scrapbook clipping on dot paper, with a ticket stub. The paper follows the app's
/// theme (surface, outlineVariant dots, onSurface foot); the tone colours the clipping itself.
private struct ClippingBody: View {
    let ctx: ShareCtx

    var body: some View {
        if let note = ctx.render.content.note {
            let s = ctx.scheme
            let story = ctx.frame == .poster
            VStack(alignment: .leading, spacing: 0) {
                VStack(alignment: .leading, spacing: 0) {
                    HStack(alignment: .bottom, spacing: 0) {
                        ShareCover(image: ctx.render.cover, size: story ? 136 : 104, tint: s.onSurface, corner: 6)
                            .rotationEffect(.degrees(-3))
                        TicketStub(ctx: ctx, note: note, story: story)
                    }
                    VStack(alignment: .leading, spacing: 0) {
                        ShareNoteText(text: note.content, size: story ? 18 : 16, weight: .medium, lineHeight: 1.6,
                                      color: ctx.colors.content, lineWidth: noteLineWidth(ctx, inset: story ? 36 : 84),
                                      covers: ctx.render.mentionCovers)
                        ctx.role(ShareStrings.noteDate(note.createdAt, locale: ctx.render.locale), Tokens.type.labelMedium,
                                 .regular, color: ctx.colors.secondary, align: .trailing)
                            .frame(maxWidth: .infinity, alignment: .trailing)
                            .padding(.top, 6)
                    }
                    .padding(.leading, 18)
                    .padding(.trailing, 18)
                    .padding(.top, 16)
                    .padding(.bottom, 14)
                    .background(ctx.colors.card, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                    .rotationEffect(.degrees(1))
                    .padding(.top, story ? 18 : 14)
                }
                Spacer(minLength: 0)
                ShareFoot(ctx: ctx, ink: s.onSurface).padding(.top, 24)
            }
            .frame(maxWidth: .infinity, maxHeight: story ? .infinity : nil, alignment: .topLeading)
            .modifier(ClippingGround(frame: ctx.frame, scheme: s))
        }
    }
}

/// The paper is the clipping's ground: surface with dots (Card: to every edge; a Story card: rounded).
private struct ClippingGround: ViewModifier {
    let frame: ShareFrame
    let scheme: CoverScheme

    func body(content: Content) -> some View {
        switch frame {
        case .card:
            content.padding(.horizontal, 24).padding(.top, 28).padding(.bottom, 24)
                .background { ZStack { scheme.surface; ShareDotPaper(dot: scheme.outlineVariant) } }
        case .storyCard:
            content.padding(24)
                .background { ZStack { scheme.surface; ShareDotPaper(dot: scheme.outlineVariant) } }
                .clipShape(RoundedRectangle(cornerRadius: ShareMetrics.panel, style: .continuous))
        case .poster:
            content
        }
    }
}

/// The ticket stub beside the clipping's cover: title > artist > time (owner 2026-11-25) — the title larger, the
/// time last, lighter, narrower and muted.
private struct TicketStub: View {
    let ctx: ShareCtx
    let note: ShareNote
    let story: Bool

    var body: some View {
        let s = ctx.scheme
        MaxWidthLayout(maxWidth: 190) {
            VStack(alignment: .leading, spacing: 0) {
                ctx.text(ctx.song.title, ctx.gsf(story ? 20 : 17, .semibold), size: story ? 20 : 17,
                         lineHeight: story ? 26 : 22, color: s.onSurface, lines: 1)
                if !ctx.song.artist.isBlank {
                    ctx.text(ctx.song.artist, ctx.gsf(story ? 14 : 13), size: story ? 14 : 13, lineHeight: 18,
                             color: s.onSurfaceVariant, lines: 1)
                }
                if let at = note.positionMs?.int64Value {
                    HStack(spacing: 3) {
                        YoinIcon("play_filled", size: 10).foregroundStyle(s.onSurfaceVariant.opacity(0.7))
                        Text(verbatim: YoinShareCards.shared.time(ms: at))
                            .font(ctx.narrow(story ? 13 : 12))
                            .tracking(-0.1)
                            .foregroundStyle(s.onSurfaceVariant.opacity(0.9))
                    }
                    .padding(.top, 3)
                }
            }
            .padding(.leading, 16)
            .padding(.trailing, 14)
            .padding(.vertical, 9)
            .background(s.surfaceContainerHigh, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        }
        .rotationEffect(.degrees(2))
        .padding(.bottom, 6)
        .offset(x: -10)
    }
}

/// Timeline: the note hanging from its moment on a wavy progress line.
private struct TimelineBody: View {
    let ctx: ShareCtx

    var body: some View {
        if let note = ctx.render.content.note, let at = note.positionMs?.int64Value,
           let fraction = YoinShareCards.shared.timelineFraction(positionMs: note.positionMs,
                                                                 durationMs: ctx.song.durationMs)?.floatValue {
            let f = CGFloat(fraction)
            VStack(alignment: .leading, spacing: 0) {
                ShareHead(ctx: ctx)
                CentredAtLayout(fraction: f) {
                    ShareTimeChip(ctx: ctx, text: YoinShareCards.shared.time(ms: at))
                }
                .frame(maxWidth: .infinity, minHeight: 34, maxHeight: 34, alignment: .topLeading)
                .padding(.top, 22)
                WaveLine(fraction: f, ink: ctx.colors.content)
                Ends(ctx: ctx, durationMs: ctx.song.durationMs).padding(.top, 2)
                // The note hangs from its moment: a bubble whose notch points at the marker.
                ShareNoteText(text: note.content, size: 17, weight: .medium, lineHeight: 1.5,
                              color: ctx.colors.content, lineWidth: noteLineWidth(ctx, inset: 84),
                              covers: ctx.render.mentionCovers)
                    .padding(.horizontal, 18)
                    .padding(.vertical, 16)
                    .background(NotchBubble(fraction: f).fill(ctx.colors.content.opacity(0.09)))
                    .padding(.top, 16)
                ShareFoot(ctx: ctx, sign: noteSign(ctx, note)).padding(.top, 24)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .frameGround(ctx.frame, ctx.colors.card)
        }
    }
}

/// A rounded bubble with a notch above it at [fraction] of its width (kept 18 in from the ends).
private struct NotchBubble: Shape {
    var fraction: CGFloat

    func path(in rect: CGRect) -> Path {
        let notch: CGFloat = 8
        let x = min(max(rect.minX + rect.width * fraction, rect.minX + 18), rect.maxX - 18)
        var p = Path(roundedRect: rect, cornerRadius: 16, style: .continuous)
        p.move(to: CGPoint(x: x - notch, y: rect.minY))
        p.addLine(to: CGPoint(x: x, y: rect.minY - notch))
        p.addLine(to: CGPoint(x: x + notch, y: rect.minY))
        p.closeSubpath()
        return p
    }
}

/// Yoin's wavy progress: the played part a sine, the rest a flat track, a short bar at the moment.
private struct WaveLine: View {
    var fraction: CGFloat
    var ink: Color

    var body: some View {
        Canvas { gc, size in
            let mid = size.height / 2, amp: CGFloat = 3.2, wavelength: CGFloat = 16, stroke: CGFloat = 4
            let x = size.width * fraction, gap: CGFloat = 6
            var wave = Path()
            wave.move(to: CGPoint(x: 0, y: mid))
            var px: CGFloat = 0
            while px <= x - gap {
                wave.addLine(to: CGPoint(x: px, y: mid + amp * sin(px / wavelength * 2 * .pi)))
                px += 1
            }
            gc.stroke(wave, with: .color(ink), style: StrokeStyle(lineWidth: stroke, lineCap: .round))
            if x + gap < size.width {
                var rest = Path()
                rest.move(to: CGPoint(x: x + gap, y: mid))
                rest.addLine(to: CGPoint(x: size.width, y: mid))
                gc.stroke(rest, with: .color(ink.opacity(0.24)), style: StrokeStyle(lineWidth: stroke, lineCap: .round))
            }
            gc.fill(Path(roundedRect: CGRect(x: x - 2, y: mid - 12, width: 4, height: 24), cornerRadius: 2),
                    with: .color(ink))
        }
        .frame(height: 32)
    }
}

extension String {
    /// Kotlin's isBlank: empty or whitespace only.
    fileprivate var isBlank: Bool { allSatisfy(\.isWhitespace) }
}
