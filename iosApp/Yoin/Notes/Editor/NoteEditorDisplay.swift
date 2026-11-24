import UIKit
import YoinShared

/*
 * What the note editor's text view holds for a stored text (Android NoteEditorDisplay, an output
 * transformation there): the same characters, except that each mention link is one attachment character
 * (U+FFFC) drawn as an "@Title" chip, so the caret can't enter it and a backspace takes it whole. Markdown
 * stays as typed: its markers are drawn faint, the words they wrap bold / italic / heading-sized. The stored
 * text comes back from the text view by putting each chip's link back ([stored(from:)]).
 */

extension NSAttributedString.Key {
    /// On a chip's attachment character: the mention's stored link (`[@Title](yoin:…)`).
    static let yoinNoteMentionLink = NSAttributedString.Key("yoinNoteMentionLink")
}

/// Where the chips sit: stored-text offsets ↔ text-view offsets (both UTF-16).
struct NoteEditorDisplayMap: Equatable {
    struct Span: Equatable {
        /// The link in the stored text.
        var stored: Range<Int>
        /// The chip's character in the text view.
        var display: Int
    }

    var spans: [Span] = []

    /// A stored offset in the text view (one inside a link lands after its chip).
    func display(_ stored: Int) -> Int {
        var shift = 0
        for span in spans {
            if stored <= span.stored.lowerBound { break }
            if stored < span.stored.upperBound { return span.display + 1 }
            shift += span.stored.count - 1
        }
        return stored - shift
    }

    /// A text-view offset in the stored text (before a chip: the link's start; after it: its end).
    func stored(_ display: Int) -> Int {
        var shift = 0
        for span in spans {
            if display <= span.display { return display + shift }
            shift += span.stored.count - 1
        }
        return display + shift
    }

    func display(_ r: NSRange) -> NSRange {
        let a = display(r.location), b = display(r.location + r.length)
        return NSRange(location: a, length: max(0, b - a))
    }

    func stored(_ r: NSRange) -> NSRange {
        let a = stored(r.location), b = stored(r.location + r.length)
        return NSRange(location: a, length: max(0, b - a))
    }
}

/// The editor's type and colours.
struct NoteEditorStyle {
    var size: CGFloat
    var textColor: UIColor
    var mentionColor: UIColor
    /// The faint Markdown markers (Android: the marker symbols at reduced alpha).
    var markerAlpha: CGFloat = 0.4
    /// Extra leading between lines.
    var lineSpacing: CGFloat = 0
    /// The widest a chip draws (the text view's width); longer names end in "…".
    var maxChipWidth: CGFloat = 320

    var font: UIFont { YoinFont.staticUIFont(size: UIFontMetrics(forTextStyle: .body).scaledValue(for: size), weight: .regular) }
}

@MainActor
enum NoteEditorDisplay {
    static let attachmentChar = "\u{FFFC}"

    /// The text view's text for [stored], and where its chips sit.
    static func build(_ stored: String, style: NoteEditorStyle) -> (NSAttributedString, NoteEditorDisplayMap) {
        let source = stored as NSString
        let spans = YoinNoteEditor.shared.mentionSpans(text: stored)
        let out = NSMutableAttributedString()
        var map = NoteEditorDisplayMap()
        var cursor = 0
        let base = baseAttributes(style)
        for span in spans {
            let start = Int(span.start), end = Int(span.end)
            guard start >= cursor, end <= source.length else { continue }
            out.append(NSAttributedString(string: source.substring(with: NSRange(location: cursor, length: start - cursor)),
                                          attributes: base))
            map.spans.append(.init(stored: start..<end, display: out.length))
            out.append(chip(span.mention, link: source.substring(with: NSRange(location: start, length: end - start)),
                            style: style))
            cursor = end
        }
        out.append(NSAttributedString(string: source.substring(from: cursor), attributes: base))
        restyle(out, stored: stored, map: map, style: style)
        return (out, map)
    }

