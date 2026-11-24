import SwiftUI
import UIKit
import YoinShared

/*
 * Note text as Yoin draws it (Android ui/component/NoteMarkupText.kt, notes revamp phase A): headings, list
 * items, to-dos, bold, italic and @ mentions, read from the stored text by :shared's NoteMarkup (through
 * YoinNoteReader). The stored text is never Markdown to SwiftUI: the AttributedString is built here, run by
 * run, so `Text` parses nothing. A mention shows its name: in the mention colour, SemiBold, tappable, when
 * it resolves for the account in use (NoteMentions); as plain words in the text's own style when it doesn't
 * (owner 2026-11-22: not grey, not a link). Editing stays plain text for now: the fields edit the stored
 * text as it is.
 */

/// How one mention is drawn.
enum NoteMentionDraw: Equatable {
    /// Unresolved: its name as plain words.
    case plain
    /// Resolved but not tappable here (a glance window, a card that is one button).
    case styled
    /// Resolved: a link the view's `openURL` takes (the mention's `yoin:` link).
    case linked(URL)
}

/// The type and colours a note is drawn in.
struct NoteMarkupStyle {
    var size: CGFloat
    var weight: Font.Weight = .regular
    var mentionColor: Color
    /// The ☐ / ☑ boxes (Android glyphStyle: primary).
    var glyphColor: Color
    /// The serif face (All Memories' note cards, Android YoinSerifTitle) instead of Google Sans Flex.
    var serif = false
}

@MainActor
enum NoteMarkupRender {
    /// Android NoteMarkupGlyphs: what read-only text draws for list items and to-dos.
    static let bullet = "•  "
    /// U+FE0E: the boxes as text, not the ☑️ emoji iOS would otherwise draw.
    static let todo = "☐\u{FE0E}  "
    static let done = "☑\u{FE0E}  "
    /// Android noteMentionStyle: a touch heavier than the words around it.
    static let mentionWeight: Font.Weight = .semibold
    /// Android's synthesized italic slant (Google Sans Flex has no italic face).
    static let italicSlant: CGFloat = 12

    /// [text] as lines, read once per text (notes redraw often: the playhead, a highlight).
    static func lines(_ text: String) -> [YoinNoteLine] {
        if let hit = cache[text] { return hit }
        let read = YoinNoteReader.shared.read(text: text)
        if cache.count >= 512 { cache.removeAll(keepingCapacity: true) }
        cache[text] = read
        return read
    }

    private static var cache: [String: [YoinNoteLine]] = [:]

    /// The words alone (mentions as names, no marks): accessibility labels, Home's excerpts.
    nonisolated static func words(_ text: String) -> String { YoinNoteReader.shared.plainText(text: text) }

    /// [text] drawn: every run carries its own font and colour; `draw` says how each mention looks.
    static func attributed(_ text: String, style: NoteMarkupStyle,
                           draw: (YoinNoteMention) -> NoteMentionDraw) -> AttributedString {
        var out = AttributedString()
        for (index, line) in lines(text).enumerated() {
            if index > 0 { out += plain("\n", style) }
            let kind = line.kind
            var size = style.size
            var lineWeight = style.weight
            if kind == .heading {
                // Android: Bold, 1.15 em for #, 1.05 em for ## / ###.
                size = style.size * (line.level == 1 ? 1.15 : 1.05)
                lineWeight = .bold
            } else if kind == .bullet {
                out += plain(bullet, style)
            } else if kind == .checklist {
                var glyph = plain(line.checked ? done : todo, style)
                glyph.foregroundColor = style.glyphColor
                out += glyph
            }
            for run in line.runs {
                var weight = run.bold ? .bold : lineWeight
                var piece = AttributedString(run.text)
                if let mention = run.mention {
                    switch draw(mention) {
                    case .plain:
                        break
                    case .styled:
                        weight = mentionWeight
                        piece.foregroundColor = style.mentionColor
                    case .linked(let url):
                        weight = mentionWeight
                        piece.foregroundColor = style.mentionColor
                        piece.link = url
                    }
                }
                piece.font = font(size: size, weight: weight, italic: run.italic, serif: style.serif)
                if kind == .checklist && line.checked { piece.strikethroughStyle = Text.LineStyle.single }
                out += piece
            }
        }
        return out
    }

    private static func plain(_ s: String, _ style: NoteMarkupStyle) -> AttributedString {
        var a = AttributedString(s)
        a.font = font(size: style.size, weight: style.weight, italic: false, serif: style.serif)
        return a
    }

    /// Google Sans Flex at [size] / [weight] (Dynamic Type scaled like `.gsf`); italic is slanted.
    static func font(size: CGFloat, weight: Font.Weight, italic: Bool, serif: Bool = false) -> Font {
        if serif {
            let face = Font.system(size: size, weight: weight, design: .serif)
            return italic ? face.italic() : face
        }
        guard italic else { return .gsf(size, weight) }
        let key = "\(size)|\(weight)|\(UIApplication.shared.preferredContentSizeCategory.rawValue)"
        if let hit = italicCache[key] { return hit }
        let scaled = UIFontMetrics(forTextStyle: .body).scaledValue(for: size)
        let upright = YoinFont.staticUIFont(size: scaled, weight: weight)
        let slant = CGAffineTransform(a: 1, b: 0, c: tan(italicSlant * .pi / 180), d: 1, tx: 0, ty: 0)
        let font = Font(UIFont(descriptor: upright.fontDescriptor.withMatrix(slant), size: scaled))
        italicCache[key] = font
        return font
    }

    private static var italicCache: [String: Font] = [:]
}

/// A note, song note or review drawn from its stored text (Android NoteMarkupText). Resolved mentions are
/// links unless [links] is off; a tap on one opens what it names (NoteMentions), never the system.
struct NoteMarkupText: View {
    var text: String
    var size: CGFloat
    var weight: Font.Weight = .regular
    var mentionColor: Color
    var glyphColor: Color
    /// False: mentions are drawn but take no tap (the host takes no touches).
    var links = true
    var serif = false

    var body: some View {
        let mentions = NoteMentions.shared
        let style = NoteMarkupStyle(size: size, weight: weight, mentionColor: mentionColor, glyphColor: glyphColor,
                                    serif: serif)
        Text(NoteMarkupRender.attributed(text, style: style) { mentions.draw($0, links: links) })
            .tint(mentionColor)
            .environment(\.openURL, OpenURLAction { url in mentions.handle(url, in: text) })
    }
}
