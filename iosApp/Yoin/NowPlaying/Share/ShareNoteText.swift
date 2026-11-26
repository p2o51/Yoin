import SwiftUI
import UIKit
import YoinShared

/// A note on a share image (Android ShareNoteText → NoteMarkupText, interactive = false): headings, lists,
/// to-dos, bold, italic, and its resolved mentions as pills of the ink with their covers. Type is fixed — the
/// image is laid out at font scale 1, whatever the reader's text size — and nothing takes a touch. Mentions are
/// glued into one unbreakable pill, cut to [lineWidth] with an ellipsis when longer than a line.
struct ShareNoteText: View {
    var text: String
    var size: CGFloat
    var weight: Font.Weight
    /// Android's line height, × the size.
    var lineHeight: CGFloat
    var color: Color
    /// The line a pill must fit (nil: never cut).
    var lineWidth: CGFloat?
    /// Mention covers by link (loaded before the image draws).
    var covers: [String: UIImage]

    var body: some View {
        let built = Self.compose(text, size: size, weight: weight, color: color, lineWidth: lineWidth)
        let images = built.pills.reduce(into: [Int: Image]()) { out, pill in
            if let image = covers[pill.mention.link] { out[pill.ordinal] = Image(uiImage: image) }
        }
        built.text
            .textRenderer(MentionPillRenderer(covers: images, pressed: nil, hits: MentionHitBox()))
            .lineSpacing(YoinType.lineSpacing(size: size, lineHeight: size * lineHeight))
            .padding(.vertical, YoinType.lineSpacing(size: size, lineHeight: size * lineHeight) / 2)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityLabel(NoteMarkupRender.words(text))
    }

    /// The fixed-size font the image draws: Google Sans Flex at [size] / [weight], italic slanted (no Dynamic Type).
    static func font(size: CGFloat, weight: Font.Weight, italic: Bool) -> Font {
        let upright = YoinFont.staticUIFont(size: size, weight: weight)
        guard italic else { return Font(upright) }
        let slant = CGAffineTransform(a: 1, b: 0, c: tan(NoteMarkupRender.italicSlant * .pi / 180), d: 1, tx: 0, ty: 0)
        return Font(UIFont(descriptor: upright.fontDescriptor.withMatrix(slant), size: size))
    }

    /// The note's runs and pills as one Text (NoteMarkupRender.pieces at a fixed size): each pill's characters
    /// carry [MentionPillAttribute] for the renderer.
    @MainActor
    static func compose(_ text: String, size base: CGFloat, weight: Font.Weight, color: Color,
                        lineWidth: CGFloat?) -> (text: Text, pills: [NoteMentionPill]) {
        var parts = LocalizedStringKey.StringInterpolation(literalCapacity: 0, interpolationCount: 8)
        var pills: [NoteMentionPill] = []
        var pending = AttributedString()
        func plain(_ s: String, size: CGFloat) -> AttributedString {
            var a = AttributedString(s)
            a.font = font(size: size, weight: weight, italic: false)
            a.foregroundColor = color
            return a
        }
        func flush() {
            if !pending.characters.isEmpty { parts.appendInterpolation(Text(pending)) }
            pending = AttributedString()
        }
        let style = NoteMarkupStyle(size: base, weight: weight, mentionColor: color, glyphColor: color)
        var ordinal = 0
        for (index, line) in YoinNoteReader.shared.read(text: text).enumerated() {
            if index > 0 { pending += plain("\n", size: base) }
            var size = base
            var lineWeight = weight
            switch line.kind {
            case .heading:
                size = base * (line.level == 1 ? 1.15 : 1.05)
                lineWeight = .bold
            case .bullet:
                pending += plain(NoteMarkupRender.bullet, size: base)
            case .checklist:
                pending += plain(line.checked ? NoteMarkupRender.done : NoteMarkupRender.todo, size: base)
            default:
                break
            }
            for run in line.runs {
                if let mention = run.mention, NoteMentions.shared.draw(mention, links: false) != .plain {
                    flush()
                    let pill = NoteMentionPill(ordinal: ordinal, mention: mention, size: size, italic: run.italic,
                                               link: nil)
                    let pillFont = YoinFont.staticUIFont(size: size, weight: NoteMarkupRender.mentionWeight)
                    var piece = pill.run(scaled: size, lineWidth: lineWidth,
                                         measure: { ($0 as NSString).size(withAttributes: [.font: pillFont]).width },
                                         style: style)
                    piece.font = Font(pillFont)
                    parts.appendInterpolation(Text(piece).customAttribute(
                        MentionPillAttribute(ordinal: ordinal, scaled: size, kind: MentionKindToken(mention.kind),
                                             tint: color)))
                    pills.append(pill)
                    ordinal += 1
                    continue
                }
                var piece = AttributedString(run.text)
                piece.font = font(size: size, weight: run.bold ? .bold : lineWeight, italic: run.italic)
                piece.foregroundColor = color
                if line.kind == .checklist && line.checked { piece.strikethroughStyle = Text.LineStyle.single }
                pending += piece
            }
        }
        flush()
        return (Text(LocalizedStringKey(stringInterpolation: parts)), pills)
    }
}