    /// The stored text a text view holds: each chip's link back in its place.
    static func stored(from display: NSAttributedString) -> String {
        var out = ""
        let s = display.string as NSString
        display.enumerateAttribute(.yoinNoteMentionLink, in: NSRange(location: 0, length: display.length)) { value, range, _ in
            guard let link = value as? String else { return out += s.substring(with: range) }
            // One link per chip character (two chips side by side share a run); a typed character that picked
            // the attribute up beside a chip is itself.
            for i in 0..<range.length {
                let c = s.substring(with: NSRange(location: range.location + i, length: 1))
                out += c == attachmentChar ? link : c
            }
        }
        return out
    }

    /// The chips' places in a text view's text.
    static func map(of display: NSAttributedString) -> NoteEditorDisplayMap {
        var map = NoteEditorDisplayMap()
        var shift = 0
        display.enumerateAttribute(.yoinNoteMentionLink, in: NSRange(location: 0, length: display.length)) { value, range, _ in
            guard let link = value as? String else { return }
            let n = (link as NSString).length
            let chars = display.string as NSString
            for i in 0..<range.length {
                let at = range.location + i
                guard chars.substring(with: NSRange(location: at, length: 1)) == attachmentChar else { continue }
                map.spans.append(.init(stored: (at + shift)..<(at + shift + n), display: at))
                shift += n - 1
            }
        }
        return map
    }

    /// Fonts and colours from the Markdown, over the characters as they are (a keyboard edit's text keeps its
    /// characters; only how they look follows the stored text).
    static func restyle(_ text: NSMutableAttributedString, stored: String, map: NoteEditorDisplayMap, style: NoteEditorStyle) {
        let whole = NSRange(location: 0, length: text.length)
        guard whole.length > 0 else { return }
        text.beginEditing()
        defer { text.endEditing() }
        // The chips keep their own attributes; everything else starts plain.
        var plain: [NSRange] = []
        var last = 0
        for span in map.spans where span.display < text.length {
            if span.display > last { plain.append(NSRange(location: last, length: span.display - last)) }
            last = span.display + 1
        }
        if last < text.length { plain.append(NSRange(location: last, length: text.length - last)) }
        let base = baseAttributes(style)
        for r in plain { text.setAttributes(base, range: r) }
        let faint = style.textColor.withAlphaComponent(style.markerAlpha)
        func faintRange(_ a: Int, _ b: Int) {
            let r = map.display(NSRange(location: a, length: b - a))
            if r.length > 0, r.location + r.length <= text.length { text.addAttribute(.foregroundColor, value: faint, range: r) }
        }
        func font(_ a: Int, _ b: Int, _ transform: (UIFont) -> UIFont) {
            let r = map.display(NSRange(location: a, length: b - a))
            guard r.length > 0, r.location + r.length <= text.length else { return }
            text.enumerateAttribute(.font, in: r) { value, sub, _ in
                guard let f = value as? UIFont, text.attribute(.yoinNoteMentionLink, at: sub.location, effectiveRange: nil) == nil
                else { return }
                text.addAttribute(.font, value: transform(f), range: sub)
            }
        }
        let headingFont: (Int) -> (UIFont) -> UIFont = { level in
            { f in YoinFont.staticUIFont(size: f.pointSize * (level == 1 ? 1.15 : 1.05), weight: .bold) }
        }
        for line in YoinNoteEditor.shared.layout(text: stored) {
            let start = Int(line.start), content = Int(line.contentStart), end = Int(line.end)
            switch line.kind {
            case .heading:
                faintRange(start, content)
                font(content, end, headingFont(Int(line.level)))
            case .bullet, .checklist:
                faintRange(start, content)
                if line.kind == .checklist && line.checked {
                    let r = map.display(NSRange(location: content, length: end - content))
                    if r.length > 0, r.location + r.length <= text.length {
                        text.addAttribute(.strikethroughStyle, value: NSUnderlineStyle.single.rawValue, range: r)
                    }
                }
            default:
                break
            }
            for inline in line.inlines where inline.kind != .mention {
                let a = Int(inline.start), ia = Int(inline.innerStart), ib = Int(inline.innerEnd), b = Int(inline.end)
                faintRange(a, ia)
                faintRange(ib, b)
                if inline.kind == .bold {
                    font(ia, ib) { YoinFont.staticUIFont(size: $0.pointSize, weight: .bold).withTraits(of: $0, keepSlant: true) }
                } else {
                    font(ia, ib) { $0.slanted() }
                }
            }
        }
    }

    static func baseAttributes(_ style: NoteEditorStyle) -> [NSAttributedString.Key: Any] {
        let paragraph = NSMutableParagraphStyle()
        paragraph.lineSpacing = style.lineSpacing
        return [.font: style.font, .foregroundColor: style.textColor, .paragraphStyle: paragraph]
    }

    /// One mention as a chip: "@Title" in the mention colour on a 12 % pill of it, one character wide in the text.
    static func chip(_ mention: YoinNoteMention, link: String, style: NoteEditorStyle) -> NSAttributedString {
        let attachment = NSTextAttachment()
        let font = style.font
        let image = chipImage(title: mention.title, font: font, color: style.mentionColor, maxWidth: style.maxChipWidth)
        attachment.image = image
        // Centred on the line's x-height, like the words around it.
        let y = (font.capHeight - image.size.height) / 2
        attachment.bounds = CGRect(x: 0, y: y.rounded(), width: image.size.width, height: image.size.height)
        let s = NSMutableAttributedString(attachment: attachment)
        let r = NSRange(location: 0, length: s.length)
        var attributes = baseAttributes(style)
        attributes[.yoinNoteMentionLink] = link
        s.addAttributes(attributes, range: r)
        return s
    }

    private static var chipCache: [String: UIImage] = [:]

    static func chipImage(title: String, font: UIFont, color: UIColor, maxWidth: CGFloat) -> UIImage {
        let key = "\(title)|\(font.pointSize)|\(color.hashValue)|\(Int(maxWidth))|\(UITraitCollection.current.displayScale)"
        if let hit = chipCache[key] { return hit }
        let bold = YoinFont.staticUIFont(size: font.pointSize, weight: .semibold)
        let paragraph = NSMutableParagraphStyle()
        paragraph.lineBreakMode = .byTruncatingTail
        let attrs: [NSAttributedString.Key: Any] = [.font: bold, .foregroundColor: color, .paragraphStyle: paragraph]
        let label = "@" + title
        let padH: CGFloat = 6, padV: CGFloat = 1
        let textSize = (label as NSString).size(withAttributes: attrs)
        let height = ceil(bold.lineHeight + padV * 2)
        let width = min(ceil(textSize.width + padH * 2), max(maxWidth, height * 2))
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: width, height: height))
        let image = renderer.image { _ in
            let rect = CGRect(x: 0, y: 0, width: width, height: height)
            color.withAlphaComponent(0.12).setFill()
            UIBezierPath(roundedRect: rect, cornerRadius: height / 2).fill()
            (label as NSString).draw(with: rect.insetBy(dx: padH, dy: padV),
                                     options: [.usesLineFragmentOrigin, .truncatesLastVisibleLine], attributes: attrs,
                                     context: nil)
        }
        if chipCache.count > 256 { chipCache.removeAll(keepingCapacity: true) }
        chipCache[key] = image
        return image
    }
}

@MainActor
extension UIFont {
    /// Google Sans Flex has no italic face: the same slant as read-only notes (NoteMarkupRender.italicSlant).
    func slanted() -> UIFont {
        let slant = CGAffineTransform(a: 1, b: 0, c: tan(NoteMarkupRender.italicSlant * .pi / 180), d: 1, tx: 0, ty: 0)
        return UIFont(descriptor: fontDescriptor.withMatrix(slant), size: pointSize)
    }

    /// [self] carrying [other]'s slant when it has one (bold words inside italic ones).
    func withTraits(of other: UIFont, keepSlant: Bool) -> UIFont {
        guard keepSlant, other.fontDescriptor.matrix != .identity else { return self }
        return slanted()
    }
}
